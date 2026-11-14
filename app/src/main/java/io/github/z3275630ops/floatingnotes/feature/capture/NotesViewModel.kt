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
import io.github.z3275630ops.floatingnotes.data.local.RecurrenceRule
import io.github.z3275630ops.floatingnotes.data.local.ReminderEntity
import io.github.z3275630ops.floatingnotes.data.local.ReminderPolicy
import io.github.z3275630ops.floatingnotes.data.local.ReminderStatus
import io.github.z3275630ops.floatingnotes.data.local.SearchRepository
import io.github.z3275630ops.floatingnotes.data.local.TaskItemProjection
import io.github.z3275630ops.floatingnotes.data.local.TaskPreferences
import io.github.z3275630ops.floatingnotes.data.local.TaskScheduleResult
import io.github.z3275630ops.floatingnotes.data.local.TaskToggleResult
import io.github.z3275630ops.floatingnotes.data.local.TimeReconcileResult
import io.github.z3275630ops.floatingnotes.feature.tasks.ReminderScheduler
import io.github.z3275630ops.floatingnotes.feature.tasks.TaskBucket
import io.github.z3275630ops.floatingnotes.feature.tasks.TaskRecurrence
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneId

internal class EditorBlock(private val original: NoteBlock) {
    val id = original.id
    val type = original.type
    val editable = original.editable
    val text = TextFieldState(original.text)
    var checked by mutableStateOf(original.checked)
    var dueDate by mutableStateOf(original.dueDate)
    var reminderId by mutableStateOf(original.reminderId)
    var occurrenceId by mutableStateOf(original.occurrenceId)
    var reminderLocalTime by mutableStateOf<String?>(null)
    var recurrenceRule by mutableStateOf(RecurrenceRule.NONE)
    var reminderPolicy by mutableStateOf(ReminderPolicy.FOLLOW_LOCAL)
    var reminderStatus by mutableStateOf<ReminderStatus?>(null)
    var pendingReminderTime by mutableStateOf<String?>(null)
    var pendingScheduleChanged by mutableStateOf(false)

    val attachmentId get() = original.attachmentId
    val referenceTarget get() = original.referenceTarget
    val referenceTargetBlockId get() = original.referenceTargetBlockId
    val referenceStart get() = original.referenceStart
    val referenceEnd get() = original.referenceEnd

    fun documentBlock(): NoteBlock = when {
        type == "checkbox" -> {
            val base = original.copy(text = text.text.toString(), checked = checked)
            if (dueDate == original.dueDate && reminderId == original.reminderId && occurrenceId == original.occurrenceId) {
                base
            } else {
                base.withTaskSchedule(dueDate = dueDate, reminderId = reminderId, occurrenceId = occurrenceId)
            }
        }
        editable -> original.copy(text = text.text.toString(), checked = checked)
        else -> original
    }
}

internal class EditorSession(initialDraft: DraftEntity, document: NoteDocument, val existingNote: Boolean) {
    var draft by mutableStateOf(initialDraft)
    val blocks = mutableStateListOf<EditorBlock>().apply { addAll(document.blocks.map(::EditorBlock)) }
    val pendingTagIds = mutableStateListOf<String>()
    var focusBlockId by mutableStateOf<String?>(null)
    var status by mutableStateOf(if (initialDraft.editSeq > 0) "草稿已恢复" else "")
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
    var checkpointSeq = initialDraft.editSeq
    var committedSeq = if (existingNote && initialDraft.editSeq > 0) -1 else initialDraft.editSeq
    private var sequence = initialDraft.editSeq
    private var trackedDocument = document

    fun document() = NoteDocument(blocks.map { it.documentBlock() })

    fun sequenceFor(document: NoteDocument): Long {
        if (document != trackedDocument) {
            trackedDocument = document
            sequence++
        }
        return sequence
    }

    fun syncAfterExternalCommit(newRevision: Long) {
        draft = draft.copy(baseRevision = newRevision)
        trackedDocument = document()
        checkpointSeq = sequence
        committedSeq = sequence
    }

    fun addPendingTag(tagId: String) {
        if (tagId !in pendingTagIds) pendingTagIds.add(tagId)
    }

    fun removePendingTag(tagId: String) {
        pendingTagIds.remove(tagId)
    }

