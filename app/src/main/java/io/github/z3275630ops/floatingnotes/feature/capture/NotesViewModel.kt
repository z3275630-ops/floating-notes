package io.github.z3275630ops.floatingnotes.feature.capture

import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import java.io.File
import android.net.Uri
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.z3275630ops.floatingnotes.core.model.NoteBlock
import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import io.github.z3275630ops.floatingnotes.core.model.newId
import io.github.z3275630ops.floatingnotes.data.local.AttachmentImportException
import io.github.z3275630ops.floatingnotes.data.local.AttachmentKind
import io.github.z3275630ops.floatingnotes.data.local.AttachmentStore
import io.github.z3275630ops.floatingnotes.data.local.DraftEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteEntity
import io.github.z3275630ops.floatingnotes.data.local.NotesDatabase
import io.github.z3275630ops.floatingnotes.data.local.NotesRepository
import io.github.z3275630ops.floatingnotes.data.local.OrganizationRepository
import io.github.z3275630ops.floatingnotes.data.local.SearchRepository
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

    val attachmentId get() = original.attachmentId
    val referenceTarget get() = original.referenceTarget

    fun documentBlock() = if (editable) original.copy(text = text.text.toString(), checked = checked) else original
}

internal class EditorSession(val draft: DraftEntity, document: NoteDocument, val existingNote: Boolean) {
    val blocks = mutableStateListOf<EditorBlock>().apply { addAll(document.blocks.map(::EditorBlock)) }
    var status by mutableStateOf(if (draft.editSeq > 0) "草稿已恢复" else "")
    var writing by mutableStateOf(false)
    var closing = false
    var recording by mutableStateOf(false)
    var recordingPaused by mutableStateOf(false)
    var recordingMillis by mutableLongStateOf(0L)
    var voiceSaving by mutableStateOf(false)
    var voicePending by mutableStateOf(false)
    var voiceStatus by mutableStateOf<String?>(null)
    val voiceBusy get() = recording || voiceSaving
    var importing by mutableStateOf(false)
    var importStatus by mutableStateOf<String?>(null)
    var importFailed by mutableStateOf(false)
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

    fun addReference(note: NoteEntity) {
        if (writing || note.id == draft.noteId) return
        blocks.add(EditorBlock(NoteBlock.reference(note.id, note.revision, referenceLabel(note))))
        blocks.add(EditorBlock(NoteBlock.paragraph()))
    }

    fun insertTranscript(block: EditorBlock, text: String) {
        if (writing || closing) return
        val position = blocks.indexOf(block)
        if (position >= 0) blocks.add(position + 1, EditorBlock(NoteBlock.paragraph(text)))
    }

    fun removeAttachment(block: EditorBlock) {
        if (writing || block.attachmentId == null) return
        blocks.remove(block)
        if (blocks.isEmpty()) blocks.add(EditorBlock(NoteBlock.paragraph()))
    }

    fun removeReference(block: EditorBlock) {
        if (writing || block.referenceTarget == null) return
        blocks.remove(block)
        if (blocks.isEmpty()) blocks.add(EditorBlock(NoteBlock.paragraph()))
    }

    fun addCheckbox() {
        blocks.add(EditorBlock(NoteBlock.checkbox()))
        blocks.add(EditorBlock(NoteBlock.paragraph()))
    }
}

internal enum class IncomingCapture { Opened, AlreadySaved, Failed }

internal class NotesViewModel(application: Application) : AndroidViewModel(application) {
    private val database = NotesDatabase.open(application)
    private val repository = NotesRepository(database)
    val attachments = AttachmentStore(application)
    val organization = OrganizationRepository(database)
    val search = SearchRepository(database)
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
    private var importJob: Job? = null
    private var voiceRecorder: VoiceRecorder? = null
    private var voiceClock: Job? = null
    private var foreground = false

    fun onForeground() {
        foreground = true
    }

