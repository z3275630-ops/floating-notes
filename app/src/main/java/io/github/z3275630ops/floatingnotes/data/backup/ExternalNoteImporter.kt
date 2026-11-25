package io.github.z3275630ops.floatingnotes.data.backup

import androidx.room.withTransaction
import io.github.z3275630ops.floatingnotes.core.model.NoteBlock
import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import io.github.z3275630ops.floatingnotes.core.model.newId
import io.github.z3275630ops.floatingnotes.data.local.NoteEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteTagEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteVersionEntity
import io.github.z3275630ops.floatingnotes.data.local.NotesDatabase
import io.github.z3275630ops.floatingnotes.data.local.TagEntity
import io.github.z3275630ops.floatingnotes.data.local.TaskIndexEntity
import io.github.z3275630ops.floatingnotes.data.local.tagNameKey
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipFile

internal enum class ExternalImportFormat(val displayName: String) {
    MARKDOWN_SINGLE("单篇 Markdown"),
    MARKDOWN_ZIP("Markdown 压缩包"),
    FLOMO_HTML("flomo HTML 导出"),
    JSON_NOTES("JSON 笔记导出"),
}

internal data class ParsedExternalNote(
    val tempId: String,
    val title: String?,
    val blocks: List<NoteBlock>,
    val searchText: String,
    val contentHash: String,
    val createdAtIso: String,
    val updatedAtIso: String,
    val tagPaths: List<String>,
    val sourceUrl: String?,
    val sourceType: String,
    val isDuplicate: Boolean = false,
)

internal data class ExternalImportPreview(
    val sourceName: String,
    val format: ExternalImportFormat,
    val totalParsed: Int,
    val duplicateCount: Int,
    val newCount: Int,
    val uniqueTagCount: Int,
    val lossyWarnings: List<String>,
    val parsedNotes: List<ParsedExternalNote>,
)

internal data class ExternalImportCommitResult(
    val importedCount: Int,
    val skippedDuplicates: Int,
    val createdTagCount: Int,
    val warnings: List<String>,
)

internal object ExternalNoteImporter {
    private const val BATCH_SIZE = 500
    private const val MAX_IMPORT_PREVIEW_CHARS = 12_000_000
    private const val MEDIA_WARNING = "含媒体附件未自动导入，需保留原备份文件夹"
    private val whitespace = Regex("""[\s\p{Z}]+""")
    private val heading = Regex(""" {0,3}#{1,6}[ \t]+(.+)""")
    private val checkbox = Regex("""[ \t]*[-*][ \t]+\[([ xX])](?:[ \t]+(.*))?""")
    private val fence = Regex(""" {0,3}(\x60{3,}|~{3,})(.*)""")
    private val yamlListItem = Regex("""^\s*-\s+(.+)$""")
    private val yamlField = Regex("""([a-zA-Z_][\w-]*):(?:[ \t]+(.*)|[ \t]*)""")
    private val yamlInlineItem = Regex("""(?:"(?:\\.|[^"\\]++)*+"|'(?:''|[^']++)*'|[^,'"]++)++""")
    private val yamlSingleQuoted = Regex("""'(?:''|[^'])*+'""")
    private val frontmatterKeys = setOf(
        "title", "created", "created_at", "date", "updated", "updated_at", "tags", "tag",
        "source_url", "url", "id", "pinned", "archived_at",
    )
    private val wikiLink = Regex("""\[\[[^\]\r\n]+]]""")
    private val markdownImage = Regex("""!\[[^\]\r\n]*]\(\s*(?:<([^>\r\n]+)>|([^\s)]+))""")
    private val markdownLink = Regex("""(?<!!)\[[^\]\r\n]*]\(\s*(?:<([^>\r\n]+)>|([^\s)]+))[^)\r\n]*\)""")
    private val remoteTarget = Regex("""^(?:[a-z][a-z\d+.-]*://|//)""", RegexOption.IGNORE_CASE)
    private val attachmentDirectory = Regex("""(?:^|/)attachments/""", RegexOption.IGNORE_CASE)
    private val mediaExtensions = setOf("m4a", "mp3", "mp4", "webm", "ogg", "wav", "pdf", "bin")
    private val url = Regex("""(?:[a-z][a-z\d+.-]*://|(?<![\w/])//|(?<![\w.])www\.)[^\s<>"“”‘’。，；！？、]+""", RegexOption.IGNORE_CASE)
    private val hashtag = Regex("""(?<![\p{L}\p{M}\p{N}_/#\\])#([\p{L}\p{M}\p{N}_-]+(?:/[\p{L}\p{M}\p{N}_-]+)*+)""")
    private val htmlToken = Regex(
        """<!--[\s\S]*?(?:-->|$)|<(script|style)\b[^>]*>[\s\S]*?(?:</\1\s*>|$)|<![^>]*>|<(/?)([a-z][a-z\d:-]*)\b((?:"[^"]*"|'[^']*'|[^"'<>]++)*+)>""",
        RegexOption.IGNORE_CASE,
    )
    private val htmlAttribute = Regex("""([^\s=/"'<>]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>]+)))?""")
    private val htmlEntity = Regex("""&(#(?:[xX][0-9a-fA-F]+|\d+)|amp|lt|gt|quot|apos|nbsp);""")
    private val htmlBoundaries = setOf("p", "li", "br", "div", "ul", "ol", "blockquote", "pre")

