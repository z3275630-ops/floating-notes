package io.github.z3275630ops.floatingnotes.core.model

import io.github.z3275630ops.floatingnotes.data.backup.MiniJson
import java.time.LocalDate
import java.util.UUID

enum class NoteMarkType(val wire: String, val displayLabel: String) {
    BOLD("bold", "加粗"),
    HIGHLIGHT("highlight", "高亮"),
    ITALIC("italic", "斜体"),
    ;

    companion object {
        fun fromWire(value: String?): NoteMarkType? =
            entries.firstOrNull { it.wire == value }
    }
}

data class NoteTextMark(
    val type: NoteMarkType,
    val start: Int,
    val end: Int,
) {
    init {
        require(start >= 0 && end > start) { "Invalid mark range [$start, $end)" }
    }
}

@ConsistentCopyVisibility
data class NoteBlock internal constructor(
    val id: String,
    val type: String,
    val text: String,
    val checked: Boolean,
    private val original: String?,
    val level: Int = 1,
    val indent: Int = 0,
    val marks: List<NoteTextMark> = emptyList(),
    val tone: String = DEFAULT_CALLOUT_TONE,
    val title: String = "",
    val collapsed: Boolean = false,
    val dividerStyle: String = DEFAULT_DIVIDER_STYLE,
) {
    private fun parsedOriginal(): Map<String, Any?>? {
        val raw = original ?: return null
        return runCatching { MiniJson.parseObject(raw) }.getOrNull()
    }

    private fun optionalOriginalString(key: String): String? {
        val value = parsedOriginal()?.get(key) as? String ?: return null
        return value.takeIf { it.isNotBlank() }
    }

    private fun optionalOriginalInt(key: String): Int? {
        val value = parsedOriginal()?.get(key) as? Number ?: return null
        return value.toInt().takeIf { it >= 0 }
    }

    val referenceTarget: String?
        get() = if (type == "reference") optionalOriginalString("target_note_id") else null

    val referenceTargetBlockId: String?
        get() = if (type == "reference") optionalOriginalString("target_block_id") else null

    val referenceStart: Int?
        get() = if (type == "reference") optionalOriginalInt("start") else null

    val referenceEnd: Int?
        get() = if (type == "reference") optionalOriginalInt("end") else null

    val referenceRevision: Long?
        get() = if (type == "reference") {
            (parsedOriginal()?.get("target_revision") as? Number)?.toLong()?.takeIf { it > 0 }
        } else null

    val dueDate: String?
        get() = if (type == "checkbox") optionalOriginalString("due_date") else null

    val reminderId: String?
        get() = if (type == "checkbox") optionalOriginalString("reminder_id") else null

    val occurrenceId: String?
        get() = if (type == "checkbox") optionalOriginalString("occurrence_id") else null

    internal val attachmentId: String?
        get() = if (type in mediaTypes) optionalOriginalString("attachment_id") else null

    val editable: Boolean
        get() = type in editableTypes

    val searchableText: String
        get() = when (type) {
            "toggle" -> listOf(title, text).filter { it.isNotEmpty() }.joinToString("\n")
            "divider" -> ""
            else -> text
        }

    fun withChecked(checked: Boolean): NoteBlock {
        require(type == "checkbox") { "Only checkbox blocks have checked state" }
        val map = parsedOriginal()?.toMutableMap()
        if (map != null) {
            map["checked"] = checked
            return copy(checked = checked, original = MiniJson.encode(map))
        }
        return copy(checked = checked)
    }

    fun withTaskSchedule(
        dueDate: String? = this.dueDate,
        reminderId: String? = this.reminderId,
        occurrenceId: String? = this.occurrenceId,
    ): NoteBlock {
        require(type == "checkbox") { "Only checkbox blocks support task schedule fields" }
        val normalizedDate = dueDate?.trim()?.takeIf { it.isNotEmpty() }?.also {
            require(ISO_DATE_PATTERN.matches(it)) { "due_date must be YYYY-MM-DD" }
            LocalDate.parse(it)
        }
        val normalizedReminder = reminderId?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedOccurrence = occurrenceId?.trim()?.takeIf { it.isNotEmpty() }
        val map = (parsedOriginal()?.toMutableMap() ?: LinkedHashMap())
        map["id"] = id
        map["type"] = type
        map["text"] = text
        map["checked"] = checked
        if (normalizedDate == null) map.remove("due_date") else map["due_date"] = normalizedDate
        if (normalizedReminder == null) map.remove("reminder_id") else map["reminder_id"] = normalizedReminder
        if (normalizedOccurrence == null) map.remove("occurrence_id") else map["occurrence_id"] = normalizedOccurrence
        return copy(original = MiniJson.encode(map))
    }

    fun withIndent(indent: Int): NoteBlock {
        if (!editable) return this
        return copy(indent = indent.coerceIn(0, MAX_INDENT))
    }

    fun withMarks(marks: List<NoteTextMark>): NoteBlock {
        if (!editable) return this
        return copy(marks = normalizeMarks(marks, text))
    }

    fun withHeadingLevel(level: Int): NoteBlock {
        require(type == "heading") { "Only heading blocks have heading level" }
        return copy(level = level.coerceIn(1, 3))
    }

    fun withCalloutTone(tone: String): NoteBlock {
        require(type == "callout") { "Only callout blocks have callout tone" }
        return copy(tone = normalizeCalloutTone(tone))
    }

    fun withToggleState(
        title: String = this.title,
        text: String = this.text,
        collapsed: Boolean = this.collapsed,
    ): NoteBlock {
        require(type == "toggle") { "Only toggle blocks have toggle state" }
        return copy(
            title = title,
            text = text,
            collapsed = collapsed,
            marks = normalizeMarks(marks, text),
        )
    }

    fun withDividerStyle(style: String): NoteBlock {
        require(type == "divider") { "Only divider blocks have divider style" }
        return copy(dividerStyle = normalizeDividerStyle(style))
    }

    fun convertToType(
        newType: String,
        headingLevel: Int = 1,
        calloutTone: String = this.tone,
        dividerStyle: String = this.dividerStyle,
    ): NoteBlock {
        if (newType == "divider") {
            return NoteBlock(
                id = id,
                type = "divider",
                text = "",
                checked = false,
                original = null,
                dividerStyle = normalizeDividerStyle(dividerStyle),
            )
        }
        require(newType in editableTypes) { "Unsupported target block type: $newType" }
        if (newType == type) {
            return when (newType) {
                "heading" -> copy(level = headingLevel.coerceIn(1, 3))
                "callout" -> copy(tone = normalizeCalloutTone(calloutTone))
                else -> this
            }
        }
        if (newType == "toggle") {
            val nl = text.indexOf('\n')
            val newTitle = if (nl >= 0) text.substring(0, nl) else text
            val newBody = if (nl >= 0) text.substring(nl + 1) else ""
            val bodyMarks = if (nl >= 0 && newBody.isNotEmpty()) {
                val shift = nl + 1
                marks.mapNotNull { mark ->
                    val s = (mark.start - shift).coerceAtLeast(0)
                    val e = (mark.end - shift).coerceAtMost(newBody.length)
                    if (s < e) NoteTextMark(mark.type, s, e) else null
                }
            } else {
                emptyList()
            }
            return NoteBlock(
                id = id,
                type = "toggle",
                text = newBody,
                checked = false,
                original = null,
                indent = indent.coerceIn(0, MAX_INDENT),
                marks = normalizeMarks(bodyMarks, newBody),
                title = newTitle,
                collapsed = false,
            )
        }
        val nextText: String
        val nextMarks: List<NoteTextMark>
        if (type == "toggle") {
            nextText = listOf(title, text).filter { it.isNotEmpty() }.joinToString("\n")
            val shift = if (title.isNotEmpty()) title.length + 1 else 0
            nextMarks = if (text.isEmpty()) {
                emptyList()
            } else {
                normalizeMarks(
                    marks.map { NoteTextMark(it.type, it.start + shift, it.end + shift) },
                    nextText,
                )
            }
        } else {
            nextText = text
            nextMarks = normalizeMarks(marks, nextText)
        }
        return NoteBlock(
            id = id,
            type = newType,
            text = nextText,
            checked = false,
            original = null,
            level = if (newType == "heading") headingLevel.coerceIn(1, 3) else 1,
            indent = indent.coerceIn(0, MAX_INDENT),
            marks = nextMarks,
            tone = if (newType == "callout") normalizeCalloutTone(calloutTone) else DEFAULT_CALLOUT_TONE,
            title = "",
            collapsed = false,
        )
    }

    internal fun encodeMap(): LinkedHashMap<String, Any?> {
        if (type == "divider") {
            return linkedMapOf(
                "id" to id,
                "type" to "divider",
                "style" to normalizeDividerStyle(dividerStyle),
            )
        }
        val result = LinkedHashMap<String, Any?>()
        parsedOriginal()?.let { result.putAll(it) }
        result["id"] = id
        result["type"] = type
        if (editable) {
            result["text"] = text
            if (type == "heading") {
                result["level"] = level.coerceIn(1, 3)
            } else {
                result.remove("level")
            }
            if (type == "checkbox") {
                result["checked"] = checked
            } else {
                result.remove("checked")
                result.remove("due_date")
                result.remove("reminder_id")
                result.remove("occurrence_id")
            }
            if (type == "callout") {
                result["tone"] = normalizeCalloutTone(tone)
            } else {
                result.remove("tone")
            }
            if (type == "toggle") {
                result["title"] = title
                result["collapsed"] = collapsed
            } else {
                result.remove("title")
                result.remove("collapsed")
            }
            result.remove("style")
            val safeIndent = indent.coerceIn(0, MAX_INDENT)
            if (safeIndent > 0) result["indent"] = safeIndent else result.remove("indent")
            val safeMarks = normalizeMarks(marks, text)
            if (safeMarks.isNotEmpty()) {
                result["marks"] = safeMarks.map { mark ->
                    linkedMapOf<String, Any?>(
                        "type" to mark.type.wire,
                        "start" to mark.start,
                        "end" to mark.end,
                    )
                }
            } else {
                result.remove("marks")
            }
        }
        return result
    }

    companion object {
        const val MAX_INDENT = 4
        const val DEFAULT_CALLOUT_TONE = "info"
        const val DEFAULT_DIVIDER_STYLE = "solid"
        val CALLOUT_TONES = listOf("info", "tip", "warn", "important")
        val DIVIDER_STYLES = listOf("solid", "dashed")

        private val mediaTypes = setOf("image", "audio", "video")
        private val editableTypes = setOf(
            "paragraph",
            "heading",
            "bullet",
            "numbered",
            "checkbox",
            "quote",
            "callout",
            "toggle",
        )
        private val ISO_DATE_PATTERN = Regex("""\d{4}-\d{2}-\d{2}""")

        internal fun normalizeCalloutTone(raw: String?): String =
            if (raw in CALLOUT_TONES) raw!! else DEFAULT_CALLOUT_TONE

        internal fun normalizeDividerStyle(raw: String?): String =
            if (raw in DIVIDER_STYLES) raw!! else DEFAULT_DIVIDER_STYLE

        internal fun attachment(type: String, attachmentId: String): NoteBlock {
            require(type in mediaTypes)
            val map = linkedMapOf<String, Any?>(
                "id" to newId(),
                "type" to type,
                "attachment_id" to attachmentId,
            )
            return decode(map)
        }

        @JvmOverloads
        fun paragraph(
            text: String = "",
            indent: Int = 0,
            marks: List<NoteTextMark> = emptyList(),
        ) = NoteBlock(
            id = newId(),
            type = "paragraph",
            text = text,
            checked = false,
            original = null,
            indent = indent.coerceIn(0, MAX_INDENT),
            marks = normalizeMarks(marks, text),
        )

        @JvmOverloads
        fun heading(
            level: Int = 1,
            text: String = "",
            indent: Int = 0,
            marks: List<NoteTextMark> = emptyList(),
        ) = NoteBlock(
            id = newId(),
            type = "heading",
            text = text,
            checked = false,
            original = null,
            level = level.coerceIn(1, 3),
            indent = indent.coerceIn(0, MAX_INDENT),
            marks = normalizeMarks(marks, text),
        )

        @JvmOverloads
        fun bullet(
            text: String = "",
            indent: Int = 0,
            marks: List<NoteTextMark> = emptyList(),
        ) = NoteBlock(
            id = newId(),
            type = "bullet",
            text = text,
            checked = false,
            original = null,
            indent = indent.coerceIn(0, MAX_INDENT),
            marks = normalizeMarks(marks, text),
        )

        @JvmOverloads
        fun numbered(
            text: String = "",
            indent: Int = 0,
            marks: List<NoteTextMark> = emptyList(),
        ) = NoteBlock(
            id = newId(),
            type = "numbered",
            text = text,
            checked = false,
            original = null,
            indent = indent.coerceIn(0, MAX_INDENT),
            marks = normalizeMarks(marks, text),
        )

        @JvmOverloads
        fun checkbox(
            text: String = "",
            checked: Boolean = false,
            dueDate: String? = null,
            reminderId: String? = null,
            occurrenceId: String? = null,
            indent: Int = 0,
            marks: List<NoteTextMark> = emptyList(),
        ): NoteBlock {
            val base = NoteBlock(
                id = newId(),
                type = "checkbox",
                text = text,
                checked = checked,
                original = null,
                indent = indent.coerceIn(0, MAX_INDENT),
                marks = normalizeMarks(marks, text),
            )
            return if (dueDate == null && reminderId == null && occurrenceId == null) base
            else base.withTaskSchedule(dueDate = dueDate, reminderId = reminderId, occurrenceId = occurrenceId)
        }

        @JvmOverloads
        fun quote(
            text: String = "",
            indent: Int = 0,
            marks: List<NoteTextMark> = emptyList(),
        ) = NoteBlock(
            id = newId(),
            type = "quote",
            text = text,
            checked = false,
            original = null,
            indent = indent.coerceIn(0, MAX_INDENT),
            marks = normalizeMarks(marks, text),
        )

        @JvmOverloads
        fun callout(
            text: String = "",
            tone: String = DEFAULT_CALLOUT_TONE,
            indent: Int = 0,
            marks: List<NoteTextMark> = emptyList(),
        ) = NoteBlock(
            id = newId(),
            type = "callout",
            text = text,
            checked = false,
            original = null,
            indent = indent.coerceIn(0, MAX_INDENT),
            marks = normalizeMarks(marks, text),
            tone = normalizeCalloutTone(tone),
        )

        @JvmOverloads
        fun toggle(
            title: String = "",
            text: String = "",
            collapsed: Boolean = false,
            indent: Int = 0,
            marks: List<NoteTextMark> = emptyList(),
        ) = NoteBlock(
            id = newId(),
            type = "toggle",
            text = text,
            checked = false,
            original = null,
            indent = indent.coerceIn(0, MAX_INDENT),
            marks = normalizeMarks(marks, text),
            title = title,
            collapsed = collapsed,
        )

        @JvmOverloads
        fun divider(style: String = DEFAULT_DIVIDER_STYLE) = NoteBlock(
            id = newId(),
            type = "divider",
            text = "",
            checked = false,
            original = null,
            dividerStyle = normalizeDividerStyle(style),
        )

        @JvmOverloads
        fun reference(
            noteId: String,
            revision: Long,
            label: String,
            targetBlockId: String? = null,
            start: Int? = null,
            end: Int? = null,
        ): NoteBlock {
            val id = newId()
            val map = linkedMapOf<String, Any?>(
                "id" to id,
                "type" to "reference",
                "target_note_id" to noteId,
                "target_revision" to revision,
                "label" to label,
            )
            val normalizedBlockId = targetBlockId?.trim()?.takeIf { it.isNotEmpty() }
            if (normalizedBlockId != null) {
                map["target_block_id"] = normalizedBlockId
            }
            if (start != null && end != null) {
                require(start >= 0 && end >= start) { "Invalid reference range [$start, $end)" }
                map["start"] = start
                map["end"] = end
            }
            return decode(map)
        }

        internal fun decode(value: Map<String, Any?>): NoteBlock {
            val id = value["id"] as? String
            require(!id.isNullOrEmpty()) { "Block missing id" }
            val type = value["type"] as? String
            require(!type.isNullOrEmpty()) { "Block missing type" }
            val text = when (type) {
                "reference" -> (value["label"] as? String).orEmpty()
                "divider" -> ""
                else -> (value["text"] as? String).orEmpty()
            }
            val checked = (value["checked"] as? Boolean) ?: false
            val level = ((value["level"] as? Number)?.toInt() ?: 1).coerceIn(1, 3)
            val indent = ((value["indent"] as? Number)?.toInt() ?: 0).coerceIn(0, MAX_INDENT)
            val tone = if (type == "callout") normalizeCalloutTone(value["tone"] as? String) else DEFAULT_CALLOUT_TONE
            val title = if (type == "toggle") (value["title"] as? String).orEmpty() else ""
            val collapsed = if (type == "toggle") ((value["collapsed"] as? Boolean) ?: false) else false
            val dividerStyle = if (type == "divider") normalizeDividerStyle(value["style"] as? String) else DEFAULT_DIVIDER_STYLE
            val marks = if (type in editableTypes) decodeMarks(value["marks"], text) else emptyList()
            return NoteBlock(
                id = id,
                type = type,
                text = text,
                checked = checked,
                original = MiniJson.encode(value),
                level = level,
                indent = indent,
                marks = marks,
                tone = tone,
                title = title,
                collapsed = collapsed,
                dividerStyle = dividerStyle,
            )
        }

        private fun decodeMarks(raw: Any?, text: String): List<NoteTextMark> {
            val list = raw as? List<*> ?: return emptyList()
            if (list.isEmpty() || text.isEmpty()) return emptyList()
            val parsed = ArrayList<NoteTextMark>(list.size)
            for (entry in list) {
                val obj = entry as? Map<*, *> ?: continue
                val markType = NoteMarkType.fromWire(obj["type"] as? String) ?: continue
                val start = (obj["start"] as? Number)?.toInt() ?: continue
                val end = (obj["end"] as? Number)?.toInt() ?: continue
                val clamped = clampUtf16Range(text, start, end)
                if (!clamped.isEmpty()) {
                    parsed.add(NoteTextMark(markType, clamped.first, clamped.last + 1))
                }
            }
            return normalizeMarks(parsed, text)
        }
    }
}

