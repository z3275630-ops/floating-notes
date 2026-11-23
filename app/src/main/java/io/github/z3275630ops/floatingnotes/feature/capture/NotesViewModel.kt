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
import io.github.z3275630ops.floatingnotes.data.agent.AgentActionCommitResult
import io.github.z3275630ops.floatingnotes.data.agent.AgentRepository
import io.github.z3275630ops.floatingnotes.data.agent.AgentRunEntity
import io.github.z3275630ops.floatingnotes.data.agent.AgentRunResult
import io.github.z3275630ops.floatingnotes.data.agent.AgentSkill
import io.github.z3275630ops.floatingnotes.data.agent.AgentTimeRange
import io.github.z3275630ops.floatingnotes.data.backup.BackupManager
import io.github.z3275630ops.floatingnotes.data.backup.BackupUiState
import io.github.z3275630ops.floatingnotes.data.backup.HistoryUiState
import io.github.z3275630ops.floatingnotes.data.backup.MiniJson
import io.github.z3275630ops.floatingnotes.data.backup.RestoreMode
import io.github.z3275630ops.floatingnotes.data.backup.RestoreOutcome
import io.github.z3275630ops.floatingnotes.data.backup.TrashPurgeImpact
import io.github.z3275630ops.floatingnotes.data.discovery.DiscoveryEngines
import io.github.z3275630ops.floatingnotes.data.discovery.DiscoveryRepository
import io.github.z3275630ops.floatingnotes.data.discovery.DuplicateNoteGroup
import io.github.z3275630ops.floatingnotes.data.discovery.EvidenceBasketItem
import io.github.z3275630ops.floatingnotes.data.discovery.EvidenceHit
import io.github.z3275630ops.floatingnotes.data.discovery.ExplorePreferences
import io.github.z3275630ops.floatingnotes.data.discovery.ReviewConfig
import io.github.z3275630ops.floatingnotes.data.local.NoteScope
import io.github.z3275630ops.floatingnotes.data.local.StaleDraftException
import io.github.z3275630ops.floatingnotes.feature.agent.AgentUiState
import io.github.z3275630ops.floatingnotes.feature.explore.EditorDiscoveryState
import io.github.z3275630ops.floatingnotes.feature.explore.ExploreUiState
import io.github.z3275630ops.floatingnotes.feature.explore.WalkMode
import io.github.z3275630ops.floatingnotes.feature.widget.WidgetPreferences
import io.github.z3275630ops.floatingnotes.feature.widget.WidgetUpdater
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
import io.github.z3275630ops.floatingnotes.data.local.RestoreVersionResult
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
    var syncedText: String = original.text
    var syncedChecked: Boolean = original.checked
    var syncedDueDate: String? = original.dueDate
    var syncedReminderId: String? = original.reminderId
    var syncedOccurrenceId: String? = original.occurrenceId
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

    fun markSyncedWith(block: NoteBlock? = null) {
        syncedText = block?.text ?: text.text.toString()
        syncedChecked = block?.checked ?: checked
        syncedDueDate = block?.dueDate ?: dueDate
        syncedReminderId = block?.reminderId ?: reminderId
        syncedOccurrenceId = block?.occurrenceId ?: occurrenceId
    }

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
    internal var onPendingExtrasChanged: (() -> Unit)? = null
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
        blocks.forEach { block ->
            block.markSyncedWith()
        }
    }

    fun replaceDocument(note: NoteEntity) {
        val restoredDoc = NoteDocument.decode(note.documentJson)
        draft = draft.copy(
            id = newId(),
            noteId = note.id,
            baseRevision = note.revision,
            documentJson = note.documentJson,
            editSeq = 0L,
            updatedAt = note.updatedAt,
            sourceType = note.sourceType,
            sourceUrl = note.sourceUrl,
        )
        blocks.clear()
        blocks.addAll(restoredDoc.blocks.map(::EditorBlock))
        pendingTagIds.clear()
        onPendingExtrasChanged?.invoke()
        trackedDocument = restoredDoc
        sequence = 0L
        checkpointSeq = 0L
        committedSeq = 0L
        focusBlockId = null
        status = "已恢复为新版本 v${note.revision}"
    }

    fun addPendingTag(tagId: String) {
        if (tagId !in pendingTagIds) {
            pendingTagIds.add(tagId)
            onPendingExtrasChanged?.invoke()
        }
    }

    fun removePendingTag(tagId: String) {
        if (pendingTagIds.remove(tagId)) {
            onPendingExtrasChanged?.invoke()
        }
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

    fun insertTranscript(block: EditorBlock, text: String): Boolean {
        if (writing || closing) return false
        val position = blocks.indexOf(block)
        if (position < 0) return false
        blocks.add(position + 1, EditorBlock(NoteBlock.paragraph(text)))
        return true
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

    fun ensureTailEditableBlock(): EditorBlock {
        val tail = blocks.lastOrNull()
        if (tail != null && tail.editable) return tail
        val added = EditorBlock(NoteBlock.paragraph())
        blocks.add(added)
        return added
    }
}

internal enum class IncomingCapture { Opened, AlreadySaved, Failed }

internal class NotesViewModel(application: Application) : AndroidViewModel(application) {
    private val database = NotesDatabase.open(application)
    internal val repository = NotesRepository(database)
    val taskPreferences = TaskPreferences(application)
    val attachments = AttachmentStore(application)
    internal val backupManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BackupManager(application, database, attachments)
    }
    val organization = OrganizationRepository(database)
    val search by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { SearchRepository(database) }
    val explorePreferences = ExplorePreferences(application)
    internal val widgetPreferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { WidgetPreferences(application) }
    internal val discovery by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DiscoveryRepository(database, repository, explorePreferences)
    }
    internal val agentRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AgentRepository(database, repository, organization, discovery)
    }
    private val _agentState = MutableStateFlow(AgentUiState())
    val agentState: StateFlow<AgentUiState> = _agentState.asStateFlow()
    private var agentJob: Job? = null
    private var agentRunsObserved = false

    fun ensureAgentRunsObserved() {
        if (agentRunsObserved) return
        agentRunsObserved = true
        viewModelScope.launch {
            agentRepository.observeRecentRuns(15).collect { runs ->
                _agentState.update { it.copy(recentRuns = runs) }
            }
        }
    }

    init {
        viewModelScope.launch {
            delay(1_200L)
            ensureAgentRunsObserved()
        }
    }
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

    val trashedNotes: StateFlow<List<NoteEntity>> = repository.trashedNotes.catch {
        error = "无法读取回收站"
        emit(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val trashedNoteIds: StateFlow<Set<String>> = repository.trashedNoteIds
        .map { it.toSet() }
        .catch { emit(emptySet()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val trashedCount: StateFlow<Int> = trashedNoteIds
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _historyState = MutableStateFlow(HistoryUiState())
    val historyState: StateFlow<HistoryUiState> = _historyState.asStateFlow()

    private val _trashPurgeImpact = MutableStateFlow<TrashPurgeImpact?>(null)
    val trashPurgeImpact: StateFlow<TrashPurgeImpact?> = _trashPurgeImpact.asStateFlow()

    private val _backupState = MutableStateFlow(BackupUiState())
    val backupState: StateFlow<BackupUiState> = _backupState.asStateFlow()

    private val _exploreState = MutableStateFlow(ExploreUiState())
    val exploreState: StateFlow<ExploreUiState> = _exploreState.asStateFlow()

    private val _editorDiscoveryState = MutableStateFlow(
        EditorDiscoveryState(globalEchoEnabled = explorePreferences.edgeEchoEnabled),
    )
    val editorDiscoveryState: StateFlow<EditorDiscoveryState> = _editorDiscoveryState.asStateFlow()

    private var edgeEchoJob: Job? = null
    private var tagRecJob: Job? = null
    private var findSearchJob: Job? = null
    private var findSearchSeq = 0L

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
        .map { items -> items.count { !it.checked && !it.noteArchived } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val todoFilterNoteIds: StateFlow<Set<String>> = taskItems
        .map { items -> items.filter { !it.noteArchived }.mapTo(hashSetOf()) { it.noteId } }
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
    private var timeReconciledOnLaunch = false
    private var expiredTrashPurged = false
    private var pendingFocusBlockId: String? = null

    fun queueFocusBlock(blockId: String?) {
        pendingFocusBlockId = blockId
    }

    fun onForeground() {
        foreground = true
        val launchDelayMs = if (!timeReconciledOnLaunch) 650L else 0L
        timeReconciledOnLaunch = true
        viewModelScope.launch(Dispatchers.IO) {
            if (launchDelayMs > 0L) delay(launchDelayMs)
            runCatching { reconcileTimeChange() }
        }
        purgeExpiredTrashIfNeeded(initialDelayMs = launchDelayMs)
    }

    private fun purgeExpiredTrashIfNeeded(initialDelayMs: Long = 0L) {
        if (expiredTrashPurged) return
        expiredTrashPurged = true
        viewModelScope.launch(Dispatchers.IO) {
            if (initialDelayMs > 0L) delay(initialDelayMs)
            withContext(NonCancellable) {
                persistenceMutex.withLock {
                    runCatching {
                        val openNoteId = editor?.draft?.noteId
                        val result = repository.purgeExpiredTrash(
                            excludeNoteIds = setOfNotNull(openNoteId),
                        )
                        if (result.deletedNoteIds.isNotEmpty()) {
                            runCatching { discovery.removeChunksForNoteIds(result.deletedNoteIds) }
                            val app = getApplication<Application>()
                            result.cancelledReminderIds.forEach { ReminderScheduler.cancelReminder(app, it) }
                            attachments.collectUnreferenced(database, backupManager.activeLeaseIds())
                        }
                    }.onFailure {
                        expiredTrashPurged = false
                    }
                }
            }
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
        File(getApplication<Application>().filesDir, "recordings/note_${session.draft.noteId}")

    private fun legacyVoiceDirectory(session: EditorSession) =
        File(getApplication<Application>().filesDir, "recordings/${session.draft.id}")

    private fun pendingRecording(session: EditorSession): File? =
        voiceDirectory(session).listFiles()?.firstOrNull { it.extension == "m4a" && it.isFile }
            ?: legacyVoiceDirectory(session).listFiles()?.firstOrNull { it.extension == "m4a" && it.isFile }

    private fun stopActiveRecorderBeforeClose(session: EditorSession) {
        val recorder = voiceRecorder ?: return
        voiceRecorder = null
        voiceClock?.cancel()
        voiceClock = null
        session.recording = false
        session.recordingPaused = false
        val hasAudio = runCatching { recorder.stop() }.getOrDefault(false)
        if (!hasAudio && recorder.file.length() == 0L) {
            recorder.file.delete()
        } else if (recorder.file.isFile && recorder.file.length() > 0L) {
            session.voicePending = true
        }
    }

    override fun onCleared() {
        runCatching { voiceRecorder?.stop() }
        voiceRecorder = null
        super.onCleared()
    }

    init {
        viewModelScope.launch {
            persistenceMutex.withLock {
                try {
                    val recoveredRestore = backupManager.recoverInterruptedRestoreIfNeeded()
                    attachments.recover(database, backupManager.activeLeaseIds())
                    if (recoveredRestore) {
                        runCatching { reconcileTimeChange() }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Retry recovery on import. Existing notes remain available.
                }
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
                attachments.recover(database, backupManager.activeLeaseIds())
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

    private suspend fun addImportedBlock(session: EditorSession, block: NoteBlock) {
        persistenceMutex.withLock {
            withContext(NonCancellable) {
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
    }

    suspend fun openNote(
        noteId: String? = null,
        captureSlot: String? = null,
        focusBlockId: String? = null,
    ): Boolean {
        val resolvedFocusBlockId = focusBlockId ?: pendingFocusBlockId.also { pendingFocusBlockId = null }
        val current = editor
        val requestedDifferentTarget = current != null && (
            (noteId != null && current.draft.noteId != noteId) ||
                (captureSlot != null && current.draft.slot != captureSlot)
            )
        if (current != null && !requestedDifferentTarget) {
            if (noteId != null && current.draft.noteId == noteId && resolvedFocusBlockId != null) {
                current.focusBlockId = resolvedFocusBlockId
            }
            return true
        }
        if (opening) return false
        opening = true
        var previousStopped = false
        return try {
            if (current != null) {
                // Persist active session cleanly before loading the next note without flashing editor = null.
                if (!persistAndStopSession(current)) return false
                previousStopped = true
            }
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
            if (previousStopped && editor === current) {
                editor = null
            }
            throw cancelled
        } catch (_: Exception) {
            if (previousStopped && editor === current) {
                editor = null
            }
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

    private fun draftExtrasFile(draftId: String): File =
        File(getApplication<Application>().filesDir, "draft_extras/$draftId.json")

    private fun saveDraftExtras(session: EditorSession) {
        runCatching {
            val pendingTags = session.pendingTagIds.toList()
            val pendingSchedules = session.blocks.filter { it.type == "checkbox" && it.pendingScheduleChanged }
            val file = draftExtrasFile(session.draft.id)
            if (pendingTags.isEmpty() && pendingSchedules.isEmpty()) {
                file.delete()
                return
            }
            val payload = linkedMapOf<String, Any?>(
                "pending_tag_ids" to pendingTags,
                "pending_schedules" to pendingSchedules.map { block ->
                    linkedMapOf<String, Any?>(
                        "block_id" to block.id,
                        "due_date" to block.dueDate,
                        "pending_reminder_time" to block.pendingReminderTime,
                        "recurrence" to block.recurrenceRule.name,
                        "policy" to block.reminderPolicy.name,
                    )
                },
            )
            file.parentFile?.mkdirs()
            file.writeText(MiniJson.encode(payload), Charsets.UTF_8)
        }
    }

    private fun restoreDraftExtras(session: EditorSession) {
        val file = draftExtrasFile(session.draft.id)
        if (!file.isFile) return
        runCatching {
            val obj = MiniJson.parseObject(file.readText(Charsets.UTF_8))
            val tags = obj["pending_tag_ids"] as? List<*>
            tags?.mapNotNull { it as? String }?.forEach { tagId ->
                if (tagId.isNotBlank() && tagId !in session.pendingTagIds) {
                    session.pendingTagIds.add(tagId)
                }
            }
            val schedules = obj["pending_schedules"] as? List<*>
            val byId = session.blocks.associateBy(EditorBlock::id)
            schedules?.forEach { entry ->
                @Suppress("UNCHECKED_CAST")
                val map = entry as? Map<String, Any?> ?: return@forEach
                val blockId = map["block_id"] as? String ?: return@forEach
                val block = byId[blockId]?.takeIf { it.type == "checkbox" } ?: return@forEach
                block.dueDate = (map["due_date"] as? String)?.takeIf { it.isNotBlank() }
                val remTime = (map["pending_reminder_time"] as? String)?.takeIf { it.isNotBlank() }
                block.pendingReminderTime = remTime
                block.reminderLocalTime = remTime
                block.recurrenceRule = (map["recurrence"] as? String)
                    ?.let { RecurrenceRule.fromStored(it) } ?: RecurrenceRule.NONE
                block.reminderPolicy = (map["policy"] as? String)
                    ?.let { ReminderPolicy.fromStored(it) } ?: ReminderPolicy.FOLLOW_LOCAL
                block.pendingScheduleChanged = true
            }
        }
    }

    private fun deleteDraftExtras(draftId: String) {
        runCatching { draftExtrasFile(draftId).delete() }
    }

    private fun startEditor(
        draft: DraftEntity,
        existingNote: Boolean,
        focusBlockId: String? = null,
        preloadedTasks: List<TaskItemProjection> = emptyList(),
    ) {
        val session = EditorSession(draft, NoteDocument.decode(draft.documentJson), existingNote).apply {
            this.focusBlockId = focusBlockId
            this.onPendingExtrasChanged = { saveDraftExtras(this) }
        }
        val sourceItems = if (preloadedTasks.isNotEmpty()) preloadedTasks else taskItems.value
        applyTaskMetadataToSession(session, sourceItems)
        restoreDraftExtras(session)
        val pending = pendingRecording(session)
        if (pending != null && session.blocks.any { it.attachmentId == pending.nameWithoutExtension }) pending.delete()
        session.voicePending = pendingRecording(session) != null
        if (session.voicePending) session.voiceStatus = "有一段本机录音待保存"
        editor = session
        watch(session)
        resetEditorDiscoveryForSession(if (existingNote) draft.noteId else null)
    }

    private fun applyTaskMetadataToSession(
        session: EditorSession,
        sourceItems: List<TaskItemProjection>,
    ) {
        val itemsByBlock = sourceItems
            .filter { it.noteId == session.draft.noteId }
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
                block.markSyncedWith()
            }
        }
    }

    private suspend fun tryMergeExternalUpdateAndRetryCommitLocked(
        session: EditorSession,
    ): NoteEntity? {
        val fresh = repository.loadNote(session.draft.noteId)?.takeIf { it.deletedAt == null } ?: return null
        val freshDoc = runCatching { NoteDocument.decode(fresh.documentJson) }.getOrNull() ?: return null
        val freshById = freshDoc.blocks.associateBy(NoteBlock::id)
        val sessionById = session.blocks.associateBy(EditorBlock::id)

        for (freshBlock in freshDoc.blocks) {
            val local = sessionById[freshBlock.id] ?: return null
            val localText = local.text.text.toString()
            val extTextChanged = freshBlock.text != local.syncedText
            val localTextChanged = localText != local.syncedText
            if (extTextChanged && localTextChanged) return null
            if (local.type == "checkbox") {
                val extTaskChanged = freshBlock.checked != local.syncedChecked ||
                    freshBlock.dueDate != local.syncedDueDate ||
                    freshBlock.reminderId != local.syncedReminderId ||
                    freshBlock.occurrenceId != local.syncedOccurrenceId
                val localTaskChanged = local.checked != local.syncedChecked ||
                    local.pendingScheduleChanged ||
                    local.dueDate != local.syncedDueDate
                if (extTaskChanged && localTaskChanged) return null
            }
        }
        for (local in session.blocks) {
            if (local.id !in freshById && local.syncedText.isNotEmpty()) {
                return null
            }
        }

        for (freshBlock in freshDoc.blocks) {
            val local = sessionById[freshBlock.id] ?: continue
            val localText = local.text.text.toString()
            if (freshBlock.text != local.syncedText && localText == local.syncedText) {
                local.text.edit { replace(0, length, freshBlock.text) }
            }
            if (local.type == "checkbox") {
                val extTaskChanged = freshBlock.checked != local.syncedChecked ||
                    freshBlock.dueDate != local.syncedDueDate ||
                    freshBlock.reminderId != local.syncedReminderId ||
                    freshBlock.occurrenceId != local.syncedOccurrenceId
                val localTaskChanged = local.checked != local.syncedChecked ||
                    local.pendingScheduleChanged ||
                    local.dueDate != local.syncedDueDate
                if (extTaskChanged && !localTaskChanged) {
                    local.checked = freshBlock.checked
                    local.dueDate = freshBlock.dueDate
                    local.reminderId = freshBlock.reminderId
                    local.occurrenceId = freshBlock.occurrenceId
                }
            }
            local.markSyncedWith(freshBlock)
        }
        val sourceItems = runCatching { repository.observeTaskItems().first() }.getOrDefault(taskItems.value)
        applyTaskMetadataToSession(session, sourceItems)
        session.draft = session.draft.copy(baseRevision = fresh.revision)
        val mergedDoc = session.document()
        val mergedSeq = session.sequenceFor(mergedDoc)
        val committed = repository.commit(encode(session, mergedDoc, mergedSeq))
        syncChunksAsync(committed)
        session.draft = session.draft.copy(baseRevision = committed.revision)
        session.committedSeq = maxOf(session.committedSeq, mergedSeq)
        commitSessionExtras(session)
        session.syncAfterExternalCommit(committed.revision)
        return committed
    }

    private fun syncChunksAsync(note: NoteEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { discovery.syncChunksForNote(note) }
        }
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
                    var resolvedByExternalMerge = false
                    persistenceMutex.withLock {
                        if (session.closing || editor !== session) return@withLock
                        if (sequence > session.checkpointSeq) {
                            val checkpointDraft = encode(session, document, sequence)
                            try {
                                repository.checkpoint(checkpointDraft)
                                session.checkpointSeq = maxOf(session.checkpointSeq, sequence)
                                saveDraftExtras(session)
                                session.status = "草稿已存到本机"
                            } catch (stale: StaleDraftException) {
                                val merged = tryMergeExternalUpdateAndRetryCommitLocked(session)
                                if (merged != null) {
                                    resolvedByExternalMerge = true
                                    session.status = "已保存到本机"
                                } else {
                                    throw stale
                                }
                            }
                        }
                    }
                    if (!resolvedByExternalMerge && needsCommit) {
                        delay(350)
                        persistenceMutex.withLock {
                            if (session.closing || editor !== session || sequence <= session.committedSeq) {
                                return@withLock
                            }
                            withContext(NonCancellable) {
                                session.status = "正在保存"
                                val commitDraft = encode(session, document, sequence)
                                try {
                                    val committed = repository.commit(commitDraft)
                                    syncChunksAsync(committed)
                                    session.draft = session.draft.copy(baseRevision = committed.revision)
                                    session.committedSeq = maxOf(session.committedSeq, sequence)
                                    commitSessionExtras(session)
                                    session.syncAfterExternalCommit(committed.revision)
                                    session.status = "已保存到本机"
                                } catch (stale: StaleDraftException) {
                                    val merged = tryMergeExternalUpdateAndRetryCommitLocked(session)
                                    if (merged != null) {
                                        session.status = "已保存到本机"
                                    } else {
                                        throw stale
                                    }
                                }
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: StaleDraftException) {
                    session.status = "检测到外部修改，已保留草稿，请核对后重存"
                } catch (_: Exception) {
                    session.status = "保存失败，正文仍保留"
                }
            }
        }
    }

    suspend fun finishEditor(save: Boolean = false, discard: Boolean = false): Boolean {
        val session = editor ?: return true
        if (!persistAndStopSession(session, save = save, discard = discard)) return false
        if (editor === session) {
            editor = null
        }
        return true
    }

    private suspend fun persistAndStopSession(
        session: EditorSession,
        save: Boolean = false,
        discard: Boolean = false,
    ): Boolean {
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
                    discard -> {
                        repository.discard(draft)
                        deleteDraftExtras(draft.id)
                    }
                    save || (session.existingNote && document.hasContent && sequence > session.committedSeq) -> {
                        session.status = "正在保存"
                        val committed = try {
                            repository.commit(draft)
                        } catch (stale: StaleDraftException) {
                            tryMergeExternalUpdateAndRetryCommitLocked(session) ?: throw stale
                        }
                        runCatching { discovery.syncChunksForNote(committed) }
                        session.draft = session.draft.copy(baseRevision = maxOf(session.draft.baseRevision, committed.revision))
                        session.committedSeq = maxOf(session.committedSeq, sequence)
                        didCommit = true
                    }
                    sequence > session.checkpointSeq -> {
                        try {
                            repository.checkpoint(draft)
                            session.checkpointSeq = maxOf(session.checkpointSeq, sequence)
                            saveDraftExtras(session)
                        } catch (stale: StaleDraftException) {
                            if (session.existingNote) {
                                val merged = tryMergeExternalUpdateAndRetryCommitLocked(session)
                                if (merged != null) {
                                    runCatching { discovery.syncChunksForNote(merged) }
                                    didCommit = true
                                } else {
                                    throw stale
                                }
                            } else {
                                throw stale
                            }
                        }
                    }
                }
                if (!discard && didCommit) {
                    commitSessionExtras(session)
                    session.syncAfterExternalCommit(session.draft.baseRevision)
                    WidgetUpdater.requestRefreshAll(getApplication())
                }
            }
            val editedDuringCommit = !discard &&
                session.blocks.map { it.id to it.text.text.toString() } != preCommitEditableSnapshot
            if (editedDuringCommit) {
                if (editor === session) watch(session)
                false
            } else {
                true
            }
        } catch (cancelled: CancellationException) {
            if (editor == session) watch(session)
            throw cancelled
        } catch (_: StaleDraftException) {
            session.status = "检测到外部修改，已保留草稿，请核对后重存"
            if (editor == session) watch(session)
            false
        } catch (_: Exception) {
            session.status = "保存失败，正文仍保留。可重试。"
            if (editor == session) watch(session)
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
                val res = runCatching { organization.tagNote(session.draft.noteId, tagId, attach = true) }
                if (res.isSuccess) {
                    session.pendingTagIds.remove(tagId)
                }
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
        saveDraftExtras(session)
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
                    if (sequence <= session.checkpointSeq) {
                        saveDraftExtras(session)
                        return@withLock
                    }
                    repository.checkpoint(encode(session, document, sequence))
                    session.checkpointSeq = maxOf(session.checkpointSeq, sequence)
                    saveDraftExtras(session)
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
                            syncChunksAsync(committed)
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
                                val committed = try {
                                    repository.commit(encode(session, doc, sequence))
                                } catch (stale: StaleDraftException) {
                                    tryMergeExternalUpdateAndRetryCommitLocked(session) ?: throw stale
                                }
                                syncChunksAsync(committed)
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
                            saveDraftExtras(session)
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
                            saveDraftExtras(session)
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
                        val existingNoteRow = repository.loadNote(session.draft.noteId)
                        val doc = session.document()
                        if (existingNoteRow != null && doc.hasContent) {
                            val sequence = session.sequenceFor(doc)
                            if (sequence > session.committedSeq) {
                                val committed = try {
                                    repository.commit(encode(session, doc, sequence))
                                } catch (stale: StaleDraftException) {
                                    tryMergeExternalUpdateAndRetryCommitLocked(session) ?: throw stale
                                }
                                syncChunksAsync(committed)
                                session.draft = session.draft.copy(baseRevision = committed.revision)
                                session.committedSeq = maxOf(session.committedSeq, sequence)
                            }
                        }
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
                        saveDraftExtras(session)
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
                    refreshRecommendationsForSession(session, extraTagId = tagId)
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
                    refreshRecommendationsForSession(session, extraTagId = tagId)
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
        expectedRevision: Long? = null,
    ): ReminderEntity? {
        val snoozed = repository.snoozeReminder(
            reminderId = reminderId,
            snoozeMinutes = snoozeMinutes,
            expectedOccurrenceId = expectedOccurrenceId,
            expectedRevision = expectedRevision,
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
        expectedRevision: Long? = null,
    ): TaskToggleResult {
        val result = repository.completeFromNotification(
            noteId = noteId,
            blockId = blockId,
            expectedReminderId = expectedReminderId,
            expectedOccurrenceId = expectedOccurrenceId,
            expectedRevision = expectedRevision,
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
        block.markSyncedWith(synced)
    }

    fun pin(note: NoteEntity) = changeMetadata { repository.setPinned(note.id, !note.pinned) }
    fun archive(note: NoteEntity) = changeMetadata { repository.setArchived(note.id, note.archivedAt == null) }

    fun moveToTrash(note: NoteEntity) {
        viewModelScope.launch {
            withContext(NonCancellable) {
                persistenceMutex.withLock {
                    try {
                        val activeSession = editor
                        if (activeSession != null && activeSession.draft.noteId == note.id) {
                            stopActiveRecorderBeforeClose(activeSession)
                            flushOpenEditorLocked()
                            deleteDraftExtras(activeSession.draft.id)
                        }
                        closeEditorIfMatchingLocked(setOf(note.id))
                        val reminderIds = repository.moveToTrash(note.id)
                        runCatching { discovery.removeChunksForNoteIds(listOf(note.id)) }
                        val app = getApplication<Application>()
                        reminderIds.forEach { ReminderScheduler.cancelReminder(app, it) }
                        if (_historyState.value.noteId == note.id) {
                            _historyState.value = HistoryUiState()
                        }
                        WidgetUpdater.requestRefreshAll(app)
                        error = null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        error = "移入回收站失败，请重试"
                    }
                }
            }
        }
    }

    fun restoreFromTrash(note: NoteEntity) {
        viewModelScope.launch {
            withContext(NonCancellable) {
                persistenceMutex.withLock {
                    try {
                        val sync = repository.restoreFromTrash(note.id)
                        runCatching { repository.loadNote(note.id)?.let { discovery.syncChunksForNote(it) } }
                        sync?.let { ReminderScheduler.syncReconcile(getApplication(), it) }
                        WidgetUpdater.requestRefreshAll(getApplication())
                        error = null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        error = "从回收站恢复失败，请重试"
                    }
                }
            }
        }
    }

    fun preparePurgeNote(note: NoteEntity) {
        viewModelScope.launch {
            persistenceMutex.withLock {
                try {
                    val readyIds = attachments.listReady().mapTo(HashSet()) { it.id }
                    _trashPurgeImpact.value = repository.previewPurge(listOf(note.id), readyIds)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _trashPurgeImpact.value = TrashPurgeImpact(noteCount = 1, versionCount = 0, orphanedAttachmentCount = 0)
                }
            }
        }
    }

    fun prepareEmptyTrash() {
        viewModelScope.launch {
            persistenceMutex.withLock {
                try {
                    val trashedIds = repository.listTrashedNotes().map { it.id }
                    val readyIds = attachments.listReady().mapTo(HashSet()) { it.id }
                    _trashPurgeImpact.value = repository.previewPurge(trashedIds, readyIds)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    val count = trashedNotes.value.size
                    _trashPurgeImpact.value = TrashPurgeImpact(noteCount = count, versionCount = 0, orphanedAttachmentCount = 0)
                }
            }
        }
    }

    fun clearPurgeImpact() {
        _trashPurgeImpact.value = null
    }

    fun permanentlyDeleteNote(note: NoteEntity) {
        viewModelScope.launch {
            withContext(NonCancellable) {
                persistenceMutex.withLock {
                    try {
                        closeEditorIfMatchingLocked(setOf(note.id))
                        val result = repository.permanentlyDeleteNotes(listOf(note.id))
                        runCatching { discovery.removeChunksForNoteIds(listOf(note.id)) }
                        val app = getApplication<Application>()
                        result.cancelledReminderIds.forEach { ReminderScheduler.cancelReminder(app, it) }
                        attachments.collectUnreferenced(database, backupManager.activeLeaseIds())
                        _trashPurgeImpact.value = null
                        error = null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        error = "彻底删除失败，请重试"
                    }
                }
            }
        }
    }

    fun emptyTrash() {
        viewModelScope.launch {
            withContext(NonCancellable) {
                persistenceMutex.withLock {
                    try {
                        val trashedIds = repository.listTrashedNotes().mapTo(HashSet()) { it.id }
                        if (trashedIds.isNotEmpty()) {
                            closeEditorIfMatchingLocked(trashedIds)
                        }
                        val result = repository.emptyTrash()
                        runCatching { discovery.removeChunksForNoteIds(result.deletedNoteIds) }
                        val app = getApplication<Application>()
                        result.cancelledReminderIds.forEach { ReminderScheduler.cancelReminder(app, it) }
                        attachments.collectUnreferenced(database, backupManager.activeLeaseIds())
                        _trashPurgeImpact.value = null
                        error = null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        error = "清空回收站失败，请重试"
                    }
                }
            }
        }
    }

    fun openVersionHistory(noteId: String) {
        _historyState.value = HistoryUiState(noteId = noteId, loading = true)
        viewModelScope.launch {
            persistenceMutex.withLock {
                try {
                    if (editor?.draft?.noteId == noteId) {
                        flushOpenEditorLocked()
                    }
                    val loaded = repository.listVersions(noteId)
                    if (loaded == null) {
                        _historyState.value = HistoryUiState(
                            noteId = noteId,
                            loading = false,
                            errorMessage = "未找到该笔记的历史版本记录",
                        )
                        return@withLock
                    }
                    val (note, revisions) = loaded
                    val defaultSelected = revisions.firstOrNull { !it.isCurrent }?.revision
                        ?: revisions.firstOrNull()?.revision
                    val readyMap = attachments.listReady().associate { it.id to it.name }
                    val diff = defaultSelected?.let { rev ->
                        repository.loadVersionDiff(noteId, rev) { readyMap[it] }
                    }
                    _historyState.value = HistoryUiState(
                        noteId = noteId,
                        currentRevision = note.revision,
                        revisions = revisions,
                        selectedRevision = defaultSelected,
                        diffPreview = diff,
                        loading = false,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _historyState.update {
                        it.copy(
                            loading = false,
                            errorMessage = "无法加载版本历史，请稍后重试",
                        )
                    }
                }
            }
        }
    }

    fun closeVersionHistory() {
        _historyState.value = HistoryUiState()
    }

    fun selectHistoryRevision(revision: Long) {
        val noteId = _historyState.value.noteId ?: return
        _historyState.update {
            it.copy(
                selectedRevision = revision,
                loading = true,
                statusMessage = null,
                errorMessage = null,
            )
        }
        viewModelScope.launch {
            persistenceMutex.withLock {
                try {
                    val readyMap = attachments.listReady().associate { it.id to it.name }
                    val diff = repository.loadVersionDiff(noteId, revision) { readyMap[it] }
                    _historyState.update { state ->
                        if (state.noteId != noteId) state
                        else state.copy(
                            selectedRevision = revision,
                            diffPreview = diff,
                            loading = false,
                            errorMessage = if (diff == null) "该版本记录不存在" else null,
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _historyState.update {
                        it.copy(loading = false, errorMessage = "无法读取该版本的差异对比")
                    }
                }
            }
        }
    }

    fun restoreVersionAsNewRevision(noteId: String, targetRevision: Long) {
        _historyState.update { it.copy(restoring = true, statusMessage = null, errorMessage = null) }
        viewModelScope.launch {
            withContext(NonCancellable) {
                persistenceMutex.withLock {
                    try {
                        val expectedRevision = _historyState.value
                            .takeIf { it.noteId == noteId && it.currentRevision > 0L }
                            ?.currentRevision
                            ?: repository.loadNote(noteId)?.revision
                            ?: 0L

                        when (val result = repository.restoreVersion(noteId, targetRevision, expectedRevision)) {
                            RestoreVersionResult.NotFound -> {
                                _historyState.update {
                                    it.copy(restoring = false, errorMessage = "找不到目标版本或笔记")
                                }
                            }
                            RestoreVersionResult.Trashed -> {
                                _historyState.update {
                                    it.copy(restoring = false, errorMessage = "回收站中的笔记无法直接恢复版本，请先从回收站还原")
                                }
                            }
                            is RestoreVersionResult.Conflict -> {
                                val refreshed = repository.listVersions(noteId)
                                val readyMap = attachments.listReady().associate { it.id to it.name }
                                val selectedRev = _historyState.value.selectedRevision ?: targetRevision
                                val diff = repository.loadVersionDiff(noteId, selectedRev) { readyMap[it] }
                                _historyState.update { state ->
                                    state.copy(
                                        currentRevision = result.actualRevision,
                                        revisions = refreshed?.second ?: state.revisions,
                                        diffPreview = diff,
                                        restoring = false,
                                        errorMessage = "恢复期间笔记已在其他位置更新为 v${result.actualRevision}，已刷新列表，请确认差异后重试",
                                    )
                                }
                            }
                            is RestoreVersionResult.Restored -> {
                                runCatching { discovery.syncChunksForNote(result.note) }
                                ReminderScheduler.syncReconcile(getApplication(), result.reminderSync)
                                val activeSession = editor
                                if (activeSession != null && activeSession.draft.noteId == noteId) {
                                    watcher?.cancelAndJoin()
                                    stopCheckpoint?.cancelAndJoin()
                                    activeSession.replaceDocument(result.note)
                                    val freshTasks = runCatching { repository.observeTaskItems().first() }
                                        .getOrDefault(taskItems.value)
                                    applyTaskMetadataToSession(activeSession, freshTasks)
                                    watch(activeSession)
                                }
                                val readyMap = attachments.listReady().associate { it.id to it.name }
                                val missingCount = result.referencedAttachmentIds
                                    .distinct()
                                    .count { it !in readyMap }
                                val refreshed = repository.listVersions(noteId)
                                val newCurrentRev = result.note.revision
                                val refreshedDiff = repository.loadVersionDiff(noteId, targetRevision) { readyMap[it] }
                                val statusMsg = if (missingCount > 0) {
                                    "已创建新版本 v$newCurrentRev（有 $missingCount 个原附件在本机缺失，已保留缺失提示）"
                                } else {
                                    "已基于 v$targetRevision 创建新版本 v$newCurrentRev"
                                }
                                _historyState.update { state ->
                                    state.copy(
                                        currentRevision = newCurrentRev,
                                        revisions = refreshed?.second ?: state.revisions,
                                        selectedRevision = targetRevision,
                                        diffPreview = refreshedDiff,
                                        restoring = false,
                                        statusMessage = statusMsg,
                                        errorMessage = null,
                                    )
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        _historyState.update {
                            it.copy(restoring = false, errorMessage = "恢复版本失败，当前笔记保持不变")
                        }
                    }
                }
            }
        }
    }

    fun exportFullBackup(destinationUri: Uri) {
        _backupState.update {
            it.copy(busy = true, busyLabel = "正在打包完整备份…", lastExportOutcome = null, errorMessage = null)
        }
        viewModelScope.launch {
            withContext(NonCancellable) {
                persistenceMutex.withLock {
                    try {
                        flushOpenEditorLocked()
                        val outcome = backupManager.exportFullBackupToUri(destinationUri)
                        _backupState.update {
                            it.copy(
                                busy = false,
                                busyLabel = null,
                                lastExportOutcome = outcome,
                                errorMessage = if (outcome.ok) null else outcome.message,
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (err: Exception) {
                        _backupState.update {
                            it.copy(
                                busy = false,
                                busyLabel = null,
                                errorMessage = err.message ?: "导出完整备份失败",
                            )
                        }
                    }
                }
            }
        }
    }

    fun exportMarkdownBundle(destinationUri: Uri) {
        _backupState.update {
            it.copy(busy = true, busyLabel = "正在导出 Markdown 压缩包…", lastExportOutcome = null, errorMessage = null)
        }
        viewModelScope.launch {
            withContext(NonCancellable) {
                persistenceMutex.withLock {
                    try {
                        flushOpenEditorLocked()
                        val outcome = backupManager.exportMarkdownToUri(destinationUri)
                        _backupState.update {
                            it.copy(
                                busy = false,
                                busyLabel = null,
                                lastExportOutcome = outcome,
                                errorMessage = if (outcome.ok) null else outcome.message,
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (err: Exception) {
                        _backupState.update {
                            it.copy(
                                busy = false,
                                busyLabel = null,
                                errorMessage = err.message ?: "导出 Markdown 压缩包失败",
                            )
                        }
                    }
                }
            }
        }
    }

    fun preflightBackup(sourceUri: Uri) {
        _backupState.update {
            it.copy(
                busy = true,
                busyLabel = "正在校验备份完整性…",
                preflight = null,
                lastRestoreOutcome = null,
                errorMessage = null,
            )
        }
        viewModelScope.launch {
            persistenceMutex.withLock {
                try {
                    val preflight = backupManager.preflightBackupUri(sourceUri)
                    _backupState.update {
                        it.copy(
                            busy = false,
                            busyLabel = null,
                            preflight = preflight,
                            errorMessage = null,
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (err: Exception) {
                    _backupState.update {
                        it.copy(
                            busy = false,
                            busyLabel = null,
                            preflight = null,
                            errorMessage = err.message ?: "备份校验未通过，原有数据保持完好",
                        )
                    }
                }
            }
        }
    }

    fun setRestoreMode(mode: RestoreMode) {
        _backupState.update { it.copy(preflightMode = mode) }
    }

    fun setAutoBackupBeforeRestore(enabled: Boolean) {
        _backupState.update { it.copy(autoBackupBeforeRestore = enabled) }
    }

    fun confirmRestoreFromPreflight() {
        val currentState = _backupState.value
        if (currentState.preflight == null || currentState.busy) return
        _backupState.update {
            it.copy(busy = true, busyLabel = "正在安全还原数据…", lastRestoreOutcome = null, errorMessage = null)
        }
        viewModelScope.launch {
            withContext(NonCancellable) {
                persistenceMutex.withLock {
                    try {
                        flushOpenEditorLocked(throwOnFailure = true)
                        val mode = _backupState.value.preflightMode
                        val autoBackup = _backupState.value.autoBackupBeforeRestore
                        val exec = backupManager.executeRestore(
                            mode = mode,
                            autoBackupBeforeRestore = autoBackup,
                            zoneId = ZoneId.systemDefault(),
                        )
                        val app = getApplication<Application>()
                        runCatching {
                            exec.cancelledReminderIds.forEach { ReminderScheduler.cancelReminder(app, it) }
                            exec.activeRemindersToSchedule.forEach { ReminderScheduler.scheduleReminder(app, it) }
                        }

                        if (exec.outcome is RestoreOutcome.Success) {
                            runCatching { discovery.ensureIndexFresh(forceRebuild = true) }
                            val openSession = editor
                            if (openSession != null && (mode == RestoreMode.REPLACE_ALL || repository.loadNote(openSession.draft.noteId) == null)) {
                                deleteDraftExtras(openSession.draft.id)
                                closeEditorIfMatchingLocked(setOf(openSession.draft.noteId))
                            }
                            _historyState.value = HistoryUiState()
                            _trashPurgeImpact.value = null
                        }

                        _backupState.update {
                            when (val outcome = exec.outcome) {
                                is RestoreOutcome.Success -> it.copy(
                                    busy = false,
                                    busyLabel = null,
                                    preflight = null,
                                    lastRestoreOutcome = outcome,
                                    errorMessage = null,
                                )
                                is RestoreOutcome.Failure -> it.copy(
                                    busy = false,
                                    busyLabel = null,
                                    lastRestoreOutcome = outcome,
                                    errorMessage = null,
                                )
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (err: Exception) {
                        _backupState.update {
                            it.copy(
                                busy = false,
                                busyLabel = null,
                                lastRestoreOutcome = RestoreOutcome.Failure(
                                    err.message ?: "还原过程中出现异常，原有笔记保持不变",
                                    existingLibraryIntact = true,
                                ),
                                errorMessage = null,
                            )
                        }
                    }
                }
            }
        }
    }

    fun dismissRestorePreflight() {
        _backupState.update { it.copy(preflight = null, errorMessage = null) }
        viewModelScope.launch {
            persistenceMutex.withLock {
                runCatching { backupManager.dismissPreflight() }
            }
        }
    }

    fun clearBackupOutcome() {
        _backupState.update {
            it.copy(lastExportOutcome = null, lastRestoreOutcome = null, errorMessage = null)
        }
    }

    fun clearExploreMessage() {
        _exploreState.update { it.copy(statusMessage = null, errorMessage = null) }
    }

    fun setWidgetPrivacyModeEnabled(enabled: Boolean) {
        widgetPreferences.privacyModeEnabled = enabled
        WidgetUpdater.requestRefreshAll(getApplication())
    }

    fun focusReviewSessionFromWidget(sessionId: String?, noteId: String?) {
        viewModelScope.launch {
            _exploreState.update { it.copy(loading = true, errorMessage = null) }
            try {
                val coverage = discovery.ensureIndexFresh(forceRebuild = false)
                val review = discovery.loadOrCreateDailyReview(startExtraRound = false)
                val targetIndex = noteId?.let { target ->
                    review.cards.indexOfFirst { it.note.id == target }.takeIf { it >= 0 }
                }
                val adjustedReview = if (targetIndex != null) {
                    discovery.setReviewIndex(review.session.id, targetIndex)
                    review.copy(currentIndex = targetIndex)
                } else {
                    review
                }
                val statusHint = when {
                    sessionId != null && review.session.id != sessionId ->
                        "桌面组件上的回顾会话已切换为今日最新回顾"
                    noteId != null && targetIndex == null ->
                        "桌面卡片对应的原笔记已变更或已移出回顾队列，已展示当前队列"
                    else -> null
                }
                _exploreState.update {
                    it.copy(
                        loading = false,
                        review = adjustedReview,
                        coverage = coverage,
                        statusMessage = statusHint ?: it.statusMessage,
                    )
                }
                WidgetUpdater.requestRefreshAll(getApplication())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _exploreState.update {
                    it.copy(loading = false, errorMessage = "从桌面组件打开回顾失败，请重试")
                }
            }
        }
    }

    fun loadExploreDashboard(startExtraRound: Boolean = false) {
        viewModelScope.launch {
            _exploreState.update { it.copy(loading = true, errorMessage = null) }
            try {
                val coverage = discovery.ensureIndexFresh(forceRebuild = false)
                val review = discovery.loadOrCreateDailyReview(startExtraRound = startExtraRound)
                val latestWalk = _exploreState.value.walk ?: discovery.loadLatestActiveWalk()
                _exploreState.update {
                    it.copy(
                        loading = false,
                        review = review,
                        coverage = coverage,
                        walk = latestWalk,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _exploreState.update {
                    it.copy(loading = false, errorMessage = "加载探索内容失败，请重试")
                }
            }
        }
    }

    fun selectReviewCard(index: Int) {
        val review = _exploreState.value.review ?: return
        val clamped = index.coerceIn(0, (review.cards.size - 1).coerceAtLeast(0))
        _exploreState.update { state ->
            state.copy(review = state.review?.copy(currentIndex = clamped))
        }
        viewModelScope.launch {
            runCatching { discovery.setReviewIndex(review.session.id, clamped) }
        }
    }

    fun markReviewCardSeen(noteId: String) {
        val sessionId = _exploreState.value.review?.session?.id ?: return
        viewModelScope.launch {
            runCatching { discovery.markReviewCardSeen(sessionId, noteId) }
        }
    }

    fun deferReviewCard(noteId: String) {
        val sessionId = _exploreState.value.review?.session?.id ?: return
        viewModelScope.launch {
            runCatching {
                discovery.recordReviewAction(sessionId, noteId, "deferred")
                val refreshed = discovery.loadOrCreateDailyReview()
                _exploreState.update {
                    it.copy(review = refreshed, statusMessage = "已移出今日队列，明天可再次重温")
                }
            }.onFailure {
                if (it is CancellationException) throw it
                _exploreState.update { s -> s.copy(errorMessage = "操作未生效，请重试") }
            }
        }
    }

    fun suppressReviewCard7Days(noteId: String) {
        val sessionId = _exploreState.value.review?.session?.id ?: return
        viewModelScope.launch {
            runCatching {
                discovery.recordReviewAction(sessionId, noteId, "suppressed_7d")
                val refreshed = discovery.loadOrCreateDailyReview()
                _exploreState.update {
                    it.copy(review = refreshed, statusMessage = "已设为近期（7 天）不再出现")
                }
            }.onFailure {
                if (it is CancellationException) throw it
                _exploreState.update { s -> s.copy(errorMessage = "操作未生效，请重试") }
            }
        }
    }

    fun excludeReviewCard(noteId: String) {
        val sessionId = _exploreState.value.review?.session?.id ?: return
        viewModelScope.launch {
            runCatching {
                discovery.recordReviewAction(sessionId, noteId, "excluded")
                val refreshed = discovery.loadOrCreateDailyReview()
                _exploreState.update {
                    it.copy(review = refreshed, statusMessage = "已永久排除该条回顾（可在设置中恢复）")
                }
            }.onFailure {
                if (it is CancellationException) throw it
                _exploreState.update { s -> s.copy(errorMessage = "操作未生效，请重试") }
            }
        }
    }

    fun excludeReviewTag(tagId: String, tagName: String) {
        viewModelScope.launch {
            explorePreferences.addExcludedReviewTag(tagId)
            val refreshed = discovery.loadOrCreateDailyReview()
            _exploreState.update {
                it.copy(review = refreshed, statusMessage = "已排除 #$tagName 类回顾（可在设置恢复）")
            }
        }
    }

    fun updateReviewConfig(config: ReviewConfig) {
        viewModelScope.launch {
            explorePreferences.reviewConfig = config
            val refreshed = discovery.loadOrCreateDailyReview()
            _exploreState.update {
                it.copy(review = refreshed, statusMessage = "已更新今日回顾筛选范围")
            }
        }
    }

    fun annotateReviewCard(targetNote: NoteEntity, text: String) {
        val sessionId = _exploreState.value.review?.session?.id ?: return
        viewModelScope.launch {
            try {
                persistenceMutex.withLock {
                    discovery.annotateReviewCardAsNote(sessionId, targetNote, text)
                }
                val refreshed = discovery.loadOrCreateDailyReview()
                _exploreState.update {
                    it.copy(review = refreshed, statusMessage = "已生成一条带原笔记引用的新批注")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (err: Exception) {
                _exploreState.update { it.copy(errorMessage = err.message ?: "保存批注失败") }
            }
        }
    }

    fun restoreAllDiscoveryExclusions() {
        viewModelScope.launch {
            val count = runCatching { discovery.restoreAllExclusions() }.getOrDefault(0)
            val refreshed = runCatching { discovery.loadOrCreateDailyReview() }.getOrNull()
            _exploreState.update {
                it.copy(
                    review = refreshed ?: it.review,
                    statusMessage = if (count > 0) "已恢复 $count 项此前排除的回顾/发现内容" else "当前没有已排除的内容",
                )
            }
        }
    }

    fun updateFindQuery(query: String) {
        _exploreState.update { it.copy(findQuery = query) }
    }

    fun updateFindScope(scope: NoteScope) {
        _exploreState.update { it.copy(findScope = scope) }
        val q = _exploreState.value.findQuery
        if (q.isNotBlank()) runFindSearch(q, scope)
    }

    fun runFindSearch(
        query: String = _exploreState.value.findQuery,
        scope: NoteScope = _exploreState.value.findScope,
    ) {
        val seq = ++findSearchSeq
        findSearchJob?.cancel()
        _exploreState.update { it.copy(findQuery = query, findScope = scope, indexing = true, errorMessage = null) }
        findSearchJob = viewModelScope.launch {
            try {
                val res = discovery.searchFind(query, scope)
                if (seq != findSearchSeq) return@launch
                _exploreState.update {
                    it.copy(indexing = false, findResult = res, coverage = res.coverage)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (seq != findSearchSeq) return@launch
                _exploreState.update {
                    it.copy(indexing = false, errorMessage = "检索失败，请重试")
                }
            }
        }
    }

    fun refreshRetrievalIndex() {
        _exploreState.update { it.copy(indexing = true) }
        viewModelScope.launch {
            runCatching {
                val cov = discovery.ensureIndexFresh(forceRebuild = true)
                val q = _exploreState.value.findQuery
                val res = if (q.isNotBlank()) discovery.searchFind(q, _exploreState.value.findScope) else null
                _exploreState.update {
                    it.copy(
                        indexing = false,
                        coverage = cov,
                        findResult = res ?: it.findResult,
                        statusMessage = "索引已更新：已覆盖 ${cov.indexedCount}/${cov.eligibleCount} 条可检索笔记",
                    )
                }
            }.onFailure {
                if (it is CancellationException) throw it
                _exploreState.update { s -> s.copy(indexing = false, errorMessage = "索引刷新失败") }
            }
        }
    }

    fun toggleEvidenceBasket(hit: EvidenceHit) {
        _exploreState.update { state ->
            val exists = state.evidenceBasket.any { it.noteId == hit.note.id }
            val updated = if (exists) {
                state.evidenceBasket.filterNot { it.noteId == hit.note.id }
            } else {
                state.evidenceBasket + EvidenceBasketItem(
                    noteId = hit.note.id,
                    noteRevision = hit.note.revision,
                    noteTitle = hit.noteTitle,
                    excerpt = hit.mergedExcerpt,
                    blockId = hit.matchedBlockId,
                    reasons = hit.reasons,
                )
            }
            state.copy(evidenceBasket = updated)
        }
    }

    fun clearEvidenceBasket() {
        _exploreState.update { it.copy(evidenceBasket = emptyList()) }
    }

    fun saveEvidenceBasketAsNote() {
        val state = _exploreState.value
        val items = state.evidenceBasket
        if (items.isEmpty()) return
        viewModelScope.launch {
            try {
                persistenceMutex.withLock {
                    discovery.saveEvidenceBasketAsNote(state.findQuery, items)
                }
                _exploreState.update {
                    it.copy(
                        evidenceBasket = emptyList(),
                        statusMessage = "已将 ${items.size} 条证据汇总为新笔记（带原笔记引用）",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (err: Exception) {
                _exploreState.update { it.copy(errorMessage = err.message ?: "生成整理笔记失败") }
            }
        }
    }

    fun excludeNoteFromFind(noteId: String) {
        viewModelScope.launch {
            explorePreferences.addExcludedFindNote(noteId)
            val res = runCatching {
                discovery.searchFind(_exploreState.value.findQuery, _exploreState.value.findScope)
            }.getOrNull()
            _exploreState.update { state ->
                state.copy(
                    findResult = res ?: state.findResult,
                    evidenceBasket = state.evidenceBasket.filterNot { it.noteId == noteId },
                    statusMessage = "已排除此笔记参与“找一找”（可在设置恢复）",
                )
            }
        }
    }

    fun startWalk(seedNoteId: String? = null, seedTagId: String? = null) {
        viewModelScope.launch {
            _exploreState.update { it.copy(loading = true, walkPostcard = null, errorMessage = null) }
            try {
                val snap = discovery.startWalk(seedNoteId = seedNoteId, seedTagId = seedTagId)
                _exploreState.update {
                    if (snap == null) {
                        it.copy(loading = false, errorMessage = "当前范围内没有可漫步的笔记，先写几条笔记吧")
                    } else {
                        it.copy(loading = false, walk = snap, walkPostcard = null)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _exploreState.update { it.copy(loading = false, errorMessage = "启动漫步失败") }
            }
        }
    }

    fun stepWalkForward(forceCrossDirection: Boolean = false) {
        val sessionId = _exploreState.value.walk?.session?.id ?: return
        viewModelScope.launch {
            runCatching {
                discovery.stepWalkForward(sessionId, forceCrossDirection)
            }.onSuccess { snap ->
                if (snap != null) {
                    _exploreState.update { it.copy(walk = snap) }
                }
            }.onFailure {
                if (it is CancellationException) throw it
                _exploreState.update { s -> s.copy(errorMessage = "找不到更多关联笔记了") }
            }
        }
    }

    fun stepWalkBack() {
        val sessionId = _exploreState.value.walk?.session?.id ?: return
        viewModelScope.launch {
            runCatching {
                discovery.stepWalkBack(sessionId)
            }.onSuccess { snap ->
                if (snap != null) _exploreState.update { it.copy(walk = snap) }
            }
        }
    }

    fun saveWalkStepMemo(memo: String) {
        val walk = _exploreState.value.walk ?: return
        val step = walk.currentStep ?: return
        viewModelScope.launch {
            runCatching {
                discovery.saveWalkStepMemo(step.id, walk.session.id, memo)
                discovery.loadWalkSnapshot(walk.session.id)
            }.onSuccess { snap ->
                _exploreState.update {
                    it.copy(walk = snap ?: it.walk, statusMessage = "已记录这一站的随想")
                }
            }
        }
    }

    fun finishWalkAndPreviewPostcard(hideDetailsForPrivacy: Boolean = _exploreState.value.walkPrivacyHidden) {
        val sessionId = _exploreState.value.walk?.session?.id ?: return
        viewModelScope.launch {
            runCatching {
                discovery.buildWalkPostcard(sessionId, hideDetailsForPrivacy, markCompleted = true)
            }.onSuccess { card ->
                _exploreState.update {
                    it.copy(walkPostcard = card, walkPrivacyHidden = hideDetailsForPrivacy)
                }
            }
        }
    }

    fun toggleWalkPostcardPrivacy(hideDetailsForPrivacy: Boolean) {
        val sessionId = _exploreState.value.walk?.session?.id ?: return
        viewModelScope.launch {
            runCatching {
                discovery.buildWalkPostcard(sessionId, hideDetailsForPrivacy, markCompleted = false)
            }.onSuccess { card ->
                _exploreState.update {
                    it.copy(walkPostcard = card, walkPrivacyHidden = hideDetailsForPrivacy)
                }
            }
        }
    }

    fun saveWalkPostcardAsNote() {
        val postcard = _exploreState.value.walkPostcard ?: return
        viewModelScope.launch {
            try {
                persistenceMutex.withLock {
                    discovery.savePostcardAsNote(postcard)
                }
                _exploreState.update {
                    it.copy(walkPostcard = null, walk = null, statusMessage = "漫步明信片已保存到你的笔记")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _exploreState.update { it.copy(errorMessage = "保存明信片失败，请重试") }
            }
        }
    }

    fun dismissWalkPostcard() {
        _exploreState.update { it.copy(walkPostcard = null) }
    }

    fun clearActiveWalk() {
        _exploreState.update { it.copy(walk = null, walkPostcard = null) }
    }

    fun selectWalkMode(mode: WalkMode) {
        _exploreState.update { it.copy(walkMode = mode, errorMessage = null, statusMessage = null) }
        if (mode == WalkMode.Dual && _exploreState.value.dualPair == null) {
            drawNextDualPair(pushHistory = false)
        }
    }

    fun drawNextDualPair(pushHistory: Boolean = true) {
        val current = _exploreState.value
        val previous = current.dualPair
        val excludeKeys = buildSet {
            previous?.pairKey?.let(::add)
            current.dualHistory.takeLast(6).forEach { add(it.pairKey) }
        }
        viewModelScope.launch {
            runCatching {
                discovery.drawDualCollisionPair(excludePairKeys = excludeKeys)
            }.onSuccess { result ->
                _exploreState.update { state ->
                    when (result) {
                        is DiscoveryRepository.DualPairDrawResult.Insufficient -> state.copy(
                            dualPair = null,
                            dualBridge = null,
                            dualBridgeFarFetched = false,
                            dualInsufficientCount = result.eligibleCount,
                        )
                        is DiscoveryRepository.DualPairDrawResult.Ready -> state.copy(
                            dualPair = result.pair,
                            dualHistory = if (pushHistory && previous != null) {
                                (state.dualHistory + previous).takeLast(12)
                            } else state.dualHistory,
                            dualBridge = null,
                            dualBridgeFarFetched = false,
                            dualInsufficientCount = null,
                        )
                    }
                }
            }.onFailure {
                if (it is CancellationException) throw it
                _exploreState.update { s -> s.copy(errorMessage = "抽取双卡失败，请重试") }
            }
        }
    }

    fun stepDualPairBack() {
        _exploreState.update { state ->
            val prev = state.dualHistory.lastOrNull() ?: return@update state
            state.copy(
                dualPair = prev,
                dualHistory = state.dualHistory.dropLast(1),
                dualBridge = null,
                dualBridgeFarFetched = false,
            )
        }
    }

    fun analyzeCurrentDualPair() {
        val pair = _exploreState.value.dualPair ?: return
        viewModelScope.launch {
            runCatching {
                discovery.analyzeDualCollisionRefreshed(pair)
            }.onSuccess { analyzed ->
                _exploreState.update {
                    if (it.dualPair?.pairId == pair.pairId) {
                        when (analyzed) {
                            is DiscoveryRepository.DualBridgeAnalyzeResult.Ready -> it.copy(
                                dualPair = analyzed.pair,
                                dualBridge = analyzed.bridge,
                                dualBridgeFarFetched = false,
                            )
                            DiscoveryRepository.DualBridgeAnalyzeResult.Unavailable -> it.copy(
                                dualBridge = null,
                                dualBridgeFarFetched = false,
                                statusMessage = "其中一条笔记已移入回收站或设为不参与分析，请“换一组”",
                            )
                        }
                    } else {
                        it
                    }
                }
            }
        }
    }

    fun markDualBridgeFarFetched() {
        _exploreState.update {
            it.copy(
                dualBridgeFarFetched = true,
                statusMessage = "已标记这组关联偏牵强，可以点“换一组”再试试",
            )
        }
    }

    fun saveDualCollisionAsNote(userReflection: String) {
        val snap = _exploreState.value
        val pair = snap.dualPair ?: return
        viewModelScope.launch {
            try {
                val res = persistenceMutex.withLock {
                    discovery.saveDualCollisionAsNote(
                        pair = pair,
                        bridge = snap.dualBridge,
                        userReflection = userReflection,
                        farFetched = snap.dualBridgeFarFetched,
                    )
                }
                _exploreState.update { state ->
                    when (res) {
                        is DiscoveryRepository.DualCollisionSaveResult.Saved ->
                            state.copy(statusMessage = "已将双卡对撞保存为新笔记《${res.title}》")
                        is DiscoveryRepository.DualCollisionSaveResult.AlreadySaved ->
                            state.copy(statusMessage = "这组对撞已经保存过笔记，不重复写入")
                        DiscoveryRepository.DualCollisionSaveResult.SourceChangedOrDeleted ->
                            state.copy(errorMessage = "来源笔记刚刚有修改或移除，请“换一组”重新读取后再保存")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _exploreState.update { it.copy(errorMessage = "保存双卡笔记失败，请重试") }
            }
        }
    }

    fun updateAgentPrompt(prompt: String) {
        _agentState.update { it.copy(promptDraft = prompt) }
    }

    fun selectAgentTimeRange(range: AgentTimeRange) {
        _agentState.update { it.copy(timeRange = range) }
    }

    fun selectAgentTagId(tagId: String?) {
        _agentState.update { it.copy(selectedTagId = tagId) }
    }

    fun runAgentSkill(skill: AgentSkill) {
        executeAgentRun(skill = skill, prompt = skill.defaultPrompt)
    }

    fun runAgentQuestion(question: String) {
        val clean = question.trim()
        if (clean.isEmpty()) return
        executeAgentRun(skill = AgentSkill.FreeQuestion, prompt = clean)
    }

    private fun executeAgentRun(skill: AgentSkill, prompt: String) {
        ensureAgentRunsObserved()
        agentJob?.cancel()
        val current = _agentState.value
        val scope = NoteScope(
            includeTags = setOfNotNull(current.selectedTagId),
            aiOnly = true,
        )
        _agentState.update {
            it.copy(
                running = true,
                promptDraft = if (skill == AgentSkill.FreeQuestion) prompt else it.promptDraft,
                duplicateGroups = emptyList(),
                statusMessage = null,
                errorMessage = null,
            )
        }
        agentJob = viewModelScope.launch {
            try {
                val (entity, result) = agentRepository.runAnalysis(
                    skill = skill,
                    prompt = prompt,
                    scope = scope,
                    timeRange = current.timeRange,
                )
                val actions = agentRepository.actionsForRun(entity.id)
                val dupGroups = if (skill == AgentSkill.DuplicateCleanup) {
                    runCatching {
                        agentRepository.hydrateDuplicateGroups(result.duplicateGroupSnapshots)
                    }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                _agentState.update {
                    it.copy(
                        running = false,
                        activeRun = entity,
                        activeResult = result,
                        activeActions = actions,
                        duplicateGroups = dupGroups,
                    )
                }
            } catch (cancelled: CancellationException) {
                _agentState.update { it.copy(running = false, statusMessage = "已停止本机整理") }
                throw cancelled
            } catch (_: Exception) {
                _agentState.update {
                    it.copy(running = false, errorMessage = "本机分析未能完成，请重试")
                }
            }
        }
    }

    fun stopCurrentAgentRun() {
        agentJob?.cancel()
    }

    fun loadPastAgentRun(run: AgentRunEntity) {
        ensureAgentRunsObserved()
        val decoded = AgentRunResult.decode(run.resultJson) ?: return
        val pastSkill = AgentSkill.fromId(run.skillId)
        val pastScope = runCatching { NoteScope.decode(run.scopeJson) }.getOrDefault(NoteScope(aiOnly = true))
        val currentRange = _agentState.value.timeRange
        viewModelScope.launch {
            val actions = runCatching { agentRepository.actionsForRun(run.id) }.getOrDefault(emptyList())
            val dupGroups = if (pastSkill == AgentSkill.DuplicateCleanup) {
                runCatching {
                    if (decoded.duplicateGroupSnapshots.isNotEmpty()) {
                        agentRepository.hydrateDuplicateGroups(decoded.duplicateGroupSnapshots)
                    } else {
                        val fallbackRange = io.github.z3275630ops.floatingnotes.data.agent.AgentTimeRange.entries
                            .firstOrNull { it.name == decoded.timeRangeName }
                            ?: currentRange
                        agentRepository.findScopedDuplicateGroups(pastScope, fallbackRange)
                    }
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            _agentState.update {
                it.copy(
                    running = false,
                    activeRun = run,
                    activeResult = decoded,
                    activeActions = actions,
                    duplicateGroups = dupGroups,
                    statusMessage = null,
                    errorMessage = null,
                )
            }
        }
    }

    fun commitMergeDuplicateGroup(group: DuplicateNoteGroup, moveDuplicatesToTrash: Boolean) {
        val run = _agentState.value.activeRun
        viewModelScope.launch {
            try {
                val commitRes = persistenceMutex.withLock {
                    val srcIds = buildSet {
                        add(group.primaryNote.id)
                        group.duplicateNotes.forEach { add(it.id) }
                    }
                    val activeSession = editor
                    if (moveDuplicatesToTrash && activeSession != null && activeSession.draft.noteId in srcIds) {
                        stopActiveRecorderBeforeClose(activeSession)
                        flushOpenEditorLocked()
                    }
                    val result = agentRepository.commitMergeDuplicateGroup(
                        group = group,
                        moveDuplicatesToTrash = moveDuplicatesToTrash,
                        runId = run?.id,
                    )
                    if (result is AgentActionCommitResult.Committed && moveDuplicatesToTrash) {
                        if (activeSession != null && activeSession.draft.noteId in srcIds) {
                            deleteDraftExtras(activeSession.draft.id)
                        }
                        closeEditorIfMatchingLocked(srcIds)
                    }
                    result
                }
                val refreshedActions = if (run != null) {
                    runCatching { agentRepository.actionsForRun(run.id) }
                        .getOrDefault(_agentState.value.activeActions)
                } else {
                    _agentState.value.activeActions
                }
                if (commitRes is AgentActionCommitResult.Committed) {
                    WidgetUpdater.requestRefreshAll(getApplication())
                }
                _agentState.update { cur ->
                    when (commitRes) {
                        is AgentActionCommitResult.Committed -> cur.copy(
                            activeActions = refreshedActions,
                            duplicateGroups = cur.duplicateGroups.filterNot { it.groupId == group.groupId },
                            statusMessage = commitRes.summary,
                            errorMessage = null,
                        )
                        is AgentActionCommitResult.AlreadyApplied -> cur.copy(
                            activeActions = refreshedActions,
                            duplicateGroups = cur.duplicateGroups.filterNot { it.groupId == group.groupId },
                            statusMessage = "该组相似碎片已合并（不重复写入）",
                            errorMessage = null,
                        )
                        is AgentActionCommitResult.ConflictDetected -> cur.copy(
                            activeActions = refreshedActions,
                            errorMessage = "来源笔记已更新或删除（${commitRes.changedNoteTitles.joinToString("、")}），请先重新扫描再合并",
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _agentState.update { it.copy(errorMessage = "合并相似碎片失败，请重试") }
            }
        }
    }

    fun commitSaveAgentRunAsNote() {
        val state = _agentState.value
        val run = state.activeRun ?: return
        val result = state.activeResult ?: return
        viewModelScope.launch {
            try {
                val commitRes = persistenceMutex.withLock {
                    agentRepository.commitSaveInsightAsNote(run, result)
                }
                val refreshedActions = agentRepository.actionsForRun(run.id)
                _agentState.update { cur ->
                    when (commitRes) {
                        is AgentActionCommitResult.Committed -> cur.copy(
                            activeActions = refreshedActions,
                            statusMessage = commitRes.summary,
                        )
                        is AgentActionCommitResult.AlreadyApplied -> cur.copy(
                            activeActions = refreshedActions,
                            statusMessage = "已保存到笔记（不重复写入）",
                        )
                        is AgentActionCommitResult.ConflictDetected -> cur.copy(
                            activeActions = refreshedActions,
                            errorMessage = "来源笔记已更新或删除（${commitRes.changedNoteTitles.joinToString("、")}），请先重新整理再写入",
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _agentState.update { it.copy(errorMessage = "存为笔记失败，请重试") }
            }
        }
    }

    fun commitExtractAgentTodosAsNote() {
        val state = _agentState.value
        val run = state.activeRun ?: return
        val result = state.activeResult ?: return
        if (result.suggestedTodos.isEmpty()) return
        viewModelScope.launch {
            try {
                val commitRes = persistenceMutex.withLock {
                    agentRepository.commitExtractTodosAsNote(run, result, result.suggestedTodos)
                }
                val refreshedActions = agentRepository.actionsForRun(run.id)
                _agentState.update { cur ->
                    when (commitRes) {
                        is AgentActionCommitResult.Committed -> cur.copy(
                            activeActions = refreshedActions,
                            statusMessage = commitRes.summary,
                        )
                        is AgentActionCommitResult.AlreadyApplied -> cur.copy(
                            activeActions = refreshedActions,
                            statusMessage = "待办笔记已生成（不重复写入）",
                        )
                        is AgentActionCommitResult.ConflictDetected -> cur.copy(
                            activeActions = refreshedActions,
                            errorMessage = "来源笔记已变动（${commitRes.changedNoteTitles.joinToString("、")}），请先重新整理",
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _agentState.update { it.copy(errorMessage = "生成待办失败，请重试") }
            }
        }
    }

    fun clearAgentMessage() {
        _agentState.update { it.copy(statusMessage = null, errorMessage = null) }
    }

    fun refreshEditorRelatedNotes(noteId: String?) {
        if (noteId == null) {
            _editorDiscoveryState.update { it.copy(noteId = null, loadingRelated = false, relatedNotes = emptyList()) }
            return
        }
        viewModelScope.launch {
            _editorDiscoveryState.update { it.copy(noteId = noteId, loadingRelated = true) }
            val matches = runCatching { discovery.relatedNotesFor(noteId) }.getOrDefault(emptyList())
            _editorDiscoveryState.update { state ->
                if (editor?.draft?.noteId != noteId) state
                else state.copy(noteId = noteId, loadingRelated = false, relatedNotes = matches)
            }
        }
    }

    fun refreshEditorTagRecommendations(
        draftText: String,
        noteId: String?,
        attachedTagIds: Set<String>,
    ) {
        tagRecJob?.cancel()
        if (draftText.isBlank() && attachedTagIds.isEmpty()) {
            _editorDiscoveryState.update { it.copy(recommendedTags = emptyList()) }
            return
        }
        tagRecJob = viewModelScope.launch {
            delay(250)
            val recs = runCatching {
                discovery.recommendTagsForDraft(
                    draftText = draftText,
                    currentNoteId = noteId,
                    attachedTagIds = attachedTagIds,
                    limit = 4,
                )
            }.getOrDefault(emptyList())
            _editorDiscoveryState.update { state ->
                val currentSession = editor ?: return@update state
                val sessionNoteId = if (currentSession.existingNote) currentSession.draft.noteId else null
                if (sessionNoteId != noteId) state
                else state.copy(recommendedTags = recs)
            }
        }
    }

    private suspend fun refreshRecommendationsForSession(
        session: EditorSession,
        extraTagId: String? = null,
    ) {
        val draftText = session.blocks
            .filter { it.editable }
            .joinToString("\n") { it.text.text.toString() }
        val sessionNoteId = if (session.existingNote) session.draft.noteId else null
        val dbTagIds = if (sessionNoteId != null) {
            withContext(Dispatchers.IO) {
                database.organization().noteTags()
                    .filter { it.noteId == sessionNoteId }
                    .mapTo(HashSet()) { it.tagId }
            }
        } else {
            emptySet()
        }
        val attached = buildSet {
            addAll(dbTagIds)
            addAll(session.pendingTagIds)
            if (extraTagId != null) add(extraTagId)
        }
        refreshEditorTagRecommendations(draftText, sessionNoteId, attached)
    }

    private fun resetEditorDiscoveryForSession(noteId: String?) {
        edgeEchoJob?.cancel()
        tagRecJob?.cancel()
        _editorDiscoveryState.value = EditorDiscoveryState(
            noteId = noteId,
            loadingRelated = noteId != null,
            relatedNotes = emptyList(),
            edgeEcho = null,
            sessionMuted = false,
            globalEchoEnabled = explorePreferences.edgeEchoEnabled,
            lastAutoShownAtMs = 0L,
            dismissedNoteIds = emptySet(),
            recommendedTags = emptyList(),
        )
        if (noteId != null) {
            refreshEditorRelatedNotes(noteId)
        }
        val currentSession = editor
        if (currentSession != null) {
            viewModelScope.launch {
                refreshRecommendationsForSession(currentSession)
            }
        }
    }

    fun onEditorTextActivity(noteId: String?, blockText: String, isComposing: Boolean) {
        edgeEchoJob?.cancel()
        val state = _editorDiscoveryState.value
        val nowMs = System.currentTimeMillis()
        if (!DiscoveryEngines.shouldTriggerAutoEcho(
                globalEnabled = state.globalEchoEnabled,
                sessionMuted = state.sessionMuted,
                isComposing = isComposing,
                draftText = blockText,
                lastAutoShownAtMs = state.lastAutoShownAtMs,
                nowMs = nowMs,
            )
        ) {
            return
        }
        edgeEchoJob = viewModelScope.launch {
            delay(DiscoveryEngines.ECHO_DEBOUNCE_MS)
            val currentSession = editor ?: return@launch
            if (noteId != null && currentSession.draft.noteId != noteId) return@launch
            val checkTimeMs = System.currentTimeMillis()
            val latestState = _editorDiscoveryState.value
            if (!DiscoveryEngines.shouldTriggerAutoEcho(
                    globalEnabled = latestState.globalEchoEnabled,
                    sessionMuted = latestState.sessionMuted,
                    isComposing = false,
                    draftText = blockText,
                    lastAutoShownAtMs = latestState.lastAutoShownAtMs,
                    nowMs = checkTimeMs,
                )
            ) {
                return@launch
            }
            val hit = runCatching {
                discovery.findLiveEdgeEcho(
                    draftText = blockText,
                    currentNoteId = currentSession.draft.noteId,
                    dismissedNoteIds = latestState.dismissedNoteIds,
                )
            }.getOrNull() ?: return@launch

            val verified = runCatching {
                discovery.verifyEdgeEchoHit(
                    hit = hit,
                    currentNoteId = currentSession.draft.noteId,
                    excludedNoteIds = explorePreferences.excludedFindNoteIds + latestState.dismissedNoteIds,
                )
            }.getOrNull() ?: return@launch

            _editorDiscoveryState.update { s ->
                s.copy(
                    edgeEcho = verified,
                    lastAutoShownAtMs = System.currentTimeMillis(),
                )
            }
        }
    }

    fun dismissEdgeEcho(suppressNoteForSession: Boolean = true) {
        _editorDiscoveryState.update { state ->
            val id = state.edgeEcho?.note?.id
            state.copy(
                edgeEcho = null,
                dismissedNoteIds = if (suppressNoteForSession && id != null) state.dismissedNoteIds + id else state.dismissedNoteIds,
            )
        }
    }

    fun muteEdgeEchoForSession() {
        edgeEchoJob?.cancel()
        _editorDiscoveryState.update { it.copy(sessionMuted = true, edgeEcho = null) }
    }

    fun setEdgeEchoGlobalEnabled(enabled: Boolean) {
        explorePreferences.edgeEchoEnabled = enabled
        if (!enabled) edgeEchoJob?.cancel()
        _editorDiscoveryState.update {
            it.copy(
                globalEchoEnabled = enabled,
                edgeEcho = if (enabled) it.edgeEcho else null,
            )
        }
    }

    fun openDiscoveryNote(noteId: String, focusBlockId: String? = null) {
        viewModelScope.launch {
            if (!openNote(noteId = noteId, focusBlockId = focusBlockId)) {
                error = "该笔记暂时无法打开（可能已移入回收站）"
            }
        }
    }

    fun verifyAndOpenEdgeEchoTarget(hit: EvidenceHit) {
        viewModelScope.launch {
            val currentId = editor?.draft?.noteId
            val verified = runCatching { discovery.verifyEdgeEchoHit(hit, currentId) }.getOrNull()
            if (verified == null) {
                _editorDiscoveryState.update { it.copy(edgeEcho = null) }
                error = "该历史笔记已被删除或移入回收站"
                return@launch
            }
            if (!openNote(noteId = verified.note.id, focusBlockId = verified.matchedBlockId)) {
                error = "打开关联笔记失败，请重试"
            }
        }
    }

    private suspend fun flushOpenEditorLocked(throwOnFailure: Boolean = false) {
        val session = editor ?: return
        if (session.closing || session.writing) return
        session.writing = true
        try {
            watcher?.cancelAndJoin()
            stopCheckpoint?.cancelAndJoin()
            val document = session.document()
            val sequence = session.sequenceFor(document)
            if (session.existingNote && document.hasContent && sequence > session.committedSeq) {
                val committed = try {
                    repository.commit(encode(session, document, sequence))
                } catch (stale: StaleDraftException) {
                    tryMergeExternalUpdateAndRetryCommitLocked(session) ?: throw stale
                }
                runCatching { discovery.syncChunksForNote(committed) }
                commitSessionExtras(session)
                session.syncAfterExternalCommit(committed.revision)
            } else if (sequence > session.checkpointSeq && document.hasContent) {
                repository.checkpoint(encode(session, document, sequence))
                session.checkpointSeq = maxOf(session.checkpointSeq, sequence)
                saveDraftExtras(session)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (err: Exception) {
            if (throwOnFailure) throw err
            // Keep existing persisted DB state intact when flushing dirty draft fails outside mandatory gates.
        } finally {
            session.writing = session.closing
            if (!session.closing && editor === session) watch(session)
        }
    }

    private suspend fun closeEditorIfMatchingLocked(noteIds: Set<String>) {
        val session = editor ?: return
        if (session.draft.noteId !in noteIds) return
        session.closing = true
        session.writing = true
        try {
            stopActiveRecorderBeforeClose(session)
            importJob?.cancelAndJoin()
            watcher?.cancelAndJoin()
            stopCheckpoint?.cancelAndJoin()
            editor = null
        } finally {
            session.closing = false
            session.writing = false
        }
    }

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