    fun previewImport(
        sourceFile: File,
        displayName: String,
        existingContentHashes: Set<String> = emptySet(),
        existingNormalizedTexts: Set<String> = emptySet(),
        nowIso: String = Instant.now().toString(),
    ): ExternalImportPreview = try {
        val sourceName = displayName.ifBlank { sourceFile.name }
        val parser = ImportParser(parseInstant(nowIso)?.toString() ?: Instant.now().toString())
        val format = try {
            val hint = detectFormat(sourceFile, sourceName)
            if (hint == ExternalImportFormat.MARKDOWN_ZIP) {
                parseZip(sourceFile, parser)
            } else {
                val raw = sourceFile.inputStream().use { readText(it, BackupConstants.MAX_ENTRY_BYTES) }
                parser.countDecodedText(raw)
                val detected = hint ?: detectTextFormat(raw)
                when (detected) {
                    ExternalImportFormat.MARKDOWN_SINGLE -> parser.parseMarkdownText(raw, fileTitle(sourceName), parser.nowIso)
                    ExternalImportFormat.FLOMO_HTML -> parser.parseFlomoHtml(raw, parser.nowIso)
                    ExternalImportFormat.JSON_NOTES -> parser.parseJsonNotes(raw, parser.nowIso)
                    ExternalImportFormat.MARKDOWN_ZIP -> error("ZIP is handled before reading text")
                }
                detected
            }
        } catch (e: BackupValidationException) {
            throw e
        } catch (e: IOException) {
            throw BackupValidationException("无法读取导入文件或压缩包已损坏", e)
        }
        val normalizedExisting = existingNormalizedTexts.mapTo(HashSet(), ::normalizeText)
        val titledExisting = existingNormalizedTexts.asSequence()
            .filter { '\n' in it }
            .map { normalizeText(it.substringBefore('\n')) to normalizeText(it.substringAfter('\n')) }
            .toHashSet()
        val notes = parser.notes.map { note ->
            val normalized = normalizeText(note.searchText)
            val title = note.title?.let(::normalizeText).orEmpty()
            // Body-only keys carry no title or checkbox state. Structured imports need an exact hash.
            val duplicate = note.contentHash in existingContentHashes ||
                (normalized.isNotBlank() && note.blocks.all { it.type == "paragraph" } &&
                    if (title.isEmpty()) normalized in normalizedExisting else (title to normalized) in titledExisting)
            note.copy(isDuplicate = duplicate)
        }
        val duplicates = notes.count { it.isDuplicate }
        ExternalImportPreview(
            sourceName = sourceName,
            format = format,
            totalParsed = notes.size,
            duplicateCount = duplicates,
            newCount = notes.size - duplicates,
            uniqueTagCount = notes.flatMap { it.tagPaths }.map(::tagPathKey).toSet().size,
            lossyWarnings = parser.warnings.toList(),
            parsedNotes = notes,
        )
    } catch (_: OutOfMemoryError) {
        throw BackupValidationException("导入文件过大，超出当前设备可用内存，请拆分后分批导入")
    }

