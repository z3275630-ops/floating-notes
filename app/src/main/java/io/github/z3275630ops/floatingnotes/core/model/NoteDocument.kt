package io.github.z3275630ops.floatingnotes.core.model

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

data class NoteBlock internal constructor(
    val id: String,
    val type: String,
    val text: String,
    val checked: Boolean,
    private val original: String?,
) {
    private fun optionalOriginalString(key: String): String? {
        val raw = original ?: return null
        val json = JSONObject(raw)
        if (!json.has(key) || json.isNull(key)) return null
        return json.optString(key).takeIf { it.isNotBlank() }
    }

    private fun optionalOriginalInt(key: String): Int? {
        val raw = original ?: return null
        val json = JSONObject(raw)
        if (!json.has(key) || json.isNull(key)) return null
        return json.optInt(key, -1).takeIf { it >= 0 }
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
        get() = if (type == "reference") original?.let { JSONObject(it).optLong("target_revision") }?.takeIf { it > 0 } else null

    val dueDate: String?
        get() = if (type == "checkbox") optionalOriginalString("due_date") else null

    val reminderId: String?
        get() = if (type == "checkbox") optionalOriginalString("reminder_id") else null

    val occurrenceId: String?
        get() = if (type == "checkbox") optionalOriginalString("occurrence_id") else null

    internal val attachmentId: String?
        get() = if (type in mediaTypes) optionalOriginalString("attachment_id") else null

    val editable: Boolean
        get() = (type == "paragraph" || type == "checkbox") &&
            original?.let { (JSONObject(it).optJSONArray("marks")?.length() ?: 0) == 0 } != false

    fun withChecked(checked: Boolean): NoteBlock {
        require(type == "checkbox") { "Only checkbox blocks have checked state" }
        val raw = original ?: return copy(checked = checked)
        val json = JSONObject(raw).put("checked", checked)
        return copy(checked = checked, original = json.toString())
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
        val json = (original?.let(::JSONObject) ?: JSONObject())
            .put("id", id)
            .put("type", type)
            .put("text", text)
            .put("checked", checked)
        if (normalizedDate == null) json.remove("due_date") else json.put("due_date", normalizedDate)
        if (normalizedReminder == null) json.remove("reminder_id") else json.put("reminder_id", normalizedReminder)
        if (normalizedOccurrence == null) json.remove("occurrence_id") else json.put("occurrence_id", normalizedOccurrence)
        return copy(original = json.toString())
    }

    internal fun encode(): JSONObject {
        val result = original?.let(::JSONObject) ?: JSONObject()
        result.put("id", id).put("type", type)
        if (editable) {
            result.put("text", text)
            if (type == "checkbox") result.put("checked", checked)
        }
        return result
    }

    companion object {
        private val mediaTypes = setOf("image", "audio", "video")
        private val ISO_DATE_PATTERN = Regex("""\d{4}-\d{2}-\d{2}""")

        internal fun attachment(type: String, attachmentId: String): NoteBlock {
            require(type in mediaTypes)
            return decode(JSONObject().put("id", newId()).put("type", type).put("attachment_id", attachmentId))
        }

        fun paragraph(text: String = "") = NoteBlock(newId(), "paragraph", text, false, null)

        @JvmOverloads
        fun checkbox(
            text: String = "",
            checked: Boolean = false,
            dueDate: String? = null,
            reminderId: String? = null,
            occurrenceId: String? = null,
        ): NoteBlock {
            val base = NoteBlock(newId(), "checkbox", text, checked, null)
            return if (dueDate == null && reminderId == null && occurrenceId == null) base
            else base.withTaskSchedule(dueDate = dueDate, reminderId = reminderId, occurrenceId = occurrenceId)
        }

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
            val json = JSONObject()
                .put("id", id)
                .put("type", "reference")
                .put("target_note_id", noteId)
                .put("target_revision", revision)
                .put("label", label)
            val normalizedBlockId = targetBlockId?.trim()?.takeIf { it.isNotEmpty() }
            if (normalizedBlockId != null) {
                json.put("target_block_id", normalizedBlockId)
            }
            if (start != null && end != null) {
                require(start >= 0 && end >= start) { "Invalid reference range [$start, $end)" }
                json.put("start", start).put("end", end)
            }
            return decode(json)
        }
        internal fun decode(value: JSONObject) = NoteBlock(
            value.getString("id"), value.getString("type"), value.optString(if (value.optString("type") == "reference") "label" else "text"),
            value.optBoolean("checked"), value.toString(),
        )
    }
}

data class NoteDocument(val blocks: List<NoteBlock>) {
    init {
        require(blocks.isNotEmpty()) { "A document requires at least one block" }
        require(blocks.map { it.id }.toSet().size == blocks.size) { "Duplicate block ID" }
    }

    val hasContent: Boolean get() = blocks.any { it.text.isNotBlank() || !it.editable }
    val text: String get() = blocks.joinToString("\n") { it.text }

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

    fun encode(): String = JSONObject()
        .put("document_schema_version", 1)
        .put("blocks", JSONArray().apply { blocks.forEach { put(it.encode()) } })
        .toString()

    companion object {
        fun empty() = NoteDocument(listOf(NoteBlock.paragraph()))

        fun decode(json: String): NoteDocument {
            val document = JSONObject(json)
            require(document.getInt("document_schema_version") == 1) { "Unsupported document version" }
            val blocks = document.getJSONArray("blocks")
            return NoteDocument(List(blocks.length()) { NoteBlock.decode(blocks.getJSONObject(it)) })
        }
    }
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