data class NoteDocument(val blocks: List<NoteBlock>) {
    init {
        require(blocks.isNotEmpty()) { "A document requires at least one block" }
        require(blocks.map { it.id }.toSet().size == blocks.size) { "Duplicate block ID" }
    }

    val hasContent: Boolean get() = blocks.any { it.text.isNotBlank() || it.title.isNotBlank() || !it.editable }
    val text: String get() = blocks.joinToString("\n") { it.searchableText }

    fun findBlock(blockId: String): NoteBlock? = blocks.firstOrNull { it.id == blockId }

    fun replaceBlock(blockId: String, transform: (NoteBlock) -> NoteBlock): NoteDocument {
        var matched = false
        val updated = blocks.map { block ->
            if (block.id == blockId) {
                matched = true
                transform(block)
            } else block
        }
        require(matched) { "Missing block $blockId" }
        return NoteDocument(updated)
    }

    fun encode(): String = MiniJson.encode(
        linkedMapOf<String, Any?>(
            "document_schema_version" to 1,
            "blocks" to blocks.map { it.encodeMap() },
        ),
    )

    companion object {
        fun empty() = NoteDocument(listOf(NoteBlock.paragraph()))

        fun decode(json: String): NoteDocument {
            val document = MiniJson.parseObject(json)
            val version = (document["document_schema_version"] as? Number)?.toInt()
            require(version == 1) { "Unsupported document version" }
            val rawBlocks = document["blocks"] as? List<*>
                ?: throw IllegalArgumentException("Missing blocks array")
            val decoded = rawBlocks.map { entry ->
                @Suppress("UNCHECKED_CAST")
                val obj = entry as? Map<String, Any?>
                    ?: throw IllegalArgumentException("Block entry must be an object")
                NoteBlock.decode(obj)
            }
            return NoteDocument(decoded)
        }
    }
}