    suspend fun commitImport(
        preview: ExternalImportPreview,
        skipDuplicates: Boolean,
        database: NotesDatabase,
        now: Instant = Instant.now(),
    ): ExternalImportCommitResult {
        val selected = preview.parsedNotes.filterNot { skipDuplicates && it.isDuplicate }
        val warnings = preview.lossyWarnings.toMutableSet()
        val createdTags = database.withTransaction {
            val notesDao = database.notes()
            val organization = database.organization()
            val tags = organization.tags().associateBy { it.parentId to it.nameKey }.toMutableMap()
            var createdTagCount = 0

            suspend fun resolveTag(path: String): String {
                var parentId = ""
                for (segment in path.split('/')) {
                    val key = parentId to tagNameKey(segment)
                    val tag = tags[key] ?: TagEntity(newId(), parentId, segment, key.second).also {
                        organization.insertTag(it)
                        tags[key] = it
                        createdTagCount++
                    }
                    parentId = tag.id
                }
                return parentId
            }

            for (batch in selected.chunked(BATCH_SIZE)) {
                val entities = batch.map { note ->
                    val createdAt = parseInstant(note.createdAtIso)?.toString() ?: now.toString().also {
                        warnings.add("部分笔记的创建时间无法识别，已使用导入时间")
                    }
                    val updatedAt = parseInstant(note.updatedAtIso)?.toString() ?: now.toString().also {
                        warnings.add("部分笔记的更新时间无法识别，已使用导入时间")
                    }
                    NoteEntity(
                        id = newId(),
                        title = note.title,
                        documentJson = NoteDocument(note.blocks).encode(),
                        searchText = note.searchText,
                        sourceType = note.sourceType,
                        sourceUrl = note.sourceUrl,
                        createdAt = createdAt,
                        updatedAt = updatedAt,
                        revision = 1L,
                        contentHash = note.contentHash,
                        lastDraftId = "",
                        committedSeq = 0L,
                    )
                }
                notesDao.insertNotes(entities)
                notesDao.insertVersions(entities.map { note ->
                    NoteVersionEntity(
                        noteId = note.id,
                        revision = note.revision,
                        documentJson = note.documentJson,
                        origin = "import",
                        createdAt = note.createdAt,
                        title = note.title,
                        attachmentRefs = "[]",
                    )
                })
                val noteTags = ArrayList<NoteTagEntity>(BATCH_SIZE)
                val tasks = ArrayList<TaskIndexEntity>(BATCH_SIZE)
                for ((index, note) in batch.withIndex()) {
                    val noteId = entities[index].id
                    for (path in note.tagPaths) {
                        noteTags.add(NoteTagEntity(noteId, resolveTag(path)))
                        if (noteTags.size == BATCH_SIZE) {
                            organization.insertNoteTags(noteTags)
                            noteTags.clear()
                        }
                    }
                    for (block in note.blocks) {
                        if (block.type != "checkbox") continue
                        tasks.add(TaskIndexEntity(noteId, block.id, block.checked, block.dueDate, block.occurrenceId))
                        if (tasks.size == BATCH_SIZE) {
                            notesDao.addTasks(tasks)
                            tasks.clear()
                        }
                    }
                }
                if (noteTags.isNotEmpty()) organization.insertNoteTags(noteTags)
                if (tasks.isNotEmpty()) notesDao.addTasks(tasks)
            }
            createdTagCount
        }
        return ExternalImportCommitResult(
            importedCount = selected.size,
            skippedDuplicates = preview.parsedNotes.size - selected.size,
            createdTagCount = createdTags,
            warnings = warnings.toList(),
        )
    }

    private fun detectFormat(file: File, displayName: String): ExternalImportFormat? {
        val magic = file.inputStream().use { it.readBytesUpTo(4) }
        if (magic.size == 4 && magic[0] == 0x50.toByte() && magic[1] == 0x4b.toByte() &&
            ((magic[2] == 3.toByte() && magic[3] == 4.toByte()) ||
                (magic[2] == 5.toByte() && magic[3] == 6.toByte()) ||
                (magic[2] == 7.toByte() && magic[3] == 8.toByte()))
        ) return ExternalImportFormat.MARKDOWN_ZIP
        return formatFromName(displayName) ?: formatFromName(file.name)
    }

    private fun InputStream.readBytesUpTo(max: Int): ByteArray {
        val bytes = ByteArray(max)
        var size = 0
        while (size < max) {
            val count = read(bytes, size, max - size)
            if (count < 0) break
            size += count
        }
        return bytes.copyOf(size)
    }

