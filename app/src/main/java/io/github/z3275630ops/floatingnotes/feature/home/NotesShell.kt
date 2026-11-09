package io.github.z3275630ops.floatingnotes.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.z3275630ops.floatingnotes.DebugSampleAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.style.TextOverflow
import io.github.z3275630ops.floatingnotes.data.local.NoteEntity
import io.github.z3275630ops.floatingnotes.data.local.tagSubtree
import io.github.z3275630ops.floatingnotes.feature.capture.NotesViewModel
import io.github.z3275630ops.floatingnotes.feature.capture.NoteEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import io.github.z3275630ops.floatingnotes.feature.capture.CaptureMotion
import io.github.z3275630ops.floatingnotes.feature.capture.CaptureSheet
import io.github.z3275630ops.floatingnotes.ui.designsystem.*

private enum class Page(val label: String) {
    Record("记录"), Explore("探索"), Agent("Agent"), Library("笔记库"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotesShell(model: NotesViewModel, labs: @Composable RowScope.() -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = MaterialTheme.colorScheme
    val backdrop = rememberGlassBackdrop()
    var page by rememberSaveable { mutableStateOf(Page.Record) }
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
    val tagScope = selectedTag?.let { if (descendants) tagSubtree(tags, it) else setOf(it) }
    val tagMatches = noteTags.filter { tagScope != null && it.tagId in tagScope }.mapTo(hashSetOf()) { it.noteId }
    val notebookMatches = notebookNotes.filter { it.notebookId == selectedNotebook }.mapTo(hashSetOf()) { it.noteId }
    val scope = rememberCoroutineScope()
    val motion = remember { CaptureMotion(scope, open) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    var samples by remember { mutableStateOf(emptyList<String>()) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current

    fun launchEditor(noteId: String? = null, captureSlot: String? = null) {
        scope.launch(Dispatchers.Main.immediate) {
            if (model.openNote(noteId, captureSlot)) {
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
        NoteOrganizationDialog(id, tags, notebooks,
            noteTags.filter { it.noteId == id }.mapTo(hashSetOf()) { it.tagId },
            notebookNotes.firstOrNull { it.noteId == id }?.notebookId, organization) { organizeNote = null }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = !motion.visible,
        scrimColor = Color(0xFF0D2118).copy(alpha = if (dark) .50f else .28f),
        drawerContent = {
            NotesDrawer(
                backdrop, dark,
                total = notes.count { it.archivedAt == null },
                archived = notes.count { it.archivedAt != null },
                archiveSelected = page == Page.Record && showArchive,
                allSelected = page == Page.Record && !showArchive,
                tags = tags, notebooks = notebooks,
                filter = if (page == Page.Library) LibraryFilter(selectedTag, selectedNotebook, descendants) else LibraryFilter(),
                onRecord = { archive ->
                    showArchive = archive
                    page = Page.Record
                    scope.launch { drawer.close() }
                },
                onLibrary = { filter ->
                    selectedTag = filter.tag
                    selectedNotebook = filter.notebook
                    descendants = filter.descendants
                    page = Page.Library
                    scope.launch { drawer.close() }
                },
            )
        },
    ) {
        Box(
            Modifier.fillMaxSize().background(scheme.background)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .navigationBarsPadding(),
        ) {
            Box(
                Modifier.fillMaxSize().glassSource(backdrop).background(mintAmbient(dark))
                    .then(if (motion.visible || drawer.isOpen) Modifier.clearAndSetSemantics { } else Modifier),
            ) {
                Crossfade(page, label = "home-page") { current ->
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 150.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedIconButton(
                                    onClick = { scope.launch { drawer.open() } },
                                    modifier = Modifier.testTag("open-notes-drawer")
                                        .semantics { contentDescription = "打开笔记导航" },
                                ) { Text("☰", style = MaterialTheme.typography.titleMedium) }
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    if (current == Page.Record) if (showArchive) "归档" else "浮记" else current.label,
                                    style = MaterialTheme.typography.headlineLarge,
                                )
                            }
                        }
                        if (current == Page.Record) {
                            item {
                                Text(
                                    if (notes.isEmpty()) "记下一件今天想留住的事。" else "留住今天的想法。",
                                    color = scheme.onSurfaceVariant,
                                )
                                Row(content = labs)
                                model.error?.let { Text(it, color = scheme.error) }
                                DebugSampleAction { samples = it }
                            }
                            if (!showArchive) items(captures, key = { it.id }) { draft ->
                                TextButton(onClick = { launchEditor(captureSlot = draft.slot) }) {
                                    Text("继续收集草稿" + (draft.sourceUrl?.let { " · $it" } ?: ""),
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            items(notes.filter { (it.archivedAt != null) == showArchive }, key = { it.id }) { note ->
                                NoteCard(note, { launchEditor(note.id) }, { model.pin(note) }, { model.archive(note) }) { organizeNote = note.id }
                            }
                            items(samples) { text ->
                                Card(
                                    Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(20.dp),
                                    colors = CardDefaults.cardColors(containerColor = scheme.surface),
                                ) {
                                    Text(text, Modifier.padding(20.dp), style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        } else if (current == Page.Library) {
                            item {
                                OrganizationPanel(organization, tags, notebooks,
                                    LibraryFilter(selectedTag, selectedNotebook, descendants)) {
                                    selectedTag = it.tag
                                    selectedNotebook = it.notebook
                                    descendants = it.descendants
                                }
                            }
                            val visible = notes.filter {
                                it.archivedAt == null && (selectedTag == null || it.id in tagMatches) &&
                                    (selectedNotebook == null || it.id in notebookMatches)
                            }
                            if (visible.isEmpty()) item { Text("此范围没有笔记") }
                            items(visible, key = { it.id }) { note ->
                                NoteCard(note, { launchEditor(note.id) }, { model.pin(note) }, { model.archive(note) }) { organizeNote = note.id }
                            }
                        } else {
                            item { Text("页面原型，功能开发中。", color = scheme.onSurfaceVariant) }
                        }
                    }
                }
            }

            Box(Modifier.matchParentSize().imePadding()) {
                if (!motion.visible && !drawer.isOpen) {
                    Column(
                        Modifier.align(Alignment.BottomCenter).widthIn(max = 640.dp)
                            .fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ExtendedFloatingActionButton(
                            onClick = { launchEditor() },
                            modifier = Modifier.align(Alignment.End).testTag("new-note"),
                            containerColor = scheme.primaryContainer,
                            contentColor = scheme.onPrimaryContainer,
                        ) { Text("＋ 快速记录", style = MaterialTheme.typography.labelLarge) }
                        GlassSurface(backdrop, GlassTier.Enhanced, dark, Modifier.fillMaxWidth()) {
                            BoxWithConstraints(Modifier.fillMaxWidth().padding(4.dp)) {
                                val tabWidth = maxWidth / Page.entries.size
                                val position by animateDpAsState(
                                    tabWidth * page.ordinal,
                                    spring(dampingRatio = .85f, stiffness = 500f),
                                    label = "tab-selection",
                                )
                                Box(
                                    Modifier.offset { IntOffset(position.roundToPx(), 0) }.width(tabWidth).height(48.dp)
                                        .background(mintHighlight(dark), RoundedCornerShape(24.dp)),
                                )
                                Row(Modifier.fillMaxWidth().selectableGroup()) {
                                    for (target in Page.entries) {
                                        Box(
                                            Modifier.weight(1f).heightIn(min = 48.dp)
                                                .testTag("tab-${target.name}")
                                                .selectable(page == target, role = Role.Tab) { page = target }
                                                .padding(vertical = 8.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                target.label,
                                                color = if (page == target) scheme.primary else scheme.onSurfaceVariant,
                                                style = MaterialTheme.typography.labelLarge,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                CaptureSheet(motion, open, backdrop, dark, { close() }, { close(it) }, { open = true }) {
                    val session = model.editor
                    if (session != null) {
                        val sources = links.filter { it.targetId == session.draft.noteId }.mapTo(hashSetOf()) { it.sourceId }
                        key(session.draft.id) {
                            NoteEditor(session, { close(save = true) }, { close(discard = true) },
                                notes, notes.filter { it.id in sources }) { id ->
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
                        CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteCard(note: NoteEntity, onOpen: () -> Unit, onPin: () -> Unit, onArchive: () -> Unit, onOrganize: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().testTag("saved-note"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                if (note.pinned) Text("已置顶", style = MaterialTheme.typography.labelMedium)
                Text(note.searchText.ifBlank { "笔记" }, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge)
            }
            Box {
                NoteMenuTrigger { menu = true }
                ComposablesMenu(expanded = menu, onDismiss = { menu = false }) {
                    ComposablesMenuItem("整理笔记") { menu = false; onOrganize() }
                    ComposablesMenuItem(if (note.pinned) "取消置顶" else "置顶") { menu = false; onPin() }
                    ComposablesMenuItem(if (note.archivedAt == null) "归档" else "取消归档") {
                        menu = false
                        onArchive()
                    }
                }
            }
        }
    }
}