internal fun normalizeMarks(marks: List<NoteTextMark>, text: String): List<NoteTextMark> {
    if (marks.isEmpty() || text.isEmpty()) return emptyList()
    val grouped = HashMap<NoteMarkType, MutableList<NoteTextMark>>()
    for (mark in marks) {
        val range = clampUtf16Range(text, mark.start, mark.end)
        if (!range.isEmpty()) {
            grouped.getOrPut(mark.type) { ArrayList() }
                .add(NoteTextMark(mark.type, range.first, range.last + 1))
        }
    }
    if (grouped.isEmpty()) return emptyList()
    val merged = ArrayList<NoteTextMark>()
    for ((type, items) in grouped) {
        items.sortWith(compareBy<NoteTextMark> { it.start }.thenBy { it.end })
        var curStart = items[0].start
        var curEnd = items[0].end
        for (i in 1 until items.size) {
            val next = items[i]
            if (next.start <= curEnd) {
                curEnd = maxOf(curEnd, next.end)
            } else {
                merged.add(NoteTextMark(type, curStart, curEnd))
                curStart = next.start
                curEnd = next.end
            }
        }
        merged.add(NoteTextMark(type, curStart, curEnd))
    }
    merged.sortWith(compareBy<NoteTextMark> { it.start }.thenBy { it.end }.thenBy { it.type.ordinal })
    return merged
}