    fun addReference(
        note: NoteEntity,
        targetBlockId: String? = null,
        start: Int? = null,
        end: Int? = null,
        labelOverride: String? = null,
    ) {
        if (writing || note.id == draft.noteId) return
        val label = labelOverride?.takeIf { it.isNotBlank() } ?: referenceLabel(note)
        blocks.add(
            EditorBlock(
                NoteBlock.reference(
                    noteId = note.id,
                    revision = note.revision,
                    label = label,
                    targetBlockId = targetBlockId,
                    start = start,
                    end = end,
                ),
            ),
        )
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
    internal val repository = NotesRepository(database)
    val taskPreferences = TaskPreferences(application)
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

    fun observeTaskItems(): Flow<List<TaskItemProjection>> = repository.observeTaskItems()

    val taskItems: StateFlow<List<TaskItemProjection>> = repository.observeTaskItems().catch {
        error = "无法读取待办事项"
        emit(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pendingTasksCount: StateFlow<Int> = taskItems
        .map { items -> items.count { !it.checked } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val todoFilterNoteIds: StateFlow<Set<String>> = taskItems
        .map { items -> items.mapTo(hashSetOf()) { it.noteId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val noteDueBadges: StateFlow<Map<String, String>> = taskItems
        .map(::buildNoteDueBadges)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val persistenceMutex = Mutex()
    private var watcher: Job? = null
    private var stopCheckpoint: Job? = null
    private var importJob: Job? = null
    private var voiceRecorder: VoiceRecorder? = null
    private var voiceClock: Job? = null
    private var foreground = false
    private var pendingFocusBlockId: String? = null

    fun queueFocusBlock(blockId: String?) {
        pendingFocusBlockId = blockId
    }

    fun onForeground() {
        foreground = true
        viewModelScope.launch {
            runCatching { reconcileTimeChange() }
        }
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
        persistenceMutex.withLock {
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
                if (!session.closing && editor === session) watch(session)
            }
        }
    }

    suspend fun openNote(
        noteId: String? = null,
        captureSlot: String? = null,
        focusBlockId: String? = null,
    ): Boolean {
        val resolvedFocusBlockId = focusBlockId ?: pendingFocusBlockId.also { pendingFocusBlockId = null }
        val current = editor
        if (current != null) {
            if (noteId != null && current.draft.noteId == noteId && resolvedFocusBlockId != null) {
                current.focusBlockId = resolvedFocusBlockId
            }
            return true
        }
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
            val preloadedTasks = if (note != null) {
                runCatching { repository.observeTaskItems().first() }.getOrDefault(taskItems.value)
            } else {
                emptyList()
            }
            startEditor(draft, note != null, resolvedFocusBlockId, preloadedTasks)
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

    private fun startEditor(
        draft: DraftEntity,
        existingNote: Boolean,
        focusBlockId: String? = null,
        preloadedTasks: List<TaskItemProjection> = emptyList(),
    ) {
        val session = EditorSession(draft, NoteDocument.decode(draft.documentJson), existingNote).apply {
            this.focusBlockId = focusBlockId
        }
        val sourceItems = if (preloadedTasks.isNotEmpty()) preloadedTasks else taskItems.value
        val itemsByBlock = sourceItems
            .filter { it.noteId == draft.noteId }
            .associateBy(TaskItemProjection::blockId)
        session.blocks.forEach { block ->
            if (block.type == "checkbox") {
                itemsByBlock[block.id]?.let { item ->
                    block.dueDate = item.dueDate ?: block.dueDate
                    block.reminderId = item.reminderId ?: block.reminderId
                    block.occurrenceId = item.occurrenceId ?: block.occurrenceId
                    block.reminderLocalTime = item.reminderLocalTime
                    block.recurrenceRule = item.recurrence
                    block.reminderPolicy = item.policy
                    block.reminderStatus = item.reminderStatus
                }
            }
        }
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
                    persistenceMutex.withLock {
                        if (session.closing || editor !== session) return@withLock
                        if (sequence > session.checkpointSeq) {
                            val checkpointDraft = encode(session, document, sequence)
                            repository.checkpoint(checkpointDraft)
                            session.checkpointSeq = maxOf(session.checkpointSeq, sequence)
                            session.status = "草稿已存到本机"
                        }
                    }
                    if (needsCommit) {
                        delay(350)
                        persistenceMutex.withLock {
                            if (session.closing || editor !== session || sequence <= session.committedSeq) {
                                return@withLock
                            }
                            withContext(NonCancellable) {
                                session.status = "正在保存"
                                val commitDraft = encode(session, document, sequence)
                                val committed = repository.commit(commitDraft)
                                session.draft = session.draft.copy(baseRevision = committed.revision)
                                session.committedSeq = maxOf(session.committedSeq, sequence)
                                commitSessionExtras(session)
                                session.status = "已保存到本机"
                            }
                        }
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
        if (session.closing || session.voiceSaving || session.writing) return false
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
            val preCommitEditableSnapshot = session.blocks.map { it.id to it.text.text.toString() }
            val sequence = session.sequenceFor(document)
            val draft = encode(session, document, sequence)
            var didCommit = false
            persistenceMutex.withLock {
                when {
                    discard -> repository.discard(draft)
                    save || (session.existingNote && document.hasContent && sequence > session.committedSeq) -> {
                        session.status = "正在保存"
                        val committed = repository.commit(draft)
                        session.draft = session.draft.copy(baseRevision = committed.revision)
                        session.committedSeq = maxOf(session.committedSeq, sequence)
                        didCommit = true
                    }
                    sequence > session.checkpointSeq -> {
                        repository.checkpoint(draft)
                        session.checkpointSeq = maxOf(session.checkpointSeq, sequence)
                    }
                }
                if (!discard && didCommit) {
                    commitSessionExtras(session)
                }
            }
            val editedDuringCommit = !discard &&
                session.blocks.map { it.id to it.text.text.toString() } != preCommitEditableSnapshot
            if (editedDuringCommit) {
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

    private suspend fun commitSessionExtras(session: EditorSession) {
        val pendingTags = session.pendingTagIds.toList()
        if (pendingTags.isNotEmpty()) {
            for (tagId in pendingTags) {
                runCatching { organization.tagNote(session.draft.noteId, tagId, attach = true) }
                session.pendingTagIds.remove(tagId)
            }
        }
        val app = getApplication<Application>()
        val zoneId = ZoneId.systemDefault()
        for (block in session.blocks) {
            if (!block.pendingScheduleChanged || block.type != "checkbox") continue
            val result = if (block.dueDate != null || block.pendingReminderTime != null) {
                repository.setTaskSchedule(
                    noteId = session.draft.noteId,
                    blockId = block.id,
                    dueDate = block.dueDate,
                    reminderTime = block.pendingReminderTime,
                    zoneId = zoneId,
                    policy = block.reminderPolicy,
                    recurrence = if (block.pendingReminderTime != null) block.recurrenceRule else RecurrenceRule.NONE,
                )
            } else {
                repository.clearTaskSchedule(session.draft.noteId, block.id)
            }
            ReminderScheduler.syncScheduleResult(app, result)
            applyScheduleResultToBlock(block, result)
            session.syncAfterExternalCommit(result.note.revision)
        }
    }

    fun checkpointOnStop() {
        foreground = false
        val session = editor ?: return
        if (session.recording) stopRecording(session, "已离开录音界面，正在保存已录部分")
        if (session.writing || session.closing) return
        stopCheckpoint?.cancel()
        stopCheckpoint = viewModelScope.launch {
            try {
                persistenceMutex.withLock {
                    if (editor !== session || session.writing || session.closing) return@withLock
                    val document = session.document()
                    val sequence = session.sequenceFor(document)
                    if (sequence <= session.checkpointSeq) return@withLock
                    repository.checkpoint(encode(session, document, sequence))
                    session.checkpointSeq = maxOf(session.checkpointSeq, sequence)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                session.status = "草稿写入失败，正文仍保留"
            }
        }
    }

    fun toggleEditorTask(session: EditorSession, block: EditorBlock, checked: Boolean) {
        if (editor !== session || session.writing || session.closing || block.type != "checkbox") return
        val hasPersistedReminder = session.existingNote &&
            !block.pendingScheduleChanged &&
            (block.reminderId != null || block.recurrenceRule != RecurrenceRule.NONE)
        if (!hasPersistedReminder) {
            block.checked = checked
            return
        }
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                persistenceMutex.withLock {
                    if (editor !== session || session.closing) return@withLock
                    session.writing = true
                    try {
                        watcher?.cancelAndJoin()
                        stopCheckpoint?.cancelAndJoin()
                        val document = session.document()
                        val sequence = session.sequenceFor(document)
                        if (document.hasContent && sequence > session.committedSeq) {
                            val committed = repository.commit(encode(session, document, sequence))
                            session.draft = session.draft.copy(baseRevision = committed.revision)
                            session.committedSeq = maxOf(session.committedSeq, sequence)
                            commitSessionExtras(session)
                        }
                        val noteRow = repository.loadNote(session.draft.noteId)
                        val blockInDb = noteRow?.let {
                            runCatching { NoteDocument.decode(it.documentJson).findBlock(block.id) }.getOrNull()
                        }
                        if (noteRow != null && blockInDb != null) {
                            val result = toggleTaskChecked(session.draft.noteId, block.id, checked)
                            if (result is TaskToggleResult.Updated) {
                                val syncedBlock = runCatching {
                                    NoteDocument.decode(result.note.documentJson).findBlock(block.id)
                                }.getOrNull()
                                if (syncedBlock != null) {
                                    block.checked = syncedBlock.checked
                                    block.dueDate = syncedBlock.dueDate
                                    block.reminderId = syncedBlock.reminderId
                                    block.occurrenceId = syncedBlock.occurrenceId
                                }
                                block.reminderStatus = result.reminder?.statusEnum ?: block.reminderStatus
                                block.reminderLocalTime = result.reminder?.localTime
                                block.recurrenceRule = result.reminder?.recurrenceEnum ?: block.recurrenceRule
                                block.reminderPolicy = result.reminder?.policyEnum ?: block.reminderPolicy
                                session.syncAfterExternalCommit(result.note.revision)
                                session.status = result.advancedToDate?.let { next ->
                                    "已完成本次重复任务，已推进至 $next"
                                } ?: "已保存到本机"
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        session.status = "同步待办状态失败，已保留编辑内容"
                    } finally {
                        session.writing = session.closing
                        if (!session.closing && editor === session) watch(session)
                    }
                }
            }
        }
    }

    fun scheduleEditorTask(
        session: EditorSession,
        block: EditorBlock,
        dueDate: String?,
        reminderTime: String?,
        policy: ReminderPolicy,
        recurrence: RecurrenceRule,
    ) {
        if (editor !== session || session.writing || session.closing || block.type != "checkbox") return
        val app = getApplication<Application>()
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                persistenceMutex.withLock {
                    if (editor !== session || session.closing) return@withLock
                    session.writing = true
                    try {
                        watcher?.cancelAndJoin()
                        stopCheckpoint?.cancelAndJoin()
                        val existingNoteRow = repository.loadNote(session.draft.noteId)
                        val doc = session.document()
                        if (existingNoteRow != null && doc.hasContent) {
                            val sequence = session.sequenceFor(doc)
                            if (sequence > session.committedSeq) {
                                val committed = repository.commit(encode(session, doc, sequence))
                                session.draft = session.draft.copy(baseRevision = committed.revision)
                                session.committedSeq = maxOf(session.committedSeq, sequence)
                            }
                        }
                        val noteAfterCommit = repository.loadNote(session.draft.noteId)
                        val blockPersisted = noteAfterCommit?.let {
                            runCatching { NoteDocument.decode(it.documentJson).findBlock(block.id) }.getOrNull()
                        } != null
                        if (blockPersisted) {
                            val result = setTaskSchedule(
                                noteId = session.draft.noteId,
                                blockId = block.id,
                                dueDate = dueDate,
                                reminderTime = reminderTime,
                                policy = policy,
                                recurrence = if (reminderTime != null) recurrence else RecurrenceRule.NONE,
                            )
                            applyScheduleResultToBlock(block, result)
                            session.syncAfterExternalCommit(result.note.revision)
                            session.status = when {
                                reminderTime != null && !ReminderScheduler.capability(app).notificationsEnabled ->
                                    "已保存排期，但提醒未开启"
                                reminderTime != null -> "已设置提醒"
                                dueDate != null -> "已更新截止日期"
                                else -> "已更新待办排期"
                            }
                        } else {
                            block.dueDate = dueDate
                            block.pendingReminderTime = reminderTime
                            block.reminderLocalTime = reminderTime
                            block.recurrenceRule = if (reminderTime != null) recurrence else RecurrenceRule.NONE
                            block.reminderPolicy = policy
                            block.pendingScheduleChanged = true
                            session.status = if (reminderTime != null && !ReminderScheduler.capability(app).notificationsEnabled) {
                                "已暂存排期，但提醒未开启"
                            } else {
                                "已暂存待办排期，保存笔记后生效"
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        session.status = "设置待办排期失败，可重试"
                    } finally {
                        session.writing = session.closing
                        if (!session.closing && editor === session) watch(session)
                    }
                }
            }
        }
    }

    fun clearEditorTaskSchedule(session: EditorSession, block: EditorBlock) {
        if (editor !== session || session.writing || session.closing || block.type != "checkbox") return
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                persistenceMutex.withLock {
                    if (editor !== session || session.closing) return@withLock
                    session.writing = true
                    try {
                        watcher?.cancelAndJoin()
                        stopCheckpoint?.cancelAndJoin()
                        val noteRow = repository.loadNote(session.draft.noteId)
                        val blockPersisted = noteRow?.let {
                            runCatching { NoteDocument.decode(it.documentJson).findBlock(block.id) }.getOrNull()
                        } != null
                        if (blockPersisted) {
                            val result = clearTaskSchedule(session.draft.noteId, block.id)
                            applyScheduleResultToBlock(block, result)
                            session.syncAfterExternalCommit(result.note.revision)
                        } else {
                            block.dueDate = null
                            block.reminderId = null
                            block.occurrenceId = null
                            block.reminderLocalTime = null
                            block.pendingReminderTime = null
                            block.recurrenceRule = RecurrenceRule.NONE
                            block.reminderStatus = null
                            block.pendingScheduleChanged = false
                        }
                        session.status = "已清除日期与提醒"
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        session.status = "清除待办排期失败，可重试"
                    } finally {
                        session.writing = session.closing
                        if (!session.closing && editor === session) watch(session)
                    }
                }
            }
        }
    }

    fun attachTagToSession(session: EditorSession, tagId: String) {
        if (editor !== session || session.closing) return
        viewModelScope.launch {
            persistenceMutex.withLock {
                if (editor !== session || session.closing) return@withLock
                try {
                    val noteExists = repository.loadNote(session.draft.noteId) != null
                    if (noteExists) {
                        organization.tagNote(session.draft.noteId, tagId, attach = true)
                    } else {
                        session.addPendingTag(tagId)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    error = "添加标签失败，可重试"
                }
            }
        }
    }

    fun createAndAttachTag(session: EditorSession, name: String, existingTagId: String? = null) {
        if (editor !== session || session.closing) return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            persistenceMutex.withLock {
                if (editor !== session || session.closing) return@withLock
                try {
                    val tagId = existingTagId ?: organization.createTag(trimmed)
                    val noteExists = repository.loadNote(session.draft.noteId) != null
                    if (noteExists) {
                        organization.tagNote(session.draft.noteId, tagId, attach = true)
                    } else {
                        session.addPendingTag(tagId)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (err: IllegalArgumentException) {
                    error = err.message ?: "无法创建标签"
                } catch (_: Exception) {
                    error = "创建标签失败，可重试"
                }
            }
        }
    }

    suspend fun toggleTaskChecked(noteId: String, blockId: String, checked: Boolean): TaskToggleResult {
        val result = repository.toggleTaskChecked(noteId, blockId, checked)
        if (result is TaskToggleResult.Updated) {
            ReminderScheduler.syncToggleResult(getApplication(), result)
        }
        return result
    }

    suspend fun setTaskSchedule(
        noteId: String,
        blockId: String,
        dueDate: String?,
        reminderTime: String? = null,
        policy: ReminderPolicy = ReminderPolicy.FOLLOW_LOCAL,
        recurrence: RecurrenceRule = RecurrenceRule.NONE,
    ): TaskScheduleResult {
        val result = repository.setTaskSchedule(
            noteId = noteId,
            blockId = blockId,
            dueDate = dueDate,
            reminderTime = reminderTime,
            zoneId = ZoneId.systemDefault(),
            policy = policy,
            recurrence = recurrence,
        )
        ReminderScheduler.syncScheduleResult(getApplication(), result)
        return result
    }

    suspend fun clearTaskSchedule(noteId: String, blockId: String): TaskScheduleResult {
        val result = repository.clearTaskSchedule(noteId, blockId)
        ReminderScheduler.syncScheduleResult(getApplication(), result)
        return result
    }

    suspend fun snoozeReminder(
        reminderId: String,
        snoozeMinutes: Long = 15L,
        expectedOccurrenceId: String? = null,
    ): ReminderEntity? {
        val snoozed = repository.snoozeReminder(
            reminderId = reminderId,
            snoozeMinutes = snoozeMinutes,
            expectedOccurrenceId = expectedOccurrenceId,
        )
        if (snoozed != null) {
            ReminderScheduler.scheduleReminder(getApplication(), snoozed)
        }
        return snoozed
    }

    suspend fun completeFromNotification(
        noteId: String,
        blockId: String,
        expectedReminderId: String? = null,
        expectedOccurrenceId: String? = null,
    ): TaskToggleResult {
        val result = repository.completeFromNotification(
            noteId = noteId,
            blockId = blockId,
            expectedReminderId = expectedReminderId,
            expectedOccurrenceId = expectedOccurrenceId,
        )
        if (result is TaskToggleResult.Updated) {
            ReminderScheduler.syncToggleResult(getApplication(), result)
        }
        return result
    }

    suspend fun reconcileTimeChange(): TimeReconcileResult {
        val result = repository.reconcileTimeChange()
        ReminderScheduler.syncReconcile(getApplication(), result)
        return result
    }

    private fun applyScheduleResultToBlock(block: EditorBlock, result: TaskScheduleResult) {
        val synced = runCatching { NoteDocument.decode(result.note.documentJson).findBlock(block.id) }.getOrNull()
        if (synced != null) {
            block.checked = synced.checked
        }
        block.dueDate = synced?.dueDate
        block.reminderId = result.reminder?.id ?: synced?.reminderId
        block.occurrenceId = result.reminder?.occurrenceId ?: synced?.occurrenceId
        block.reminderLocalTime = result.reminder?.localTime
        block.recurrenceRule = result.reminder?.recurrenceEnum ?: RecurrenceRule.NONE
        block.reminderPolicy = result.reminder?.policyEnum ?: ReminderPolicy.FOLLOW_LOCAL
        block.reminderStatus = result.reminder?.statusEnum
        block.pendingReminderTime = null
        block.pendingScheduleChanged = false
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

private fun buildNoteDueBadges(items: List<TaskItemProjection>): Map<String, String> {
    if (items.isEmpty()) return emptyMap()
    val zoneId = ZoneId.systemDefault()
    val today = LocalDate.now(zoneId)
    val badges = LinkedHashMap<String, String>()
    val byNote = items.filter { !it.checked }.groupBy(TaskItemProjection::noteId)
    for ((noteId, noteItems) in byNote) {
        val chosen = noteItems
            .filter {
                it.dueDate != null ||
                    (it.reminderId != null && it.reminderStatus != ReminderStatus.CANCELLED)
            }
            .minByOrNull { item ->
                val due = TaskRecurrence.parseDueDate(item.dueDate)
                val remDate = TaskRecurrence.parseDueDate(item.reminderLocalDate)
                val bucket = TaskRecurrence.classifyTask(due, remDate, checked = false, today = today)
                val rank = when (bucket) {
                    TaskBucket.OVERDUE -> 0
                    TaskBucket.TODAY -> 1
                    TaskBucket.UPCOMING -> 2
                    TaskBucket.UNSCHEDULED -> 3
                    TaskBucket.COMPLETED -> 4
                }
                val sortDate = due ?: remDate ?: LocalDate.MAX
                "%d:%s:%s".format(rank, sortDate.toString(), item.reminderLocalTime ?: "99:99")
            } ?: continue
        val due = TaskRecurrence.parseDueDate(chosen.dueDate)
        val remDate = TaskRecurrence.parseDueDate(chosen.reminderLocalDate)
        val bucket = TaskRecurrence.classifyTask(due, remDate, checked = false, today = today)
        val hasTime = chosen.reminderId != null &&
            chosen.reminderStatus != ReminderStatus.CANCELLED &&
            !chosen.reminderLocalTime.isNullOrBlank()
        val label = when (bucket) {
            TaskBucket.OVERDUE -> "已逾期"
            TaskBucket.TODAY -> if (hasTime) "今天 ${chosen.reminderLocalTime}" else "今天"
            TaskBucket.UPCOMING -> {
                val targetDate = due ?: remDate
                when (targetDate) {
                    today.plusDays(1) -> if (hasTime) "明天 ${chosen.reminderLocalTime}" else "明天"
                    null -> if (hasTime) "提醒 ${chosen.reminderLocalTime}" else ""
                    else -> "${targetDate.monthValue}/${targetDate.dayOfMonth}" +
                        if (hasTime) " ${chosen.reminderLocalTime}" else ""
                }
            }
            TaskBucket.UNSCHEDULED -> if (hasTime) "提醒 ${chosen.reminderLocalTime}" else ""
            TaskBucket.COMPLETED -> ""
        }
        if (label.isNotBlank()) badges[noteId] = label
    }
    return badges
}
