package io.github.z3275630ops.floatingnotes.data.backup

import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExternalNoteImporterTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val nowIso = "2026-11-25T10:00:00Z"

    @Test
    fun markdownParsesFrontmatterCheckboxesTagsAndLosses() {
        val file = textFile(
            """
            ---
            title: "浮记迁移"
            created_at: "2026-11-23T10:12:30+08:00"
            updated: 2026-11-24
            tags: [工作, "长文, 待读"]
            source_url: https://example.org/original#part
            ---
            # 正文标题
            阅读 https://x.com/#top 和 https://x.com/?q=notes#fragment，再归档 #项目/浮记
            - [ ] 待做
            * [X] 完成
            查看 [[旧笔记]] 和 ![截图](./img/a.png)
            """.trimIndent(),
        )

        val preview = preview(file, "export.MD")
        val note = preview.parsedNotes.single()
        assertEquals(ExternalImportFormat.MARKDOWN_SINGLE, preview.format)
        assertEquals("浮记迁移", note.title)
        assertEquals("2026-11-23T02:12:30Z", note.createdAtIso)
        assertEquals("2026-11-24T00:00:00Z", note.updatedAtIso)
        assertEquals("https://example.org/original#part", note.sourceUrl)
        assertEquals(listOf("工作", "长文, 待读", "项目/浮记"), note.tagPaths)
        assertEquals(3, preview.uniqueTagCount)
        assertEquals(listOf("待做", "完成"), note.blocks.filter { it.type == "checkbox" }.map { it.text })
        assertEquals(listOf(false, true), note.blocks.filter { it.type == "checkbox" }.map { it.checked })
        assertTrue(note.searchText.contains("[[旧笔记]]"))
        assertTrue(note.searchText.contains("![截图](./img/a.png)"))
        assertTrue(preview.lossyWarnings.any { "Wiki" in it })
        assertTrue(preview.lossyWarnings.any { "Markdown 图片" in it })
        assertEquals(
            BackupCodec.sha256Hex(NoteDocument(note.blocks).encode().toByteArray(Charsets.UTF_8)),
            note.contentHash,
        )
    }

    @Test
    fun markdownParsesMultilineTagsAndWarnsForInvalidDateOnly() {
        val file = textFile(
            """
            ---
            date: 2026-02-30
            tags:
              - 项目/浮记
              - '项目/测试'
            ---
            # 一级标题
            正文。
            """.trimIndent(),
        )

        val preview = preview(file, "export.md")
        val note = preview.parsedNotes.single()
        assertEquals("一级标题", note.title)
        assertEquals("正文。", note.searchText)
        assertEquals(listOf("项目/浮记", "项目/测试"), note.tagPaths)
        assertEquals(nowIso, note.createdAtIso)
        assertEquals(nowIso, note.updatedAtIso)
        assertEquals(1, preview.lossyWarnings.size)
        assertTrue(preview.lossyWarnings.single().contains("创建时间"))
    }

    @Test
    fun markdownPreservesLeadingDividersWithoutValidMetadata() {
        for (paragraph in listOf("这是一段普通正文。", "custom_key: 普通正文", "title: \"未闭合")) {
            val body = "---\n$paragraph\n---\n后续正文"
            val note = preview(textFile(body), "divider.md").parsedNotes.single()
            assertEquals("divider", note.title)
            assertEquals(listOf("---", paragraph, "---", "后续正文"), note.blocks.map { it.text })
        }
    }

    @Test
    fun markdownStripsOnlyTheRepeatedTitleAtTheStartOfAnExport() {
        for (titleLine in listOf("# 浮记导出", "浮记导出")) {
            val file = textFile("---\nid: \"note-id\"\ntitle: \"浮记导出\"\nlocal_revision: 1\n---\n\n$titleLine\n\n正文\n$titleLine")
            val note = preview(file, "export.md").parsedNotes.single()
            assertEquals("浮记导出", note.title)
            assertEquals(listOf("正文", titleLine), note.blocks.map { it.text })
        }
    }

    @Test
    fun flomoParsesMultipleCardsWithNestedDivsEntitiesAndMediaWarning() {
        val file = textFile(
            """
            <html><body>
            <!-- <div class="memo"><div class="content">注释</div></div> -->
            <script>let example = '<div class="memo"></div>';</script>
            <div class='memo extra'>
                <div class="time">2026-11-23 09:08:07</div>
                <div class="content"><div><p>A &amp; B<br>第二行 #flomo/标签</p></div>
                <ul><li>&lt;文字&gt; &quot;引号&quot; &#39; &#x1F600;</li></ul></div>
                <div class="files"><img src="a.png"></div>
            </div>
            <div class=memo>
                <div class=time>2026-11-24T09:08:07+08:00</div>
                <div class=content><p>另一条&nbsp;笔记</p></div>
            </div>
            </body></html>
            """.trimIndent(),
        )

        val preview = preview(file, "flomo.htm")
        assertEquals(ExternalImportFormat.FLOMO_HTML, preview.format)
        assertEquals(2, preview.totalParsed)
        val (first, second) = preview.parsedNotes
        assertNull(first.title)
        assertNull(first.sourceUrl)
        assertEquals("2026-11-23T09:08:07Z", first.createdAtIso)
        assertEquals(listOf("A & B", "第二行 #flomo/标签", "<文字> \"引号\" ' 😀"), first.blocks.map { it.text })
        assertEquals(listOf("flomo/标签"), first.tagPaths)
        assertEquals("2026-11-24T01:08:07Z", second.createdAtIso)
        assertEquals("另一条 笔记", second.searchText)
        assertEquals(listOf("含媒体附件未自动导入，需保留原备份文件夹"), preview.lossyWarnings)
    }

    @Test
    fun htmlWithoutMemoCardsReportsEmptyPreview() {
        val preview = preview(textFile("<!doctype html><body><p>普通页面</p></body>"), "data")
        assertEquals(ExternalImportFormat.FLOMO_HTML, preview.format)
        assertEquals(0, preview.totalParsed)
        assertTrue(preview.lossyWarnings.single().contains("没有找到"))
    }

    @Test
    fun flomoPreservesAnchorLabelsAndUrlsWithoutRepeatingMatchingLabels() {
        val file = textFile(
            """
            <div class="memo"><div class="content">
                <p><a href="https://example.com/post"><strong>原文</strong></a></p>
                <p><a href='https://example.com/?a=1&amp;b=2'>https://example.com/?a=1&amp;b=2</a></p>
            </div></div>
            """.trimIndent(),
        )
        val note = preview(file, "flomo.html").parsedNotes.single()
        assertEquals(listOf("原文 (https://example.com/post)", "https://example.com/?a=1&b=2"), note.blocks.map { it.text })
    }

    @Test
    fun zipMagicDetectsMixedBundleAndSkipsOsArtifacts() {
        val file = zipFile(
            "notes/one.md" to "Markdown #项目",
            "memos/one.html" to """<div class="memo"><div class="content"><p>flomo 笔记</p></div></div>""",
            "notes/two.markdown" to "第二篇 Markdown",
            "__MACOSX/ignored.md" to "忽略",
            "notes/._one.md" to "忽略",
            "files/photo.jpg" to "未读取的附件",
        )

        val preview = preview(file, "download")
        assertEquals(ExternalImportFormat.MARKDOWN_ZIP, preview.format)
        assertEquals(listOf("Markdown #项目", "flomo 笔记", "第二篇 Markdown"), preview.parsedNotes.map { it.searchText })
        assertEquals(1, preview.uniqueTagCount)
    }

    @Test
    fun markdownZipWarnsForLocalMediaLinksAndPreservesThem() {
        val localLink = "[recording](attachments/audio.m4a)"
        val remoteLink = "[recording](https://example.com/attachments/audio.m4a)"
        val local = preview(zipFile("notes/note.md" to "$localLink\n$remoteLink"), "bundle.zip")
        assertEquals(listOf(localLink, remoteLink), local.parsedNotes.single().blocks.map { it.text })
        assertEquals(listOf("含媒体附件未自动导入，需保留原备份文件夹"), local.lossyWarnings)
        val remoteOnly = preview(zipFile("notes/note.md" to remoteLink), "bundle.zip")
        assertTrue(remoteOnly.lossyWarnings.isEmpty())
    }

    @Test
    fun zipRejectsDecodedTextOverTheCumulativePreviewBudget() {
        val whitespace = " ".repeat(6_000_001)
        val file = zipFile("notes/one.md" to whitespace, "notes/two.md" to whitespace, compression = Deflater.NO_COMPRESSION)
        val error = assertThrows(BackupValidationException::class.java) { preview(file, "bundle.zip") }
        assertEquals("导入包文本总量超过安全预检上限（12M 字符），请拆分后分批导入", error.message)
    }

    @Test
    fun zipRejectsTraversalEvenInIgnoredEntries() {
        val file = zipFile("valid.md" to "正文", "__MACOSX/../escape.md" to "无效")
        assertThrows(BackupValidationException::class.java) { preview(file, "bundle.zip") }
    }

    @Test
    fun zipRejectsUnixSymlinksAndOversizedEntries() {
        val link = zipFile("ignored/.hidden" to "target.md")
        editZipDirectory(link) { bytes, start ->
            bytes.put(start + 5, 3.toByte()) // ZIP creator system Unix.
            bytes.putShort(start + 40, 0xa1ff.toShort())
        }
        assertThrows(BackupValidationException::class.java) { preview(link, "links.zip") }

        val oversized = zipFile("ignored.bin" to "内容")
        editZipDirectory(oversized) { bytes, start ->
            bytes.putInt(start + 24, BackupConstants.MAX_TOTAL_UNCOMPRESSED_BYTES.toInt() + 1)
        }
        assertThrows(BackupValidationException::class.java) { preview(oversized, "large.zip") }
    }

    @Test
    fun jsonParsesArrayAndBothObjectWrappers() {
        val array = """[{"title":"摘要","content":"两行\n正文","createdAt":"2026-11-24T19:00:00-05:00","updated_at":"2026-11-26","tags":["#项目/浮记","标签"],"url":"https://example.org"}]"""
        val note = preview(textFile(array), "data").parsedNotes.single()
        assertEquals("摘要", note.title)
        assertEquals(listOf("两行", "正文"), note.blocks.map { it.text })
        assertEquals("2026-11-25T00:00:00Z", note.createdAtIso)
        assertEquals("2026-11-26T00:00:00Z", note.updatedAtIso)
        assertEquals(listOf("项目/浮记", "标签"), note.tagPaths)
        assertEquals("https://example.org", note.sourceUrl)
        for (key in listOf("notes", "memos")) {
            val objectText = """{"$key":[{"text":"第一条"},{"body":"第二条","time":"2026-11-23 19:00:00"}]}"""
            val result = preview(textFile(objectText), "bundle.json")
            assertEquals(ExternalImportFormat.JSON_NOTES, result.format)
            assertEquals(listOf("第一条", "第二条"), result.parsedNotes.map { it.searchText })
            assertEquals(nowIso, result.parsedNotes[0].createdAtIso)
            assertEquals("2026-11-23T19:00:00Z", result.parsedNotes[1].createdAtIso)
            assertTrue(result.lossyWarnings.isEmpty())
        }
    }

    @Test
    fun duplicatesMatchExistingEncodedHashOrNormalizedText() {
        val json = textFile("""[{"text":"Hash match"},{"text":"TEXT   match","tags":["Project/Notes"]},{"text":"Unique","tags":["project/notes"]}]""")
        val initial = preview(json, "notes.json")
        val existingHash = initial.parsedNotes.first().contentHash
        val preview = ExternalNoteImporter.previewImport(
            json,
            "notes.json",
            existingContentHashes = setOf(existingHash),
            existingNormalizedTexts = setOf("text match"),
            nowIso = nowIso,
        )
        assertEquals(3, preview.totalParsed)
        assertEquals(2, preview.duplicateCount)
        assertEquals(1, preview.newCount)
        assertEquals(1, preview.uniqueTagCount)
        assertEquals(listOf(true, true, false), preview.parsedNotes.map { it.isDuplicate })
        assertEquals(existingHash, preview.parsedNotes.first().contentHash)
        assertFalse(initial.parsedNotes.any { it.isDuplicate })
    }

    @Test
    fun emptyBodiesWithDifferentTitlesOnlyMatchTheirOwnExactHash() {
        val file = textFile("""[{"title":"第一篇"},{"title":"第二篇"}]""")
        val existing = preview(file, "notes.json").parsedNotes.first()
        val result = ExternalNoteImporter.previewImport(
            file,
            "notes.json",
            existingContentHashes = setOf(existing.contentHash),
            existingNormalizedTexts = setOf(""),
            nowIso = nowIso,
        )
        assertEquals(listOf(true, false), result.parsedNotes.map { it.isDuplicate })
    }

    @Test
    fun duplicatePreviewDistinguishesTitlesAndCheckboxStates() {
        val body = "相同正文与待办事项"
        val file = zipFile(
            "original.md" to "# 原标题\n$body",
            "renamed.md" to "# 另一标题\n$body",
            "checked.md" to "# 原标题\n- [x] $body",
            "unchecked.md" to "# 原标题\n- [ ] $body",
        )
        val notes = preview(file, "notes.zip").parsedNotes
        val result = ExternalNoteImporter.previewImport(
            file,
            "notes.zip",
            existingContentHashes = setOf(notes[2].contentHash),
            existingNormalizedTexts = setOf(body, "原标题\n$body"),
            nowIso = nowIso,
        )
        assertEquals(listOf(true, false, true, false), result.parsedNotes.map { it.isDuplicate })
        assertEquals(4, notes.map { it.contentHash }.toSet().size)
    }

    private fun preview(file: File, displayName: String) =
        ExternalNoteImporter.previewImport(file, displayName, nowIso = nowIso)

    private fun textFile(text: String): File = folder.newFile().apply { writeText(text) }

    private fun zipFile(
        vararg entries: Pair<String, String>,
        compression: Int = Deflater.DEFAULT_COMPRESSION,
    ): File = folder.newFile().apply {
        ZipOutputStream(outputStream()).use { zip ->
            zip.setLevel(compression)
            for ((path, body) in entries) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(body.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    private fun editZipDirectory(file: File, edit: (ByteBuffer, Int) -> Unit) {
        val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val end = bytes.capacity() - 22
        assertEquals(0x06054b50, bytes.getInt(end))
        val start = bytes.getInt(end + 16)
        assertEquals(0x02014b50, bytes.getInt(start))
        edit(bytes, start)
        file.writeBytes(bytes.array())
    }
}
