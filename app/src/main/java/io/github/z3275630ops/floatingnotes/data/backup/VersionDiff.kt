package io.github.z3275630ops.floatingnotes.data.backup

internal data class ParsedDocBlock(
    val id: String,
    val type: String,
    val text: String,
    val checked: Boolean,
    val dueDate: String?,
    val attachmentId: String?,
    val referenceTarget: String?,
    val referenceTargetBlockId: String?,
    val referenceStart: Int?,
    val referenceEnd: Int?,
    val referenceLabel: String?,
    val raw: Map<String, Any?>,
)

internal object VersionDiff {
    private val MEDIA_TYPES = setOf("image", "audio", "video")
    private const val MAX_LCS_LINES = 350

    fun parseBlocks(documentJson: String): List<ParsedDocBlock> {
        val root = MiniJson.parseObject(documentJson)
        val schemaVersion = root.reqInt("document_schema_version")
        if (schemaVersion != 1) {
            throw BackupValidationException("不支持的文档版本: $schemaVersion")
        }
        val blocksArray = root.reqArray("blocks")
        if (blocksArray.isEmpty()) {
            throw BackupValidationException("文档块列表为空")
        }
        return blocksArray.map { entry ->
            @Suppress("UNCHECKED_CAST")
            val obj = entry as? Map<String, Any?>
                ?: throw BackupValidationException("文档块必须是 JSON 对象")
            val id = obj.reqString("id")
            val type = obj.reqString("type")
            val label = obj.optString("label")
            val text = if (type == "reference") label.orEmpty() else obj.optString("text").orEmpty()
            val checked = (obj["checked"] as? Boolean) ?: false
            val attachmentId = if (type in MEDIA_TYPES) obj.optString("attachment_id")?.takeIf { it.isNotBlank() } else null
            val refTarget = if (type == "reference") obj.optString("target_note_id")?.takeIf { it.isNotBlank() } else null
            val refBlockId = if (type == "reference") obj.optString("target_block_id")?.takeIf { it.isNotBlank() } else null
            val refStart = if (type == "reference") obj.optLong("start")?.toInt()?.takeIf { it >= 0 } else null
            val refEnd = if (type == "reference") obj.optLong("end")?.toInt()?.takeIf { it >= 0 } else null
            ParsedDocBlock(
                id = id,
                type = type,
                text = text,
                checked = checked,
                dueDate = if (type == "checkbox") obj.optString("due_date")?.takeIf { it.isNotBlank() } else null,
                attachmentId = attachmentId,
                referenceTarget = refTarget,
                referenceTargetBlockId = refBlockId,
                referenceStart = refStart,
                referenceEnd = refEnd,
                referenceLabel = label,
                raw = obj,
            )
        }
    }

    fun extractAttachmentIds(documentJson: String): List<String> = runCatching {
        parseBlocks(documentJson).mapNotNull { it.attachmentId }.distinct()
    }.getOrDefault(emptyList())

    fun encodeAttachmentRefsJson(ids: Collection<String>): String =
        MiniJson.encode(ids.distinct())

    fun decodeAttachmentRefs(storedJson: String?, fallbackDocumentJson: String): List<String> {
        val parsed = storedJson?.takeIf { it.isNotBlank() && it != "[]" }?.let { raw ->
            runCatching {
                MiniJson.parseArray(raw).mapNotNull { it as? String }.filter { it.isNotBlank() }.distinct()
            }.getOrNull()
        }
        return if (!parsed.isNullOrEmpty()) parsed else extractAttachmentIds(fallbackDocumentJson)
    }