internal fun toggleTextMark(
    marks: List<NoteTextMark>,
    type: NoteMarkType,
    rawStart: Int,
    rawEnd: Int,
    text: String,
): List<NoteTextMark> {
    val range = clampUtf16Range(text, rawStart, rawEnd)
    if (range.isEmpty()) return normalizeMarks(marks, text)
    val start = range.first
    val end = range.last + 1
    val normalized = normalizeMarks(marks, text)
    val fullyCovered = isRangeCoveredByMark(normalized, type, start, end)
    if (!fullyCovered) {
        return normalizeMarks(normalized + NoteTextMark(type, start, end), text)
    }
    val updated = ArrayList<NoteTextMark>(normalized.size + 1)
    for (mark in normalized) {
        if (mark.type != type || mark.end <= start || mark.start >= end) {
            updated.add(mark)
            continue
        }
        if (mark.start < start) {
            updated.add(NoteTextMark(type, mark.start, start))
        }
        if (mark.end > end) {
            updated.add(NoteTextMark(type, end, mark.end))
        }
    }
    return normalizeMarks(updated, text)
}

internal fun isRangeCoveredByMark(
    marks: List<NoteTextMark>,
    type: NoteMarkType,
    start: Int,
    end: Int,
): Boolean {
    if (start >= end) return false
    var cursor = start
    for (mark in marks) {
        if (mark.type != type) continue
        if (mark.end <= cursor) continue
        if (mark.start > cursor) return false
        cursor = maxOf(cursor, mark.end)
        if (cursor >= end) return true
    }
    return cursor >= end
}

