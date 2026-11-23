package io.github.z3275630ops.floatingnotes

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.z3275630ops.floatingnotes.core.model.NoteBlock
import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import io.github.z3275630ops.floatingnotes.core.model.newId
import io.github.z3275630ops.floatingnotes.data.agent.AgentActionCommitResult
import io.github.z3275630ops.floatingnotes.data.agent.AgentActionEntity
import io.github.z3275630ops.floatingnotes.data.agent.AgentRepository
import io.github.z3275630ops.floatingnotes.data.backup.BackupCodec
import io.github.z3275630ops.floatingnotes.data.backup.BackupManager
import io.github.z3275630ops.floatingnotes.data.backup.MiniJson
import io.github.z3275630ops.floatingnotes.data.backup.RestoreMode
import io.github.z3275630ops.floatingnotes.data.backup.RestoreOutcome
import io.github.z3275630ops.floatingnotes.data.backup.RestorePhase
import io.github.z3275630ops.floatingnotes.data.discovery.DiscoveryRepository
import io.github.z3275630ops.floatingnotes.data.discovery.DuplicateNoteGroup
import io.github.z3275630ops.floatingnotes.data.discovery.ExplorePreferences
import io.github.z3275630ops.floatingnotes.data.discovery.LocalTextIntelligence
import io.github.z3275630ops.floatingnotes.data.local.AttachmentStore
import io.github.z3275630ops.floatingnotes.data.local.DraftEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteTagEntity
import io.github.z3275630ops.floatingnotes.data.local.NotesDatabase
import io.github.z3275630ops.floatingnotes.data.local.NotesRepository
import io.github.z3275630ops.floatingnotes.data.local.OrganizationRepository
import io.github.z3275630ops.floatingnotes.data.local.RecurrenceRule
import io.github.z3275630ops.floatingnotes.data.local.ReminderPolicy
import io.github.z3275630ops.floatingnotes.data.local.ReminderStatus
import io.github.z3275630ops.floatingnotes.data.local.RestoreVersionResult
import io.github.z3275630ops.floatingnotes.data.local.StaleDraftException
import io.github.z3275630ops.floatingnotes.data.local.TagEntity
import io.github.z3275630ops.floatingnotes.data.local.TaskToggleResult
import io.github.z3275630ops.floatingnotes.data.local.tagNameKey
import io.github.z3275630ops.floatingnotes.feature.tasks.ReminderReceiver
import io.github.z3275630ops.floatingnotes.feature.tasks.ReminderScheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class P0HardeningEndToEndTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val appContext get() = InstrumentationRegistry.getInstrumentation().targetContext

    private data class Env(
        val db: NotesDatabase,
        val notes: NotesRepository,
        val org: OrganizationRepository,
        val attachments: AttachmentStore,
        val backup: BackupManager,
        val discovery: DiscoveryRepository,
        val agent: AgentRepository,
    )

    private suspend fun withTempEnv(block: suspend (Env) -> Unit) {
        val rootDir = File(temp.root, "env-${newId()}").apply { mkdirs() }
        val db = Room.inMemoryDatabaseBuilder(appContext, NotesDatabase::class.java).build()
        val attachmentsDir = File(rootDir, "attachments").apply { mkdirs() }
        val store = AttachmentStore(appContext, attachmentsDir) { 10L * 1024L * 1024L * 1024L }
        val notes = NotesRepository(db)
        val org = OrganizationRepository(db)
        val backup = BackupManager(appContext, db, store)
        val prefs = ExplorePreferences(appContext, prefsName = "e2e_p0_${newId()}")
        val discovery = DiscoveryRepository(db, notes, prefs)
        val agent = AgentRepository(db, notes, org, discovery)
        try {
            block(Env(db, notes, org, store, backup, discovery, agent))
        } finally {
            db.close()
        }
    }

    @Test
    fun dailyReviewKeepsTagIdsAlignedWithSortedTagNames() = runBlocking {
        withTempEnv { env ->
            val note = env.notes.commit(
                env.notes.newDraft("manual").copy(
                    documentJson = NoteDocument(listOf(NoteBlock.paragraph("标签排序对齐回归验证内容"))).encode(),
                    editSeq = 1L,
                ),
            )
            // Insert 'Zebra' first so natural list order differs from alphabetical name sort.
            val zTag = TagEntity("tag-z", "", "zeta", tagNameKey("zeta"))
            val aTag = TagEntity("tag-a", "", "alpha", tagNameKey("alpha"))
            env.db.organization().insertTag(zTag)
            env.db.organization().insertTag(aTag)
            env.db.organization().attachTag(NoteTagEntity(note.id, zTag.id))
            env.db.organization().attachTag(NoteTagEntity(note.id, aTag.id))

            val snapshot = env.discovery.loadOrCreateDailyReview(
                startExtraRound = true,
                now = Instant.parse("2026-11-23T08:00:00Z"),
            )
            val card = snapshot.cards.first { it.note.id == note.id }
            assertEquals(listOf("alpha", "zeta"), card.tagNames)
            assertEquals(listOf("tag-a", "tag-z"), card.tagIds)
        }
    }

    @Test
    fun duplicateMergePreservesCheckboxesAttachmentsRefsAndRespectsAiPolicy() = runBlocking {
        withTempEnv { env ->
            val scratch = File(appContext.filesDir, "recordings/p0-test-${newId()}").apply { mkdirs() }
            val attFile = File(scratch, "clip.png")
            val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.parseColor("#34C759"))
            }
            FileOutputStream(attFile).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            val attUri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.attachments", attFile)
            val attachment = try {
                env.attachments.import(attUri, "image/png", id = newId())
            } finally {
                scratch.deleteRecursively()
            }

            val targetNote = env.notes.commit(
                env.notes.newDraft("ref-target").copy(
                    documentJson = NoteDocument(listOf(NoteBlock.paragraph("外部关联目标笔记"))).encode(),
                    editSeq = 1L,
                ),
            )

            val doc1 = NoteDocument(
                listOf(
                    NoteBlock.paragraph("第一段项目复盘结论一致"),
                    NoteBlock.checkbox(text = "核对清单项", checked = false, dueDate = "2026-11-30", reminderId = "rem-orig", occurrenceId = "occ-orig"),
                    NoteBlock.attachment("image", attachment.id),
                ),
            )
            val doc2 = NoteDocument(
                listOf(
                    NoteBlock.paragraph("第一段项目复盘结论一致"),
                    NoteBlock.checkbox(text = "核对清单项", checked = true),
                    NoteBlock.reference(targetNote.id, targetNote.revision, "外部关联目标"),
                ),
            )
            val n1 = env.notes.commit(env.notes.newDraft("m1").copy(documentJson = doc1.encode(), editSeq = 1L))
            val n2 = env.notes.commit(env.notes.newDraft("m2").copy(documentJson = doc2.encode(), editSeq = 1L))

            val group = DuplicateNoteGroup(
                groupId = "grp-rich",
                primaryNote = n1,
                duplicateNotes = listOf(n2),
                similarityPercent = 92,
                reason = "测试合并",
                previewText = "",
            )

            val merged = env.agent.commitMergeDuplicateGroup(group, moveDuplicatesToTrash = false)
            assertTrue(merged is AgentActionCommitResult.Committed)
            val createdId = (merged as AgentActionCommitResult.Committed).noteId
            val created = env.notes.loadNote(createdId)!!
            val createdDoc = NoteDocument.decode(created.documentJson)

            val cb = createdDoc.blocks.firstOrNull { it.type == "checkbox" }
            assertNotNull(cb)
            assertTrue(cb!!.checked)
            assertEquals("2026-11-30", cb.dueDate)
            assertNull(cb.reminderId)
            assertTrue(createdDoc.blocks.any { it.type == "image" && it.attachmentId == attachment.id })
            assertTrue(createdDoc.blocks.any { it.type == "reference" && it.referenceTarget == targetNote.id })

            // Editing a source note later generates a new default actionId instead of staying stuck as AlreadyApplied,
            // and excluding aiPolicy blocks merging cleanly.
            val n1Excluded = n1.copy(aiPolicy = "exclude", revision = n1.revision + 1L)
            env.db.notes().putNote(n1Excluded)
            val policyGroup = group.copy(primaryNote = n1Excluded)
            val conflictRes = env.agent.commitMergeDuplicateGroup(policyGroup, moveDuplicatesToTrash = false)
            assertTrue(conflictRes is AgentActionCommitResult.ConflictDetected)
        }
    }

    @Test
    fun interruptedRestoreRecoversViaDbCommitSentinelEvenWhenJournalStillApplyingDb() = runBlocking {
        withTempEnv { env ->
            val restoreId = newId()
            val attId = newId()
            val scratch = File(appContext.filesDir, "recordings/p0-crash-${newId()}").apply { mkdirs() }
            val srcFile = File(scratch, "crash.png").apply { writeBytes("sentinel-bytes-123".toByteArray()) }
            val bytes = srcFile.readBytes()
            val sha = BackupCodec.sha256Hex(bytes)

            env.db.notes().insertNotes(
                listOf(
                    NoteEntity(
                        id = "n-sentinel",
                        title = "崩溃恢复",
                        documentJson = NoteDocument(listOf(NoteBlock.attachment("image", attId))).encode(),
                        searchText = "",
                        sourceType = "manual",
                        sourceUrl = null,
                        createdAt = "2026-11-23T08:00:00.000Z",
                        updatedAt = "2026-11-23T08:00:00.000Z",
                        revision = 1L,
                        contentHash = "h",
                        lastDraftId = "d",
                        committedSeq = 1L,
                    ),
                ),
            )
            env.db.agent().insertAction(
                AgentActionEntity(
                    actionId = BackupManager.restoreCommitActionId(restoreId),
                    runId = "__system_restore__",
                    actionType = "backup_restore_commit",
                    targetNoteId = null,
                    sourceRevisionsJson = "{}",
                    status = "committed",
                    summary = RestoreMode.REPLACE_ALL.name,
                    createdAt = "2026-11-23T08:00:00.000Z",
                ),
            )

            val stageDir = env.backup.stagingDirForRestore(restoreId).apply { mkdirs() }
            env.attachments.stageFromBackupFile(
                id = attId,
                name = "crash.png",
                mime = "image/png",
                expectedBytes = bytes.size.toLong(),
                expectedSha256 = sha,
                sourceOriginalFile = srcFile,
                stagingFolder = stageDir,
            )
            scratch.deleteRecursively()

            val journalFile = File(env.backup.journalDir, "pending_restore.json").apply { parentFile?.mkdirs() }
            journalFile.writeText(
                MiniJson.encode(
                    linkedMapOf(
                        "id" to restoreId,
                        "phase" to RestorePhase.APPLYING_DB.name,
                        "mode" to RestoreMode.REPLACE_ALL.name,
                        "rollback_zip_path" to null,
                        "leased_attachment_ids" to listOf(attId),
                        "staged_attachment_ids" to listOf(attId),
                        "updated_at" to "2026-11-23T08:00:00.000Z",
                        "error_message" to null,
                    ),
                ),
                StandardCharsets.UTF_8,
            )

            assertTrue(env.backup.recoverInterruptedRestoreIfNeeded())
            val promoted = env.attachments.find(attId)
            assertNotNull(promoted)
            assertArrayEquals(bytes, env.attachments.original(promoted!!).readBytes())
            assertNull(env.db.agent().actionById(BackupManager.restoreCommitActionId(restoreId)))
        }
    }

    @Test
    fun mergeRestorePreservesStandaloneDraftsAndIsolatesRecurringOccurrences() = runBlocking {
        val zipFile = File(temp.root, "p0-merge-draft-occ.zip")
        var sharedNoteId = ""
        var origOccurrenceId = ""

        withTempEnv { src ->
            val savedNote = src.notes.commit(
                src.notes.newDraft("shared").copy(
                    documentJson = NoteDocument(
                        listOf(
                            NoteBlock.paragraph("原版共享笔记"),
                            NoteBlock.checkbox("每天打卡", checked = false, dueDate = "2026-11-25"),
                        ),
                    ).encode(),
                    editSeq = 1L,
                ),
            )
            sharedNoteId = savedNote.id
            val cbId = NoteDocument.decode(savedNote.documentJson).blocks.first { it.type == "checkbox" }.id
            val sched = src.notes.setTaskSchedule(
                noteId = sharedNoteId,
                blockId = cbId,
                dueDate = "2026-11-25",
                reminderTime = "09:00",
                zoneId = ZoneId.of("UTC"),
                policy = ReminderPolicy.FOLLOW_LOCAL,
                recurrence = RecurrenceRule.DAILY,
            )
            origOccurrenceId = sched.reminder!!.occurrenceId!!

            // Save a standalone unsaved manual draft.
            src.notes.checkpoint(
                DraftEntity(
                    id = "src-manual-draft",
                    slot = "manual",
                    noteId = "standalone-note-id",
                    baseRevision = 0L,
                    documentJson = NoteDocument(listOf(NoteBlock.paragraph("备份中的未保存新草稿"))).encode(),
                    editSeq = 2L,
                    updatedAt = "2026-11-23T08:00:00.000Z",
                    sourceType = "manual",
                    sourceUrl = null,
                ),
            )
            src.backup.exportFullBackupToFile(zipFile)
        }

        withTempEnv { dst ->
            // Create conflicting saved note with same sharedNoteId + existing local "manual" draft.
            val localNote = NoteEntity(
                id = sharedNoteId,
                title = "本地已改版本",
                documentJson = NoteDocument(
                    listOf(
                        NoteBlock.paragraph("本地修改版本"),
                        NoteBlock.checkbox("本地打卡", checked = false),
                    ),
                ).encode(),
                searchText = "本地修改版本",
                sourceType = "manual",
                sourceUrl = null,
                createdAt = "2026-11-23T07:00:00.000Z",
                updatedAt = "2026-11-23T07:30:00.000Z",
                revision = 1L,
                contentHash = "localdiffhash",
                lastDraftId = "local-draft",
                committedSeq = 1L,
            )
            dst.db.notes().insertNotes(listOf(localNote))
            val localOcc = io.github.z3275630ops.floatingnotes.data.local.TaskOccurrenceEntity(
                id = origOccurrenceId,
                noteId = sharedNoteId,
                blockId = "orig-cb",
                reminderId = null,
                scheduledDate = "2026-11-25",
                titleSnapshot = "原记录期次",
                recurrence = RecurrenceRule.DAILY.name,
                completedAt = "2026-11-22T08:00:00.000Z",
            )
            dst.db.taskReminders().upsertOccurrence(localOcc)

            dst.notes.checkpoint(
                DraftEntity(
                    id = "dst-manual-draft",
                    slot = "manual",
                    noteId = "dst-standalone",
                    baseRevision = 0L,
                    documentJson = NoteDocument(listOf(NoteBlock.paragraph("目标机本地草稿"))).encode(),
                    editSeq = 1L,
                    updatedAt = "2026-11-23T08:00:00.000Z",
                    sourceType = "manual",
                    sourceUrl = null,
                ),
            )

            val preflight = dst.backup.preflightBackupFile(zipFile)
            assertTrue(preflight.verifiedSha256)
            val exec = dst.backup.executeRestore(mode = RestoreMode.MERGE_KEEP_CONFLICTS, autoBackupBeforeRestore = false)
            assertTrue(exec.outcome is RestoreOutcome.Success)

            // Both standalone drafts survive.
            val allDrafts = dst.db.notes().allDrafts()
            assertTrue(allDrafts.any { it.documentJson.contains("备份中的未保存新草稿") && it.sourceType == "share" })
            assertTrue(allDrafts.any { it.documentJson.contains("目标机本地草稿") && it.slot == "manual" })

            // Conflicting copy remapped occurrence_id so toggling copy never overwrites localOcc.
            val copyNote = dst.db.notes().allNotes().first { it.id != sharedNoteId && (it.title ?: "").contains("恢复副本") }
            val copyCb = NoteDocument.decode(copyNote.documentJson).blocks.first { it.type == "checkbox" }
            assertNotNull(copyCb.occurrenceId)
            assertNotEquals(origOccurrenceId, copyCb.occurrenceId)

            val toggleCopy = dst.notes.toggleTaskChecked(copyNote.id, copyCb.id, checked = true)
            assertTrue(toggleCopy is TaskToggleResult.Updated)
            val localOccAfter = dst.db.taskReminders().allOccurrences().first { it.id == origOccurrenceId }
            assertEquals("原记录期次", localOccAfter.titleSnapshot)
        }
    }

    @Test
    fun notificationToggleRotatesDraftIdClearedReminderHiddenFromTasksAndOldVersionCancelsNewReminder() = runBlocking {
        withTempEnv { env ->
            val futureDate = LocalDate.now(ZoneId.of("UTC")).plusDays(3).toString()
            val v1Note = env.notes.commit(
                env.notes.newDraft("n").copy(
                    documentJson = NoteDocument(
                        listOf(
                            NoteBlock.paragraph("任务文档"),
                            NoteBlock.checkbox("完成合同审查"),
                        ),
                    ).encode(),
                    editSeq = 1L,
                ),
            )
            val cbId = NoteDocument.decode(v1Note.documentJson).blocks.first { it.type == "checkbox" }.id
            val sched = env.notes.setTaskSchedule(
                noteId = v1Note.id,
                blockId = cbId,
                dueDate = futureDate,
                reminderTime = "10:30",
                zoneId = ZoneId.of("UTC"),
            )
            val rev1Id = sched.reminder!!.id
            val remRev = sched.reminder!!.revision

            // Notification completion rotates lastDraftId -> stale editor draft gets StaleDraftException.
            val openEditorDraft = DraftEntity(
                id = sched.note.lastDraftId,
                slot = "note:${v1Note.id}",
                noteId = v1Note.id,
                baseRevision = sched.note.revision,
                documentJson = NoteDocument(
                    listOf(
                        NoteBlock.paragraph("编辑器未保存文本"),
                        NoteBlock.checkbox("完成合同审查"),
                    ),
                ).encode(),
                editSeq = 2L,
                updatedAt = "2026-11-23T08:00:00.000Z",
                sourceType = "manual",
                sourceUrl = null,
            )
            val notifIntent = Intent(ReminderScheduler.ACTION_COMPLETE_TASK).apply {
                putExtra(ReminderScheduler.EXTRA_NOTE_ID, v1Note.id)
                putExtra(ReminderScheduler.EXTRA_BLOCK_ID, cbId)
                putExtra(ReminderScheduler.EXTRA_REMINDER_ID, rev1Id)
                putExtra(ReminderScheduler.EXTRA_REMINDER_REVISION, remRev)
            }
            ReminderReceiver.handleIntent(appContext, notifIntent, env.notes, ZoneId.of("UTC"))

            try {
                env.notes.commit(openEditorDraft)
                fail("Expected StaleDraftException when committing stale editor draft after notification toggle")
            } catch (_: StaleDraftException) {
                // Expected: prevents silently overwriting notification completion back to unchecked.
            }

            // Re-enable a reminder on unchecked state, clear it, verify projection clears & snooze refuses CANCELLED.
            env.notes.toggleTaskChecked(v1Note.id, cbId, checked = false, zoneId = ZoneId.of("UTC"))
            val cleared = env.notes.clearTaskSchedule(v1Note.id, cbId)
            val projected = env.notes.observeTaskItems().first().first { it.noteId == v1Note.id && it.blockId == cbId }
            assertNull(projected.reminderId)
            assertNull(projected.reminderLocalDate)
            assertNull(projected.reminderStatus)
            assertNull(env.notes.snoozeReminder(rev1Id))

            // Add reminder again (vN), then restore v1 (no reminder) -> vN reminder must be CANCELLED cleanly.
            val resched = env.notes.setTaskSchedule(
                noteId = v1Note.id,
                blockId = cbId,
                dueDate = futureDate,
                reminderTime = "14:00",
                zoneId = ZoneId.of("UTC"),
            )
            val staleRem = resched.reminder!!
            // Outdated revision intent must be ignored after schedule revision bumps.
            assertNull(env.notes.snoozeReminder(staleRem.id, expectedRevision = staleRem.revision - 1L))

            val restoreRes = env.notes.restoreVersion(
                noteId = v1Note.id,
                targetRevision = v1Note.revision,
                expectedCurrentRevision = resched.note.revision,
                zoneId = ZoneId.of("UTC"),
            )
            assertTrue(restoreRes is RestoreVersionResult.Restored)
            val restored = restoreRes as RestoreVersionResult.Restored
            assertTrue(staleRem.id in restored.reminderSync.cancelledReminderIds)
            assertEquals(ReminderStatus.CANCELLED, env.db.taskReminders().reminder(staleRem.id)!!.statusEnum)
        }
    }
}