    /**
     * Rewrites embedded reference targets, attachment IDs, and task reminder/occurrence IDs
     * inside schema-v1 document JSON while preserving unknown fields/marks.
     */
    fun rewriteDocumentIds(
        documentJson: String,
        noteIdMap: Map<String, String>,
        attachmentIdMap: Map<String, String>,
        reminderIdMap: Map<String, String> = emptyMap(),
        occurrenceIdMap: Map<String, String> = emptyMap(),
    ): Pair<String, Boolean> {
        if (noteIdMap.isEmpty() && attachmentIdMap.isEmpty() && reminderIdMap.isEmpty() && occurrenceIdMap.isEmpty()) {
            return documentJson to false
        }
        val root = MiniJson.parseObject(documentJson)
        val blocks = root.reqArray("blocks")
        var changed = false
        val rewrittenBlocks = ArrayList<Any?>(blocks.size)
        for (entry in blocks) {
            @Suppress("UNCHECKED_CAST")
            val obj = entry as? Map<String, Any?>
            if (obj == null) {
                rewrittenBlocks.add(entry)
                continue
            }
            val type = obj["type"] as? String
            var updated: LinkedHashMap<String, Any?>? = null
            if (type == "reference") {
                val targetNoteId = obj["target_note_id"] as? String
                val mappedNoteId = targetNoteId?.let { noteIdMap[it] }
                if (mappedNoteId != null && mappedNoteId != targetNoteId) {
                    updated = LinkedHashMap(obj)
                    updated["target_note_id"] = mappedNoteId
                    changed = true
                }
            } else if (type in MEDIA_TYPES) {
                val attachmentId = obj["attachment_id"] as? String
                val mappedAttachmentId = attachmentId?.let { attachmentIdMap[it] }
                if (mappedAttachmentId != null && mappedAttachmentId != attachmentId) {
                    updated = LinkedHashMap(obj)
                    updated["attachment_id"] = mappedAttachmentId
                    changed = true
                }
            } else if (type == "checkbox") {
                val remId = obj["reminder_id"] as? String
                val mappedRemId = remId?.let { reminderIdMap[it] }
                if (mappedRemId != null && mappedRemId != remId) {
                    updated = (updated ?: LinkedHashMap(obj)).apply { put("reminder_id", mappedRemId) }
                    changed = true
                }
                val occId = obj["occurrence_id"] as? String
                val mappedOccId = occId?.let { occurrenceIdMap[it] }
                if (mappedOccId != null && mappedOccId != occId) {
                    updated = (updated ?: LinkedHashMap(obj)).apply { put("occurrence_id", mappedOccId) }
                    changed = true
                }
            }
            rewrittenBlocks.add(updated ?: obj)
        }
        if (!changed) return documentJson to false
        val newRoot = LinkedHashMap(root)
        newRoot["blocks"] = rewrittenBlocks
        return MiniJson.encode(newRoot) to true
    }

    fun summarizeDocument(documentJson: String, title: String?): Pair<String, Int> {
        val blocks = runCatching { parseBlocks(documentJson) }.getOrDefault(emptyList())
        val attachmentCount = blocks.mapNotNull { it.attachmentId }.distinct().size
        val firstLine = title?.trim()?.takeIf { it.isNotEmpty() }
            ?: blocks.firstNotNullOfOrNull { block ->
                when {
                    block.text.isNotBlank() -> block.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                    block.attachmentId != null -> when (block.type) {
                        "image" -> "[图片附件]"
                        "audio" -> "[音频附件]"
                        "video" -> "[视频附件]"
                        else -> "[附件]"
                    }
                    else -> null
                }
            }
            ?: "空白笔记"
        val snippet = if (firstLine.length > 80) firstLine.take(79) + "…" else firstLine
        return snippet to attachmentCount
    }

    fun diffDocuments(
        noteId: String,
        targetRevision: Long,
        targetTitle: String?,
        targetDocumentJson: String,
        targetAttachmentRefsJson: String?,
        targetOrigin: String,
        targetCreatedAt: String,
        currentRevision: Long,
        currentTitle: String?,
        currentDocumentJson: String,
        resolveAttachmentLabel: (String) -> String?,
    ): VersionDiffPreview {
        val targetAttachmentIds = decodeAttachmentRefs(targetAttachmentRefsJson, targetDocumentJson)
        val missingAttachments = mutableListOf<MissingAttachmentInfo>()
        val targetAttachmentLabels = HashMap<String, String>(targetAttachmentIds.size)
        for (id in targetAttachmentIds) {
            val label = resolveAttachmentLabel(id)
            if (label == null) {
                missingAttachments.add(MissingAttachmentInfo(attachmentId = id, label = "缺失附件 ($id)"))
                targetAttachmentLabels[id] = "缺失附件 (${id.take(8)})"
            } else {
                targetAttachmentLabels[id] = label
            }
        }

        val currentBlocks = runCatching { parseBlocks(currentDocumentJson) }.getOrDefault(emptyList())
        val currentAttachmentLabels = HashMap<String, String>()
        for (block in currentBlocks) {
            val id = block.attachmentId ?: continue
            if (!currentAttachmentLabels.containsKey(id)) {
                currentAttachmentLabels[id] = resolveAttachmentLabel(id) ?: "缺失附件 (${id.take(8)})"
            }
        }

        val currentLines = toReadableLines(currentBlocks, currentAttachmentLabels)
        val targetLines = toReadableLines(
            runCatching { parseBlocks(targetDocumentJson) }.getOrDefault(emptyList()),
            targetAttachmentLabels,
        )

        val diffLines = computeLineDiff(currentLines = currentLines, targetLines = targetLines)
        return VersionDiffPreview(
            noteId = noteId,
            targetRevision = targetRevision,
            currentRevision = currentRevision,
            origin = targetOrigin,
            createdAt = targetCreatedAt,
            titleChanged = normalizeTitle(currentTitle) != normalizeTitle(targetTitle),
            currentTitle = currentTitle,
            targetTitle = targetTitle,
            lines = diffLines,
            totalAttachmentCount = targetAttachmentIds.size,
            missingAttachments = missingAttachments,
        )
    }

