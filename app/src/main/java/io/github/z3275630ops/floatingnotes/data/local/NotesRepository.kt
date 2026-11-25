package io.github.z3275630ops.floatingnotes.data.local

import androidx.room.withTransaction
import io.github.z3275630ops.floatingnotes.core.model.NoteBlock
import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import io.github.z3275630ops.floatingnotes.core.model.newId
import io.github.z3275630ops.floatingnotes.data.backup.BackupConstants
import io.github.z3275630ops.floatingnotes.data.backup.NoteRevisionSummary
import io.github.z3275630ops.floatingnotes.data.backup.TrashPurgeImpact
import io.github.z3275630ops.floatingnotes.data.backup.VersionDiff
import io.github.z3275630ops.floatingnotes.data.backup.VersionDiffPreview
import io.github.z3275630ops.floatingnotes.feature.tasks.TaskRecurrence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoUnit

internal class StaleDraftException : IllegalStateException("The draft was superseded")
internal class EmptyNoteException : IllegalArgumentException("The note is blank")

internal sealed interface RestoreVersionResult {
    data class Restored(
        val note: NoteEntity,
        val previousRevision: Long,
        val referencedAttachmentIds: List<String>,
        val reminderSync: TimeReconcileResult,
    ) : RestoreVersionResult

    data class Conflict(val actualRevision: Long) : RestoreVersionResult
    data object NotFound : RestoreVersionResult
    data object Trashed : RestoreVersionResult
}

internal data class PermanentDeleteResult(
    val deletedNoteIds: List<String>,
    val cancelledReminderIds: List<String>,
)

internal class NotesRepository(private val database: NotesDatabase) {
    private val dao = database.notes()
    private val taskDao = database.taskReminders()
    val notes = dao.observeNotes()
    val trashedNotes = dao.observeTrashedNotes()
    val trashedNoteIds = dao.observeTrashedNoteIds()
    val captureDrafts = dao.observeCaptureDrafts()

    fun observeTaskItems(): Flow<List<TaskItemProjection>> = taskDao.observeTaskRows().map { rows ->
        val decodedDocs = HashMap<String, NoteDocument?>()
        rows.mapNotNull { row ->
            val doc = decodedDocs.getOrPut(row.noteId) {
                runCatching { NoteDocument.decode(row.noteDocumentJson) }.getOrNull()
            } ?: return@mapNotNull null
            val block = doc.findBlock(row.blockId)?.takeIf { it.type == "checkbox" } ?: return@mapNotNull null
            val activeReminder = row.reminderStatus != ReminderStatus.CANCELLED.name
            TaskItemProjection(
                noteId = row.noteId,
                blockId = row.blockId,
                text = block.text,
                checked = block.checked,
                dueDate = block.dueDate ?: row.indexDueDate,
                occurrenceId = block.occurrenceId ?: row.indexOccurrenceId,
                noteTitle = row.noteTitle,
                notePreview = notePreview(row.noteTitle, row.noteSearchText),
                noteArchived = row.noteArchivedAt != null,
                noteUpdatedAt = row.noteUpdatedAt,
                reminderId = if (activeReminder) row.reminderId ?: block.reminderId else null,
                reminderZoneId = if (activeReminder) row.reminderZoneId else null,
                reminderLocalDate = if (activeReminder) row.reminderLocalDate else null,
                reminderLocalTime = if (activeReminder) row.reminderLocalTime else null,
                triggerInstantMs = if (activeReminder) row.triggerInstantMs else null,
                policy = if (activeReminder) ReminderPolicy.fromStored(row.reminderPolicy) else ReminderPolicy.FOLLOW_LOCAL,
                reminderStatus = if (activeReminder) row.reminderStatus?.let { ReminderStatus.fromStored(it) } else null,
                recurrence = if (activeReminder) RecurrenceRule.fromStored(row.reminderRecurrence) else RecurrenceRule.NONE,
                snoozedUntilMs = if (activeReminder) row.snoozedUntilMs else null,
                missedSummarized = if (activeReminder) row.missedSummarized ?: false else false,
            )
        }
    }.flowOn(Dispatchers.Default)

    fun observeCompletedOccurrences(): Flow<List<TaskOccurrenceEntity>> = taskDao.observeCompletedOccurrences()

    suspend fun activeReminders(): List<ReminderEntity> = taskDao.activeReminders()

    suspend fun loadNote(id: String): NoteEntity? = dao.note(id)
    suspend fun loadDraft(slot: String): DraftEntity? = dao.draft(slot)
    suspend fun countActiveNotes(): Int = dao.countActiveNotes()

    fun newDraft(slot: String, note: NoteEntity? = null): DraftEntity = DraftEntity(
        id = newId(),
        slot = slot,
        noteId = note?.id ?: newId(),
        baseRevision = note?.revision ?: 0,
        documentJson = note?.documentJson ?: NoteDocument.empty().encode(),
        editSeq = 0,
        updatedAt = now(),
        sourceType = note?.sourceType ?: "manual",
        sourceUrl = note?.sourceUrl,
    )

    suspend fun checkpoint(draft: DraftEntity) = database.withTransaction {
        val note = dao.note(draft.noteId)
        if (note?.lastDraftId == draft.id && note.committedSeq >= draft.editSeq) return@withTransaction
        verifyRevision(draft, note)
        val stored = dao.draft(draft.slot)
        if (stored != null && stored.id != draft.id) throw StaleDraftException()
        if (stored != null && stored.editSeq == draft.editSeq && stored.documentJson != draft.documentJson) {
            throw StaleDraftException()
        }
        if (stored == null || draft.editSeq > stored.editSeq) dao.putDraft(draft)
    }

    suspend fun discard(draft: DraftEntity) = dao.deleteDraft(draft.id, draft.editSeq)