    private fun formatFromName(name: String): ExternalImportFormat? = when (name.substringAfterLast('.', "").lowercase()) {
        "md", "markdown" -> ExternalImportFormat.MARKDOWN_SINGLE
        "zip" -> ExternalImportFormat.MARKDOWN_ZIP
        "html", "htm" -> ExternalImportFormat.FLOMO_HTML
        "json" -> ExternalImportFormat.JSON_NOTES
        else -> null
    }

    private fun detectTextFormat(raw: String): ExternalImportFormat {
        val text = raw.trimStart()
        return when {
            text.startsWith('{') || text.startsWith('[') && !text.startsWith("[[") ->
                ExternalImportFormat.JSON_NOTES
            htmlToken.find(text)?.range?.first == 0 -> ExternalImportFormat.FLOMO_HTML
            else -> ExternalImportFormat.MARKDOWN_SINGLE
        }
    }

    private fun fileTitle(name: String): String? = name.substringAfterLast('/').substringBeforeLast('.')
        .trim().ifEmpty { null }

    private fun parseZip(file: File, parser: ImportParser): ExternalImportFormat {
        val entryCount = validateZipDirectory(file)
        ZipFile(file).use { zip ->
            if (zip.size() != entryCount) throw BackupValidationException("ZIP 条目数量不一致")
            val names = HashSet<String>()
            val entries = zip.entries().asSequence().toList()
            var declaredBytes = 0L
            for (entry in entries) {
                val path = BackupCodec.validateArchiveEntryPath(entry.name)
                if (!names.add(path)) throw BackupValidationException("ZIP 内存在重复路径")
                if (entry.size < 0 || entry.size > BackupConstants.MAX_TOTAL_UNCOMPRESSED_BYTES - declaredBytes) {
                    throw BackupValidationException("ZIP 解压总大小超限")
                }
                declaredBytes += entry.size
            }

            var remaining = BackupConstants.MAX_TOTAL_UNCOMPRESSED_BYTES
            var sawMarkdown = false
            var sawHtml = false
            for (entry in entries) {
                if (entry.isDirectory || entry.name.split('/').any {
                        it.startsWith('.') || it.equals("__MACOSX", ignoreCase = true)
                    }
                ) continue
                val format = formatFromName(entry.name)
                if (format != ExternalImportFormat.MARKDOWN_SINGLE && format != ExternalImportFormat.FLOMO_HTML) continue
                if (entry.size > BackupConstants.MAX_ENTRY_BYTES) throw BackupValidationException("ZIP 笔记条目过大")
                if (entry.size >= BackupConstants.RATIO_CHECK_MIN_BYTES && entry.compressedSize > 0 &&
                    entry.size / entry.compressedSize > BackupConstants.MAX_COMPRESSION_RATIO
                ) throw BackupValidationException("ZIP 笔记条目压缩比异常")

                val bytes = zip.getInputStream(entry).use { readBytes(it, minOf(remaining, entry.size)) }
                if (bytes.size.toLong() != entry.size || CRC32().apply { update(bytes) }.value != entry.crc) {
                    throw BackupValidationException("ZIP 笔记条目校验失败")
                }
                remaining -= bytes.size
                val raw = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
                parser.countDecodedText(raw)
                if (format == ExternalImportFormat.MARKDOWN_SINGLE) {
                    sawMarkdown = true
                    parser.parseMarkdownText(raw, fileTitle(entry.name), parser.nowIso)
                } else {
                    sawHtml = true
                    parser.parseFlomoHtml(raw, parser.nowIso)
                }
            }
            if (!sawMarkdown && !sawHtml) parser.warnings.add("ZIP 中没有找到 Markdown 或 flomo HTML 文件")
            return if (sawHtml && !sawMarkdown) ExternalImportFormat.FLOMO_HTML else ExternalImportFormat.MARKDOWN_ZIP
        }
    }

