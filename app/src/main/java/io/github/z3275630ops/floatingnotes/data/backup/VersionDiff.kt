package io.github.z3275630ops.floatingnotes.data.backup

import io.github.z3275630ops.floatingnotes.core.model.safeUtf16Slice

internal data class ParsedDocMark(
    val type: String,
    val start: Int,
    val end: Int,
)

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
    val level: Int = 1,
    val indent: Int = 0,
    val tone: String? = null,
    val title: String? = null,
    val collapsed: Boolean = false,
    val dividerStyle: String? = null,
    val marks: List<ParsedDocMark> = emptyList(),
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
        val seenIds = HashSet<String>(blocksArray.size)
        return blocksArray.map { entry ->
            @Suppress("UNCHECKED_CAST")
            val obj = entry as? Map<String, Any?>
                ?: throw BackupValidationException("文档块必须是 JSON 对象")
            val id = obj.reqString("id")
            if (!seenIds.add(id)) {
                throw BackupValidationException("文档包含重复块 ID: $id")
            }
            val type = obj.reqString("type")
            val label = obj.optString("label")
            val text = when (type) {
                "reference" -> label.orEmpty()
                "divider" -> ""
                else -> obj.optString("text").orEmpty()
            }
            val checked = (obj["checked"] as? Boolean) ?: false
            val attachmentId = if (type in MEDIA_TYPES) obj.optString("attachment_id")?.takeIf { it.isNotBlank() } else null
            val refTarget = if (type == "reference") obj.optString("target_note_id")?.takeIf { it.isNotBlank() } else null
            val refBlockId = if (type == "reference") obj.optString("target_block_id")?.takeIf { it.isNotBlank() } else null
            val refStart = if (type == "reference") obj.optLong("start")?.toInt()?.takeIf { it >= 0 } else null
            val refEnd = if (type == "reference") obj.optLong("end")?.toInt()?.takeIf { it >= 0 } else null
            val level = (obj.optLong("level")?.toInt() ?: 1).coerceIn(1, 3)
            val indent = (obj.optLong("indent")?.toInt() ?: 0).coerceIn(0, 4)
            val tone = if (type == "callout") obj.optString("tone")?.takeIf { it.isNotBlank() } else null
            val title = if (type == "toggle") obj.optString("title") else null
            val collapsed = if (type == "toggle") ((obj["collapsed"] as? Boolean) ?: false) else false
            val dividerStyle = if (type == "divider") obj.optString("style")?.takeIf { it.isNotBlank() } else null
            val rawMarks = (obj["marks"] as? List<*>)?.mapNotNull { entry ->
                @Suppress("UNCHECKED_CAST")
                val m = entry as? Map<String, Any?> ?: return@mapNotNull null
                val mType = m["type"] as? String ?: return@mapNotNull null
                val mStart = (m["start"] as? Number)?.toInt() ?: return@mapNotNull null
                val mEnd = (m["end"] as? Number)?.toInt() ?: return@mapNotNull null
                if (mStart >= 0 && mEnd > mStart) ParsedDocMark(mType, mStart, mEnd) else null
            }.orEmpty()
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
                level = level,
                indent = indent,
                tone = tone,
                title = title,
                collapsed = collapsed,
                dividerStyle = dividerStyle,
                marks = rawMarks,
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
        clearTaskIds: Boolean = false,
    ): Pair<String, Boolean> {
        if (!clearTaskIds && noteIdMap.isEmpty() && attachmentIdMap.isEmpty() &&
            reminderIdMap.isEmpty() && occurrenceIdMap.isEmpty()
        ) {
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
                if (clearTaskIds && remId != null) {
                    updated = LinkedHashMap(obj).apply { remove("reminder_id") }
                    changed = true
                } else if (mappedRemId != null && mappedRemId != remId) {
                    updated = LinkedHashMap(obj).apply { put("reminder_id", mappedRemId) }
                    changed = true
                }
                val occId = obj["occurrence_id"] as? String
                val mappedOccId = occId?.let { occurrenceIdMap[it] }
                if (clearTaskIds && occId != null) {
                    updated = (updated ?: LinkedHashMap(obj)).apply { remove("occurrence_id") }
                    changed = true
                } else if (mappedOccId != null && mappedOccId != occId) {
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
                    block.type == "toggle" && !block.title.isNullOrBlank() -> block.title.trim()
                    block.text.isNotBlank() -> block.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                    block.attachmentId != null -> when (block.type) {
                        "image" -> "[图片附件]"
                        "audio" -> "[音频附件]"
                        "video" -> "[视频附件]"
                        else -> "[附件]"
                    }
                    block.type == "divider" -> "[分割线]"
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
        val numberedCounters = IntArray(5)
        for (block in blocks) {
            if (block.type != "numbered") {
                val resetFrom = if (block.type == "bullet" || block.type == "checkbox") {
                    block.indent.coerceIn(0, 4)
                } else {
                    0
                }
                for (i in resetFrom until numberedCounters.size) {
                    numberedCounters[i] = 0
                }
            }
            val indentPrefix = if (block.indent > 0) "  ".repeat(block.indent.coerceIn(0, 4)) else ""
            val markTag = formatMarksBadge(block.text, block.marks)
            when (block.type) {
                "heading" -> {
                    val body = block.text.trim()
                    if (body.isNotEmpty() || markTag.isNotEmpty()) {
                        lines.add("$indentPrefix${"#".repeat(block.level.coerceIn(1, 3))} $body$markTag")
                    }
                }
                "bullet" -> {
                    val body = block.text.trim()
                    if (body.isNotEmpty() || markTag.isNotEmpty()) {
                        lines.add("$indentPrefix• $body$markTag")
                    }
                }
                "numbered" -> {
                    val d = block.indent.coerceIn(0, 4)
                    numberedCounters[d] += 1
                    for (i in (d + 1) until numberedCounters.size) numberedCounters[i] = 0
                    val body = block.text.trim()
                    if (body.isNotEmpty() || markTag.isNotEmpty()) {
                        lines.add("$indentPrefix${numberedCounters[d]}. $body$markTag")
                    }
                }
                "checkbox" -> {
                    val mark = if (block.checked) "[✓]" else "[ ]"
                    val due = block.dueDate?.let { "（截止 $it）" }.orEmpty()
                    val body = block.text.trim().ifEmpty { "未命名待办" }
                    lines.add("$indentPrefix$mark $body$due$markTag")
                }
                "quote" -> {
                    val body = block.text.trim()
                    if (body.isNotEmpty() || markTag.isNotEmpty()) {
                        lines.add("$indentPrefix> $body$markTag")
                    }
                }
                "callout" -> {
                    val toneLabel = when (block.tone) {
                        "tip" -> "技巧"
                        "warn" -> "提醒"
                        "important" -> "重点"
                        else -> "信息"
                    }
                    val body = block.text.trim().ifEmpty { "提示内容" }
                    lines.add("$indentPrefix[提示($toneLabel)] $body$markTag")
                }
                "toggle" -> {
                    val arrow = if (block.collapsed) "▸" else "▾"
                    val title = block.title?.trim().orEmpty().ifEmpty { "未命名折叠块" }
                    lines.add("$indentPrefix[折叠$arrow] $title")
                    var firstBody = true
                    block.text.lines().forEach { line ->
                        if (line.isNotBlank()) {
                            val suffix = if (firstBody) markTag else ""
                            firstBody = false
                            lines.add("$indentPrefix  $line$suffix")
                        }
                    }
                }
                "divider" -> {
                    val styleLabel = if (block.dividerStyle == "dashed") "虚线" else "实线"
                    lines.add("——— 分割线（$styleLabel）———")
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
                        var first = true
                        block.text.lines().forEach { line ->
                            if (line.isNotBlank()) {
                                val lineOut = buildString {
                                    append(indentPrefix)
                                    append(line)
                                    if (first) append(markTag)
                                }
                                first = false
                                lines.add(lineOut)
                            }
                        }
                    }
                }
            }
        }
        return if (lines.isEmpty()) listOf("（空内容）") else lines
    }

    private fun formatMarksBadge(text: String, marks: List<ParsedDocMark>): String {
        if (marks.isEmpty() || text.isEmpty()) return ""
        val parts = marks.mapNotNull { mark ->
            val label = when (mark.type) {
                "bold" -> "加粗"
                "highlight" -> "高亮"
                "italic" -> "斜体"
                else -> null
            } ?: return@mapNotNull null
            val snippet = safeUtf16Slice(text, mark.start, mark.end).trim()
            if (snippet.isEmpty()) null else "$label「${safeUtf16Slice(snippet, 0, 16)}」"
        }.distinct()
        return if (parts.isEmpty()) "" else " · [${parts.joinToString("、")}]"
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