    suspend fun commit(draft: DraftEntity): NoteEntity = database.withTransaction {
        val previous = dao.note(draft.noteId)
        if (previous != null && previous.lastDraftId == draft.id && previous.committedSeq >= draft.editSeq) {
            if (previous.committedSeq == draft.editSeq && previous.documentJson != draft.documentJson) {
                throw StaleDraftException()
            }
            return@withTransaction previous
        }

        verifyRevision(draft, previous)
        val stored = dao.draft(draft.slot)
        if (stored != null && (stored.id != draft.id || stored.editSeq > draft.editSeq)) throw StaleDraftException()
        val initialDocument = NoteDocument.decode(draft.documentJson)
        if (!initialDocument.hasContent) throw EmptyNoteException()

        val timeInstant = Instant.now()
        val time = timestamp.format(timeInstant)
        val zoneId = ZoneId.systemDefault()
        val previousBlocks = previous?.let {
            runCatching { NoteDocument.decode(it.documentJson).blocks.associateBy(NoteBlock::id) }.getOrNull()
        }.orEmpty()
        val existingReminders = taskDao.remindersForNote(draft.noteId).associateBy(ReminderEntity::blockId)

        val advancedBlockIds = mutableSetOf<String>()
        var document = initialDocument
        for (block in initialDocument.blocks) {
            if (block.type != "checkbox") continue
            val wasChecked = previousBlocks[block.id]?.checked == true
            val reminder = existingReminders[block.id]
            if (
                block.checked &&
                !wasChecked &&
                reminder != null &&
                reminder.recurrenceEnum != RecurrenceRule.NONE &&
                reminder.statusEnum != ReminderStatus.CANCELLED
            ) {
                val advanced = advanceRecurringBlock(
                    noteId = draft.noteId,
                    block = block,
                    reminder = reminder,
                    zoneId = zoneId,
                    timeStr = time,
                )
                document = document.replaceBlock(block.id) { advanced.updatedBlock }
                advancedBlockIds.add(block.id)
            }
        }
        val finalJson = if (advancedBlockIds.isEmpty()) draft.documentJson else document.encode()

        val updated = if (previous == null) {
            NoteEntity(
                id = draft.noteId,
                title = null,
                documentJson = finalJson,
                searchText = document.text,
                mediaMask = document.mediaMask(),
                sourceType = draft.sourceType,
                sourceUrl = draft.sourceUrl,
                createdAt = time,
                updatedAt = time,
                revision = 1,
                contentHash = hash(finalJson),
                lastDraftId = draft.id,
                committedSeq = draft.editSeq,
            )
        } else {
            previous.copy(
                documentJson = finalJson,
                searchText = document.text,
                mediaMask = document.mediaMask(),
                updatedAt = time,
                revision = previous.revision + 1,
                contentHash = hash(finalJson),
                lastDraftId = draft.id,
                committedSeq = draft.editSeq,
            )
        }
        writeRevision(updated, "edit")
        syncTasksForDocument(
            updated = updated,
            previous = previous,
            document = document,
            timeStr = time,
            zoneId = zoneId,
            now = timeInstant,
            skipOccurrenceIds = advancedBlockIds,
        )
        database.organization().clearLinks(updated.id)
        database.organization().insertLinks(document.linksFrom(updated.id))
        dao.deleteDraft(draft.id, draft.editSeq)
        updated
    }

    suspend fun toggleTaskChecked(
        noteId: String,
        blockId: String,
        checked: Boolean,
        zoneId: ZoneId = ZoneId.systemDefault(),
        now: Instant = Instant.now(),
    ): TaskToggleResult = database.withTransaction {
        toggleTaskCheckedLocked(noteId, blockId, checked, zoneId, now)
    }

    suspend fun completeFromNotification(
        noteId: String,
        blockId: String,
        expectedReminderId: String? = null,
        expectedOccurrenceId: String? = null,
        expectedRevision: Long? = null,
        zoneId: ZoneId = ZoneId.systemDefault(),
        now: Instant = Instant.now(),
    ): TaskToggleResult = database.withTransaction {
        val note = dao.note(noteId)?.takeIf { it.deletedAt == null } ?: return@withTransaction TaskToggleResult.NotFound
        val document = NoteDocument.decode(note.documentJson)
        val block = document.findBlock(blockId)?.takeIf { it.type == "checkbox" }
            ?: return@withTransaction TaskToggleResult.BlockNotFound
        val reminder = taskDao.reminderForBlock(noteId, blockId)
        if (expectedReminderId != null && reminder != null && reminder.id != expectedReminderId) {
            return@withTransaction TaskToggleResult.Updated(note, reminder)
        }
        if (expectedOccurrenceId != null && block.occurrenceId != null && block.occurrenceId != expectedOccurrenceId) {
            return@withTransaction TaskToggleResult.Updated(note, reminder)
        }
        if (expectedRevision != null && expectedRevision > 0L && reminder != null && reminder.revision != expectedRevision) {
            return@withTransaction TaskToggleResult.Updated(note, reminder)
        }
        if (block.checked) {
            return@withTransaction TaskToggleResult.Updated(note, reminder)
        }
        toggleTaskCheckedLocked(noteId, blockId, checked = true, zoneId = zoneId, now = now)
    }

    suspend fun setTaskSchedule(
        noteId: String,
        blockId: String,
        dueDate: String?,
        reminderTime: String? = null,
        zoneId: ZoneId = ZoneId.systemDefault(),
        policy: ReminderPolicy = ReminderPolicy.FOLLOW_LOCAL,
        recurrence: RecurrenceRule = RecurrenceRule.NONE,
        now: Instant = Instant.now(),
        reminderDate: String? = null,
    ): TaskScheduleResult = database.withTransaction {
        val note = dao.note(noteId)?.takeIf { it.deletedAt == null }
            ?: throw IllegalStateException("Missing note $noteId")
        val document = NoteDocument.decode(note.documentJson)
        val block = document.findBlock(blockId)?.takeIf { it.type == "checkbox" }
            ?: throw IllegalStateException("Missing checkbox block $blockId")
        val timeStr = timestamp.format(now)
        val parsedDueDate = TaskRecurrence.parseDueDate(dueDate)
        val parsedReminderDate = TaskRecurrence.parseDueDate(reminderDate)
        val parsedTime = TaskRecurrence.parseLocalTime(reminderTime)
        val existingReminder = taskDao.reminderForBlock(noteId, blockId)
        var savedReminder: ReminderEntity? = null
        var cancelledId: String? = null

        val updatedBlock = if (parsedTime != null) {
            val effectiveDate = parsedReminderDate ?: parsedDueDate ?: LocalDate.now(zoneId)
            val reminderId = existingReminder?.id ?: newId()
            val occurrenceId = block.occurrenceId ?: existingReminder?.occurrenceId ?: newId()
            val resolved = TaskRecurrence.resolveTrigger(effectiveDate, parsedTime, zoneId)
            val status = if (block.checked && recurrence == RecurrenceRule.NONE) {
                ReminderStatus.COMPLETED
            } else {
                ReminderStatus.ACTIVE
            }
            val entity = ReminderEntity(
                id = reminderId,
                noteId = noteId,
                blockId = blockId,
                occurrenceId = occurrenceId,
                zoneId = zoneId.id,
                localDate = effectiveDate.toString(),
                localTime = TaskRecurrence.formatLocalTime(parsedTime),
                triggerInstantMs = resolved.instant.toEpochMilli(),
                policy = policy.name,
                status = status.name,
                recurrence = recurrence.name,
                snoozedUntilMs = null,
                missedSummarized = false,
                revision = (existingReminder?.revision ?: 0L) + 1L,
                updatedAt = timeStr,
            )
            val scheduledBlock = block.withTaskSchedule(
                dueDate = parsedDueDate?.toString(),
                reminderId = reminderId,
                occurrenceId = occurrenceId,
            )
            if (block.checked && recurrence != RecurrenceRule.NONE) {
                val advanced = advanceRecurringBlock(
                    noteId = noteId,
                    block = scheduledBlock,
                    reminder = entity,
                    zoneId = zoneId,
                    timeStr = timeStr,
                )
                savedReminder = advanced.nextReminder
                advanced.updatedBlock
            } else {
                taskDao.upsertReminder(entity)
                savedReminder = entity
                scheduledBlock
            }
        } else {
            if (existingReminder != null && existingReminder.statusEnum != ReminderStatus.CANCELLED) {
                taskDao.cancelReminderForBlock(noteId, blockId, timeStr)
                cancelledId = existingReminder.id
            }
            block.withTaskSchedule(
                dueDate = parsedDueDate?.toString(),
                reminderId = null,
                occurrenceId = block.occurrenceId,
            )
        }

        val updatedDoc = document.replaceBlock(blockId) { updatedBlock }
        val updatedJson = updatedDoc.encode()
        val updatedNote = note.copy(
            documentJson = updatedJson,
            searchText = updatedDoc.text,
            mediaMask = updatedDoc.mediaMask(),
            updatedAt = timeStr,
            revision = note.revision + 1L,
            contentHash = hash(updatedJson),
            lastDraftId = newId(),
            committedSeq = 0L,
        )
        writeRevision(updatedNote, "task")
        replaceNoteTaskIndex(updatedNote.id, updatedDoc)
        TaskScheduleResult(updatedNote, savedReminder, cancelledId)
    }