    private fun normalizeTitle(title: String?): String = title?.trim().orEmpty()

    private fun toReadableLines(
        blocks: List<ParsedDocBlock>,
        attachmentLabels: Map<String, String>,
    ): List<String> {
        val lines = mutableListOf<String>()
        for (block in blocks) {
            when (block.type) {
                "checkbox" -> {
                    val mark = if (block.checked) "[✓]" else "[ ]"
                    val due = block.dueDate?.let { "（截止 $it）" }.orEmpty()
                    val body = block.text.trim().ifEmpty { "未命名待办" }
                    lines.add("$mark $body$due")
                }
                in MEDIA_TYPES -> {
                    val kindLabel = when (block.type) {
                        "image" -> "图片"
                        "audio" -> "音频"
                        "video" -> "视频"
                        else -> "附件"
                    }
                    val label = block.attachmentId?.let { attachmentLabels[it] ?: it.take(8) } ?: "未知"
                    lines.add("[$kindLabel: $label]")
                }
                "reference" -> {
                    val label = block.referenceLabel?.takeIf { it.isNotBlank() } ?: "引用笔记"
                    lines.add("[@引用: $label]")
                }
                else -> {
                    if (block.text.isEmpty()) {
                        // Ignore trailing blank editing line so diffs stay focused on content.
                    } else {
                        block.text.lines().forEach { line ->
                            if (line.isNotBlank()) lines.add(line)
                        }
                    }
                }
            }
        }
        return if (lines.isEmpty()) listOf("（空内容）") else lines
    }

    private fun computeLineDiff(
        currentLines: List<String>,
        targetLines: List<String>,
    ): List<VersionDiffLine> {
        var prefix = 0
        val minLen = minOf(currentLines.size, targetLines.size)
        while (prefix < minLen && currentLines[prefix] == targetLines[prefix]) {
            prefix++
        }
        var suffix = 0
        while (
            suffix < (currentLines.size - prefix) &&
            suffix < (targetLines.size - prefix) &&
            currentLines[currentLines.size - 1 - suffix] == targetLines[targetLines.size - 1 - suffix]
        ) {
            suffix++
        }

        val result = ArrayList<VersionDiffLine>(currentLines.size + targetLines.size)
        for (i in 0 until prefix) {
            result.add(VersionDiffLine(DiffLineKind.UNCHANGED, currentLines[i]))
        }

        val midCurrent = currentLines.subList(prefix, currentLines.size - suffix)
        val midTarget = targetLines.subList(prefix, targetLines.size - suffix)

        if (midCurrent.size <= MAX_LCS_LINES && midTarget.size <= MAX_LCS_LINES) {
            val n = midCurrent.size
            val m = midTarget.size
            val dp = Array(n + 1) { IntArray(m + 1) }
            for (i in n - 1 downTo 0) {
                for (j in m - 1 downTo 0) {
                    dp[i][j] = if (midCurrent[i] == midTarget[j]) {
                        dp[i + 1][j + 1] + 1
                    } else {
                        maxOf(dp[i + 1][j], dp[i][j + 1])
                    }
                }
            }
            var i = 0
            var j = 0
            while (i < n && j < m) {
                when {
                    midCurrent[i] == midTarget[j] -> {
                        result.add(VersionDiffLine(DiffLineKind.UNCHANGED, midTarget[j]))
                        i++
                        j++
                    }
                    dp[i + 1][j] >= dp[i][j + 1] -> {
                        result.add(VersionDiffLine(DiffLineKind.REMOVED, midCurrent[i]))
                        i++
                    }
                    else -> {
                        result.add(VersionDiffLine(DiffLineKind.ADDED, midTarget[j]))
                        j++
                    }
                }
            }
            while (i < n) {
                result.add(VersionDiffLine(DiffLineKind.REMOVED, midCurrent[i++]))
            }
            while (j < m) {
                result.add(VersionDiffLine(DiffLineKind.ADDED, midTarget[j++]))
            }
        } else {
            midCurrent.forEach { result.add(VersionDiffLine(DiffLineKind.REMOVED, it)) }
            midTarget.forEach { result.add(VersionDiffLine(DiffLineKind.ADDED, it)) }
        }

        for (i in (currentLines.size - suffix) until currentLines.size) {
            result.add(VersionDiffLine(DiffLineKind.UNCHANGED, currentLines[i]))
        }
        return result
    }
}
