package io.github.z3275630ops.floatingnotes

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.z3275630ops.floatingnotes.core.model.NoteBlock
import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import io.github.z3275630ops.floatingnotes.core.model.newId
import io.github.z3275630ops.floatingnotes.data.backup.BackupCodec
import io.github.z3275630ops.floatingnotes.data.backup.BackupManager
import io.github.z3275630ops.floatingnotes.data.backup.BackupValidationException
import io.github.z3275630ops.floatingnotes.data.backup.MiniJson
import io.github.z3275630ops.floatingnotes.data.backup.RestoreMode
import io.github.z3275630ops.floatingnotes.data.backup.RestoreOutcome
import io.github.z3275630ops.floatingnotes.data.backup.RestorePhase
import io.github.z3275630ops.floatingnotes.data.backup.VersionDiff
import io.github.z3275630ops.floatingnotes.data.local.AttachmentStore
import io.github.z3275630ops.floatingnotes.data.local.LocalAttachment
import io.github.z3275630ops.floatingnotes.data.local.NotesDatabase
import io.github.z3275630ops.floatingnotes.data.local.NotesRepository
import io.github.z3275630ops.floatingnotes.data.local.OrganizationRepository
import io.github.z3275630ops.floatingnotes.data.local.ReminderStatus
import io.github.z3275630ops.floatingnotes.data.local.RestoreVersionResult
import io.github.z3275630ops.floatingnotes.data.local.SearchRepository
import io.github.z3275630ops.floatingnotes.data.local.NoteScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.util.zip.ZipFile

class HistoryTrashBackupTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val appContext get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext get() = InstrumentationRegistry.getInstrumentation().context

    @Test
    fun migration4To5PreservesDataAddsDeletedIndexAndBackfillsAttachmentRefs() = runBlocking {
        val dbFile = File(temp.root, "legacy-v4.db")
        val attId = "11111111-1111-4111-8111-111111111111"
        val noteId = "note-legacy-v4"
        val docWithMedia = NoteDocument(
            listOf(
                NoteBlock.paragraph("旧版本升级正文"),
                NoteBlock.attachment("image", attId),
            ),
        ).encode()

        createV4DatabaseFromSchemaAsset(dbFile) { sqlDb ->
            sqlDb.insertOrThrow(
                "notes",
                null,
                ContentValues().apply {
                    put("id", noteId)
                    putNull("title")
                    put("document_json", docWithMedia)
                    put("search_text", "旧版本升级正文")
                    put("author_type", "user")
                    put("source_type", "manual")
                    putNull("source_url")
                    put("ai_policy", "inherit")
                    put("created_at", "2026-11-14T10:00:00.000Z")
                    put("updated_at", "2026-11-14T10:00:00.000Z")
                    put("local_revision", 1L)
                    putNull("server_revision")
                    put("content_hash", "legacyhash")
                    putNull("archived_at")
                    putNull("deleted_at")
                    put("pinned", 0)
                    put("last_draft_id", "draft-legacy")
                    put("committed_seq", 1L)
                    put("media_mask", 1)
                },
            )
            sqlDb.insertOrThrow(
                "note_versions",
                null,
                ContentValues().apply {
                    put("note_id", noteId)
                    put("revision", 1L)
                    put("document_json", docWithMedia)
                    put("origin", "edit")
                    put("created_at", "2026-11-14T10:00:00.000Z")
                },
            )
            sqlDb.version = 4
        }

        val migratedDb = Room.databaseBuilder(appContext, NotesDatabase::class.java, dbFile.absolutePath)
            .addMigrations(*NotesDatabase.migrations)
            .build()
        try {
            val note = migratedDb.notes().note(noteId)
            assertNotNull(note)
            assertEquals("旧版本升级正文", note!!.searchText)

            val versions = migratedDb.notes().versionsForNote(noteId)
            assertEquals(1, versions.size)
            assertNull(versions.single().title)
            assertTrue(versions.single().attachmentRefs.contains(attId))

            var hasDeletedAtIndex = false
            migratedDb.openHelper.readableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_notes_deleted_at'",
            ).use { cursor ->
                hasDeletedAtIndex = cursor.moveToFirst()
            }
            assertTrue(hasDeletedAtIndex)
        } finally {
            migratedDb.close()
        }
    }

    @Test
    fun versionRestoreCreatesNewRevisionPreservesHistoryAndEnforcesConflictGuard() = runBlocking {
        withIsolatedEnv("history") { env ->
            val targetDoc = NoteDocument(listOf(NoteBlock.paragraph("被引用目标"))).encode()
            val targetNote = env.repo.commit(
                env.repo.newDraft("target").copy(documentJson = targetDoc, editSeq = 1),
            )

            val importedAtt = importSyntheticPng(env.attachments, "hist.png")

            val cbV1 = NoteBlock.checkbox("历史待办事项", checked = false, dueDate = "2026-11-25")
            val docV1 = NoteDocument(
                listOf(
                    NoteBlock.paragraph("第一版段落"),
                    cbV1,
                    NoteBlock.attachment("image", importedAtt.id),
                    NoteBlock.reference(targetNote.id, targetNote.revision, "引用目标"),
                ),
            )
            val v1Note = env.repo.commit(
                env.repo.newDraft("hist").copy(documentJson = docV1.encode(), editSeq = 1),
            )

            val docV2 = NoteDocument(listOf(NoteBlock.paragraph("第二版覆盖内容")))
            val v2Note = env.repo.commit(
                env.repo.newDraft("hist", v1Note).copy(documentJson = docV2.encode(), editSeq = 2),
            )
            assertEquals(2L, v2Note.revision)

            // Simulate missing attachment on disk before diff/restore (Q045).
            env.attachments.collectUnreferenced(env.db, emptySet())
            File(env.attachments.rootDirectory(), importedAtt.id).deleteRecursively()
            assertNull(env.attachments.find(importedAtt.id))

            val readyMap = env.attachments.listReady().associate { it.id to it.name }
            val diff = env.repo.loadVersionDiff(v1Note.id, 1L) { readyMap[it] }
            assertNotNull(diff)
            assertEquals(1, diff!!.missingAttachments.size)
            assertEquals(importedAtt.id, diff.missingAttachments.single().attachmentId)

            // Concurrent conflict guard (Q046): wrong expectedCurrentRevision must fail cleanly.
            val staleAttempt = env.repo.restoreVersion(
                noteId = v1Note.id,
                targetRevision = 1L,
                expectedCurrentRevision = 1L,
            )
            assertTrue(staleAttempt is RestoreVersionResult.Conflict)
            assertEquals(2L, (staleAttempt as RestoreVersionResult.Conflict).actualRevision)

            // Correct restore creates revision 3 (keeping v1 and v2 intact) and rebuilds tasks + links.
            val restored = env.repo.restoreVersion(
                noteId = v1Note.id,
                targetRevision = 1L,
                expectedCurrentRevision = 2L,
            )
            assertTrue(restored is RestoreVersionResult.Restored)
            val ok = restored as RestoreVersionResult.Restored
            assertEquals(3L, ok.note.revision)
            assertTrue(ok.referencedAttachmentIds.contains(importedAtt.id))

            val versions = env.db.notes().versionsForNote(v1Note.id)
            assertEquals(listOf(3L, 2L, 1L), versions.map { it.revision })
            assertEquals("restore", versions.first().origin)

            val rebuiltTasks = env.db.notes().tasks(v1Note.id)
            assertEquals(1, rebuiltTasks.size)
            assertEquals(cbV1.id, rebuiltTasks.single().blockId)

            val rebuiltLinks = env.db.organization().noteLinks().filter { it.sourceId == v1Note.id }
            assertEquals(1, rebuiltLinks.size)
            assertEquals(targetNote.id, rebuiltLinks.single().targetId)
        }
    }

    @Test
    fun trashPreservesRemindersAcrossRestoreAndPurgeRespectsActiveLeases() = runBlocking {
        withIsolatedEnv("trash") { env ->
            val sharedPng = importSyntheticPng(env.attachments, "shared.png")
            val orphanPng = importSyntheticPng(env.attachments, "orphan.png")

            val keeper = env.repo.commit(
                env.repo.newDraft("keeper").copy(
                    documentJson = NoteDocument(
                        listOf(NoteBlock.paragraph("保留笔记"), NoteBlock.attachment("image", sharedPng.id)),
                    ).encode(),
                    editSeq = 1,
                ),
            )

            val cb = NoteBlock.checkbox("回收站提醒测试")
            val trashDoc = NoteDocument(
                listOf(
                    cb,
                    NoteBlock.attachment("image", orphanPng.id),
                    NoteBlock.attachment("image", sharedPng.id),
                    NoteBlock.reference(keeper.id, keeper.revision, "链接"),
                ),
            )
            val victim = env.repo.commit(
                env.repo.newDraft("victim").copy(documentJson = trashDoc.encode(), editSeq = 1),
            )
            val shanghai = ZoneId.of("Asia/Shanghai")
            val scheduled = env.repo.setTaskSchedule(
                noteId = victim.id,
                blockId = cb.id,
                dueDate = "2099-01-15",
                reminderTime = "09:00",
                zoneId = shanghai,
            )
            val reminderId = scheduled.reminder!!.id

            // Move to Trash: hidden from live list & active reminders, but ReminderEntity preserved.
            val cancelledOnTrash = env.repo.moveToTrash(victim.id)
            assertEquals(listOf(reminderId), cancelledOnTrash)
            assertTrue(env.repo.notes.first().none { it.id == victim.id })
            assertEquals(listOf(victim.id), env.repo.trashedNotes.first().map { it.id })
            assertTrue(env.repo.activeReminders().none { it.id == reminderId })

            // Time reconcile / missed check / delivery while in Trash must NOT mark reminder CANCELLED.
            val reconcileWhileTrashed = env.repo.reconcileTimeChange(shanghai, Instant.parse("2026-11-15T05:00:00Z"))
            assertTrue(reconcileWhileTrashed.cancelledReminderIds.contains(reminderId))
            assertNull(env.repo.prepareReminderDelivery(reminderId))
            assertEquals(ReminderStatus.ACTIVE.name, env.db.taskReminders().reminder(reminderId)!!.status)

            // Restore from Trash: note and active reminder return cleanly.
            val reconcileOnRestore = env.repo.restoreFromTrash(victim.id, shanghai, Instant.parse("2026-11-15T06:00:00Z"))!!
            assertTrue(reconcileOnRestore.activeReminders.any { it.id == reminderId })
            assertTrue(env.repo.notes.first().any { it.id == victim.id })

            // Move back to Trash and preview purge impact: only orphanPng is orphaned (sharedPng kept by keeper).
            env.repo.moveToTrash(victim.id)
            val readyIds = env.attachments.listReady().mapTo(HashSet()) { it.id }
            val impact = env.repo.previewPurge(listOf(victim.id), readyIds)
            assertEquals(1, impact.noteCount)
            assertEquals(1, impact.orphanedAttachmentCount)

            // Active backup lease protects orphanPng from GC even after permanent deletion.
            val leaseFile = File(env.backup.journalDir, "leases.json").apply { parentFile?.mkdirs() }
            leaseFile.writeText(
                MiniJson.encode(mapOf("leased_attachment_ids" to listOf(orphanPng.id))),
                StandardCharsets.UTF_8,
            )
            env.repo.permanentlyDeleteNotes(listOf(victim.id))
            env.attachments.collectUnreferenced(env.db, env.backup.activeLeaseIds())
            assertNull(env.db.notes().note(victim.id))
            assertNull(env.db.taskReminders().reminder(reminderId))
            assertTrue(env.db.organization().noteLinks().none { it.sourceId == victim.id || it.targetId == victim.id })
            assertNotNull(env.attachments.find(orphanPng.id))

            // Once lease clears, orphanPng is cleaned while sharedPng remains intact.
            leaseFile.delete()
            env.attachments.collectUnreferenced(env.db, env.backup.activeLeaseIds())
            assertNull(env.attachments.find(orphanPng.id))
            assertNotNull(env.attachments.find(sharedPng.id))
        }
    }

    @Test
    fun backupPreflightMergeReplaceAndCorruptRollbackProtectExistingData() = runBlocking {
        val exportZip = File(temp.root, "library-backup.zip")
        val mdZip = File(temp.root, "library-markdown.zip")
        var exportedAttId = ""
        var exportedAttBytes = ByteArray(0)
        var conflictedNoteId = ""
        var activeNoteId = ""
        var trashedNoteId = ""

        withIsolatedEnv("source") { src ->
            val att = importSyntheticPng(src.attachments, "backup-img.png")
            exportedAttId = att.id
            exportedAttBytes = src.attachments.original(att).readBytes()

            val tagId = src.org.createTag("备份标签")
            val nb = src.org.createNotebook("备份笔记本")

            val cb = NoteBlock.checkbox("同步任务")
            val doc1 = NoteDocument(
                listOf(
                    NoteBlock.paragraph("导出笔记一"),
                    cb,
                    NoteBlock.attachment("image", att.id),
                ),
            )
            val n1 = src.repo.commit(src.repo.newDraft("n1").copy(documentJson = doc1.encode(), editSeq = 1))
            activeNoteId = n1.id
            conflictedNoteId = n1.id
            src.repo.setPinned(n1.id, true)
            src.org.tagNote(n1.id, tagId, attach = true)
            src.org.fileNote(n1.id, nb.id)
            src.repo.setTaskSchedule(
                noteId = n1.id,
                blockId = cb.id,
                dueDate = "2026-11-16",
                reminderTime = "09:00",
                zoneId = ZoneId.of("Asia/Shanghai"),
            )

            val n2 = src.repo.commit(
                src.repo.newDraft("n2").copy(
                    documentJson = NoteDocument(listOf(NoteBlock.paragraph("垃圾桶导出笔记"))).encode(),
                    editSeq = 1,
                ),
            )
            trashedNoteId = n2.id
            src.repo.moveToTrash(n2.id)

            SearchRepository(src.db).save("保存筛选", "导出", NoteScope())

            val manifest = src.backup.exportFullBackupToFile(
                exportZip,
                now = Instant.parse("2026-11-15T04:00:00Z"),
            )
            assertEquals(2, manifest.counts.notes)
            assertEquals(1, manifest.counts.attachments)

            val mdOutcome = src.backup.exportMarkdownToFile(mdZip)
            assertTrue(mdOutcome.ok)
            assertEquals(1, mdOutcome.itemCount) // trashed omitted by default
            ZipFile(mdZip).use { zf ->
                assertTrue(zf.entries().toList().any { it.name.endsWith(".md") })
            }
        }

        withIsolatedEnv("target") { dst ->
            // Create local conflicting note with the same ID as activeNoteId plus a local-only note.
            val localOnly = dst.repo.commit(
                dst.repo.newDraft("local-only").copy(
                    documentJson = NoteDocument(listOf(NoteBlock.paragraph("本地独有笔记"))).encode(),
                    editSeq = 1,
                ),
            )
            dst.db.notes().insertNotes(
                listOf(
                    io.github.z3275630ops.floatingnotes.data.local.NoteEntity(
                        id = conflictedNoteId,
                        title = "本地修改版",
                        documentJson = NoteDocument(listOf(NoteBlock.paragraph("本地不同内容"))).encode(),
                        searchText = "本地不同内容",
                        sourceType = "manual",
                        sourceUrl = null,
                        createdAt = "2026-11-14T12:00:00.000Z",
                        updatedAt = "2026-11-15T03:00:00.000Z",
                        revision = 1L,
                        contentHash = "localdiffhash",
                        lastDraftId = "localdraft",
                        committedSeq = 1L,
                    ),
                ),
            )

            // 1. Corrupt ZIP preflight (Q052) fails without altering local DB.
            val corruptZip = File(temp.root, "corrupt.zip").apply { writeText("not a zip archive") }
            val corruptErr = runCatching { dst.backup.preflightBackupFile(corruptZip) }.exceptionOrNull()
            assertTrue(corruptErr is BackupValidationException)
            assertNotNull(dst.db.notes().note(localOnly.id))

            // 2. MERGE_KEEP_CONFLICTS preserves local conflicted note + creates conflict copy + restores attachment.
            val preflight = dst.backup.preflightBackupFile(exportZip)
            assertEquals(1, preflight.conflictNoteCount)
            assertTrue(preflight.verifiedSha256)

            val mergeExec = dst.backup.executeRestore(
                mode = RestoreMode.MERGE_KEEP_CONFLICTS,
                autoBackupBeforeRestore = true,
                now = Instant.parse("2026-11-16T05:00:00Z"),
            )
            assertTrue(mergeExec.outcome is RestoreOutcome.Success)
            val mergeSuccess = mergeExec.outcome as RestoreOutcome.Success
            assertNotNull(mergeSuccess.rollbackPath)
            assertTrue(File(mergeSuccess.rollbackPath!!).isFile)
            assertEquals(1, mergeSuccess.skippedOrRenamedNotes)

            val allAfterMerge = dst.db.notes().allNotes()
            assertEquals(4, allAfterMerge.size) // localOnly + local conflictedNoteId + conflict copy + trashedNoteId
            assertEquals("本地修改版", dst.db.notes().note(conflictedNoteId)!!.title)
            assertTrue(allAfterMerge.any { it.title?.contains("恢复副本") == true })
            assertNotNull(dst.db.notes().note(localOnly.id))
            val mergedAtt = dst.attachments.find(exportedAttId)
            assertNotNull(mergedAtt)
            assertArrayEquals(exportedAttBytes, dst.attachments.original(mergedAtt!!).readBytes())

            // 3. REPLACE_ALL removes localOnly, restores exact source DB, and pauses past-due reminder.
            dst.backup.preflightBackupFile(exportZip)
            val replaceExec = dst.backup.executeRestore(
                mode = RestoreMode.REPLACE_ALL,
                autoBackupBeforeRestore = true,
                now = Instant.parse("2026-11-16T05:00:00Z"),
            )
            assertTrue(replaceExec.outcome is RestoreOutcome.Success)
            val replaceSuccess = replaceExec.outcome as RestoreOutcome.Success
            assertNotNull(replaceSuccess.pausedRemindersNote)
            assertNull(dst.db.notes().note(localOnly.id))
            assertNotNull(dst.db.notes().note(activeNoteId))
            assertNotNull(dst.db.notes().note(trashedNoteId)?.deletedAt)
            val restoredReminder = dst.db.taskReminders().remindersForNote(activeNoteId).single()
            assertEquals(ReminderStatus.MISSED.name, restoredReminder.status)
            assertTrue(restoredReminder.missedSummarized)
        }
    }

    @Test
    fun interruptedRestoreAtDbCommittedRecoversStagedAttachmentsOnStartup() = runBlocking {
        withIsolatedEnv("crash") { env ->
            val restoreId = newId()
            val attId = "22222222-2222-4222-8222-222222222222"
            val srcPng = createSyntheticPngFile("crash-att.png")
            val bytes = srcPng.readBytes()
            val sha = BackupCodec.sha256Hex(bytes)

            env.db.notes().insertNotes(
                listOf(
                    io.github.z3275630ops.floatingnotes.data.local.NoteEntity(
                        id = "note-after-db-commit",
                        title = "已提交未晋升",
                        documentJson = NoteDocument(
                            listOf(NoteBlock.attachment("image", attId)),
                        ).encode(),
                        searchText = "",
                        sourceType = "manual",
                        sourceUrl = null,
                        createdAt = "2026-11-15T04:00:00.000Z",
                        updatedAt = "2026-11-15T04:00:00.000Z",
                        revision = 1L,
                        contentHash = "crashhash",
                        lastDraftId = "crashdraft",
                        committedSeq = 1L,
                    ),
                ),
            )

            val stageDir = env.backup.stagingDirForRestore(restoreId).apply { mkdirs() }
            env.attachments.stageFromBackupFile(
                id = attId,
                name = "crash-att.png",
                mime = "image/png",
                expectedBytes = bytes.size.toLong(),
                expectedSha256 = sha,
                sourceOriginalFile = srcPng,
                stagingFolder = stageDir,
            )

            val journalFile = File(env.backup.journalDir, "pending_restore.json").apply {
                parentFile?.mkdirs()
            }
            journalFile.writeText(
                MiniJson.encode(
                    linkedMapOf(
                        "id" to restoreId,
                        "phase" to RestorePhase.DB_COMMITTED.name,
                        "mode" to RestoreMode.REPLACE_ALL.name,
                        "rollback_zip_path" to null,
                        "leased_attachment_ids" to listOf(attId),
                        "staged_attachment_ids" to listOf(attId),
                        "updated_at" to "2026-11-15T04:00:00.000Z",
                        "error_message" to null,
                    ),
                ),
                StandardCharsets.UTF_8,
            )

            assertNull(env.attachments.find(attId))
            val recovered = env.backup.recoverInterruptedRestoreIfNeeded()
            assertTrue(recovered)
            assertFalse(journalFile.exists())
            env.attachments.collectUnreferenced(env.db, env.backup.activeLeaseIds())

            val ready = env.attachments.find(attId)
            assertNotNull(ready)
            assertArrayEquals(bytes, env.attachments.original(ready!!).readBytes())
        }
    }

    @Test
    fun nonUuidSafeAttachmentIdSurvivesImportBackupAndGc() = runBlocking {
        val customId = "Legacy_Att-01.custom"
        val backupZip = File(temp.root, "custom-id-backup.zip")
        var expectedBytes = ByteArray(0)

        withIsolatedEnv("custom-src") { env ->
            val att = importSyntheticPng(env.attachments, "legacy.png", id = customId)
            expectedBytes = env.attachments.original(att).readBytes()
            assertEquals(customId, att.id)
            assertNotNull(env.attachments.find(customId))
            assertTrue(env.attachments.listReady().any { it.id == customId })
            val thumb = env.attachments.thumbnail(customId)
            assertNotNull(thumb)
            thumb?.recycle()

            env.repo.commit(
                env.repo.newDraft("custom-note").copy(
                    documentJson = NoteDocument(
                        listOf(NoteBlock.paragraph("自定义ID附件"), NoteBlock.attachment("image", customId)),
                    ).encode(),
                    editSeq = 1,
                ),
            )
            val looseFile = File(env.attachments.rootDirectory(), "synthetic-keep.png").apply {
                parentFile?.mkdirs()
                writeText("keep")
            }
            env.attachments.collectUnreferenced(env.db, emptySet())
            assertNotNull(env.attachments.find(customId))
            assertTrue(looseFile.isFile)
            env.backup.exportFullBackupToFile(backupZip)
        }

        withIsolatedEnv("custom-dst") { dst ->
            dst.backup.preflightBackupFile(backupZip)
            val exec = dst.backup.executeRestore(RestoreMode.REPLACE_ALL, autoBackupBeforeRestore = false)
            assertTrue(exec.outcome is RestoreOutcome.Success)
            val restored = dst.attachments.find(customId)
            assertNotNull(restored)
            assertArrayEquals(expectedBytes, dst.attachments.original(restored!!).readBytes())
        }
    }

    @Test
    fun mergeKeepsCollidingAttachmentIsolatedAndRemapsEmbeddedReferences() = runBlocking {
        val exportZip = File(temp.root, "merge-remap.zip")
        val sharedAttId = newId()
        var targetNoteId = ""
        var linkerNoteId = ""
        var sourcePngBytes = ByteArray(0)
        var localPngBytes = ByteArray(0)

        withIsolatedEnv("merge-src") { src ->
            val srcAtt = importSyntheticPng(
                store = src.attachments,
                name = "source-red.png",
                colorArgb = 0xFFE53935.toInt(),
                id = sharedAttId,
            )
            sourcePngBytes = src.attachments.original(srcAtt).readBytes()

            val targetNote = src.repo.commit(
                src.repo.newDraft("target").copy(
                    documentJson = NoteDocument(listOf(NoteBlock.paragraph("备份目标笔记"))).encode(),
                    editSeq = 1,
                ),
            )
            targetNoteId = targetNote.id

            val linkerDoc = NoteDocument(
                listOf(
                    NoteBlock.paragraph("引用与附件"),
                    NoteBlock.reference(targetNote.id, targetNote.revision, "跳转目标"),
                    NoteBlock.attachment("image", sharedAttId),
                ),
            )
            val linker = src.repo.commit(
                src.repo.newDraft("linker").copy(documentJson = linkerDoc.encode(), editSeq = 1),
            )
            linkerNoteId = linker.id
            src.backup.exportFullBackupToFile(exportZip)
        }

        withIsolatedEnv("merge-dst") { dst ->
            val localAtt = importSyntheticPng(
                store = dst.attachments,
                name = "local-blue.png",
                colorArgb = 0xFF1E88E5.toInt(),
                id = sharedAttId,
            )
            localPngBytes = dst.attachments.original(localAtt).readBytes()
            assertFalse(localPngBytes.contentEquals(sourcePngBytes))

            dst.repo.commit(
                dst.repo.newDraft("local-holder").copy(
                    documentJson = NoteDocument(
                        listOf(NoteBlock.paragraph("本地图片笔记"), NoteBlock.attachment("image", sharedAttId)),
                    ).encode(),
                    editSeq = 1,
                ),
            )

            // Pre-insert conflicting note with the same ID as targetNoteId so merge duplicates it.
            dst.db.notes().insertNotes(
                listOf(
                    io.github.z3275630ops.floatingnotes.data.local.NoteEntity(
                        id = targetNoteId,
                        title = "本地冲突目标",
                        documentJson = NoteDocument(listOf(NoteBlock.paragraph("本地修改目标正文"))).encode(),
                        searchText = "本地修改目标正文",
                        sourceType = "manual",
                        sourceUrl = null,
                        createdAt = "2026-11-14T10:00:00.000Z",
                        updatedAt = "2026-11-15T03:00:00.000Z",
                        revision = 1L,
                        contentHash = "localtargetdiff",
                        lastDraftId = "localtargetdraft",
                        committedSeq = 1L,
                    ),
                ),
            )

            val preflight = dst.backup.preflightBackupFile(exportZip)
            assertEquals(1, preflight.conflictNoteCount)

            val exec = dst.backup.executeRestore(RestoreMode.MERGE_KEEP_CONFLICTS, autoBackupBeforeRestore = false)
            assertTrue(exec.outcome is RestoreOutcome.Success)

            // 1. Existing local attachment under sharedAttId must remain intact with local bytes.
            val keptLocalAtt = dst.attachments.find(sharedAttId)
            assertNotNull(keptLocalAtt)
            assertArrayEquals(localPngBytes, dst.attachments.original(keptLocalAtt!!).readBytes())

            // 2. Conflicted target was duplicated under a new ID.
            val copyNote = dst.db.notes().allNotes().first { it.id != targetNoteId && it.title?.contains("恢复副本") == true }
            assertEquals("本地冲突目标", dst.db.notes().note(targetNoteId)!!.title)

            // 3. Imported linker note remapped both embedded reference target_note_id and colliding attachment_id.
            val importedLinker = dst.db.notes().note(linkerNoteId)!!
            val parsedBlocks = VersionDiff.parseBlocks(importedLinker.documentJson)
            val refBlock = parsedBlocks.first { it.type == "reference" }
            assertEquals(copyNote.id, refBlock.referenceTarget)
            val linkRow = dst.db.organization().noteLinks().single { it.sourceId == linkerNoteId }
            assertEquals(copyNote.id, linkRow.targetId)

            val imgBlock = parsedBlocks.first { it.type == "image" }
            val remappedAttId = imgBlock.attachmentId!!
            assertFalse(remappedAttId == sharedAttId)
            val isolatedAtt = dst.attachments.find(remappedAttId)
            assertNotNull(isolatedAtt)
            assertArrayEquals(sourcePngBytes, dst.attachments.original(isolatedAtt!!).readBytes())
            assertEquals(
                BackupCodec.sha256Hex(importedLinker.documentJson.toByteArray(StandardCharsets.UTF_8)),
                importedLinker.contentHash,
            )
        }
    }

    @Test
    fun postDbCommittedPromotionFailurePreservesJournalAndRecoversOnRestart() = runBlocking {
        val exportZip = File(temp.root, "post-commit-fail.zip")
        var attId = ""
        var expectedBytes = ByteArray(0)

        withIsolatedEnv("promote-src") { src ->
            val att = importSyntheticPng(src.attachments, "promote.png")
            attId = att.id
            expectedBytes = src.attachments.original(att).readBytes()
            src.repo.commit(
                src.repo.newDraft("p1").copy(
                    documentJson = NoteDocument(
                        listOf(NoteBlock.paragraph("晋升失败测试"), NoteBlock.attachment("image", attId)),
                    ).encode(),
                    editSeq = 1,
                ),
            )
            src.backup.exportFullBackupToFile(exportZip)
        }

        withIsolatedEnv("promote-dst") { dst ->
            dst.backup.preflightBackupFile(exportZip)
            dst.backup.beforePromoteHook = { throw IOException("模拟附件晋升 I/O 失败") }

            val exec = dst.backup.executeRestore(RestoreMode.REPLACE_ALL, autoBackupBeforeRestore = true)
            assertTrue(exec.outcome is RestoreOutcome.Failure)
            val failure = exec.outcome as RestoreOutcome.Failure
            assertFalse(failure.existingLibraryIntact)
            assertTrue(failure.message.contains("rollback-"))

            val journalFile = File(dst.backup.journalDir, "pending_restore.json")
            assertTrue(journalFile.isFile)
            val journalJson = MiniJson.parseObject(journalFile.readText(StandardCharsets.UTF_8))
            assertEquals(RestorePhase.DB_COMMITTED.name, journalJson["phase"])
            assertNull(dst.attachments.find(attId))

            dst.backup.beforePromoteHook = null
            val recovered = dst.backup.recoverInterruptedRestoreIfNeeded()
            assertTrue(recovered)
            assertFalse(journalFile.exists())
            val promoted = dst.attachments.find(attId)
            assertNotNull(promoted)
            assertArrayEquals(expectedBytes, dst.attachments.original(promoted!!).readBytes())
        }
    }

    private data class IsolatedEnv(
        val db: NotesDatabase,
        val repo: NotesRepository,
        val org: OrganizationRepository,
        val attachments: AttachmentStore,
        val backup: BackupManager,
    )

    private suspend fun withIsolatedEnv(label: String, block: suspend (IsolatedEnv) -> Unit) {
        val box = File(temp.root, "env-$label-${newId()}").apply { mkdirs() }
        val attachmentsDir = File(box, "attachments").apply { mkdirs() }
        val db = Room.inMemoryDatabaseBuilder(appContext, NotesDatabase::class.java).build()
        val store = AttachmentStore(appContext, attachmentsDir) { 10L * 1024L * 1024L * 1024L }
        val repo = NotesRepository(db)
        val org = OrganizationRepository(db)
        val backup = BackupManager(appContext, db, store)
        try {
            block(IsolatedEnv(db, repo, org, store, backup))
        } finally {
            db.close()
        }
    }

    private suspend fun importSyntheticPng(
        store: AttachmentStore,
        name: String,
        colorArgb: Int = 0xFF74CCA9.toInt(),
        id: String = newId(),
    ): LocalAttachment {
        val scratchDir = File(appContext.filesDir, "recordings/t14-test-${newId()}").apply { mkdirs() }
        val out = createSyntheticPngFile(name = name, dir = scratchDir, colorArgb = colorArgb)
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.attachments", out)
        return try {
            store.import(uri, "image/png", id = id)
        } finally {
            scratchDir.deleteRecursively()
        }
    }

    private fun createSyntheticPngFile(
        name: String,
        dir: File = temp.root,
        colorArgb: Int = 0xFF74CCA9.toInt(),
    ): File {
        val out = File(dir, name)
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(colorArgb)
        }
        FileOutputStream(out).use { stream ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        bmp.recycle()
        return out
    }

    private fun createV4DatabaseFromSchemaAsset(
        dbFile: File,
        populate: (SQLiteDatabase) -> Unit,
    ) {
        val schemaJson = testContext.assets.open(
            "io.github.z3275630ops.floatingnotes.data.local.NotesDatabase/4.json",
        ).bufferedReader().use { it.readText() }
        val root = JSONObject(schemaJson).getJSONObject("database")
        val entities = root.getJSONArray("entities")

        val sqlDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val tableName = entity.getString("tableName")
                val createSql = entity.getString("createSql").replace("\${TABLE_NAME}", tableName)
                sqlDb.execSQL(createSql)
                val indices = entity.optJSONArray("indices")
                if (indices != null) {
                    for (j in 0 until indices.length()) {
                        val idxSql = indices.getJSONObject(j)
                            .getString("createSql")
                            .replace("\${TABLE_NAME}", tableName)
                        sqlDb.execSQL(idxSql)
                    }
                }
            }
            populate(sqlDb)
        } finally {
            sqlDb.close()
        }
    }
}