    suspend fun clearTaskSchedule(noteId: String, blockId: String, now: Instant = Instant.now()): TaskScheduleResult =
        database.withTransaction {
            val note = dao.note(noteId)?.takeIf { it.deletedAt == null }
                ?: throw IllegalStateException("Missing note $noteId")
            val document = NoteDocument.decode(note.documentJson)
            val block = document.findBlock(blockId)?.takeIf { it.type == "checkbox" }
                ?: throw IllegalStateException("Missing checkbox block $blockId")
            val timeStr = timestamp.format(now)
            val existingReminder = taskDao.reminderForBlock(noteId, blockId)
            var cancelledId: String? = null
            if (existingReminder != null && existingReminder.statusEnum != ReminderStatus.CANCELLED) {
                taskDao.cancelReminderForBlock(noteId, blockId, timeStr)
                cancelledId = existingReminder.id
            }
            val cleared = block.withTaskSchedule(dueDate = null, reminderId = null, occurrenceId = null)
            val updatedDoc = document.replaceBlock(blockId) { cleared }
            val updatedJson = updatedDoc.encode()
            val updatedNote = note.copy(
                documentJson = updatedJson,
                searchText = updatedDoc.text,
                mediaMask = updatedDoc.mediaMask(),
                updatedAt = timeStr,
                revision = note.revision + 1L,
                contentHash = hash(updatedJson),
                lastDraftId = newId(),
                committedSeq = 0L,
            )
            writeRevision(updatedNote, "task")
            replaceNoteTaskIndex(updatedNote.id, updatedDoc)
            TaskScheduleResult(updatedNote, null, cancelledId)
        }

    suspend fun snoozeReminder(
        reminderId: String,
        snoozeMinutes: Long = 15L,
        now: Instant = Instant.now(),
        expectedOccurrenceId: String? = null,
        expectedRevision: Long? = null,
    ): ReminderEntity? = database.withTransaction {
        require(snoozeMinutes > 0L)
        val reminder = taskDao.reminder(reminderId) ?: return@withTransaction null
        if (reminder.statusEnum == ReminderStatus.CANCELLED || reminder.statusEnum == ReminderStatus.COMPLETED) {
            return@withTransaction null
        }
        if (expectedOccurrenceId != null && reminder.occurrenceId != null && reminder.occurrenceId != expectedOccurrenceId) {
            return@withTransaction null
        }
        if (expectedRevision != null && expectedRevision > 0L && reminder.revision != expectedRevision) {
            return@withTransaction null
        }
        val timeStr = timestamp.format(now)
        val note = dao.note(reminder.noteId)?.takeIf { it.deletedAt == null }
        val block = note?.let { NoteDocument.decode(it.documentJson).findBlock(reminder.blockId) }
        if (note == null || block == null || block.type != "checkbox" || block.checked) {
            if (reminder.statusEnum != ReminderStatus.CANCELLED) {
                taskDao.upsertReminder(
                    reminder.copy(
                        status = if (block?.checked == true) ReminderStatus.COMPLETED.name else ReminderStatus.CANCELLED.name,
                        snoozedUntilMs = null,
                        revision = reminder.revision + 1L,
                        updatedAt = timeStr,
                    ),
                )
            }
            return@withTransaction null
        }
        val targetMs = now.plusSeconds(snoozeMinutes * 60L).toEpochMilli()
        val updated = reminder.copy(
            triggerInstantMs = targetMs,
            snoozedUntilMs = targetMs,
            status = ReminderStatus.ACTIVE.name,
            missedSummarized = false,
            revision = reminder.revision + 1L,
            updatedAt = timeStr,
        )
        taskDao.upsertReminder(updated)
        updated
    }

    suspend fun reconcileTimeChange(
        currentZone: ZoneId = ZoneId.systemDefault(),
        now: Instant = Instant.now(),
    ): TimeReconcileResult = database.withTransaction {
        reconcileTimeChangeLocked(currentZone, now)
    }

    private suspend fun reconcileTimeChangeLocked(
        currentZone: ZoneId,
        now: Instant,
    ): TimeReconcileResult {
        val timeStr = timestamp.format(now)
        val nowMs = now.toEpochMilli()
        val active = mutableListOf<ReminderEntity>()
        val cancelled = mutableListOf<String>()
        var missedCount = 0

        for (reminder in taskDao.activeOrMissedReminders()) {
            val rawNote = dao.note(reminder.noteId)
            if (rawNote != null && rawNote.deletedAt != null) {
                cancelled.add(reminder.id)
                continue
            }
            val note = rawNote?.takeIf { it.deletedAt == null }
            val block = note?.let {
                runCatching { NoteDocument.decode(it.documentJson).findBlock(reminder.blockId) }.getOrNull()
            }?.takeIf { it.type == "checkbox" }

            if (note == null || block == null || block.checked) {
                val targetStatus = if (block?.checked == true) ReminderStatus.COMPLETED else ReminderStatus.CANCELLED
                taskDao.upsertReminder(
                    reminder.copy(
                        status = targetStatus.name,
                        snoozedUntilMs = null,
                        revision = reminder.revision + 1L,
                        updatedAt = timeStr,
                    ),
                )
                cancelled.add(reminder.id)
                continue
            }

            val localDate = TaskRecurrence.parseDueDate(reminder.localDate)
            val localTime = TaskRecurrence.parseLocalTime(reminder.localTime)
            val snoozed = reminder.snoozedUntilMs
            val (effectiveZoneId, resolvedMs) = if (snoozed != null) {
                reminder.zoneId to snoozed
            } else if (localDate != null && localTime != null) {
                val (zone, resolved) = TaskRecurrence.recomputeForZoneChange(
                    localDate, localTime, reminder.zoneId, currentZone, reminder.policyEnum,
                )
                zone.id to resolved.instant.toEpochMilli()
            } else {
                reminder.zoneId to reminder.triggerInstantMs
            }

            if (resolvedMs != null && resolvedMs <= nowMs) {
                val preserveSummarized = reminder.missedSummarized
                if (!preserveSummarized) missedCount++
                if (reminder.statusEnum != ReminderStatus.MISSED ||
                    reminder.zoneId != effectiveZoneId ||
                    reminder.triggerInstantMs != resolvedMs
                ) {
                    taskDao.upsertReminder(
                        reminder.copy(
                            zoneId = effectiveZoneId,
                            triggerInstantMs = resolvedMs,
                            status = ReminderStatus.MISSED.name,
                            missedSummarized = preserveSummarized,
                            revision = reminder.revision + 1L,
                            updatedAt = timeStr,
                        ),
                    )
                }
            } else if (resolvedMs != null) {
                val updated = reminder.copy(
                    zoneId = effectiveZoneId,
                    triggerInstantMs = resolvedMs,
                    status = ReminderStatus.ACTIVE.name,
                    missedSummarized = false,
                    revision = if (reminder.zoneId != effectiveZoneId ||
                        reminder.triggerInstantMs != resolvedMs ||
                        reminder.statusEnum != ReminderStatus.ACTIVE
                    ) reminder.revision + 1L else reminder.revision,
                    updatedAt = timeStr,
                )
                if (updated != reminder) taskDao.upsertReminder(updated)
                active.add(updated)
            }
        }
        return TimeReconcileResult(active, cancelled, missedCount)
    }

