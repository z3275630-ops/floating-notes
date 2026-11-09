package io.github.z3275630ops.floatingnotes.data.local

import androidx.room.withTransaction
import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import io.github.z3275630ops.floatingnotes.core.model.newId
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder

internal class StaleDraftException : IllegalStateException("The draft was superseded")
internal class EmptyNoteException : IllegalArgumentException("The note is blank")

internal class NotesRepository(private val database: NotesDatabase) {
    private val dao = database.notes()
    val notes = dao.observeNotes()

    suspend fun loadNote(id: String): NoteEntity? = dao.note(id)
    suspend fun loadDraft(slot: String): DraftEntity? = dao.draft(slot)

    fun newDraft(slot: String, note: NoteEntity? = null): DraftEntity = DraftEntity(
        id = newId(),
        slot = slot,
        noteId = note?.id ?: newId(),
        baseRevision = note?.revision ?: 0,
        documentJson = note?.documentJson ?: NoteDocument.empty().encode(),
        editSeq = 0,
        updatedAt = now(),
        sourceType = note?.sourceType ?: "manual",
        sourceUrl = note?.sourceUrl,
    )

    suspend fun checkpoint(draft: DraftEntity) = database.withTransaction {
        val note = dao.note(draft.noteId)
        if (note?.lastDraftId == draft.id && note.committedSeq >= draft.editSeq) return@withTransaction
        verifyRevision(draft, note)
        val stored = dao.draft(draft.slot)
        if (stored != null && stored.id != draft.id) throw StaleDraftException()
        if (stored != null && stored.editSeq == draft.editSeq && stored.documentJson != draft.documentJson) {
            throw StaleDraftException()
        }
        if (stored == null || draft.editSeq > stored.editSeq) dao.putDraft(draft)
    }

    suspend fun discard(draft: DraftEntity) = dao.deleteDraft(draft.id, draft.editSeq)

    suspend fun commit(draft: DraftEntity): NoteEntity = database.withTransaction {
        val previous = dao.note(draft.noteId)
        if (previous != null && previous.lastDraftId == draft.id && previous.committedSeq >= draft.editSeq) {
            if (previous.committedSeq == draft.editSeq && previous.documentJson != draft.documentJson) {
                throw StaleDraftException()
            }
            return@withTransaction previous
        }

        verifyRevision(draft, previous)
        val stored = dao.draft(draft.slot)
        if (stored != null && (stored.id != draft.id || stored.editSeq > draft.editSeq)) throw StaleDraftException()
        val document = NoteDocument.decode(draft.documentJson)
        if (!document.hasContent) throw EmptyNoteException()

        val time = now()
        val updated = if (previous == null) {
            NoteEntity(
                id = draft.noteId,
                title = null,
                documentJson = draft.documentJson,
                searchText = document.text,
                sourceType = draft.sourceType,
                sourceUrl = draft.sourceUrl,
                createdAt = time,
                updatedAt = time,
                revision = 1,
                contentHash = hash(draft.documentJson),
                lastDraftId = draft.id,
                committedSeq = draft.editSeq,
            )
        } else {
            previous.copy(
                documentJson = draft.documentJson,
                searchText = document.text,
                updatedAt = time,
                revision = previous.revision + 1,
                contentHash = hash(draft.documentJson),
                lastDraftId = draft.id,
                committedSeq = draft.editSeq,
            )
        }
        writeRevision(updated, "edit")
        dao.deleteTasks(updated.id)
        dao.addTasks(document.blocks.filter { it.type == "checkbox" }.map { block ->
            val encoded = block.encode()
            TaskIndexEntity(updated.id, block.id, block.checked,
                encoded.optString("due_date").ifBlank { null },
                encoded.optString("occurrence_id").ifBlank { null })
        })
        dao.deleteDraft(draft.id, draft.editSeq)
        updated
    }

    suspend fun setPinned(id: String, pinned: Boolean) =
        changeMetadata(id) { if (it.pinned == pinned) it else it.copy(pinned = pinned) }

    suspend fun setArchived(id: String, archived: Boolean) =
        changeMetadata(id) {
            if ((it.archivedAt != null) == archived) it
            else it.copy(archivedAt = if (archived) now() else null)
        }

    private suspend fun changeMetadata(id: String, change: (NoteEntity) -> NoteEntity) = database.withTransaction {
        val previous = dao.note(id) ?: return@withTransaction
        check(previous.deletedAt == null)
        val updated = change(previous)
        if (updated != previous) writeRevision(
            updated.copy(revision = previous.revision + 1, updatedAt = now()), "metadata",
        )
    }

    private suspend fun writeRevision(note: NoteEntity, origin: String) {
        dao.putNote(note)
        dao.addVersion(NoteVersionEntity(note.id, note.revision, note.documentJson, origin, note.updatedAt))
        val payload = JSONObject()
            .put("note_id", note.id)
            .put("revision", note.revision)
            .put("document", JSONObject(note.documentJson))
            .put("source_type", note.sourceType)
            .put("source_url", note.sourceUrl ?: JSONObject.NULL)
            .put("pinned", note.pinned)
            .put("archived_at", note.archivedAt ?: JSONObject.NULL)
            .toString()
        dao.addOutbox(OutboxEntity(newId(), note.id, note.revision, note.serverRevision, payload, note.updatedAt))
    }

    private fun verifyRevision(draft: DraftEntity, note: NoteEntity?) {
        if (note?.deletedAt != null ||
            ((note?.revision ?: 0) != draft.baseRevision && note?.lastDraftId != draft.id)
        ) throw StaleDraftException()
    }

    private fun now() = timestamp.format(Instant.now())

    private val timestamp = DateTimeFormatterBuilder().appendInstant(3).toFormatter()

    private fun hash(document: String): String =
        MessageDigest.getInstance("SHA-256").digest(document.toByteArray()).joinToString("") { "%02x".format(it) }
}
