package io.github.z3275630ops.floatingnotes.feature.home

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.z3275630ops.floatingnotes.BuildConfig
import io.github.z3275630ops.floatingnotes.DebugSampleAction
import io.github.z3275630ops.floatingnotes.core.model.NoteBlock
import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import io.github.z3275630ops.floatingnotes.data.local.*
import io.github.z3275630ops.floatingnotes.feature.agent.AgentWorkspace
import io.github.z3275630ops.floatingnotes.feature.capture.AttachmentThumbnail
import io.github.z3275630ops.floatingnotes.feature.capture.CaptureMotion
import io.github.z3275630ops.floatingnotes.feature.capture.CaptureSheet
import io.github.z3275630ops.floatingnotes.feature.capture.EditorIcons
import io.github.z3275630ops.floatingnotes.feature.capture.EditorUndoHandle
import io.github.z3275630ops.floatingnotes.feature.capture.NoteEditor
import io.github.z3275630ops.floatingnotes.feature.capture.NotesViewModel
import io.github.z3275630ops.floatingnotes.feature.capture.rememberEditorUndoHandle
import io.github.z3275630ops.floatingnotes.feature.explore.CognitiveMapSheet
import io.github.z3275630ops.floatingnotes.feature.explore.ExploreScreen
import io.github.z3275630ops.floatingnotes.feature.share.ShareCardSheet
import io.github.z3275630ops.floatingnotes.feature.stats.StatsSheet
import io.github.z3275630ops.floatingnotes.feature.tasks.ReminderScheduler
import io.github.z3275630ops.floatingnotes.feature.widget.WidgetContract
import io.github.z3275630ops.floatingnotes.ui.designsystem.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private enum class Page(val label: String) {
    Record("记录"),
    Explore("探索"),
    Agent("Agent"),
    Library("笔记库"),
}

private enum class RecordFilter(val label: String) {
    All("全部"),
    Todos("待办"),
}

private data class NoteCardMeta(
    val title: String,
    val preview: String,
    val domainLabel: String? = null,
    val media: List<NoteBlock>,
    val mediaTypes: Set<String>,
    val doneTodos: Int,
    val totalTodos: Int,
    val relativeTime: String,
)

