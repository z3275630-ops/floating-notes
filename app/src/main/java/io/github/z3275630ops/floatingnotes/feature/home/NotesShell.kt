package io.github.z3275630ops.floatingnotes.feature.home

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.z3275630ops.floatingnotes.DebugSampleAction
import io.github.z3275630ops.floatingnotes.core.model.NoteBlock
import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import io.github.z3275630ops.floatingnotes.data.local.*
import io.github.z3275630ops.floatingnotes.feature.agent.AgentWorkspace
import io.github.z3275630ops.floatingnotes.feature.capture.AttachmentThumbnail
import io.github.z3275630ops.floatingnotes.feature.capture.CaptureMotion
import io.github.z3275630ops.floatingnotes.feature.capture.CaptureSheet
import io.github.z3275630ops.floatingnotes.feature.capture.NoteEditor
import io.github.z3275630ops.floatingnotes.feature.capture.NotesViewModel
import io.github.z3275630ops.floatingnotes.feature.explore.ExploreScreen
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
) {
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
    var showBackupSheet by rememberSaveable { mutableStateOf(false) }
    var quickCaptureDismissPrompt by remember { mutableStateOf(false) }
    val notes by model.notes.collectAsStateWithLifecycle()
    val captures by model.captureDrafts.collectAsStateWithLifecycle()
    val trashedNotes by model.trashedNotes.collectAsStateWithLifecycle()
    val trashedCount by model.trashedCount.collectAsStateWithLifecycle()
    val trashedNoteIds by model.trashedNoteIds.collectAsStateWithLifecycle()
    val historyState by model.historyState.collectAsStateWithLifecycle()
    val trashPurgeImpact by model.trashPurgeImpact.collectAsStateWithLifecycle()
    val backupState by model.backupState.collectAsStateWithLifecycle()
    val exploreState by model.exploreState.collectAsStateWithLifecycle()
    val agentState by model.agentState.collectAsStateWithLifecycle()
    val todoStartupEnabled by model.taskPreferences.observeTodoStartupEnabled().collectAsStateWithLifecycle(
        initialValue = model.taskPreferences.todoStartupEnabled,
    )
    val edgeEchoEnabled by model.explorePreferences.observeEdgeEchoEnabled().collectAsStateWithLifecycle(
        initialValue = model.explorePreferences.edgeEchoEnabled,
    )
    var exploreSectionIndex by rememberSaveable { mutableStateOf(0) }
    val organization = model.organization
    val tags by organization.tags.collectAsStateWithLifecycle(emptyList())
    val notebooks by organization.notebooks.collectAsStateWithLifecycle(emptyList())
    val noteTags by organization.tagsByNote.collectAsStateWithLifecycle(emptyList())
    val notebookNotes by organization.notebookNotes.collectAsStateWithLifecycle(emptyList())
    val links by organization.links.collectAsStateWithLifecycle(emptyList())
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

    val derivedTodoNoteIds = remember(notes) {
        notes.filter { note ->
            runCatching {
                NoteDocument.decode(note.documentJson).blocks.any { it.type == "checkbox" }
            }.getOrDefault(false)
        }.mapTo(hashSetOf()) { it.id }
    }
    val derivedPendingTodosCount = remember(notes) {
        notes.sumOf { note ->
            runCatching {
                NoteDocument.decode(note.documentJson).blocks.count { it.type == "checkbox" && !it.checked }
            }.getOrDefault(0)
        }
    }
    val activeTodoNoteIds = todoFilterNoteIds ?: derivedTodoNoteIds
    val badgeTasksCount = pendingTasksCount ?: derivedPendingTodosCount

    val tagScope = selectedTag?.let { if (descendants) tagSubtree(tags, it) else setOf(it) }
    val tagMatches = noteTags.filter { tagScope != null && it.tagId in tagScope }.mapTo(hashSetOf()) { it.noteId }
    val notebookMatches = notebookNotes.filter { it.notebookId == selectedNotebook }.mapTo(hashSetOf()) { it.noteId }
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
                    model.search.observe(phrase, effectiveScope, searchLimit + 1).collect { value = it }
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
    BackHandler(enabled = showTasksOverlay && !motion.visible) { showTasksOverlay = false }
    BackHandler(enabled = showTrash && !motion.visible && !drawer.isOpen && !showTasksOverlay) { showTrash = false }
    var samples by remember { mutableStateOf(emptyList<String>()) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var hadEditorSession by remember { mutableStateOf(model.editor != null) }
    var switchingNote by remember { mutableStateOf(false) }

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
                open = false
                motion.settle(false)
            }
        }
    }

    fun launchEditor(noteId: String? = null, captureSlot: String? = null, focusBlockId: String? = null) {
        scope.launch(Dispatchers.Main.immediate) {
            val switching = model.editor != null && noteId != null && model.editor?.draft?.noteId != noteId
            if (switching) switchingNote = true
            try {
                if (model.openNote(noteId, captureSlot, focusBlockId)) {
                    requestedNoteId = noteId
                    requestedCaptureSlot = captureSlot
                    quickCaptureDismissPrompt = false
                    open = true
                    motion.settle(true)
                } else if (model.editor == null) {
                    open = false
                    motion.settle(false)
                }
            } finally {
                if (switching) switchingNote = false
            }
        }
    }

    LaunchedEffect(Unit) {
        if (open && !model.openNote(requestedNoteId, requestedCaptureSlot)) {
            open = false
            motion.settle(false)
        }
    }

    LaunchedEffect(deepLinkRequest?.seq) {
        val request = deepLinkRequest ?: return@LaunchedEffect
        when {
            request.noteId != null -> {
                showTasksOverlay = false
                showTrash = false
                launchEditor(noteId = request.noteId, focusBlockId = request.blockId)
            }
            request.openTasks -> {
                showTasksOverlay = true
            }
        }
        onConsumeDeepLink()
    }

    fun close(velocity: Float? = null, save: Boolean = false, discard: Boolean = false) {
        scope.launch(Dispatchers.Main.immediate) {
            if (model.finishEditor(save = save, discard = discard)) {
                keyboard?.hide()
                focus.clearFocus()
                requestedNoteId = null
                requestedCaptureSlot = null
                quickCaptureDismissPrompt = false
                open = false
                if (velocity == null) motion.settle(false) else motion.settle(false, velocity)
            } else if (open) {
                motion.settle(true)
            }
        }
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

    if (showBackupSheet) {
        BackupRestoreSheet(
            state = backupState,
            todoStartupEnabled = todoStartupEnabled,
            onToggleTodoStartup = { enabled -> model.taskPreferences.todoStartupEnabled = enabled },
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
            edgeEchoEnabled = edgeEchoEnabled,
            onToggleEdgeEcho = { enabled -> model.setEdgeEchoGlobalEnabled(enabled) },
            onRestoreDiscoveryExclusions = {
                model.restoreAllDiscoveryExclusions()
                showBackupSheet = false
                exploreSectionIndex = 0
                page = Page.Explore
            },
        )
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = !motion.visible && !showTasksOverlay,
        scrimColor = Color(0xFF0D2118).copy(alpha = if (dark) .54f else .30f),
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
                    showTrash = false
                    showArchive = archive
                    page = Page.Record
                    scope.launch { drawer.close() }
                },
                onLibrary = { filter ->
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
                    showTrash = false
                    scope.launch { drawer.close() }
                    showTasksOverlay = true
                },
                onOpenTrash = {
                    showArchive = false
                    showTrash = true
                    scope.launch { drawer.close() }
                },
                onOpenBackup = {
                    scope.launch { drawer.close() }
                    showBackupSheet = true
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
                    )
                } else {
                    Crossfade(page, label = "home-page") { current ->
                        if (current == Page.Library) {
                            val visible = if (librarySearching) {
                                libraryResults.orEmpty().take(libraryLimit)
                            } else {
                                notes.filter {
                                    it.archivedAt == null &&
                                        (selectedTag == null || it.id in tagMatches) &&
                                        (selectedNotebook == null || it.id in notebookMatches)
                                }
                            }
                            val hasOrgFilter = selectedTag != null || selectedNotebook != null
                            val libraryBuckets = remember(visible, librarySearching) {
                                if (librarySearching && visible.isNotEmpty()) {
                                    listOf(NoteGroupBucket("search", "搜索结果 · ${visible.size}", visible))
                                } else {
                                    bucketNotesForAppleGroups(visible)
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
                                            onClick = { showBackupSheet = true },
                                            contentDescription = "设置与数据管理",
                                            dark = dark,
                                            icon = MintIcons.Settings,
                                            tint = scheme.primary,
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
                                    contentPadding = PaddingValues(top = 10.dp, bottom = 104.dp),
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
                                                onOpen = { launchEditor(it.id) },
                                                onPin = { model.pin(it) },
                                                onArchive = { model.archive(it) },
                                                onOrganize = { organizeNote = it.id },
                                                onHistory = { model.openVersionHistory(it.id) },
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
                            val archivedFiltered = notes.filter { (it.archivedAt != null) == showArchive }
                            val recordNotes = if (!showArchive && recordFilter == RecordFilter.Todos) {
                                archivedFiltered.filter { it.id in activeTodoNoteIds }
                            } else {
                                archivedFiltered
                            }
                            val recordBuckets = remember(recordNotes, showArchive) {
                                bucketNotesForAppleGroups(recordNotes, includePinnedBucket = !showArchive)
                            }

                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .windowInsetsPadding(
                                        WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                                    )
                                    .navigationBarsPadding(),
                                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 0.dp, bottom = 104.dp),
                            ) {
                                item("shell_header") {
                                    val headerTitle = if (current == Page.Record) {
                                        if (showArchive) "归档" else "浮记"
                                    } else {
                                        current.label
                                    }
                                    val headerSubtitle = when (current) {
                                        Page.Record -> when {
                                            showArchive -> "${archivedFiltered.size} 条已归档笔记"
                                            notes.isEmpty() -> "记下一件今天想留住的事"
                                            else -> "${archivedFiltered.size} 条笔记"
                                        }
                                        Page.Explore -> "按时间与关联浏览你的笔记脉络"
                                        Page.Agent -> "本地优先的隐私智能助手"
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
                                                        tint = scheme.primary,
                                                        modifier = Modifier.size(17.dp),
                                                    )
                                                    Text(
                                                        text = "待办清单",
                                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                                        color = scheme.primary,
                                                    )
                                                }
                                            }
                                        } else null,
                                    )
                                }

                                if (current == Page.Record) {
                                    if (!showArchive) {
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
                                    if (!showArchive) {
                                        items(captures, key = { it.id }) { draft ->
                                            Box(modifier = Modifier.padding(top = 8.dp)) {
                                                DraftBannerCard(
                                                    sourceUrl = draft.sourceUrl,
                                                    dark = dark,
                                                    onClick = { launchEditor(captureSlot = draft.slot) },
                                                )
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
                                                onOpen = { launchEditor(it.id) },
                                                onPin = { model.pin(it) },
                                                onArchive = { model.archive(it) },
                                                onOrganize = { organizeNote = it.id },
                                                onHistory = { model.openVersionHistory(it.id) },
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
                                                selectedSectionIndex = exploreSectionIndex,
                                                onSelectSection = { exploreSectionIndex = it },
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
                    val canvasTint = if (dark) Color(0xFF0D1914) else Color(0xFFF4F8F2)
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(96.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.Transparent,
                                        canvasTint.copy(alpha = 0.78f),
                                        canvasTint.copy(alpha = 0.96f),
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
                        .background(if (dark) Color(0xFF0D1813) else Color(0xFFF3FAF5)),
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
                if (!motion.visible && !drawer.isOpen && !showTasksOverlay && !showTrash && !imeVisible) {
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
                    autoFocus = open && session != null && !editingExisting,
                    headerActions = if (editingExisting) {
                        {
                            if (activeNote != null) {
                                var editorMenu by remember { mutableStateOf(false) }
                                MintIconButton(
                                    onClick = { model.pin(activeNote) },
                                    contentDescription = if (activeNote.pinned) "取消置顶" else "置顶",
                                    dark = dark,
                                    icon = MintIcons.Pin,
                                    tint = if (activeNote.pinned) scheme.primary else scheme.onSurfaceVariant,
                                    containerColor = if (activeNote.pinned) {
                                        if (dark) Color(0xFF23523E) else Color(0xFFD7F2E4)
                                    } else Color.Transparent,
                                )
                                MintIconButton(
                                    onClick = { organizeNote = activeNote.id },
                                    contentDescription = "整理笔记",
                                    dark = dark,
                                    icon = MintIcons.Tag,
                                    containerColor = Color.Transparent,
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
                            TextButton(
                                onClick = { close(save = true) },
                                enabled = canSave,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                            ) {
                                Text(
                                    text = "保存",
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (canSave) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.38f),
                                )
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
                                autoFocusKeyboard = open && !editingExisting,
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
                    imageVector = MintIcons.Back,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "文件夹",
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = scheme.primary,
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
            style = MaterialTheme.typography.headlineLarge.copy(
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
            ),
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
    val pillColor = if (dark) Color(0xFF285845) else Color.White.copy(alpha = 0.88f)
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
                    .height(50.dp)
                    .then(
                        if (!dark) Modifier.shadow(2.dp, RoundedCornerShape(25.dp), ambientColor = Color(0x16000000), spotColor = Color(0x16000000))
                        else Modifier
                    )
                    .clip(RoundedCornerShape(25.dp))
                    .background(pillColor, RoundedCornerShape(25.dp)),
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
                            .heightIn(min = 50.dp)
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
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = target.label,
                            color = tint,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            ),
                            maxLines = 1,
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
    GlassSurface(
        backdrop = backdrop,
        tier = tier,
        dark = dark,
        shapeRadius = 29.dp,
        shape = CircleShape,
        elevatedModal = true,
        modifier = modifier
            .size(58.dp)
            .semantics { contentDescription = "快速记录" },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
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
                tint = scheme.primary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun DraftBannerCard(
    sourceUrl: String?,
    dark: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val fill = if (dark) Color(0xFF1D3329) else Color(0xFFEAF7EF)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .clip(shape)
            .background(fill, shape)
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
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(scheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MintIcons.Link,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(16.dp),
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
            tint = scheme.primary,
            modifier = Modifier.size(16.dp),
        )
    }
}

private data class NoteGroupBucket(
    val key: String,
    val title: String?,
    val notes: List<NoteEntity>,
)

private fun bucketNotesForAppleGroups(
    notes: List<NoteEntity>,
    includePinnedBucket: Boolean = true,
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
        val date = runCatching {
            Instant.parse(note.updatedAt.ifBlank { note.createdAt }).atZone(zone).toLocalDate()
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
    onOpen: (NoteEntity) -> Unit,
    onPin: (NoteEntity) -> Unit,
    onArchive: (NoteEntity) -> Unit,
    onOrganize: (NoteEntity) -> Unit,
    onHistory: (NoteEntity) -> Unit,
    onMoveToTrash: (NoteEntity) -> Unit,
) {
    if (!bucket.title.isNullOrBlank()) {
        item("hdr_${bucket.key}") {
            Text(
                text = bucket.title,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = topSpacing, bottom = 6.dp),
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
        val isFirst = index == 0
        val isLast = index == bucket.notes.lastIndex
        val shape = RoundedCornerShape(
            topStart = if (isFirst) 16.dp else 0.dp,
            topEnd = if (isFirst) 16.dp else 0.dp,
            bottomStart = if (isLast) 16.dp else 0.dp,
            bottomEnd = if (isLast) 16.dp else 0.dp,
        )
        val nbName = notebookByNoteId[note.id]?.let { notebookById[it]?.name }
        val noteTagLabels = tagIdsByNoteId[note.id].orEmpty().mapNotNull(tagPaths::get)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(mintCardSurface(dark)),
        ) {
            NoteCard(
                note = note,
                attachments = attachments,
                dark = dark,
                notebookName = nbName,
                tagLabels = noteTagLabels,
                dueBadge = noteDueBadges[note.id],
                onOpen = { onOpen(note) },
                onPin = { onPin(note) },
                onArchive = { onArchive(note) },
                onOrganize = { onOrganize(note) },
                onHistory = { onHistory(note) },
                onMoveToTrash = { onMoveToTrash(note) },
            )
            if (!isLast) {
                MintGroupedDivider(dark = dark, startIndent = 16.dp)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoteCard(
    note: NoteEntity,
    attachments: AttachmentStore,
    dark: Boolean,
    notebookName: String? = null,
    tagLabels: List<String> = emptyList(),
    dueBadge: String? = null,
    onOpen: () -> Unit,
    onPin: () -> Unit,
    onArchive: () -> Unit,
    onOrganize: () -> Unit,
    onHistory: () -> Unit = {},
    onMoveToTrash: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val meta = remember(note.documentJson, note.title, note.searchText, note.updatedAt, note.createdAt) {
        deriveNoteCardMeta(note)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("saved-note")
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onOpen,
            )
            .padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 2.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                if (note.pinned) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = MintIcons.Pin,
                            contentDescription = null,
                            tint = scheme.primary,
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            text = "已置顶",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = scheme.primary,
                        )
                    }
                }
                Text(
                    text = meta.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.15).sp,
                    ),
                    color = scheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (meta.relativeTime.isNotBlank() || meta.preview.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (meta.relativeTime.isNotBlank()) {
                            Text(
                                text = meta.relativeTime,
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        if (meta.preview.isNotBlank()) {
                            Text(
                                text = meta.preview,
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant.copy(alpha = 0.85f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                    }
                }
            }
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

        if (meta.media.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(end = 10.dp),
            ) {
                meta.media.forEach { block ->
                    key(block.id) {
                        AttachmentThumbnail(
                            store = attachments,
                            id = requireNotNull(block.attachmentId),
                            type = block.type,
                            modifier = Modifier.size(44.dp),
                        )
                    }
                }
            }
        }

        val hasQuietMeta = !notebookName.isNullOrBlank() ||
            tagLabels.isNotEmpty() ||
            meta.totalTodos > 0 ||
            meta.mediaTypes.isNotEmpty() ||
            dueBadge != null
        if (hasQuietMeta) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(end = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (meta.totalTodos > 0) {
                    val allDone = meta.doneTodos == meta.totalTodos
                    InlineMetadataTag(
                        text = "${meta.doneTodos}/${meta.totalTodos}",
                        icon = MintIcons.Checklist,
                        tint = if (allDone) scheme.onSurfaceVariant else scheme.primary,
                    )
                }
                if (dueBadge != null) {
                    InlineMetadataTag(
                        text = dueBadge,
                        icon = MintIcons.Bell,
                        tint = scheme.primary,
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
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = scheme.primary.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (tagLabels.size > 2) {
                    Text(
                        text = "+${tagLabels.size - 2}",
                        style = MaterialTheme.typography.labelSmall,
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
                        modifier = Modifier.size(13.dp),
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
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
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
                block.text.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { rawLines.add(it) }
            }
        }
    }

    val explicitTitle = note.title?.trim()?.takeIf { it.isNotEmpty() }
    val title: String
    val preview: String
    if (explicitTitle != null) {
        title = explicitTitle
        val rest = if (rawLines.firstOrNull() == explicitTitle) rawLines.drop(1) else rawLines
        preview = rest.joinToString(" · ")
    } else if (rawLines.isNotEmpty()) {
        title = rawLines.first()
        preview = rawLines.drop(1).joinToString(" · ")
    } else {
        title = note.searchText.trim().ifBlank { if (media.isEmpty()) "笔记" else "媒体笔记" }
        preview = ""
    }

    val relativeTime = formatRelativeTimestamp(note.updatedAt.ifBlank { note.createdAt })
    return NoteCardMeta(
        title = title,
        preview = preview,
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
