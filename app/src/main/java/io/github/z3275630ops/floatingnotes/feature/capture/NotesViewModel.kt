package io.github.z3275630ops.floatingnotes.feature.capture

import android.app.Application
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.z3275630ops.floatingnotes.core.model.NoteBlock
import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import io.github.z3275630ops.floatingnotes.data.local.DraftEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteEntity
import io.github.z3275630ops.floatingnotes.data.local.NotesDatabase
import io.github.z3275630ops.floatingnotes.data.local.NotesRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn

internal class EditorBlock(private val original: NoteBlock) {
    val id = original.id
    val type = original.type
    val editable = original.editable
    val text = TextFieldState(original.text)
    var checked by mutableStateOf(original.checked)

    fun documentBlock() = if (editable) original.copy(text = text.text.toString(), checked = checked) else original
}

internal class EditorSession(val draft: DraftEntity, document: NoteDocument, val existingNote: Boolean) {
    val blocks = mutableStateListOf<EditorBlock>().apply { addAll(document.blocks.map(::EditorBlock)) }
    var status by mutableStateOf(if (draft.editSeq > 0) "草稿已恢复" else "")
    var writing by mutableStateOf(false)
    var checkpointSeq = draft.editSeq
    var committedSeq = if (existingNote && draft.editSeq > 0) -1 else draft.editSeq
    private var sequence = draft.editSeq
    private var trackedDocument = document

    fun document() = NoteDocument(blocks.map { it.documentBlock() })

    fun sequenceFor(document: NoteDocument): Long {
        if (document != trackedDocument) {
            trackedDocument = document
            sequence++
        }
        return sequence
    }

    fun addCheckbox() {
        blocks.add(EditorBlock(NoteBlock.checkbox()))
        blocks.add(EditorBlock(NoteBlock.paragraph()))
    }
}

internal enum class IncomingCapture { Opened, AlreadySaved, Failed }

internal class NotesViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = NotesRepository(NotesDatabase.open(application))
    var editor by mutableStateOf<EditorSession?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var opening by mutableStateOf(false)
        private set
    val notes = repository.notes.catch {
        error = "无法读取本机笔记"
        emit(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val captureDrafts = repository.captureDrafts.catch {
        error = "无法读取跨应用草稿"
        emit(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var watcher: Job? = null
    private var stopCheckpoint: Job? = null

    suspend fun openNote(noteId: String? = null, captureSlot: String? = null): Boolean {
        if (editor != null) return true
        if (opening) return false
        opening = true
        return try {
            val slot = captureSlot ?: noteId?.let { "note:$it" } ?: "manual"
            val note = noteId?.let {
                repository.loadNote(it)?.takeIf { item -> item.deletedAt == null }
                    ?: throw IllegalStateException("Missing note")
            }
            val draft = repository.loadDraft(slot) ?: if (captureSlot == null) repository.newDraft(slot, note)
                else throw IllegalStateException("Missing capture draft")
            startEditor(draft, note != null)
            error = null
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "无法打开笔记，原有内容仍在本机"
            false
        } finally {
            opening = false
        }
    }

    suspend fun openIncomingCapture(id: String, body: String, sourceUrl: String?): IncomingCapture {
        if (editor?.draft?.id == id) return IncomingCapture.Opened
        return try {
            if (repository.loadNote(id) != null) return IncomingCapture.AlreadySaved
            val slot = "capture:$id"
            val restored = repository.loadDraft(slot)
            val draft = restored ?: repository.newDraft(slot).copy(
                id = id,
                noteId = id,
                documentJson = withContext(Dispatchers.Default) {
                    NoteDocument(listOf(NoteBlock.paragraph(body))).encode()
                },
                editSeq = 1,
                sourceType = "share",
                sourceUrl = sourceUrl,
            )
            val checkpointed = try {
                repository.checkpoint(draft)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            startEditor(draft, existingNote = false)
            editor?.status = when {
                !checkpointed -> "草稿写入失败，正文仍保留。可重试保存。"
                restored != null -> "草稿已恢复"
                else -> "草稿已存到本机"
            }
            IncomingCapture.Opened
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            IncomingCapture.Failed
        }
    }

    private fun startEditor(draft: DraftEntity, existingNote: Boolean) {
        val session = EditorSession(draft, NoteDocument.decode(draft.documentJson), existingNote)
        editor = session
        watch(session)
    }

    private fun watch(session: EditorSession) {
        watcher?.cancel()
        watcher = viewModelScope.launch {
            snapshotFlow { session.document() }.collectLatest { document ->
                val sequence = session.sequenceFor(document)
                val needsCommit = session.existingNote && sequence > session.committedSeq && document.hasContent
                if (sequence <= session.checkpointSeq && !needsCommit) return@collectLatest
                session.status = "等待写入"
                delay(180)
                try {
                    val draft = encode(session, document, sequence)
                    repository.checkpoint(draft)
                    session.checkpointSeq = sequence
                    session.status = "草稿已存到本机"
                    if (needsCommit) {
                        delay(350)
                        session.status = "正在保存"
                        repository.commit(draft)
                        session.committedSeq = sequence
                        session.status = "已保存到本机"
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    session.status = "保存失败，正文仍保留"
                }
            }
        }
    }

    suspend fun finishEditor(save: Boolean = false, discard: Boolean = false): Boolean {
        val session = editor ?: return true
        if (session.writing) return false
        session.writing = true
        val previousWatcher = watcher
        return try {
            previousWatcher?.cancelAndJoin()
            stopCheckpoint?.cancelAndJoin()
            val document = session.document()
            val sequence = session.sequenceFor(document)
            val draft = encode(session, document, sequence)
            when {
                discard -> repository.discard(draft)
                save || (session.existingNote && document.hasContent && sequence > session.committedSeq) -> {
                    session.status = "正在保存"
                    repository.commit(draft)
                    session.committedSeq = sequence
                }
                sequence > session.checkpointSeq -> repository.checkpoint(draft)
            }
            if (!discard && session.document() != document) {
                watch(session)
                return false
            }
            editor = null
            true
        } catch (cancelled: CancellationException) {
            if (editor == session) watch(session)
            throw cancelled
        } catch (_: Exception) {
            session.status = "保存失败，正文仍保留。可重试。"
            false
        } finally {
            session.writing = false
        }
    }

    fun checkpointOnStop() {
        val session = editor ?: return
        if (session.writing) return
        val document = session.document()
        val sequence = session.sequenceFor(document)
        if (sequence <= session.checkpointSeq) return
        stopCheckpoint?.cancel()
        stopCheckpoint = viewModelScope.launch {
            try {
                repository.checkpoint(encode(session, document, sequence))
                session.checkpointSeq = maxOf(session.checkpointSeq, sequence)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                session.status = "草稿写入失败，正文仍保留"
            }
        }
    }

    fun pin(note: NoteEntity) = changeMetadata { repository.setPinned(note.id, !note.pinned) }
    fun archive(note: NoteEntity) = changeMetadata { repository.setArchived(note.id, note.archivedAt == null) }

    private fun changeMetadata(change: suspend () -> Unit) = viewModelScope.launch {
        try {
            change()
            error = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "更新失败，可重试"
        }
    }

    private suspend fun encode(session: EditorSession, document: NoteDocument, sequence: Long): DraftEntity =
        withContext(Dispatchers.Default) {
            session.draft.copy(documentJson = document.encode(), editSeq = sequence)
        }
}