private data class FallbackTodoItem(
    val noteId: String,
    val noteTitle: String,
    val text: String,
    val checked: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
internal data class ShellDeepLinkRequest(
    val seq: Long,
    val openTasks: Boolean,
    val noteId: String?,
    val blockId: String?,
    val widgetAction: String? = null,
    val reviewSessionId: String? = null,
    val shortcutAction: String? = null,
)

@Composable
internal fun NotesShell(
    model: NotesViewModel,
    labs: @Composable RowScope.() -> Unit = {},
    initialStartOnTasks: Boolean = false,
    pendingTasksCount: Int? = null,
    todoFilterNoteIds: Set<String>? = null,
    tasksOverlay: (@Composable (onClose: () -> Unit, onOpenNote: (String) -> Unit) -> Unit)? = null,
    noteDueBadges: Map<String, String> = emptyMap(),
    deepLinkRequest: ShellDeepLinkRequest? = null,
    onConsumeDeepLink: () -> Unit = {},
    onRequestNotificationPermission: (() -> Unit)? = null,
    onRequestVoiceCapture: (() -> Unit)? = null,
) {
    val appPrefs by model.appPreferencesSnapshot.collectAsStateWithLifecycle()
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme,
        typography = mintTypography(appPrefs.fontScaleTier.multiplier),
    ) {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val dark = isSystemInDarkTheme()
        val scheme = MaterialTheme.colorScheme
        val backdrop = rememberGlassBackdrop()
        val supportedGlassTier = GlassTier.Enhanced.supportedOn(Build.VERSION.SDK_INT)
        var page by rememberSaveable { mutableStateOf(Page.Record) }
        var recordFilter by rememberSaveable { mutableStateOf(RecordFilter.All) }
        var showTasksOverlay by rememberSaveable { mutableStateOf(initialStartOnTasks && tasksOverlay != null) }
        var requestedNoteId by rememberSaveable { mutableStateOf<String?>(null) }
        var requestedCaptureSlot by rememberSaveable { mutableStateOf<String?>(null) }
        var open by rememberSaveable { mutableStateOf(false) }
        var showArchive by rememberSaveable { mutableStateOf(false) }
        var showTrash by rememberSaveable { mutableStateOf(false) }
        var showSettingsSheet by rememberSaveable { mutableStateOf(false) }
        var showAiSettingsSheet by rememberSaveable { mutableStateOf(false) }
        var showBackupSheet by rememberSaveable { mutableStateOf(false) }
        var quickCaptureDismissPrompt by remember { mutableStateOf(false) }
        var suppressNextAutoFocus by remember { mutableStateOf(false) }
        var selectedNoteIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
        val selectedNoteIdSet = remember(selectedNoteIds) { selectedNoteIds.toSet() }
        val inSelectionMode = selectedNoteIdSet.isNotEmpty()
        var showBatchTagSheet by remember { mutableStateOf(false) }
        var showBatchNotebookSheet by remember { mutableStateOf(false) }
        val notes by model.notes.collectAsStateWithLifecycle()
        val captures by model.captureDrafts.collectAsStateWithLifecycle()
        val trashedCount by model.trashedCount.collectAsStateWithLifecycle()
        val trashedNoteIds by model.trashedNoteIds.collectAsStateWithLifecycle()
        val historyState by model.historyState.collectAsStateWithLifecycle()
        val trashPurgeImpact by model.trashPurgeImpact.collectAsStateWithLifecycle()
        val backupState by model.backupState.collectAsStateWithLifecycle()
        val externalImportState by model.externalImportState.collectAsStateWithLifecycle()
        val exploreState by model.exploreState.collectAsStateWithLifecycle()
        val cognitiveMapState by model.cognitiveMapState.collectAsStateWithLifecycle()
        val agentState by model.agentState.collectAsStateWithLifecycle()
        val aiSettingsState by model.aiSettingsState.collectAsStateWithLifecycle()
        val attachmentStorageBytes by model.attachmentStorageBytes.collectAsStateWithLifecycle()
        var alarmHealth by remember { mutableStateOf(ReminderScheduler.inspectAlarmHealth(context)) }
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    alarmHealth = ReminderScheduler.inspectAlarmHealth(context)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        LaunchedEffect(showSettingsSheet) {
            if (showSettingsSheet) {
                alarmHealth = ReminderScheduler.inspectAlarmHealth(context)
                model.refreshAttachmentStorageBytes()
            }
        }
        LaunchedEffect(notes) {
            if (selectedNoteIds.isNotEmpty()) {
                val activeIds = notes.mapTo(HashSet()) { it.id }
                val retained = selectedNoteIds.filter { it in activeIds }
                if (retained.size != selectedNoteIds.size) {
                    selectedNoteIds = retained
                }
            }
        }
    var showStatsSheet by rememberSaveable { mutableStateOf(false) }
    var sharingNoteId by rememberSaveable { mutableStateOf<String?>(null) }
    var exploreSectionIndex by rememberSaveable { mutableStateOf(0) }
    var showDraftsSheet by rememberSaveable { mutableStateOf(false) }
    val trashedNotes by if (showTrash || showStatsSheet) {
        model.trashedNotes.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf(emptyList()) }
    }
    val organization = model.organization
    val tags by organization.tags.collectAsStateWithLifecycle(emptyList())
    val notebooks by organization.notebooks.collectAsStateWithLifecycle(emptyList())
    val noteTags by organization.tagsByNote.collectAsStateWithLifecycle(emptyList())
    val notebookNotes by organization.notebookNotes.collectAsStateWithLifecycle(emptyList())
    val links by if (page == Page.Explore || model.editor != null) {
        organization.links.collectAsStateWithLifecycle(emptyList())
    } else {
        remember { mutableStateOf(emptyList()) }
    }
    var selectedTag by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedNotebook by rememberSaveable { mutableStateOf<String?>(null) }
    var descendants by rememberSaveable { mutableStateOf(true) }
    var organizeNote by rememberSaveable { mutableStateOf<String?>(null) }

    val tagPaths = remember(tags) { tags.associate { it.id to tagPath(tags, it) } }
    val notebookById = remember(notebooks) { notebooks.associateBy { it.id } }
    val notebookByNoteId = remember(notebookNotes) {
        notebookNotes.associate { it.noteId to it.notebookId }
    }
    val tagIdsByNoteId = remember(noteTags) {
        noteTags.groupBy({ it.noteId }, { it.tagId })
    }
    val activeNoteIds = remember(notes) {
        notes.filter { it.archivedAt == null }.mapTo(hashSetOf()) { it.id }
    }
    val notebookCounts = remember(notebookNotes, activeNoteIds) {
        notebookNotes.filter { it.noteId in activeNoteIds }.groupingBy { it.notebookId }.eachCount()
    }
    val tagCounts = remember(noteTags, activeNoteIds) {
        noteTags.filter { it.noteId in activeNoteIds }.groupingBy { it.tagId }.eachCount()
    }

    val activeTodoNoteIds = todoFilterNoteIds ?: remember(notes) {
        notes.filter { note ->
            runCatching {
                NoteDocument.decode(note.documentJson).blocks.any { it.type == "checkbox" }
            }.getOrDefault(false)
        }.mapTo(hashSetOf()) { it.id }
    }
    val badgeTasksCount = pendingTasksCount ?: remember(notes) {
        notes.sumOf { note ->
            runCatching {
                NoteDocument.decode(note.documentJson).blocks.count { it.type == "checkbox" && !it.checked }
            }.getOrDefault(0)
        }
    }

    val tagScope = remember(tags, selectedTag, descendants) {
        selectedTag?.let { if (descendants) tagSubtree(tags, it) else setOf(it) }
    }
    val tagMatches = remember(noteTags, tagScope) {
        if (tagScope == null) emptySet()
        else noteTags.filter { it.tagId in tagScope }.mapTo(hashSetOf()) { it.noteId }
    }
    val notebookMatches = remember(notebookNotes, selectedNotebook) {
        if (selectedNotebook == null) emptySet()
        else notebookNotes.filter { it.notebookId == selectedNotebook }.mapTo(hashSetOf()) { it.noteId }
    }
    val keyword = rememberTextFieldState()
    var searchScopeJson by rememberSaveable { mutableStateOf(NoteScope().encode()) }
    val searchScope = remember(searchScopeJson) { NoteScope.decode(searchScopeJson) }
    val phrase = keyword.text.toString()
    val effectiveScope = if (searchScope != NoteScope()) {
        searchScope
    } else {
        NoteScope(
            includeTags = setOfNotNull(selectedTag),
            notebooks = setOfNotNull(selectedNotebook),
            descendants = descendants,
        )
    }
    val searching = phrase.isNotBlank() || searchScope != NoteScope()
    val librarySearching by rememberUpdatedState(searching)
    val libraryScope by rememberUpdatedState(effectiveScope)
    var searchLimit by remember(phrase, effectiveScope) { mutableIntStateOf(50) }
    var searchFailed by remember(page, phrase, effectiveScope, searchLimit) { mutableStateOf(false) }
    var searchAttempt by remember { mutableIntStateOf(0) }
    val results by key(page, phrase, effectiveScope, searchAttempt) {
        produceState<List<NoteEntity>?>(null, searchLimit) {
            searchFailed = false
            if (page == Page.Library && searching) {
                delay(160)
                try {
                    model.search.observeSmart(phrase, effectiveScope, searchLimit + 1).collect { value = it }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    searchFailed = true
                    value = null
                }
            }
        }
    }
    val libraryResults by rememberUpdatedState(results)
    val libraryLimit by rememberUpdatedState(searchLimit)
    val librarySearchFailed by rememberUpdatedState(searchFailed)
    val scope = rememberCoroutineScope()
    val motion = remember { CaptureMotion(scope, open) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    BackHandler(
        enabled = inSelectionMode &&
            !motion.visible &&
            !drawer.isOpen &&
            !showTasksOverlay &&
            !showTrash &&
            !showSettingsSheet &&
            !showBackupSheet &&
            !showBatchTagSheet &&
            !showBatchNotebookSheet,
    ) { selectedNoteIds = emptyList() }
    BackHandler(enabled = showTasksOverlay && !motion.visible) { showTasksOverlay = false }
    BackHandler(enabled = showTrash && !motion.visible && !drawer.isOpen && !showTasksOverlay) { showTrash = false }
    var samples by remember { mutableStateOf(emptyList<String>()) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var hadEditorSession by remember { mutableStateOf(model.editor != null) }
    var switchingNote by remember { mutableStateOf(false) }

    fun toggleNoteSelection(noteId: String) {
        selectedNoteIds = if (noteId in selectedNoteIdSet) {
            selectedNoteIds.filterNot { it == noteId }
        } else {
            selectedNoteIds + noteId
        }
    }

    fun enterNoteSelection(noteId: String) {
        keyboard?.hide()
        focus.clearFocus()
        if (noteId !in selectedNoteIdSet) {
            selectedNoteIds = selectedNoteIds + noteId
        }
    }

    LaunchedEffect(model.editor, switchingNote) {
        if (switchingNote) return@LaunchedEffect
        val currentEditor = model.editor
        if (currentEditor != null) {
            hadEditorSession = true
        } else if (hadEditorSession) {
            hadEditorSession = false
            if (open) {
                keyboard?.hide()
                focus.clearFocus()
                requestedNoteId = null
                requestedCaptureSlot = null
                quickCaptureDismissPrompt = false
                suppressNextAutoFocus = false
                open = false
                motion.settle(false)
            }
        }
    }

    fun launchEditor(
        noteId: String? = null,
        captureSlot: String? = null,
        focusBlockId: String? = null,
        startVoice: Boolean = false,
    ) {
        selectedNoteIds = emptyList()
        scope.launch(Dispatchers.Main.immediate) {
            val currentSession = model.editor
            val switching = currentSession != null && (
                (noteId != null && currentSession.draft.noteId != noteId) ||
                    (captureSlot != null && currentSession.draft.slot != captureSlot) ||
                    (noteId == null && captureSlot == null && currentSession.draft.slot != "manual")
                )
            if (switching) switchingNote = true
            try {
                if (model.openNote(noteId, captureSlot, focusBlockId)) {
                    requestedNoteId = noteId
                    requestedCaptureSlot = captureSlot
                    quickCaptureDismissPrompt = false
                    suppressNextAutoFocus = startVoice
                    open = true
                    motion.settle(true)
                    if (startVoice) {
                        onRequestVoiceCapture?.invoke()
                    }
                } else if (model.editor == null) {
                    open = false
                    motion.settle(false)
                }
            } finally {
                if (switching) switchingNote = false
            }
        }
    }

    LaunchedEffect(page) {
        if (page != Page.Record && page != Page.Library) {
            selectedNoteIds = emptyList()
        }
        if (page == Page.Agent) {
            model.ensureAgentRunsObserved()
        }
    }

    LaunchedEffect(Unit) {
        if (open && !model.openNote(requestedNoteId, requestedCaptureSlot)) {
            open = false
            motion.settle(false)
        }
    }

    fun close(velocity: Float? = null, save: Boolean = false, discard: Boolean = false) {
        scope.launch(Dispatchers.Main.immediate) {
            if (model.finishEditor(save = save, discard = discard)) {
                keyboard?.hide()
                focus.clearFocus()
                requestedNoteId = null
                requestedCaptureSlot = null
                quickCaptureDismissPrompt = false
                suppressNextAutoFocus = false
                open = false
                if (velocity == null) motion.settle(false) else motion.settle(false, velocity)
            } else if (open) {
                motion.settle(true)
            }
        }
    }

    LaunchedEffect(deepLinkRequest?.seq) {
        val request = deepLinkRequest ?: return@LaunchedEffect
        selectedNoteIds = emptyList()
        showSettingsSheet = false
        showBackupSheet = false
        when {
            request.shortcutAction == WidgetContract.ACTION_SHORTCUT_CAPTURE ||
                request.widgetAction == WidgetContract.ACTION_QUICK_CAPTURE -> {
                showTasksOverlay = false
                showTrash = false
                launchEditor(noteId = null)
            }
            request.shortcutAction == WidgetContract.ACTION_SHORTCUT_VOICE -> {
                showTasksOverlay = false
                showTrash = false
                launchEditor(noteId = null, startVoice = true)
            }
            request.shortcutAction == WidgetContract.ACTION_SHORTCUT_TASKS -> {
                if (open) close()
                showTrash = false
                showTasksOverlay = true
            }
            request.widgetAction == WidgetContract.ACTION_OPEN_REVIEW_SESSION -> {
                if (open) close()
                showTasksOverlay = false
                showTrash = false
                exploreSectionIndex = 0
                page = Page.Explore
                model.focusReviewSessionFromWidget(
                    sessionId = request.reviewSessionId,
                    noteId = request.noteId,
                )
            }
            request.widgetAction == WidgetContract.ACTION_OPEN_EXPLORE -> {
                if (open) close()
                showTasksOverlay = false
                showTrash = false
                exploreSectionIndex = 0
                page = Page.Explore
            }
            request.widgetAction == WidgetContract.ACTION_OPEN_AGENT -> {
                if (open) close()
                showTasksOverlay = false
                showTrash = false
                page = Page.Agent
            }
            request.noteId != null -> {
                showTasksOverlay = false
                showTrash = false
                launchEditor(noteId = request.noteId, focusBlockId = request.blockId)
            }
            request.openTasks -> {
                if (open) close()
                showTrash = false
                showTasksOverlay = true
            }
        }
        onConsumeDeepLink()
    }

    organizeNote?.let { id ->
        NoteOrganizationDialog(
            note = id,
            tags = tags,
            notebooks = notebooks,
            tagIds = noteTags.filter { it.noteId == id }.mapTo(hashSetOf()) { it.tagId },
            notebookId = notebookNotes.firstOrNull { it.noteId == id }?.notebookId,
            repository = organization,
        ) { organizeNote = null }
    }

    if (showTasksOverlay && tasksOverlay == null) {
        FallbackTasksDialog(
            notes = notes,
            dark = dark,
            onDismiss = { showTasksOverlay = false },
            onOpenNote = { noteId ->
                showTasksOverlay = false
                launchEditor(noteId)
            },
        )
    }

    if (quickCaptureDismissPrompt) {
        QuickCaptureDismissSheet(
            dark = dark,
            onKeepDraft = {
                quickCaptureDismissPrompt = false
                close(save = false, discard = false)
            },
            onDiscardDraft = {
                quickCaptureDismissPrompt = false
                close(discard = true)
            },
            onContinueEditing = { quickCaptureDismissPrompt = false },
        )
    }

    if (historyState.noteId != null) {
        NoteHistorySheet(
            state = historyState,
            backdrop = backdrop,
            dark = dark,
            onDismiss = { model.closeVersionHistory() },
            onSelectRevision = { rev -> model.selectHistoryRevision(rev) },
            onRestoreRevision = { noteId, rev -> model.restoreVersionAsNewRevision(noteId, rev) },
        )
    }

    if (showSettingsSheet) {
        val settingsState = SettingsCenterState(
            preferences = appPrefs,
            alarmChecklist = AlarmChecklistState(
                notificationsGranted = alarmHealth.notificationsGranted,
                exactAlarmGranted = alarmHealth.exactAlarmAllowed,
                fullScreenIntentGranted = alarmHealth.fullScreenIntentAllowed,
                batteryOptimizationIgnored = alarmHealth.ignoringBatteryOptimizations,
            ),
            attachmentStorageBytes = attachmentStorageBytes,
            appVersionName = BuildConfig.VERSION_NAME,
        )
        SettingsCenterSheet(
            state = settingsState,
            dark = dark,
            backdrop = backdrop,
            onDismiss = { showSettingsSheet = false },
            onSelectFontScaleTier = { tier -> model.setFontScaleTier(tier) },
            onSelectNoteSortOrder = { order -> model.setNoteSortOrder(order) },
            onToggleSlashShortcuts = { enabled -> model.setEnableSlashShortcuts(enabled) },
            onToggleEdgeEcho = { enabled -> model.setEdgeEchoGlobalEnabled(enabled) },
            onToggleOpenTasksFirst = { enabled -> model.setOpenTasksFirst(enabled) },
            onToggleStrongAlarm = { enabled -> model.setStrongAlarmEnabled(enabled) },
            onOpenNotificationSettings = {
                ReminderScheduler.launchNotificationSettings(context)
            },
            onOpenExactAlarmSettings = {
                ReminderScheduler.launchExactAlarmSettings(context)
            },
            onOpenFullScreenIntentSettings = {
                ReminderScheduler.launchFullScreenIntentSettings(context)
            },
            onOpenBatteryOptimizationSettings = {
                ReminderScheduler.launchBatteryOptimizationSettings(context)
            },
            onToggleWidgetPrivacyMask = { enabled -> model.setWidgetPrivacyModeEnabled(enabled) },
            onSelectTrashRetentionDays = { days -> model.setTrashRetentionDays(days) },
            onOpenBackupRestore = {
                showSettingsSheet = false
                showBackupSheet = true
            },
            onRestoreDiscoveryExclusions = { model.restoreAllDiscoveryExclusions() },
            onRefreshAlarmHealth = {
                alarmHealth = ReminderScheduler.inspectAlarmHealth(context)
            },
            aiSummaryLabel = model.formatAiSummaryLabel(aiSettingsState),
            onOpenAiSettings = {
                showSettingsSheet = false
                model.openAiSettings()
                showAiSettingsSheet = true
            },
        )
    }

    if (showAiSettingsSheet) {
        AiSettingsSheet(
            state = aiSettingsState,
            backdrop = backdrop,
            dark = dark,
            onDismiss = {
                showAiSettingsSheet = false
                model.resetAiSettingsDraftToSaved()
            },
            onSelectPreset = { option -> model.selectAiProviderPreset(option) },
            onToggleEnabled = { enabled -> model.setAiEnabledDraft(enabled) },
            onBaseUrlChange = { url -> model.updateAiBaseUrlDraft(url) },
            onModelChange = { m -> model.updateAiModelDraft(m) },
            onApiKeyDraftChange = { key -> model.updateAiApiKeyDraft(key) },
            onClearSavedApiKey = { model.clearSavedAiApiKey() },
            onTestConnection = { model.testAiConnection() },
            onSave = { model.saveAiSettings { showAiSettingsSheet = false } },
        )
    }

    if (showBackupSheet) {
        BackupRestoreSheet(
            state = backupState,
            backdrop = backdrop,
            dark = dark,
            onDismiss = { showBackupSheet = false },
            onExportFullBackup = { uri -> model.exportFullBackup(uri) },
            onExportMarkdownBundle = { uri -> model.exportMarkdownBundle(uri) },
            onPreflightBackup = { uri -> model.preflightBackup(uri) },
            onSetRestoreMode = { mode -> model.setRestoreMode(mode) },
            onSetAutoBackupBeforeRestore = { enabled -> model.setAutoBackupBeforeRestore(enabled) },
            onConfirmRestore = { model.confirmRestoreFromPreflight() },
            onDismissPreflight = { model.dismissRestorePreflight() },
            onClearOutcome = { model.clearBackupOutcome() },
            externalImportPreview = externalImportState.preview,
            externalImportBusy = externalImportState.busy,
            externalImportOutcome = externalImportState.outcome,
            externalImportError = externalImportState.error,
            skipDuplicateImports = externalImportState.skipDuplicates,
            onPickExternalImport = { uri, name -> model.pickExternalImport(uri, name) },
            onToggleSkipDuplicateImports = { skip -> model.toggleSkipDuplicateImports(skip) },
            onConfirmExternalImport = { model.confirmExternalImport() },
            onDismissExternalImport = { model.dismissExternalImport() },
        )
    }

    if (showBatchTagSheet && inSelectionMode) {
        BatchTagPickerSheet(
            selectedCount = selectedNoteIdSet.size,
            tags = tags,
            tagPaths = tagPaths,
            dark = dark,
            backdrop = backdrop,
            onDismiss = { showBatchTagSheet = false },
            onSelectTag = { tagId ->
                model.batchAddTag(selectedNoteIdSet, tagId)
                showBatchTagSheet = false
                selectedNoteIds = emptyList()
            },
            onCreateAndApplyTag = { name ->
                val targetIds = selectedNoteIdSet.toList()
                showBatchTagSheet = false
                selectedNoteIds = emptyList()
                scope.launch {
                    runCatching {
                        val existing = tags.firstOrNull {
                            it.parentId.isEmpty() && it.name.equals(name.trim(), ignoreCase = true)
                        }
                        val tagId = existing?.id ?: organization.createTag(name.trim())
                        model.batchAddTag(targetIds, tagId)
                    }
                }
            },
        )
    }

    if (showBatchNotebookSheet && inSelectionMode) {
        BatchNotebookPickerSheet(
            selectedCount = selectedNoteIdSet.size,
            notebooks = notebooks,
            dark = dark,
            backdrop = backdrop,
            onDismiss = { showBatchNotebookSheet = false },
            onSelectNotebook = { notebookId ->
                model.batchAssignNotebook(selectedNoteIdSet, notebookId)
                showBatchNotebookSheet = false
                selectedNoteIds = emptyList()
            },
            onCreateAndApplyNotebook = { name ->
                val targetIds = selectedNoteIdSet.toList()
                showBatchNotebookSheet = false
                selectedNoteIds = emptyList()
                scope.launch {
                    runCatching {
                        val existing = notebooks.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
                        val notebookId = existing?.id ?: organization.createNotebook(name.trim()).id
                        model.batchAssignNotebook(targetIds, notebookId)
                    }
                }
            },
        )
    }

    if (showStatsSheet) {
        StatsSheet(
            allNotes = notes + trashedNotes,
            tagRefs = noteTags,
            tags = tags,
            dark = dark,
            backdrop = backdrop,
            onDismiss = { showStatsSheet = false },
            onOpenNote = { noteId ->
                showStatsSheet = false
                showTrash = false
                launchEditor(noteId)
            },
            onSelectTag = { tagId ->
                showStatsSheet = false
                showTrash = false
                keyword.edit { replace(0, length, "") }
                searchScopeJson = NoteScope().encode()
                selectedTag = tagId
                selectedNotebook = null
                descendants = true
                page = Page.Library
            },
        )
    }

    if (cognitiveMapState.visible) {
        CognitiveMapSheet(
            state = cognitiveMapState,
            dark = dark,
            backdrop = backdrop,
            onDismiss = { model.closeCognitiveMap() },
            onSelectMode = { mode -> model.setCognitiveMapMode(mode) },
            onSelectMonth = { month -> model.setCognitiveMapMonthFilter(month) },
            onToggleRecluster = { recluster -> model.setCognitiveMapReclusterForMonth(recluster) },
            onRenameCluster = { clusterId, customName -> model.renameCognitiveMapCluster(clusterId, customName) },
            onOpenNote = { noteId ->
                model.closeCognitiveMap()
                launchEditor(noteId = noteId)
            },
            onStartWalkFromNote = { noteId ->
                model.closeCognitiveMap()
                exploreSectionIndex = 2
                page = Page.Explore
                model.startWalk(seedNoteId = noteId, seedTagId = null)
            },
        )
    }

    sharingNoteId?.let { targetId ->
        val shareNote = notes.firstOrNull { it.id == targetId }
        if (shareNote != null) {
            ShareCardSheet(
                note = shareNote,
                tagNames = tagIdsByNoteId[shareNote.id].orEmpty().mapNotNull(tagPaths::get),
                dark = dark,
                backdrop = backdrop,
                onDismiss = { sharingNoteId = null },
            )
        } else {
            LaunchedEffect(targetId) { sharingNoteId = null }
        }
    }

    if (showDraftsSheet) {
        val visibleCaptures = captures
        if (visibleCaptures.isEmpty()) {
            LaunchedEffect(Unit) { showDraftsSheet = false }
        } else {
            MintBottomSheet(
                title = "待整理草稿 (${visibleCaptures.size})",
                dark = dark,
                backdrop = backdrop,
                onDismissRequest = { showDraftsSheet = false },
                leadingLabel = "关闭",
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    visibleCaptures.forEach { draft ->
                        DraftBannerCard(
                            sourceUrl = draft.sourceUrl,
                            dark = dark,
                            onClick = {
                                showDraftsSheet = false
                                launchEditor(captureSlot = draft.slot)
                            },
                        )
                    }
                }
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = !motion.visible && !showTasksOverlay,
        scrimColor = Color.Black.copy(alpha = if (dark) .54f else .28f),
        drawerContent = {
            NotesDrawer(
                backdrop = backdrop,
                dark = dark,
                total = notes.count { it.archivedAt == null },
                archived = notes.count { it.archivedAt != null },
                archiveSelected = page == Page.Record && !showTrash && showArchive,
                allSelected = page == Page.Record && !showTrash && !showArchive,
                tags = tags,
                notebooks = notebooks,
                filter = if (page == Page.Library && !showTrash) LibraryFilter(selectedTag, selectedNotebook, descendants) else LibraryFilter(),
                onRecord = { archive ->
                    selectedNoteIds = emptyList()
                    showTrash = false
                    showArchive = archive
                    page = Page.Record
                    scope.launch { drawer.close() }
                },
                onLibrary = { filter ->
                    selectedNoteIds = emptyList()
                    showTrash = false
                    keyword.edit { replace(0, length, "") }
                    searchScopeJson = NoteScope().encode()
                    selectedTag = filter.tag
                    selectedNotebook = filter.notebook
                    descendants = filter.descendants
                    page = Page.Library
                    scope.launch { drawer.close() }
                },
                notebookCounts = notebookCounts,
                tagCounts = tagCounts,
                pendingTasksCount = badgeTasksCount,
                trashedCount = trashedCount,
                trashSelected = showTrash,
                onOpenTasks = {
                    selectedNoteIds = emptyList()
                    showTrash = false
                    scope.launch { drawer.close() }
                    showTasksOverlay = true
                },
                onOpenTrash = {
                    selectedNoteIds = emptyList()
                    showArchive = false
                    showTrash = true
                    scope.launch { drawer.close() }
                },
                onOpenStats = {
                    scope.launch { drawer.close() }
                    showStatsSheet = true
                },
                onOpenBackup = {
                    scope.launch { drawer.close() }
                    showSettingsSheet = true
                },
                labs = labs,
                debugSampleSlot = {
                    DebugSampleAction { loaded ->
                        showTrash = false
                        samples = loaded
                        scope.launch { drawer.close() }
                    }
                },
            )
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = if (showTasksOverlay) 0f else 1f }
                    .glassSource(backdrop)
                    .mintAmbient(dark)
                    .then(
                        if (motion.visible || drawer.isOpen || showTasksOverlay) {
                            Modifier.clearAndSetSemantics { }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                if (showTrash) {
                    TrashView(
                        trashedNotes = trashedNotes,
                        purgeImpact = trashPurgeImpact,
                        dark = dark,
                        backdrop = backdrop,
                        onOpenDrawer = { scope.launch { drawer.open() } },
                        onRestore = { note -> model.restoreFromTrash(note) },
                        onPreparePurgeNote = { note -> model.preparePurgeNote(note) },
                        onPrepareEmptyTrash = { model.prepareEmptyTrash() },
                        onConfirmPurgeNote = { note -> model.permanentlyDeleteNote(note) },
                        onConfirmEmptyTrash = { model.emptyTrash() },
                        onClearPurgeImpact = { model.clearPurgeImpact() },
                        onOpenBackupExport = { showBackupSheet = true },
                        retentionDays = appPrefs.trashRetentionDays,
                    )
                } else {
                    val tabAnimEnabled = mintAnimatorsEnabled()
                    AnimatedContent(
                        targetState = page,
                        transitionSpec = {
                            if (!tabAnimEnabled) {
                                fadeIn(snap()) togetherWith fadeOut(snap())
                            } else {
                                (fadeIn(tween(150, delayMillis = 75)) +
                                    slideInVertically(tween(150, delayMillis = 75)) { (it * 0.015f).toInt() }) togetherWith
                                    fadeOut(tween(75))
                            }
                        },
                        label = "home-page",
                    ) { current ->
                        if (current == Page.Library) {
                            val visible = remember(
                                librarySearching,
                                libraryResults,
                                libraryLimit,
                                phrase,
                                notes,
                                selectedTag,
                                tagMatches,
                                selectedNotebook,
                                notebookMatches,
                                appPrefs.noteSortOrder,
                            ) {
                                if (librarySearching) {
                                    val sliced = libraryResults.orEmpty().take(libraryLimit)
                                    if (phrase.isBlank()) sortNotesByOrder(sliced, appPrefs.noteSortOrder) else sliced
                                } else {
                                    val filtered = notes.filter {
                                        it.archivedAt == null &&
                                            (selectedTag == null || it.id in tagMatches) &&
                                            (selectedNotebook == null || it.id in notebookMatches)
                                    }
                                    sortNotesByOrder(filtered, appPrefs.noteSortOrder)
                                }
                            }
                            val hasOrgFilter = selectedTag != null || selectedNotebook != null
                            val libraryBuckets = remember(visible, librarySearching, appPrefs.noteSortOrder) {
                                if (librarySearching && visible.isNotEmpty()) {
                                    listOf(NoteGroupBucket("search", "搜索结果 · ${visible.size}", visible))
                                } else {
                                    bucketNotesForAppleGroups(visible, sortOrder = appPrefs.noteSortOrder)
                                }
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .windowInsetsPadding(
                                        WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                                    )
                                    .navigationBarsPadding()
                                    .padding(horizontal = 20.dp),
                            ) {
                                ShellTopHeader(
                                    title = "笔记库",
                                    subtitle = null,
                                    dark = dark,
                                    onOpenDrawer = { scope.launch { drawer.open() } },
                                    trailingAction = {
                                        MintIconButton(
                                            onClick = { showSettingsSheet = true },
                                            contentDescription = "设置与数据管理",
                                            dark = dark,
                                            icon = MintIcons.Settings,
                                            tint = scheme.onSurfaceVariant,
                                            containerColor = mintSecondaryFill(dark),
                                            modifier = Modifier.testTag("open-backup-settings"),
                                        )
                                    },
                                )
                                Spacer(Modifier.height(6.dp))
                                LibrarySearchControls(
                                    repository = model.search,
                                    keyword = keyword,
                                    scope = libraryScope,
                                    tags = tags,
                                    notebooks = notebooks,
                                    onFilter = { updated ->
                                        selectedTag = null
                                        selectedNotebook = null
                                        descendants = true
                                        searchScopeJson = updated.encode()
                                    },
                                    resultCount = if (librarySearching && libraryResults == null) null else visible.size,
                                    hasExtraFilter = hasOrgFilter,
                                    onResetExtraFilter = {
                                        selectedTag = null
                                        selectedNotebook = null
                                        descendants = true
                                    },
                                    dark = dark,
                                )
                                LazyColumn(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .testTag("library-list"),
                                    contentPadding = PaddingValues(top = 10.dp, bottom = 116.dp),
                                ) {
                                    if (!librarySearching) {
                                        item("org_panel") {
                                            Box(modifier = Modifier.padding(bottom = 10.dp)) {
                                                OrganizationPanel(
                                                    repository = organization,
                                                    tags = tags,
                                                    notebooks = notebooks,
                                                    filter = LibraryFilter(selectedTag, selectedNotebook, descendants),
                                                    onFilter = {
                                                        selectedTag = it.tag
                                                        selectedNotebook = it.notebook
                                                        descendants = it.descendants
                                                    },
                                                    notebookCounts = notebookCounts,
                                                    tagCounts = tagCounts,
                                                    dark = dark,
                                                )
                                            }
                                        }
                                    }
                                    if (librarySearching && libraryResults == null) {
                                        item("lib_searching") {
                                            Box(modifier = Modifier.padding(top = 8.dp)) {
                                                if (librarySearchFailed) {
                                                    TextButton(onClick = { searchAttempt++ }) {
                                                        Text(
                                                            text = "搜索失败，点此重试",
                                                            color = scheme.primary,
                                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                                        )
                                                    }
                                                } else {
                                                    Text(
                                                        text = "正在搜索…",
                                                        color = scheme.onSurfaceVariant,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                                                    )
                                                }
                                            }
                                        }
                                    } else if (!librarySearching && !hasOrgFilter) {
                                        item("lib_idle_guide") {
                                            Box(modifier = Modifier.padding(top = 6.dp)) {
                                                EmptyStateCard(
                                                    title = "按条件精准定位",
                                                    subtitle = "在上方输入关键词、点击筛选器，或展开笔记本与标签浏览对应笔记。",
                                                    dark = dark,
                                                )
                                            }
                                        }
                                    } else if (visible.isEmpty()) {
                                        item("lib_empty") {
                                            Box(modifier = Modifier.padding(top = 6.dp)) {
                                                EmptyStateCard(
                                                    title = "此范围没有笔记",
                                                    subtitle = "调整搜索关键词或筛选条件，或点右下角新建笔记。",
                                                    dark = dark,
                                                )
                                            }
                                        }
                                    } else {
                                        libraryBuckets.forEachIndexed { index, bucket ->
                                            groupedNoteBucket(
                                                bucket = bucket,
                                                topSpacing = if (index == 0) 4.dp else 16.dp,
                                                attachments = model.attachments,
                                                dark = dark,
                                                notebookById = notebookById,
                                                notebookByNoteId = notebookByNoteId,
                                                tagIdsByNoteId = tagIdsByNoteId,
                                                tagPaths = tagPaths,
                                                noteDueBadges = noteDueBadges,
                                                selectionMode = inSelectionMode,
                                                selectedNoteIds = selectedNoteIdSet,
                                                onToggleSelect = { toggleNoteSelection(it.id) },
                                                onEnterSelection = { enterNoteSelection(it.id) },
                                                onOpen = { launchEditor(it.id) },
                                                onPin = { model.pin(it) },
                                                onArchive = { model.archive(it) },
                                                onOrganize = { organizeNote = it.id },
                                                onToggleAiPolicy = {
                                                    val nextPolicy = if (it.aiPolicy == "exclude") "inherit" else "exclude"
                                                    model.setNoteAiPolicy(it.id, nextPolicy)
                                                },
                                                onHistory = { model.openVersionHistory(it.id) },
                                                onShareCard = { sharingNoteId = it.id },
                                                onMoveToTrash = { model.moveToTrash(it) },
                                            )
                                        }
                                    }
                                    if (librarySearching && (libraryResults?.size ?: 0) > libraryLimit) {
                                        item("lib_more") {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = 12.dp),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                TextButton(
                                                    onClick = { searchLimit += 50 },
                                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                                                ) {
                                                    Text(
                                                        text = "加载更多",
                                                        color = scheme.primary,
                                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            val archivedFiltered = remember(notes, showArchive) {
                                notes.filter { (it.archivedAt != null) == showArchive }
                            }
                            val recordNotes = remember(
                                archivedFiltered,
                                showArchive,
                                recordFilter,
                                activeTodoNoteIds,
                                appPrefs.noteSortOrder,
                            ) {
                                val filtered = if (!showArchive && recordFilter == RecordFilter.Todos) {
                                    archivedFiltered.filter { it.id in activeTodoNoteIds }
                                } else {
                                    archivedFiltered
                                }
                                sortNotesByOrder(filtered, appPrefs.noteSortOrder)
                            }
                            val recordBuckets = remember(recordNotes, showArchive, appPrefs.noteSortOrder) {
                                bucketNotesForAppleGroups(
                                    notes = recordNotes,
                                    includePinnedBucket = !showArchive,
                                    sortOrder = appPrefs.noteSortOrder,
                                )
                            }

                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .windowInsetsPadding(
                                        WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                                    )
                                    .navigationBarsPadding(),
                                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 0.dp, bottom = 116.dp),
                            ) {
                                item("shell_header") {
                                    val headerTitle = if (current == Page.Record) {
                                        if (showArchive) "归档" else "浮记"
                                    } else {
                                        current.label
                                    }
                                    val headerSubtitle = when (current) {
                                        Page.Record -> when {
                                            showArchive -> "${archivedFiltered.size} 条已归档"
                                            notes.isEmpty() -> "记下一件今天想留住的事"
                                            else -> "${archivedFiltered.size} 条笔记"
                                        }
                                        Page.Explore -> null
                                        Page.Agent -> if (agentState.remoteAiEnabled) "BYOK 协同" else "本机私有"
                                        Page.Library -> null
                                    }
                                    ShellTopHeader(
                                        title = headerTitle,
                                        subtitle = headerSubtitle,
                                        dark = dark,
                                        onOpenDrawer = { scope.launch { drawer.open() } },
                                        trailingAction = if (current == Page.Record && !showArchive) {
                                            {
                                                Row(
                                                    modifier = Modifier
                                                        .defaultMinSize(minHeight = 48.dp)
                                                        .clip(RoundedCornerShape(10.dp))
                                                        .clickable(
                                                            interactionSource = remember { MutableInteractionSource() },
                                                            indication = null,
                                                            role = Role.Button,
                                                            onClick = { showTasksOverlay = true },
                                                        )
                                                        .padding(horizontal = 6.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                                ) {
                                                    Icon(
                                                        imageVector = MintIcons.Checklist,
                                                        contentDescription = null,
                                                        tint = mintTodoAccent(dark).fg,
                                                        modifier = Modifier.size(17.dp),
                                                    )
                                                    Text(
                                                        text = "待办清单",
                                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                                        color = scheme.onSurface,
                                                    )
                                                }
                                            }
                                        } else null,
                                    )
                                }

                                if (current == Page.Record) {
                                    if (!showArchive) {
                                        item("record_quick_prompt") {
                                            QuickComposeTriggerBar(
                                                dark = dark,
                                                onClick = { launchEditor() },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = 10.dp),
                                            )
                                        }
                                        item("record_filter_bar") {
                                            MintSegmentedBar(
                                                options = listOf(RecordFilter.All.label, RecordFilter.Todos.label),
                                                selectedIndex = recordFilter.ordinal,
                                                onSelect = { recordFilter = RecordFilter.entries[it] },
                                                dark = dark,
                                                badges = listOf(null, badgeTasksCount.takeIf { it > 0 }),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = 8.dp, bottom = 6.dp),
                                            )
                                        }
                                    }
                                    model.error?.let { err ->
                                        item("record_error") {
                                            Text(
                                                text = err,
                                                color = scheme.error,
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.padding(vertical = 6.dp),
                                            )
                                        }
                                    }
                                    if (!showArchive && captures.isNotEmpty()) {
                                        item("record_drafts") {
                                            Box(modifier = Modifier.padding(top = 8.dp)) {
                                                if (captures.size == 1) {
                                                    val draft = captures.first()
                                                    DraftBannerCard(
                                                        sourceUrl = draft.sourceUrl,
                                                        dark = dark,
                                                        onClick = { launchEditor(captureSlot = draft.slot) },
                                                    )
                                                } else {
                                                    DraftsSummaryBanner(
                                                        count = captures.size,
                                                        dark = dark,
                                                        onClick = { showDraftsSheet = true },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    if (recordNotes.isEmpty() && samples.isEmpty()) {
                                        item("record_empty") {
                                            Box(modifier = Modifier.padding(top = 12.dp)) {
                                                EmptyStateCard(
                                                    title = when {
                                                        showArchive -> "归档箱是空的"
                                                        recordFilter == RecordFilter.Todos -> "暂无含待办的笔记"
                                                        else -> "还没有记录"
                                                    },
                                                    subtitle = when {
                                                        showArchive -> "把暂时不需要频繁查看的笔记归档后，会集中收纳在这里。"
                                                        recordFilter == RecordFilter.Todos -> "在任意笔记中插入待办事项，就会出现在这里。"
                                                        else -> "轻点右下角写笔记图标，一句话立刻存进本机。"
                                                    },
                                                    dark = dark,
                                                )
                                            }
                                        }
                                    } else {
                                        recordBuckets.forEachIndexed { index, bucket ->
                                            groupedNoteBucket(
                                                bucket = bucket,
                                                topSpacing = if (index == 0) 10.dp else 16.dp,
                                                attachments = model.attachments,
                                                dark = dark,
                                                notebookById = notebookById,
                                                notebookByNoteId = notebookByNoteId,
                                                tagIdsByNoteId = tagIdsByNoteId,
                                                tagPaths = tagPaths,
                                                noteDueBadges = noteDueBadges,
                                                selectionMode = inSelectionMode,
                                                selectedNoteIds = selectedNoteIdSet,
                                                onToggleSelect = { toggleNoteSelection(it.id) },
                                                onEnterSelection = { enterNoteSelection(it.id) },
                                                onOpen = { launchEditor(it.id) },
                                                onPin = { model.pin(it) },
                                                onArchive = { model.archive(it) },
                                                onOrganize = { organizeNote = it.id },
                                                onToggleAiPolicy = {
                                                    val nextPolicy = if (it.aiPolicy == "exclude") "inherit" else "exclude"
                                                    model.setNoteAiPolicy(it.id, nextPolicy)
                                                },
                                                onHistory = { model.openVersionHistory(it.id) },
                                                onShareCard = { sharingNoteId = it.id },
                                                onMoveToTrash = { model.moveToTrash(it) },
                                            )
                                        }
                                    }
                                    if (samples.isNotEmpty()) {
                                        item("samples_spacer") { Spacer(Modifier.height(14.dp)) }
                                        item("samples_group") {
                                            MintGroupedSection(dark = dark) {
                                                samples.forEachIndexed { index, text ->
                                                    Text(
                                                        text = text,
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(horizontal = 16.dp, vertical = 14.dp),
                                                        style = MaterialTheme.typography.bodyLarge,
                                                        color = scheme.onSurface,
                                                    )
                                                    if (index < samples.lastIndex) {
                                                        MintGroupedDivider(dark = dark, startIndent = 16.dp)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } else if (current == Page.Explore) {
                                    item("explore_section") {
                                        Box(modifier = Modifier.padding(top = 10.dp)) {
                                            ExploreScreen(
                                                state = exploreState,
                                                tags = tags,
                                                notesCount = notes.count { it.archivedAt == null },
                                                linksCount = links.size,
                                                openTodosCount = badgeTasksCount,
                                                dark = dark,
                                                backdrop = backdrop,
                                                onEnsureLoaded = { model.loadExploreDashboard() },
                                                onClearMessage = { model.clearExploreMessage() },
                                                onStartExtraReviewRound = { model.loadExploreDashboard(startExtraRound = true) },
                                                onSelectReviewIndex = { idx -> model.selectReviewCard(idx) },
                                                onDeferReviewCard = { id -> model.deferReviewCard(id) },
                                                onSuppressReviewCard7Days = { id -> model.suppressReviewCard7Days(id) },
                                                onExcludeReviewCard = { id -> model.excludeReviewCard(id) },
                                                onExcludeReviewTag = { tid, name -> model.excludeReviewTag(tid, name) },
                                                onUpdateReviewConfig = { cfg -> model.updateReviewConfig(cfg) },
                                                onRestoreAllExclusions = { model.restoreAllDiscoveryExclusions() },
                                                onAnnotateReviewCard = { note, text -> model.annotateReviewCard(note, text) },
                                                onQueryChange = { q -> model.updateFindQuery(q) },
                                                onScopeChange = { sc -> model.updateFindScope(sc) },
                                                onSearchFind = { q, sc -> model.runFindSearch(q, sc) },
                                                onRefreshIndex = { model.refreshRetrievalIndex() },
                                                onToggleBasket = { hit -> model.toggleEvidenceBasket(hit) },
                                                onClearBasket = { model.clearEvidenceBasket() },
                                                onSaveBasketAsNote = { model.saveEvidenceBasketAsNote() },
                                                onExcludeFindNote = { nid -> model.excludeNoteFromFind(nid) },
                                                onStartWalk = { nid, tid -> model.startWalk(nid, tid) },
                                                onStepWalkForward = { cross -> model.stepWalkForward(cross) },
                                                onStepWalkBack = { model.stepWalkBack() },
                                                onSaveWalkMemo = { memo -> model.saveWalkStepMemo(memo) },
                                                onFinishWalkPostcard = { model.finishWalkAndPreviewPostcard() },
                                                onToggleWalkPrivacy = { priv -> model.toggleWalkPostcardPrivacy(priv) },
                                                onSaveWalkPostcardAsNote = { model.saveWalkPostcardAsNote() },
                                                onDismissWalkPostcard = { model.dismissWalkPostcard() },
                                                onSelectWalkMode = { wm -> model.selectWalkMode(wm) },
                                                onDrawNextDualPair = { model.drawNextDualPair() },
                                                onStepDualPairBack = { model.stepDualPairBack() },
                                                onAnalyzeDualPair = { model.analyzeCurrentDualPair() },
                                                onMarkDualFarFetched = { model.markDualBridgeFarFetched() },
                                                onSaveDualAsNote = { refl -> model.saveDualCollisionAsNote(refl) },
                                                onOpenNote = { noteId, blockId -> launchEditor(noteId = noteId, focusBlockId = blockId) },
                                                onOpenTodos = {
                                                    recordFilter = RecordFilter.Todos
                                                    page = Page.Record
                                                },
                                                onOpenStats = { showStatsSheet = true },
                                                onShareNote = { note -> sharingNoteId = note.id },
                                                selectedSectionIndex = exploreSectionIndex,
                                                onSelectSection = { exploreSectionIndex = it },
                                                onMarkReviewCardSeen = { model.markReviewCardSeen(it) },
                                                onOpenCognitiveMap = { model.openCognitiveMap() },
                                            )
                                        }
                                    }
                                } else {
                                    item("agent_section") {
                                        Box(modifier = Modifier.padding(top = 10.dp)) {
                                            AgentWorkspace(
                                                state = agentState,
                                                notesCount = notes.count { it.archivedAt == null },
                                                tags = tags,
                                                dark = dark,
                                                onPromptChange = { model.updateAgentPrompt(it) },
                                                onSelectTimeRange = { model.selectAgentTimeRange(it) },
                                                onSelectTagId = { model.selectAgentTagId(it) },
                                                onRunSkill = { model.runAgentSkill(it) },
                                                onRunQuestion = { model.runAgentQuestion(it) },
                                                onStopRun = { model.stopCurrentAgentRun() },
                                                onSelectPastRun = { model.loadPastAgentRun(it) },
                                                onSaveAsNote = { model.commitSaveAgentRunAsNote() },
                                                onExtractTodos = { model.commitExtractAgentTodosAsNote() },
                                                onOpenNote = { nid -> launchEditor(noteId = nid) },
                                                onClearMessage = { model.clearAgentMessage() },
                                                onQuickCapture = { launchEditor() },
                                                onMergeDuplicateGroup = { grp, trash ->
                                                    model.commitMergeDuplicateGroup(grp, trash)
                                                },
                                                onSelectTab = { idx -> model.selectAgentTab(idx) },
                                                onOpenAiSettings = {
                                                    model.openAiSettings()
                                                    showAiSettingsSheet = true
                                                },
                                                onRerunLocally = { model.rerunAgentLocally() },
                                                onFollowUpDraftChange = { draft -> model.updateAgentFollowUpDraft(draft) },
                                                onSendFollowUp = { q -> model.sendAgentFollowUp(q) },
                                                onApplyWriteProposal = { prop -> model.applyWriteProposal(prop) },
                                                onDismissWriteProposal = { prop -> model.dismissWriteProposal(prop) },
                                                onRunInsightPreset = { preset -> model.runInsightPreset(preset) },
                                                onSaveCustomPreset = { id, name, desc, prompt, days ->
                                                    model.saveCustomInsightPreset(id, name, desc, prompt, days)
                                                },
                                                onDeleteCustomPreset = { id -> model.deleteCustomInsightPreset(id) },
                                                onContinueChatFromInsight = { insight -> model.continueChatFromInsight(insight) },
                                                onSaveInsightAsNote = { insight -> model.saveInsightAsNote(insight) },
                                                onToggleMemoryAutoReference = { enabled ->
                                                    model.toggleMemoryAutoReference(enabled)
                                                },
                                                onExtractMemoryUpdates = { model.extractMemoryUpdates() },
                                                onCommitMemoryCandidates = { chosen -> model.commitMemoryCandidates(chosen) },
                                                onDismissMemoryCandidates = { model.dismissMemoryCandidates() },
                                                onToggleMemoryItem = { id, enabled -> model.toggleMemoryItem(id, enabled) },
                                                onEditMemoryItem = { id, cat, content ->
                                                    model.editMemoryItem(id, cat, content)
                                                },
                                                onDeleteMemoryItem = { id -> model.deleteMemoryItem(id) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                val imeVisibleForScrim = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                if (!motion.visible && !drawer.isOpen && !showTasksOverlay && !showTrash && !imeVisibleForScrim) {
                    val canvasTint = mintCanvasColor(dark)
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(108.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.Transparent,
                                        canvasTint.copy(alpha = 0.88f),
                                        canvasTint,
                                    ),
                                ),
                            ),
                    )
                }
            }

            if (showTasksOverlay && tasksOverlay != null) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(mintCanvasColor(dark)),
                ) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .clearAndSetSemantics { }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {},
                            ),
                    )
                    tasksOverlay(
                        { showTasksOverlay = false },
                        { noteId ->
                            showTasksOverlay = false
                            launchEditor(noteId)
                        },
                    )
                }
            }

            val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
            Box(
                Modifier
                    .matchParentSize()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                    )
                    .navigationBarsPadding(),
            ) {
                if (!motion.visible && !drawer.isOpen && !showTasksOverlay && !showTrash) {
                    if (inSelectionMode && (page == Page.Record || page == Page.Library)) {
                        val selectedNotesList = notes.filter { it.id in selectedNoteIdSet }
                        val allSelectedArchived = selectedNotesList.isNotEmpty() && selectedNotesList.all { it.archivedAt != null }
                        BatchActionBottomBar(
                            backdrop = backdrop,
                            dark = dark,
                            selectedCount = selectedNoteIdSet.size,
                            allArchived = allSelectedArchived,
                            onCancel = { selectedNoteIds = emptyList() },
                            onAddTag = { showBatchTagSheet = true },
                            onAssignNotebook = { showBatchNotebookSheet = true },
                            onToggleArchive = {
                                model.batchSetArchived(selectedNoteIdSet, !allSelectedArchived)
                                selectedNoteIds = emptyList()
                            },
                            onSoftDelete = {
                                model.batchSoftDelete(selectedNoteIdSet)
                                selectedNoteIds = emptyList()
                            },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .widthIn(max = 640.dp)
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    } else if (!imeVisible) {
                        Row(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .widthIn(max = 640.dp)
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            FloatingNavigation(
                                backdrop = backdrop,
                                dark = dark,
                                current = page,
                                onSelect = {
                                    showTrash = false
                                    page = it
                                },
                                modifier = Modifier.weight(1f),
                            )
                            CircularComposeFab(
                                backdrop = backdrop,
                                tier = supportedGlassTier,
                                dark = dark,
                                onClick = { launchEditor() },
                                modifier = Modifier.testTag("new-note"),
                            )
                        }
                    }
                }
            }

            Box(
                Modifier
                    .matchParentSize()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                    )
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                val session = model.editor
                val editingExisting = session?.existingNote == true
                val activeNote = session?.draft?.noteId?.let { id -> notes.firstOrNull { it.id == id } }
                val undoHandle = if (session != null && editingExisting) {
                    key(session.draft.id) { rememberEditorUndoHandle() }
                } else {
                    null
                }
                CaptureSheet(
                    motion = motion,
                    open = open,
                    backdrop = backdrop,
                    dark = dark,
                    onDismiss = {
                        if (!editingExisting && session?.document()?.hasContent == true) {
                            quickCaptureDismissPrompt = true
                        } else {
                            close()
                        }
                    },
                    onDragClose = { close(it) },
                    onDragStart = { open = true },
                    title = if (editingExisting) "" else "快速记录",
                    dismissLabel = if (editingExisting) "关闭" else "取消",
                    fullScreen = editingExisting,
                    autoFocus = open && session != null && !editingExisting && !suppressNextAutoFocus,
                    headerActions = if (editingExisting) {
                        {
                            EditorHeaderUndoRedoButtons(
                                undoHandle = undoHandle,
                                enabled = session?.closing != true,
                            )
                            if (activeNote != null) {
                                var editorMenu by remember { mutableStateOf(false) }
                                MintIconButton(
                                    onClick = { model.pin(activeNote) },
                                    contentDescription = if (activeNote.pinned) "取消置顶" else "置顶",
                                    dark = dark,
                                    icon = MintIcons.Pin,
                                    tint = if (activeNote.pinned) mintTodoAccent(dark).fg else scheme.onSurfaceVariant,
                                    containerColor = if (activeNote.pinned) {
                                        mintTodoAccent(dark).bg
                                    } else Color.Transparent,
                                )
                                Box {
                                    NoteMenuTrigger(
                                        label = "更多编辑操作",
                                        tag = "editor-note-actions",
                                    ) { editorMenu = true }
                                    ComposablesMenu(expanded = editorMenu, onDismiss = { editorMenu = false }) {
                                        ComposablesMenuItem(
                                            label = "版本历史",
                                            icon = MintIcons.History,
                                        ) {
                                            editorMenu = false
                                            model.openVersionHistory(activeNote.id)
                                        }
                                        ComposablesMenuItem(
                                            label = "生成分享卡片",
                                            icon = MintIcons.Share,
                                        ) {
                                            editorMenu = false
                                            sharingNoteId = activeNote.id
                                        }
                                        ComposablesMenuItem(
                                            label = if (activeNote.archivedAt == null) "归档" else "取消归档",
                                            icon = MintIcons.Archive,
                                        ) {
                                            editorMenu = false
                                            model.archive(activeNote)
                                        }
                                        ComposablesMenuItem(
                                            label = "整理笔记",
                                            icon = MintIcons.Folder,
                                        ) {
                                            editorMenu = false
                                            organizeNote = activeNote.id
                                        }
                                        ComposablesMenuItem(
                                            label = if (activeNote.aiPolicy == "exclude") "允许 AI 整理" else "不参与 AI 整理",
                                            icon = MintIcons.Lock,
                                        ) {
                                            editorMenu = false
                                            model.setNoteAiPolicy(
                                                activeNote.id,
                                                if (activeNote.aiPolicy == "exclude") "inherit" else "exclude",
                                            )
                                        }
                                        ComposablesMenuItem(
                                            label = "移到废纸篓",
                                            icon = MintIcons.Trash,
                                            destructive = true,
                                        ) {
                                            editorMenu = false
                                            keyboard?.hide()
                                            focus.clearFocus()
                                            requestedNoteId = null
                                            requestedCaptureSlot = null
                                            open = false
                                            motion.settle(false)
                                            model.moveToTrash(activeNote)
                                        }
                                    }
                                }
                            }
                            TextButton(
                                onClick = { close(save = true) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                            ) {
                                Text(
                                    text = "完成",
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                    color = scheme.primary,
                                )
                            }
                        }
                    } else if (session != null) {
                        {
                            val canSave = session.document().hasContent
                            Box(
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 48.dp)
                                    .clickable(
                                        enabled = canSave,
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        role = Role.Button,
                                        onClick = { close(save = true) },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(if (canSave) scheme.primary else mintSecondaryFill(dark))
                                        .padding(horizontal = 16.dp, vertical = 7.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "保存",
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                        color = if (canSave) scheme.onPrimary else mintDisabledTextColor(dark),
                                    )
                                }
                            }
                        }
                    } else null,
                ) {
                    if (session != null) {
                        val sources = links.filter { it.targetId == session.draft.noteId }
                            .mapTo(hashSetOf()) { it.sourceId }
                        key(session.draft.id) {
                            NoteEditor(
                                session = session,
                                model = model,
                                onSave = { close(save = true) },
                                onDiscard = { close(discard = true) },
                                notes = notes,
                                backlinks = notes.filter { it.id in sources },
                                activeNote = activeNote,
                                tags = tags,
                                notebooks = notebooks,
                                noteTagIds = tagIdsByNoteId[session.draft.noteId]?.toSet().orEmpty(),
                                noteNotebookId = notebookByNoteId[session.draft.noteId],
                                backdrop = backdrop,
                                tier = supportedGlassTier,
                                dark = dark,
                                autoFocusKeyboard = open && !editingExisting && !suppressNextAutoFocus,
                                onOpenOrganize = activeNote?.let { note -> { organizeNote = note.id } },
                                onRequestNotificationPermission = onRequestNotificationPermission,
                                onOpenRelated = { id ->
                                    scope.launch(Dispatchers.Main.immediate) {
                                        switchingNote = true
                                        try {
                                            if (model.openNote(id)) {
                                                requestedNoteId = id
                                                requestedCaptureSlot = null
                                            } else if (model.editor == null) {
                                                open = false
                                                motion.settle(false)
                                            }
                                        } finally {
                                            switchingNote = false
                                        }
                                    }
                                },
                                onStartWalkFromNote = { nid ->
                                    close(save = true)
                                    exploreSectionIndex = 2
                                    page = Page.Explore
                                    model.startWalk(seedNoteId = nid)
                                },
                                trashedNoteIds = trashedNoteIds,
                                trashedNotes = trashedNotes,
                                enableSlashShortcuts = appPrefs.enableSlashShortcuts,
                                isAiExcluded = activeNote?.aiPolicy == "exclude",
                                onToggleAiExcluded = activeNote?.let { note ->
                                    {
                                        val nextPolicy = if (note.aiPolicy == "exclude") "inherit" else "exclude"
                                        model.setNoteAiPolicy(note.id, nextPolicy)
                                    }
                                },
                                undoHandle = undoHandle,
                            )
                        }
                    } else if (open) {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            MintActivityIndicator(color = scheme.primary)
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun EditorHeaderUndoRedoButtons(
    undoHandle: EditorUndoHandle?,
    enabled: Boolean,
) {
    if (undoHandle == null) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EditorHeaderUndoRedoButton(
            icon = EditorIcons.Undo,
            label = "撤销",
            enabled = enabled && undoHandle.canUndo,
            onClick = undoHandle::performUndo,
        )
        EditorHeaderUndoRedoButton(
            icon = EditorIcons.Redo,
            label = "重做",
            enabled = enabled && undoHandle.canRedo,
            onClick = undoHandle::performRedo,
        )
    }
}

@Composable
private fun EditorHeaderUndoRedoButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val scale = rememberMintPressScale(interactionSource, 0.94f)
    Box(
        modifier = Modifier
            .size(40.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .focusProperties { canFocus = false }
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClickLabel = label,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) scheme.onSurfaceVariant else scheme.onSurfaceVariant.copy(alpha = 0.34f),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun QuickCaptureDismissSheet(
    dark: Boolean,
    onKeepDraft: () -> Unit,
    onDiscardDraft: () -> Unit,
    onContinueEditing: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Dialog(
        onDismissRequest = onContinueEditing,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = if (dark) 0.45f else 0.25f))
                    .clearAndSetSemantics { }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onContinueEditing,
                    ),
            )
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 14.dp)
                    .widthIn(max = 460.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MintGroupedSection(
                    dark = dark,
                    shapeRadius = 16.dp,
                    containerColor = mintElevatedSurface(dark),
                ) {
                    Text(
                        text = "未保存的快速记录",
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    MintGroupedDivider(dark = dark, startIndent = 0.dp)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 52.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Button,
                                onClick = onKeepDraft,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "保留草稿并收起",
                            style = MaterialTheme.typography.bodyLarge,
                            color = scheme.primary,
                        )
                    }
                    MintGroupedDivider(dark = dark, startIndent = 0.dp)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 52.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Button,
                                onClick = onDiscardDraft,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "丢弃草稿",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = mintDestructiveColor(dark),
                        )
                    }
                }
                MintGroupedSection(
                    dark = dark,
                    shapeRadius = 16.dp,
                    containerColor = mintElevatedSurface(dark),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 52.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Button,
                                onClick = onContinueEditing,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "继续编辑",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = scheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShellTopHeader(
    title: String,
    subtitle: String?,
    dark: Boolean,
    onOpenDrawer: () -> Unit,
    trailingAction: (@Composable RowScope.() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .testTag("open-notes-drawer")
                    .semantics { contentDescription = "打开笔记导航" }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onOpenDrawer,
                    )
                    .padding(end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = MintIcons.Menu,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "文件夹",
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = scheme.onSurfaceVariant,
                )
            }
            if (trailingAction != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    content = trailingAction,
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.displayLarge,
            color = scheme.onSurface,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FloatingNavigation(
    backdrop: GlassBackdrop,
    dark: Boolean,
    current: Page,
    onSelect: (Page) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val pillColor = if (dark) Color(0xFF2C2C2E) else Color.White
    val navScale = mintFontScale()
    val pillHeight = if (navScale > 1.1f) 58.dp else 54.dp
    GlassSurface(backdrop, GlassTier.Enhanced, dark, modifier, shapeRadius = 30.dp) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(4.dp)) {
            val tabWidth = maxWidth / Page.entries.size
            val targetOffset = tabWidth * current.ordinal
            val animatedPosition by animateDpAsState(
                targetValue = targetOffset,
                animationSpec = mintSpringSpec(),
                label = "tab-selection",
            )
            val position = if (mintAnimatorsEnabled()) animatedPosition else targetOffset
            Box(
                Modifier
                    .offset { IntOffset(position.roundToPx(), 0) }
                    .width(tabWidth)
                    .height(pillHeight)
                    .then(
                        if (!dark) Modifier.shadow(2.dp, RoundedCornerShape(27.dp), ambientColor = Color(0x14000000), spotColor = Color(0x14000000))
                        else Modifier
                    )
                    .clip(RoundedCornerShape(27.dp))
                    .background(pillColor, RoundedCornerShape(27.dp)),
            )
            Row(Modifier.fillMaxWidth().selectableGroup()) {
                for (target in Page.entries) {
                    val selected = current == target
                    val icon = when (target) {
                        Page.Record -> MintIcons.Records
                        Page.Explore -> MintIcons.Explore
                        Page.Agent -> MintIcons.Agent
                        Page.Library -> MintIcons.Library
                    }
                    val tint = if (selected) scheme.primary else scheme.onSurfaceVariant
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = pillHeight)
                            .testTag("tab-${target.name}")
                            .selectable(
                                selected = selected,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Tab,
                            ) { onSelect(target) }
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = target.label,
                            color = tint,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = (12.5f * navScale).sp,
                                lineHeight = (16f * navScale).sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            ),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CircularComposeFab(
    backdrop: GlassBackdrop,
    tier: GlassTier,
    dark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val shadowColor = if (dark) Color(0x55000000) else Color(0x3D1F6B4F)
    Box(
        modifier = modifier
            .size(56.dp)
            .semantics { contentDescription = "快速记录" }
            .shadow(
                elevation = 8.dp,
                shape = CircleShape,
                ambientColor = shadowColor,
                spotColor = shadowColor,
            )
            .clip(CircleShape)
            .background(scheme.primary, CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = "快速记录",
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MintIcons.Compose,
            contentDescription = null,
            tint = scheme.onPrimary,
            modifier = Modifier.size(23.dp),
        )
    }
}

@Composable
private fun QuickComposeTriggerBar(
    dark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val surfaceColor = if (dark) mintCardSurface(true) else Color.White
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 52.dp)
            .then(
                if (dark) {
                    Modifier
                        .clip(shape)
                        .background(surfaceColor, shape)
                        .border(0.5.dp, mintDividerColor(true), shape)
                } else {
                    Modifier
                        .shadow(
                            elevation = 1.5.dp,
                            shape = shape,
                            ambientColor = Color(0x0A000000),
                            spotColor = Color(0x0D000000),
                        )
                        .clip(shape)
                        .background(surfaceColor, shape)
                }
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = MintIcons.Compose,
            contentDescription = null,
            tint = scheme.primary,
            modifier = Modifier.size(19.dp),
        )
        Text(
            text = "写点什么…",
            style = MaterialTheme.typography.bodyMedium,
            color = mintTertiaryTextColor(dark),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DraftsSummaryBanner(
    count: Int,
    dark: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val accent = mintTodoAccent(dark)
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .clip(shape)
            .background(accent.bg, shape)
            .border(0.6.dp, accent.fg.copy(alpha = 0.28f), shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(accent.fg.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MintIcons.Compose,
                contentDescription = null,
                tint = accent.fg,
                modifier = Modifier.size(15.dp),
            )
        }
        Text(
            text = "待整理草稿 · 共 $count 条",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = scheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = MintIcons.ChevronRight,
            contentDescription = null,
            tint = mintTertiaryTextColor(dark),
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun DraftBannerCard(
    sourceUrl: String?,
    dark: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val accent = mintLinkAccent(dark)
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .clip(shape)
            .background(accent.bg, shape)
            .border(0.6.dp, accent.fg.copy(alpha = 0.25f), shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(accent.fg.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MintIcons.Link,
                contentDescription = null,
                tint = accent.fg,
                modifier = Modifier.size(15.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "继续收集草稿",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = scheme.onSurface,
            )
            if (!sourceUrl.isNullOrBlank()) {
                Text(
                    text = sourceUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            imageVector = MintIcons.ChevronRight,
            contentDescription = null,
            tint = mintTertiaryTextColor(dark),
            modifier = Modifier.size(16.dp),
        )
    }
}

private data class NoteGroupBucket(
    val key: String,
    val title: String?,
    val notes: List<NoteEntity>,
)

private fun sortNotesByOrder(
    notes: List<NoteEntity>,
    order: NoteSortOrder,
): List<NoteEntity> {
    val comparator = when (order) {
        NoteSortOrder.UPDATED_DESC -> compareByDescending<NoteEntity> {
            it.updatedAt.ifBlank { it.createdAt }
        }.thenBy { it.id }
        NoteSortOrder.CREATED_DESC -> compareByDescending<NoteEntity> {
            it.createdAt.ifBlank { it.updatedAt }
        }.thenBy { it.id }
    }
    return notes.sortedWith(comparator)
}

private fun bucketNotesForAppleGroups(
    notes: List<NoteEntity>,
    includePinnedBucket: Boolean = true,
    sortOrder: NoteSortOrder = NoteSortOrder.UPDATED_DESC,
): List<NoteGroupBucket> {
    if (notes.isEmpty()) return emptyList()
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val weekAgo = today.minusDays(7)
    val pinned = if (includePinnedBucket) notes.filter { it.pinned } else emptyList()
    val regular = if (includePinnedBucket) notes.filterNot { it.pinned } else notes

    val buckets = mutableListOf<NoteGroupBucket>()
    if (pinned.isNotEmpty()) {
        buckets += NoteGroupBucket(key = "pinned", title = "已置顶", notes = pinned)
    }
    val todayNotes = mutableListOf<NoteEntity>()
    val weekNotes = mutableListOf<NoteEntity>()
    val earlierNotes = mutableListOf<NoteEntity>()
    for (note in regular) {
        val rawTimestamp = when (sortOrder) {
            NoteSortOrder.UPDATED_DESC -> note.updatedAt.ifBlank { note.createdAt }
            NoteSortOrder.CREATED_DESC -> note.createdAt.ifBlank { note.updatedAt }
        }
        val date = runCatching {
            Instant.parse(rawTimestamp).atZone(zone).toLocalDate()
        }.getOrNull()
        when {
            date == null || !date.isBefore(today) -> todayNotes += note
            !date.isBefore(weekAgo) -> weekNotes += note
            else -> earlierNotes += note
        }
    }
    if (todayNotes.isNotEmpty()) {
        buckets += NoteGroupBucket(key = "today", title = "今天", notes = todayNotes)
    }
    if (weekNotes.isNotEmpty()) {
        buckets += NoteGroupBucket(key = "week", title = "过去 7 天", notes = weekNotes)
    }
    if (earlierNotes.isNotEmpty()) {
        buckets += NoteGroupBucket(key = "earlier", title = "更早", notes = earlierNotes)
    }
    return buckets
}

private fun LazyListScope.groupedNoteBucket(
    bucket: NoteGroupBucket,
    topSpacing: androidx.compose.ui.unit.Dp,
    attachments: AttachmentStore,
    dark: Boolean,
    notebookById: Map<String, NotebookEntity>,
    notebookByNoteId: Map<String, String>,
    tagIdsByNoteId: Map<String, List<String>>,
    tagPaths: Map<String, String>,
    noteDueBadges: Map<String, String>,
    selectionMode: Boolean = false,
    selectedNoteIds: Set<String> = emptySet(),
    onToggleSelect: (NoteEntity) -> Unit = {},
    onEnterSelection: (NoteEntity) -> Unit = {},
    onOpen: (NoteEntity) -> Unit,
    onPin: (NoteEntity) -> Unit,
    onArchive: (NoteEntity) -> Unit,
    onOrganize: (NoteEntity) -> Unit,
    onToggleAiPolicy: (NoteEntity) -> Unit = {},
    onHistory: (NoteEntity) -> Unit,
    onShareCard: (NoteEntity) -> Unit = {},
    onMoveToTrash: (NoteEntity) -> Unit,
) {
    if (!bucket.title.isNullOrBlank()) {
        item("hdr_${bucket.key}") {
            Text(
                text = bucket.title,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = topSpacing, bottom = 8.dp),
            )
        }
    } else if (topSpacing > 0.dp) {
        item("sp_${bucket.key}") {
            Spacer(Modifier.height(topSpacing))
        }
    }

    itemsIndexed(
        items = bucket.notes,
        key = { _, note -> "${bucket.key}_${note.id}" },
    ) { index, note ->
        val isLast = index == bucket.notes.lastIndex
        val shape = RoundedCornerShape(16.dp)
        val cardSurface = if (dark) mintCardSurface(true) else Color.White
        val nbName = notebookByNoteId[note.id]?.let { notebookById[it]?.name }
        val noteTagLabels = tagIdsByNoteId[note.id].orEmpty().mapNotNull(tagPaths::get)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = if (isLast) 0.dp else 12.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (dark) {
                            Modifier
                                .clip(shape)
                                .background(cardSurface, shape)
                                .border(0.5.dp, mintDividerColor(true), shape)
                        } else {
                            Modifier
                                .shadow(
                                    elevation = 1.5.dp,
                                    shape = shape,
                                    ambientColor = Color(0x0A000000),
                                    spotColor = Color(0x0D000000),
                                )
                                .clip(shape)
                                .background(cardSurface, shape)
                        }
                    ),
            ) {
                NoteCard(
                    note = note,
                    attachments = attachments,
                    dark = dark,
                    notebookName = nbName,
                    tagLabels = noteTagLabels,
                    dueBadge = noteDueBadges[note.id],
                    selectionMode = selectionMode,
                    selected = note.id in selectedNoteIds,
                    onToggleSelect = { onToggleSelect(note) },
                    onEnterSelection = { onEnterSelection(note) },
                    onOpen = { onOpen(note) },
                    onPin = { onPin(note) },
                    onArchive = { onArchive(note) },
                    onOrganize = { onOrganize(note) },
                    onToggleAiPolicy = { onToggleAiPolicy(note) },
                    onHistory = { onHistory(note) },
                    onShareCard = { onShareCard(note) },
                    onMoveToTrash = { onMoveToTrash(note) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(
    note: NoteEntity,
    attachments: AttachmentStore,
    dark: Boolean,
    notebookName: String? = null,
    tagLabels: List<String> = emptyList(),
    dueBadge: String? = null,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onEnterSelection: () -> Unit = {},
    onOpen: () -> Unit,
    onPin: () -> Unit,
    onArchive: () -> Unit,
    onOrganize: () -> Unit,
    onToggleAiPolicy: () -> Unit = {},
    onHistory: () -> Unit = {},
    onShareCard: () -> Unit = {},
    onMoveToTrash: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val scale = mintFontScale()
    val haptic = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val meta = remember(note.id, note.revision, note.updatedAt) {
        deriveNoteCardMeta(note)
    }
    val aiExcluded = note.aiPolicy == "exclude"
    val selectedBg = if (selectionMode && selected) {
        scheme.primary.copy(alpha = if (dark) 0.14f else 0.08f)
    } else {
        Color.Transparent
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(selectedBg)
            .testTag("saved-note")
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = if (selectionMode) onToggleSelect else onOpen,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (selectionMode) onToggleSelect() else onEnterSelection()
                },
            )
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (selectionMode) {
                Box(modifier = Modifier.padding(top = 4.dp)) {
                    MintCheckbox(
                        checked = selected,
                        onCheckedChange = null,
                        dark = dark,
                        contentDescription = if (selected) "已选中笔记" else "未选中笔记",
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (note.pinned) {
                    val pinTint = mintTodoAccent(dark).fg
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = MintIcons.Pin,
                            contentDescription = null,
                            tint = pinTint,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = "已置顶",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = (13f * scale).sp,
                                lineHeight = (18f * scale).sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = pinTint,
                        )
                    }
                }
                Text(
                    text = meta.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = (17.5f * scale).sp,
                        lineHeight = (24.5f * scale).sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.15).sp,
                    ),
                    color = scheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (meta.preview.isNotBlank()) {
                    Text(
                        text = meta.preview,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = (15.5f * scale).sp,
                            lineHeight = (23f * scale).sp,
                        ),
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!selectionMode) {
                Box {
                    NoteMenuTrigger { menu = true }
                    ComposablesMenu(expanded = menu, onDismiss = { menu = false }) {
                        ComposablesMenuItem("整理笔记", icon = MintIcons.Folder) {
                            menu = false
                            onOrganize()
                        }
                        ComposablesMenuItem("版本历史", icon = MintIcons.History) {
                            menu = false
                            onHistory()
                        }
                        ComposablesMenuItem("生成分享卡片", icon = MintIcons.Share) {
                            menu = false
                            onShareCard()
                        }
                        ComposablesMenuItem(
                            label = if (note.pinned) "取消置顶" else "置顶",
                            icon = MintIcons.Pin,
                        ) {
                            menu = false
                            onPin()
                        }
                        ComposablesMenuItem(
                            label = if (note.archivedAt == null) "归档" else "取消归档",
                            icon = MintIcons.Archive,
                        ) {
                            menu = false
                            onArchive()
                        }
                        ComposablesMenuItem(
                            label = if (aiExcluded) "允许 AI 整理" else "不参与 AI 整理",
                            icon = MintIcons.Lock,
                        ) {
                            menu = false
                            onToggleAiPolicy()
                        }
                        ComposablesMenuItem(
                            label = "移到废纸篓",
                            icon = MintIcons.Trash,
                            destructive = true,
                        ) {
                            menu = false
                            onMoveToTrash()
                        }
                    }
                }
            }
        }

        if (meta.media.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                meta.media.forEach { block ->
                    key(block.id) {
                        AttachmentThumbnail(
                            store = attachments,
                            id = requireNotNull(block.attachmentId),
                            type = block.type,
                            modifier = Modifier.size(46.dp),
                        )
                    }
                }
            }
        }

        val hasUnifiedMeta = meta.relativeTime.isNotBlank() ||
            meta.domainLabel != null ||
            !notebookName.isNullOrBlank() ||
            tagLabels.isNotEmpty() ||
            meta.totalTodos > 0 ||
            meta.mediaTypes.isNotEmpty() ||
            dueBadge != null ||
            aiExcluded
        if (hasUnifiedMeta) {
            val metaTextStyle = MaterialTheme.typography.labelSmall.copy(
                fontSize = (13.5f * scale).sp,
                lineHeight = (19f * scale).sp,
                fontFeatureSettings = "tnum",
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (meta.relativeTime.isNotBlank()) {
                    Text(
                        text = meta.relativeTime,
                        style = metaTextStyle,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                if (meta.totalTodos > 0) {
                    val allDone = meta.doneTodos == meta.totalTodos
                    InlineMetadataTag(
                        text = "${meta.doneTodos}/${meta.totalTodos}",
                        icon = MintIcons.Checklist,
                        tint = if (allDone) scheme.onSurfaceVariant else mintTodoAccent(dark).fg,
                    )
                }
                if (dueBadge != null) {
                    InlineMetadataTag(
                        text = dueBadge,
                        icon = MintIcons.Bell,
                        tint = mintTodoAccent(dark).fg,
                    )
                }
                if (aiExcluded) {
                    InlineMetadataTag(
                        text = "不参与 AI",
                        icon = MintIcons.Lock,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.testTag("note-ai-excluded-badge"),
                    )
                }
                if (meta.domainLabel != null) {
                    InlineMetadataTag(
                        text = meta.domainLabel,
                        icon = MintIcons.Link,
                        tint = mintLinkAccent(dark).fg,
                    )
                }
                if (!notebookName.isNullOrBlank()) {
                    InlineMetadataTag(
                        text = notebookName,
                        icon = MintIcons.Folder,
                        tint = scheme.onSurfaceVariant,
                    )
                }
                tagLabels.take(2).forEach { tag ->
                    Text(
                        text = "#$tag",
                        style = metaTextStyle.copy(fontWeight = FontWeight.Medium),
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (tagLabels.size > 2) {
                    Text(
                        text = "+${tagLabels.size - 2}",
                        style = metaTextStyle,
                        color = scheme.onSurfaceVariant,
                    )
                }
                meta.mediaTypes.take(2).forEach { type ->
                    val mediaIcon = when (type) {
                        "audio" -> MintIcons.Audio
                        "video" -> MintIcons.Video
                        else -> MintIcons.Image
                    }
                    Icon(
                        imageVector = mediaIcon,
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun InlineMetadataTag(
    text: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val scale = mintFontScale()
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = (13.5f * scale).sp,
                lineHeight = (19f * scale).sp,
                fontFeatureSettings = "tnum",
            ),
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EmptyStateCard(
    title: String,
    subtitle: String,
    dark: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    MintGroupedSection(dark = dark) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = scheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FallbackTasksDialog(
    notes: List<NoteEntity>,
    dark: Boolean,
    onDismiss: () -> Unit,
    onOpenNote: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val items = remember(notes) {
        notes.filter { it.archivedAt == null }.flatMap { note ->
            val meta = deriveNoteCardMeta(note)
            runCatching {
                NoteDocument.decode(note.documentJson).blocks
                    .filter { it.type == "checkbox" }
                    .map { block ->
                        FallbackTodoItem(
                            noteId = note.id,
                            noteTitle = meta.title,
                            text = block.text.ifBlank { "未命名待办" },
                            checked = block.checked,
                        )
                    }
            }.getOrDefault(emptyList())
        }
    }
    val pending = items.filter { !it.checked }
    val done = items.filter { it.checked }
    val ordered = pending + done

    MintBottomSheet(
        onDismissRequest = onDismiss,
        dark = dark,
        title = "待办清单",
        subtitle = "${pending.size} 项未完成 · ${done.size} 项已完成",
        leadingLabel = "关闭",
        onLeadingClick = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (ordered.isEmpty()) {
                Text(
                    text = "当前笔记中还没有待办事项，在任意笔记内点「添加待办」即可创建。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                MintGroupedSection(dark = dark) {
                    ordered.forEachIndexed { index, todo ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 52.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    role = Role.Button,
                                ) { onOpenNote(todo.noteId) }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            MintCheckbox(
                                checked = todo.checked,
                                onCheckedChange = null,
                                dark = dark,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = todo.text,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = if (todo.checked) FontWeight.Normal else FontWeight.Medium,
                                    ),
                                    color = if (todo.checked) scheme.onSurfaceVariant else scheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = todo.noteTitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(
                                imageVector = MintIcons.ChevronRight,
                                contentDescription = null,
                                tint = scheme.onSurfaceVariant.copy(alpha = 0.55f),
                                modifier = Modifier.size(15.dp),
                            )
                        }
                        if (index < ordered.lastIndex) {
                            MintGroupedDivider(dark = dark, startIndent = 46.dp)
                        }
                    }
                }
            }
        }
    }
}

private fun deriveNoteCardMeta(note: NoteEntity): NoteCardMeta {
    val doc = runCatching { NoteDocument.decode(note.documentJson) }.getOrNull()
    val blocks = doc?.blocks.orEmpty()
    val media = blocks.filter { it.attachmentId != null }.take(3)
    val mediaTypes = blocks.mapNotNull { block ->
        block.attachmentId?.let { block.type.takeIf(String::isNotBlank) }
    }.toSet()
    val checkboxes = blocks.filter { it.type == "checkbox" }
    val doneTodos = checkboxes.count { it.checked }
    val totalTodos = checkboxes.size

    val rawLines = mutableListOf<String>()
    for (block in blocks) {
        when (block.type) {
            "checkbox" -> {
                val content = block.text.trim()
                if (content.isNotEmpty()) {
                    val mark = if (block.checked) "☑" else "☐"
                    rawLines.add("$mark $content")
                }
            }
            "reference" -> {
                val label = block.text.trim()
                if (label.isNotEmpty()) rawLines.add("@$label")
            }
            else -> {
                block.searchableText.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { rawLines.add(it) }
            }
        }
    }

    val explicitTitle = note.title?.trim()?.takeIf { it.isNotEmpty() }
    val rawTitle: String
    val rawPreview: String
    if (explicitTitle != null) {
        rawTitle = explicitTitle
        val rest = if (rawLines.firstOrNull() == explicitTitle) rawLines.drop(1) else rawLines
        rawPreview = rest.joinToString(" · ")
    } else if (rawLines.isNotEmpty()) {
        rawTitle = rawLines.first()
        rawPreview = rawLines.drop(1).joinToString(" · ")
    } else {
        rawTitle = note.searchText.trim().ifBlank { if (media.isEmpty()) "笔记" else "媒体笔记" }
        rawPreview = ""
    }

    val urlRegex = Regex("""https?://[^\s]+""")
    val matchedUrl = urlRegex.find(rawTitle)?.value
        ?: urlRegex.find(rawPreview)?.value
        ?: note.sourceUrl?.trim()?.takeIf { it.startsWith("http") }
    val domainLabel = matchedUrl?.let { raw ->
        runCatching {
            java.net.URI(raw).host?.removePrefix("www.")?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }
    val cleanedTitle = rawTitle.replace(urlRegex, "").trim().ifBlank { domainLabel ?: rawTitle }
    val cleanedPreview = rawPreview.replace(urlRegex, "").replace(Regex("""^\s*·\s*|\s*·\s*$"""), "").trim()

    val relativeTime = formatRelativeTimestamp(note.updatedAt.ifBlank { note.createdAt })
    return NoteCardMeta(
        title = cleanedTitle,
        preview = cleanedPreview,
        domainLabel = domainLabel,
        media = media,
        mediaTypes = mediaTypes,
        doneTodos = doneTodos,
        totalTodos = totalTodos,
        relativeTime = relativeTime,
    )
}

private fun formatRelativeTimestamp(iso: String): String {
    if (iso.isBlank()) return ""
    val instant = runCatching { Instant.parse(iso) }.getOrNull() ?: return ""
    val now = Instant.now()
    val zone = ZoneId.systemDefault()
    val minutes = ChronoUnit.MINUTES.between(instant, now)
    if (minutes in 0..0) return "刚刚"
    if (minutes in 1..59) return "${minutes} 分钟前"
    val targetDate = instant.atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when {
        targetDate == today -> instant.atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
        targetDate == today.minusDays(1) -> "昨天"
        targetDate.year == today.year -> instant.atZone(zone).format(DateTimeFormatter.ofPattern("M月d日"))
        else -> instant.atZone(zone).format(DateTimeFormatter.ofPattern("yyyy/M/d"))
    }
}

@Composable
private fun BatchActionBottomBar(
    backdrop: GlassBackdrop,
    dark: Boolean,
    selectedCount: Int,
    allArchived: Boolean,
    onCancel: () -> Unit,
    onAddTag: () -> Unit,
    onAssignNotebook: () -> Unit,
    onToggleArchive: () -> Unit,
    onSoftDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val scale = mintFontScale()
    GlassSurface(
        backdrop = backdrop,
        tier = GlassTier.Enhanced,
        dark = dark,
        shapeRadius = 24.dp,
        elevatedModal = true,
        modifier = modifier.testTag("batch-action-bar"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "已选 $selectedCount 项",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = (15f * scale).sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = scheme.onSurface,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .testTag("batch-selected-count"),
                )
                TextButton(
                    onClick = onCancel,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier
                        .defaultMinSize(minHeight = 40.dp)
                        .testTag("batch-cancel"),
                ) {
                    Text(
                        text = "取消多选",
                        style = MaterialTheme.typography.labelLarge.copy(fontSize = (14f * scale).sp),
                        color = scheme.primary,
                    )
                }
            }
            MintGroupedDivider(dark = dark, startIndent = 0.dp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BatchActionItem(
                    label = "加标签",
                    icon = MintIcons.Tag,
                    tint = scheme.onSurface,
                    scale = scale,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("batch-add-tag"),
                    onClick = onAddTag,
                )
                BatchActionItem(
                    label = "笔记本",
                    icon = MintIcons.Folder,
                    tint = scheme.onSurface,
                    scale = scale,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("batch-assign-notebook"),
                    onClick = onAssignNotebook,
                )
                BatchActionItem(
                    label = if (allArchived) "取消归档" else "归档",
                    icon = MintIcons.Archive,
                    tint = scheme.onSurface,
                    scale = scale,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("batch-archive"),
                    onClick = onToggleArchive,
                )
                BatchActionItem(
                    label = "回收站",
                    icon = MintIcons.Trash,
                    tint = mintDestructiveColor(dark),
                    scale = scale,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("batch-delete"),
                    onClick = onSoftDelete,
                )
            }
        }
    }
}

@Composable
private fun BatchActionItem(
    label: String,
    icon: ImageVector,
    tint: Color,
    scale: Float,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(19.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = (12f * scale).sp,
                lineHeight = (15f * scale).sp,
                fontWeight = FontWeight.Medium,
            ),
            color = tint,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BatchTagPickerSheet(
    selectedCount: Int,
    tags: List<TagEntity>,
    tagPaths: Map<String, String>,
    dark: Boolean,
    backdrop: GlassBackdrop,
    onDismiss: () -> Unit,
    onSelectTag: (String) -> Unit,
    onCreateAndApplyTag: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val sortedTags = remember(tags, tagPaths) { tags.sortedBy { tagPaths[it.id] ?: it.name } }
    var newTagName by remember { mutableStateOf("") }
    var validationError by remember { mutableStateOf<String?>(null) }

    fun submitNewTag() {
        val trimmed = newTagName.trim()
        when {
            trimmed.isEmpty() -> validationError = "请输入标签名称"
            trimmed.length > 60 || '/' in trimmed || trimmed.any(Char::isISOControl) -> {
                validationError = "名称须为 1–60 个字符，且不能包含斜杠或控制字符"
            }
            else -> onCreateAndApplyTag(trimmed)
        }
    }

    MintBottomSheet(
        onDismissRequest = onDismiss,
        dark = dark,
        backdrop = backdrop,
        title = "批量添加标签",
        subtitle = "为已选中的 $selectedCount 条笔记添加标签",
        leadingLabel = "取消",
        modifier = Modifier.testTag("batch-tag-picker-sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MintTextField(
                    value = newTagName,
                    onValueChange = {
                        newTagName = it
                        validationError = null
                    },
                    placeholder = "新建标签并应用",
                    dark = dark,
                    leadingIcon = MintIcons.Tag,
                    modifier = Modifier.weight(1f),
                )
                MintPillButton(
                    label = "添加",
                    primary = true,
                    dark = dark,
                    enabled = newTagName.isNotBlank(),
                    onClick = ::submitNewTag,
                )
            }
            validationError?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = mintDestructiveColor(dark),
                )
            }
            if (sortedTags.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(mintSecondaryFill(dark))
                        .padding(16.dp),
                ) {
                    Text(
                        text = "还没有已有标签，可在上方直接新建并应用。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                ) {
                    itemsIndexed(
                        items = sortedTags,
                        key = { _, tag -> tag.id },
                    ) { index, tag ->
                        MintGroupedItemSlot(
                            index = index,
                            totalCount = sortedTags.size,
                            dark = dark,
                            dividerStartIndent = 46.dp,
                        ) {
                            MintGroupedRow(
                                label = "#${tagPaths[tag.id] ?: tag.name}",
                                leadingIcon = MintIcons.Tag,
                                showChevron = true,
                                dark = dark,
                                onClick = { onSelectTag(tag.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BatchNotebookPickerSheet(
    selectedCount: Int,
    notebooks: List<NotebookEntity>,
    dark: Boolean,
    backdrop: GlassBackdrop,
    onDismiss: () -> Unit,
    onSelectNotebook: (String?) -> Unit,
    onCreateAndApplyNotebook: (String) -> Unit,
) {
    var newNotebookName by remember { mutableStateOf("") }
    var validationError by remember { mutableStateOf<String?>(null) }

    fun submitNewNotebook() {
        val trimmed = newNotebookName.trim()
        when {
            trimmed.isEmpty() -> validationError = "请输入笔记本名称"
            trimmed.length > 60 || '/' in trimmed || trimmed.any(Char::isISOControl) -> {
                validationError = "名称须为 1–60 个字符，且不能包含斜杠或控制字符"
            }
            else -> onCreateAndApplyNotebook(trimmed)
        }
    }

    MintBottomSheet(
        onDismissRequest = onDismiss,
        dark = dark,
        backdrop = backdrop,
        title = "批量移入笔记本",
        subtitle = "为已选中的 $selectedCount 条笔记指定笔记本",
        leadingLabel = "取消",
        modifier = Modifier.testTag("batch-notebook-picker-sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MintTextField(
                    value = newNotebookName,
                    onValueChange = {
                        newNotebookName = it
                        validationError = null
                    },
                    placeholder = "新建笔记本并移入",
                    dark = dark,
                    leadingIcon = MintIcons.Folder,
                    modifier = Modifier.weight(1f),
                )
                MintPillButton(
                    label = "创建",
                    primary = true,
                    dark = dark,
                    enabled = newNotebookName.isNotBlank(),
                    onClick = ::submitNewNotebook,
                )
            }
            validationError?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = mintDestructiveColor(dark),
                )
            }
            MintGroupedSection(dark = dark) {
                MintGroupedRow(
                    label = "移出笔记本（设为未归类）",
                    leadingIcon = MintIcons.Close,
                    dark = dark,
                    onClick = { onSelectNotebook(null) },
                )
            }
            if (notebooks.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                ) {
                    itemsIndexed(
                        items = notebooks,
                        key = { _, nb -> nb.id },
                    ) { index, notebook ->
                        MintGroupedItemSlot(
                            index = index,
                            totalCount = notebooks.size,
                            dark = dark,
                            dividerStartIndent = 46.dp,
                        ) {
                            MintGroupedRow(
                                label = notebook.name,
                                leadingIcon = MintIcons.Folder,
                                showChevron = true,
                                dark = dark,
                                onClick = { onSelectNotebook(notebook.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}
