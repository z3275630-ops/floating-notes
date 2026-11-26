package io.github.z3275630ops.floatingnotes.data.backup

import android.content.Context
import android.net.Uri
import android.system.Os
import androidx.room.withTransaction
import io.github.z3275630ops.floatingnotes.BuildConfig
import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import io.github.z3275630ops.floatingnotes.core.model.newId
import io.github.z3275630ops.floatingnotes.data.agent.AgentActionEntity
import io.github.z3275630ops.floatingnotes.data.local.AttachmentStore
import io.github.z3275630ops.floatingnotes.data.local.DraftEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteLinkEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteTagEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteVersionEntity
import io.github.z3275630ops.floatingnotes.data.local.NotebookEntity
import io.github.z3275630ops.floatingnotes.data.local.NotebookNoteEntity
import io.github.z3275630ops.floatingnotes.data.local.NotesDatabase
import io.github.z3275630ops.floatingnotes.data.local.ReminderEntity
import io.github.z3275630ops.floatingnotes.data.local.ReminderStatus
import io.github.z3275630ops.floatingnotes.data.local.SavedSearch
import io.github.z3275630ops.floatingnotes.data.local.TagEntity
import io.github.z3275630ops.floatingnotes.data.local.TaskIndexEntity
import io.github.z3275630ops.floatingnotes.data.local.TaskOccurrenceEntity
import io.github.z3275630ops.floatingnotes.data.local.mediaMask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatterBuilder

internal data class RestoreExecutionResult(
    val outcome: RestoreOutcome,
    val activeRemindersToSchedule: List<ReminderEntity> = emptyList(),
    val cancelledReminderIds: List<String> = emptyList(),
)