    suspend fun pendingMissedReminders(
        currentZone: ZoneId = ZoneId.systemDefault(),
        now: Instant = Instant.now(),
    ): List<MissedReminderSummaryItem> = database.withTransaction {
        pendingMissedRemindersLocked(now)
    }

    suspend fun markMissedSummarized(
        reminderIds: Collection<String>,
        now: Instant = Instant.now(),
    ) = database.withTransaction {
        markMissedSummarizedLocked(reminderIds, now)
    }

    suspend fun collectMissedAndMarkSummarized(
        currentZone: ZoneId = ZoneId.systemDefault(),
        now: Instant = Instant.now(),
    ): List<MissedReminderSummaryItem> = database.withTransaction {
        val items = pendingMissedRemindersLocked(now)
        if (items.isNotEmpty()) {
            markMissedSummarizedLocked(items.map { it.reminderId }, now)
        }
        items
    }

    private suspend fun pendingMissedRemindersLocked(now: Instant): List<MissedReminderSummaryItem> {
        val timeStr = timestamp.format(now)
        val nowMs = now.toEpochMilli()
        val items = mutableListOf<MissedReminderSummaryItem>()

        for (reminder in taskDao.activeOrMissedReminders()) {
            val triggerMs = reminder.triggerInstantMs ?: continue
            val isDueNow = triggerMs <= nowMs
            if (reminder.statusEnum == ReminderStatus.ACTIVE && !isDueNow) continue
            if (reminder.missedSummarized) continue

            val rawNote = dao.note(reminder.noteId)
            if (rawNote != null && rawNote.deletedAt != null) {
                continue
            }
            val note = rawNote?.takeIf { it.deletedAt == null }
            val block = note?.let {
                runCatching { NoteDocument.decode(it.documentJson).findBlock(reminder.blockId) }.getOrNull()
            }?.takeIf { it.type == "checkbox" }

            if (note == null || block == null || block.checked) {
                taskDao.upsertReminder(
                    reminder.copy(
                        status = if (block?.checked == true) ReminderStatus.COMPLETED.name else ReminderStatus.CANCELLED.name,
                        snoozedUntilMs = null,
                        revision = reminder.revision + 1L,
                        updatedAt = timeStr,
                    ),
                )
                continue
            }

            if (reminder.statusEnum != ReminderStatus.MISSED) {
                taskDao.upsertReminder(
                    reminder.copy(
                        status = ReminderStatus.MISSED.name,
                        missedSummarized = false,
                        revision = reminder.revision + 1L,
                        updatedAt = timeStr,
                    ),
                )
            }
            items.add(
                MissedReminderSummaryItem(
                    reminderId = reminder.id,
                    noteId = note.id,
                    blockId = block.id,
                    taskText = block.text,
                    noteLabel = notePreview(note.title, note.searchText),
                    scheduledInstantMs = triggerMs,
                ),
            )
        }
        return items
    }

    private suspend fun markMissedSummarizedLocked(reminderIds: Collection<String>, now: Instant) {
        if (reminderIds.isEmpty()) return
        val timeStr = timestamp.format(now)
        for (id in reminderIds.distinct()) {
            val reminder = taskDao.reminder(id) ?: continue
            if (reminder.statusEnum == ReminderStatus.ACTIVE || reminder.statusEnum == ReminderStatus.MISSED) {
                if (!reminder.missedSummarized || reminder.statusEnum != ReminderStatus.MISSED) {
                    taskDao.upsertReminder(
                        reminder.copy(
                            status = ReminderStatus.MISSED.name,
                            missedSummarized = true,
                            revision = reminder.revision + 1L,
                            updatedAt = timeStr,
                        ),
                    )
                }
            }
        }
    }

    suspend fun prepareReminderDelivery(
        reminderId: String,
        expectedOccurrenceId: String? = null,
        expectedRevision: Long? = null,
        now: Instant = Instant.now(),
    ): ReminderDeliveryPayload? = database.withTransaction {
        val reminder = taskDao.reminder(reminderId) ?: return@withTransaction null
        if (reminder.statusEnum != ReminderStatus.ACTIVE) return@withTransaction null
        if (expectedOccurrenceId != null && reminder.occurrenceId != null && reminder.occurrenceId != expectedOccurrenceId) {
            return@withTransaction null
        }
        if (expectedRevision != null && expectedRevision > 0L && reminder.revision != expectedRevision) {
            return@withTransaction null
        }
        val rawNote = dao.note(reminder.noteId)
        if (rawNote != null && rawNote.deletedAt != null) {
            return@withTransaction null
        }
        val note = rawNote?.takeIf { it.deletedAt == null }
        val block = note?.let {
            runCatching { NoteDocument.decode(it.documentJson).findBlock(reminder.blockId) }.getOrNull()
        }?.takeIf { it.type == "checkbox" }
        if (note == null || block == null || block.checked) {
            taskDao.upsertReminder(
                reminder.copy(
                    status = if (block?.checked == true) ReminderStatus.COMPLETED.name else ReminderStatus.CANCELLED.name,
                    snoozedUntilMs = null,
                    revision = reminder.revision + 1L,
                    updatedAt = timestamp.format(now),
                ),
            )
            return@withTransaction null
        }
        ReminderDeliveryPayload(
            reminder = reminder,
            taskText = block.text,
            noteLabel = notePreview(note.title, note.searchText),
        )
    }

    suspend fun markReminderNotifiedOnTime(reminderId: String, now: Instant = Instant.now()) = database.withTransaction {
        val reminder = taskDao.reminder(reminderId) ?: return@withTransaction
        if (!reminder.missedSummarized && reminder.statusEnum == ReminderStatus.ACTIVE) {
            taskDao.upsertReminder(
                reminder.copy(
                    missedSummarized = true,
                    revision = reminder.revision + 1L,
                    updatedAt = timestamp.format(now),
                ),
            )
        }
    }