    // Adapted from BackupCodec's central-directory scan. ZipEntry does not expose Unix file modes.
    // Reject directories we cannot verify, including split ZIPs and ZIP64.
    private fun validateZipDirectory(file: File): Int = RandomAccessFile(file, "r").use { archive ->
        val tail = ByteBuffer.allocate(minOf(archive.length(), 65_557L).toInt()).order(ByteOrder.LITTLE_ENDIAN)
        val tailStart = archive.length() - tail.capacity()
        archive.seek(tailStart)
        archive.readFully(tail.array())
        val end = (tail.capacity() - 22 downTo 0).firstOrNull { offset ->
            tail.getInt(offset) == 0x06054b50 && offset + 22 + tail.unsignedShort(offset + 20) == tail.capacity()
        } ?: throw BackupValidationException("ZIP 中央目录缺失")
        val count = tail.unsignedShort(end + 10)
        if (count > BackupConstants.MAX_ARCHIVE_ENTRIES) throw BackupValidationException("ZIP 条目数量超限")
        val size = tail.unsignedInt(end + 12)
        var offset = tail.unsignedInt(end + 16)
        val directoryEnd = tailStart + end
        if (tail.getInt(end + 4) != 0 || tail.unsignedShort(end + 8) != count || offset + size != directoryEnd) {
            throw BackupValidationException("ZIP 中央目录格式不支持或已损坏")
        }

        val header = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN)
        repeat(count) {
            if (offset + header.capacity() > directoryEnd) throw BackupValidationException("ZIP 中央目录不完整")
            archive.seek(offset)
            archive.readFully(header.array())
            if (header.getInt(0) != 0x02014b50 || header.unsignedShort(34) != 0) {
                throw BackupValidationException("ZIP 条目头无效")
            }
            if ((header.unsignedShort(40) and 0xf000) == 0xa000) {
                throw BackupValidationException("ZIP 禁止包含符号链接条目")
            }
            offset += header.capacity() + header.unsignedShort(28) + header.unsignedShort(30) + header.unsignedShort(32)
        }
        if (offset != directoryEnd) throw BackupValidationException("ZIP 中央目录长度不一致")
        count
    }

    private fun ByteBuffer.unsignedShort(offset: Int): Int = getShort(offset).toInt() and 0xffff

    private fun ByteBuffer.unsignedInt(offset: Int): Long = getInt(offset).toLong() and 0xffffffffL

    private fun readText(input: InputStream, limit: Long): String =
        readBytes(input, limit).toString(Charsets.UTF_8).removePrefix("\uFEFF")

    private fun readBytes(input: InputStream, limit: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return output.toByteArray()
            if (output.size().toLong() + count > limit) throw BackupValidationException("导入文件解压读取大小超限")
            output.write(buffer, 0, count)
        }
    }

    private class ImportParser(val nowIso: String) {
        val notes = mutableListOf<ParsedExternalNote>()
        val warnings = linkedSetOf<String>()
        private var decodedChars = 0L
        private var parsedChars = 0L

        fun countDecodedText(raw: String) {
            decodedChars += raw.length
            checkPreviewBudget(decodedChars)
        }

        fun parseMarkdownText(raw: String, fallbackTitle: String?, nowIso: String) {
            val lines = raw.lines()
            val end = if (lines.first().trimEnd() == "---") {
                (1 until lines.size).firstOrNull { lines[it].trimEnd() == "---" || lines[it].trimEnd() == "..." }
            } else null
            val frontmatter = end?.let { parseFrontmatter(lines.subList(1, it)) }
            val fields = frontmatter?.fields.orEmpty()
            val tags = frontmatter?.tags.orEmpty().toMutableList()
            val blocks = mutableListOf<NoteBlock>()
            var title = fields["title"]?.takeIf(String::isNotBlank)
            val bodyStart = if (frontmatter == null) 0 else checkNotNull(end) + 1
            val duplicateTitleIndex = title?.trim()?.let { metadataTitle ->
                (bodyStart until lines.size).firstOrNull { lines[it].isNotBlank() }?.takeIf {
                    val first = lines[it].trim()
                    first == metadataTitle || first == "# $metadataTitle"
                }
            }
            var activeFence: String? = null
            for (index in bodyStart until lines.size) {
                if (index == duplicateTitleIndex) continue
                val line = lines[index]
                val fenceMatch = fence.matchEntire(line)
                val currentFence = activeFence
                if (currentFence != null) {
                    blocks.add(NoteBlock.paragraph(line))
                    if (fenceMatch != null && fenceMatch.groupValues[1].first() == currentFence.first() &&
                        fenceMatch.groupValues[1].length >= currentFence.length && fenceMatch.groupValues[2].isBlank()
                    ) activeFence = null
                    continue
                }
                if (fenceMatch != null) {
                    activeFence = fenceMatch.groupValues[1]
                    blocks.add(NoteBlock.paragraph(line))
                    continue
                }
                if (line.isBlank()) continue
                val check = checkbox.matchEntire(line)
                val headingText = heading.matchEntire(line)?.groupValues?.get(1)?.trim()
                when {
                    check != null -> blocks.add(NoteBlock.checkbox(check.groupValues[2], checked = check.groupValues[1] != " "))
                    title == null && headingText != null -> title = headingText
                    else -> blocks.add(NoteBlock.paragraph(line))
                }
                tags.addAll(inlineTags(if (headingText != null) headingText else line))
                if (wikiLink.containsMatchIn(line)) warnings.add("含 Wiki 链接，已保留原文，未建立笔记引用")
                if (markdownImage.findAll(line).any { !remoteTarget.containsMatchIn(markdownTarget(it)) }) {
                    warnings.add("含本地 Markdown 图片，已保留原链接，未导入图片附件")
                }
                if (MEDIA_WARNING !in warnings && markdownLink.findAll(line).any { isLocalMedia(markdownTarget(it)) }) {
                    warnings.add(MEDIA_WARNING)
                }
            }
            if (blocks.isEmpty() && title == null) {
                warnings.add("已跳过没有正文或标题的 Markdown 笔记")
                return
            }
            addNote(
                title = title ?: fallbackTitle,
                blocks = blocks,
                created = fields.firstString("created", "created_at", "date"),
                updated = fields.firstString("updated", "updated_at"),
                tags = tags,
                sourceUrl = fields.firstString("source_url", "url"),
                nowIso = nowIso,
            )
        }

        fun parseFlomoHtml(html: String, nowIso: String) {
            val memos = divContents(html, setOf("memo"))
            if (memos.isEmpty()) {
                warnings.add("HTML 中没有找到 flomo 笔记卡片")
                return
            }
            for (memo in memos) {
                val memoHtml = html.substring(memo.content)
                val divs = divContents(memoHtml, setOf("time", "content", "files")).groupBy { it.role }
                if (divs.containsKey("files") || htmlToken.findAll(memoHtml).any {
                        it.groupValues[3].equals("img", true) || it.groupValues[3].equals("audio", true)
                    }
                ) warnings.add(MEDIA_WARNING)

                val content = divs["content"]?.flatMap { htmlLines(memoHtml.substring(it.content)) }.orEmpty()
                if (content.isEmpty()) {
                    warnings.add("已跳过没有可导入正文的 flomo 笔记")
                    continue
                }
                addNote(
                    title = null,
                    blocks = content.map { NoteBlock.paragraph(it) },
                    created = divs["time"]?.firstOrNull()?.let { htmlLines(memoHtml.substring(it.content)).joinToString(" ") },
                    updated = null,
                    tags = content.flatMap(::inlineTags),
                    sourceUrl = null,
                    nowIso = nowIso,
                )
            }
        }

        fun parseJsonNotes(jsonText: String, nowIso: String) {
            val rows = when (val root = MiniJson.parse(jsonText)) {
                is List<*> -> root
                is Map<*, *> -> (root["notes"] as? List<*>) ?: (root["memos"] as? List<*>)
                else -> null
            } ?: throw BackupValidationException("JSON 必须是笔记数组或包含 notes / memos 数组的对象")
            for (row in rows) {
                val record = row as? Map<*, *>
                val content = record?.firstString("content", "text", "body")
                val title = record?.firstString("title")
                if (record == null || (content == null && title == null)) {
                    warnings.add("已跳过没有正文或标题的 JSON 笔记条目")
                    continue
                }
                val tags = (record["tags"] as? List<*>)?.filterIsInstance<String>().orEmpty()
                addNote(
                    title = title,
                    blocks = content.orEmpty().lineSequence().filter(String::isNotBlank).map { NoteBlock.paragraph(it) }.toList(),
                    created = record.firstString("created_at", "createdAt", "time"),
                    updated = record.firstString("updated_at", "updatedAt"),
                    tags = tags + inlineTags(content.orEmpty()),
                    sourceUrl = record.firstString("source_url", "url"),
                    nowIso = nowIso,
                )
            }
        }

        private fun addNote(
            title: String?,
            blocks: List<NoteBlock>,
            created: String?,
            updated: String?,
            tags: List<String>,
            sourceUrl: String?,
            nowIso: String,
        ) {
            parsedChars += blocks.sumOf { it.text.length.toLong() } + tags.sumOf { it.length.toLong() } +
                title.orEmpty().length + sourceUrl.orEmpty().length
            checkPreviewBudget(parsedChars)
            // Block IDs are scoped to a note. Stable IDs keep its encoded hash repeatable across previews.
            // Include the title so the exact hash also distinguishes equal bodies under different titles.
            val stableBlocks = blocks.ifEmpty { listOf(NoteBlock.paragraph()) }.mapIndexed { index, block ->
                val key = MiniJson.encode(listOf(index, block.type, block.checked, block.text, title?.trim()?.takeIf { index == 0 }))
                block.copy(id = UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString())
            }
            val document = NoteDocument(stableBlocks)
            val createdAt = importDate(created, nowIso, nowIso, "创建")
            notes.add(
                ParsedExternalNote(
                    tempId = newId(),
                    title = title,
                    blocks = stableBlocks,
                    searchText = document.text,
                    contentHash = BackupCodec.sha256Hex(document.encode().toByteArray(Charsets.UTF_8)),
                    createdAtIso = createdAt,
                    updatedAtIso = importDate(updated, createdAt, nowIso, "更新"),
                    tagPaths = tags.mapNotNull(::cleanTagPath).distinctBy(::tagPathKey),
                    sourceUrl = sourceUrl,
                    sourceType = "import",
                ),
            )
        }

        private fun importDate(raw: String?, missing: String, fallback: String, label: String): String {
            if (raw.isNullOrBlank()) return missing
            return parseInstant(raw)?.toString() ?: fallback.also {
                warnings.add("部分笔记的${label}时间无法识别，已使用导入时间")
            }
        }
    }

    private fun checkPreviewBudget(chars: Long) {
        if (chars > MAX_IMPORT_PREVIEW_CHARS) {
            throw BackupValidationException("导入包文本总量超过安全预检上限（12M 字符），请拆分后分批导入")
        }
    }

    private data class Frontmatter(val fields: Map<String, String>, val tags: List<String>)

    private fun parseFrontmatter(lines: List<String>): Frontmatter? {
        val fields = mutableMapOf<String, String>()
        val tags = mutableListOf<String>()
        val keys = mutableSetOf<String>()
        var readingTags = false
        for (line in lines) {
            if (line.isBlank() || line.trimStart().startsWith('#')) continue
            val item = yamlListItem.matchEntire(line)
            if (readingTags && item != null) {
                tags.add(yamlScalar(item.groupValues[1]) ?: return null)
                continue
            }
            val field = yamlField.matchEntire(line) ?: return null
            val key = field.groupValues[1].lowercase()
            if (!keys.add(key)) return null
            val value = field.groupValues[2].trim()
            val isTagKey = key == "tags" || key == "tag"
            readingTags = isTagKey && value.isEmpty()
            if (isTagKey && value.startsWith('[') && value.endsWith(']')) {
                val items = value.substring(1, value.lastIndex)
                if (items.isBlank()) continue
                var cursor = 0
                for (match in yamlInlineItem.findAll(items)) {
                    if (match.range.first != cursor) return null
                    tags.add(yamlScalar(match.value) ?: return null)
                    cursor = match.range.last + 1
                    if (cursor < items.length && items[cursor++] != ',') return null
                }
                if (cursor != items.length) return null
            } else {
                val scalar = yamlScalar(value) ?: return null
                if (!isTagKey) fields[key] = scalar
                else if (scalar.isNotEmpty()) tags.add(scalar)
            }
        }
        return if (keys.any { it in frontmatterKeys }) Frontmatter(fields, tags) else null
    }

    private fun yamlScalar(raw: String): String? {
        val text = raw.trim()
        return when {
            text.startsWith('"') -> try {
                MiniJson.parse(text) as? String
            } catch (_: BackupValidationException) {
                null
            }
            text.startsWith('\'') -> if (yamlSingleQuoted.matches(text)) {
                text.substring(1, text.lastIndex).replace("''", "'")
            } else null
            text.firstOrNull() in listOf('[', '{', '|', '>') || ": " in text -> null
            else -> text
        }
    }

    private fun markdownTarget(match: MatchResult): String = match.groupValues[1].ifEmpty { match.groupValues[2] }

    private fun isLocalMedia(target: String): Boolean {
        if (remoteTarget.containsMatchIn(target)) return false
        val path = target.substringBefore('?').substringBefore('#').replace('\\', '/')
        return attachmentDirectory.containsMatchIn(path) || path.substringAfterLast('.', "").lowercase() in mediaExtensions
    }

    private fun Map<*, *>.firstString(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { (get(it) as? String)?.takeIf(String::isNotBlank) }

    private fun parseInstant(raw: String): Instant? {
        val text = raw.trim().replaceFirst(' ', 'T')
        // A source timestamp without an offset is treated as UTC on every device.
        return runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(text).toInstant(ZoneOffset.UTC) }.getOrNull()
            ?: runCatching { LocalDate.parse(text).atStartOfDay().toInstant(ZoneOffset.UTC) }.getOrNull()
    }

    private fun normalizeText(text: String): String = whitespace.replace(text, " ").trim().lowercase()

    private fun inlineTags(text: String): List<String> =
        hashtag.findAll(url.replace(text, " ")).map { it.groupValues[1] }.toList()

    private fun cleanTagPath(path: String): String? {
        val segments = path.trim().removePrefix("#").split('/').map(String::trim)
        return segments.takeIf { it.all(String::isNotEmpty) }?.joinToString("/")
    }

    private fun tagPathKey(path: String): String = path.split('/').joinToString("/") { tagNameKey(it) }

    private data class DivStart(val role: String?, val contentStart: Int)

    private data class DivContent(val role: String, val content: IntRange)

    private fun divContents(html: String, roles: Set<String>): List<DivContent> {
        val stack = ArrayList<DivStart>()
        val found = mutableListOf<DivContent>()
        // Store offsets on the stack so nested divs do not terminate enclosing cards early.
        for (token in htmlToken.findAll(html)) {
            if (!token.groupValues[3].equals("div", ignoreCase = true)) continue
            if (token.groupValues[2] == "/") {
                if (stack.isEmpty()) continue
                val div = stack.removeAt(stack.lastIndex)
                if (div.role != null) found.add(DivContent(div.role, div.contentStart until token.range.first))
            } else {
                val attribute = htmlAttribute.findAll(token.groupValues[4])
                    .firstOrNull { it.groupValues[1].equals("class", ignoreCase = true) }
                val classNames = attribute?.groupValues?.drop(2)?.firstOrNull(String::isNotEmpty).orEmpty()
                val role = classNames.split(whitespace).firstOrNull { it in roles }
                stack.add(DivStart(role, token.range.last + 1))
            }
        }
        return found.sortedBy { it.content.first }
    }

    private fun htmlLines(html: String): List<String> {
        val source = whitespace.replace(html, " ")
        val text = StringBuilder()
        var cursor = 0
        var href: String? = null
        var labelStart = 0
        for (token in htmlToken.findAll(source)) {
            text.append(source, cursor, token.range.first)
            cursor = token.range.last + 1
            val tag = token.groupValues[3].lowercase()
            when {
                tag in htmlBoundaries -> text.append('\n')
                tag == "a" && token.groupValues[2] != "/" -> {
                    href = htmlAttribute.findAll(token.groupValues[4])
                        .firstOrNull { it.groupValues[1].equals("href", ignoreCase = true) }
                        ?.groupValues?.drop(2)?.firstOrNull(String::isNotBlank)?.trim()
                    labelStart = text.length
                }
                tag == "a" -> {
                    href?.let { target ->
                        val label = decodeEntities(text.substring(labelStart)).trim()
                        when {
                            label.isEmpty() -> text.append(target)
                            label != decodeEntities(target) -> text.append(" (").append(target).append(')')
                        }
                    }
                    href = null
                }
            }
        }
        text.append(source, cursor, source.length)
        return text.lineSequence().map { line ->
            decodeEntities(whitespace.replace(line, " ")).trim()
        }.filter(String::isNotEmpty).toList()
    }

    private fun decodeEntities(text: String): String = htmlEntity.replace(text) { entity ->
        val key = entity.groupValues[1]
        when (key) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            "nbsp" -> " "
            else -> {
                val digits = key.substring(1)
                val code = if (digits.startsWith('x', ignoreCase = true)) digits.substring(1).toIntOrNull(16)
                else digits.toIntOrNull()
                if (code != null && code in 1..0x10ffff && code !in 0xd800..0xdfff) {
                    String(Character.toChars(code))
                } else entity.value
            }
        }
    }
}