internal class BackupManager(
    private val context: Context,
    private val database: NotesDatabase,
    private val attachments: AttachmentStore,
) {
    private val timestamp = DateTimeFormatterBuilder().appendInstant(3).toFormatter()

    val journalDir: File
        get() = File(attachments.rootDirectory().parentFile ?: context.filesDir, "backup_journal")

    private val leasesFile: File get() = File(journalDir, "leases.json")
    private val pendingRestoreFile: File get() = File(journalDir, "pending_restore.json")
    private val rollbacksDir: File get() = File(journalDir, "rollbacks")
    private val preflightDir: File get() = File(journalDir, "preflight")
    private val tmpDir: File get() = File(journalDir, "tmp")

    private var stagedPreflightArchive: File? = null
    private var stagedPreflightSource: String? = null
    internal var beforePromoteHook: (() -> Unit)? = null

    fun stagingDirForRestore(restoreId: String): File = File(journalDir, "staging/$restoreId")
    private fun extractDirForRestore(restoreId: String): File = File(journalDir, "extract/$restoreId")
    // The attachment store ignores names ending in .part.
    private fun stagedDraftExtras(restoreId: String) = File(stagingDirForRestore(restoreId), "draft-extras.part")
    private val draftExtrasDir get() = File(context.filesDir, "draft_extras")

    private fun stageDraftExtras(restoreId: String, draftId: String, extras: String?) {
        if (!BackupCodec.isSafeAttachmentId(draftId)) {
            if (!extras.isNullOrBlank()) throw BackupValidationException("草稿标识无效，无法恢复附加信息")
            return
        }
        if (extras == null && !File(draftExtrasDir, "$draftId.json").isFile) return
        writeAtomicText(File(stagedDraftExtras(restoreId), "$draftId.json"), extras ?: "{}")
    }

    private fun promoteDraftExtras(restoreId: String) {
        val staged = stagedDraftExtras(restoreId).listFiles()?.filter { it.isFile }.orEmpty()
        if (staged.isEmpty()) return
        if (!draftExtrasDir.isDirectory && !draftExtrasDir.mkdirs()) throw IOException("无法写入草稿附加信息")
        for (file in staged) Os.rename(file.path, File(draftExtrasDir, file.name).path)
    }

    private fun remapDraftTags(extras: String?, tagIdMap: Map<String, String>): String? {
        if (extras == null) return null
        val data = LinkedHashMap(MiniJson.parseObject(extras))
        val tags = (data["pending_tag_ids"] as? List<*>).orEmpty()
        data["pending_tag_ids"] = tags.filterIsInstance<String>().mapNotNull(tagIdMap::get).distinct()
        return MiniJson.encode(data)
    }

    suspend fun activeLeaseIds(): Set<String> = withContext(Dispatchers.IO) {
        val retained = HashSet<String>()
        for (file in listOf(leasesFile, pendingRestoreFile)) {
            if (!file.isFile) continue
            runCatching {
                val obj = MiniJson.parseObject(file.readText(StandardCharsets.UTF_8))
                (obj["leased_attachment_ids"] as? List<*>)?.mapNotNull { it as? String }?.let(retained::addAll)
                (obj["staged_attachment_ids"] as? List<*>)?.mapNotNull { it as? String }?.let(retained::addAll)
            }
        }
        retained
    }

    suspend fun recoverInterruptedRestoreIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        tmpDir.deleteRecursively()
        leasesFile.delete()

        val journalFile = pendingRestoreFile
        if (!journalFile.isFile) {
            File(journalDir, "extract").deleteRecursively()
            File(journalDir, "staging").deleteRecursively()
            return@withContext false
        }

        val journal = runCatching { parseRestoreJournal(journalFile.readText(StandardCharsets.UTF_8)) }.getOrNull()
        val sentinelId = journal?.let { restoreCommitActionId(it.id) }
        val hasCommitSentinel = sentinelId != null &&
            runCatching { database.agent().actionById(sentinelId) != null }.getOrDefault(false)
        val committed = journal?.phase == RestorePhase.DB_COMMITTED || hasCommitSentinel
        if (committed) {
            val stageDir = stagingDirForRestore(journal!!.id)
            if (stageDir.isDirectory) {
                attachments.promoteStagedAttachments(
                    stagingFolder = stageDir,
                    allowOverwrite = journal.mode == RestoreMode.REPLACE_ALL,
                )
                promoteDraftExtras(journal.id)
            }
            if (sentinelId != null) {
                runCatching { database.agent().deleteAction(sentinelId) }
            }
        }
        if (journal != null) {
            stagingDirForRestore(journal.id).deleteRecursively()
            extractDirForRestore(journal.id).deleteRecursively()
        }
        File(journalDir, "staging").deleteRecursively()
        File(journalDir, "extract").deleteRecursively()
        journalFile.delete()
        committed
    }

    suspend fun exportFullBackupToUri(destinationUri: Uri): ExportOutcome = withContext(Dispatchers.IO) {
        journalDir.mkdirs()
        tmpDir.mkdirs()
        val tempZip = File(tmpDir, "export-${newId()}.zip")
        try {
            val manifest = exportFullBackupToFile(tempZip)
            copyFileToUri(tempZip, destinationUri)
            ExportOutcome(
                ok = true,
                message = "已导出完整备份（${manifest.counts.notes} 篇笔记，${manifest.counts.attachments} 个附件）",
                itemCount = manifest.counts.notes,
            )
        } catch (err: Exception) {
            ExportOutcome(
                ok = false,
                message = err.message ?: "导出完整备份失败",
            )
        } finally {
            tempZip.delete()
        }
    }

    internal suspend fun exportFullBackupToFile(
        targetZipFile: File,
        now: Instant = Instant.now(),
    ): BackupManifest = withContext(Dispatchers.IO) {
        journalDir.mkdirs()
        try {
            val snapshot = captureSnapshot(now)
            writeLeases(snapshot.attachments.map { it.id })
            val manifest = BackupCodec.writeBackupZip(
                snapshot = snapshot,
                targetZipFile = targetZipFile,
                attachmentFileProvider = { id ->
                    val folder = File(attachments.rootDirectory(), id)
                    File(folder, "original").takeIf { it.isFile }
                },
            )
            BackupCodec.validateArchiveStreaming(targetZipFile)
            manifest
        } catch (err: OutOfMemoryError) {
            throw BackupValidationException("设备可用内存不足，无法生成完整备份或还原前的回滚包", err)
        } finally {
            leasesFile.delete()
        }
    }

    suspend fun exportMarkdownToUri(
        destinationUri: Uri,
        includeArchived: Boolean = true,
        includeTrashed: Boolean = false,
    ): ExportOutcome = withContext(Dispatchers.IO) {
        journalDir.mkdirs()
        tmpDir.mkdirs()
        val tempZip = File(tmpDir, "markdown-${newId()}.zip")
        try {
            val outcome = exportMarkdownToFile(
                targetZipFile = tempZip,
                includeArchived = includeArchived,
                includeTrashed = includeTrashed,
            )
            if (outcome.ok) {
                copyFileToUri(tempZip, destinationUri)
            }
            outcome
        } catch (err: Exception) {
            ExportOutcome(
                ok = false,
                message = err.message ?: "导出 Markdown 压缩包失败",
                warnings = BackupUiState.DEFAULT_MARKDOWN_WARNINGS,
            )
        } finally {
            tempZip.delete()
        }
    }

    internal suspend fun exportMarkdownToFile(
        targetZipFile: File,
        includeArchived: Boolean = true,
        includeTrashed: Boolean = false,
        now: Instant = Instant.now(),
    ): ExportOutcome = withContext(Dispatchers.IO) {
        journalDir.mkdirs()
        val snapshot = captureSnapshot(now)
        writeLeases(snapshot.attachments.map { it.id })
        try {
            BackupCodec.writeMarkdownZip(
                snapshot = snapshot,
                targetZipFile = targetZipFile,
                includeArchived = includeArchived,
                includeTrashed = includeTrashed,
                attachmentFileProvider = { id ->
                    File(File(attachments.rootDirectory(), id), "original").takeIf { it.isFile }
                },
            )
        } finally {
            leasesFile.delete()
        }
    }

    suspend fun preflightBackupUri(sourceUri: Uri): RestorePreflight = withContext(Dispatchers.IO) {
        dismissPreflight()
        journalDir.mkdirs()
        preflightDir.mkdirs()
        val candidateZip = File(preflightDir, "candidate-${newId()}.zip")
        try {
            copyUriToFile(sourceUri, candidateZip)
            preflightBackupFileInternal(candidateZip, sourceUri.toString())
        } catch (err: Exception) {
            candidateZip.delete()
            throw err
        }
    }

    internal suspend fun preflightBackupFile(sourceZip: File): RestorePreflight = withContext(Dispatchers.IO) {
        dismissPreflight()
        journalDir.mkdirs()
        preflightDir.mkdirs()
        val candidateZip = File(preflightDir, "candidate-${newId()}.zip")
        try {
            sourceZip.inputStream().use { input ->
                FileOutputStream(candidateZip).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            preflightBackupFileInternal(candidateZip, sourceZip.absolutePath)
        } catch (err: Exception) {
            candidateZip.delete()
            throw err
        }
    }

    private suspend fun preflightBackupFileInternal(
        candidateZip: File,
        sourceLabel: String,
    ): RestorePreflight {
        val recordDir = File(preflightDir, "${candidateZip.name}.records")
        val (manifest, conflictCount) = try {
            val parsed = BackupCodec.readAndValidateArchive(candidateZip, recordStagingDir = recordDir)
            val existingNoteIds = database.notes().allNoteIds().toHashSet()
            parsed.manifest to parsed.snapshot.notes.count { it.id in existingNoteIds }
        } finally {
            recordDir.deleteRecursively()
        }
        stagedPreflightArchive = candidateZip
        stagedPreflightSource = sourceLabel
        val counts = manifest.counts
        return RestorePreflight(
            sourceUri = sourceLabel,
            exportedAt = manifest.exportedAt,
            schemaVersion = manifest.backupSchemaVersion,
            dbSchemaVersion = manifest.dbSchemaVersion,
            totalNotes = counts.notes,
            trashedNotes = counts.trashedNotes,
            versionCount = counts.versions,
            attachmentCount = counts.attachments,
            tagCount = counts.tags,
            notebookCount = counts.notebooks,
            savedSearchCount = counts.savedSearches,
            reminderCount = counts.reminders,
            conflictNoteCount = conflictCount,
            verifiedSha256 = true,
        )
    }

    suspend fun dismissPreflight() = withContext(Dispatchers.IO) {
        stagedPreflightArchive?.delete()
        stagedPreflightArchive = null
        stagedPreflightSource = null
        preflightDir.deleteRecursively()
    }

    suspend fun executeRestore(
        mode: RestoreMode,
        autoBackupBeforeRestore: Boolean,
        zoneId: ZoneId = ZoneId.systemDefault(),
        now: Instant = Instant.now(),
    ): RestoreExecutionResult = withContext(Dispatchers.IO) {
        val archiveFile = stagedPreflightArchive
        if (archiveFile == null || !archiveFile.isFile) {
            return@withContext RestoreExecutionResult(
                RestoreOutcome.Failure("未找到待恢复的预检备份文件", existingLibraryIntact = true),
            )
        }
        val restoreId = newId()
        val extractDir = extractDirForRestore(restoreId)
        val stagingDir = stagingDirForRestore(restoreId)
        var rollbackFile: File? = null
        var dbCommitted = false
        val existingReadyAttachments = attachments.listReady()
        val existingById = existingReadyAttachments.associateBy { it.id }
        val existingAttachmentIds = existingReadyAttachments.map { it.id }
        var currentLeasedIds = existingAttachmentIds
        var currentStagedIds: List<String> = emptyList()

        try {
            journalDir.mkdirs()
            val recordDir = File(extractDir, "records.part")
            val parsed = BackupCodec.readAndValidateArchive(
                archiveFile, attachmentStagingDir = extractDir, recordStagingDir = recordDir,
            )
            val attachmentIdMap = HashMap<String, String>(parsed.manifest.attachments.size)
            val reservedIds = HashSet<String>(existingById.keys).apply {
                parsed.manifest.attachments.forEach { add(it.id) }
            }
            val attachmentsToStage = ArrayList<Pair<BackupAttachmentEntry, String>>()
            var stagedNeededBytes = 0L

            for (att in parsed.manifest.attachments) {
                if (mode == RestoreMode.MERGE_KEEP_CONFLICTS) {
                    val existing = existingById[att.id]
                    if (existing == null) {
                        attachmentIdMap[att.id] = att.id
                        attachmentsToStage.add(att to att.id)
                        stagedNeededBytes += att.bytes
                    } else if (
                        existing.bytes == att.bytes &&
                        existing.sha256.equals(att.sha256, ignoreCase = true) &&
                        attachments.verifySha256(att.id)
                    ) {
                        // Existing ready attachment already matches byte-for-byte; reuse without overwrite.
                        attachmentIdMap[att.id] = att.id
                    } else {
                        // Incoming attachment shares ID with differing local bytes; isolate under a fresh UUID.
                        var freshId = newId()
                        while (!reservedIds.add(freshId)) {
                            freshId = newId()
                        }
                        attachmentIdMap[att.id] = freshId
                        attachmentsToStage.add(att to freshId)
                        stagedNeededBytes += att.bytes
                    }
                } else {
                    attachmentIdMap[att.id] = att.id
                    attachmentsToStage.add(att to att.id)
                    stagedNeededBytes += att.bytes
                }
            }

            val stagedAttachmentIds = attachmentsToStage.map { it.second }.distinct()
            val leasedIds = (existingAttachmentIds + stagedAttachmentIds + attachmentIdMap.values).distinct()
            currentStagedIds = stagedAttachmentIds
            currentLeasedIds = leasedIds

            val neededBytes = stagedNeededBytes + 2L * 1024L * 1024L
            if (attachments.availableSpace() < neededBytes) {
                throw BackupValidationException("设备可用存储空间不足，无法安全还原备份")
            }

            if (autoBackupBeforeRestore) {
                rollbacksDir.mkdirs()
                val rb = File(rollbacksDir, "rollback-${restoreId}.zip")
                exportFullBackupToFile(rb, now)
                rollbackFile = rb
                writeJournal(
                    RestoreJournalRecord(
                        id = restoreId,
                        phase = RestorePhase.ROLLBACK_SAVED,
                        mode = mode,
                        rollbackZipPath = rb.absolutePath,
                        leasedAttachmentIds = leasedIds,
                        stagedAttachmentIds = emptyList(),
                        updatedAt = timestamp.format(now),
                    ),
                )
            }

            stagingDir.mkdirs()
            for ((att, targetAttId) in attachmentsToStage) {
                val extractedOriginal = File(File(extractDir, att.id), "original")
                attachments.stageFromBackupFile(
                    id = targetAttId,
                    name = att.originalName,
                    mime = att.mime,
                    expectedBytes = att.bytes,
                    expectedSha256 = att.sha256,
                    sourceOriginalFile = extractedOriginal,
                    stagingFolder = stagingDir,
                )
            }
            writeJournal(
                RestoreJournalRecord(
                    id = restoreId,
                    phase = RestorePhase.STAGED_ATTACHMENTS,
                    mode = mode,
                    rollbackZipPath = rollbackFile?.absolutePath,
                    leasedAttachmentIds = leasedIds,
                    stagedAttachmentIds = stagedAttachmentIds,
                    updatedAt = timestamp.format(now),
                ),
            )

            writeJournal(
                RestoreJournalRecord(
                    id = restoreId,
                    phase = RestorePhase.APPLYING_DB,
                    mode = mode,
                    rollbackZipPath = rollbackFile?.absolutePath,
                    leasedAttachmentIds = leasedIds,
                    stagedAttachmentIds = stagedAttachmentIds,
                    updatedAt = timestamp.format(now),
                ),
            )

            val dbOutcome = applySnapshotToDatabase(
                restoreId = restoreId,
                snapshot = parsed.snapshot,
                mode = mode,
                attachmentIdMap = attachmentIdMap,
                now = now,
                zoneId = zoneId,
            )
            dbCommitted = true

            writeJournal(
                RestoreJournalRecord(
                    id = restoreId,
                    phase = RestorePhase.DB_COMMITTED,
                    mode = mode,
                    rollbackZipPath = rollbackFile?.absolutePath,
                    leasedAttachmentIds = leasedIds,
                    stagedAttachmentIds = stagedAttachmentIds,
                    updatedAt = timestamp.format(now),
                ),
            )

            beforePromoteHook?.invoke()
            attachments.promoteStagedAttachments(
                stagingFolder = stagingDir,
                allowOverwrite = mode == RestoreMode.REPLACE_ALL,
            )
            promoteDraftExtras(restoreId)
            runCatching { database.agent().deleteAction(restoreCommitActionId(restoreId)) }
            extractDir.deleteRecursively()
            stagingDir.deleteRecursively()
            pendingRestoreFile.delete()
            // Remove older rollback snapshots once new restore & rollback finished safely.
            rollbacksDir.listFiles()?.filter { it.isFile && it != rollbackFile }?.forEach { it.delete() }
            runCatching { attachments.collectUnreferenced(database, activeLeaseIds()) }
            dismissPreflight()

            val pausedNote = buildReminderSummaryNote(
                pausedPastDueCount = dbOutcome.pausedPastDueReminders,
                activeScheduledCount = dbOutcome.activeReminders.size,
            )
            RestoreExecutionResult(
                outcome = RestoreOutcome.Success(
                    mode = mode,
                    importedNotes = dbOutcome.importedNotes,
                    skippedOrRenamedNotes = dbOutcome.skippedOrRenamedNotes,
                    rollbackPath = rollbackFile?.absolutePath,
                    pausedRemindersNote = pausedNote,
                ),
                activeRemindersToSchedule = dbOutcome.activeReminders,
                cancelledReminderIds = dbOutcome.cancelledReminderIds,
            )
        } catch (failure: Throwable) {
            val err = when (failure) {
                is kotlinx.coroutines.CancellationException -> throw failure
                is OutOfMemoryError -> BackupValidationException("设备可用内存不足，无法完成恢复", failure)
                is Exception -> failure
                else -> throw failure
            }
            if (dbCommitted) {
                // Keep stagingDir & pending_restore.json intact so next launch finishes Q053 promotion.
                runCatching { extractDir.deleteRecursively() }
                runCatching {
                    writeJournal(
                        RestoreJournalRecord(
                            id = restoreId,
                            phase = RestorePhase.DB_COMMITTED,
                            mode = mode,
                            rollbackZipPath = rollbackFile?.absolutePath,
                            leasedAttachmentIds = currentLeasedIds,
                            stagedAttachmentIds = currentStagedIds,
                            updatedAt = timestamp.format(now),
                            errorMessage = err.message,
                        ),
                    )
                }
                val rollbackHint = rollbackFile?.absolutePath?.let { "；回滚包已保存在 $it" }.orEmpty()
                RestoreExecutionResult(
                    RestoreOutcome.Failure(
                        message = "数据库已更新，但附件晋升中断（${err.message ?: "存储异常"}）$rollbackHint。请重启应用完成恢复。",
                        existingLibraryIntact = false,
                    ),
                )
            } else {
                runCatching {
                    extractDir.deleteRecursively()
                    stagingDir.deleteRecursively()
                    pendingRestoreFile.delete()
                }
                RestoreExecutionResult(
                    RestoreOutcome.Failure(
                        message = err.message ?: "备份还原失败，原有数据完好无损",
                        existingLibraryIntact = true,
                    ),
                )
            }
        }
    }

    private data class DbRestoreSummary(
        val importedNotes: Int,
        val skippedOrRenamedNotes: Int,
        val activeReminders: List<ReminderEntity>,
        val cancelledReminderIds: List<String>,
        val pausedPastDueReminders: Int,
    )

    private suspend fun applySnapshotToDatabase(
        restoreId: String,
        snapshot: BackupSnapshot,
        mode: RestoreMode,
        attachmentIdMap: Map<String, String>,
        now: Instant,
        zoneId: ZoneId,
    ): DbRestoreSummary = database.withTransaction {
        val notesDao = database.notes()
        val orgDao = database.organization()
        val searchDao = database.search()
        val taskDao = database.taskReminders()
        val discoveryDao = database.discovery()
        val agentDao = database.agent()
        val nowMs = now.toEpochMilli()
        val timeStr = timestamp.format(now)

        if (mode == RestoreMode.REPLACE_ALL) {
            val oldReminderIds = taskDao.allReminders().map { it.id }

            agentDao.clearAllActions()
            agentDao.clearAllRuns()
            discoveryDao.clearAllChunks()
            discoveryDao.clearAllReviewEvents()
            discoveryDao.clearAllReviewSessions()
            discoveryDao.clearAllWalkSteps()
            discoveryDao.clearAllWalkSessions()
            orgDao.clearAllNoteTags()
            orgDao.clearAllNotebookNotes()
            orgDao.clearAllLinks()
            taskDao.clearAllTasks()
            taskDao.clearAllReminders()
            taskDao.clearAllOccurrences()
            notesDao.clearAllDrafts()
            notesDao.clearAllVersions()
            notesDao.clearAllOutbox()
            notesDao.clearAllNotes()
            orgDao.clearAllTags()
            orgDao.clearAllNotebooks()
            searchDao.clearAll()

            val noteIds = HashSet<String>()
            val deletedNoteIds = HashSet<String>()
            for (batch in snapshot.notes.asSequence().chunked(RESTORE_DOCUMENT_BATCH_SIZE)) {
                val notes = batch.map { it.toEntity() }
                for (note in notes) {
                    noteIds.add(note.id)
                    if (note.deletedAt != null) deletedNoteIds.add(note.id)
                }
                notesDao.insertNotes(notes)
                taskDao.insertTasks(rebuildTaskIndexForNotes(notes))
            }
            val tags = snapshot.tags.map { TagEntity(it.id, it.parentId, it.name, it.nameKey) }
            val tagIds = tags.mapTo(HashSet()) { it.id }
            val notebooks = snapshot.notebooks.map { NotebookEntity(it.id, it.name, it.nameKey) }
            val notebookIds = notebooks.mapTo(HashSet()) { it.id }
            tags.chunked(RESTORE_INSERT_BATCH_SIZE).forEach { orgDao.insertTags(it) }
            notebooks.chunked(RESTORE_INSERT_BATCH_SIZE).forEach { orgDao.insertNotebooks(it) }

            val versions = snapshot.versions.asSequence().filter { it.noteId in noteIds }.map {
                NoteVersionEntity(
                    noteId = it.noteId,
                    revision = it.revision,
                    documentJson = it.documentJson,
                    origin = it.origin,
                    createdAt = it.createdAt,
                    title = it.title,
                    attachmentRefs = it.attachmentRefs,
                )
            }
            for (batch in versions.chunked(RESTORE_DOCUMENT_BATCH_SIZE)) notesDao.insertVersions(batch)

            for (batch in snapshot.drafts.asSequence().chunked(RESTORE_DOCUMENT_BATCH_SIZE)) {
                notesDao.insertDrafts(batch.map {
                    stageDraftExtras(restoreId, it.id, it.extrasJson)
                    it.toEntity()
                })
            }

            val noteTags = snapshot.noteTags
                .filter { it.noteId in noteIds && it.tagId in tagIds }
                .map { NoteTagEntity(it.noteId, it.tagId) }
            noteTags.chunked(RESTORE_INSERT_BATCH_SIZE).forEach { orgDao.insertNoteTags(it) }

            val notebookNotes = snapshot.notebookNotes
                .filter { it.noteId in noteIds && it.notebookId in notebookIds }
                .map { NotebookNoteEntity(it.noteId, it.notebookId) }
            notebookNotes.chunked(RESTORE_INSERT_BATCH_SIZE).forEach { orgDao.insertNotebookNotes(it) }

            val noteLinks = snapshot.noteLinks
                .filter { it.sourceId in noteIds }
                .map { NoteLinkEntity(it.sourceId, it.blockId, it.targetId, it.targetRevision) }
            noteLinks.chunked(RESTORE_INSERT_BATCH_SIZE).forEach { orgDao.upsertLinks(it) }

            val searches = snapshot.savedSearches.map { SavedSearch(it.id, it.name, it.keyword, it.scopeJson) }
            searches.chunked(RESTORE_INSERT_BATCH_SIZE).forEach { searchDao.insertAll(it) }

            val normalizedReminders = ArrayList<ReminderEntity>(snapshot.reminders.size)
            val activeToSchedule = ArrayList<ReminderEntity>()
            var pausedPastDue = 0
            for (r in snapshot.reminders) {
                val raw = r.toEntity()
                if (raw.noteId !in noteIds) continue
                val (adjusted, paused, isActive) = normalizeRestoredReminder(
                    reminder = raw,
                    isTrashed = raw.noteId in deletedNoteIds,
                    nowMs = nowMs,
                    timeStr = timeStr,
                    zoneId = zoneId,
                )
                normalizedReminders.add(adjusted)
                if (paused) pausedPastDue++
                if (isActive) activeToSchedule.add(adjusted)
            }
            normalizedReminders.chunked(RESTORE_INSERT_BATCH_SIZE).forEach { taskDao.insertReminders(it) }

            val occurrences = snapshot.taskOccurrences
                .filter { it.noteId in noteIds }
                .map { it.toEntity() }
            occurrences.chunked(RESTORE_INSERT_BATCH_SIZE).forEach { taskDao.insertOccurrences(it) }

            agentDao.insertAction(
                AgentActionEntity(
                    actionId = restoreCommitActionId(restoreId),
                    runId = RESTORE_COMMIT_RUN_ID,
                    actionType = "backup_restore_commit",
                    targetNoteId = null,
                    sourceRevisionsJson = "{}",
                    status = "committed",
                    summary = mode.name,
                    createdAt = timeStr,
                ),
            )

            DbRestoreSummary(
                importedNotes = noteIds.size,
                skippedOrRenamedNotes = 0,
                activeReminders = activeToSchedule,
                cancelledReminderIds = oldReminderIds,
                pausedPastDueReminders = pausedPastDue,
            )
        } else {
            val existingNotes = notesDao.restoreNoteMetadata().associateBy { it.id }
            val existingDraftSlots = notesDao.draftSlots().toHashSet()
            val existingTags = orgDao.tags()
            val existingTagById = existingTags.associateBy { it.id }.toMutableMap()
            val existingTagByKey = existingTags.associateBy { it.parentId to it.nameKey }.toMutableMap()
            val tagIdMap = HashMap<String, String>()
            val tagsToInsert = ArrayList<TagEntity>()

            val orderedTags = sortTagsParentFirst(snapshot.tags)
            for (tag in orderedTags) {
                val mappedParent = if (tag.parentId.isEmpty()) "" else tagIdMap[tag.parentId] ?: tag.parentId
                val byId = existingTagById[tag.id]
                if (byId != null) {
                    tagIdMap[tag.id] = byId.id
                    continue
                }
                val byKey = existingTagByKey[mappedParent to tag.nameKey]
                if (byKey != null) {
                    tagIdMap[tag.id] = byKey.id
                    continue
                }
                val newTag = TagEntity(tag.id, mappedParent, tag.name, tag.nameKey)
                existingTagById[newTag.id] = newTag
                existingTagByKey[newTag.parentId to newTag.nameKey] = newTag
                tagsToInsert.add(newTag)
                tagIdMap[tag.id] = newTag.id
            }
            tagsToInsert.chunked(RESTORE_INSERT_BATCH_SIZE).forEach { orgDao.insertTags(it) }

            val existingNotebooks = orgDao.notebooks()
            val existingNotebookById = existingNotebooks.associateBy { it.id }.toMutableMap()
            val existingNotebookByKey = existingNotebooks.associateBy { it.nameKey }.toMutableMap()
            val notebookIdMap = HashMap<String, String>()
            val notebooksToInsert = ArrayList<NotebookEntity>()
            for (nb in snapshot.notebooks) {
                val byId = existingNotebookById[nb.id]
                if (byId != null) {
                    notebookIdMap[nb.id] = byId.id
                    continue
                }
                val byKey = existingNotebookByKey[nb.nameKey]
                if (byKey != null) {
                    notebookIdMap[nb.id] = byKey.id
                    continue
                }
                val newNb = NotebookEntity(nb.id, nb.name, nb.nameKey)
                existingNotebookById[newNb.id] = newNb
                existingNotebookByKey[newNb.nameKey] = newNb
                notebooksToInsert.add(newNb)
                notebookIdMap[nb.id] = newNb.id
            }
            notebooksToInsert.chunked(RESTORE_INSERT_BATCH_SIZE).forEach { orgDao.insertNotebooks(it) }

            val noteIdMap = HashMap<String, String>()
            val skippedNoteIds = HashSet<String>()
            val conflictNoteIds = HashSet<String>()

            // Pass 1: assign final note IDs across the whole snapshot before rewriting cross-note references.
            for (incoming in snapshot.notes) {
                val existing = existingNotes[incoming.id]
                val hasRemappedAttachment = VersionDiff.extractAttachmentIds(incoming.documentJson)
                    .any { attId -> attachmentIdMap[attId]?.let { it != attId } == true }
                if (existing == null) {
                    noteIdMap[incoming.id] = incoming.id
                } else if (
                    !hasRemappedAttachment &&
                    existing.contentHash == incoming.contentHash &&
                    existing.title == incoming.title &&
                    existing.deletedAt == incoming.deletedAt &&
                    existing.archivedAt == incoming.archivedAt &&
                    existing.pinned == incoming.pinned
                ) {
                    noteIdMap[incoming.id] = existing.id
                    skippedNoteIds.add(incoming.id)
                } else {
                    noteIdMap[incoming.id] = newId()
                    conflictNoteIds.add(incoming.id)
                }
            }

            // Precompute reminder and occurrence ID remapping before Pass 2 document rewriting.
            val existingReminderIds = taskDao.allReminders().mapTo(HashSet()) { it.id }
            val reminderIdMap = HashMap<String, String>()
            val existingOccIds = taskDao.allOccurrences().mapTo(HashSet()) { it.id }
            val occIdMap = HashMap<String, String>()

            for (r in snapshot.reminders) {
                if (r.noteId in skippedNoteIds) continue
                val mappedNote = noteIdMap[r.noteId] ?: continue
                val freshReminderId = if (r.id in existingReminderIds || mappedNote != r.noteId) newId() else r.id
                existingReminderIds.add(freshReminderId)
                reminderIdMap[r.id] = freshReminderId
                r.occurrenceId?.takeIf { it.isNotBlank() }?.let { rawOccId ->
                    if (!occIdMap.containsKey(rawOccId)) {
                        val mappedOcc = if (rawOccId in existingOccIds || mappedNote != r.noteId) newId() else rawOccId
                        existingOccIds.add(mappedOcc)
                        occIdMap[rawOccId] = mappedOcc
                    }
                }
            }
            for (occ in snapshot.taskOccurrences) {
                if (occ.noteId in skippedNoteIds) continue
                val mappedNote = noteIdMap[occ.noteId] ?: continue
                if (!occIdMap.containsKey(occ.id)) {
                    val occId = if (occ.id in existingOccIds || mappedNote != occ.noteId) newId() else occ.id
                    existingOccIds.add(occId)
                    occIdMap[occ.id] = occId
                }
            }
            for (incoming in snapshot.notes) {
                if (incoming.id in skippedNoteIds) continue
                val mappedNote = noteIdMap[incoming.id] ?: continue
                for (block in VersionDiff.parseBlocks(incoming.documentJson)) {
                    if (block.type == "checkbox") {
                        (block.raw["reminder_id"] as? String)?.takeIf { it.isNotBlank() }?.let { rawRemId ->
                            if (!reminderIdMap.containsKey(rawRemId) && (rawRemId in existingReminderIds || mappedNote != incoming.id)) {
                                val freshRemId = newId()
                                existingReminderIds.add(freshRemId)
                                reminderIdMap[rawRemId] = freshRemId
                            }
                        }
                        (block.raw["occurrence_id"] as? String)?.takeIf { it.isNotBlank() }?.let { rawOccId ->
                            if (!occIdMap.containsKey(rawOccId) && (rawOccId in existingOccIds || mappedNote != incoming.id)) {
                                val mappedOcc = newId()
                                existingOccIds.add(mappedOcc)
                                occIdMap[rawOccId] = mappedOcc
                            }
                        }
                    }
                }
            }

            // Pass 2: rewrite embedded reference targets, attachment IDs & task IDs inside documentJson.
            val notesToInsert = ArrayList<NoteEntity>(RESTORE_DOCUMENT_BATCH_SIZE)
            val insertedNoteIds = HashSet<String>()
            val deletedInsertedNoteIds = HashSet<String>()
            suspend fun flushNotes() {
                if (notesToInsert.isEmpty()) return
                notesDao.insertNotes(notesToInsert)
                taskDao.insertTasks(rebuildTaskIndexForNotes(notesToInsert))
                notesToInsert.clear()
            }
            var importedCount = 0
            var skippedOrRenamed = 0

            for (incoming in snapshot.notes) {
                if (incoming.id in skippedNoteIds) {
                    skippedOrRenamed++
                    continue
                }
                val targetNoteId = noteIdMap.getValue(incoming.id)
                val (rewrittenDoc, docChanged) = VersionDiff.rewriteDocumentIds(
                    documentJson = incoming.documentJson,
                    noteIdMap = noteIdMap,
                    attachmentIdMap = attachmentIdMap,
                    reminderIdMap = reminderIdMap,
                    occurrenceIdMap = occIdMap,
                )
                val normalizedEntity = if (docChanged) {
                    val decoded = NoteDocument.decode(rewrittenDoc)
                    incoming.toEntity().copy(
                        documentJson = rewrittenDoc,
                        searchText = decoded.text,
                        mediaMask = decoded.mediaMask(),
                        contentHash = BackupCodec.sha256Hex(rewrittenDoc.toByteArray(StandardCharsets.UTF_8)),
                    )
                } else {
                    incoming.toEntity()
                }
                if (incoming.id in conflictNoteIds) {
                    val baseTitle = incoming.title?.takeIf { it.isNotBlank() }
                        ?: VersionDiff.summarizeDocument(normalizedEntity.documentJson, null).first
                    notesToInsert.add(
                        normalizedEntity.copy(
                            id = targetNoteId,
                            title = "$baseTitle（恢复副本）",
                            serverRevision = null,
                            lastDraftId = newId(),
                        ),
                    )
                    skippedOrRenamed++
                } else {
                    notesToInsert.add(normalizedEntity)
                }
                insertedNoteIds.add(targetNoteId)
                if (normalizedEntity.deletedAt != null) deletedInsertedNoteIds.add(targetNoteId)
                importedCount++
                if (notesToInsert.size == RESTORE_DOCUMENT_BATCH_SIZE) flushNotes()
            }
            flushNotes()

            val versionsToInsert = snapshot.versions.asSequence()
                .filter { it.noteId !in skippedNoteIds && noteIdMap[it.noteId] in insertedNoteIds }
                .map {
                    val (rewrittenVerDoc, verChanged) = VersionDiff.rewriteDocumentIds(
                        documentJson = it.documentJson,
                        noteIdMap = noteIdMap,
                        attachmentIdMap = attachmentIdMap,
                        reminderIdMap = reminderIdMap,
                        occurrenceIdMap = occIdMap,
                    )
                    NoteVersionEntity(
                        noteId = noteIdMap.getValue(it.noteId),
                        revision = it.revision,
                        documentJson = rewrittenVerDoc,
                        origin = it.origin,
                        createdAt = it.createdAt,
                        title = it.title,
                        attachmentRefs = if (verChanged) {
                            VersionDiff.encodeAttachmentRefsJson(VersionDiff.extractAttachmentIds(rewrittenVerDoc))
                        } else {
                            it.attachmentRefs
                        },
                    )
                }
            for (batch in versionsToInsert.chunked(RESTORE_DOCUMENT_BATCH_SIZE)) notesDao.insertVersions(batch)

            val restoredNoteIds = noteIdMap.values.toHashSet()
            val draftsToInsert = mutableListOf<DraftEntity>()
            for (d in snapshot.drafts) {
                val savedNoteId = noteIdMap[d.noteId]
                val baseSlot = if (d.slot == "note:${d.noteId}" && savedNoteId != null) {
                    "note:$savedNoteId"
                } else {
                    d.slot
                }
                val slotOccupied = baseSlot in existingDraftSlots
                val extras = remapDraftTags(d.extrasJson, tagIdMap)
                if (slotOccupied) {
                    val local = notesDao.draft(baseSlot)
                    if (local != null && local.noteId == (savedNoteId ?: d.noteId) && local.documentJson == d.documentJson &&
                        readDraftExtras(local.id) == extras
                    ) continue
                }
                val separate = slotOccupied || (savedNoteId == null &&
                    (d.noteId in existingNotes || d.noteId in restoredNoteIds))
                val mappedNoteId = if (separate) newId() else savedNoteId ?: d.noteId
                val mappedSlot = if (separate) "capture:restore-${newId()}" else baseSlot
                val mappedSource = if (mappedSlot.startsWith("capture:")) "share" else d.sourceType
                existingDraftSlots.add(mappedSlot)
                val (rewrittenDraftDoc, _) = VersionDiff.rewriteDocumentIds(
                    documentJson = d.documentJson,
                    noteIdMap = noteIdMap,
                    attachmentIdMap = attachmentIdMap,
                    reminderIdMap = reminderIdMap,
                    occurrenceIdMap = occIdMap,
                    clearTaskIds = separate,
                )
                val draftId = newId()
                stageDraftExtras(restoreId, draftId, extras)
                draftsToInsert.add(d.toEntity().copy(
                    id = draftId,
                    slot = mappedSlot,
                    noteId = mappedNoteId,
                    baseRevision = when {
                        separate -> 0L
                        d.noteId in skippedNoteIds -> existingNotes.getValue(mappedNoteId).revision
                        else -> d.baseRevision
                    },
                    documentJson = rewrittenDraftDoc,
                    sourceType = mappedSource,
                ))
                if (draftsToInsert.size == RESTORE_DOCUMENT_BATCH_SIZE) {
                    notesDao.insertDrafts(draftsToInsert)
                    draftsToInsert.clear()
                }
            }
            if (draftsToInsert.isNotEmpty()) notesDao.insertDrafts(draftsToInsert)

            val noteTagsToInsert = snapshot.noteTags
                .filter { it.noteId !in skippedNoteIds }
                .mapNotNull { nt ->
                    val targetNote = noteIdMap[nt.noteId]?.takeIf { it in insertedNoteIds } ?: return@mapNotNull null
                    val targetTag = tagIdMap[nt.tagId] ?: return@mapNotNull null
                    NoteTagEntity(targetNote, targetTag)
                }
            noteTagsToInsert.chunked(RESTORE_INSERT_BATCH_SIZE).forEach { orgDao.insertNoteTags(it) }

            val notebookNotesToInsert = snapshot.notebookNotes
                .filter { it.noteId !in skippedNoteIds }
                .mapNotNull { nn ->
                    val targetNote = noteIdMap[nn.noteId]?.takeIf { it in insertedNoteIds } ?: return@mapNotNull null
                    val targetNb = notebookIdMap[nn.notebookId] ?: return@mapNotNull null
                    NotebookNoteEntity(targetNote, targetNb)
                }
            notebookNotesToInsert.chunked(RESTORE_INSERT_BATCH_SIZE).forEach { orgDao.insertNotebookNotes(it) }

            val linksToInsert = snapshot.noteLinks
                .filter { it.sourceId !in skippedNoteIds }
                .mapNotNull { link ->
                    val targetSource = noteIdMap[link.sourceId]?.takeIf { it in insertedNoteIds } ?: return@mapNotNull null
                    NoteLinkEntity(
                        sourceId = targetSource,
                        blockId = link.blockId,
                        targetId = noteIdMap[link.targetId] ?: link.targetId,
                        targetRevision = link.targetRevision,
                    )
                }
            linksToInsert.chunked(RESTORE_INSERT_BATCH_SIZE).forEach { orgDao.upsertLinks(it) }

            val existingSearchIds = searchDao.allSavedSearches().mapTo(HashSet()) { it.id }
            val searchesToInsert = snapshot.savedSearches
                .filter { it.id !in existingSearchIds }
                .map { SavedSearch(it.id, it.name, it.keyword, it.scopeJson) }
            searchesToInsert.chunked(RESTORE_INSERT_BATCH_SIZE).forEach { searchDao.insertAll(it) }

            val remindersToInsert = ArrayList<ReminderEntity>()
            val activeToSchedule = ArrayList<ReminderEntity>()
            var pausedPastDue = 0
            for (r in snapshot.reminders) {
                if (r.noteId in skippedNoteIds) continue
                val mappedNote = noteIdMap[r.noteId]?.takeIf { it in insertedNoteIds } ?: continue
                val freshReminderId = reminderIdMap[r.id] ?: continue
                val raw = r.toEntity().copy(
                    id = freshReminderId,
                    noteId = mappedNote,
                    occurrenceId = r.occurrenceId?.let { occIdMap[it] ?: it },
                )
                val (adjusted, paused, isActive) = normalizeRestoredReminder(
                    reminder = raw,
                    isTrashed = mappedNote in deletedInsertedNoteIds,
                    nowMs = nowMs,
                    timeStr = timeStr,
                    zoneId = zoneId,
                )
                remindersToInsert.add(adjusted)
                if (paused) pausedPastDue++
                if (isActive) activeToSchedule.add(adjusted)
            }
            remindersToInsert.chunked(RESTORE_INSERT_BATCH_SIZE).forEach { taskDao.insertReminders(it) }

            val occToInsert = snapshot.taskOccurrences
                .filter { it.noteId !in skippedNoteIds }
                .mapNotNull { occ ->
                    val mappedNote = noteIdMap[occ.noteId]?.takeIf { it in insertedNoteIds } ?: return@mapNotNull null
                    val occId = occIdMap[occ.id] ?: return@mapNotNull null
                    occ.toEntity().copy(
                        id = occId,
                        noteId = mappedNote,
                        reminderId = occ.reminderId?.let { reminderIdMap[it] ?: it },
                    )
                }
            occToInsert.chunked(RESTORE_INSERT_BATCH_SIZE).forEach { taskDao.insertOccurrences(it) }

            agentDao.insertAction(
                AgentActionEntity(
                    actionId = restoreCommitActionId(restoreId),
                    runId = RESTORE_COMMIT_RUN_ID,
                    actionType = "backup_restore_commit",
                    targetNoteId = null,
                    sourceRevisionsJson = "{}",
                    status = "committed",
                    summary = mode.name,
                    createdAt = timeStr,
                ),
            )

            DbRestoreSummary(
                importedNotes = importedCount,
                skippedOrRenamedNotes = skippedOrRenamed,
                activeReminders = activeToSchedule,
                cancelledReminderIds = emptyList(),
                pausedPastDueReminders = pausedPastDue,
            )
        }
    }

    private fun normalizeRestoredReminder(
        reminder: ReminderEntity,
        isTrashed: Boolean,
        nowMs: Long,
        timeStr: String,
        zoneId: ZoneId,
    ): Triple<ReminderEntity, Boolean, Boolean> {
        if (isTrashed || reminder.statusEnum != ReminderStatus.ACTIVE) {
            return Triple(reminder, false, false)
        }
        val triggerMs = reminder.triggerInstantMs
        return if (triggerMs != null && triggerMs <= nowMs) {
            Triple(
                reminder.copy(
                    status = ReminderStatus.MISSED.name,
                    missedSummarized = true,
                    updatedAt = timeStr,
                ),
                true,
                false,
            )
        } else if (triggerMs != null) {
            Triple(
                reminder.copy(zoneId = reminder.zoneId.ifBlank { zoneId.id }),
                false,
                true,
            )
        } else {
            Triple(reminder, false, false)
        }
    }

    private fun buildReminderSummaryNote(
        pausedPastDueCount: Int,
        activeScheduledCount: Int,
    ): String? = when {
        pausedPastDueCount > 0 && activeScheduledCount > 0 ->
            "已暂停 $pausedPastDueCount 条过期提醒，并重新注册 $activeScheduledCount 条未到期提醒。"
        pausedPastDueCount > 0 ->
            "已将 $pausedPastDueCount 条过期提醒标记为已错过，避免恢复时集中弹窗。"
        activeScheduledCount > 0 ->
            "已重新恢复 $activeScheduledCount 条未到期定时提醒。"
        else -> null
    }

    private fun rebuildTaskIndexForNotes(notes: List<NoteEntity>): List<TaskIndexEntity> {
        val result = ArrayList<TaskIndexEntity>()
        for (note in notes) {
            val doc = runCatching { NoteDocument.decode(note.documentJson) }.getOrNull() ?: continue
            for (block in doc.blocks) {
                if (block.type == "checkbox") {
                    result.add(
                        TaskIndexEntity(
                            noteId = note.id,
                            blockId = block.id,
                            checked = block.checked,
                            dueDate = block.dueDate,
                            occurrenceId = block.occurrenceId,
                        ),
                    )
                }
            }
        }
        return result
    }

    private fun sortTagsParentFirst(tags: List<BackupTagRecord>): List<BackupTagRecord> {
        val remaining = LinkedHashMap<String, BackupTagRecord>()
        tags.forEach { remaining[it.id] = it }
        val ordered = ArrayList<BackupTagRecord>(tags.size)
        val resolvedIds = HashSet<String>()
        while (remaining.isNotEmpty()) {
            val batch = remaining.values.filter { it.parentId.isEmpty() || it.parentId in resolvedIds || it.parentId !in remaining }
            if (batch.isEmpty()) {
                ordered.addAll(remaining.values)
                break
            }
            for (item in batch) {
                ordered.add(item)
                resolvedIds.add(item.id)
                remaining.remove(item.id)
            }
        }
        return ordered
    }

    private suspend fun captureSnapshot(now: Instant): BackupSnapshot {
        val notesDao = database.notes()
        val orgDao = database.organization()
        val searchDao = database.search()
        val taskDao = database.taskReminders()
        val readyAttachments = attachments.listReady()

        return database.withTransaction {
            BackupSnapshot(
                exportedAt = timestamp.format(now),
                appVersion = BuildConfig.VERSION_NAME,
                dbSchemaVersion = NotesDatabase.SCHEMA_VERSION,
                notes = notesDao.allNotes().map { it.toBackupRecord() },
                versions = notesDao.allVersions().map {
                    BackupVersionRecord(
                        noteId = it.noteId,
                        revision = it.revision,
                        documentJson = it.documentJson,
                        origin = it.origin,
                        createdAt = it.createdAt,
                        title = it.title,
                        attachmentRefs = it.attachmentRefs,
                    )
                },
                drafts = notesDao.allDrafts().map { it.toBackupRecord() },
                tags = orgDao.tags().map { BackupTagRecord(it.id, it.parentId, it.name, it.nameKey) },
                notebooks = orgDao.notebooks().map { BackupNotebookRecord(it.id, it.name, it.nameKey) },
                noteTags = orgDao.noteTags().map { BackupNoteTagRecord(it.noteId, it.tagId) },
                notebookNotes = orgDao.notebookNotes().map { BackupNotebookNoteRecord(it.noteId, it.notebookId) },
                noteLinks = orgDao.noteLinks().map { BackupNoteLinkRecord(it.sourceId, it.blockId, it.targetId, it.targetRevision) },
                savedSearches = searchDao.allSavedSearches().map { BackupSavedSearchRecord(it.id, it.name, it.keyword, it.scopeJson) },
                reminders = taskDao.allReminders().map { it.toBackupRecord() },
                taskOccurrences = taskDao.allOccurrences().map { it.toBackupRecord() },
                attachments = readyAttachments.map { att ->
                    val ext = BackupCodec.safeAttachmentExtension(att.mime, att.name)
                    BackupAttachmentEntry(
                        id = att.id,
                        archivePath = "attachments/${att.id}.$ext",
                        originalName = att.name,
                        mime = att.mime,
                        bytes = att.bytes,
                        sha256 = att.sha256,
                    )
                },
            )
        }
    }

    private fun copyFileToUri(source: File, destinationUri: Uri) {
        val out = runCatching { context.contentResolver.openOutputStream(destinationUri, "wt") }.getOrNull()
            ?: context.contentResolver.openOutputStream(destinationUri)
            ?: throw IOException("无法打开导出目标文件")
        out.use { target ->
            source.inputStream().use { input ->
                input.copyTo(target, 64 * 1024)
            }
            target.flush()
        }
    }

    private fun copyUriToFile(sourceUri: Uri, destination: File) {
        val input = context.contentResolver.openInputStream(sourceUri)
            ?: throw IOException("无法读取所选备份文件")
        val maxCopyBytes = BackupConstants.MAX_TOTAL_UNCOMPRESSED_BYTES + 64L * 1024L * 1024L
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        input.use { src ->
            FileOutputStream(destination).use { out ->
                while (true) {
                    val read = src.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxCopyBytes) {
                        throw BackupValidationException("备份文件体积超过安全上限")
                    }
                    out.write(buffer, 0, read)
                }
                out.fd.sync()
            }
        }
        if (total == 0L) {
            throw BackupValidationException("所选备份文件为空")
        }
    }

    private fun writeLeases(attachmentIds: List<String>) {
        val json = MiniJson.encode(
            linkedMapOf("leased_attachment_ids" to attachmentIds.distinct()),
        )
        writeAtomicText(leasesFile, json)
    }

    private fun writeJournal(record: RestoreJournalRecord) {
        val json = MiniJson.encode(
            linkedMapOf(
                "id" to record.id,
                "phase" to record.phase.name,
                "mode" to record.mode.name,
                "rollback_zip_path" to record.rollbackZipPath,
                "leased_attachment_ids" to record.leasedAttachmentIds,
                "staged_attachment_ids" to record.stagedAttachmentIds,
                "updated_at" to record.updatedAt,
                "error_message" to record.errorMessage,
            ),
        )
        writeAtomicText(pendingRestoreFile, json)
    }

    private fun writeAtomicText(target: File, content: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(content.toByteArray(StandardCharsets.UTF_8))
            out.fd.sync()
        }
        Os.rename(tmp.path, target.path)
    }

    private fun parseRestoreJournal(text: String): RestoreJournalRecord {
        val m = MiniJson.parseObject(text)
        return RestoreJournalRecord(
            id = m.reqString("id"),
            phase = RestorePhase.valueOf(m.reqString("phase")),
            mode = RestoreMode.valueOf(m.reqString("mode")),
            rollbackZipPath = m.optString("rollback_zip_path"),
            leasedAttachmentIds = m.reqArray("leased_attachment_ids").mapNotNull { it as? String },
            stagedAttachmentIds = m.reqArray("staged_attachment_ids").mapNotNull { it as? String },
            updatedAt = m.reqString("updated_at"),
            errorMessage = m.optString("error_message"),
        )
    }

    private fun NoteEntity.toBackupRecord() = BackupNoteRecord(
        id = id,
        title = title,
        documentJson = documentJson,
        searchText = searchText,
        authorType = authorType,
        sourceType = sourceType,
        sourceUrl = sourceUrl,
        aiPolicy = aiPolicy,
        createdAt = createdAt,
        updatedAt = updatedAt,
        revision = revision,
        serverRevision = serverRevision,
        contentHash = contentHash,
        archivedAt = archivedAt,
        deletedAt = deletedAt,
        pinned = pinned,
        lastDraftId = lastDraftId,
        committedSeq = committedSeq,
        mediaMask = mediaMask,
    )

    private fun BackupNoteRecord.toEntity() = NoteEntity(
        id = id,
        title = title,
        documentJson = documentJson,
        searchText = searchText,
        authorType = authorType,
        sourceType = sourceType,
        sourceUrl = sourceUrl,
        aiPolicy = aiPolicy,
        createdAt = createdAt,
        updatedAt = updatedAt,
        revision = revision,
        serverRevision = serverRevision,
        contentHash = contentHash,
        archivedAt = archivedAt,
        deletedAt = deletedAt,
        pinned = pinned,
        lastDraftId = lastDraftId,
        committedSeq = committedSeq,
        mediaMask = mediaMask,
    )

    private fun readDraftExtras(id: String): String? {
        if (!BackupCodec.isSafeAttachmentId(id)) return null
        val file = File(draftExtrasDir, "$id.json")
        return file.takeIf { it.isFile }?.readText(StandardCharsets.UTF_8)?.takeUnless { it == "{}" }
    }

    private fun DraftEntity.toBackupRecord() = BackupDraftRecord(
        id = id,
        slot = slot,
        noteId = noteId,
        baseRevision = baseRevision,
        documentJson = documentJson,
        editSeq = editSeq,
        updatedAt = updatedAt,
        sourceType = sourceType,
        sourceUrl = sourceUrl,
        extrasJson = readDraftExtras(id),
    )

    private fun BackupDraftRecord.toEntity() = DraftEntity(
        id = id,
        slot = slot,
        noteId = noteId,
        baseRevision = baseRevision,
        documentJson = documentJson,
        editSeq = editSeq,
        updatedAt = updatedAt,
        sourceType = sourceType,
        sourceUrl = sourceUrl,
    )

    private fun ReminderEntity.toBackupRecord() = BackupReminderRecord(
        id = id,
        noteId = noteId,
        blockId = blockId,
        occurrenceId = occurrenceId,
        zoneId = zoneId,
        localDate = localDate,
        localTime = localTime,
        triggerInstantMs = triggerInstantMs,
        policy = policy,
        status = status,
        recurrence = recurrence,
        snoozedUntilMs = snoozedUntilMs,
        missedSummarized = missedSummarized,
        revision = revision,
        updatedAt = updatedAt,
    )

    private fun BackupReminderRecord.toEntity() = ReminderEntity(
        id = id,
        noteId = noteId,
        blockId = blockId,
        occurrenceId = occurrenceId,
        zoneId = zoneId,
        localDate = localDate,
        localTime = localTime,
        triggerInstantMs = triggerInstantMs,
        policy = policy,
        status = status,
        recurrence = recurrence,
        snoozedUntilMs = snoozedUntilMs,
        missedSummarized = missedSummarized,
        revision = revision,
        updatedAt = updatedAt,
    )

    private fun TaskOccurrenceEntity.toBackupRecord() = BackupTaskOccurrenceRecord(
        id = id,
        noteId = noteId,
        blockId = blockId,
        reminderId = reminderId,
        scheduledDate = scheduledDate,
        titleSnapshot = titleSnapshot,
        recurrence = recurrence,
        completedAt = completedAt,
    )

    private fun BackupTaskOccurrenceRecord.toEntity() = TaskOccurrenceEntity(
        id = id,
        noteId = noteId,
        blockId = blockId,
        reminderId = reminderId,
        scheduledDate = scheduledDate,
        titleSnapshot = titleSnapshot,
        recurrence = recurrence,
        completedAt = completedAt,
    )

    companion object {
        private const val RESTORE_COMMIT_RUN_ID = "__system_restore__"
        private const val RESTORE_INSERT_BATCH_SIZE = 500
        private const val RESTORE_DOCUMENT_BATCH_SIZE = 8

        internal fun restoreCommitActionId(restoreId: String): String = "__restore_commit__:$restoreId"
    }
}