internal fun hasMarkAtSelection(
    marks: List<NoteTextMark>,
    type: NoteMarkType,
    rawStart: Int,
    rawEnd: Int,
    text: String,
): Boolean {
    if (marks.isEmpty() || text.isEmpty()) return false
    val clamped = clampUtf16Range(text, rawStart, rawEnd)
    return if (clamped.isEmpty()) {
        val pos = clamped.first
        marks.any { it.type == type && pos >= it.start && pos < it.end }
    } else {
        isRangeCoveredByMark(normalizeMarks(marks, text), type, clamped.first, clamped.last + 1)
    }
}

internal fun adjustMarksForTextEdit(
    marks: List<NoteTextMark>,
    oldText: String,
    newText: String,
    cursorAfterEdit: Int? = null,
): List<NoteTextMark> {
    if (marks.isEmpty() || oldText == newText) return normalizeMarks(marks, newText)
    if (newText.isEmpty()) return emptyList()

    val minLen = minOf(oldText.length, newText.length)
    var prefix = 0
    while (prefix < minLen && oldText[prefix] == newText[prefix]) {
        prefix++
    }
    var suffix = 0
    while (
        suffix < (oldText.length - prefix) &&
        suffix < (newText.length - prefix) &&
        oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]
    ) {
        suffix++
    }

    var editStart = prefix
    var oldEnd = oldText.length - suffix
    var newEnd = newText.length - suffix

    // Disambiguate pure single insertion inside a repeated character run when cursor position is known.
    if (cursorAfterEdit != null && oldEnd == editStart && newText.length > oldText.length) {
        val insertedLen = newEnd - editStart
        val candidateStart = cursorAfterEdit - insertedLen
        if (candidateStart in 0..editStart &&
            oldText.substring(0, candidateStart) == newText.substring(0, candidateStart) &&
            oldText.substring(candidateStart) == newText.substring(cursorAfterEdit)
        ) {
            editStart = candidateStart
            oldEnd = candidateStart
            newEnd = cursorAfterEdit
        }
    }

    val delta = newEnd - oldEnd
    val pureInsert = oldEnd == editStart
    val adjusted = ArrayList<NoteTextMark>(marks.size)
    for (mark in marks) {
        val mStart = mark.start
        val mEnd = mark.end
        if (pureInsert) {
            when {
                editStart <= mStart -> adjusted.add(NoteTextMark(mark.type, mStart + delta, mEnd + delta))
                editStart >= mEnd -> adjusted.add(mark)
                else -> adjusted.add(NoteTextMark(mark.type, mStart, mEnd + delta))
            }
        } else {
            val nextStart = when {
                mStart <= editStart -> mStart
                mStart < oldEnd -> newEnd
                else -> mStart + delta
            }
            val nextEnd = when {
                mEnd <= editStart -> mEnd
                mEnd <= oldEnd -> editStart
                else -> mEnd + delta
            }
            if (nextStart < nextEnd) {
                adjusted.add(NoteTextMark(mark.type, nextStart, nextEnd))
            }
        }
    }
    return normalizeMarks(adjusted, newText)
}