    suspend fun rebuildTaskIndex() = database.withTransaction {
        taskDao.clearAllTasks()
        for (note in taskDao.allLiveNotes()) {
            val document = runCatching { NoteDocument.decode(note.documentJson) }.getOrNull() ?: continue
            val tasks = document.toTaskIndexEntities(note.id)
            if (tasks.isNotEmpty()) dao.addTasks(tasks)
        }
    }

    private suspend fun toggleTaskCheckedLocked(
        noteId: String,
        blockId: String,
        checked: Boolean,
        zoneId: ZoneId,
        now: Instant,
    ): TaskToggleResult {
        val note = dao.note(noteId)?.takeIf { it.deletedAt == null } ?: return TaskToggleResult.NotFound
        val document = NoteDocument.decode(note.documentJson)
        val block = document.findBlock(blockId)?.takeIf { it.type == "checkbox" } ?: return TaskToggleResult.BlockNotFound
        val reminder = taskDao.reminderForBlock(noteId, blockId)
        val timeStr = timestamp.format(now)
        var updatedReminder: ReminderEntity? = reminder
        var advancedDate: String? = null
        var cancelledId: String? = null
        var recordedOccurrence: TaskOccurrenceEntity? = null

        val updatedBlock = if (checked) {
            if (reminder != null && reminder.recurrenceEnum != RecurrenceRule.NONE && reminder.statusEnum != ReminderStatus.CANCELLED) {
                val advanced = advanceRecurringBlock(
                    noteId = noteId,
                    block = block,
                    reminder = reminder,
                    zoneId = zoneId,
                    timeStr = timeStr,
                )
                recordedOccurrence = advanced.occurrence
                updatedReminder = advanced.nextReminder
                advancedDate = advanced.nextDate
                advanced.updatedBlock
            } else {
                val currentOccId = block.occurrenceId ?: reminder?.occurrenceId ?: newId()
                val scheduledDate = block.dueDate ?: reminder?.localDate
                val occurrence = TaskOccurrenceEntity(
                    id = currentOccId,
                    noteId = noteId,
                    blockId = blockId,
                    reminderId = reminder?.id,
                    scheduledDate = scheduledDate,
                    titleSnapshot = block.text,
                    recurrence = reminder?.recurrence ?: RecurrenceRule.NONE.name,
                    completedAt = timeStr,
                )
                taskDao.upsertOccurrence(occurrence)
                recordedOccurrence = occurrence
                if (reminder != null && reminder.statusEnum != ReminderStatus.COMPLETED && reminder.statusEnum != ReminderStatus.CANCELLED) {
                    val doneReminder = reminder.copy(
                        occurrenceId = currentOccId,
                        status = ReminderStatus.COMPLETED.name,
                        snoozedUntilMs = null,
                        revision = reminder.revision + 1L,
                        updatedAt = timeStr,
                    )
                    taskDao.upsertReminder(doneReminder)
                    updatedReminder = doneReminder
                    cancelledId = doneReminder.id
                }
                block.withChecked(true).withTaskSchedule(
                    dueDate = block.dueDate,
                    reminderId = block.reminderId ?: reminder?.id,
                    occurrenceId = currentOccId,
                )
            }
        } else {
            updatedReminder = clearNonRecurringCompletionAndMaybeReactivate(
                noteId = noteId,
                blockId = blockId,
                reminder = reminder,
                zoneId = zoneId,
                now = now,
                timeStr = timeStr,
            )
            block.withChecked(false)
        }

        val updatedDoc = document.replaceBlock(blockId) { updatedBlock }
        val updatedJson = updatedDoc.encode()
        val updatedNote = note.copy(
            documentJson = updatedJson,
            searchText = updatedDoc.text,
            mediaMask = updatedDoc.mediaMask(),
            updatedAt = timeStr,
            revision = note.revision + 1L,
            contentHash = hash(updatedJson),
            lastDraftId = newId(),
            committedSeq = 0L,
        )
        writeRevision(updatedNote, "task")
        replaceNoteTaskIndex(updatedNote.id, updatedDoc)
        return TaskToggleResult.Updated(
            note = updatedNote,
            reminder = updatedReminder,
            advancedToDate = advancedDate,
            cancelledReminderId = cancelledId,
            recordedOccurrence = recordedOccurrence,
        )
    }

    private data class AdvancedRecurringOutcome(
        val updatedBlock: NoteBlock,
        val nextReminder: ReminderEntity,
        val nextDate: String,
        val occurrence: TaskOccurrenceEntity,
    )

    private suspend fun advanceRecurringBlock(
        noteId: String,
        block: NoteBlock,
        reminder: ReminderEntity,
        zoneId: ZoneId,
        timeStr: String,
    ): AdvancedRecurringOutcome {
        val currentOccId = block.occurrenceId ?: reminder.occurrenceId ?: newId()
        val scheduledDate = block.dueDate ?: reminder.localDate
        val occurrence = TaskOccurrenceEntity(
            id = currentOccId,
            noteId = noteId,
            blockId = block.id,
            reminderId = reminder.id,
            scheduledDate = scheduledDate,
            titleSnapshot = block.text,
            recurrence = reminder.recurrence,
            completedAt = timeStr,
        )
        taskDao.upsertOccurrence(occurrence)

        val baseDate = TaskRecurrence.parseDueDate(block.dueDate)
            ?: TaskRecurrence.parseDueDate(reminder.localDate)
            ?: LocalDate.now(zoneId)
        val nextDate = TaskRecurrence.nextDate(baseDate, reminder.recurrenceEnum)
        val nextOccId = newId()
        val effectiveZone = if (reminder.policyEnum == ReminderPolicy.FOLLOW_LOCAL) {
            zoneId
        } else {
            TaskRecurrence.parseZoneOrDefault(reminder.zoneId, zoneId)
        }
        val localTime = TaskRecurrence.parseLocalTime(reminder.localTime) ?: LocalTime.of(9, 0)
        val resolved = TaskRecurrence.resolveTrigger(nextDate, localTime, effectiveZone)
        val nextReminder = reminder.copy(
            occurrenceId = nextOccId,
            zoneId = effectiveZone.id,
            localDate = nextDate.toString(),
            localTime = TaskRecurrence.formatLocalTime(localTime),
            triggerInstantMs = resolved.instant.toEpochMilli(),
            status = ReminderStatus.ACTIVE.name,
            snoozedUntilMs = null,
            missedSummarized = false,
            revision = reminder.revision + 1L,
            updatedAt = timeStr,
        )
        taskDao.upsertReminder(nextReminder)
        val updatedBlock = block.withChecked(false).withTaskSchedule(
            dueDate = if (block.dueDate != null) nextDate.toString() else null,
            reminderId = reminder.id,
            occurrenceId = nextOccId,
        )
        return AdvancedRecurringOutcome(
            updatedBlock = updatedBlock,
            nextReminder = nextReminder,
            nextDate = nextDate.toString(),
            occurrence = occurrence,
        )
    }

