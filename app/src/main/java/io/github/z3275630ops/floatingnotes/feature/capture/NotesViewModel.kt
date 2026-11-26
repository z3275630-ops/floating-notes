package io.github.z3275630ops.floatingnotes.feature.capture

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.provider.OpenableColumns
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
import io.github.z3275630ops.floatingnotes.data.agent.AgentMemoryItem
import io.github.z3275630ops.floatingnotes.data.agent.AgentMemorySnapshot
import io.github.z3275630ops.floatingnotes.data.agent.AgentMemoryStore
import io.github.z3275630ops.floatingnotes.data.agent.AgentPreset
import io.github.z3275630ops.floatingnotes.data.agent.AgentPresetStore
import io.github.z3275630ops.floatingnotes.data.agent.AgentRepository
import io.github.z3275630ops.floatingnotes.data.agent.AgentRunEntity
import io.github.z3275630ops.floatingnotes.data.agent.AgentRunResult
import io.github.z3275630ops.floatingnotes.data.agent.AgentSkill
import io.github.z3275630ops.floatingnotes.data.agent.AgentStepTrace
import io.github.z3275630ops.floatingnotes.data.agent.AgentTimeRange
import io.github.z3275630ops.floatingnotes.data.agent.AgentWriteProposal
import io.github.z3275630ops.floatingnotes.data.agent.AiProviderPreset
import io.github.z3275630ops.floatingnotes.data.agent.ByokAiConfigSnapshot
import io.github.z3275630ops.floatingnotes.data.agent.ByokAiConfigStore
import io.github.z3275630ops.floatingnotes.data.agent.CitationLink
import io.github.z3275630ops.floatingnotes.data.agent.HybridAgentRunResult
import io.github.z3275630ops.floatingnotes.data.agent.HybridChatMessage
import io.github.z3275630ops.floatingnotes.data.agent.LlmGateway
import io.github.z3275630ops.floatingnotes.data.agent.LlmToolCallRequest
import io.github.z3275630ops.floatingnotes.data.agent.LlmToolSchema
import io.github.z3275630ops.floatingnotes.data.agent.LlmTurnResult
import io.github.z3275630ops.floatingnotes.data.agent.MemoryCategory
import io.github.z3275630ops.floatingnotes.data.agent.MemoryProposal
import io.github.z3275630ops.floatingnotes.data.agent.OpenAiChatMessage
import io.github.z3275630ops.floatingnotes.data.agent.OpenAiCompatibleClient
import io.github.z3275630ops.floatingnotes.data.agent.OpenAiToolCall
import io.github.z3275630ops.floatingnotes.data.agent.OpenAiToolDefinition
import io.github.z3275630ops.floatingnotes.data.agent.analyzeDualCollisionWithByok
import io.github.z3275630ops.floatingnotes.data.agent.cleanVoiceTranscriptWithByok
import io.github.z3275630ops.floatingnotes.data.agent.normalizeChatCompletionsUrl
import io.github.z3275630ops.floatingnotes.data.backup.BackupConstants
import io.github.z3275630ops.floatingnotes.data.backup.BackupManager
import io.github.z3275630ops.floatingnotes.data.backup.BackupUiState
import io.github.z3275630ops.floatingnotes.data.backup.BackupValidationException
import io.github.z3275630ops.floatingnotes.data.backup.ExternalImportUiState
import io.github.z3275630ops.floatingnotes.data.backup.ExternalNoteImporter
import io.github.z3275630ops.floatingnotes.data.backup.HistoryUiState
import io.github.z3275630ops.floatingnotes.data.backup.MiniJson
import io.github.z3275630ops.floatingnotes.data.backup.RestoreMode
import io.github.z3275630ops.floatingnotes.data.backup.RestoreOutcome
import io.github.z3275630ops.floatingnotes.data.backup.TrashPurgeImpact
import io.github.z3275630ops.floatingnotes.data.discovery.CognitiveMapEngine
import io.github.z3275630ops.floatingnotes.data.discovery.CognitiveMapMode
import io.github.z3275630ops.floatingnotes.data.discovery.CognitiveMapPreferences
import io.github.z3275630ops.floatingnotes.data.discovery.DiscoveryEngines
import io.github.z3275630ops.floatingnotes.data.discovery.DiscoveryRepository
import io.github.z3275630ops.floatingnotes.data.discovery.DualCollisionBridge
import io.github.z3275630ops.floatingnotes.data.discovery.DuplicateNoteGroup
import io.github.z3275630ops.floatingnotes.data.discovery.EvidenceBasketItem
import io.github.z3275630ops.floatingnotes.data.discovery.EvidenceHit
import io.github.z3275630ops.floatingnotes.data.discovery.ExplorePreferences
import io.github.z3275630ops.floatingnotes.data.discovery.LocalRetrievalEngine
import io.github.z3275630ops.floatingnotes.data.discovery.ReviewConfig
import io.github.z3275630ops.floatingnotes.data.local.NoteScope
import io.github.z3275630ops.floatingnotes.data.local.StaleDraftException
import io.github.z3275630ops.floatingnotes.feature.agent.AgentChatTurnUi
import io.github.z3275630ops.floatingnotes.feature.agent.AgentCitationUi
import io.github.z3275630ops.floatingnotes.feature.agent.AgentInsightResultUi
import io.github.z3275630ops.floatingnotes.feature.agent.AgentMemoryCandidateUi
import io.github.z3275630ops.floatingnotes.feature.agent.AgentMemoryItemUi
import io.github.z3275630ops.floatingnotes.feature.agent.AgentPresetUi
import io.github.z3275630ops.floatingnotes.feature.agent.AgentProgressStepUi
import io.github.z3275630ops.floatingnotes.feature.agent.AgentUiState
import io.github.z3275630ops.floatingnotes.feature.agent.AgentWorkspaceTab
import io.github.z3275630ops.floatingnotes.feature.agent.AgentWriteProposalUi
import io.github.z3275630ops.floatingnotes.feature.explore.CognitiveMapUiState
import io.github.z3275630ops.floatingnotes.feature.explore.EditorDiscoveryState
import io.github.z3275630ops.floatingnotes.feature.explore.ExploreUiState
import io.github.z3275630ops.floatingnotes.feature.explore.WalkMode
import io.github.z3275630ops.floatingnotes.feature.home.AiProviderPresetOption
import io.github.z3275630ops.floatingnotes.feature.home.AiSettingsUiState
import io.github.z3275630ops.floatingnotes.feature.home.AppPreferences
import io.github.z3275630ops.floatingnotes.feature.home.AppPreferencesSnapshot
import io.github.z3275630ops.floatingnotes.feature.home.FontScaleTier
import io.github.z3275630ops.floatingnotes.feature.home.NoteSortOrder
import io.github.z3275630ops.floatingnotes.feature.home.defaultAiProviderPresets
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal class EditorBlock(initialOriginal: NoteBlock) {
    private var original by mutableStateOf(initialOriginal)
    val id = initialOriginal.id
    val type = initialOriginal.type
    val editable = initialOriginal.editable
    val text = TextFieldState(initialOriginal.text)
    var checked by mutableStateOf(initialOriginal.checked)
    var dueDate by mutableStateOf(initialOriginal.dueDate)
    var reminderId by mutableStateOf(initialOriginal.reminderId)
    var occurrenceId by mutableStateOf(initialOriginal.occurrenceId)
    var syncedText: String = initialOriginal.text
    var syncedChecked: Boolean = initialOriginal.checked
    var syncedDueDate: String? = initialOriginal.dueDate
    var syncedReminderId: String? = initialOriginal.reminderId
    var syncedOccurrenceId: String? = initialOriginal.occurrenceId
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

    fun replaceOriginal(block: NoteBlock) {
        original = block
    }

    fun markSyncedWith(block: NoteBlock? = null) {
        syncedText = block?.text ?: text.text.toString()
        syncedChecked = block?.checked ?: checked
        syncedDueDate = if (block == null) dueDate else block.dueDate
        syncedReminderId = if (block == null) reminderId else block.reminderId
        syncedOccurrenceId = if (block == null) occurrenceId else block.occurrenceId
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
    internal val syncedDocumentBlocks = HashMap<String, NoteBlock>().apply {
        document.blocks.forEach { put(it.id, it) }
    }
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

    fun setSyncedBaseline(baselineBlocks: List<NoteBlock>) {
        syncedDocumentBlocks.clear()
        baselineBlocks.forEach { syncedDocumentBlocks[it.id] = it }
    }

    fun document() = NoteDocument(blocks.map { it.documentBlock() })

    fun sequenceFor(document: NoteDocument): Long {
        if (document != trackedDocument) {
            trackedDocument = document
            sequence++
        }
        return sequence
    }

    fun acknowledgeCommit(note: NoteEntity, savedSequence: Long = committedSeq) {
        if (note.revision < draft.baseRevision) return
        val persisted = NoteDocument.decode(note.documentJson)
        draft = draft.copy(baseRevision = note.revision)
        val current = document()
        val savedThrough = if (current != trackedDocument && current.encode() == note.documentJson) {
            trackedDocument = current
            sequence
        } else {
            savedSequence
        }
        checkpointSeq = maxOf(checkpointSeq, savedThrough)
        committedSeq = maxOf(committedSeq, savedThrough)
        setSyncedBaseline(persisted.blocks)
        blocks.forEach { block ->
            syncedDocumentBlocks[block.id]?.let(block::markSyncedWith)
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
        setSyncedBaseline(restoredDoc.blocks)
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
        afterBlockId: String? = null,
    ): Boolean {
        if (writing || closing || note.id == draft.noteId) return false
        val after = if (afterBlockId == null) {
            blocks.size
        } else {
            blocks.indexOfFirst { it.id == afterBlockId }.takeIf { it >= 0 }?.plus(1) ?: return false
        }
        val label = labelOverride?.takeIf { it.isNotBlank() } ?: referenceLabel(note)
        blocks.add(
            after,
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
        ensureTailEditableBlock()
        return true
    }

    fun insertTranscript(block: EditorBlock, text: String): Boolean {
        if (writing || closing) return false
        val position = blocks.indexOf(block)
        if (position < 0) return false
        blocks.add(position + 1, EditorBlock(NoteBlock.paragraph(text)))
        return true
    }

    fun insertCleanedTranscriptAndTodos(
        block: EditorBlock,
        cleanedText: String,
        todos: List<String>,
    ): Boolean {
        if (writing || closing) return false
        val position = blocks.indexOf(block)
        if (position < 0) return false
        var insertAt = position + 1
        val cleanText = cleanedText.trim()
        if (cleanText.isNotEmpty()) {
            blocks.add(insertAt, EditorBlock(NoteBlock.paragraph(cleanText)))
            insertAt++
        }
        todos.map { it.trim() }.filter { it.isNotEmpty() }.forEach { todo ->
            blocks.add(insertAt, EditorBlock(NoteBlock.checkbox(text = todo, checked = false)))
            insertAt++
        }
        ensureTailEditableBlock()
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

internal data class VoiceCleanUiState(
    val activeAttachmentId: String? = null,
    val cleaning: Boolean = false,
    val cleanedTranscriptPreview: String? = null,
    val extractedTodoPreviews: List<String> = emptyList(),
    val notice: String? = null,
)

internal class NotesViewModel(application: Application) : AndroidViewModel(application) {
    private val database = NotesDatabase.open(application)
    internal val repository = NotesRepository(database)
    val taskPreferences = TaskPreferences(application)
    internal val appPreferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AppPreferences(application) }
    val appPreferencesSnapshot: StateFlow<AppPreferencesSnapshot> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        appPreferences.observeSnapshot()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), appPreferences.snapshot())
    }
    private val _attachmentStorageBytes = MutableStateFlow(0L)
    val attachmentStorageBytes: StateFlow<Long> = _attachmentStorageBytes.asStateFlow()
    val attachments = AttachmentStore(application)
    internal val backupManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BackupManager(application, database, attachments)
    }
    val organization = OrganizationRepository(database)
    val search by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { SearchRepository(database) }
    val explorePreferences = ExplorePreferences(application)
    internal val cognitiveMapPreferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        CognitiveMapPreferences(application.filesDir)
    }
    internal val widgetPreferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { WidgetPreferences(application) }
    internal val discovery by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DiscoveryRepository(database, repository, explorePreferences)
    }
    internal val byokAiConfigStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { ByokAiConfigStore(application) }
    internal val openAiClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { OpenAiCompatibleClient() }
    internal val agentMemoryStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AgentMemoryStore(application.filesDir) }
    internal val agentPresetStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AgentPresetStore(application.filesDir) }
    internal val agentRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AgentRepository(database, repository, organization, discovery)
    }
    private val _aiSettingsState = MutableStateFlow(AiSettingsUiState())
    val aiSettingsState: StateFlow<AiSettingsUiState> = _aiSettingsState.asStateFlow()
    private val _voiceCleanState = MutableStateFlow(VoiceCleanUiState())
    val voiceCleanState: StateFlow<VoiceCleanUiState> = _voiceCleanState.asStateFlow()
    private val _agentState = MutableStateFlow(AgentUiState())
    val agentState: StateFlow<AgentUiState> = _agentState.asStateFlow()
    private var agentJob: Job? = null
    private var aiConnectionTestJob: Job? = null
    private var voiceCleanJob: Job? = null
    private var agentRunsObserved = false
    private var pendingClearAiKey = false
    private var testedEndpointSnapshot: Pair<String, Boolean?>? = null
    private var testedEndpointMessage: String? = null
    private var activeChatHistory: List<HybridChatMessage> = emptyList()
    private var lastHybridPrompt: String = ""
    private var lastHybridSkill: AgentSkill = AgentSkill.FreeQuestion
    private val cachedWriteProposals = LinkedHashMap<String, AgentWriteProposal>()
    private val cachedMemoryProposals = LinkedHashMap<String, MemoryProposal>()

    fun refreshAttachmentStorageBytes() {
        viewModelScope.launch {
            _attachmentStorageBytes.value = runCatching { attachments.totalStorageBytes() }.getOrDefault(0L)
        }
    }

    fun setFontScaleTier(tier: FontScaleTier) {
        appPreferences.fontScaleTier = tier
    }

    fun setNoteSortOrder(order: NoteSortOrder) {
        appPreferences.noteSortOrder = order
    }

    fun setEnableSlashShortcuts(enabled: Boolean) {
        appPreferences.enableSlashShortcuts = enabled
    }

    fun setTrashRetentionDays(days: Int) {
        appPreferences.trashRetentionDays = days
    }

    fun setOpenTasksFirst(enabled: Boolean) {
        appPreferences.openTasksFirst = enabled
    }

    fun setStrongAlarmEnabled(enabled: Boolean) {
        val previous = appPreferences.strongAlarmEnabled
        appPreferences.strongAlarmEnabled = enabled
        if (previous != enabled) {
            rescheduleActiveReminders()
        }
    }

    private fun rescheduleActiveReminders() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val nowMs = System.currentTimeMillis()
                val app = getApplication<Application>()
                repository.activeReminders().forEach { reminder ->
                    val triggerMs = reminder.triggerInstantMs
                    if (triggerMs != null && triggerMs > nowMs) {
                        ReminderScheduler.scheduleReminder(app, reminder)
                    }
                }
            }
        }
    }

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
        viewModelScope.launch(Dispatchers.IO) {
            loadInitialAiAndAgentStores()
        }
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

    private val _externalImportState = MutableStateFlow(ExternalImportUiState())
    val externalImportState: StateFlow<ExternalImportUiState> = _externalImportState.asStateFlow()

    private val _exploreState = MutableStateFlow(ExploreUiState())
    val exploreState: StateFlow<ExploreUiState> = _exploreState.asStateFlow()

    private val _cognitiveMapState = MutableStateFlow(CognitiveMapUiState())
    val cognitiveMapState: StateFlow<CognitiveMapUiState> = _cognitiveMapState.asStateFlow()

    private val _editorDiscoveryState = MutableStateFlow(
        EditorDiscoveryState(globalEchoEnabled = explorePreferences.edgeEchoEnabled),
    )
    val editorDiscoveryState: StateFlow<EditorDiscoveryState> = _editorDiscoveryState.asStateFlow()

    private var edgeEchoJob: Job? = null
    private var tagRecJob: Job? = null
    private var findSearchJob: Job? = null
    private var findSearchSeq = 0L
    private var cognitiveMapJob: Job? = null
    private var externalImportJob: Job? = null

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

    val isForeground: Boolean
        get() = foreground

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
                            retentionDays = appPreferences.trashRetentionDays,
                            excludeNoteIds = setOfNotNull(openNoteId),
                        )
                        if (result.deletedNoteIds.isNotEmpty()) {
                            runCatching { discovery.removeChunksForNoteIds(result.deletedNoteIds) }
                            val app = getApplication<Application>()
                            result.cancelledReminderIds.forEach { ReminderScheduler.cancelReminder(app, it) }
                            attachments.collectUnreferenced(database, backupManager.activeLeaseIds())
                            _attachmentStorageBytes.value = runCatching { attachments.totalStorageBytes() }.getOrDefault(0L)
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
                    _attachmentStorageBytes.value = runCatching { attachments.totalStorageBytes() }.getOrDefault(0L)
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
                    val appendedBlocks = buildList {
                        add(block)
                        if (!block.editable) {
                            add(NoteBlock.paragraph())
                        }
                    }
                    val document = NoteDocument(session.document().blocks + appendedBlocks)
                    val sequence = session.sequenceFor(document)
                    try {
                        repository.checkpoint(encode(session, document, sequence))
                    } catch (_: Exception) {
                        throw AttachmentImportException(
                            "无法保存附件到草稿，请检查本机空间后重试",
                        )
                    }
                    appendedBlocks.forEach { session.blocks.add(EditorBlock(it)) }
                    session.checkpointSeq = sequence
                    _attachmentStorageBytes.value = runCatching { attachments.totalStorageBytes() }.getOrDefault(0L)
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
                (captureSlot != null && current.draft.slot != captureSlot) ||
                (noteId == null && captureSlot == null && current.draft.slot != "manual")
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
            val baselineBlocks = note?.let {
                runCatching { NoteDocument.decode(it.documentJson).blocks }.getOrNull()
            }
            startEditor(draft, note != null, resolvedFocusBlockId, preloadedTasks, baselineBlocks)
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
        baselineBlocks: List<NoteBlock>? = null,
    ) {
        val session = EditorSession(draft, NoteDocument.decode(draft.documentJson), existingNote).apply {
            this.focusBlockId = focusBlockId
            this.onPendingExtrasChanged = { saveDraftExtras(this) }
            if (baselineBlocks != null) {
                setSyncedBaseline(baselineBlocks)
            }
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

        val mergedById = LinkedHashMap<String, NoteBlock>(freshDoc.blocks.size)
        for (freshBlock in freshDoc.blocks) {
            val local = sessionById[freshBlock.id] ?: return null
            val localBlock = local.documentBlock()
            val synced = session.syncedDocumentBlocks[freshBlock.id]
            val merged = mergeExternalBlockUpdate(
                synced = synced,
                fresh = freshBlock,
                local = localBlock,
                localFallbackText = local.syncedText,
                localFallbackChecked = local.syncedChecked,
                localFallbackDueDate = local.syncedDueDate,
                localFallbackReminderId = local.syncedReminderId,
                localFallbackOccurrenceId = local.syncedOccurrenceId,
                localPendingSchedule = local.pendingScheduleChanged,
            ) ?: return null
            mergedById[freshBlock.id] = merged
        }
        for (local in session.blocks) {
            if (local.id !in freshById) {
                val existedBefore = local.id in session.syncedDocumentBlocks || local.syncedText.isNotEmpty()
                if (existedBefore) return null
            }
        }

        for (freshBlock in freshDoc.blocks) {
            val local = sessionById[freshBlock.id] ?: continue
            val merged = mergedById[freshBlock.id] ?: continue
            val localBlock = local.documentBlock()
            if (merged != localBlock) {
                if (merged.type != local.type) {
                    val idx = session.blocks.indexOf(local)
                    if (idx >= 0) {
                        session.blocks[idx] = local.withUpdatedDocumentBlock(merged).apply {
                            dueDate = merged.dueDate
                            reminderId = merged.reminderId
                            occurrenceId = merged.occurrenceId
                            markSyncedWith(freshBlock)
                        }
                    }
                } else {
                    local.replaceOriginal(merged)
                    if (local.text.text.toString() != merged.text) {
                        local.text.edit { replace(0, length, merged.text) }
                    }
                    local.checked = merged.checked
                    local.dueDate = merged.dueDate
                    local.reminderId = merged.reminderId
                    local.occurrenceId = merged.occurrenceId
                    local.markSyncedWith(freshBlock)
                }
            } else {
                local.markSyncedWith(freshBlock)
            }
            session.syncedDocumentBlocks[freshBlock.id] = freshBlock
        }
        val sourceItems = runCatching { repository.observeTaskItems().first() }.getOrDefault(taskItems.value)
        applyTaskMetadataToSession(session, sourceItems)
        session.draft = session.draft.copy(baseRevision = fresh.revision)
        val mergedDoc = session.document()
        val mergedSeq = session.sequenceFor(mergedDoc)
        val committed = repository.commit(encode(session, mergedDoc, mergedSeq))
        syncChunksAsync(committed)
        session.acknowledgeCommit(committed, mergedSeq)
        commitSessionExtras(session)
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
                                    session.acknowledgeCommit(committed, sequence)
                                    commitSessionExtras(session)
                                    session.status = if (session.sequenceFor(session.document()) <= session.committedSeq) {
                                        "已保存到本机"
                                    } else {
                                        "等待写入"
                                    }
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

    suspend fun saveOpenNote(session: EditorSession): Boolean {
        if (editor !== session || !session.existingNote) return false
        val hasChanges = session.sequenceFor(session.document()) > session.committedSeq ||
            session.pendingTagIds.isNotEmpty() || session.blocks.any { it.pendingScheduleChanged }
        if (!persistAndStopSession(session, save = hasChanges)) return false
        if (editor !== session) return false
        watch(session)
        return true
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
                        session.acknowledgeCommit(committed, sequence)
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
                    WidgetUpdater.requestRefreshAll(getApplication())
                }
            }
            val editedDuringCommit = !discard && session.sequenceFor(session.document()) >
                maxOf(session.checkpointSeq, session.committedSeq)
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
        for (block in session.blocks.toList()) {
            if (!block.pendingScheduleChanged || block.type != "checkbox" ||
                session.syncedDocumentBlocks[block.id]?.type != "checkbox" ||
                session.blocks.none { it === block }
            ) continue
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
            session.blocks.firstOrNull { it.id == block.id && it.type == "checkbox" }
                ?.let { applyScheduleResultToBlock(it, result) }
            session.acknowledgeCommit(result.note)
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
                    try {
                        repository.checkpoint(encode(session, document, sequence))
                        session.checkpointSeq = maxOf(session.checkpointSeq, sequence)
                        saveDraftExtras(session)
                    } catch (stale: StaleDraftException) {
                        if (session.existingNote) {
                            val merged = tryMergeExternalUpdateAndRetryCommitLocked(session)
                            if (merged != null) {
                                session.status = "已保存到本机"
                            } else {
                                throw stale
                            }
                        } else {
                            throw stale
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: StaleDraftException) {
                session.status = "检测到外部修改，已保留草稿，请核对后重存"
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
                            session.acknowledgeCommit(committed, sequence)
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
                                session.acknowledgeCommit(result.note)
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
                                session.acknowledgeCommit(committed, sequence)
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
                            session.acknowledgeCommit(result.note)
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
                                session.acknowledgeCommit(committed, sequence)
                            }
                        }
                        val noteRow = repository.loadNote(session.draft.noteId)
                        val blockPersisted = noteRow?.let {
                            runCatching { NoteDocument.decode(it.documentJson).findBlock(block.id) }.getOrNull()
                        } != null
                        if (blockPersisted) {
                            val result = clearTaskSchedule(session.draft.noteId, block.id)
                            applyScheduleResultToBlock(block, result)
                            session.acknowledgeCommit(result.note)
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

    fun attachTagToSession(session: EditorSession, tagId: String, onAttached: () -> Unit = {}) {
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
                    onAttached()
                    refreshRecommendationsForSession(session, extraTagId = tagId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    error = "添加标签失败，可重试"
                }
            }
        }
    }

    fun createAndAttachTag(
        session: EditorSession,
        name: String,
        existingTagId: String? = null,
        onAttached: () -> Unit = {},
    ) {
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
                    onAttached()
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

    fun setNoteAiPolicy(noteId: String, aiPolicy: String) {
        val validated = NotesRepository.validateAiPolicy(aiPolicy)
        viewModelScope.launch {
            withContext(NonCancellable) {
                persistenceMutex.withLock {
                    try {
                        val activeSession = editor
                        if (activeSession != null && activeSession.draft.noteId == noteId) {
                            flushOpenEditorLocked()
                        }
                        repository.setAiPolicy(noteId, validated)
                        val updated = repository.loadNote(noteId)
                        if (updated != null) {
                            if (activeSession != null && activeSession.draft.noteId == noteId) {
                                activeSession.draft = activeSession.draft.copy(
                                    baseRevision = maxOf(activeSession.draft.baseRevision, updated.revision),
                                )
                            }
                            runCatching { discovery.syncChunksForNote(updated) }
                        }
                        WidgetUpdater.requestRefreshAll(getApplication())
                        error = null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        error = "更新 AI 参与设置失败，请重试"
                    }
                }
            }
        }
    }

    fun batchAddTag(noteIds: Collection<String>, tagId: String) {
        val normalized = NotesRepository.normalizeBatchNoteIds(noteIds)
        if (normalized.isEmpty()) return
        viewModelScope.launch {
            withContext(NonCancellable) {
                persistenceMutex.withLock {
                    try {
                        organization.batchAddTag(normalized, tagId)
                        error = null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        error = "批量添加标签失败，请重试"
                    }
                }
            }
        }
    }

    fun batchAssignNotebook(noteIds: Collection<String>, notebookId: String?) {
        val normalized = NotesRepository.normalizeBatchNoteIds(noteIds)
        if (normalized.isEmpty()) return
        viewModelScope.launch {
            withContext(NonCancellable) {
                persistenceMutex.withLock {
                    try {
                        organization.batchAssignNotebook(normalized, notebookId)
                        error = null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        error = "批量移动笔记本失败，请重试"
                    }
                }
            }
        }
    }

    fun batchSetArchived(noteIds: Collection<String>, archived: Boolean) {
        val normalized = NotesRepository.normalizeBatchNoteIds(noteIds)
        if (normalized.isEmpty()) return
        val idSet = normalized.toSet()
        viewModelScope.launch {
            withContext(NonCancellable) {
                persistenceMutex.withLock {
                    try {
                        val activeSession = editor
                        if (activeSession != null && activeSession.draft.noteId in idSet) {
                            flushOpenEditorLocked()
                        }
                        repository.batchSetArchived(normalized, archived)
                        if (activeSession != null && activeSession.draft.noteId in idSet) {
                            repository.loadNote(activeSession.draft.noteId)?.let { updated ->
                                activeSession.draft = activeSession.draft.copy(
                                    baseRevision = maxOf(activeSession.draft.baseRevision, updated.revision),
                                )
                            }
                        }
                        WidgetUpdater.requestRefreshAll(getApplication())
                        error = null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        error = "批量归档操作失败，请重试"
                    }
                }
            }
        }
    }

    fun batchSoftDelete(noteIds: Collection<String>) {
        val normalized = NotesRepository.normalizeBatchNoteIds(noteIds)
        if (normalized.isEmpty()) return
        val idSet = normalized.toSet()
        viewModelScope.launch {
            withContext(NonCancellable) {
                persistenceMutex.withLock {
                    try {
                        val activeSession = editor
                        if (activeSession != null && activeSession.draft.noteId in idSet) {
                            stopActiveRecorderBeforeClose(activeSession)
                            flushOpenEditorLocked()
                            deleteDraftExtras(activeSession.draft.id)
                        }
                        closeEditorIfMatchingLocked(idSet)
                        val reminderIds = repository.batchSoftDelete(normalized)
                        runCatching { discovery.removeChunksForNoteIds(normalized) }
                        val app = getApplication<Application>()
                        reminderIds.forEach { ReminderScheduler.cancelReminder(app, it) }
                        if (_historyState.value.noteId in idSet) {
                            _historyState.value = HistoryUiState()
                        }
                        WidgetUpdater.requestRefreshAll(app)
                        error = null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        error = "批量移入回收站失败，请重试"
                    }
                }
            }
        }
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
                        _attachmentStorageBytes.value = runCatching { attachments.totalStorageBytes() }.getOrDefault(0L)
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
                        _attachmentStorageBytes.value = runCatching { attachments.totalStorageBytes() }.getOrDefault(0L)
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
                            _attachmentStorageBytes.value = runCatching { attachments.totalStorageBytes() }.getOrDefault(0L)
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
        _externalImportState.update {
            it.copy(outcome = null, error = null)
        }
    }

    fun pickExternalImport(sourceUri: Uri, displayName: String = "") {
        if (_externalImportState.value.busy || _backupState.value.busy) return
        externalImportJob?.cancel()
        _externalImportState.update {
            it.copy(busy = true, preview = null, outcome = null, error = null)
        }
        externalImportJob = viewModelScope.launch {
            var tempFile: File? = null
            try {
                val app = getApplication<Application>()
                val preview = withContext(Dispatchers.IO) {
                    val resolvedDisplayName = displayName.trim().ifEmpty {
                        resolveExternalDocumentDisplayName(app, sourceUri)
                    }
                    val cacheRoot = File(app.cacheDir, "external-import").apply { mkdirs() }
                    tempFile = File.createTempFile("import-", ".tmp", cacheRoot)
                    val target = tempFile!!
                    val input = app.contentResolver.openInputStream(sourceUri)
                        ?: throw BackupValidationException("无法打开所选文件，请确认文件权限后重试")
                    input.use { stream ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var total = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = stream.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > BackupConstants.MAX_ENTRY_BYTES) {
                                    throw BackupValidationException("导入文件过大（超过 128 MB 上限）")
                                }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    val activeNotes = database.notes().allNotes().filter { it.deletedAt == null }
                    val existingHashes = activeNotes.mapTo(HashSet()) { it.contentHash }
                    val existingTexts = activeNotes.mapTo(HashSet()) { it.searchText }
                    ExternalNoteImporter.previewImport(
                        sourceFile = target,
                        displayName = resolvedDisplayName,
                        existingContentHashes = existingHashes,
                        existingNormalizedTexts = existingTexts,
                    )
                }
                _externalImportState.update {
                    it.copy(busy = false, preview = preview, error = null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (err: Exception) {
                _externalImportState.update {
                    it.copy(
                        busy = false,
                        preview = null,
                        error = err.message ?: "无法解析导入文件，请确认格式为 Markdown / ZIP / flomo HTML / JSON",
                    )
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { tempFile?.delete() }
                }
            }
        }
    }

    private fun resolveExternalDocumentDisplayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = cursor.getString(idx)?.trim()
                        if (!name.isNullOrEmpty()) return name
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "import"
    }

    fun toggleSkipDuplicateImports(skip: Boolean) {
        _externalImportState.update { it.copy(skipDuplicates = skip) }
    }

    fun dismissExternalImport() {
        _externalImportState.update { it.copy(preview = null, error = null) }
    }

    fun confirmExternalImport() {
        val current = _externalImportState.value
        val preview = current.preview ?: return
        if (current.busy || _backupState.value.busy) return
        _externalImportState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            withContext(NonCancellable) {
                persistenceMutex.withLock {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            ExternalNoteImporter.commitImport(
                                preview = preview,
                                skipDuplicates = current.skipDuplicates,
                                database = database,
                            )
                        }
                        val cov = runCatching { discovery.ensureIndexFresh(forceRebuild = false) }.getOrNull()
                        if (cov != null) {
                            _exploreState.update { it.copy(coverage = cov) }
                        }
                        if (_cognitiveMapState.value.visible) {
                            refreshCognitiveMap()
                        }
                        _externalImportState.update {
                            it.copy(
                                busy = false,
                                preview = null,
                                outcome = result,
                                error = null,
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (err: Exception) {
                        _externalImportState.update {
                            it.copy(
                                busy = false,
                                error = err.message ?: "导入笔记失败，现有笔记库未受影响",
                            )
                        }
                    }
                }
            }
        }
    }

    fun clearExploreMessage() {
        _exploreState.update { it.copy(statusMessage = null, errorMessage = null) }
    }

    fun setWidgetPrivacyModeEnabled(enabled: Boolean) {
        appPreferences.widgetPrivacyMask = enabled
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

    fun analyzeCurrentDualPair(userReflection: String = "") {
        val current = _exploreState.value
        val pair = current.dualPair ?: return
        val wasFarFetched = current.dualBridgeFarFetched
        viewModelScope.launch {
            runCatching {
                val analyzed = discovery.analyzeDualCollisionRefreshed(pair)
                if (analyzed !is DiscoveryRepository.DualBridgeAnalyzeResult.Ready) {
                    return@runCatching analyzed
                }
                val (snapshot, apiKey) = withContext(Dispatchers.IO) {
                    byokAiConfigStore.resolveCredentials()
                }
                if (!snapshot.isReadyForRemote) {
                    return@runCatching analyzed
                }
                val leftText = listOf(analyzed.pair.leftTitle, analyzed.pair.leftBody)
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                val rightText = listOf(analyzed.pair.rightTitle, analyzed.pair.rightBody)
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                val aiRes = analyzeDualCollisionWithByok(
                    leftText = leftText,
                    rightText = rightText,
                    reflection = userReflection,
                    isFarFetched = wasFarFetched,
                    client = openAiClient,
                    snapshot = snapshot,
                    apiKey = apiKey,
                )
                if (aiRes.usedRemoteAi) {
                    val points = buildList {
                        if (aiRes.sharedThread.isNotBlank()) add("共通脉络：${aiRes.sharedThread}")
                        if (aiRes.actionPrompt.isNotBlank()) add("思考追问：${aiRes.actionPrompt}")
                    }.ifEmpty { analyzed.bridge.points }
                    val enhancedBridge = DualCollisionBridge(
                        headline = aiRes.bridgeSummary.ifBlank { analyzed.bridge.headline },
                        points = points,
                        engineNotice = aiRes.note ?: "由 ${snapshot.model} 生成双卡对撞洞察",
                    )
                    DiscoveryRepository.DualBridgeAnalyzeResult.Ready(analyzed.pair, enhancedBridge)
                } else {
                    val fallbackNotice = aiRes.note?.takeIf { it.isNotBlank() }
                        ?: analyzed.bridge.engineNotice
                    DiscoveryRepository.DualBridgeAnalyzeResult.Ready(
                        analyzed.pair,
                        analyzed.bridge.copy(engineNotice = fallbackNotice),
                    )
                }
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
        val remoteReady = _agentState.value.remoteAiEnabled
        _exploreState.update {
            it.copy(
                dualBridgeFarFetched = true,
                statusMessage = if (remoteReady) {
                    "已标记这组关联偏牵强，可点“重新分析”评估边界或“换一组”"
                } else {
                    "已标记这组关联偏牵强，可以点“换一组”再试试"
                },
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

    fun openCognitiveMap() {
        _cognitiveMapState.update { it.copy(visible = true, errorMessage = null) }
        refreshCognitiveMap()
    }

    fun closeCognitiveMap() {
        cognitiveMapJob?.cancel()
        _cognitiveMapState.update { it.copy(visible = false, loading = false) }
    }

    fun setCognitiveMapMode(mode: CognitiveMapMode) {
        if (_cognitiveMapState.value.mode == mode) return
        _cognitiveMapState.update { it.copy(mode = mode, errorMessage = null) }
        refreshCognitiveMap()
    }

    fun setCognitiveMapMonthFilter(month: String?) {
        if (_cognitiveMapState.value.activeMonthFilter == month) return
        _cognitiveMapState.update { it.copy(activeMonthFilter = month, errorMessage = null) }
        refreshCognitiveMap()
    }

    fun setCognitiveMapReclusterForMonth(recluster: Boolean) {
        if (_cognitiveMapState.value.reclusterForMonth == recluster) return
        _cognitiveMapState.update { it.copy(reclusterForMonth = recluster, errorMessage = null) }
        refreshCognitiveMap()
    }

    fun renameCognitiveMapCluster(clusterId: String, customName: String) {
        viewModelScope.launch {
            runCatching {
                val updatedNames = withContext(Dispatchers.IO) {
                    cognitiveMapPreferences.renameCluster(clusterId, customName)
                }
                _cognitiveMapState.update { state ->
                    val currentSnapshot = state.snapshot
                    val patchedSnapshot = currentSnapshot?.copy(
                        clusters = currentSnapshot.clusters.map { cluster ->
                            if (cluster.clusterId == clusterId) {
                                cluster.copy(displayLabel = updatedNames[clusterId] ?: cluster.machineLabel)
                            } else {
                                cluster
                            }
                        },
                    )
                    state.copy(
                        customClusterNames = updatedNames,
                        snapshot = patchedSnapshot,
                        errorMessage = null,
                    )
                }
            }.onFailure {
                if (it is CancellationException) throw it
                _cognitiveMapState.update { s -> s.copy(errorMessage = "保存主题名称失败，请重试") }
            }
        }
    }

    private fun refreshCognitiveMap() {
        cognitiveMapJob?.cancel()
        _cognitiveMapState.update { it.copy(loading = true, errorMessage = null) }
        cognitiveMapJob = viewModelScope.launch {
            try {
                val stateSnapshot = _cognitiveMapState.value
                val (renames, snapshot) = withContext(Dispatchers.IO) {
                    val loadedRenames = cognitiveMapPreferences.loadRenames()
                    val notesDao = database.notes()
                    val orgDao = database.organization()
                    val candidates = notesDao.cognitiveMapCandidates()
                    val tags = orgDao.tags()
                    val noteTags = orgDao.noteTags()
                    val links = orgDao.noteLinks()
                    val input = withContext(Dispatchers.Default) {
                        CognitiveMapEngine.prepareMap(
                            candidates, tags, noteTags, links,
                            stateSnapshot.activeMonthFilter, stateSnapshot.reclusterForMonth,
                        )
                    }
                    val texts = HashMap<String, String>(input.sampled.size)
                    for (batch in input.sampled.map { it.id }.chunked(100)) {
                        for (item in notesDao.cognitiveMapText(batch)) texts[item.id] = item.text
                    }
                    val built = withContext(Dispatchers.Default) {
                        val job = currentCoroutineContext()
                        CognitiveMapEngine.buildMap(input, texts, loadedRenames, stateSnapshot.mode) {
                            job.ensureActive()
                        }
                    }
                    loadedRenames to built
                }
                _cognitiveMapState.update {
                    it.copy(
                        loading = false,
                        customClusterNames = renames,
                        snapshot = snapshot,
                        errorMessage = null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _cognitiveMapState.update {
                    it.copy(loading = false, errorMessage = "构建认知地图失败，请重试")
                }
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
        if (skill == AgentSkill.DuplicateCleanup) {
            executeDuplicateCleanupRun()
        } else {
            executeHybridAgentRun(skill = skill, prompt = skill.defaultPrompt, isFollowUp = false, forceLocal = false)
        }
    }

    fun runAgentQuestion(question: String) {
        val clean = question.trim()
        if (clean.isEmpty()) return
        executeHybridAgentRun(skill = AgentSkill.FreeQuestion, prompt = clean, isFollowUp = false, forceLocal = false)
    }

    private fun executeDuplicateCleanupRun() {
        ensureAgentRunsObserved()
        agentJob?.cancel()
        val current = _agentState.value
        val scope = NoteScope(
            includeTags = setOfNotNull(current.selectedTagId),
            aiOnly = true,
        )
        activeChatHistory = emptyList()
        cachedWriteProposals.clear()
        _agentState.update {
            it.copy(
                running = true,
                duplicateGroups = emptyList(),
                liveSteps = emptyList(),
                lastUsedRemoteAi = false,
                fallbackReason = null,
                chatTurns = emptyList(),
                writeProposals = emptyList(),
                statusMessage = null,
                errorMessage = null,
            )
        }
        agentJob = viewModelScope.launch {
            try {
                val (entity, result) = agentRepository.runAnalysis(
                    skill = AgentSkill.DuplicateCleanup,
                    prompt = AgentSkill.DuplicateCleanup.defaultPrompt,
                    scope = scope,
                    timeRange = current.timeRange,
                )
                val actions = agentRepository.actionsForRun(entity.id)
                val dupGroups = runCatching {
                    agentRepository.hydrateDuplicateGroups(result.duplicateGroupSnapshots)
                }.getOrDefault(emptyList())
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

    // --- 浮记 2.0 第二块：BYOK AI 设置 / Hybrid Agent / 洞察预设 / 长期记忆 / 语音克制整理 ---

    private suspend fun loadInitialAiAndAgentStores() {
        val aiSnapshot = runCatching { byokAiConfigStore.loadSnapshot() }.getOrDefault(ByokAiConfigSnapshot())
        val presets = runCatching { agentPresetStore.loadPresets() }.getOrDefault(AgentPresetStore.BUILT_IN_PRESETS)
        val memorySnapshot = runCatching { agentMemoryStore.loadSnapshot() }.getOrDefault(AgentMemorySnapshot())
        val noteTitles = runCatching {
            database.notes().allNotes().associate { it.id to LocalRetrievalEngine.displayNoteTitle(it) }
        }.getOrDefault(emptyMap())
        applyAiSnapshotToStates(aiSnapshot, resetDraftKey = true)
        _agentState.update {
            it.copy(
                insightPresets = presets.map { p -> p.toUi() },
                memoryAutoReference = memorySnapshot.memoryEnabledInAgent,
                memoryItems = memorySnapshot.items.map { item -> item.toUi(noteTitles) },
            )
        }
    }

    private fun applyAiSnapshotToStates(snapshot: ByokAiConfigSnapshot, resetDraftKey: Boolean) {
        _aiSettingsState.update { cur ->
            cur.copy(
                presetId = snapshot.preset.id,
                enabled = snapshot.enabled,
                baseUrl = snapshot.baseUrl,
                model = snapshot.model,
                apiKeyDraft = if (resetDraftKey) "" else cur.apiKeyDraft,
                hasSavedApiKey = snapshot.hasApiKey,
                maskedApiKey = snapshot.maskedApiKey,
                requiresApiKey = snapshot.preset.requiresApiKey,
                testingConnection = false,
                lastTestSuccess = snapshot.lastTestSuccess,
                lastTestMessage = snapshot.lastTestMessage,
            )
        }
        _agentState.update {
            it.copy(
                remoteAiEnabled = snapshot.isReadyForRemote,
                remoteModelName = snapshot.model.takeIf { m -> snapshot.isReadyForRemote && m.isNotBlank() },
            )
        }
    }

    fun openAiSettings() {
        pendingClearAiKey = false
        testedEndpointSnapshot = null
        testedEndpointMessage = null
        viewModelScope.launch(Dispatchers.IO) {
            val snapshot = runCatching { byokAiConfigStore.loadSnapshot() }.getOrDefault(ByokAiConfigSnapshot())
            applyAiSnapshotToStates(snapshot, resetDraftKey = true)
        }
    }

    fun resetAiSettingsDraftToSaved() {
        aiConnectionTestJob?.cancel()
        pendingClearAiKey = false
        testedEndpointSnapshot = null
        testedEndpointMessage = null
        viewModelScope.launch(Dispatchers.IO) {
            val snapshot = runCatching { byokAiConfigStore.loadSnapshot() }.getOrDefault(ByokAiConfigSnapshot())
            applyAiSnapshotToStates(snapshot, resetDraftKey = true)
        }
    }

    fun selectAiProviderPreset(option: AiProviderPresetOption) {
        val preset = AiProviderPreset.fromId(option.id)
        _aiSettingsState.update {
            it.copy(
                presetId = preset.id,
                baseUrl = preset.defaultBaseUrl,
                model = preset.defaultModel,
                requiresApiKey = preset.requiresApiKey,
                lastTestSuccess = null,
                lastTestMessage = null,
                lastLatencyMs = null,
            )
        }
    }

    fun setAiEnabledDraft(enabled: Boolean) {
        _aiSettingsState.update { it.copy(enabled = enabled) }
    }

    fun updateAiBaseUrlDraft(url: String) {
        _aiSettingsState.update {
            it.copy(
                baseUrl = url,
                lastTestSuccess = null,
                lastTestMessage = null,
                lastLatencyMs = null,
            )
        }
    }

    fun updateAiModelDraft(model: String) {
        _aiSettingsState.update {
            it.copy(
                model = model,
                lastTestSuccess = null,
                lastTestMessage = null,
                lastLatencyMs = null,
            )
        }
    }

    fun updateAiApiKeyDraft(apiKey: String) {
        if (apiKey.isNotBlank()) pendingClearAiKey = false
        _aiSettingsState.update {
            it.copy(
                apiKeyDraft = apiKey,
                lastTestSuccess = null,
                lastTestMessage = null,
                lastLatencyMs = null,
            )
        }
    }

    fun clearSavedAiApiKey() {
        pendingClearAiKey = true
        _aiSettingsState.update {
            it.copy(
                hasSavedApiKey = false,
                maskedApiKey = "",
                apiKeyDraft = "",
                lastTestSuccess = null,
                lastTestMessage = null,
                lastLatencyMs = null,
            )
        }
    }

    private fun draftEndpointKey(state: AiSettingsUiState, keyMarker: String): String =
        "${state.presetId}|${state.baseUrl.trim()}|${state.model.trim()}|$keyMarker"

    fun testAiConnection() {
        val draft = _aiSettingsState.value
        aiConnectionTestJob?.cancel()
        _aiSettingsState.update {
            it.copy(
                testingConnection = true,
                lastTestSuccess = null,
                lastTestMessage = null,
                lastLatencyMs = null,
            )
        }
        aiConnectionTestJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val (storedSnap, storedKey) = byokAiConfigStore.resolveCredentials()
                val newKey = draft.apiKeyDraft.trim()
                val effectiveKey = when {
                    newKey.isNotEmpty() -> newKey
                    pendingClearAiKey -> null
                    else -> storedKey
                }
                val preset = AiProviderPreset.fromId(draft.presetId)
                val res = openAiClient.testConnection(
                    baseUrl = draft.baseUrl,
                    model = draft.model,
                    apiKey = effectiveKey,
                    requiresApiKey = preset.requiresApiKey,
                )
                val keyMarker = if (newKey.isNotEmpty()) "new:${newKey.hashCode()}" else if (pendingClearAiKey) "cleared" else "saved:${storedSnap.maskedApiKey}"
                testedEndpointSnapshot = draftEndpointKey(draft, keyMarker) to res.success
                testedEndpointMessage = res.message
                if (storedSnap.preset == preset &&
                    storedSnap.baseUrl == draft.baseUrl.trim() &&
                    storedSnap.model == draft.model.trim() &&
                    newKey.isEmpty() &&
                    !pendingClearAiKey
                ) {
                    byokAiConfigStore.recordTestResult(res.success, res.message)
                }
                _aiSettingsState.update {
                    it.copy(
                        testingConnection = false,
                        lastTestSuccess = res.success,
                        lastTestMessage = res.message,
                        lastLatencyMs = res.latencyMs,
                    )
                }
            } catch (cancelled: CancellationException) {
                _aiSettingsState.update { it.copy(testingConnection = false) }
                throw cancelled
            } catch (err: Throwable) {
                _aiSettingsState.update {
                    it.copy(
                        testingConnection = false,
                        lastTestSuccess = false,
                        lastTestMessage = err.message ?: "连接测试发生未知异常",
                    )
                }
            }
        }
    }

    fun saveAiSettings(onSaved: () -> Unit = {}) {
        val draft = _aiSettingsState.value
        val preset = AiProviderPreset.fromId(draft.presetId)
        val cleanBaseUrl = draft.baseUrl.trim()
        val cleanModel = draft.model.trim()
        val newKey = draft.apiKeyDraft.trim()

        if (draft.enabled) {
            val validUrl = cleanBaseUrl.isNotEmpty() && runCatching { normalizeChatCompletionsUrl(cleanBaseUrl) }.isSuccess
            if (!validUrl) {
                _aiSettingsState.update {
                    it.copy(lastTestSuccess = false, lastTestMessage = "启用外部模型前请填写有效的 http(s) 接口地址")
                }
                return
            }
            if (cleanModel.isEmpty()) {
                _aiSettingsState.update {
                    it.copy(lastTestSuccess = false, lastTestMessage = "启用外部模型前请填写模型名称（Model）")
                }
                return
            }
            val willHaveKey = newKey.isNotEmpty() || (!pendingClearAiKey && draft.hasSavedApiKey)
            if (preset.requiresApiKey && !willHaveKey) {
                _aiSettingsState.update {
                    it.copy(lastTestSuccess = false, lastTestMessage = "该服务商需要填写 API Key 才能启用")
                }
                return
            }
        }

        viewModelScope.launch {
            val savedSnapshot = withContext(Dispatchers.IO) {
                val beforeSnap = byokAiConfigStore.loadSnapshot()
                var saved = byokAiConfigStore.saveConfig(
                    preset = preset,
                    enabled = draft.enabled,
                    baseUrl = cleanBaseUrl,
                    model = cleanModel,
                    newApiKeyOrNull = newKey.ifEmpty { null },
                    clearApiKey = pendingClearAiKey,
                )
                val keyMarker = if (newKey.isNotEmpty()) "new:${newKey.hashCode()}" else if (pendingClearAiKey) "cleared" else "saved:${beforeSnap.maskedApiKey}"
                val testedPair = testedEndpointSnapshot
                val testedMsg = testedEndpointMessage
                if (testedPair != null &&
                    testedPair.first == draftEndpointKey(draft, keyMarker) &&
                    testedPair.second != null &&
                    !testedMsg.isNullOrBlank()
                ) {
                    saved = byokAiConfigStore.recordTestResult(testedPair.second!!, testedMsg)
                }
                pendingClearAiKey = false
                testedEndpointSnapshot = null
                testedEndpointMessage = null
                saved
            }
            applyAiSnapshotToStates(savedSnapshot, resetDraftKey = true)
            onSaved()
        }
    }

    fun formatAiSummaryLabel(state: AiSettingsUiState = _aiSettingsState.value): String {
        if (!state.enabled) return "仅本机离线规则"
        val ready = state.baseUrl.isNotBlank() && state.model.isNotBlank() && (!state.requiresApiKey || state.hasSavedApiKey)
        if (!ready) return "待完善配置"
        val presetName = state.presets.firstOrNull { it.id == state.presetId }?.displayName ?: "外部模型"
        return "$presetName · ${state.model}"
    }

    private fun buildLlmGateway(): LlmGateway? {
        val (snapshot, apiKey) = byokAiConfigStore.resolveCredentials()
        if (!snapshot.isReadyForRemote) return null
        return LlmGateway { messages, tools, temperature, maxTokens ->
            val result = openAiClient.chatCompletions(
                baseUrl = snapshot.baseUrl,
                model = snapshot.model,
                apiKey = apiKey,
                messages = messages.map { msg ->
                    OpenAiChatMessage(
                        role = msg.role,
                        content = msg.content,
                        toolCalls = msg.toolCalls.map { tc ->
                            OpenAiToolCall(id = tc.id, name = tc.name, argumentsJson = tc.argumentsJson)
                        },
                        toolCallId = msg.toolCallId,
                        name = msg.name,
                    )
                },
                tools = tools.map { tool ->
                    OpenAiToolDefinition(
                        name = tool.name,
                        description = tool.description,
                        parametersJsonSchema = tool.parametersJsonSchema,
                    )
                },
                temperature = temperature,
                maxTokens = maxTokens,
            )
            LlmTurnResult(
                content = result.content,
                toolCalls = result.toolCalls.map { tc ->
                    LlmToolCallRequest(id = tc.id, name = tc.name, argumentsJson = tc.argumentsJson)
                },
                modelUsed = result.model.ifBlank { snapshot.model },
                errorMessage = result.errorMessage,
            )
        }
    }

    fun selectAgentTab(tabIndex: Int) {
        _agentState.update {
            it.copy(activeTabIndex = tabIndex.coerceIn(0, AgentWorkspaceTab.entries.lastIndex))
        }
    }

    fun updateAgentFollowUpDraft(draft: String) {
        _agentState.update { it.copy(followUpDraft = draft) }
    }

    fun sendAgentFollowUp(question: String) {
        val clean = question.trim()
        if (clean.isEmpty()) return
        _agentState.update { it.copy(followUpDraft = "") }
        executeHybridAgentRun(
            skill = AgentSkill.FreeQuestion,
            prompt = clean,
            isFollowUp = true,
            forceLocal = false,
        )
    }

    fun rerunAgentLocally() {
        val current = _agentState.value
        val prompt = lastHybridPrompt.ifBlank { current.activeRun?.prompt.orEmpty() }.trim()
        if (prompt.isEmpty()) return
        executeHybridAgentRun(
            skill = lastHybridSkill,
            prompt = prompt,
            isFollowUp = current.chatTurns.isNotEmpty(),
            forceLocal = true,
        )
    }

    private fun executeHybridAgentRun(
        skill: AgentSkill,
        prompt: String,
        isFollowUp: Boolean,
        forceLocal: Boolean,
    ) {
        ensureAgentRunsObserved()
        agentJob?.cancel()
        val current = _agentState.value
        val scope = NoteScope(
            includeTags = setOfNotNull(current.selectedTagId),
            aiOnly = true,
        )
        lastHybridPrompt = prompt
        lastHybridSkill = skill
        if (!isFollowUp) {
            activeChatHistory = emptyList()
            cachedWriteProposals.clear()
        }
        val historySnapshot = if (isFollowUp) activeChatHistory else emptyList()

        _agentState.update {
            it.copy(
                running = true,
                promptDraft = if (!isFollowUp && skill == AgentSkill.FreeQuestion) prompt else it.promptDraft,
                duplicateGroups = emptyList(),
                liveSteps = emptyList(),
                lastUsedRemoteAi = false,
                fallbackReason = null,
                chatTurns = if (isFollowUp) it.chatTurns else emptyList(),
                writeProposals = if (isFollowUp) it.writeProposals else emptyList(),
                statusMessage = null,
                errorMessage = null,
            )
        }

        agentJob = viewModelScope.launch {
            try {
                val (gateway, memorySnap) = withContext(Dispatchers.IO) {
                    val gw = if (forceLocal) null else buildLlmGateway()
                    val mem = if (_agentState.value.memoryAutoReference) agentMemoryStore.loadSnapshot() else null
                    gw to mem
                }
                val (entity, hybridResult) = agentRepository.runHybridQuery(
                    prompt = prompt,
                    scope = scope,
                    timeRange = current.timeRange,
                    skill = skill,
                    conversationHistory = historySnapshot,
                    memorySnapshot = memorySnap,
                    llmGateway = gateway,
                    onStep = { step ->
                        val maxSteps = _agentState.value.maxSteps
                        _agentState.update { cur ->
                            cur.copy(liveSteps = cur.liveSteps + step.toUi(maxSteps))
                        }
                    },
                )
                val actions = agentRepository.actionsForRun(entity.id)
                hybridResult.proposals.forEach { proposal ->
                    cachedWriteProposals[proposal.proposalId] = proposal
                }
                val uiProposals = hybridResult.proposals.map { it.toUi() }
                val uiCitations = hybridResult.citations.map { it.toUi() }
                val uiSteps = hybridResult.steps.map { it.toUi(_agentState.value.maxSteps) }
                val answerText = hybridResult.rawAnswerMarkdown.ifBlank { hybridResult.runResult.headline }

                val userMsg = HybridChatMessage(id = newId(), role = "user", text = prompt)
                val assistantMsg = HybridChatMessage(
                    id = newId(),
                    role = "assistant",
                    text = answerText,
                    citations = hybridResult.citations,
                    proposals = hybridResult.proposals,
                    usedRemoteAi = hybridResult.usedRemoteAi,
                    modelUsed = hybridResult.modelUsed,
                    steps = hybridResult.steps,
                )
                activeChatHistory = historySnapshot + userMsg + assistantMsg

                val nowLabel = formatEpochLabel(System.currentTimeMillis())
                val appendedTurns = if (isFollowUp) {
                    listOf(
                        AgentChatTurnUi(
                            id = userMsg.id,
                            role = "user",
                            text = prompt,
                            createdAtLabel = nowLabel,
                        ),
                        AgentChatTurnUi(
                            id = assistantMsg.id,
                            role = "assistant",
                            text = answerText,
                            citations = uiCitations,
                            proposals = emptyList(),
                            usedRemoteAi = hybridResult.usedRemoteAi,
                            modelUsed = hybridResult.modelUsed,
                            steps = uiSteps,
                            createdAtLabel = nowLabel,
                        ),
                    )
                } else {
                    emptyList()
                }

                val surfaceFallback = if (gateway != null && !hybridResult.usedRemoteAi) {
                    hybridResult.fallbackReason
                } else {
                    null
                }

                _agentState.update { cur ->
                    val existingIds = cur.writeProposals.mapTo(HashSet()) { it.proposalId }
                    val mergedProposals = if (isFollowUp) {
                        cur.writeProposals + uiProposals.filter { it.proposalId !in existingIds }
                    } else {
                        uiProposals
                    }
                    cur.copy(
                        running = false,
                        activeRun = entity,
                        activeResult = hybridResult.runResult,
                        activeActions = actions,
                        duplicateGroups = emptyList(),
                        lastUsedRemoteAi = hybridResult.usedRemoteAi,
                        remoteModelName = hybridResult.modelUsed ?: cur.remoteModelName,
                        fallbackReason = surfaceFallback,
                        liveSteps = uiSteps,
                        chatTurns = if (isFollowUp) cur.chatTurns + appendedTurns else emptyList(),
                        writeProposals = mergedProposals,
                    )
                }
            } catch (cancelled: CancellationException) {
                _agentState.update { it.copy(running = false, statusMessage = "已停止当前研究分析") }
                throw cancelled
            } catch (_: Exception) {
                _agentState.update {
                    it.copy(running = false, errorMessage = "分析未能完成，请重试")
                }
            }
        }
    }

    fun applyWriteProposal(uiProposal: AgentWriteProposalUi) {
        val domainProposal = cachedWriteProposals[uiProposal.proposalId]
        val runId = _agentState.value.activeRun?.id
        if (domainProposal == null) {
            _agentState.update { it.copy(errorMessage = "该写入提案已失效，请重新发起研究后再应用") }
            return
        }
        viewModelScope.launch {
            try {
                val commitRes = persistenceMutex.withLock {
                    if (domainProposal is AgentWriteProposal.PatchNoteProposal) {
                        val activeSession = editor
                        if (activeSession != null && activeSession.draft.noteId == domainProposal.targetNoteId) {
                            stopActiveRecorderBeforeClose(activeSession)
                            flushOpenEditorLocked()
                        }
                    }
                    val res = when (domainProposal) {
                        is AgentWriteProposal.NewNoteProposal ->
                            agentRepository.commitNewNoteProposal(domainProposal, runId = runId)
                        is AgentWriteProposal.PatchNoteProposal ->
                            agentRepository.commitNotePatch(domainProposal, runId = runId)
                        is AgentWriteProposal.ExtractTodosProposal ->
                            agentRepository.commitTodosProposal(domainProposal, runId = runId)
                    }
                    if (res is AgentActionCommitResult.Committed && domainProposal is AgentWriteProposal.PatchNoteProposal) {
                        val activeSession = editor
                        if (activeSession != null && activeSession.draft.noteId == domainProposal.targetNoteId) {
                            database.notes().note(domainProposal.targetNoteId)?.let { refreshed ->
                                activeSession.replaceDocument(refreshed)
                            }
                        }
                    }
                    res
                }
                val refreshedActions = if (runId != null) {
                    runCatching { agentRepository.actionsForRun(runId) }.getOrDefault(_agentState.value.activeActions)
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
                            writeProposals = markProposalApplied(cur.writeProposals, uiProposal.proposalId),
                            chatTurns = cur.chatTurns.map { t ->
                                t.copy(proposals = markProposalApplied(t.proposals, uiProposal.proposalId))
                            },
                            statusMessage = commitRes.summary,
                            errorMessage = null,
                        )
                        is AgentActionCommitResult.AlreadyApplied -> cur.copy(
                            activeActions = refreshedActions,
                            writeProposals = markProposalApplied(cur.writeProposals, uiProposal.proposalId),
                            chatTurns = cur.chatTurns.map { t ->
                                t.copy(proposals = markProposalApplied(t.proposals, uiProposal.proposalId))
                            },
                            statusMessage = "${commitRes.summary}（不重复写入）",
                            errorMessage = null,
                        )
                        is AgentActionCommitResult.ConflictDetected -> cur.copy(
                            activeActions = refreshedActions,
                            errorMessage = "检测到冲突，已拒绝覆盖原笔记（${commitRes.changedNoteTitles.joinToString("、")}）。请重新分析后再确认写入。",
                            statusMessage = null,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _agentState.update { it.copy(errorMessage = "应用写入提案失败，请重试") }
            }
        }
    }

    fun dismissWriteProposal(uiProposal: AgentWriteProposalUi) {
        _agentState.update { cur ->
            cur.copy(
                writeProposals = markProposalDismissed(cur.writeProposals, uiProposal.proposalId),
                chatTurns = cur.chatTurns.map { t ->
                    t.copy(proposals = markProposalDismissed(t.proposals, uiProposal.proposalId))
                },
            )
        }
    }

    fun runInsightPreset(presetUi: AgentPresetUi) {
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
                selectedPresetId = presetUi.id,
                liveSteps = emptyList(),
                lastUsedRemoteAi = false,
                fallbackReason = null,
                statusMessage = null,
                errorMessage = null,
            )
        }
        agentJob = viewModelScope.launch {
            try {
                val (preset, gateway, memorySnap) = withContext(Dispatchers.IO) {
                    val resolved = agentPresetStore.getPreset(presetUi.id) ?: AgentPreset(
                        id = presetUi.id,
                        name = presetUi.name,
                        description = presetUi.description,
                        systemPrompt = presetUi.promptTemplate,
                        builtIn = presetUi.builtIn,
                        defaultDays = presetUi.defaultRangeDays,
                    )
                    val gw = buildLlmGateway()
                    val mem = if (_agentState.value.memoryAutoReference) agentMemoryStore.loadSnapshot() else null
                    Triple(resolved, gw, mem)
                }
                val (entity, hybridResult) = agentRepository.runPresetAnalysis(
                    preset = preset,
                    scope = scope,
                    timeRange = null,
                    memorySnapshot = memorySnap,
                    llmGateway = gateway,
                    onStep = { step ->
                        val maxSteps = _agentState.value.maxSteps
                        _agentState.update { cur -> cur.copy(liveSteps = cur.liveSteps + step.toUi(maxSteps)) }
                    },
                )
                val uiCitations = hybridResult.citations.map { it.toUi() }
                val insightUi = AgentInsightResultUi(
                    presetId = preset.id,
                    presetName = preset.name,
                    summaryMarkdown = hybridResult.rawAnswerMarkdown.ifBlank { hybridResult.runResult.headline },
                    highlights = if (hybridResult.usedRemoteAi) emptyList() else hybridResult.runResult.observations,
                    citations = uiCitations,
                    usedRemoteAi = hybridResult.usedRemoteAi,
                    modelUsed = hybridResult.modelUsed,
                )
                val surfaceFallback = if (gateway != null && !hybridResult.usedRemoteAi) {
                    hybridResult.fallbackReason
                } else {
                    null
                }
                _agentState.update {
                    it.copy(
                        running = false,
                        activeRun = entity,
                        activeInsightResult = insightUi,
                        lastUsedRemoteAi = hybridResult.usedRemoteAi,
                        remoteModelName = hybridResult.modelUsed ?: it.remoteModelName,
                        fallbackReason = surfaceFallback,
                    )
                }
            } catch (cancelled: CancellationException) {
                _agentState.update { it.copy(running = false, statusMessage = "已停止多视角洞察") }
                throw cancelled
            } catch (_: Exception) {
                _agentState.update { it.copy(running = false, errorMessage = "多视角洞察未能完成，请重试") }
            }
        }
    }

    fun saveCustomInsightPreset(
        id: String?,
        name: String,
        description: String,
        promptTemplate: String,
        defaultRangeDays: Int?,
    ) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val saved = agentPresetStore.saveCustomPreset(
                        name = name,
                        description = description,
                        systemPrompt = promptTemplate,
                        defaultDays = defaultRangeDays,
                        id = id,
                    )
                    saved to agentPresetStore.loadPresets()
                }
            }.onSuccess { (saved, allPresets) ->
                _agentState.update {
                    it.copy(
                        insightPresets = allPresets.map { p -> p.toUi() },
                        statusMessage = "已保存自定义视角「${saved.name}」",
                        errorMessage = null,
                    )
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                _agentState.update { it.copy(errorMessage = err.message ?: "保存自定义视角失败") }
            }
        }
    }

    fun deleteCustomInsightPreset(id: String) {
        viewModelScope.launch {
            val (deleted, allPresets) = withContext(Dispatchers.IO) {
                val ok = agentPresetStore.deleteCustomPreset(id)
                ok to agentPresetStore.loadPresets()
            }
            _agentState.update { cur ->
                cur.copy(
                    insightPresets = allPresets.map { p -> p.toUi() },
                    selectedPresetId = if (cur.selectedPresetId == id) null else cur.selectedPresetId,
                    activeInsightResult = if (cur.activeInsightResult?.presetId == id) null else cur.activeInsightResult,
                    statusMessage = if (deleted) "已删除自定义视角" else cur.statusMessage,
                )
            }
        }
    }

    fun continueChatFromInsight(insight: AgentInsightResultUi) {
        val summary = buildString {
            appendLine(insight.summaryMarkdown)
            if (insight.highlights.isNotEmpty()) {
                appendLine()
                insight.highlights.forEach { appendLine("- $it") }
            }
        }.trim()
        val seedUser = HybridChatMessage(
            id = newId(),
            role = "user",
            text = "请基于「${insight.presetName}」视角分析我的近期笔记。",
        )
        val seedAssistant = HybridChatMessage(
            id = newId(),
            role = "assistant",
            text = summary,
            usedRemoteAi = insight.usedRemoteAi,
            modelUsed = insight.modelUsed,
        )
        activeChatHistory = listOf(seedUser, seedAssistant)
        cachedWriteProposals.clear()
        lastHybridPrompt = seedUser.text
        lastHybridSkill = AgentSkill.FreeQuestion
        val nowLabel = formatEpochLabel(System.currentTimeMillis())
        _agentState.update {
            it.copy(
                activeTabIndex = AgentWorkspaceTab.Research.index,
                activeRun = null,
                activeResult = null,
                writeProposals = emptyList(),
                chatTurns = listOf(
                    AgentChatTurnUi(
                        id = seedUser.id,
                        role = "user",
                        text = seedUser.text,
                        createdAtLabel = nowLabel,
                    ),
                    AgentChatTurnUi(
                        id = seedAssistant.id,
                        role = "assistant",
                        text = summary,
                        citations = insight.citations,
                        usedRemoteAi = insight.usedRemoteAi,
                        modelUsed = insight.modelUsed,
                        createdAtLabel = nowLabel,
                    ),
                ),
                statusMessage = "已带入「${insight.presetName}」分析上下文，可在下方继续追问",
                errorMessage = null,
            )
        }
    }

    fun saveInsightAsNote(insight: AgentInsightResultUi) {
        viewModelScope.launch {
            try {
                val body = buildString {
                    appendLine(insight.summaryMarkdown.trim())
                    if (insight.highlights.isNotEmpty()) {
                        appendLine()
                        insight.highlights.forEach { appendLine("- $it") }
                    }
                    if (insight.citations.isNotEmpty()) {
                        appendLine()
                        appendLine("## 引用原笔记")
                        insight.citations.forEach { appendLine("- ${it.noteTitle}") }
                    }
                }.trim()
                val proposal = AgentWriteProposal.NewNoteProposal(
                    proposalId = "insight_${insight.presetId}_${newId().take(8)}",
                    title = "洞察：${insight.presetName}",
                    markdownBody = body,
                    suggestedTags = listOf("AI洞察"),
                    reason = "从「${insight.presetName}」视角分析保存",
                )
                val commitRes = persistenceMutex.withLock {
                    agentRepository.commitNewNoteProposal(proposal, runId = _agentState.value.activeRun?.id)
                }
                if (commitRes is AgentActionCommitResult.Committed) {
                    WidgetUpdater.requestRefreshAll(getApplication())
                }
                _agentState.update { cur ->
                    when (commitRes) {
                        is AgentActionCommitResult.Committed -> cur.copy(statusMessage = commitRes.summary, errorMessage = null)
                        is AgentActionCommitResult.AlreadyApplied -> cur.copy(statusMessage = "已存为新笔记", errorMessage = null)
                        is AgentActionCommitResult.ConflictDetected -> cur.copy(errorMessage = "存为笔记遇到冲突，请重试")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _agentState.update { it.copy(errorMessage = "保存洞察笔记失败，请重试") }
            }
        }
    }

    fun toggleMemoryAutoReference(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = agentMemoryStore.setGlobalEnabled(enabled)
            _agentState.update { it.copy(memoryAutoReference = updated.memoryEnabledInAgent) }
        }
    }

    fun extractMemoryUpdates() {
        if (_agentState.value.memoryExtracting) return
        _agentState.update { it.copy(memoryExtracting = true, statusMessage = null, errorMessage = null) }
        viewModelScope.launch {
            try {
                val (proposals, noteTitles) = withContext(Dispatchers.IO) {
                    val currentMem = agentMemoryStore.loadSnapshot()
                    val gw = buildLlmGateway()
                    val list = agentRepository.proposeMemoryUpdates(
                        scope = NoteScope(aiOnly = true),
                        currentMemory = currentMem,
                        llmGateway = gw,
                    )
                    val titles = database.notes().allNotes().associate { it.id to LocalRetrievalEngine.displayNoteTitle(it) }
                    list to titles
                }
                cachedMemoryProposals.clear()
                proposals.forEach { cachedMemoryProposals[it.proposalId] = it }
                val candidateUis = proposals.map { p ->
                    val srcId = p.sourceNoteIds.firstOrNull()
                    AgentMemoryCandidateUi(
                        candidateId = p.proposalId,
                        category = p.category.displayName,
                        proposedContent = if (p.title.isNotBlank() && !p.content.startsWith(p.title)) {
                            "${p.title}：${p.content}"
                        } else {
                            p.content
                        },
                        existingContent = p.existingContent,
                        sourceNoteId = srcId,
                        sourceNoteTitle = srcId?.let(noteTitles::get),
                        isConflictWithUserEdited = p.conflictsWithUserEdited,
                        existingMemoryId = p.targetMemoryId,
                    )
                }
                _agentState.update {
                    it.copy(
                        memoryExtracting = false,
                        memoryCandidates = candidateUis,
                        statusMessage = if (candidateUis.isEmpty()) "近期笔记暂无新的长期记忆更新建议" else null,
                    )
                }
            } catch (cancelled: CancellationException) {
                _agentState.update { it.copy(memoryExtracting = false) }
                throw cancelled
            } catch (_: Exception) {
                _agentState.update { it.copy(memoryExtracting = false, errorMessage = "提炼长期记忆失败，请重试") }
            }
        }
    }

    fun commitMemoryCandidates(chosen: List<AgentMemoryCandidateUi>) {
        if (chosen.isEmpty()) return
        viewModelScope.launch {
            val (snapshot, noteTitles) = withContext(Dispatchers.IO) {
                val selectedProposals = chosen.mapNotNull { ui -> cachedMemoryProposals[ui.candidateId] }
                val allowOverwriteUserEditedIds = chosen
                    .flatMap { ui ->
                        listOfNotNull(
                            ui.existingMemoryId?.takeIf { it.isNotBlank() },
                            if (ui.isConflictWithUserEdited) ui.candidateId else null,
                        )
                    }
                    .toSet()
                val updated = agentMemoryStore.applyApprovedProposals(
                    selectedProposals = selectedProposals,
                    allowOverwriteUserEditedIds = allowOverwriteUserEditedIds,
                )
                val titles = database.notes().allNotes().associate { it.id to LocalRetrievalEngine.displayNoteTitle(it) }
                updated to titles
            }
            cachedMemoryProposals.clear()
            _agentState.update {
                it.copy(
                    memoryAutoReference = snapshot.memoryEnabledInAgent,
                    memoryItems = snapshot.items.map { item -> item.toUi(noteTitles) },
                    memoryCandidates = emptyList(),
                    statusMessage = "已写入 ${chosen.size} 条长期记忆档案",
                    errorMessage = null,
                )
            }
        }
    }

    fun dismissMemoryCandidates() {
        cachedMemoryProposals.clear()
        _agentState.update { it.copy(memoryCandidates = emptyList()) }
    }

    fun toggleMemoryItem(id: String, enabled: Boolean) {
        viewModelScope.launch {
            val (snapshot, noteTitles) = withContext(Dispatchers.IO) {
                val updated = agentMemoryStore.setItemEnabled(id, enabled)
                val titles = database.notes().allNotes().associate { it.id to LocalRetrievalEngine.displayNoteTitle(it) }
                updated to titles
            }
            _agentState.update {
                it.copy(memoryItems = snapshot.items.map { item -> item.toUi(noteTitles) })
            }
        }
    }

    fun editMemoryItem(id: String, categoryRaw: String, content: String) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val cat = MemoryCategory.fromId(categoryRaw)
                    val trimmed = content.trim()
                    val splitParts = trimmed.split("：", limit = 2)
                    val parsedTitle = if (splitParts.size == 2 && splitParts[0].isNotBlank() && splitParts[1].isNotBlank()) {
                        splitParts[0].trim()
                    } else {
                        null
                    }
                    val parsedBody = if (parsedTitle != null) splitParts[1].trim() else trimmed
                    agentMemoryStore.updateMemoryItem(
                        id = id,
                        category = cat,
                        title = parsedTitle,
                        content = parsedBody,
                        markUserEdited = true,
                    )
                    val updated = agentMemoryStore.loadSnapshot()
                    val titles = database.notes().allNotes().associate { it.id to LocalRetrievalEngine.displayNoteTitle(it) }
                    updated to titles
                }
            }.onSuccess { (snapshot, noteTitles) ->
                _agentState.update {
                    it.copy(
                        memoryItems = snapshot.items.map { item -> item.toUi(noteTitles) },
                        statusMessage = "已更新记忆条目（自动提炼不会静默覆盖）",
                        errorMessage = null,
                    )
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                _agentState.update { it.copy(errorMessage = err.message ?: "更新记忆失败") }
            }
        }
    }

    fun deleteMemoryItem(id: String) {
        viewModelScope.launch {
            val (snapshot, noteTitles) = withContext(Dispatchers.IO) {
                val updated = agentMemoryStore.deleteMemoryItem(id)
                val titles = database.notes().allNotes().associate { it.id to LocalRetrievalEngine.displayNoteTitle(it) }
                updated to titles
            }
            _agentState.update {
                it.copy(
                    memoryItems = snapshot.items.map { item -> item.toUi(noteTitles) },
                    statusMessage = "已删除该记忆条目",
                )
            }
        }
    }

    fun requestVoiceTranscriptClean(attachmentId: String, rawTranscript: String) {
        voiceCleanJob?.cancel()
        _voiceCleanState.value = VoiceCleanUiState(
            activeAttachmentId = attachmentId,
            cleaning = true,
            cleanedTranscriptPreview = null,
            extractedTodoPreviews = emptyList(),
            notice = null,
        )
        voiceCleanJob = viewModelScope.launch {
            try {
                val (snapshot, apiKey) = withContext(Dispatchers.IO) {
                    byokAiConfigStore.resolveCredentials()
                }
                val result = cleanVoiceTranscriptWithByok(
                    rawTranscript = rawTranscript,
                    client = openAiClient,
                    snapshot = snapshot,
                    apiKey = apiKey,
                )
                _voiceCleanState.value = VoiceCleanUiState(
                    activeAttachmentId = attachmentId,
                    cleaning = false,
                    cleanedTranscriptPreview = result.cleanedText.takeIf { it.isNotBlank() },
                    extractedTodoPreviews = result.extractedTodos,
                    notice = if (!result.usedRemoteAi || result.cleanedText.isBlank()) result.statusNote else null,
                )
            } catch (cancelled: CancellationException) {
                _voiceCleanState.update { it.copy(cleaning = false) }
                throw cancelled
            } catch (_: Throwable) {
                _voiceCleanState.value = VoiceCleanUiState(
                    activeAttachmentId = attachmentId,
                    cleaning = false,
                    cleanedTranscriptPreview = null,
                    extractedTodoPreviews = emptyList(),
                    notice = "语音整理失败，可重试或直接插入原文",
                )
            }
        }
    }

    fun dismissVoiceCleanPreview() {
        voiceCleanJob?.cancel()
        _voiceCleanState.value = VoiceCleanUiState()
    }

    fun acceptVoiceTranscriptClean(
        session: EditorSession,
        block: EditorBlock,
        cleanedText: String,
        selectedTodos: List<String>,
    ): Boolean {
        val inserted = session.insertCleanedTranscriptAndTodos(block, cleanedText, selectedTodos)
        if (!inserted) return false
        voiceCleanJob?.cancel()
        _voiceCleanState.value = VoiceCleanUiState()
        if (selectedTodos.isNotEmpty()) {
            session.status = "已插入整理后文本并添加 ${selectedTodos.size} 项待办"
        }
        return true
    }

    private fun AgentStepTrace.toUi(maxSteps: Int): AgentProgressStepUi = AgentProgressStepUi(
        stepIndex = stepIndex,
        totalSteps = maxSteps,
        toolName = toolName,
        summary = summary,
        elapsedMs = elapsedMs,
    )

    private fun CitationLink.toUi(): AgentCitationUi = AgentCitationUi(
        noteId = noteId,
        noteTitle = noteTitle,
        excerpt = excerpt,
        reason = reason,
    )

    private fun AgentWriteProposal.toUi(): AgentWriteProposalUi = when (this) {
        is AgentWriteProposal.NewNoteProposal -> AgentWriteProposalUi.NewNote(
            proposalId = proposalId,
            title = title,
            markdownBody = markdownBody,
            suggestedTags = suggestedTags,
            reason = reason,
        )
        is AgentWriteProposal.PatchNoteProposal -> AgentWriteProposalUi.PatchNote(
            proposalId = proposalId,
            targetNoteId = targetNoteId,
            targetTitle = targetTitle,
            beforeExcerpt = beforeExcerpt,
            afterContent = afterContent,
            appendMode = appendMode,
            reason = reason,
        )
        is AgentWriteProposal.ExtractTodosProposal -> AgentWriteProposalUi.ExtractTodos(
            proposalId = proposalId,
            noteId = noteId,
            targetTitle = targetTitle,
            items = items,
            reason = reason,
        )
    }

    private fun AgentPreset.toUi(): AgentPresetUi = AgentPresetUi(
        id = id,
        name = name,
        description = description,
        promptTemplate = systemPrompt,
        defaultRangeDays = defaultDays,
        builtIn = builtIn,
        iconKey = when (id) {
            AgentPresetStore.PRESET_BLINDSPOTS -> "explore"
            AgentPresetStore.PRESET_SHARP_QUESTIONS -> "sparkles"
            AgentPresetStore.PRESET_STANCE_SHIFT -> "history"
            AgentPresetStore.PRESET_MEANING_RADAR -> "chart"
            else -> "sparkles"
        },
    )

    private fun AgentMemoryItem.toUi(noteTitles: Map<String, String>): AgentMemoryItemUi {
        val srcId = sourceNoteIds.firstOrNull()
        val displayBody = if (title.isNotBlank() && !content.startsWith(title)) {
            "$title：$content"
        } else {
            content
        }
        return AgentMemoryItemUi(
            id = id,
            category = category.displayName,
            content = displayBody,
            sourceNoteId = srcId,
            sourceNoteTitle = srcId?.let(noteTitles::get),
            enabled = enabled,
            userEdited = userEdited,
            updatedAtLabel = formatEpochLabel(updatedAt),
        )
    }

    private fun markProposalApplied(
        list: List<AgentWriteProposalUi>,
        proposalId: String,
    ): List<AgentWriteProposalUi> = list.map { item ->
        if (item.proposalId != proposalId) item
        else when (item) {
            is AgentWriteProposalUi.NewNote -> item.copy(applied = true)
            is AgentWriteProposalUi.PatchNote -> item.copy(applied = true)
            is AgentWriteProposalUi.ExtractTodos -> item.copy(applied = true)
        }
    }

    private fun markProposalDismissed(
        list: List<AgentWriteProposalUi>,
        proposalId: String,
    ): List<AgentWriteProposalUi> = list.map { item ->
        if (item.proposalId != proposalId) item
        else when (item) {
            is AgentWriteProposalUi.NewNote -> item.copy(dismissed = true)
            is AgentWriteProposalUi.PatchNote -> item.copy(dismissed = true)
            is AgentWriteProposalUi.ExtractTodos -> item.copy(dismissed = true)
        }
    }

    private fun formatEpochLabel(epochMs: Long): String {
        if (epochMs <= 0L) return ""
        return runCatching {
            val zdt = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
            DateTimeFormatter.ofPattern("MM/dd HH:mm").format(zdt)
        }.getOrDefault("")
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
        appPreferences.edgeEchoEnabled = enabled
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
                session.acknowledgeCommit(committed, sequence)
                commitSessionExtras(session)
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

internal fun mergeExternalBlockUpdate(
    synced: NoteBlock?,
    fresh: NoteBlock,
    local: NoteBlock,
    localFallbackText: String = local.text,
    localFallbackChecked: Boolean = local.checked,
    localFallbackDueDate: String? = local.dueDate,
    localFallbackReminderId: String? = local.reminderId,
    localFallbackOccurrenceId: String? = local.occurrenceId,
    localPendingSchedule: Boolean = false,
): NoteBlock? {
    if (synced == null) {
        val extTextChanged = fresh.text != localFallbackText
        val localTextChanged = local.text != localFallbackText
        if (extTextChanged && localTextChanged) return null
        val extTaskChanged = fresh.checked != localFallbackChecked ||
            fresh.dueDate != localFallbackDueDate ||
            fresh.reminderId != localFallbackReminderId ||
            fresh.occurrenceId != localFallbackOccurrenceId
        val localTaskChanged = localPendingSchedule ||
            local.checked != localFallbackChecked ||
            local.dueDate != localFallbackDueDate
        if (extTaskChanged && localTaskChanged) return null
        val metaDiffers = fresh.type != local.type ||
            fresh.level != local.level ||
            fresh.indent != local.indent ||
            fresh.marks != local.marks ||
            fresh.tone != local.tone ||
            fresh.title != local.title ||
            fresh.collapsed != local.collapsed ||
            fresh.dividerStyle != local.dividerStyle
        if (metaDiffers) return null
        val base = if (extTaskChanged && !localTaskChanged) fresh else local
        val merged = base.copy(
            text = if (extTextChanged && !localTextChanged) fresh.text else local.text,
            checked = if (extTaskChanged && !localTaskChanged) fresh.checked else local.checked,
        )
        return if (merged.type == "checkbox") {
            merged.withTaskSchedule(
                dueDate = if (extTaskChanged && !localTaskChanged) fresh.dueDate else local.dueDate,
                reminderId = if (extTaskChanged && !localTaskChanged) fresh.reminderId else local.reminderId,
                occurrenceId = if (extTaskChanged && !localTaskChanged) fresh.occurrenceId else local.occurrenceId,
            )
        } else {
            merged
        }
    }

    val extTypeChanged = fresh.type != synced.type
    val localTypeChanged = local.type != synced.type
    if (extTypeChanged && localTypeChanged && fresh.type != local.type) return null

    val extTextChanged = fresh.text != synced.text
    val localTextChanged = local.text != synced.text
    if (extTextChanged && localTextChanged && fresh.text != local.text) return null

    val extTitleChanged = fresh.title != synced.title
    val localTitleChanged = local.title != synced.title
    if (extTitleChanged && localTitleChanged && fresh.title != local.title) return null

    val extMarksChanged = fresh.marks != synced.marks
    val localMarksChanged = local.marks != synced.marks
    if ((extTextChanged || extMarksChanged) && (localTextChanged || localMarksChanged)) {
        if (fresh.text != local.text || fresh.marks != local.marks) return null
    }

    val extTaskChanged = fresh.checked != synced.checked ||
        fresh.dueDate != synced.dueDate ||
        fresh.reminderId != synced.reminderId ||
        fresh.occurrenceId != synced.occurrenceId
    val localTaskChanged = localPendingSchedule ||
        local.checked != synced.checked ||
        local.dueDate != synced.dueDate ||
        local.reminderId != synced.reminderId ||
        local.occurrenceId != synced.occurrenceId
    if (extTaskChanged && localTaskChanged) return null

    val extLevelChanged = fresh.level != synced.level
    val localLevelChanged = local.level != synced.level
    if (extLevelChanged && localLevelChanged && fresh.level != local.level) return null

    val extIndentChanged = fresh.indent != synced.indent
    val localIndentChanged = local.indent != synced.indent
    if (extIndentChanged && localIndentChanged && fresh.indent != local.indent) return null

    val extToneChanged = fresh.tone != synced.tone
    val localToneChanged = local.tone != synced.tone
    if (extToneChanged && localToneChanged && fresh.tone != local.tone) return null

    val extCollapsedChanged = fresh.collapsed != synced.collapsed
    val localCollapsedChanged = local.collapsed != synced.collapsed
    if (extCollapsedChanged && localCollapsedChanged && fresh.collapsed != local.collapsed) return null

    val extDividerChanged = fresh.dividerStyle != synced.dividerStyle
    val localDividerChanged = local.dividerStyle != synced.dividerStyle
    if (extDividerChanged && localDividerChanged && fresh.dividerStyle != local.dividerStyle) return null

    val extAnyFieldChanged = extTextChanged || extTitleChanged || extMarksChanged || extTaskChanged ||
        extLevelChanged || extIndentChanged || extToneChanged || extCollapsedChanged || extDividerChanged
    val localAnyFieldChanged = localTextChanged || localTitleChanged || localMarksChanged || localTaskChanged ||
        localLevelChanged || localIndentChanged || localToneChanged || localCollapsedChanged || localDividerChanged
    if (extTypeChanged && localAnyFieldChanged) return null
    if (localTypeChanged && extAnyFieldChanged) return null

    val base = when {
        extTypeChanged -> fresh
        localTypeChanged -> local
        extTaskChanged -> fresh
        else -> local
    }
    val mergedText = if (extTextChanged) fresh.text else local.text
    val mergedMarks = if (extMarksChanged || extTextChanged) fresh.marks else local.marks
    val merged = base.copy(
        text = mergedText,
        checked = if (extTaskChanged) fresh.checked else local.checked,
        level = if (extLevelChanged) fresh.level else local.level,
        indent = if (extIndentChanged) fresh.indent else local.indent,
        marks = mergedMarks,
        tone = if (extToneChanged) fresh.tone else local.tone,
        title = if (extTitleChanged) fresh.title else local.title,
        collapsed = if (extCollapsedChanged) fresh.collapsed else local.collapsed,
        dividerStyle = if (extDividerChanged) fresh.dividerStyle else local.dividerStyle,
    )
    return if (merged.type == "checkbox") {
        merged.withTaskSchedule(
            dueDate = if (extTaskChanged) fresh.dueDate else local.dueDate,
            reminderId = if (extTaskChanged) fresh.reminderId else local.reminderId,
            occurrenceId = if (extTaskChanged) fresh.occurrenceId else local.occurrenceId,
        )
    } else {
        merged
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