    fun startRecording(session: EditorSession) = viewModelScope.launch {
        if (editor !== session || session.writing || session.importing || session.voiceBusy || session.voicePending || session.closing) return@launch
        val context = getApplication<Application>()
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            session.voiceStatus = "允许麦克风权限后即可录音"
            return@launch
        }
        session.voiceSaving = true
        session.voiceStatus = "正在准备录音"
        val directory = voiceDirectory(session)
        val file = File(directory, "${newId()}.m4a")
        val recorder = VoiceRecorder(context, file) {
            stopRecording(session, "录音已中断，正在保存已录部分")
        }
        try {
            val document = session.document()
            val sequence = session.sequenceFor(document)
            repository.checkpoint(encode(session, document, sequence))
            session.checkpointSeq = maxOf(session.checkpointSeq, sequence)
            if (!foreground || editor !== session) {
                session.voiceStatus = "已离开录音界面，未开启麦克风"
                return@launch
            }
            recorder.start()
            voiceRecorder = recorder
            session.recording = true
            session.recordingMillis = 0
            session.recordingPaused = false
            session.voiceStatus = null
            voiceClock = viewModelScope.launch {
                while (isActive) {
                    val duration = recorder.duration()
                    session.recordingMillis = duration
                    if (duration >= VoiceRecorder.MAX_DURATION_MS) {
                        stopRecording(session, "录音已达 10 分钟，正在保存")
                        break
                    }
                    delay(250)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            session.voiceStatus = "无法开始录音，请检查麦克风和本机存储后重试"
        } finally {
            session.voiceSaving = false
        }
    }

    fun toggleRecordingPause(session: EditorSession) {
        if (editor !== session) return
        val recorder = voiceRecorder ?: return
        try {
            recorder.togglePause()
            session.recordingPaused = recorder.paused
        } catch (_: RuntimeException) {
            stopRecording(session, "录音已中断，正在保存已录部分")
        }
    }

    fun stopRecording(session: EditorSession, message: String = "正在保存录音") {
        if (editor !== session) return
        val recorder = voiceRecorder ?: return
        voiceRecorder = null
        voiceClock?.cancel()
        session.recording = false
        if (!recorder.stop() && recorder.file.length() == 0L) {
            recorder.file.delete()
            session.voiceStatus = "没有录到可保存的音频，请重试"
            return
        }
        session.voiceStatus = message
        saveRecording(session, recorder.file)
    }

    fun retryRecording(session: EditorSession) {
        if (editor !== session || session.voiceBusy || session.writing) return
        val file = pendingRecording(session) ?: return
        saveRecording(session, file)
    }

    fun discardPendingRecording(session: EditorSession) {
        if (editor !== session || session.voiceBusy || session.writing) return
        val file = pendingRecording(session) ?: return
        if (file.delete()) {
            session.voicePending = false
            session.voiceStatus = "已移除未保存的录音"
        } else session.voiceStatus = "无法移除未保存的录音，请重试"
    }

    private fun saveRecording(session: EditorSession, file: File) {
        session.voiceSaving = true
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val id = file.nameWithoutExtension
                if (session.blocks.none { it.attachmentId == id }) {
                    val context = getApplication<Application>()
                    val uri = FileProvider.getUriForFile(
                        context, "${context.packageName}.attachments", file, "录音.m4a",
                    )
                    attachments.recover(database)
                    val attachment = attachments.import(uri, "audio/mp4", id)
                    addImportedBlock(session, NoteBlock.attachment("audio", attachment.id))
                }
                withContext(Dispatchers.IO) {
                    if (file.delete()) file.parentFile?.delete()
                }
                session.voicePending = false
                session.voiceStatus = "录音已存到草稿"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                session.voicePending = true
                session.voiceStatus = "录音保留在本机 · ${AttachmentStore.failureMessage(error)}"
            } finally {
                session.voiceSaving = false
            }
        }
    }

    private fun voiceDirectory(session: EditorSession) =
        File(getApplication<Application>().filesDir, "recordings/${session.draft.id}")

    private fun pendingRecording(session: EditorSession): File? =
        voiceDirectory(session).listFiles()?.firstOrNull { it.extension == "m4a" && it.isFile }

    override fun onCleared() {
        voiceRecorder?.stop()
        super.onCleared()
    }

    init {
        viewModelScope.launch {
            try {
                attachments.recover(database)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Retry recovery on import. Existing notes remain available.
            }
        }
    }

    fun importAttachments(session: EditorSession, selected: List<Uri>, mimeHint: String? = null) {
        if (selected.isEmpty() || editor !== session || session.writing || session.closing || session.importing || session.voiceBusy) return
        if (selected.size > 10) {
            session.importFailed = true
            session.importStatus = "一次最多选择 10 个附件，请重新选择"
            return
        }
        session.importing = true
        session.importFailed = false
        session.importStatus = "正在准备附件"
        val uris = selected.distinct()
        importJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var imported = 0
            var failure: String? = null
            try {
                attachments.recover(database)
                for ((index, uri) in uris.withIndex()) {
                    val label = "导入 ${index + 1}/${uris.size}"
                    session.importStatus = "$label · 正在读取"
                    try {
                        val attachment = attachments.import(uri, mimeHint) { copied, total ->
                            withContext(Dispatchers.Main.immediate) {
                                session.importStatus = if (total != null && copied >= total) "$label · 正在校验"
                                    else if (total != null) "$label · ${copied * 100 / total}%"
                                    else "$label · ${copied / 1024} KB"
                            }
                        }
                        val kind = requireNotNull(AttachmentKind.fromMime(attachment.mime))
                        val block = NoteBlock.attachment(kind.blockType, attachment.id)
                        addImportedBlock(session, block)
                        imported++
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        failure = AttachmentStore.failureMessage(error)
                    }
                }
                session.importFailed = failure != null
                session.importStatus = failure?.let { "已导入 $imported 个 · $it" } ?: "已导入 $imported 个附件"
            } catch (cancelled: CancellationException) {
                session.importStatus = "导入已取消 · 已保留 $imported 个附件"
                throw cancelled
            } catch (error: Exception) {
                session.importFailed = true
                session.importStatus = AttachmentStore.failureMessage(error)
            } finally {
                session.importing = false
            }
        }
    }

    fun cancelImport(session: EditorSession) {
        if (editor === session) importJob?.cancel()
    }

    private suspend fun addImportedBlock(session: EditorSession, block: NoteBlock) = withContext(NonCancellable) {
        session.writing = true
        try {
            watcher?.cancelAndJoin()
            stopCheckpoint?.cancelAndJoin()
            val document = NoteDocument(session.document().blocks + block)
            val sequence = session.sequenceFor(document)
            try {
                repository.checkpoint(encode(session, document, sequence))
            } catch (_: Exception) {
                throw AttachmentImportException(
                    "无法保存附件到草稿，请检查本机空间后重试",
                )
            }
            session.blocks.add(EditorBlock(block))
            session.checkpointSeq = sequence
        } finally {
            session.writing = session.closing
            if (!session.closing) watch(session)
        }
    }

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
        val pending = pendingRecording(session)
        if (pending != null && session.blocks.any { it.attachmentId == pending.nameWithoutExtension }) pending.delete()
        session.voicePending = pendingRecording(session) != null
        if (session.voicePending) session.voiceStatus = "有一段本机录音待保存"
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
        if (session.closing || session.voiceSaving) return false
        if (session.recording) {
            stopRecording(session)
            return false
        }
        if (session.voicePending) {
            session.voiceStatus = "请先保存或移除待处理的录音"
            return false
        }
        session.closing = true
        session.writing = true
        return try {
            importJob?.cancelAndJoin()
            session.writing = true
            watcher?.cancelAndJoin()
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
            watch(session)
            false
        } finally {
            session.closing = false
            session.writing = false
        }
    }

    fun checkpointOnStop() {
        foreground = false
        val session = editor ?: return
        if (session.recording) stopRecording(session, "已离开录音界面，正在保存已录部分")
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
