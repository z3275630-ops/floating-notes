package io.github.z3275630ops.floatingnotes.data.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class BackupCodecTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun fullBackupZipRoundTripPreservesAllTablesAndAttachmentSha() {
        val attId = "11111111-1111-4111-8111-111111111111"
        val attBytes = "synthetic-png-bytes-1234567890".toByteArray(StandardCharsets.UTF_8)
        val attFile = temp.newFile("att.png").apply { writeBytes(attBytes) }
        val attSha = BackupCodec.sha256Hex(attBytes)

        val liveDoc = """
            {"document_schema_version":1,"blocks":[
              {"id":"b1","type":"paragraph","text":"合成测试正文"},
              {"id":"b2","type":"checkbox","text":"完成验收","checked":false,"due_date":"2026-11-20","reminder_id":"rem-1","occurrence_id":"occ-1"},
              {"id":"b3","type":"image","attachment_id":"$attId"}
            ]}
        """.trimIndent()
        val trashedDoc = """
            {"document_schema_version":1,"blocks":[
              {"id":"b4","type":"paragraph","text":"已软删除笔记"}
            ]}
        """.trimIndent()

        val snapshot = BackupSnapshot(
            exportedAt = "2026-11-15T05:00:00.000Z",
            appVersion = "0.1.13-todos.1",
            dbSchemaVersion = 5,
            notes = listOf(
                BackupNoteRecord(
                    id = "note-1",
                    title = "置顶项目清单",
                    documentJson = liveDoc,
                    searchText = "合成测试正文\n完成验收",
                    authorType = "user",
                    sourceType = "manual",
                    sourceUrl = null,
                    aiPolicy = "inherit",
                    createdAt = "2026-11-14T10:00:00.000Z",
                    updatedAt = "2026-11-15T04:00:00.000Z",
                    revision = 2L,
                    serverRevision = null,
                    contentHash = BackupCodec.sha256Hex(liveDoc.toByteArray(StandardCharsets.UTF_8)),
                    archivedAt = null,
                    deletedAt = null,
                    pinned = true,
                    lastDraftId = "draft-last",
                    committedSeq = 3L,
                    mediaMask = 1,
                ),
                BackupNoteRecord(
                    id = "note-trashed",
                    title = "回收站条目",
                    documentJson = trashedDoc,
                    searchText = "已软删除笔记",
                    authorType = "user",
                    sourceType = "share",
                    sourceUrl = "https://example.test/post",
                    aiPolicy = "inherit",
                    createdAt = "2026-11-14T09:00:00.000Z",
                    updatedAt = "2026-11-15T03:00:00.000Z",
                    revision = 1L,
                    serverRevision = null,
                    contentHash = BackupCodec.sha256Hex(trashedDoc.toByteArray(StandardCharsets.UTF_8)),
                    archivedAt = null,
                    deletedAt = "2026-11-15T03:00:00.000Z",
                    pinned = false,
                    lastDraftId = "draft-t",
                    committedSeq = 1L,
                    mediaMask = 0,
                ),
            ),
            versions = listOf(
                BackupVersionRecord(
                    noteId = "note-1",
                    revision = 1L,
                    documentJson = trashedDoc,
                    origin = "edit",
                    createdAt = "2026-11-14T10:00:00.000Z",
                    title = "初稿标题",
                    attachmentRefs = "[]",
                ),
                BackupVersionRecord(
                    noteId = "note-1",
                    revision = 2L,
                    documentJson = liveDoc,
                    origin = "edit",
                    createdAt = "2026-11-15T04:00:00.000Z",
                    title = "置顶项目清单",
                    attachmentRefs = """["$attId"]""",
                ),
            ),
            drafts = listOf(
                BackupDraftRecord(
                    id = "draft-share",
                    slot = "capture:draft-share",
                    noteId = "note-pending",
                    baseRevision = 0L,
                    documentJson = trashedDoc,
                    editSeq = 1L,
                    updatedAt = "2026-11-15T04:30:00.000Z",
                    sourceType = "share",
                    sourceUrl = "https://example.test/clip",
                ),
            ),
            tags = listOf(
                BackupTagRecord("tag-root", "", "工作", "工作"),
                BackupTagRecord("tag-child", "tag-root", "计划", "计划"),
            ),
            notebooks = listOf(BackupNotebookRecord("nb-1", "默认笔记本", "默认笔记本")),
            noteTags = listOf(BackupNoteTagRecord("note-1", "tag-child")),
            notebookNotes = listOf(BackupNotebookNoteRecord("note-1", "nb-1")),
            noteLinks = listOf(BackupNoteLinkRecord("note-1", "ref-1", "note-trashed", 1L)),
            savedSearches = listOf(BackupSavedSearchRecord("ss-1", "未完成", "验收", """{"schema_version":1}""")),
            reminders = listOf(
                BackupReminderRecord(
                    id = "rem-1",
                    noteId = "note-1",
                    blockId = "b2",
                    occurrenceId = "occ-1",
                    zoneId = "Asia/Shanghai",
                    localDate = "2026-11-20",
                    localTime = "09:30",
                    triggerInstantMs = 1795138200000L,
                    policy = "FOLLOW_LOCAL",
                    status = "ACTIVE",
                    recurrence = "WEEKLY",
                    snoozedUntilMs = null,
                    missedSummarized = false,
                    revision = 1L,
                    updatedAt = "2026-11-15T04:00:00.000Z",
                ),
            ),
            taskOccurrences = listOf(
                BackupTaskOccurrenceRecord(
                    id = "occ-old",
                    noteId = "note-1",
                    blockId = "b2",
                    reminderId = "rem-1",
                    scheduledDate = "2026-11-13",
                    titleSnapshot = "完成验收",
                    recurrence = "WEEKLY",
                    completedAt = "2026-11-13T09:35:00.000Z",
                ),
            ),
            attachments = listOf(
                BackupAttachmentEntry(
                    id = attId,
                    archivePath = "attachments/$attId.png",
                    originalName = "diagram.png",
                    mime = "image/png",
                    bytes = attBytes.size.toLong(),
                    sha256 = attSha,
                ),
            ),
        )

        val zipFile = File(temp.root, "backup-full.zip")
        val manifest = BackupCodec.writeBackupZip(snapshot, zipFile) { id ->
            if (id == attId) attFile else null
        }

        assertEquals(2, manifest.counts.notes)
        assertEquals(1, manifest.counts.trashedNotes)
        assertEquals(2, manifest.counts.versions)
        assertEquals(1, manifest.counts.attachments)

        val stageDir = temp.newFolder("stage")
        val parsed = BackupCodec.readAndValidateArchive(zipFile, attachmentStagingDir = stageDir)
        assertEquals(snapshot.notes, parsed.snapshot.notes)
        assertEquals(snapshot.versions, parsed.snapshot.versions)
        assertEquals(snapshot.drafts, parsed.snapshot.drafts)
        assertEquals(snapshot.tags.sortedBy { it.id }, parsed.snapshot.tags)
        assertEquals(snapshot.notebooks, parsed.snapshot.notebooks)
        assertEquals(snapshot.noteTags, parsed.snapshot.noteTags)
        assertEquals(snapshot.notebookNotes, parsed.snapshot.notebookNotes)
        assertEquals(snapshot.noteLinks, parsed.snapshot.noteLinks)
        assertEquals(snapshot.savedSearches, parsed.snapshot.savedSearches)
        assertEquals(snapshot.reminders, parsed.snapshot.reminders)
        assertEquals(snapshot.taskOccurrences, parsed.snapshot.taskOccurrences)

        val extracted = File(File(stageDir, attId), "original")
        assertTrue(extracted.isFile)
        assertArrayEquals(attBytes, extracted.readBytes())
    }

    @Test
    fun rejectsPathTraversalBackslashAbsoluteAndDriveEntries() {
        for (badPath in listOf("../evil.txt", "notes/../../secret", "notes\\a.json", "/etc/passwd", "C:/evil.txt", "a\u0000b")) {
            assertThrows(BackupValidationException::class.java) {
                BackupCodec.validateArchiveEntryPath(badPath)
            }
        }

        val badZip = File(temp.root, "zip-slip.zip")
        ZipOutputStream(FileOutputStream(badZip)).use { zos ->
            zos.putNextEntry(ZipEntry("../escaped.json"))
            zos.write("{}".toByteArray(StandardCharsets.UTF_8))
            zos.closeEntry()
        }
        assertThrows(BackupValidationException::class.java) {
            BackupCodec.readAndValidateArchive(badZip)
        }
    }

    @Test
    fun rejectsTamperedShaUndeclaredEntriesAndSymlinkAttributes() {
        val baseZip = writeMinimalValidBackupZip("minimal.zip")

        // 1. Tamper a note entry after signing manifest -> SHA/byte verification must reject.
        val tamperedZip = File(temp.root, "tampered.zip")
        copyZipWithReplacement(baseZip, tamperedZip) { path, bytes ->
            if (path == "notes/note-1.json") {
                String(bytes, StandardCharsets.UTF_8)
                    .replace("最小内容", "被篡改内容")
                    .toByteArray(StandardCharsets.UTF_8)
            } else {
                bytes
            }
        }
        assertThrows(BackupValidationException::class.java) {
            BackupCodec.readAndValidateArchive(tamperedZip)
        }

        // 2. Add undeclared extra entry -> manifest check must reject.
        val extraZip = File(temp.root, "extra.zip")
        copyZipWithReplacement(baseZip, extraZip, extraEntry = "notes/unexpected.json" to "{}".toByteArray())
        assertThrows(BackupValidationException::class.java) {
            BackupCodec.readAndValidateArchive(extraZip)
        }

        // 3. Patch central directory Unix mode to symlink (0xA000) -> EOCD/CD check must reject.
        val symlinkZip = File(temp.root, "symlink.zip").apply { writeBytes(baseZip.readBytes()) }
        patchFirstCentralDirectorySymlink(symlinkZip)
        val symlinkErr = assertThrows(BackupValidationException::class.java) {
            BackupCodec.readAndValidateArchive(symlinkZip)
        }
        assertTrue(symlinkErr.message.orEmpty().contains("符号链接"))
    }

    @Test
    fun rejectsZipBombExceedingCompressionRatio() {
        val baseZip = writeMinimalValidBackupZip("bomb-base.zip")
        val hugeZeroNoteJson = """
            {"id":"note-1","title":null,"document_json":${MiniJson.encode("""{"document_schema_version":1,"blocks":[{"id":"b1","type":"paragraph","text":"${"a".repeat(250_000)}"}]}""")},"search_text":"${"a".repeat(250_000)}","author_type":"user","source_type":"manual","source_url":null,"ai_policy":"inherit","created_at":"2026-11-15T00:00:00.000Z","updated_at":"2026-11-15T00:00:00.000Z","local_revision":1,"server_revision":null,"content_hash":"h","archived_at":null,"deleted_at":null,"pinned":false,"last_draft_id":"d","committed_seq":1,"media_mask":0}
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        val bombZip = File(temp.root, "bomb.zip")
        copyZipWithReplacement(baseZip, bombZip) { path, bytes ->
            when (path) {
                "notes/note-1.json" -> hugeZeroNoteJson
                else -> bytes
            }
        }
        // Update manifest entry sizes/hashes cleanly? Even before/during SHA check, reading notes/note-1.json
        // hits checkBombs when entryBytes >= 64KB and ratio > 200:1!
        val err = assertThrows(BackupValidationException::class.java) {
            BackupCodec.readAndValidateArchive(bombZip)
        }
        assertTrue(err.message.orEmpty().contains("压缩比异常") || err.message.orEmpty().contains("校验失败"))
    }

    @Test
    fun rejectsUnsupportedHigherBackupOrDbSchemaVersion() {
        val baseZip = writeMinimalValidBackupZip("future-base.zip")
        val futureBackupZip = File(temp.root, "future-backup.zip")
        copyZipWithReplacement(baseZip, futureBackupZip) { path, bytes ->
            if (path == "manifest.json") {
                String(bytes, StandardCharsets.UTF_8)
                    .replace(""""backup_schema_version":1""", """"backup_schema_version":99""")
                    .toByteArray(StandardCharsets.UTF_8)
            } else bytes
        }
        assertThrows(BackupValidationException::class.java) {
            BackupCodec.readAndValidateArchive(futureBackupZip)
        }

        val futureDbZip = File(temp.root, "future-db.zip")
        copyZipWithReplacement(baseZip, futureDbZip) { path, bytes ->
            if (path == "manifest.json") {
                String(bytes, StandardCharsets.UTF_8)
                    .replace(""""db_schema_version":5""", """"db_schema_version":99""")
                    .toByteArray(StandardCharsets.UTF_8)
            } else bytes
        }
        assertThrows(BackupValidationException::class.java) {
            BackupCodec.readAndValidateArchive(futureDbZip)
        }
    }

    @Test
    fun markdownExportIncludesFrontmatterWarningsAndExcludesTrashedByDefault() {
        val liveDoc = """
            {"document_schema_version":1,"blocks":[
              {"id":"b1","type":"paragraph","text":"正文第一段"},
              {"id":"b2","type":"checkbox","text":"买牛奶","checked":true,"due_date":"2026-11-16"},
              {"id":"b3","type":"reference","target_note_id":"note-2","label":"引用片段","start":0,"end":4},
              {"id":"b4","type":"image","attachment_id":"missing-att-id"}
            ]}
        """.trimIndent()
        val trashedDoc = """
            {"document_schema_version":1,"blocks":[{"id":"bt","type":"paragraph","text":"垃圾箱笔记"}]}
        """.trimIndent()

        val snapshot = BackupSnapshot(
            exportedAt = "2026-11-15T05:00:00.000Z",
            appVersion = "0.1.13-todos.1",
            dbSchemaVersion = 5,
            notes = listOf(
                BackupNoteRecord(
                    id = "note-11111111",
                    title = "Markdown 标题",
                    documentJson = liveDoc,
                    searchText = "正文第一段",
                    authorType = "user",
                    sourceType = "manual",
                    sourceUrl = null,
                    aiPolicy = "inherit",
                    createdAt = "2026-11-15T01:00:00.000Z",
                    updatedAt = "2026-11-15T02:00:00.000Z",
                    revision = 1L,
                    serverRevision = null,
                    contentHash = "h1",
                    archivedAt = null,
                    deletedAt = null,
                    pinned = false,
                    lastDraftId = "d1",
                    committedSeq = 1L,
                    mediaMask = 1,
                ),
                BackupNoteRecord(
                    id = "note-22222222",
                    title = "已删除",
                    documentJson = trashedDoc,
                    searchText = "垃圾箱笔记",
                    authorType = "user",
                    sourceType = "manual",
                    sourceUrl = null,
                    aiPolicy = "inherit",
                    createdAt = "2026-11-15T01:00:00.000Z",
                    updatedAt = "2026-11-15T02:00:00.000Z",
                    revision = 1L,
                    serverRevision = null,
                    contentHash = "h2",
                    archivedAt = null,
                    deletedAt = "2026-11-15T03:00:00.000Z",
                    pinned = false,
                    lastDraftId = "d2",
                    committedSeq = 1L,
                    mediaMask = 0,
                ),
            ),
            versions = emptyList(),
            drafts = emptyList(),
            tags = listOf(BackupTagRecord("t1", "", "标签A", "标签a")),
            notebooks = listOf(BackupNotebookRecord("nb1", "工作区", "工作区")),
            noteTags = listOf(BackupNoteTagRecord("note-11111111", "t1")),
            notebookNotes = listOf(BackupNotebookNoteRecord("note-11111111", "nb1")),
            noteLinks = emptyList(),
            savedSearches = emptyList(),
            reminders = emptyList(),
            taskOccurrences = emptyList(),
            attachments = emptyList(),
        )

        val mdZip = File(temp.root, "md-export.zip")
        val outcome = BackupCodec.writeMarkdownZip(snapshot, mdZip) { null }
        assertTrue(outcome.ok)
        assertEquals(1, outcome.itemCount)
        assertTrue(outcome.warnings.any { it.contains("UTF-16") })
        assertTrue(outcome.warnings.any { it.contains("缺失") })

        ZipFile(mdZip).use { zf ->
            val names = zf.entries().toList().map { it.name }
            assertTrue(names.contains("README-导出说明.txt"))
            val noteEntryName = names.single { it.startsWith("notes/") && it.endsWith(".md") }
            assertFalse(names.any { it.contains("note-2222") })
            val content = String(zf.getInputStream(zf.getEntry(noteEntryName)).readBytes(), StandardCharsets.UTF_8)
            assertTrue(content.contains("notebook: \"工作区\""))
            assertTrue(content.contains("- \"标签A\""))
            assertTrue(content.contains("- [x] 买牛奶 （截止：2026-11-16）"))
            assertTrue(content.contains("> [缺失附件: missing-att-id]"))
        }
    }

    @Test
    fun versionDiffDetectsTitleChangesLinesAndMissingAttachments() {
        val currentDoc = """
            {"document_schema_version":1,"blocks":[
              {"id":"b1","type":"paragraph","text":"共同第一行\n当前新版第二行"}
            ]}
        """.trimIndent()
        val oldDoc = """
            {"document_schema_version":1,"blocks":[
              {"id":"b1","type":"paragraph","text":"共同第一行\n历史旧版第二行"},
              {"id":"b2","type":"image","attachment_id":"att-present"},
              {"id":"b3","type":"audio","attachment_id":"att-missing"}
            ]}
        """.trimIndent()

        val preview = VersionDiff.diffDocuments(
            noteId = "note-1",
            targetRevision = 1L,
            targetTitle = "旧标题",
            targetDocumentJson = oldDoc,
            targetAttachmentRefsJson = """["att-present","att-missing"]""",
            targetOrigin = "edit",
            targetCreatedAt = "2026-11-14T10:00:00.000Z",
            currentRevision = 2L,
            currentTitle = "新标题",
            currentDocumentJson = currentDoc,
        ) { id ->
            if (id == "att-present") "photo.jpg" else null
        }

        assertTrue(preview.titleChanged)
        assertEquals(2, preview.totalAttachmentCount)
        assertEquals(1, preview.missingAttachments.size)
        assertEquals("att-missing", preview.missingAttachments.single().attachmentId)
        assertTrue(preview.lines.any { it.kind == DiffLineKind.UNCHANGED && it.text == "共同第一行" })
        assertTrue(preview.lines.any { it.kind == DiffLineKind.ADDED && it.text == "历史旧版第二行" })
        assertTrue(preview.lines.any { it.kind == DiffLineKind.REMOVED && it.text == "当前新版第二行" })
    }

    @Test
    fun rewritesEmbeddedReferenceAndAttachmentIdsPreservingUnknownFields() {
        val docJson = """
            {
              "document_schema_version": 1,
              "blocks": [
                {"id": "b1", "type": "paragraph", "text": "正文"},
                {"id": "b2", "type": "checkbox", "text": "任务", "checked": false, "due_date": "2026-11-20", "marks": [{"type": "bold", "start": 0, "end": 2}]},
                {"id": "b3", "type": "reference", "target_note_id": "note-old", "target_revision": 2, "target_block_id": "tb1", "start": 1, "end": 4, "label": "引用标签"},
                {"id": "b4", "type": "image", "attachment_id": "att-old", "caption": "保留字段"}
              ]
            }
        """.trimIndent()

        val (unchanged, didChangeNoOp) = VersionDiff.rewriteDocumentIds(
            documentJson = docJson,
            noteIdMap = mapOf("other-note" to "other-new"),
            attachmentIdMap = emptyMap(),
        )
        assertFalse(didChangeNoOp)
        assertEquals(docJson, unchanged)

        val (rewritten, changed) = VersionDiff.rewriteDocumentIds(
            documentJson = docJson,
            noteIdMap = mapOf("note-old" to "note-new"),
            attachmentIdMap = mapOf("att-old" to "att-new"),
        )
        assertTrue(changed)
        val blocks = VersionDiff.parseBlocks(rewritten)
        val refBlock = blocks.first { it.id == "b3" }
        assertEquals("note-new", refBlock.referenceTarget)
        assertEquals("tb1", refBlock.referenceTargetBlockId)
        assertEquals(1, refBlock.referenceStart)
        assertEquals(4, refBlock.referenceEnd)
        assertEquals("引用标签", refBlock.referenceLabel)

        val imgBlock = blocks.first { it.id == "b4" }
        assertEquals("att-new", imgBlock.attachmentId)
        assertEquals("保留字段", imgBlock.raw["caption"])

        val checkboxBlock = blocks.first { it.id == "b2" }
        assertEquals("2026-11-20", checkboxBlock.dueDate)
        assertNotNull(checkboxBlock.raw["marks"])
    }

    @Test
    fun validatesSafeAttachmentIdsRejectingDotAndReservedSuffixes() {
        assertTrue(BackupCodec.isSafeAttachmentId("11111111-1111-4111-8111-111111111111"))
        assertTrue(BackupCodec.isSafeAttachmentId("11111111-1111-4111-8111-111111111111".uppercase()))
        assertTrue(BackupCodec.isSafeAttachmentId("Legacy_Att-01.custom"))
        assertFalse(BackupCodec.isSafeAttachmentId(""))
        assertFalse(BackupCodec.isSafeAttachmentId("."))
        assertFalse(BackupCodec.isSafeAttachmentId(".."))
        assertFalse(BackupCodec.isSafeAttachmentId("abc.part"))
        assertFalse(BackupCodec.isSafeAttachmentId("abc.old"))
        assertFalse(BackupCodec.isSafeAttachmentId("nested/id"))

        val customId = "Legacy_Att-01.custom"
        val customBytes = "custom-att-bytes".toByteArray(StandardCharsets.UTF_8)
        val customFile = temp.newFile("custom.bin").apply { writeBytes(customBytes) }
        val customSha = BackupCodec.sha256Hex(customBytes)
        val doc = """{"document_schema_version":1,"blocks":[{"id":"b1","type":"image","attachment_id":"$customId"}]}"""
        val snapshot = BackupSnapshot(
            exportedAt = "2026-11-15T00:00:00.000Z",
            appVersion = "0.1.14-backup.1",
            dbSchemaVersion = 5,
            notes = listOf(
                BackupNoteRecord(
                    id = "n-custom",
                    title = "非UUID附件",
                    documentJson = doc,
                    searchText = "",
                    authorType = "user",
                    sourceType = "manual",
                    sourceUrl = null,
                    aiPolicy = "inherit",
                    createdAt = "2026-11-15T00:00:00.000Z",
                    updatedAt = "2026-11-15T00:00:00.000Z",
                    revision = 1L,
                    serverRevision = null,
                    contentHash = "h1",
                    archivedAt = null,
                    deletedAt = null,
                    pinned = false,
                    lastDraftId = "d1",
                    committedSeq = 1L,
                    mediaMask = 1,
                ),
            ),
            versions = emptyList(),
            drafts = emptyList(),
            tags = emptyList(),
            notebooks = emptyList(),
            noteTags = emptyList(),
            notebookNotes = emptyList(),
            noteLinks = emptyList(),
            savedSearches = emptyList(),
            reminders = emptyList(),
            taskOccurrences = emptyList(),
            attachments = listOf(
                BackupAttachmentEntry(
                    id = customId,
                    archivePath = "attachments/$customId.png",
                    originalName = "legacy.png",
                    mime = "image/png",
                    bytes = customBytes.size.toLong(),
                    sha256 = customSha,
                ),
            ),
        )
        val customZip = File(temp.root, "custom-id.zip")
        BackupCodec.writeBackupZip(snapshot, customZip) { if (it == customId) customFile else null }
        val parsed = BackupCodec.readAndValidateArchive(customZip)
        assertEquals(customId, parsed.manifest.attachments.single().id)

        val badSnapshot = snapshot.copy(
            attachments = listOf(
                BackupAttachmentEntry(
                    id = "bad.part",
                    archivePath = "attachments/bad.png",
                    originalName = "bad.png",
                    mime = "image/png",
                    bytes = customBytes.size.toLong(),
                    sha256 = customSha,
                ),
            ),
        )
        assertThrows(BackupValidationException::class.java) {
            BackupCodec.writeBackupZip(badSnapshot, File(temp.root, "bad-id.zip")) { customFile }
        }
    }

    private fun writeMinimalValidBackupZip(name: String): File {
        val doc = """{"document_schema_version":1,"blocks":[{"id":"b1","type":"paragraph","text":"最小内容"}]}"""
        val snapshot = BackupSnapshot(
            exportedAt = "2026-11-15T00:00:00.000Z",
            appVersion = "0.1.13-todos.1",
            dbSchemaVersion = 5,
            notes = listOf(
                BackupNoteRecord(
                    id = "note-1",
                    title = "标题",
                    documentJson = doc,
                    searchText = "最小内容",
                    authorType = "user",
                    sourceType = "manual",
                    sourceUrl = null,
                    aiPolicy = "inherit",
                    createdAt = "2026-11-15T00:00:00.000Z",
                    updatedAt = "2026-11-15T00:00:00.000Z",
                    revision = 1L,
                    serverRevision = null,
                    contentHash = "hash1",
                    archivedAt = null,
                    deletedAt = null,
                    pinned = false,
                    lastDraftId = "d1",
                    committedSeq = 1L,
                    mediaMask = 0,
                ),
            ),
            versions = listOf(BackupVersionRecord("note-1", 1L, doc, "edit", "2026-11-15T00:00:00.000Z", "标题", "[]")),
            drafts = emptyList(),
            tags = emptyList(),
            notebooks = emptyList(),
            noteTags = emptyList(),
            notebookNotes = emptyList(),
            noteLinks = emptyList(),
            savedSearches = emptyList(),
            reminders = emptyList(),
            taskOccurrences = emptyList(),
            attachments = emptyList(),
        )
        return File(temp.root, name).also { file ->
            BackupCodec.writeBackupZip(snapshot, file) { null }
        }
    }

    private fun copyZipWithReplacement(
        source: File,
        dest: File,
        extraEntry: Pair<String, ByteArray>? = null,
        transform: (String, ByteArray) -> ByteArray = { _, b -> b },
    ) {
        ZipFile(source).use { zf ->
            ZipOutputStream(FileOutputStream(dest)).use { zos ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val data = zf.getInputStream(entry).use { it.readBytes() }
                    zos.putNextEntry(ZipEntry(entry.name))
                    zos.write(transform(entry.name, data))
                    zos.closeEntry()
                }
                if (extraEntry != null) {
                    zos.putNextEntry(ZipEntry(extraEntry.first))
                    zos.write(extraEntry.second)
                    zos.closeEntry()
                }
            }
        }
    }

    private fun patchFirstCentralDirectorySymlink(zipFile: File) {
        val bytes = zipFile.readBytes()
        for (i in 0 until bytes.size - 46) {
            if (bytes[i] == 0x50.toByte() &&
                bytes[i + 1] == 0x4b.toByte() &&
                bytes[i + 2] == 0x01.toByte() &&
                bytes[i + 3] == 0x02.toByte()
            ) {
                // external file attributes bytes[i+38..i+41], upper 16 bits = unix mode (0xA000 symlink).
                bytes[i + 40] = 0x00.toByte()
                bytes[i + 41] = 0xA0.toByte()
                zipFile.writeBytes(bytes)
                assertNotNull(bytes)
                return
            }
        }
        throw AssertionError("Central directory signature not found")
    }
}