    private suspend fun clearNonRecurringCompletionAndMaybeReactivate(
        noteId: String,
        blockId: String,
        reminder: ReminderEntity?,
        zoneId: ZoneId,
        now: Instant,
        timeStr: String,
    ): ReminderEntity? {
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM task_occurrences WHERE note_id = ? AND block_id = ? AND recurrence = ?",
            arrayOf(noteId, blockId, RecurrenceRule.NONE.name),
        )
        if (reminder == null || reminder.recurrenceEnum != RecurrenceRule.NONE) {
            return reminder
        }
        if (reminder.statusEnum == ReminderStatus.COMPLETED) {
            val localDate = TaskRecurrence.parseDueDate(reminder.localDate)
            val localTime = TaskRecurrence.parseLocalTime(reminder.localTime)
            val targetZone = if (reminder.policyEnum == ReminderPolicy.FOLLOW_LOCAL) {
                zoneId
            } else {
                TaskRecurrence.parseZoneOrDefault(reminder.zoneId, zoneId)
            }
            val triggerMs = if (localDate != null && localTime != null) {
                TaskRecurrence.resolveTrigger(localDate, localTime, targetZone).instant.toEpochMilli()
            } else {
                reminder.triggerInstantMs
            }
            if (triggerMs != null && triggerMs > now.toEpochMilli()) {
                val reactivated = reminder.copy(
                    zoneId = targetZone.id,
                    triggerInstantMs = triggerMs,
                    status = ReminderStatus.ACTIVE.name,
                    snoozedUntilMs = null,
                    missedSummarized = false,
                    revision = reminder.revision + 1L,
                    updatedAt = timeStr,
                )
                taskDao.upsertReminder(reactivated)
                return reactivated
            }
        }
        return reminder
    }

    private suspend fun syncTasksForDocument(
        updated: NoteEntity,
        previous: NoteEntity?,
        document: NoteDocument,
        timeStr: String,
        zoneId: ZoneId = ZoneId.systemDefault(),
        now: Instant = Instant.now(),
        skipOccurrenceIds: Set<String> = emptySet(),
    ) {
        replaceNoteTaskIndex(updated.id, document)
        val previousBlocks = previous?.let {
            runCatching { NoteDocument.decode(it.documentJson).blocks.associateBy(NoteBlock::id) }.getOrNull()
        }.orEmpty()
        val currentCheckboxes = document.blocks.filter { it.type == "checkbox" }.associateBy(NoteBlock::id)
        val existingReminders = taskDao.remindersForNote(updated.id).associateBy(ReminderEntity::blockId)

        for ((blockId, reminder) in existingReminders) {
            val block = currentCheckboxes[blockId]
            if (block == null) {
                if (reminder.statusEnum == ReminderStatus.ACTIVE || reminder.statusEnum == ReminderStatus.MISSED) {
                    taskDao.cancelReminderForBlock(updated.id, blockId, timeStr)
                }
            } else if (
                block.checked &&
                reminder.recurrenceEnum == RecurrenceRule.NONE &&
                (reminder.statusEnum == ReminderStatus.ACTIVE || reminder.statusEnum == ReminderStatus.MISSED)
            ) {
                taskDao.upsertReminder(
                    reminder.copy(
                        status = ReminderStatus.COMPLETED.name,
                        snoozedUntilMs = null,
                        revision = reminder.revision + 1L,
                        updatedAt = timeStr,
                    ),
                )
            }
        }

        for ((blockId, block) in currentCheckboxes) {
            val wasChecked = previousBlocks[blockId]?.checked == true
            val reminder = existingReminders[blockId]
            if (block.checked && !wasChecked && blockId !in skipOccurrenceIds) {
                val occId = block.occurrenceId ?: reminder?.occurrenceId ?: newId()
                taskDao.upsertOccurrence(
                    TaskOccurrenceEntity(
                        id = occId,
                        noteId = updated.id,
                        blockId = blockId,
                        reminderId = reminder?.id ?: block.reminderId,
                        scheduledDate = block.dueDate ?: reminder?.localDate,
                        titleSnapshot = block.text,
                        recurrence = reminder?.recurrence ?: RecurrenceRule.NONE.name,
                        completedAt = timeStr,
                    ),
                )
            } else if (!block.checked && wasChecked && blockId !in skipOccurrenceIds) {
                clearNonRecurringCompletionAndMaybeReactivate(
                    noteId = updated.id,
                    blockId = blockId,
                    reminder = reminder,
                    zoneId = zoneId,
                    now = now,
                    timeStr = timeStr,
                )
            }
        }
    }

    private suspend fun replaceNoteTaskIndex(noteId: String, document: NoteDocument) {
        dao.deleteTasks(noteId)
        val tasks = document.toTaskIndexEntities(noteId)
        if (tasks.isNotEmpty()) dao.addTasks(tasks)
    }

    private fun NoteDocument.toTaskIndexEntities(noteId: String): List<TaskIndexEntity> =
        blocks.filter { it.type == "checkbox" }.map { block ->
            TaskIndexEntity(noteId, block.id, block.checked, block.dueDate, block.occurrenceId)
        }

    private fun notePreview(title: String?, searchText: String): String =
        title?.takeIf(String::isNotBlank)
            ?: searchText.lineSequence().firstOrNull { it.isNotBlank() }?.take(60)
            ?: "无标题笔记"

    suspend fun setPinned(id: String, pinned: Boolean) =
        changeMetadata(id) { if (it.pinned == pinned) it else it.copy(pinned = pinned) }

    suspend fun setArchived(id: String, archived: Boolean) =
        changeMetadata(id) {
            if ((it.archivedAt != null) == archived) it
            else it.copy(archivedAt = if (archived) now() else null)
        }

    suspend fun setAiPolicy(noteId: String, aiPolicy: String) {
        val validated = validateAiPolicy(aiPolicy)
        changeMetadata(noteId) {
            if (it.aiPolicy == validated) it else it.copy(aiPolicy = validated)
        }
    }

    suspend fun batchSetArchived(noteIds: Collection<String>, archived: Boolean) = database.withTransaction {
        val ids = normalizeBatchNoteIds(noteIds)
        if (ids.isEmpty()) return@withTransaction
        val ts = now()
        for (id in ids) {
            val previous = dao.note(id) ?: continue
            if (previous.deletedAt != null) continue
            if ((previous.archivedAt != null) == archived) continue
            writeRevision(
                previous.copy(
                    archivedAt = if (archived) ts else null,
                    revision = previous.revision + 1L,
                    updatedAt = ts,
                ),
                "metadata",
            )
        }
    }

    suspend fun batchSoftDelete(noteIds: Collection<String>): List<String> = database.withTransaction {
        val ids = normalizeBatchNoteIds(noteIds)
        if (ids.isEmpty()) return@withTransaction emptyList()
        val ts = now()
        val cancelledReminders = mutableListOf<String>()
        for (id in ids) {
            val previous = dao.note(id) ?: continue
            if (previous.deletedAt != null) continue
            dao.deleteDraftsForNote(id)
            writeRevision(
                previous.copy(revision = previous.revision + 1L, updatedAt = ts, deletedAt = ts),
                "trash",
            )
            cancelledReminders.addAll(taskDao.remindersForNote(id).map { it.id })
        }
        cancelledReminders.distinct()
    }

    suspend fun listTrashedNotes(): List<NoteEntity> = dao.trashedNotes()

    suspend fun moveToTrash(id: String): List<String> = database.withTransaction {
        val previous = dao.note(id) ?: return@withTransaction emptyList()
        if (previous.deletedAt != null) return@withTransaction emptyList()
        val ts = now()
        dao.deleteDraftsForNote(id)
        writeRevision(
            previous.copy(revision = previous.revision + 1L, updatedAt = ts, deletedAt = ts),
            "trash",
        )
        taskDao.remindersForNote(id).map { it.id }
    }

    suspend fun restoreFromTrash(
        id: String,
        zoneId: ZoneId = ZoneId.systemDefault(),
        nowInstant: Instant = Instant.now(),
    ): TimeReconcileResult? = database.withTransaction {
        val previous = dao.note(id) ?: return@withTransaction null
        if (previous.deletedAt == null) return@withTransaction null
        val ts = timestamp.format(nowInstant)
        writeRevision(
            previous.copy(revision = previous.revision + 1L, updatedAt = ts, deletedAt = null),
            "restore_trash",
        )
        reconcileTimeChangeLocked(zoneId, nowInstant)
    }

    suspend fun previewPurge(
        noteIds: Collection<String>,
        existingAttachmentIds: Set<String>? = null,
    ): TrashPurgeImpact = database.withTransaction {
        val targetIds = noteIds.filter { it.isNotBlank() }.toSet()
        if (targetIds.isEmpty()) return@withTransaction TrashPurgeImpact(0, 0, 0)
        val allNotes = dao.allNotes()
        val targetNotes = allNotes.filter { it.id in targetIds && it.deletedAt != null }
        if (targetNotes.isEmpty()) return@withTransaction TrashPurgeImpact(0, 0, 0)
        val actualTargetIds = targetNotes.mapTo(HashSet()) { it.id }
        val allVersions = dao.allVersions()
        val allDrafts = dao.allDrafts()
        val targetVersions = allVersions.filter { it.noteId in actualTargetIds }

        val candidateRefs = HashSet<String>()
        targetNotes.forEach { candidateRefs.addAll(VersionDiff.extractAttachmentIds(it.documentJson)) }
        targetVersions.forEach {
            candidateRefs.addAll(VersionDiff.decodeAttachmentRefs(it.attachmentRefs, it.documentJson))
        }
        allDrafts.asSequence().filter { it.noteId in actualTargetIds }.forEach {
            candidateRefs.addAll(VersionDiff.extractAttachmentIds(it.documentJson))
        }

        val retainedRefs = HashSet<String>()
        allNotes.asSequence().filter { it.id !in actualTargetIds }.forEach {
            retainedRefs.addAll(VersionDiff.extractAttachmentIds(it.documentJson))
        }
        allVersions.asSequence().filter { it.noteId !in actualTargetIds }.forEach {
            retainedRefs.addAll(VersionDiff.decodeAttachmentRefs(it.attachmentRefs, it.documentJson))
        }
        allDrafts.asSequence().filter { it.noteId !in actualTargetIds }.forEach {
            retainedRefs.addAll(VersionDiff.extractAttachmentIds(it.documentJson))
        }

        val orphanedCount = (candidateRefs - retainedRefs).let { orphaned ->
            if (existingAttachmentIds != null) orphaned.count { it in existingAttachmentIds } else orphaned.size
        }
        TrashPurgeImpact(
            noteCount = targetNotes.size,
            versionCount = targetVersions.size,
            orphanedAttachmentCount = orphanedCount,
        )
    }

    suspend fun permanentlyDeleteNotes(noteIds: Collection<String>): PermanentDeleteResult =
        database.withTransaction {
            permanentlyDeleteNotesLocked(noteIds)
        }

    suspend fun emptyTrash(): PermanentDeleteResult = database.withTransaction {
        val trashedIds = dao.trashedNotes().map { it.id }
        permanentlyDeleteNotesLocked(trashedIds)
    }

    suspend fun purgeExpiredTrash(
        retentionDays: Int,
        now: Instant = Instant.now(),
        excludeNoteIds: Set<String> = emptySet(),
    ): PermanentDeleteResult = purgeExpiredTrash(
        now = now,
        retentionDays = retentionDays.toLong(),
        excludeNoteIds = excludeNoteIds,
    )

    suspend fun purgeExpiredTrash(
        now: Instant = Instant.now(),
        retentionDays: Long = TRASH_RETENTION_DAYS,
        excludeNoteIds: Set<String> = emptySet(),
    ): PermanentDeleteResult = database.withTransaction {
        val cutoff = now.minus(retentionDays.coerceAtLeast(1L), ChronoUnit.DAYS)
        val expiredIds = dao.trashedNotes().mapNotNull { note ->
            if (note.id in excludeNoteIds) return@mapNotNull null
            val deletedAt = note.deletedAt ?: return@mapNotNull null
            val parsed = runCatching { Instant.parse(deletedAt) }.getOrNull() ?: return@mapNotNull null
            if (parsed.isBefore(cutoff)) note.id else null
        }
        if (expiredIds.isEmpty()) {
            PermanentDeleteResult(emptyList(), emptyList())
        } else {
            permanentlyDeleteNotesLocked(expiredIds)
        }
    }

    private suspend fun permanentlyDeleteNotesLocked(noteIds: Collection<String>): PermanentDeleteResult {
        val deleted = mutableListOf<String>()
        val cancelledReminders = mutableListOf<String>()
        val orgDao = database.organization()
        for (id in noteIds.distinct()) {
            val existing = dao.note(id) ?: continue
            require(existing.deletedAt != null) { "只能彻底删除回收站中的笔记" }
            cancelledReminders.addAll(taskDao.remindersForNote(id).map { it.id })
            dao.deleteTasks(id)
            taskDao.deleteRemindersForNote(id)
            taskDao.deleteOccurrencesForNote(id)
            dao.deleteDraftsForNote(id)
            dao.deleteVersionsForNote(id)
            dao.deleteUnsyncedOutboxForNote(id)
            orgDao.clearLinksInvolving(id)
            dao.deleteNoteById(id)
            deleted.add(id)
        }
        return PermanentDeleteResult(
            deletedNoteIds = deleted,
            cancelledReminderIds = cancelledReminders.distinct(),
        )
    }

    suspend fun listVersions(noteId: String): Pair<NoteEntity, List<NoteRevisionSummary>>? =
        database.withTransaction {
            val note = dao.note(noteId) ?: return@withTransaction null
            val versions = dao.versionsForNote(noteId)
            val summaries = versions.map { version ->
                val effectiveTitle = version.title ?: if (version.revision == note.revision) note.title else null
                val (snippet, attachmentCount) = VersionDiff.summarizeDocument(version.documentJson, effectiveTitle)
                NoteRevisionSummary(
                    noteId = version.noteId,
                    revision = version.revision,
                    origin = version.origin,
                    title = effectiveTitle,
                    snippet = snippet,
                    attachmentCount = attachmentCount,
                    createdAt = version.createdAt,
                    isCurrent = version.revision == note.revision,
                )
            }
            note to summaries
        }

    suspend fun loadVersionDiff(
        noteId: String,
        targetRevision: Long,
        resolveAttachmentLabel: (String) -> String?,
    ): VersionDiffPreview? {
        val note = dao.note(noteId) ?: return null
        val target = dao.version(noteId, targetRevision) ?: return null
        val targetTitle = target.title ?: if (target.revision == note.revision) note.title else null
        return VersionDiff.diffDocuments(
            noteId = noteId,
            targetRevision = target.revision,
            targetTitle = targetTitle,
            targetDocumentJson = target.documentJson,
            targetAttachmentRefsJson = target.attachmentRefs,
            targetOrigin = target.origin,
            targetCreatedAt = target.createdAt,
            currentRevision = note.revision,
            currentTitle = note.title,
            currentDocumentJson = note.documentJson,
            resolveAttachmentLabel = resolveAttachmentLabel,
        )
    }

    suspend fun restoreVersion(
        noteId: String,
        targetRevision: Long,
        expectedCurrentRevision: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        nowInstant: Instant = Instant.now(),
    ): RestoreVersionResult = database.withTransaction {
        val current = dao.note(noteId) ?: return@withTransaction RestoreVersionResult.NotFound
        if (current.deletedAt != null) return@withTransaction RestoreVersionResult.Trashed
        if (current.revision != expectedCurrentRevision) {
            return@withTransaction RestoreVersionResult.Conflict(current.revision)
        }
        val target = dao.version(noteId, targetRevision) ?: return@withTransaction RestoreVersionResult.NotFound
        val restoredDoc = NoteDocument.decode(target.documentJson)
        val ts = timestamp.format(nowInstant)
        val restoredNote = current.copy(
            title = target.title,
            documentJson = target.documentJson,
            searchText = restoredDoc.text,
            mediaMask = restoredDoc.mediaMask(),
            updatedAt = ts,
            revision = current.revision + 1L,
            contentHash = hash(target.documentJson),
            lastDraftId = newId(),
            committedSeq = 0L,
        )
        dao.deleteDraftsForNote(noteId)
        writeRevision(restoredNote, "restore")
        syncTasksForDocument(
            updated = restoredNote,
            previous = current,
            document = restoredDoc,
            timeStr = ts,
            zoneId = zoneId,
            now = nowInstant,
        )
        val extraCancelledIds = mutableListOf<String>()
        val restoredBlocksById = restoredDoc.blocks
            .filter { it.type == "checkbox" }
            .associateBy(NoteBlock::id)
        for (rem in taskDao.remindersForNote(noteId)) {
            if (rem.statusEnum != ReminderStatus.ACTIVE && rem.statusEnum != ReminderStatus.MISSED) continue
            val restoredBlock = restoredBlocksById[rem.blockId]
            val dateMismatch = restoredBlock != null &&
                rem.localDate != null &&
                restoredBlock.dueDate != null &&
                restoredBlock.dueDate != rem.localDate
            if (restoredBlock == null || restoredBlock.checked || restoredBlock.reminderId == null || dateMismatch) {
                taskDao.upsertReminder(
                    rem.copy(
                        status = ReminderStatus.CANCELLED.name,
                        snoozedUntilMs = null,
                        revision = rem.revision + 1L,
                        updatedAt = ts,
                    ),
                )
                extraCancelledIds.add(rem.id)
            }
        }
        val baseSync = reconcileTimeChangeLocked(zoneId, nowInstant)
        val reminderSync = if (extraCancelledIds.isEmpty()) {
            baseSync
        } else {
            baseSync.copy(cancelledReminderIds = (baseSync.cancelledReminderIds + extraCancelledIds).distinct())
        }
        database.organization().clearLinks(restoredNote.id)
        database.organization().insertLinks(restoredDoc.linksFrom(restoredNote.id))
        RestoreVersionResult.Restored(
            note = restoredNote,
            previousRevision = current.revision,
            referencedAttachmentIds = VersionDiff.decodeAttachmentRefs(target.attachmentRefs, target.documentJson),
            reminderSync = reminderSync,
        )
    }

    private suspend fun changeMetadata(id: String, change: (NoteEntity) -> NoteEntity) = database.withTransaction {
        val previous = dao.note(id) ?: return@withTransaction
        check(previous.deletedAt == null)
        val updated = change(previous)
        if (updated != previous) writeRevision(
            updated.copy(revision = previous.revision + 1, updatedAt = now()), "metadata",
        )
    }

    private suspend fun writeRevision(note: NoteEntity, origin: String) {
        dao.putNote(note)
        dao.addVersion(
            NoteVersionEntity(
                noteId = note.id,
                revision = note.revision,
                documentJson = note.documentJson,
                origin = origin,
                createdAt = note.updatedAt,
                title = note.title,
                attachmentRefs = VersionDiff.encodeAttachmentRefsJson(
                    VersionDiff.extractAttachmentIds(note.documentJson),
                ),
            ),
        )
        dao.pruneOldVersions(
            noteId = note.id,
            keepCount = BackupConstants.MAX_REVISIONS_PER_NOTE,
            keepRevision = note.revision,
        )
        val payload = JSONObject()
            .put("note_id", note.id)
            .put("revision", note.revision)
            .put("document", JSONObject(note.documentJson))
            .put("source_type", note.sourceType)
            .put("source_url", note.sourceUrl ?: JSONObject.NULL)
            .put("pinned", note.pinned)
            .put("archived_at", note.archivedAt ?: JSONObject.NULL)
            .toString()
        dao.addOutbox(OutboxEntity(newId(), note.id, note.revision, note.serverRevision, payload, note.updatedAt))
    }

    private fun verifyRevision(draft: DraftEntity, note: NoteEntity?) {
        if (note?.deletedAt != null ||
            ((note?.revision ?: 0) != draft.baseRevision && note?.lastDraftId != draft.id)
        ) throw StaleDraftException()
    }

    private fun now() = timestamp.format(Instant.now())

    private val timestamp = DateTimeFormatterBuilder().appendInstant(3).toFormatter()

    private fun hash(document: String): String =
        MessageDigest.getInstance("SHA-256").digest(document.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val TRASH_RETENTION_DAYS = 30L
        val ALLOWED_AI_POLICIES: Set<String> = setOf("inherit", "exclude", "allow")

        fun validateAiPolicy(aiPolicy: String): String {
            require(aiPolicy in ALLOWED_AI_POLICIES) { "不支持的 AI 策略: $aiPolicy" }
            return aiPolicy
        }

        fun normalizeBatchNoteIds(noteIds: Collection<String>): List<String> =
            noteIds.asSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()
    }
}
