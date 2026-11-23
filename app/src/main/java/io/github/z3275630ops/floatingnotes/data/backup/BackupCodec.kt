package io.github.z3275630ops.floatingnotes.data.backup

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal object BackupCodec {
    private val SAFE_EXT = Regex("^[a-z0-9]{1,8}$")
    private val SAFE_ID = Regex("^[0-9A-Za-z._-]{1,128}$")
    private val CONTROL_OR_INVALID_PATH = Regex("[\\u0000-\\u001f\\\\]")
    private val CONTROL_CHARS = Regex("[\\u0000-\\u001f\\u007f]")
    private const val BUFFER_SIZE = 64 * 1024

    fun isSafeAttachmentId(id: String): Boolean =
        SAFE_ID.matches(id) &&
            id != "." &&
            id != ".." &&
            !id.endsWith(".part") &&
            !id.endsWith(".old")

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun sha256File(file: File): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val buffer = ByteArray(BUFFER_SIZE)
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                digest.update(buffer, 0, read)
            }
        }
        return total to digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun validateArchiveEntryPath(rawName: String): String {
        if (rawName.isBlank()) throw BackupValidationException("压缩包内存在空路径条目")
        if (rawName.contains('\u0000') || CONTROL_OR_INVALID_PATH.containsMatchIn(rawName)) {
            throw BackupValidationException("压缩包包含非法路径或反斜杠: $rawName")
        }
        if (rawName.startsWith("/") || (rawName.length >= 2 && rawName[1] == ':' && rawName[0].isLetter())) {
            throw BackupValidationException("压缩包禁止包含绝对路径: $rawName")
        }
        val normalized = rawName.removeSuffix("/")
        if (normalized.isEmpty()) throw BackupValidationException("压缩包存在根路径条目: $rawName")
        val segments = normalized.split('/')
        for (segment in segments) {
            if (segment.isEmpty() || segment == "." || segment == "..") {
                throw BackupValidationException("压缩包存在路径穿越条目: $rawName")
            }
        }
        return if (rawName.endsWith("/")) "$normalized/" else normalized
    }

    fun safeAttachmentExtension(mime: String, originalName: String): String {
        val fromName = originalName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (SAFE_EXT.matches(fromName)) return fromName
        return when (mime.lowercase(Locale.ROOT)) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/heic" -> "heic"
            "audio/mp4", "audio/m4a" -> "m4a"
            "audio/mpeg" -> "mp3"
            "audio/ogg" -> "ogg"
            "audio/wav", "audio/x-wav" -> "wav"
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            else -> "bin"
        }
    }

    fun writeBackupZip(
        snapshot: BackupSnapshot,
        targetZipFile: File,
        attachmentFileProvider: (String) -> File?,
    ): BackupManifest {
        targetZipFile.parentFile?.mkdirs()
        val filesMap = LinkedHashMap<String, BackupFileChecksum>()
        val writtenAttachmentEntries = ArrayList<BackupAttachmentEntry>(snapshot.attachments.size)
        val sortedAttachments = snapshot.attachments.sortedBy { it.id }
        var entryCount = 0
        var totalUncompressedBytes = 0L

        fun recordEntryLimits(bytesCount: Long) {
            entryCount++
            if (entryCount > BackupConstants.MAX_ARCHIVE_ENTRIES) {
                throw BackupValidationException("备份条目数量超过上限（最多 ${BackupConstants.MAX_ARCHIVE_ENTRIES} 个）")
            }
            if (bytesCount > BackupConstants.MAX_ENTRY_BYTES) {
                throw BackupValidationException("单个备份条目超过大小上限（128 MB），无法安全还原")
            }
            totalUncompressedBytes += bytesCount
            if (totalUncompressedBytes > BackupConstants.MAX_TOTAL_UNCOMPRESSED_BYTES) {
                throw BackupValidationException("备份内容总大小超过可恢复上限（512 MB），请精简超大附件后重试")
            }
        }

        ZipOutputStream(BufferedOutputStream(FileOutputStream(targetZipFile))).use { zos ->
            fun writeBytesEntry(path: String, bytes: ByteArray) {
                val clean = validateArchiveEntryPath(path)
                recordEntryLimits(bytes.size.toLong())
                zos.putNextEntry(ZipEntry(clean))
                zos.write(bytes)
                zos.closeEntry()
                filesMap[clean] = BackupFileChecksum(
                    bytes = bytes.size.toLong(),
                    sha256 = sha256Hex(bytes),
                )
            }

            fun writeTextEntry(path: String, text: String) {
                writeBytesEntry(path, text.toByteArray(StandardCharsets.UTF_8))
            }

            for (note in snapshot.notes.sortedBy { it.id }) {
                writeTextEntry("notes/${note.id}.json", MiniJson.encode(noteToJsonMap(note)))
            }

            val versionsJsonl = snapshot.versions
                .sortedWith(compareBy<BackupVersionRecord> { it.noteId }.thenBy { it.revision })
                .joinToString("\n") { MiniJson.encode(versionToJsonMap(it)) }
            writeTextEntry("metadata/versions.jsonl", versionsJsonl)

            val draftsJsonl = snapshot.drafts
                .sortedBy { it.id }
                .joinToString("\n") { MiniJson.encode(draftToJsonMap(it)) }
            writeTextEntry("metadata/drafts.jsonl", draftsJsonl)

            writeTextEntry("metadata/organization.json", MiniJson.encode(organizationToJsonMap(snapshot)))
            writeTextEntry("metadata/tasks.json", MiniJson.encode(tasksToJsonMap(snapshot)))

            for (att in sortedAttachments) {
                if (!isSafeAttachmentId(att.id)) {
                    throw BackupValidationException("附件 ID 包含非法字符: ${att.id}")
                }
                val sourceFile = attachmentFileProvider(att.id)
                    ?: throw BackupValidationException("附件源文件缺失: ${att.id}")
                val (actualBytes, actualSha) = sha256File(sourceFile)
                if (actualBytes != att.bytes || actualSha != att.sha256) {
                    throw BackupValidationException("附件校验和与本地记录不一致: ${att.originalName}")
                }
                val archivePath = validateArchiveEntryPath(att.archivePath)
                recordEntryLimits(actualBytes)
                zos.putNextEntry(ZipEntry(archivePath))
                val buffer = ByteArray(BUFFER_SIZE)
                FileInputStream(sourceFile).use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        zos.write(buffer, 0, read)
                    }
                }
                zos.closeEntry()
                filesMap[archivePath] = BackupFileChecksum(bytes = actualBytes, sha256 = actualSha)

                val metaMap = linkedMapOf<String, Any?>(
                    "id" to att.id,
                    "archive_path" to archivePath,
                    "original_name" to att.originalName,
                    "mime" to att.mime,
                    "bytes" to actualBytes,
                    "sha256" to actualSha,
                )
                writeTextEntry("attachments/${att.id}.meta.json", MiniJson.encode(metaMap))
                writtenAttachmentEntries.add(att.copy(archivePath = archivePath, bytes = actualBytes, sha256 = actualSha))
            }

            val manifest = BackupManifest(
                backupSchemaVersion = BackupConstants.SUPPORTED_BACKUP_SCHEMA_VERSION,
                dbSchemaVersion = snapshot.dbSchemaVersion,
                exportedAt = snapshot.exportedAt,
                appVersion = snapshot.appVersion,
                counts = BackupEntityCounts(
                    notes = snapshot.notes.size,
                    trashedNotes = snapshot.notes.count { it.deletedAt != null },
                    versions = snapshot.versions.size,
                    drafts = snapshot.drafts.size,
                    tags = snapshot.tags.size,
                    notebooks = snapshot.notebooks.size,
                    noteTags = snapshot.noteTags.size,
                    notebookNotes = snapshot.notebookNotes.size,
                    noteLinks = snapshot.noteLinks.size,
                    savedSearches = snapshot.savedSearches.size,
                    reminders = snapshot.reminders.size,
                    taskOccurrences = snapshot.taskOccurrences.size,
                    attachments = writtenAttachmentEntries.size,
                ),
                attachments = writtenAttachmentEntries,
                files = filesMap,
                exclusions = BackupConstants.DEFAULT_EXCLUSIONS,
            )
            val manifestBytes = MiniJson.encode(manifestToJsonMap(manifest)).toByteArray(StandardCharsets.UTF_8)
            recordEntryLimits(manifestBytes.size.toLong())
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifestBytes)
            zos.closeEntry()
            return manifest
        }
    }

    fun readAndValidateArchive(
        zipFile: File,
        attachmentStagingDir: File? = null,
    ): ParsedBackupArchive {
        if (!zipFile.isFile || zipFile.length() == 0L) {
            throw BackupValidationException("备份文件不存在或为空")
        }
        rejectZipSymlinkEntries(zipFile)

        val entriesByPath = LinkedHashMap<String, ZipEntry>()
        try {
            ZipFile(zipFile).use { zf ->
                val enumEntries = zf.entries()
                while (enumEntries.hasMoreElements()) {
                    val entry = enumEntries.nextElement()
                    if (entriesByPath.size >= BackupConstants.MAX_ARCHIVE_ENTRIES) {
                        throw BackupValidationException("压缩包条目数量超过安全上限")
                    }
                    val cleanPath = validateArchiveEntryPath(entry.name)
                    if (entry.isDirectory || cleanPath.endsWith("/")) continue
                    if (entriesByPath.containsKey(cleanPath)) {
                        throw BackupValidationException("压缩包包含重复路径条目: $cleanPath")
                    }
                    entriesByPath[cleanPath] = entry
                }

                val manifestEntry = entriesByPath["manifest.json"]
                    ?: throw BackupValidationException("备份包缺少 manifest.json")
                val totalCounter = LongCounter()
                val manifestBytes = readEntryChecked(
                    zipFile = zf,
                    entry = manifestEntry,
                    maxEntryBytes = 16L * 1024L * 1024L,
                    totalCounter = totalCounter,
                ).first
                val manifest = parseManifest(String(manifestBytes, StandardCharsets.UTF_8))

                if (manifest.backupSchemaVersion !in 1..BackupConstants.SUPPORTED_BACKUP_SCHEMA_VERSION) {
                    throw BackupValidationException("备份格式版本 (${manifest.backupSchemaVersion}) 高于当前应用支持范围，请升级后再恢复")
                }
                if (manifest.dbSchemaVersion !in 1..BackupConstants.SUPPORTED_DB_SCHEMA_VERSION) {
                    throw BackupValidationException("备份数据库版本 (v${manifest.dbSchemaVersion}) 高于当前版本 (v${BackupConstants.SUPPORTED_DB_SCHEMA_VERSION})，请升级后再恢复")
                }

                // Verify every declared file exists, passes zip-bomb limits, and matches SHA-256 + length.
                for (entryPath in entriesByPath.keys) {
                    if (entryPath != "manifest.json" && !manifest.files.containsKey(entryPath)) {
                        throw BackupValidationException("压缩包包含未登记的异常文件: $entryPath")
                    }
                }
                for ((declaredPath, expectedChecksum) in manifest.files) {
                    val cleanDeclared = validateArchiveEntryPath(declaredPath)
                    val zipEntry = entriesByPath[cleanDeclared]
                        ?: throw BackupValidationException("备份包缺少声明文件: $cleanDeclared")
                    if (zipEntry.isDirectory) {
                        throw BackupValidationException("备份包声明文件实际是目录: $cleanDeclared")
                    }
                    if (expectedChecksum.bytes < 0L) {
                        throw BackupValidationException("备份文件声明大小非法: $cleanDeclared")
                    }
                }

                val notes = ArrayList<BackupNoteRecord>(manifest.counts.notes)
                val notePaths = entriesByPath.keys
                    .filter { it.startsWith("notes/") && it.endsWith(".json") }
                    .sorted()
                for (notePath in notePaths) {
                    val expected = manifest.files[notePath]
                        ?: throw BackupValidationException("笔记条目未列入清单校验: $notePath")
                    val bytes = readAndVerifyEntry(zf, entriesByPath.getValue(notePath), expected, totalCounter)
                    val record = parseNoteRecord(String(bytes, StandardCharsets.UTF_8))
                    // Validate embedded document_json matches schema v1 cleanly.
                    VersionDiff.parseBlocks(record.documentJson)
                    notes.add(record)
                }

                val versionsEntry = entriesByPath["metadata/versions.jsonl"]
                    ?: throw BackupValidationException("备份包缺少 metadata/versions.jsonl")
                val versionsExpected = manifest.files["metadata/versions.jsonl"]
                    ?: throw BackupValidationException("版本记录未列入清单校验")
                val versionsText = String(
                    readAndVerifyEntry(zf, versionsEntry, versionsExpected, totalCounter),
                    StandardCharsets.UTF_8,
                )
                val versions = parseJsonLines(versionsText) { parseVersionRecord(it) }
                versions.forEach { VersionDiff.parseBlocks(it.documentJson) }

                val draftsEntry = entriesByPath["metadata/drafts.jsonl"]
                    ?: throw BackupValidationException("备份包缺少 metadata/drafts.jsonl")
                val draftsExpected = manifest.files["metadata/drafts.jsonl"]
                    ?: throw BackupValidationException("草稿记录未列入清单校验")
                val draftsText = String(
                    readAndVerifyEntry(zf, draftsEntry, draftsExpected, totalCounter),
                    StandardCharsets.UTF_8,
                )
                val drafts = parseJsonLines(draftsText) { parseDraftRecord(it) }
                drafts.forEach { VersionDiff.parseBlocks(it.documentJson) }

                val orgEntry = entriesByPath["metadata/organization.json"]
                    ?: throw BackupValidationException("备份包缺少 metadata/organization.json")
                val orgExpected = manifest.files["metadata/organization.json"]
                    ?: throw BackupValidationException("组织关系未列入清单校验")
                val orgMap = MiniJson.parseObject(
                    String(readAndVerifyEntry(zf, orgEntry, orgExpected, totalCounter), StandardCharsets.UTF_8),
                )
                val (tags, notebooks, noteTags, notebookNotes, noteLinks, savedSearches) = parseOrganization(orgMap)

                val tasksEntry = entriesByPath["metadata/tasks.json"]
                    ?: throw BackupValidationException("备份包缺少 metadata/tasks.json")
                val tasksExpected = manifest.files["metadata/tasks.json"]
                    ?: throw BackupValidationException("待办提醒元数据未列入清单校验")
                val tasksMap = MiniJson.parseObject(
                    String(readAndVerifyEntry(zf, tasksEntry, tasksExpected, totalCounter), StandardCharsets.UTF_8),
                )
                val (reminders, occurrences) = parseTasks(tasksMap)

                // Verify all manifest-declared attachments & metadata, optionally extracting originals to staging.
                attachmentStagingDir?.mkdirs()
                for (att in manifest.attachments) {
                    val cleanArchivePath = validateArchiveEntryPath(att.archivePath)
                    val attEntry = entriesByPath[cleanArchivePath]
                        ?: throw BackupValidationException("备份包缺少附件文件: ${att.originalName}")
                    val expectedFile = manifest.files[cleanArchivePath]
                        ?: throw BackupValidationException("附件未列入清单校验: $cleanArchivePath")
                    if (expectedFile.bytes != att.bytes || expectedFile.sha256 != att.sha256) {
                        throw BackupValidationException("附件清单校验值不一致: ${att.id}")
                    }
                    val metaPath = "attachments/${att.id}.meta.json"
                    val metaEntry = entriesByPath[metaPath]
                        ?: throw BackupValidationException("备份包缺少附件元数据: ${att.id}")
                    val metaExpected = manifest.files[metaPath]
                        ?: throw BackupValidationException("附件元数据未列入清单校验: $metaPath")
                    readAndVerifyEntry(zf, metaEntry, metaExpected, totalCounter)

                    if (attachmentStagingDir != null) {
                        val destFolder = File(attachmentStagingDir, att.id).apply { mkdirs() }
                        val destOriginal = File(destFolder, "original")
                        val (actualBytes, actualSha) = streamEntryToFileChecked(zf, attEntry, destOriginal, totalCounter)
                        if (actualBytes != att.bytes || !actualSha.equals(att.sha256, ignoreCase = true)) {
                            throw BackupValidationException("附件哈希校验失败: ${att.originalName}")
                        }
                    } else {
                        val (actualBytes, actualSha) = digestEntryChecked(zf, attEntry, totalCounter)
                        if (actualBytes != att.bytes || !actualSha.equals(att.sha256, ignoreCase = true)) {
                            throw BackupValidationException("附件哈希校验失败: ${att.originalName}")
                        }
                    }
                }

                // Verify any extra declared entries not covered above are still CRC/SHA verified.
                val checkedPaths = buildSet {
                    addAll(notePaths)
                    add("metadata/versions.jsonl")
                    add("metadata/drafts.jsonl")
                    add("metadata/organization.json")
                    add("metadata/tasks.json")
                    manifest.attachments.forEach {
                        add(validateArchiveEntryPath(it.archivePath))
                        add("attachments/${it.id}.meta.json")
                    }
                }
                for ((declaredPath, expected) in manifest.files) {
                    val clean = validateArchiveEntryPath(declaredPath)
                    if (clean !in checkedPaths) {
                        val entry = entriesByPath.getValue(clean)
                        val (actualBytes, actualSha) = digestEntryChecked(zf, entry, totalCounter)
                        if (actualBytes != expected.bytes || !actualSha.equals(expected.sha256, ignoreCase = true)) {
                            throw BackupValidationException("备份文件校验失败: $clean")
                        }
                    }
                }

                verifyCounts(
                    manifest = manifest,
                    notes = notes,
                    versions = versions,
                    drafts = drafts,
                    tags = tags,
                    notebooks = notebooks,
                    noteTags = noteTags,
                    notebookNotes = notebookNotes,
                    noteLinks = noteLinks,
                    savedSearches = savedSearches,
                    reminders = reminders,
                    occurrences = occurrences,
                )

                return ParsedBackupArchive(
                    manifest = manifest,
                    snapshot = BackupSnapshot(
                        exportedAt = manifest.exportedAt,
                        appVersion = manifest.appVersion,
                        dbSchemaVersion = manifest.dbSchemaVersion,
                        notes = notes,
                        versions = versions,
                        drafts = drafts,
                        tags = tags,
                        notebooks = notebooks,
                        noteTags = noteTags,
                        notebookNotes = notebookNotes,
                        noteLinks = noteLinks,
                        savedSearches = savedSearches,
                        reminders = reminders,
                        taskOccurrences = occurrences,
                        attachments = manifest.attachments,
                    ),
                )
            }
        } catch (err: BackupValidationException) {
            throw err
        } catch (err: ZipException) {
            throw BackupValidationException("备份压缩包已损坏或格式无效", err)
        } catch (err: IOException) {
            throw BackupValidationException("无法读取备份文件", err)
        }
    }

    fun writeMarkdownZip(
        snapshot: BackupSnapshot,
        targetZipFile: File,
        includeArchived: Boolean = true,
        includeTrashed: Boolean = false,
        attachmentFileProvider: (String) -> File?,
    ): ExportOutcome {
        targetZipFile.parentFile?.mkdirs()
        val exportNotes = snapshot.notes
            .filter { note ->
                (includeTrashed || note.deletedAt == null) &&
                    (includeArchived || note.archivedAt == null)
            }
            .sortedByDescending { it.updatedAt }

        val warnings = LinkedHashSet<String>(BackupUiState.DEFAULT_MARKDOWN_WARNINGS)
        val tagsById = snapshot.tags.associateBy { it.id }
        val tagPathsById = tagsById.mapValues { (_, tag) -> resolveTagPath(tagsById, tag) }
        val tagsByNote = snapshot.noteTags.groupBy({ it.noteId }, { tagPathsById[it.tagId] })
        val notebooksById = snapshot.notebooks.associateBy { it.id }
        val notebookByNote = snapshot.notebookNotes.associate { it.noteId to notebooksById[it.notebookId]?.name }
        val titleByNoteId = snapshot.notes.associate { note ->
            val fallbackTitle = VersionDiff.summarizeDocument(note.documentJson, note.title).first
            note.id to (note.title?.takeIf { it.isNotBlank() } ?: fallbackTitle)
        }
        val attachmentsById = snapshot.attachments.associateBy { it.id }
        val copiedAttachments = HashSet<String>()
        val usedFileNames = HashSet<String>()

        ZipOutputStream(BufferedOutputStream(FileOutputStream(targetZipFile))).use { zos ->
            for (note in exportNotes) {
                val baseName = sanitizeFileStem(titleByNoteId[note.id] ?: "note")
                var candidate = "notes/$baseName-${note.id.take(8)}.md"
                var suffix = 2
                while (!usedFileNames.add(candidate)) {
                    candidate = "notes/$baseName-${note.id.take(8)}-$suffix.md"
                    suffix++
                }

                val blocks = runCatching { VersionDiff.parseBlocks(note.documentJson) }.getOrDefault(emptyList())
                val noteTags = tagsByNote[note.id].orEmpty().filterNotNull().sorted()
                val notebookName = notebookByNote[note.id]
                val markdown = buildString {
                    appendLine("---")
                    appendLine("id: ${yamlScalar(note.id)}")
                    titleByNoteId[note.id]?.let { appendLine("title: ${yamlScalar(it)}") }
                    appendLine("created_at: ${yamlScalar(note.createdAt)}")
                    appendLine("updated_at: ${yamlScalar(note.updatedAt)}")
                    appendLine("pinned: ${note.pinned}")
                    note.archivedAt?.let { appendLine("archived_at: ${yamlScalar(it)}") }
                    note.deletedAt?.let { appendLine("deleted_at: ${yamlScalar(it)}") }
                    appendLine("source_type: ${yamlScalar(note.sourceType)}")
                    note.sourceUrl?.let { appendLine("source_url: ${yamlScalar(it)}") }
                    notebookName?.let { appendLine("notebook: ${yamlScalar(it)}") }
                    if (noteTags.isNotEmpty()) {
                        appendLine("tags:")
                        noteTags.forEach { tag -> appendLine("  - ${yamlScalar(tag)}") }
                    }
                    appendLine("local_revision: ${note.revision}")
                    appendLine("---")
                    appendLine()

                    if (!note.title.isNullOrBlank()) {
                        appendLine("# ${note.title.trim()}")
                        appendLine()
                    }

                    for (block in blocks) {
                        when (block.type) {
                            "checkbox" -> {
                                val check = if (block.checked) "x" else " "
                                val due = block.dueDate?.let { " （截止：$it）" }.orEmpty()
                                appendLine("- [$check] ${block.text}$due")
                            }
                            "image", "audio", "video" -> {
                                val attId = block.attachmentId
                                val att = attId?.let(attachmentsById::get)
                                val sourceFile = attId?.let(attachmentFileProvider)
                                if (att != null && sourceFile != null && sourceFile.isFile) {
                                    val ext = safeAttachmentExtension(att.mime, att.originalName)
                                    val safeBase = sanitizeFileStem(att.originalName.substringBeforeLast('.', att.originalName))
                                    val zipAttPath = "attachments/${att.id}-$safeBase.$ext"
                                    val relPath = "../$zipAttPath"
                                    if (copiedAttachments.add(zipAttPath)) {
                                        zos.putNextEntry(ZipEntry(zipAttPath))
                                        FileInputStream(sourceFile).use { it.copyTo(zos, BUFFER_SIZE) }
                                        zos.closeEntry()
                                    }
                                    if (block.type == "image") {
                                        appendLine("![${att.originalName}]($relPath)")
                                    } else {
                                        appendLine("[${att.originalName}]($relPath)")
                                    }
                                } else {
                                    warnings.add("部分附件文件在本机缺失，已在 Markdown 中保留占位提示（${attId ?: "unknown"}）。")
                                    appendLine("> [缺失附件: ${attId ?: "unknown"}]")
                                }
                            }
                            "reference" -> {
                                val targetTitle = block.referenceTarget?.let(titleByNoteId::get) ?: "来源笔记"
                                val label = block.referenceLabel?.takeIf { it.isNotBlank() } ?: targetTitle
                                appendLine("> 引用 [[$targetTitle]]：$label")
                                if (block.referenceStart != null && block.referenceEnd != null) {
                                    warnings.add("部分引用包含 UTF-16 字符选区范围，Markdown 导出已降级为引用文本。")
                                }
                            }
                            else -> {
                                if (block.text.isNotBlank()) {
                                    appendLine(block.text)
                                    appendLine()
                                }
                            }
                        }
                    }
                }

                zos.putNextEntry(ZipEntry(candidate))
                zos.write(markdown.toByteArray(StandardCharsets.UTF_8))
                zos.closeEntry()
            }

            val readmeText = buildString {
                appendLine("浮记 Markdown 导出说明")
                appendLine("导出时间：${snapshot.exportedAt}")
                appendLine("导出笔记数：${exportNotes.size}")
                appendLine("附件文件数：${copiedAttachments.size}")
                appendLine()
                appendLine("降级与限制说明：")
                warnings.forEach { appendLine("- $it") }
            }
            zos.putNextEntry(ZipEntry("README-导出说明.txt"))
            zos.write(readmeText.toByteArray(StandardCharsets.UTF_8))
            zos.closeEntry()
        }

        return ExportOutcome(
            ok = true,
            message = "已导出 ${exportNotes.size} 条笔记和 ${copiedAttachments.size} 个附件的 Markdown 压缩包",
            itemCount = exportNotes.size,
            warnings = warnings.toList(),
        )
    }

    private fun verifyCounts(
        manifest: BackupManifest,
        notes: List<BackupNoteRecord>,
        versions: List<BackupVersionRecord>,
        drafts: List<BackupDraftRecord>,
        tags: List<BackupTagRecord>,
        notebooks: List<BackupNotebookRecord>,
        noteTags: List<BackupNoteTagRecord>,
        notebookNotes: List<BackupNotebookNoteRecord>,
        noteLinks: List<BackupNoteLinkRecord>,
        savedSearches: List<BackupSavedSearchRecord>,
        reminders: List<BackupReminderRecord>,
        occurrences: List<BackupTaskOccurrenceRecord>,
    ) {
        val c = manifest.counts
        if (c.notes != notes.size ||
            c.versions != versions.size ||
            c.drafts != drafts.size ||
            c.tags != tags.size ||
            c.notebooks != notebooks.size ||
            c.noteTags != noteTags.size ||
            c.notebookNotes != notebookNotes.size ||
            c.noteLinks != noteLinks.size ||
            c.savedSearches != savedSearches.size ||
            c.reminders != reminders.size ||
            c.taskOccurrences != occurrences.size ||
            c.attachments != manifest.attachments.size
        ) {
            throw BackupValidationException("备份清单实体数量与实际文件内容不一致")
        }
    }

    private fun readAndVerifyEntry(
        zipFile: ZipFile,
        entry: ZipEntry,
        expected: BackupFileChecksum,
        totalCounter: LongCounter,
    ): ByteArray {
        val (bytes, sha256) = readEntryChecked(zipFile, entry, BackupConstants.MAX_ENTRY_BYTES, totalCounter)
        if (bytes.size.toLong() != expected.bytes || !sha256.equals(expected.sha256, ignoreCase = true)) {
            throw BackupValidationException("备份条目校验失败: ${entry.name}")
        }
        return bytes
    }

    private fun readEntryChecked(
        zipFile: ZipFile,
        entry: ZipEntry,
        maxEntryBytes: Long,
        totalCounter: LongCounter,
    ): Pair<ByteArray, String> {
        val out = ByteArrayOutputStream()
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var entryBytes = 0L
        zipFile.getInputStream(entry).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                entryBytes += read
                totalCounter.value += read
                checkBombs(entry, entryBytes, maxEntryBytes, totalCounter.value)
                out.write(buffer, 0, read)
                digest.update(buffer, 0, read)
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        return out.toByteArray() to sha
    }

    private fun digestEntryChecked(
        zipFile: ZipFile,
        entry: ZipEntry,
        totalCounter: LongCounter,
    ): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var entryBytes = 0L
        zipFile.getInputStream(entry).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                entryBytes += read
                totalCounter.value += read
                checkBombs(entry, entryBytes, BackupConstants.MAX_ENTRY_BYTES, totalCounter.value)
                digest.update(buffer, 0, read)
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        return entryBytes to sha
    }

    private fun streamEntryToFileChecked(
        zipFile: ZipFile,
        entry: ZipEntry,
        dest: File,
        totalCounter: LongCounter,
    ): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var entryBytes = 0L
        zipFile.getInputStream(entry).use { input ->
            FileOutputStream(dest).use { output ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    entryBytes += read
                    totalCounter.value += read
                    checkBombs(entry, entryBytes, BackupConstants.MAX_ENTRY_BYTES, totalCounter.value)
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                }
                output.fd.sync()
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        return entryBytes to sha
    }

    private fun checkBombs(entry: ZipEntry, entryBytes: Long, maxEntryBytes: Long, totalBytes: Long) {
        if (entryBytes > maxEntryBytes) {
            throw BackupValidationException("备份条目体积超出上限: ${entry.name}")
        }
        if (totalBytes > BackupConstants.MAX_TOTAL_UNCOMPRESSED_BYTES) {
            throw BackupValidationException("备份解压总体积超出安全上限（疑似膨胀包）")
        }
        val compressed = entry.compressedSize
        if (compressed > 0L && entryBytes >= BackupConstants.RATIO_CHECK_MIN_BYTES) {
            if (entryBytes / compressed > BackupConstants.MAX_COMPRESSION_RATIO) {
                throw BackupValidationException("备份条目压缩比异常（疑似膨胀包）: ${entry.name}")
            }
        }
    }

    /**
     * Scans ZIP central directory records (when present within standard bounds) and rejects Unix symlink entries
     * (file mode 0xA000 in external attributes).
     */
    private fun rejectZipSymlinkEntries(zipFile: File) {
        val length = zipFile.length()
        if (length < 22L) throw BackupValidationException("备份压缩包不完整")
        val tailLen = minOf(length, 65_557L).toInt()
        val tail = ByteArray(tailLen)
        java.io.RandomAccessFile(zipFile, "r").use { raf ->
            raf.seek(length - tailLen)
            raf.readFully(tail)
            var eocdOffset = -1
            for (i in tailLen - 22 downTo 0) {
                if (tail[i] == 0x50.toByte() &&
                    tail[i + 1] == 0x4b.toByte() &&
                    tail[i + 2] == 0x05.toByte() &&
                    tail[i + 3] == 0x06.toByte()
                ) {
                    eocdOffset = i
                    break
                }
            }
            if (eocdOffset < 0) return
            val cdSize = readLeInt(tail, eocdOffset + 12).toLong() and 0xFFFF_FFFFL
            val cdOffset = readLeInt(tail, eocdOffset + 16).toLong() and 0xFFFF_FFFFL
            if (cdOffset + cdSize > length || cdSize > 32L * 1024L * 1024L) return
            raf.seek(cdOffset)
            val cd = ByteArray(cdSize.toInt())
            raf.readFully(cd)
            var pos = 0
            while (pos + 46 <= cd.size) {
                if (cd[pos] != 0x50.toByte() ||
                    cd[pos + 1] != 0x4b.toByte() ||
                    cd[pos + 2] != 0x01.toByte() ||
                    cd[pos + 3] != 0x02.toByte()
                ) {
                    break
                }
                val externalAttr = readLeInt(cd, pos + 38)
                val unixMode = (externalAttr ushr 16) and 0xF000
                if (unixMode == 0xA000) {
                    throw BackupValidationException("备份包禁止包含符号链接条目")
                }
                val nameLen = readLeShort(cd, pos + 28)
                val extraLen = readLeShort(cd, pos + 30)
                val commentLen = readLeShort(cd, pos + 32)
                pos += 46 + nameLen + extraLen + commentLen
            }
        }
    }

    private fun readLeShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readLeInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private class LongCounter(var value: Long = 0L)

    private fun <T> parseJsonLines(text: String, mapper: (Map<String, Any?>) -> T): List<T> {
        if (text.isBlank()) return emptyList()
        return text.lineSequence()
            .filter { it.isNotBlank() }
            .map { line -> mapper(MiniJson.parseObject(line)) }
            .toList()
    }

    private fun resolveTagPath(byId: Map<String, BackupTagRecord>, tag: BackupTagRecord): String {
        val seen = HashSet<String>()
        val parts = ArrayList<String>()
        var current: BackupTagRecord? = tag
        while (current != null && seen.add(current.id)) {
            parts.add(current.name)
            current = if (current.parentId.isBlank()) null else byId[current.parentId]
        }
        return parts.asReversed().joinToString("/")
    }

    private fun sanitizeFileStem(raw: String): String {
        val cleaned = CONTROL_CHARS.replace(raw, " ")
            .replace(Regex("[/\\\\:*?\"<>|\\s]+"), "-")
            .trim('-')
            .take(40)
        return cleaned.ifBlank { "note" }
    }

    private fun yamlScalar(value: String): String =
        "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""

    private fun noteToJsonMap(n: BackupNoteRecord): Map<String, Any?> = linkedMapOf(
        "id" to n.id,
        "title" to n.title,
        "document_json" to n.documentJson,
        "search_text" to n.searchText,
        "author_type" to n.authorType,
        "source_type" to n.sourceType,
        "source_url" to n.sourceUrl,
        "ai_policy" to n.aiPolicy,
        "created_at" to n.createdAt,
        "updated_at" to n.updatedAt,
        "local_revision" to n.revision,
        "server_revision" to n.serverRevision,
        "content_hash" to n.contentHash,
        "archived_at" to n.archivedAt,
        "deleted_at" to n.deletedAt,
        "pinned" to n.pinned,
        "last_draft_id" to n.lastDraftId,
        "committed_seq" to n.committedSeq,
        "media_mask" to n.mediaMask,
    )

    private fun parseNoteRecord(json: String): BackupNoteRecord {
        val m = MiniJson.parseObject(json)
        return BackupNoteRecord(
            id = m.reqString("id"),
            title = m.optString("title"),
            documentJson = m.reqString("document_json"),
            searchText = m.reqString("search_text"),
            authorType = m.optString("author_type") ?: "user",
            sourceType = m.reqString("source_type"),
            sourceUrl = m.optString("source_url"),
            aiPolicy = m.optString("ai_policy") ?: "inherit",
            createdAt = m.reqString("created_at"),
            updatedAt = m.reqString("updated_at"),
            revision = m.reqLong("local_revision"),
            serverRevision = m.optLong("server_revision"),
            contentHash = m.reqString("content_hash"),
            archivedAt = m.optString("archived_at"),
            deletedAt = m.optString("deleted_at"),
            pinned = m.reqBool("pinned"),
            lastDraftId = m.reqString("last_draft_id"),
            committedSeq = m.reqLong("committed_seq"),
            mediaMask = m.optLong("media_mask")?.toInt() ?: 0,
        )
    }

    private fun versionToJsonMap(v: BackupVersionRecord): Map<String, Any?> = linkedMapOf(
        "note_id" to v.noteId,
        "revision" to v.revision,
        "document_json" to v.documentJson,
        "origin" to v.origin,
        "created_at" to v.createdAt,
        "title" to v.title,
        "attachment_refs" to v.attachmentRefs,
    )

    private fun parseVersionRecord(m: Map<String, Any?>): BackupVersionRecord = BackupVersionRecord(
        noteId = m.reqString("note_id"),
        revision = m.reqLong("revision"),
        documentJson = m.reqString("document_json"),
        origin = m.reqString("origin"),
        createdAt = m.reqString("created_at"),
        title = m.optString("title"),
        attachmentRefs = m.optString("attachment_refs") ?: "[]",
    )

    private fun draftToJsonMap(d: BackupDraftRecord): Map<String, Any?> = linkedMapOf(
        "id" to d.id,
        "slot" to d.slot,
        "note_id" to d.noteId,
        "base_revision" to d.baseRevision,
        "document_json" to d.documentJson,
        "edit_seq" to d.editSeq,
        "updated_at" to d.updatedAt,
        "source_type" to d.sourceType,
        "source_url" to d.sourceUrl,
    )

    private fun parseDraftRecord(m: Map<String, Any?>): BackupDraftRecord = BackupDraftRecord(
        id = m.reqString("id"),
        slot = m.reqString("slot"),
        noteId = m.reqString("note_id"),
        baseRevision = m.reqLong("base_revision"),
        documentJson = m.reqString("document_json"),
        editSeq = m.reqLong("edit_seq"),
        updatedAt = m.reqString("updated_at"),
        sourceType = m.optString("source_type") ?: "manual",
        sourceUrl = m.optString("source_url"),
    )

    private fun organizationToJsonMap(s: BackupSnapshot): Map<String, Any?> = linkedMapOf(
        "tags" to s.tags.sortedBy { it.id }.map {
            linkedMapOf("id" to it.id, "parent_id" to it.parentId, "name" to it.name, "name_key" to it.nameKey)
        },
        "notebooks" to s.notebooks.sortedBy { it.id }.map {
            linkedMapOf("id" to it.id, "name" to it.name, "name_key" to it.nameKey)
        },
        "note_tags" to s.noteTags.sortedWith(compareBy({ it.noteId }, { it.tagId })).map {
            linkedMapOf("note_id" to it.noteId, "tag_id" to it.tagId)
        },
        "notebook_notes" to s.notebookNotes.sortedBy { it.noteId }.map {
            linkedMapOf("note_id" to it.noteId, "notebook_id" to it.notebookId)
        },
        "note_links" to s.noteLinks.sortedWith(compareBy({ it.sourceId }, { it.blockId })).map {
            linkedMapOf(
                "source_id" to it.sourceId,
                "block_id" to it.blockId,
                "target_id" to it.targetId,
                "target_revision" to it.targetRevision,
            )
        },
        "saved_searches" to s.savedSearches.sortedBy { it.id }.map {
            linkedMapOf("id" to it.id, "name" to it.name, "keyword" to it.keyword, "scope_json" to it.scopeJson)
        },
    )

    private data class ParsedOrganization(
        val tags: List<BackupTagRecord>,
        val notebooks: List<BackupNotebookRecord>,
        val noteTags: List<BackupNoteTagRecord>,
        val notebookNotes: List<BackupNotebookNoteRecord>,
        val noteLinks: List<BackupNoteLinkRecord>,
        val savedSearches: List<BackupSavedSearchRecord>,
    )

    private fun parseOrganization(m: Map<String, Any?>): ParsedOrganization {
        @Suppress("UNCHECKED_CAST")
        fun objList(key: String): List<Map<String, Any?>> =
            m.reqArray(key).map { it as? Map<String, Any?> ?: throw BackupValidationException("组织字段 $key 格式非法") }

        val tags = objList("tags").map {
            BackupTagRecord(
                id = it.reqString("id"),
                parentId = it.optString("parent_id").orEmpty(),
                name = it.reqString("name"),
                nameKey = it.reqString("name_key"),
            )
        }
        val notebooks = objList("notebooks").map {
            BackupNotebookRecord(
                id = it.reqString("id"),
                name = it.reqString("name"),
                nameKey = it.reqString("name_key"),
            )
        }
        val noteTags = objList("note_tags").map {
            BackupNoteTagRecord(noteId = it.reqString("note_id"), tagId = it.reqString("tag_id"))
        }
        val notebookNotes = objList("notebook_notes").map {
            BackupNotebookNoteRecord(noteId = it.reqString("note_id"), notebookId = it.reqString("notebook_id"))
        }
        val noteLinks = objList("note_links").map {
            BackupNoteLinkRecord(
                sourceId = it.reqString("source_id"),
                blockId = it.reqString("block_id"),
                targetId = it.reqString("target_id"),
                targetRevision = it.optLong("target_revision"),
            )
        }
        val savedSearches = objList("saved_searches").map {
            BackupSavedSearchRecord(
                id = it.reqString("id"),
                name = it.reqString("name"),
                keyword = it.optString("keyword").orEmpty(),
                scopeJson = it.reqString("scope_json"),
            )
        }
        return ParsedOrganization(tags, notebooks, noteTags, notebookNotes, noteLinks, savedSearches)
    }

    private fun tasksToJsonMap(s: BackupSnapshot): Map<String, Any?> = linkedMapOf(
        "reminders" to s.reminders.sortedBy { it.id }.map {
            linkedMapOf(
                "id" to it.id,
                "note_id" to it.noteId,
                "block_id" to it.blockId,
                "occurrence_id" to it.occurrenceId,
                "zone_id" to it.zoneId,
                "local_date" to it.localDate,
                "local_time" to it.localTime,
                "trigger_instant_ms" to it.triggerInstantMs,
                "policy" to it.policy,
                "status" to it.status,
                "recurrence" to it.recurrence,
                "snoozed_until_ms" to it.snoozedUntilMs,
                "missed_summarized" to it.missedSummarized,
                "revision" to it.revision,
                "updated_at" to it.updatedAt,
            )
        },
        "task_occurrences" to s.taskOccurrences.sortedBy { it.id }.map {
            linkedMapOf(
                "id" to it.id,
                "note_id" to it.noteId,
                "block_id" to it.blockId,
                "reminder_id" to it.reminderId,
                "scheduled_date" to it.scheduledDate,
                "title_snapshot" to it.titleSnapshot,
                "recurrence" to it.recurrence,
                "completed_at" to it.completedAt,
            )
        },
    )

    private fun parseTasks(m: Map<String, Any?>): Pair<List<BackupReminderRecord>, List<BackupTaskOccurrenceRecord>> {
        @Suppress("UNCHECKED_CAST")
        fun objList(key: String): List<Map<String, Any?>> =
            m.reqArray(key).map { it as? Map<String, Any?> ?: throw BackupValidationException("任务字段 $key 格式非法") }

        val reminders = objList("reminders").map {
            BackupReminderRecord(
                id = it.reqString("id"),
                noteId = it.reqString("note_id"),
                blockId = it.reqString("block_id"),
                occurrenceId = it.optString("occurrence_id"),
                zoneId = it.reqString("zone_id"),
                localDate = it.reqString("local_date"),
                localTime = it.reqString("local_time"),
                triggerInstantMs = it.optLong("trigger_instant_ms"),
                policy = it.reqString("policy"),
                status = it.reqString("status"),
                recurrence = it.reqString("recurrence"),
                snoozedUntilMs = it.optLong("snoozed_until_ms"),
                missedSummarized = it.reqBool("missed_summarized"),
                revision = it.reqLong("revision"),
                updatedAt = it.reqString("updated_at"),
            )
        }
        val occurrences = objList("task_occurrences").map {
            BackupTaskOccurrenceRecord(
                id = it.reqString("id"),
                noteId = it.reqString("note_id"),
                blockId = it.reqString("block_id"),
                reminderId = it.optString("reminder_id"),
                scheduledDate = it.optString("scheduled_date"),
                titleSnapshot = it.reqString("title_snapshot"),
                recurrence = it.reqString("recurrence"),
                completedAt = it.reqString("completed_at"),
            )
        }
        return reminders to occurrences
    }

    private fun manifestToJsonMap(m: BackupManifest): Map<String, Any?> = linkedMapOf(
        "backup_schema_version" to m.backupSchemaVersion,
        "db_schema_version" to m.dbSchemaVersion,
        "exported_at" to m.exportedAt,
        "app_version" to m.appVersion,
        "counts" to linkedMapOf(
            "notes" to m.counts.notes,
            "trashed_notes" to m.counts.trashedNotes,
            "versions" to m.counts.versions,
            "drafts" to m.counts.drafts,
            "tags" to m.counts.tags,
            "notebooks" to m.counts.notebooks,
            "note_tags" to m.counts.noteTags,
            "notebook_notes" to m.counts.notebookNotes,
            "note_links" to m.counts.noteLinks,
            "saved_searches" to m.counts.savedSearches,
            "reminders" to m.counts.reminders,
            "task_occurrences" to m.counts.taskOccurrences,
            "attachments" to m.counts.attachments,
        ),
        "attachments" to m.attachments.map {
            linkedMapOf(
                "id" to it.id,
                "archive_path" to it.archivePath,
                "original_name" to it.originalName,
                "mime" to it.mime,
                "bytes" to it.bytes,
                "sha256" to it.sha256,
            )
        },
        "files" to m.files.entries.associate { (path, cs) ->
            path to linkedMapOf("bytes" to cs.bytes, "sha256" to cs.sha256)
        },
        "exclusions" to m.exclusions,
    )

    private fun parseManifest(json: String): BackupManifest {
        val m = MiniJson.parseObject(json)
        val countsObj = m.reqObject("counts")
        val counts = BackupEntityCounts(
            notes = countsObj.reqInt("notes"),
            trashedNotes = countsObj.reqInt("trashed_notes"),
            versions = countsObj.reqInt("versions"),
            drafts = countsObj.reqInt("drafts"),
            tags = countsObj.reqInt("tags"),
            notebooks = countsObj.reqInt("notebooks"),
            noteTags = countsObj.reqInt("note_tags"),
            notebookNotes = countsObj.reqInt("notebook_notes"),
            noteLinks = countsObj.reqInt("note_links"),
            savedSearches = countsObj.reqInt("saved_searches"),
            reminders = countsObj.reqInt("reminders"),
            taskOccurrences = countsObj.reqInt("task_occurrences"),
            attachments = countsObj.reqInt("attachments"),
        )
        val attachments = m.reqArray("attachments").map { entry ->
            @Suppress("UNCHECKED_CAST")
            val obj = entry as? Map<String, Any?>
                ?: throw BackupValidationException("清单附件记录格式非法")
            val id = obj.reqString("id")
            if (!isSafeAttachmentId(id)) {
                throw BackupValidationException("附件 ID 包含非法字符: $id")
            }
            BackupAttachmentEntry(
                id = id,
                archivePath = validateArchiveEntryPath(obj.reqString("archive_path")),
                originalName = obj.reqString("original_name"),
                mime = obj.reqString("mime"),
                bytes = obj.reqLong("bytes"),
                sha256 = obj.reqString("sha256"),
            )
        }
        val filesRaw = m.reqObject("files")
        val files = LinkedHashMap<String, BackupFileChecksum>(filesRaw.size)
        for ((rawPath, rawVal) in filesRaw) {
            val clean = validateArchiveEntryPath(rawPath)
            @Suppress("UNCHECKED_CAST")
            val obj = rawVal as? Map<String, Any?>
                ?: throw BackupValidationException("清单文件校验条目格式非法: $clean")
            files[clean] = BackupFileChecksum(bytes = obj.reqLong("bytes"), sha256 = obj.reqString("sha256"))
        }
        val exclusions = m.reqArray("exclusions").mapNotNull { it as? String }
        return BackupManifest(
            backupSchemaVersion = m.reqInt("backup_schema_version"),
            dbSchemaVersion = m.reqInt("db_schema_version"),
            exportedAt = m.reqString("exported_at"),
            appVersion = m.optString("app_version") ?: "unknown",
            counts = counts,
            attachments = attachments,
            files = files,
            exclusions = exclusions,
        )
    }
}
