package io.github.z3275630ops.floatingnotes.feature.home

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.z3275630ops.floatingnotes.DebugSampleAction
import io.github.z3275630ops.floatingnotes.core.model.NoteBlock
import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import io.github.z3275630ops.floatingnotes.data.local.*
import io.github.z3275630ops.floatingnotes.feature.capture.AttachmentThumbnail
import io.github.z3275630ops.floatingnotes.feature.capture.CaptureMotion
import io.github.z3275630ops.floatingnotes.feature.capture.CaptureSheet
import io.github.z3275630ops.floatingnotes.feature.capture.NoteEditor
import io.github.z3275630ops.floatingnotes.feature.capture.NotesViewModel
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
    val notes by model.notes.collectAsStateWithLifecycle()
    val captures by model.captureDrafts.collectAsStateWithLifecycle()
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
    var samples by remember { mutableStateOf(emptyList<String>()) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current

    fun launchEditor(noteId: String? = null, captureSlot: String? = null, focusBlockId: String? = null) {
        scope.launch(Dispatchers.Main.immediate) {
            if (model.editor != null && noteId != null && model.editor?.draft?.noteId != noteId) {
                model.finishEditor()
            }
            if (model.openNote(noteId, captureSlot, focusBlockId)) {
                requestedNoteId = noteId
                requestedCaptureSlot = captureSlot
                open = true
                motion.settle(true)
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
                archiveSelected = page == Page.Record && showArchive,
                allSelected = page == Page.Record && !showArchive,
                tags = tags,
                notebooks = notebooks,
                filter = if (page == Page.Library) LibraryFilter(selectedTag, selectedNotebook, descendants) else LibraryFilter(),
                onRecord = { archive ->
                    showArchive = archive
                    page = Page.Record
                    scope.launch { drawer.close() }
                },
                onLibrary = { filter ->
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
                onOpenTasks = {
                    scope.launch { drawer.close() }
                    showTasksOverlay = true
                },
                labs = labs,
                debugSampleSlot = {
                    DebugSampleAction { loaded ->
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
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                contentPadding = PaddingValues(top = 10.dp, bottom = 156.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                if (!librarySearching) {
                                    item {
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
                                if (librarySearching && libraryResults == null) {
                                    item {
                                        if (librarySearchFailed) {
                                            MintPillButton("搜索失败，点此重试", onClick = { searchAttempt++ }, dark = dark)
                                        } else {
                                            Text("正在搜索…", color = scheme.onSurfaceVariant)
                                        }
                                    }
                                } else if (visible.isEmpty()) {
                                    item {
                                        EmptyStateCard(
                                            title = "此范围没有笔记",
                                            subtitle = "调整搜索关键词或筛选条件，或点右下角新建笔记。",
                                            dark = dark,
                                        )
                                    }
                                }
                                items(visible, key = { it.id }) { note ->
                                    val nbName = notebookByNoteId[note.id]?.let { notebookById[it]?.name }
                                    val noteTagLabels = tagIdsByNoteId[note.id].orEmpty().mapNotNull(tagPaths::get)
                                    NoteCard(
                                        note = note,
                                        attachments = model.attachments,
                                        dark = dark,
                                        notebookName = nbName,
                                        tagLabels = noteTagLabels,
                                        dueBadge = noteDueBadges[note.id],
                                        onOpen = { launchEditor(note.id) },
                                        onPin = { model.pin(note) },
                                        onArchive = { model.archive(note) },
                                        onOrganize = { organizeNote = note.id },
                                    )
                                }
                                if (librarySearching && (libraryResults?.size ?: 0) > libraryLimit) {
                                    item {
                                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                            MintPillButton("加载更多", onClick = { searchLimit += 50 }, dark = dark)
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

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .windowInsetsPadding(
                                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                                )
                                .navigationBarsPadding(),
                            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 0.dp, bottom = 156.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            item {
                                val headerTitle = if (current == Page.Record) {
                                    if (showArchive) "归档" else "浮记"
                                } else {
                                    current.label
                                }
                                val headerSubtitle = when (current) {
                                    Page.Record -> when {
                                        showArchive -> "${archivedFiltered.size} 条已归档笔记"
                                        notes.isEmpty() -> "记下一件今天想留住的事。"
                                        else -> "${archivedFiltered.size} 条笔记 · 留住今天的想法"
                                    }
                                    Page.Explore -> "按时间与关联浏览你的笔记脉络"
                                    Page.Agent -> "本地优先的隐私智能助手预览"
                                    Page.Library -> null
                                }
                                ShellTopHeader(
                                    title = headerTitle,
                                    subtitle = headerSubtitle,
                                    dark = dark,
                                    onOpenDrawer = { scope.launch { drawer.open() } },
                                )
                            }

                            if (current == Page.Record) {
                                if (!showArchive) {
                                    item {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            MintSegmentedBar(
                                                options = listOf(RecordFilter.All.label, RecordFilter.Todos.label),
                                                selectedIndex = recordFilter.ordinal,
                                                onSelect = { recordFilter = RecordFilter.entries[it] },
                                                dark = dark,
                                                badges = listOf(null, badgeTasksCount.takeIf { it > 0 }),
                                                modifier = Modifier.weight(1f),
                                            )
                                            MintPillButton(
                                                label = "待办清单 →",
                                                icon = MintIcons.Checklist,
                                                dark = dark,
                                                onClick = { showTasksOverlay = true },
                                            )
                                        }
                                    }
                                }
                                model.error?.let { err ->
                                    item {
                                        Text(err, color = scheme.error, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                if (!showArchive) {
                                    items(captures, key = { it.id }) { draft ->
                                        DraftBannerCard(
                                            sourceUrl = draft.sourceUrl,
                                            dark = dark,
                                            onClick = { launchEditor(captureSlot = draft.slot) },
                                        )
                                    }
                                }
                                if (recordNotes.isEmpty() && samples.isEmpty()) {
                                    item {
                                        EmptyStateCard(
                                            title = when {
                                                showArchive -> "归档箱是空的"
                                                recordFilter == RecordFilter.Todos -> "暂无含待办的笔记"
                                                else -> "还没有记录"
                                            },
                                            subtitle = when {
                                                showArchive -> "把暂时不需要频繁查看的笔记归档后，会集中收纳在这里。"
                                                recordFilter == RecordFilter.Todos -> "在任意笔记中插入待办事项，就会出现在这里。"
                                                else -> "轻点右下角「＋ 快速记录」，一句话立刻存进本机。"
                                            },
                                            dark = dark,
                                        )
                                    }
                                }
                                items(recordNotes, key = { it.id }) { note ->
                                    val nbName = notebookByNoteId[note.id]?.let { notebookById[it]?.name }
                                    val noteTagLabels = tagIdsByNoteId[note.id].orEmpty().mapNotNull(tagPaths::get)
                                    NoteCard(
                                        note = note,
                                        attachments = model.attachments,
                                        dark = dark,
                                        notebookName = nbName,
                                        tagLabels = noteTagLabels,
                                        dueBadge = noteDueBadges[note.id],
                                        onOpen = { launchEditor(note.id) },
                                        onPin = { model.pin(note) },
                                        onArchive = { model.archive(note) },
                                        onOrganize = { organizeNote = note.id },
                                    )
                                }
                                items(samples) { text ->
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .border(
                                                0.9.dp,
                                                if (dark) Color(0xFF283E33) else Color(0xFFDCEADF),
                                                RoundedCornerShape(20.dp),
                                            ),
                                        shape = RoundedCornerShape(20.dp),
                                        color = if (dark) Color(0xFF182720) else Color(0xFFFAFDF9),
                                        tonalElevation = 0.dp,
                                        shadowElevation = if (dark) 0.dp else 1.dp,
                                    ) {
                                        Text(
                                            text = text,
                                            modifier = Modifier.padding(18.dp),
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = scheme.onSurface,
                                        )
                                    }
                                }
                            } else if (current == Page.Explore) {
                                item {
                                    ExplorePreviewSection(
                                        notes = notes,
                                        tagsCount = tags.size,
                                        notebooksCount = notebooks.size,
                                        linksCount = links.size,
                                        openTodosCount = badgeTasksCount,
                                        dark = dark,
                                        onOpenTodos = {
                                            recordFilter = RecordFilter.Todos
                                            page = Page.Record
                                        },
                                        onOpenLibrary = { page = Page.Library },
                                    )
                                }
                            } else {
                                item {
                                    AgentPreviewSection(
                                        notesCount = notes.count { it.archivedAt == null },
                                        dark = dark,
                                        onOpenLibrary = { page = Page.Library },
                                        onQuickCapture = { launchEditor() },
                                    )
                                }
                            }
                        }
                    }
                }

                // Soft bottom scrim prevents scrolled cards from colliding harshly beneath floating navigation
                if (!motion.visible && !drawer.isOpen && !showTasksOverlay) {
                    val canvasTint = if (dark) Color(0xFF0D1914) else Color(0xFFF4F8F2)
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(136.dp)
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
                        .background(if (dark) Color(0xFF0D1914) else Color(0xFFF4F8F2)),
                ) {
                    tasksOverlay(
                        { showTasksOverlay = false },
                        { noteId ->
                            showTasksOverlay = false
                            launchEditor(noteId)
                        },
                    )
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
                if (!motion.visible && !drawer.isOpen && !showTasksOverlay) {
                    Column(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .widthIn(max = 640.dp)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        GlassAction(
                            label = "＋ 快速记录",
                            dark = dark,
                            modifier = Modifier.align(Alignment.End).testTag("new-note"),
                        ) {
                            launchEditor()
                        }
                        FloatingNavigation(
                            backdrop = backdrop,
                            dark = dark,
                            current = page,
                            onSelect = { page = it },
                        )
                    }
                }

                val session = model.editor
                val editingExisting = session?.existingNote == true
                val activeNote = session?.draft?.noteId?.let { id -> notes.firstOrNull { it.id == id } }
                CaptureSheet(
                    motion = motion,
                    open = open,
                    backdrop = backdrop,
                    dark = dark,
                    onDismiss = { close() },
                    onDragClose = { close(it) },
                    onDragStart = { open = true },
                    title = if (editingExisting) "" else "快速记录",
                    dismissLabel = "关闭",
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
                                    }
                                }
                            }
                            MintPillButton(
                                label = "完成",
                                primary = true,
                                dark = dark,
                                onClick = { close(save = true) },
                            )
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
                                onOpenOrganize = activeNote?.let { note -> { organizeNote = note.id } },
                                onRequestNotificationPermission = onRequestNotificationPermission,
                            ) { id ->
                                scope.launch(Dispatchers.Main.immediate) {
                                    if (model.finishEditor() && model.openNote(id)) {
                                        requestedNoteId = id
                                        requestedCaptureSlot = null
                                    } else if (model.editor == null) {
                                        open = false
                                        motion.settle(false)
                                    }
                                }
                            }
                        }
                    } else if (open) {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
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
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MintIconButton(
                onClick = onOpenDrawer,
                contentDescription = "打开笔记导航",
                dark = dark,
                icon = MintIcons.Menu,
                containerColor = if (dark) Color(0xFF1A2B22) else Color(0xFFFAFDF9),
                borderColor = scheme.outline.copy(alpha = 0.28f),
                modifier = Modifier.testTag("open-notes-drawer"),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineLarge.copy(
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
    }
}

@Composable
private fun FloatingNavigation(
    backdrop: GlassBackdrop,
    dark: Boolean,
    current: Page,
    onSelect: (Page) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    GlassSurface(backdrop, GlassTier.Enhanced, dark, Modifier.fillMaxWidth(), shapeRadius = 28.dp) {
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
                    .height(52.dp)
                    .background(mintHighlight(dark), RoundedCornerShape(24.dp))
                    .border(
                        0.8.dp,
                        if (dark) Color(0xFF56AD88).copy(alpha = 0.35f) else Color.White.copy(alpha = 0.8f),
                        RoundedCornerShape(24.dp),
                    ),
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
                            .heightIn(min = 52.dp)
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
private fun DraftBannerCard(
    sourceUrl: String?,
    dark: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    val fill = if (dark) Color(0xFF1D3329) else Color(0xFFEAF7EF)
    val border = scheme.primary.copy(alpha = if (dark) 0.42f else 0.28f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .clip(shape)
            .background(fill, shape)
            .border(1.dp, border, shape)
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
) {
    val scheme = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val meta = remember(note.documentJson, note.title, note.searchText, note.updatedAt, note.createdAt) {
        deriveNoteCardMeta(note)
    }
    val shape = RoundedCornerShape(20.dp)
    val surfaceColor = if (dark) Color(0xFF182720) else Color(0xFFFAFDF9)
    val borderColor = if (note.pinned) {
        scheme.primary.copy(alpha = if (dark) 0.45f else 0.30f)
    } else {
        if (dark) Color(0xFF283E33) else Color(0xFFDCEADF)
    }
    val shadowTint = if (dark) Color.Transparent else Color(0x16126543)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(if (dark) 0.dp else 3.dp, shape, ambientColor = shadowTint, spotColor = shadowTint)
            .clip(shape)
            .background(surfaceColor, shape)
            .border(if (note.pinned) 1.1.dp else 0.9.dp, borderColor, shape)
            .testTag("saved-note")
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onOpen,
            )
            .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(
                    modifier = Modifier.weight(1f).padding(top = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (note.pinned) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(scheme.primary.copy(alpha = 0.12f))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
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
                            letterSpacing = (-0.1).sp,
                        ),
                        color = scheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (meta.preview.isNotBlank()) {
                        Text(
                            text = meta.preview,
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Box {
                    NoteMenuTrigger { menu = true }
                    ComposablesMenu(expanded = menu, onDismiss = { menu = false }) {
                        ComposablesMenuItem("整理笔记", icon = MintIcons.Folder) {
                            menu = false
                            onOrganize()
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
                    }
                }
            }

            if (meta.media.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(end = 8.dp),
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

            val hasMetadataRow = meta.relativeTime.isNotBlank() ||
                notebookName != null ||
                tagLabels.isNotEmpty() ||
                meta.totalTodos > 0 ||
                meta.mediaTypes.isNotEmpty() ||
                dueBadge != null
            if (hasMetadataRow) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (meta.relativeTime.isNotBlank()) {
                        MetadataPill(
                            text = meta.relativeTime,
                            icon = null,
                            accent = false,
                            dark = dark,
                        )
                    }
                    if (meta.totalTodos > 0) {
                        val allDone = meta.doneTodos == meta.totalTodos
                        MetadataPill(
                            text = "${meta.doneTodos}/${meta.totalTodos}",
                            icon = MintIcons.Checklist,
                            accent = !allDone,
                            dark = dark,
                        )
                    }
                    if (dueBadge != null) {
                        MetadataPill(
                            text = dueBadge,
                            icon = MintIcons.Bell,
                            accent = true,
                            dark = dark,
                        )
                    }
                    if (!notebookName.isNullOrBlank()) {
                        MetadataPill(
                            text = notebookName,
                            icon = MintIcons.Folder,
                            accent = false,
                            dark = dark,
                        )
                    }
                    tagLabels.take(2).forEach { tag ->
                        MetadataPill(
                            text = "#$tag",
                            icon = null,
                            accent = false,
                            dark = dark,
                        )
                    }
                    if (tagLabels.size > 2) {
                        MetadataPill(
                            text = "+${tagLabels.size - 2}",
                            icon = null,
                            accent = false,
                            dark = dark,
                        )
                    }
                    meta.mediaTypes.take(2).forEach { type ->
                        val mediaIcon = when (type) {
                            "audio" -> MintIcons.Audio
                            "video" -> MintIcons.Video
                            else -> MintIcons.Image
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (dark) Color(0xFF21362C) else Color(0xFFEEF6F1))
                                .padding(horizontal = 7.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = mediaIcon,
                                contentDescription = null,
                                tint = scheme.onSurfaceVariant,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetadataPill(
    text: String,
    icon: ImageVector?,
    accent: Boolean,
    dark: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val bg = when {
        accent && dark -> Color(0xFF214A39)
        accent -> Color(0xFFDDF3E7)
        dark -> Color(0xFF203329)
        else -> Color(0xFFEEF5F0)
    }
    val fg = when {
        accent && dark -> Color(0xFF95E5B9)
        accent -> Color(0xFF126543)
        else -> scheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(12.dp),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (accent) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = fg,
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
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (dark) Color(0xFF182720) else Color(0xFFFAFDF9))
            .border(0.9.dp, if (dark) Color(0xFF283E33) else Color(0xFFDCEADF), shape)
            .padding(20.dp),
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

@Composable
private fun ExplorePreviewSection(
    notes: List<NoteEntity>,
    tagsCount: Int,
    notebooksCount: Int,
    linksCount: Int,
    openTodosCount: Int,
    dark: Boolean,
    onOpenTodos: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val activeNotes = notes.count { it.archivedAt == null }
    val recentWeek = remember(notes) {
        val cutoff = Instant.now().minus(7, ChronoUnit.DAYS)
        notes.count { note ->
            note.archivedAt == null &&
                runCatching { Instant.parse(note.updatedAt).isAfter(cutoff) }.getOrDefault(false)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PreviewFeatureCard(
            icon = MintIcons.Calendar,
            title = "近期创作脉络",
            badge = "近 7 天 $recentWeek 条",
            description = "当前共有 $activeNotes 条活跃笔记、$notebooksCount 个笔记本、$tagsCount 个标签，支持按「去年今日」与标签组合回看。",
            dark = dark,
            actionLabel = "前往笔记库按时间检索",
            onAction = onOpenLibrary,
        )
        PreviewFeatureCard(
            icon = MintIcons.Checklist,
            title = "待办与灵感交汇",
            badge = if (openTodosCount > 0) "$openTodosCount 项待完成" else "已全部完成",
            description = "笔记中的勾选框会自动聚合到待办视图，点开即可直接回到原笔记上下文继续补充。",
            dark = dark,
            actionLabel = "筛选含待办笔记",
            onAction = onOpenTodos,
        )
        PreviewFeatureCard(
            icon = MintIcons.Link,
            title = "双向引用与知识图谱",
            badge = "$linksCount 条引用关联",
            description = "支持在正文中通过 @ 引用其他笔记，编辑器内自动维护反向链接。",
            dark = dark,
        )
    }
}

@Composable
private fun AgentPreviewSection(
    notesCount: Int,
    dark: Boolean,
    onOpenLibrary: () -> Unit,
    onQuickCapture: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PreviewFeatureCard(
            icon = MintIcons.Agent,
            title = "本地优先 · 可确认整理",
            badge = "隐私受控",
            description = "默认遵守每条笔记的 AI 策略，不会在后台静默上传内容；所有归纳与待办提取均需你确认后写入。",
            dark = dark,
            actionLabel = "立即记一条新想法",
            onAction = onQuickCapture,
        )
        PreviewFeatureCard(
            icon = MintIcons.Explore,
            title = "每日回顾与上下文串联",
            badge = "即将上线 · 已有 $notesCount 条本地素材",
            description = "基于本机笔记库、时间线索和双向引用，协助梳理周报要点、未完成待办与跨笔记主题。",
            dark = dark,
            actionLabel = "浏览现有笔记库",
            onAction = onOpenLibrary,
        )
    }
}

@Composable
private fun PreviewFeatureCard(
    icon: ImageVector,
    title: String,
    badge: String,
    description: String,
    dark: Boolean,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (dark) Color(0xFF182720) else Color(0xFFFAFDF9))
            .border(0.9.dp, if (dark) Color(0xFF283E33) else Color(0xFFDCEADF), shape)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(scheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = scheme.onSurface,
                )
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                    color = scheme.primary,
                )
            }
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
        if (actionLabel != null && onAction != null) {
            MintPillButton(
                label = actionLabel,
                dark = dark,
                onClick = onAction,
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

    MintGlassDialog(
        onDismissRequest = onDismiss,
        dark = dark,
        title = "待办清单",
        subtitle = "${pending.size} 项未完成 · ${done.size} 项已完成",
        actions = {
            MintPillButton(
                label = "关闭",
                primary = true,
                dark = dark,
                onClick = onDismiss,
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 380.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (items.isEmpty()) {
                Text(
                    text = "当前笔记中还没有待办事项，在任意笔记内点「添加待办」即可创建。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            } else {
                (pending + done).forEach { todo ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (dark) Color(0xFF1A2A22) else Color(0xFFF3F8F4))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Button,
                            ) { onOpenNote(todo.noteId) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
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
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
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
