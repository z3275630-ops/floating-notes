package io.github.z3275630ops.floatingnotes.core.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class NoteBlock internal constructor(
    val id: String,
    val type: String,
    val text: String,
    val checked: Boolean,
    private val original: String?,
) {
    val referenceTarget: String?
        get() = if (type == "reference") original?.let { JSONObject(it).optString("target_note_id") }?.takeIf { it.isNotBlank() } else null

    val referenceRevision: Long?
        get() = if (type == "reference") original?.let { JSONObject(it).optLong("target_revision") }?.takeIf { it > 0 } else null

    val editable: Boolean
        get() = (type == "paragraph" || type == "checkbox") &&
            original?.let { (JSONObject(it).optJSONArray("marks")?.length() ?: 0) == 0 } != false

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
        fun paragraph(text: String = "") = NoteBlock(newId(), "paragraph", text, false, null)
        fun checkbox(text: String = "") = NoteBlock(newId(), "checkbox", text, false, null)
        fun reference(noteId: String, revision: Long, label: String): NoteBlock {
            val id = newId()
            return decode(JSONObject().put("id", id).put("type", "reference")
                .put("target_note_id", noteId).put("target_revision", revision).put("label", label))
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

internal fun newId(): String = UUID.randomUUID().toString()