internal fun splitMarksAt(
    marks: List<NoteTextMark>,
    splitIndex: Int,
    leftText: String,
    rightText: String,
): Pair<List<NoteTextMark>, List<NoteTextMark>> {
    if (marks.isEmpty()) return emptyList<NoteTextMark>() to emptyList()
    val safeSplit = splitIndex.coerceAtLeast(0)
    val left = ArrayList<NoteTextMark>()
    val right = ArrayList<NoteTextMark>()
    for (mark in marks) {
        if (mark.start < safeSplit) {
            val lEnd = minOf(mark.end, safeSplit)
            if (mark.start < lEnd) {
                left.add(NoteTextMark(mark.type, mark.start, lEnd))
            }
        }
        if (mark.end > safeSplit) {
            val rStart = maxOf(0, mark.start - safeSplit)
            val rEnd = mark.end - safeSplit
            if (rStart < rEnd) {
                right.add(NoteTextMark(mark.type, rStart, rEnd))
            }
        }
    }
    return normalizeMarks(left, leftText) to normalizeMarks(right, rightText)
}

internal fun mergeMarks(
    leftMarks: List<NoteTextMark>,
    leftLength: Int,
    rightMarks: List<NoteTextMark>,
    mergedText: String,
): List<NoteTextMark> {
    if (leftMarks.isEmpty() && rightMarks.isEmpty()) return emptyList()
    val combined = ArrayList<NoteTextMark>(leftMarks.size + rightMarks.size)
    combined.addAll(leftMarks)
    for (mark in rightMarks) {
        combined.add(NoteTextMark(mark.type, mark.start + leftLength, mark.end + leftLength))
    }
    return normalizeMarks(combined, mergedText)
}

internal fun clampUtf16Range(text: String, rawStart: Int, rawEnd: Int): IntRange {
    if (text.isEmpty()) return 0 until 0
    val clampedA = rawStart.coerceIn(0, text.length)
    val clampedB = rawEnd.coerceIn(0, text.length)
    var start = minOf(clampedA, clampedB)
    var end = maxOf(clampedA, clampedB)
    fun splitsSurrogate(index: Int): Boolean =
        index in 1 until text.length &&
            Character.isLowSurrogate(text[index]) &&
            Character.isHighSurrogate(text[index - 1])
    val emptySelection = start == end
    if (splitsSurrogate(start)) {
        start -= 1
    }
    if (emptySelection) {
        return start until start
    }
    if (splitsSurrogate(end)) {
        end += 1
    }
    return start until end
}

internal fun safeUtf16Slice(text: String, rawStart: Int, rawEnd: Int): String {
    val range = clampUtf16Range(text, rawStart, rawEnd)
    return if (range.isEmpty()) "" else text.substring(range.first, range.last + 1)
}

internal fun newId(): String = UUID.randomUUID().toString()
