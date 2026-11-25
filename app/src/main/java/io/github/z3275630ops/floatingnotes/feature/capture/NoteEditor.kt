package io.github.z3275630ops.floatingnotes.feature.capture

import android.Manifest
import android.content.ActivityNotFoundException
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.PinnableContainer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.z3275630ops.floatingnotes.core.model.NoteBlock
import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import io.github.z3275630ops.floatingnotes.core.model.NoteMarkType
import io.github.z3275630ops.floatingnotes.core.model.NoteTextMark
import io.github.z3275630ops.floatingnotes.core.model.adjustMarksForTextEdit
import io.github.z3275630ops.floatingnotes.core.model.clampUtf16Range
import io.github.z3275630ops.floatingnotes.core.model.normalizeMarks
import io.github.z3275630ops.floatingnotes.core.model.safeUtf16Slice
import io.github.z3275630ops.floatingnotes.data.discovery.EvidenceHit
import io.github.z3275630ops.floatingnotes.data.discovery.LocalFuzzyMatcher
import io.github.z3275630ops.floatingnotes.data.discovery.TagRecommendation
import io.github.z3275630ops.floatingnotes.data.local.NoteEntity
import io.github.z3275630ops.floatingnotes.data.local.NotebookEntity
import io.github.z3275630ops.floatingnotes.data.local.RecurrenceRule
import io.github.z3275630ops.floatingnotes.data.local.ReminderStatus
import io.github.z3275630ops.floatingnotes.data.local.TagEntity
import io.github.z3275630ops.floatingnotes.feature.explore.EdgeEchoPreviewSheet
import io.github.z3275630ops.floatingnotes.feature.explore.EditorEdgeEchoStrip
import io.github.z3275630ops.floatingnotes.feature.explore.EditorRelatedNotesSheet
import io.github.z3275630ops.floatingnotes.feature.tasks.ReminderScheduler
import io.github.z3275630ops.floatingnotes.feature.tasks.TaskRecurrence
import io.github.z3275630ops.floatingnotes.feature.tasks.TaskScheduleModal
import io.github.z3275630ops.floatingnotes.feature.tasks.TasksScheduleDraft
import io.github.z3275630ops.floatingnotes.ui.designsystem.ComposablesMenu
import io.github.z3275630ops.floatingnotes.ui.designsystem.ComposablesMenuItem
import io.github.z3275630ops.floatingnotes.ui.designsystem.ComposablesSearchField
import io.github.z3275630ops.floatingnotes.ui.designsystem.GlassBackdrop
import io.github.z3275630ops.floatingnotes.ui.designsystem.GlassTier
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintAlertDialog
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintBottomSheet
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintCheckbox
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintChip
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintChipStyle
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGroupedDivider
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGroupedRow
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGroupedSection
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintIcons
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintPillButton
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintAiAccent
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintCardSurface
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintDividerColor
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintElevatedSurface
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintLinkAccent
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintSecondaryFill
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintTertiaryTextColor
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintTodoAccent
import io.github.z3275630ops.floatingnotes.ui.designsystem.rememberGlassBackdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class InlineTriggerKind { TAG, REFERENCE }

internal data class InlineTrigger(
    val kind: InlineTriggerKind,
    val triggerIndex: Int,
    val cursorIndex: Int,
    val query: String,
)

private data class PendingTriggerReplace(
    val blockId: String,
    val start: Int,
    val expectedText: String,
    val toggleTitle: Boolean,
)

internal fun detectInlineTrigger(
    text: String,
    selection: TextRange,
    composition: TextRange?,
): InlineTrigger? {
    if (composition != null || !selection.collapsed) return null
    val cursor = selection.start.coerceIn(0, text.length)
    var index = cursor - 1
    while (index >= 0) {
        val ch = text[index]
        if (ch.isWhitespace()) return null
        if (ch == '#' || ch == '@') {
            if (index == 0 || text[index - 1].isWhitespace()) {
                return InlineTrigger(
                    kind = if (ch == '#') InlineTriggerKind.TAG else InlineTriggerKind.REFERENCE,
                    triggerIndex = index,
                    cursorIndex = cursor,
                    query = text.substring(index + 1, cursor),
                )
            }
            return null
        }
        index--
    }
    return null
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Suppress("UNUSED_PARAMETER", "DEPRECATION")
@Composable
internal fun ColumnScope.NoteEditor(
    session: EditorSession,
    model: NotesViewModel,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    notes: List<NoteEntity> = emptyList(),
    backlinks: List<NoteEntity> = emptyList(),
    activeNote: NoteEntity? = null,
    tags: List<TagEntity> = emptyList(),
    notebooks: List<NotebookEntity> = emptyList(),
    noteTagIds: Set<String> = emptySet(),
    noteNotebookId: String? = null,
    backdrop: GlassBackdrop? = null,
    tier: GlassTier = GlassTier.Enhanced.supportedOn(Build.VERSION.SDK_INT),
    dark: Boolean = isSystemInDarkTheme(),
    autoFocusKeyboard: Boolean = false,
    onOpenOrganize: (() -> Unit)? = null,
    onRequestNotificationPermission: (() -> Unit)? = null,
    onOpenRelated: ((String) -> Unit)? = null,
    onStartWalkFromNote: ((String) -> Unit)? = null,
    trashedNoteIds: Set<String> = emptySet(),
    trashedNotes: List<NoteEntity> = emptyList(),
    enableSlashShortcuts: Boolean = true,
    isAiExcluded: Boolean = activeNote?.aiPolicy == "exclude",
    onToggleAiExcluded: (() -> Unit)? = null,
    undoHandle: EditorUndoHandle? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val localBackdrop = backdrop ?: rememberGlassBackdrop()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val engine = rememberEditorEngineState(session)
    val listState = rememberLazyListState()
    val blockIds by remember(session) {
        derivedStateOf(structuralEqualityPolicy()) { session.blocks.map { it.id } }
    }

    val liveTags by model.organization.tags.collectAsStateWithLifecycle(initialValue = emptyList())
    val availableTags = if (tags.isNotEmpty()) tags else liveTags
    val noteTaskItems by model.taskItems.collectAsStateWithLifecycle()
    val discoveryState by model.editorDiscoveryState.collectAsStateWithLifecycle()
    val agentUiState by model.agentState.collectAsStateWithLifecycle()
    val voiceCleanState by model.voiceCleanState.collectAsStateWithLifecycle()

    var capability by remember { mutableStateOf(ReminderScheduler.capability(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                capability = ReminderScheduler.capability(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val fallbackNotificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        capability = ReminderScheduler.capability(context)
    }

    val requestNotifications: () -> Unit = {
        if (onRequestNotificationPermission != null) {
            onRequestNotificationPermission()
        } else if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !capability.notificationsEnabled &&
            !model.taskPreferences.notificationPromptAttempted
        ) {
            model.taskPreferences.notificationPromptAttempted = true
            fallbackNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            val intent = ReminderScheduler.openNotificationSettingsIntent(context)
            runCatching { context.startActivity(intent) }.onFailure {
                runCatching { context.startActivity(ReminderScheduler.openAppDetailsIntent(context)) }
            }
        }
        capability = ReminderScheduler.capability(context)
    }

    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) model.startRecording(session)
        else session.voiceStatus = "麦克风权限未开启，可在系统设置授权后重试"
    }

    var picker by remember { mutableStateOf<String?>(null) }
    var tagPickerOpen by remember { mutableStateOf(false) }
    var scheduleBlockId by remember { mutableStateOf<String?>(null) }
    var focusedBlockId by remember(session.draft.id) { mutableStateOf<String?>(null) }
    var focusedIsToggleTitle by remember(session.draft.id) { mutableStateOf(false) }
    var selectedControlBlockId by remember(session.draft.id) { mutableStateOf<String?>(null) }
    var initialFocusCanceled by remember(session.draft.id) { mutableStateOf(false) }
    var excerptTarget by remember { mutableStateOf<NoteEntity?>(null) }
    var pendingTriggerReplace by remember { mutableStateOf<PendingTriggerReplace?>(null) }
    var showRelatedSheet by remember { mutableStateOf(false) }
    var echoPreviewHit by remember { mutableStateOf<EvidenceHit?>(null) }
    var formatPanelOpen by remember(session.draft.id) { mutableStateOf(false) }
    var batchFormatSheetOpen by remember { mutableStateOf(false) }
    var moreInsertMenuOpen by remember { mutableStateOf(false) }
    var openBlockMenuId by remember { mutableStateOf<String?>(null) }
    var isSelectionMode by remember(session.draft.id) { mutableStateOf(false) }
    var selectedBlockIds by remember(session.draft.id) { mutableStateOf<Set<String>>(emptySet()) }

    BackHandler(enabled = openBlockMenuId != null || moreInsertMenuOpen) {
        if (openBlockMenuId != null) openBlockMenuId = null else moreInsertMenuOpen = false
    }
    LaunchedEffect(blockIds, openBlockMenuId) {
        if (openBlockMenuId != null && openBlockMenuId !in blockIds) openBlockMenuId = null
    }

    fun clearEditorFocus() {
        initialFocusCanceled = true
        engine.pendingFocus = null
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }

    fun onEditorFieldFocus(blockId: String, title: Boolean, focused: Boolean) {
        if (focused) {
            if (focusedBlockId != blockId || focusedIsToggleTitle != title) {
                engine.pendingTypingMarks = emptySet()
                engine.dismissedSlashAt = null
            }
            focusedBlockId = blockId
            focusedIsToggleTitle = title
            selectedControlBlockId = blockId
        } else if (focusedBlockId == blockId && focusedIsToggleTitle == title) {
            focusedBlockId = null
            focusedIsToggleTitle = false
            engine.pendingTypingMarks = emptySet()
        }
    }

    fun enterSelectionMode(initialBlockId: String) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        clearEditorFocus()
        formatPanelOpen = false
        moreInsertMenuOpen = false
        openBlockMenuId = null
        isSelectionMode = true
        selectedBlockIds = setOf(initialBlockId)
    }

    fun exitSelectionMode() {
        isSelectionMode = false
        selectedBlockIds = emptySet()
        batchFormatSheetOpen = false
    }

    val performUndoAction: () -> Unit = {
        if (!session.closing) engine.performUndo(focusedBlockId, focusedIsToggleTitle)
    }
    val performRedoAction: () -> Unit = {
        if (!session.closing) engine.performRedo(focusedBlockId, focusedIsToggleTitle)
    }
    SideEffect {
        undoHandle?.bind(
            canUndo = engine.canUndo && !session.closing,
            canRedo = engine.canRedo && !session.closing,
            onUndo = performUndoAction,
            onRedo = performRedoAction,
        )
    }
    DisposableEffect(undoHandle) {
        onDispose { undoHandle?.unbind() }
    }

    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        model.importAttachments(session, it)
    }
    var showImport by remember { mutableStateOf(false) }
    var space by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(showImport) {
        if (showImport) space = model.attachments.availableSpace()
    }

    LaunchedEffect(engine.transientHint) {
        if (engine.transientHint != null) {
            delay(1_800)
            engine.transientHint = null
        }
    }

    val liveCharCount = session.blocks.sumOf { block ->
        val doc = block.documentBlock()
        if (doc.type == "toggle") doc.title.length + doc.text.length else block.text.text.length
    }

    if (session.existingNote) {
        EditorHeaderMetadata(
            activeNote = activeNote,
            notebook = notebooks.firstOrNull { it.id == noteNotebookId },
            charCount = liveCharCount,
            availableTags = availableTags,
            noteTagIds = noteTagIds,
            pendingTagIds = session.pendingTagIds,
            backlinksCount = backlinks.size,
            dark = dark,
            isAiExcluded = isAiExcluded,
            onToggleAiExcluded = onToggleAiExcluded,
            showInlineUndoRedo = undoHandle == null,
            canUndo = engine.canUndo && !session.closing,
            canRedo = engine.canRedo && !session.closing,
            onUndo = performUndoAction,
            onRedo = performRedoAction,
            onOpenOrganize = onOpenOrganize,
            onOpenTagPicker = { tagPickerOpen = true },
            onRemovePendingTag = session::removePendingTag,
            onOpenBacklinks = { picker = "引用此笔记的记录" },
        )
    } else {
        if (undoHandle == null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "$liveCharCount 字",
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.5.sp),
                    color = mintTertiaryTextColor(dark),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                InlineUndoRedoButtons(
                    canUndo = engine.canUndo && !session.closing,
                    canRedo = engine.canRedo && !session.closing,
                    onUndo = performUndoAction,
                    onRedo = performRedoAction,
                )
            }
        }
        if (session.pendingTagIds.isNotEmpty() || noteTagIds.isNotEmpty() || backlinks.isNotEmpty() || isAiExcluded) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (isAiExcluded) {
                    MintChip(
                        label = "已排除 AI 整理",
                        selected = true,
                        leadingIcon = MintIcons.Lock,
                        style = MintChipStyle.Standard,
                        dark = dark,
                        onClick = { onToggleAiExcluded?.invoke() },
                    )
                }
                for (tagId in (noteTagIds + session.pendingTagIds)) {
                    val tag = availableTags.firstOrNull { it.id == tagId } ?: continue
                    MintChip(
                        label = "#${tag.name}",
                        selected = true,
                        style = MintChipStyle.Selected,
                        dark = dark,
                        removable = tagId in session.pendingTagIds,
                        onClick = {
                            if (tagId in session.pendingTagIds) session.removePendingTag(tagId)
                            else tagPickerOpen = true
                        },
                    )
                }
                if (backlinks.isNotEmpty()) {
                    TextButton(
                        onClick = { picker = "引用此笔记的记录" },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text("反链 ${backlinks.size}")
                    }
                }
            }
        }
    }

    AnimatedVisibility(visible = isSelectionMode) {
        BlockSelectionTopBanner(
            selectedCount = selectedBlockIds.size,
            dark = dark,
            onDone = { exitSelectionMode() },
        )
    }

    if (session.recording) {
        val seconds = session.recordingMillis / 1000
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(scheme.errorContainer.copy(alpha = if (dark) 0.35f else 0.7f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "录音 · ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = scheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { model.toggleRecordingPause(session) }) {
                Text(if (session.recordingPaused) "继续" else "暂停")
            }
            TextButton(onClick = { model.stopRecording(session) }) { Text("停止并保存") }
        }
    }
    session.voiceStatus?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
    if (session.voicePending && !session.voiceBusy) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { model.retryRecording(session) }) { Text("重试保存") }
            TextButton(onClick = { model.discardPendingRecording(session) }) { Text("移除待存录音") }
        }
    }

    val focusBlockRequesters = remember(session.draft.id) { mutableMapOf<String, BringIntoViewRequester>() }
    val blockFocusRequesters = remember(session.draft.id) { mutableMapOf<String, FocusRequester>() }
    val titleFocusRequesters = remember(session.draft.id) { mutableMapOf<String, FocusRequester>() }

    fun focusEditableBlock(
        targetId: String,
        moveToEnd: Boolean = false,
        cursorOffset: Int? = null,
        toggleTitle: Boolean = false,
        showKeyboard: Boolean = false,
    ) {
        val targetBlock = session.blocks.firstOrNull { it.id == targetId && it.editable } ?: return
        val docBlock = targetBlock.documentBlock()
        val effectiveTitle = toggleTitle && docBlock.type == "toggle"
        val targetText = if (effectiveTitle) docBlock.title else docBlock.text
        val resolvedCursor = when {
            cursorOffset != null -> cursorOffset.coerceIn(0, targetText.length)
            moveToEnd -> targetText.length
            else -> 0
        }
        if (effectiveTitle) {
            val state = engine.titleTfvStateFor(targetBlock)
            state.value = TextFieldValue(targetText, TextRange(resolvedCursor))
        } else {
            val state = engine.tfvStateFor(targetBlock)
            state.value = TextFieldValue(targetText, TextRange(resolvedCursor))
            targetBlock.text.edit {
                selection = TextRange(resolvedCursor.coerceIn(0, length))
            }
        }
        engine.requestFocus(targetBlock.id, effectiveTitle, showKeyboard = showKeyboard)
    }

    LaunchedEffect(session.focusBlockId) {
        val targetId = session.focusBlockId ?: return@LaunchedEffect
        val targetBlock = session.blocks.firstOrNull { it.id == targetId }
        if (targetBlock == null) {
            session.focusBlockId = null
            return@LaunchedEffect
        }
        if (targetBlock.editable) {
            val doc = targetBlock.documentBlock()
            val focusTitle = doc.type == "toggle" && doc.collapsed
            val textLen = if (focusTitle) doc.title.length else doc.text.length
            focusEditableBlock(targetId, cursorOffset = textLen, toggleTitle = focusTitle)
        } else {
            val idx = session.blocks.indexOf(targetBlock)
            if (idx >= 0) runCatching { listState.scrollToItem(idx) }
        }
        session.focusBlockId = null
    }

    LaunchedEffect(engine.pendingFocus?.token) {
        val req = engine.pendingFocus ?: return@LaunchedEffect
        try {
            if (session.closing || isSelectionMode) return@LaunchedEffect
            val targetIndex = session.blocks.indexOfFirst { it.id == req.blockId && it.editable }
            if (targetIndex < 0) return@LaunchedEffect
            val attached = if (req.toggleTitle) engine.attachedTitleFocusRequesters else engine.attachedFocusRequesters
            val requesters = if (req.toggleTitle) titleFocusRequesters else blockFocusRequesters

            // Let branch changes detach the previous field before checking its actual focus.
            withFrameNanos { }
            if (req.blockId in attached && focusedBlockId == req.blockId && focusedIsToggleTitle == req.toggleTitle) {
                if (req.showKeyboard) keyboard?.show()
                return@LaunchedEffect
            }
            if (req.blockId !in attached) listState.scrollToItem(targetIndex)
            repeat(14) {
                if (engine.pendingFocus?.token != req.token || session.closing) return@LaunchedEffect
                if (req.blockId in attached && requesters[req.blockId]?.requestFocus() == true) {
                    keyboard?.show()
                    focusBlockRequesters[req.blockId]?.bringIntoView()
                    return@LaunchedEffect
                }
                withFrameNanos { }
            }
        } finally {
            if (engine.pendingFocus?.token == req.token) engine.pendingFocus = null
        }
    }

    LaunchedEffect(session.draft.id, autoFocusKeyboard) {
        if (autoFocusKeyboard && !session.existingNote && !session.closing) {
            val firstEditable = session.blocks.firstOrNull { it.editable } ?: return@LaunchedEffect
            withFrameNanos { }
            delay(90)
            if (!initialFocusCanceled && focusedBlockId == null && selectedControlBlockId == null && !isSelectionMode && !session.closing) {
                focusEditableBlock(firstEditable.id, moveToEnd = false)
            }
        }
    }

    val edgeThresholdPx = with(density) { 64.dp.toPx() }
    val maxScrollStepPx = with(density) { 12.dp.toPx() }
    LaunchedEffect(engine.dragController.isDragging) {
        if (engine.dragController.isDragging) {
            autoScrollDuringBlockDrag(
                dragController = engine.dragController,
                listState = listState,
                blockIds = { blockIds },
                edgeThresholdPx = edgeThresholdPx,
                maxStepPx = maxScrollStepPx,
            )
        }
    }

    val docBlocks = session.blocks.map { it.documentBlock() }
    val listNumbers = remember(docBlocks) { EditorBlockOps.computeListNumbers(docBlocks) }

    var editorBoxHeightPx by remember { mutableIntStateOf(0) }
    var editorBoxTopInRootPx by remember { mutableFloatStateOf(0f) }
    var cursorAnchorBottomPx by remember { mutableFloatStateOf(Float.NaN) }
    var cursorAnchorTopPx by remember { mutableFloatStateOf(Float.NaN) }

    fun updateCursorAnchor(blockId: String, fieldCoords: LayoutCoordinates, layout: TextLayoutResult, cursorIdx: Int) {
        if (focusedBlockId == blockId) {
            val rect = layout.getCursorRect(cursorIdx.coerceIn(0, layout.layoutInput.text.length))
            val rootTop = fieldCoords.localToRoot(Offset.Zero).y
            cursorAnchorTopPx = rootTop + rect.top - editorBoxTopInRootPx
            cursorAnchorBottomPx = rootTop + rect.bottom - editorBoxTopInRootPx
        }
    }

    var dragBefore by remember(engine) { mutableStateOf<EditorSnapshot?>(null) }

    fun buildDragHandlers(blockId: String, sourceTopInRoot: () -> Float?): BlockDragHandlers = BlockDragHandlers(
        blockId = blockId,
        enabled = !session.closing,
        onStartDrag = { rootY ->
            val index = blockIds.indexOf(blockId)
            val sourceTop = sourceTopInRoot()
            if (sourceTop != null && index >= 0 && !session.closing && !engine.dragController.isDragging) {
                dragBefore = engine.snapshot(focusedBlockId, focusedIsToggleTitle)
                engine.dragController.start(blockId, index, rootY, sourceTop)
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                clearEditorFocus()
            }
        },
        onDragDelta = { rootY ->
            if (engine.dragController.draggingBlockId == blockId) {
                engine.dragController.updatePointer(rootY)
                engine.dragController.recomputeDropIndex(listState, blockIds)
            }
        },
        onDragFinish = {
            if (engine.dragController.draggingBlockId == blockId) {
                engine.dragController.recomputeDropIndex(listState, blockIds)
                val move = engine.dragController.finish()
                val before = dragBefore
                dragBefore = null
                if (move != null) {
                    val keepListStart = !listState.canScrollBackward && (move.first == 0 || move.second == 0)
                    val current = session.blocks.map { it.documentBlock() }
                    val moved = EditorBlockOps.moveBlock(current, move.first, move.second)
                    engine.applyOutcome(BlockEditOutcome(moved, null, 0), recordUndoBefore = before?.copy(blocks = current))
                    // Keep the first position visible when its key changes at the top of the note.
                    if (keepListStart) listState.requestScrollToItem(0)
                }
            }
        },
        onDragCancel = {
            if (engine.dragController.draggingBlockId == blockId) {
                engine.dragController.cancel()
                dragBefore = null
            }
        },
    )

    fun handleBackspaceAtStart(blockId: String, isToggleTitle: Boolean): Boolean {
        val idx = session.blocks.indexOfFirst { it.id == blockId }
        if (idx < 0) return false
        val currentBlocks = session.blocks.map { it.documentBlock() }
        val before = engine.snapshot(blockId, isToggleTitle)
        val outcome = EditorBlockOps.applyBackspaceAtStart(currentBlocks, idx, isToggleTitle) ?: return false
        engine.applyOutcome(outcome, recordUndoBefore = before)
        return true
    }

    fun handleEnterKey(blockId: String, isToggleTitle: Boolean): Boolean {
        val idx = session.blocks.indexOfFirst { it.id == blockId }
        if (idx < 0) return false
        val block = session.blocks[idx]
        val tfvState = if (isToggleTitle) engine.titleTfvStateFor(block) else engine.tfvStateFor(block)
        val tfv = tfvState.value
        if (tfv.composition != null) return false
        val start = tfv.selection.min.coerceIn(0, tfv.text.length)
        val end = tfv.selection.max.coerceIn(start, tfv.text.length)
        val beforeCursor = tfv.text.substring(0, start)
        val afterCursor = tfv.text.substring(end)
        val beforeSnapshot = engine.snapshot(blockId, isToggleTitle)
        val currentBlocks = session.blocks.map { it.documentBlock() }
        val outcome = EditorBlockOps.applyEnter(
            blocks = currentBlocks,
            blockIndex = idx,
            beforeCursor = beforeCursor,
            afterCursor = afterCursor,
            isToggleTitle = isToggleTitle,
        )
        engine.applyOutcome(outcome, recordUndoBefore = beforeSnapshot)
        return true
    }

    val highlightBg = if (dark) Color(0xFF4F4216) else Color(0xFFFFF0A3)
    val highlightFg = if (dark) Color(0xFFFFF1B8) else Color(0xFF2B2200)

    val goldDropIndicator = if (dark) Color(0xFFF2C14E) else Color(0xFFD99B16)
    val swipeTriggerPx = with(density) { 26.dp.toPx() }
    val swipeReleaseTriggerPx = with(density) { 18.dp.toPx() }
    val swipeMaxVisualPx = with(density) { 44.dp.toPx() }
    val swipeTriggerVisualPx = (swipeTriggerPx * 0.55f).coerceAtLeast(1f)
    val latestSelectionMode by rememberUpdatedState(isSelectionMode)
    val latestSessionClosing by rememberUpdatedState(session.closing)
    val latestBlockMenuOpen by rememberUpdatedState(openBlockMenuId != null)
    val latestEnterSelectionMode by rememberUpdatedState<(String) -> Unit>({ blockId -> enterSelectionMode(blockId) })
    val selectedCardBg = if (dark) Color(0xFF3B3118) else Color(0xFFFFF2C6)
    val selectedCardBorder = if (dark) Color(0xFFE5B84B).copy(alpha = 0.45f) else Color(0xFFE2B94B).copy(alpha = 0.7f)

    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .onSizeChanged { editorBoxHeightPx = it.height }
            .onGloballyPositioned { coords ->
                if (coords.isAttached) {
                    val topY = coords.localToRoot(Offset.Zero).y
                    editorBoxTopInRootPx = topY
                    engine.dragController.listTopInRootPx = topY
                }
            },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = 10.dp,
                bottom = 28.dp,
            ),
        ) {
            itemsIndexed(
                items = session.blocks,
                key = { _, item -> item.id },
            ) { index, block ->
                val doc = block.documentBlock()
                val bringIntoViewReq = remember(block.id) {
                    focusBlockRequesters.getOrPut(block.id) { BringIntoViewRequester() }
                }
                val blockFocusReq = remember(block.id) {
                    blockFocusRequesters.getOrPut(block.id) { FocusRequester() }
                }
                val titleFocusReq = remember(block.id) {
                    titleFocusRequesters.getOrPut(block.id) { FocusRequester() }
                }
                val isDraggingThis = engine.dragController.draggingBlockId == block.id
                val showDropBefore = engine.dragController.isDragging &&
                    !isDraggingThis &&
                    engine.dragController.targetDropIndex == index &&
                    engine.dragController.draggingIndex > index
                val showDropAfter = engine.dragController.isDragging &&
                    !isDraggingThis &&
                    engine.dragController.targetDropIndex == index &&
                    engine.dragController.draggingIndex < index
                val isBlockSelected = isSelectionMode && block.id in selectedBlockIds
                val swipeOffsetX = remember(block.id) { Animatable(0f) }
                val animatedRowBg by animateColorAsState(
                    targetValue = if (isBlockSelected) selectedCardBg else Color.Transparent,
                    label = "block-selection-bg",
                )

                val previousType = session.blocks.getOrNull(index - 1)?.type
                val blockSpacing = when {
                    previousType == null -> 0.dp
                    doc.type == "heading" -> 7.dp
                    previousType == "heading" -> 6.dp
                    doc.type in LIST_BLOCK_TYPES && previousType in LIST_BLOCK_TYPES -> 2.dp
                    else -> 3.dp
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(if (isDraggingThis) 10f else 0f)
                        .pointerInput(block.id) {
                            val touchSlop = viewConfiguration.touchSlop
                            val lockSlop = touchSlop
                            val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                            awaitEachGesture {
                                val down = awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = PointerEventPass.Initial,
                                )
                                if (latestSelectionMode || latestSessionClosing || latestBlockMenuOpen || engine.dragController.isDragging) {
                                    return@awaitEachGesture
                                }
                                val startX = down.position.x
                                val startY = down.position.y
                                var horizontalLocked = false
                                var selectionTriggered = false
                                var lastDx = 0f
                                try {
                                    while (true) {
                                        val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (event.changes.any { it.id != down.id && it.pressed } ||
                                            engine.dragController.isDragging
                                        ) break
                                        if (!change.pressed) {
                                            if (horizontalLocked) {
                                                change.consume()
                                                if (!selectionTriggered && lastDx >= swipeReleaseTriggerPx) {
                                                    selectionTriggered = true
                                                    latestEnterSelectionMode(block.id)
                                                }
                                            }
                                            break
                                        }
                                        val dx = change.position.x - startX
                                        val dy = change.position.y - startY
                                        lastDx = dx
                                        if (!horizontalLocked) {
                                            if (change.isConsumed || change.uptimeMillis - down.uptimeMillis >= longPressTimeout) break
                                            if (abs(dy) > touchSlop && dx <= abs(dy) * 1.15f) break
                                            if (dx < -touchSlop) break
                                            if (dx > lockSlop && dx > abs(dy) * 1.15f) {
                                                horizontalLocked = true
                                            } else {
                                                val afterChildren = awaitPointerEvent(PointerEventPass.Final)
                                                if (afterChildren.changes.any {
                                                        it.id == down.id && it.isConsumed && it.position != it.previousPosition
                                                    }
                                                ) break
                                            }
                                        }
                                        if (horizontalLocked) {
                                            change.consume()
                                            val targetVisual = (dx * 0.55f).coerceIn(0f, swipeMaxVisualPx)
                                            scope.launch { swipeOffsetX.snapTo(targetVisual) }
                                            if (!selectionTriggered && dx >= swipeTriggerPx) {
                                                selectionTriggered = true
                                                latestEnterSelectionMode(block.id)
                                            }
                                        }
                                    }
                                } finally {
                                    if (horizontalLocked || swipeOffsetX.value > 0f) {
                                        scope.launch {
                                            swipeOffsetX.animateTo(
                                                targetValue = 0f,
                                                animationSpec = spring(
                                                    dampingRatio = 0.78f,
                                                    stiffness = 520f,
                                                ),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        .padding(horizontal = 20.dp)
                        .bringIntoViewRequester(bringIntoViewReq)
                        .padding(top = blockSpacing),
                ) {
                    if (showDropBefore) {
                        Box(Modifier.height(0.dp).wrapContentHeight(unbounded = true)) {
                            DragInsertionIndicator(color = goldDropIndicator)
                        }
                    }

                    val isFocusedRow = selectedControlBlockId == block.id || isDraggingThis
                    val indentDp = (doc.indent.coerceIn(0, NoteBlock.MAX_INDENT) * 20).dp
                    val effectiveRowClosing = session.closing || isSelectionMode
                    var sourceCoordinates by remember(block.id) { mutableStateOf<LayoutCoordinates?>(null) }
                    val dragHandlers = buildDragHandlers(block.id) {
                        sourceCoordinates?.takeIf { it.isAttached }?.localToRoot(Offset.Zero)?.y?.takeIf { it.isFinite() }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { coordinates ->
                                // Measure outside the translated layer, including when LazyColumn places a pinned row offscreen.
                                sourceCoordinates = coordinates
                                if (engine.dragController.draggingBlockId == block.id) {
                                    engine.dragController.updateSourceTop(block.id, coordinates.localToRoot(Offset.Zero).y)
                                }
                            },
                    ) {
                        if (swipeOffsetX.value > 1f) {
                            val progress = (swipeOffsetX.value / swipeTriggerVisualPx).coerceIn(0f, 1f)
                            val reached = progress >= 1f
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .size(20.dp)
                                    .alpha(progress)
                                    .clip(CircleShape)
                                    .then(
                                        if (reached) {
                                            Modifier.background(goldDropIndicator, CircleShape)
                                        } else {
                                            Modifier.border(1.5.dp, goldDropIndicator, CircleShape)
                                        }
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (reached) {
                                    Icon(
                                        imageVector = MintIcons.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(12.dp),
                                    )
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    translationX = swipeOffsetX.value
                                    translationY = if (isDraggingThis) engine.dragController.dragOffsetY else 0f
                                    if (isDraggingThis) {
                                        alpha = 0.88f
                                        scaleX = 1.02f
                                        scaleY = 1.02f
                                    }
                                }
                                .then(
                                    when {
                                        isDraggingThis -> Modifier
                                            .shadow(12.dp, RoundedCornerShape(14.dp))
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(mintElevatedSurface(dark), RoundedCornerShape(14.dp))
                                        isBlockSelected -> Modifier
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(animatedRowBg, RoundedCornerShape(14.dp))
                                            .border(0.8.dp, selectedCardBorder, RoundedCornerShape(14.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                        animatedRowBg != Color.Transparent -> Modifier
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(animatedRowBg, RoundedCornerShape(14.dp))
                                        else -> Modifier
                                    }
                                ),
                        ) {
                            SelectionAwareBlockSlot(
                                isSelectionMode = isSelectionMode,
                                selected = isBlockSelected,
                                dark = dark,
                                onToggleSelect = {
                                    selectedBlockIds = EditorBlockOps.toggleBlockSelection(selectedBlockIds, block.id)
                                },
                                dragHandlers = dragHandlers,
                            ) {
                        when {
                            doc.type == "callout" -> {
                                val cycleTone = {
                                    openBlockMenuId = null
                                    val tones = NoteBlock.CALLOUT_TONES
                                    val nextTone = tones[(tones.indexOf(doc.tone) + 1) % tones.size]
                                    val before = engine.snapshot(focusedBlockId, focusedIsToggleTitle)
                                    engine.undoStack.recordStructuralChange(before)
                                    engine.updateSingleBlock(index, doc.withCalloutTone(nextTone))
                                    engine.refreshUndoFlags()
                                }
                                CalloutBlockRow(
                                    block = block,
                                    doc = doc,
                                    index = index,
                                    indentDp = indentDp,
                                    isFocused = isFocusedRow && !isSelectionMode,
                                    dark = dark,
                                    sessionClosing = effectiveRowClosing,
                                    engine = engine,
                                    focusRequester = blockFocusReq,
                                    highlightBg = highlightBg,
                                    highlightFg = highlightFg,
                                    enableSlashShortcuts = enableSlashShortcuts,
                                    openBlockMenuId = openBlockMenuId,
                                    onOpenBlockMenu = { openBlockMenuId = it },
                                    onFocusChange = { onEditorFieldFocus(block.id, false, it) },
                                    onCursorLayout = { coords, layout, cursorIdx -> updateCursorAnchor(block.id, coords, layout, cursorIdx) },
                                    onBackspaceAtStart = { handleBackspaceAtStart(block.id, isToggleTitle = false) },
                                    onEnterKey = { handleEnterKey(block.id, isToggleTitle = false) },
                                    onCycleTone = cycleTone,
                                    blockMenuContent = {
                                        WiredBlockContextMenu(
                                            expanded = openBlockMenuId == block.id,
                                            doc = doc,
                                            index = index,
                                            session = session,
                                            engine = engine,
                                            clipboard = clipboard,
                                            focusedBlockId = focusedBlockId,
                                            focusedIsToggleTitle = focusedIsToggleTitle,
                                            onDismiss = { openBlockMenuId = null },
                                            onEnterSelectionMode = { enterSelectionMode(block.id) },
                                            onCycleCalloutTone = cycleTone,
                                        )
                                    },
                                    dragHandlers = dragHandlers,
                                )
                            }

                            doc.type == "toggle" -> {
                                ToggleBlockRow(
                                    block = block,
                                    doc = doc,
                                    index = index,
                                    indentDp = indentDp,
                                    isFocused = isFocusedRow && !isSelectionMode,
                                    dark = dark,
                                    sessionClosing = effectiveRowClosing,
                                    engine = engine,
                                    titleFocusRequester = titleFocusReq,
                                    bodyFocusRequester = blockFocusReq,
                                    highlightBg = highlightBg,
                                    highlightFg = highlightFg,
                                    openBlockMenuId = openBlockMenuId,
                                    onOpenBlockMenu = { openBlockMenuId = it },
                                    onToggleCollapse = {
                                        val before = engine.snapshot(focusedBlockId, focusedIsToggleTitle)
                                        engine.undoStack.recordStructuralChange(before)
                                        val nextCollapsed = !doc.collapsed
                                        engine.updateSingleBlock(index, doc.withToggleState(collapsed = nextCollapsed))
                                        engine.refreshUndoFlags()
                                        if (nextCollapsed && focusedBlockId == block.id && !focusedIsToggleTitle) {
                                            focusEditableBlock(block.id, moveToEnd = true, toggleTitle = true)
                                        }
                                    },
                                    onFocusTitleChange = { onEditorFieldFocus(block.id, true, it) },
                                    onFocusBodyChange = { onEditorFieldFocus(block.id, false, it) },
                                    onCursorLayout = { coords, layout, cursorIdx -> updateCursorAnchor(block.id, coords, layout, cursorIdx) },
                                    onBackspaceAtStart = { isTitle -> handleBackspaceAtStart(block.id, isToggleTitle = isTitle) },
                                    onEnterKey = { isTitle -> handleEnterKey(block.id, isToggleTitle = isTitle) },
                                    blockMenuContent = {
                                        WiredBlockContextMenu(
                                            expanded = openBlockMenuId == block.id,
                                            doc = doc,
                                            index = index,
                                            session = session,
                                            engine = engine,
                                            clipboard = clipboard,
                                            focusedBlockId = focusedBlockId,
                                            focusedIsToggleTitle = focusedIsToggleTitle,
                                            onDismiss = { openBlockMenuId = null },
                                            onEnterSelectionMode = { enterSelectionMode(block.id) },
                                        )
                                    },
                                    dragHandlers = dragHandlers,
                                )
                            }

                            doc.type == "divider" -> {
                                DividerBlockRow(
                                    block = doc,
                                    index = index,
                                    dark = dark,
                                    selected = isFocusedRow && !isSelectionMode,
                                    openBlockMenuId = openBlockMenuId,
                                    onSelect = {
                                        selectedControlBlockId = block.id
                                        openBlockMenuId = block.id
                                    },
                                    onOpenBlockMenu = { openBlockMenuId = it },
                                    blockMenuContent = {
                                        WiredBlockContextMenu(
                                            expanded = openBlockMenuId == block.id,
                                            doc = doc,
                                            index = index,
                                            session = session,
                                            engine = engine,
                                            clipboard = clipboard,
                                            focusedBlockId = focusedBlockId,
                                            focusedIsToggleTitle = focusedIsToggleTitle,
                                            onDismiss = {
                                                openBlockMenuId = null
                                                selectedControlBlockId = focusedBlockId ?: selectedControlBlockId
                                            },
                                            onEnterSelectionMode = { enterSelectionMode(block.id) },
                                            onToggleDividerStyle = {
                                                openBlockMenuId = null
                                                val nextStyle = if (doc.dividerStyle == "dashed") "solid" else "dashed"
                                                val before = engine.snapshot(focusedBlockId, focusedIsToggleTitle)
                                                engine.undoStack.recordStructuralChange(before)
                                                engine.updateSingleBlock(index, doc.withDividerStyle(nextStyle))
                                                engine.refreshUndoFlags()
                                            },
                                        )
                                    },
                                    dragHandlers = dragHandlers,
                                )
                            }

                            doc.editable -> {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    EditableStandardBlockRow(
                                        block = block,
                                        doc = doc,
                                        index = index,
                                        blockCount = session.blocks.size,
                                        listNumber = listNumbers[block.id] ?: 1,
                                        indentDp = indentDp,
                                        isFocused = isFocusedRow && !isSelectionMode,
                                        dark = dark,
                                        sessionClosing = effectiveRowClosing,
                                        sessionWriting = session.writing,
                                        engine = engine,
                                        focusRequester = blockFocusReq,
                                        highlightBg = highlightBg,
                                        highlightFg = highlightFg,
                                        enableSlashShortcuts = enableSlashShortcuts,
                                        openBlockMenuId = openBlockMenuId,
                                        onOpenBlockMenu = { openBlockMenuId = it },
                                        onToggleCheckbox = { checked -> model.toggleEditorTask(session, block, checked) },
                                        onFocusChange = { onEditorFieldFocus(block.id, false, it) },
                                        onCursorLayout = { coords, layout, cursorIdx -> updateCursorAnchor(block.id, coords, layout, cursorIdx) },
                                        onBackspaceAtStart = { handleBackspaceAtStart(block.id, isToggleTitle = false) },
                                        onEnterKey = { handleEnterKey(block.id, isToggleTitle = false) },
                                        blockMenuContent = {
                                            WiredBlockContextMenu(
                                                expanded = openBlockMenuId == block.id,
                                                doc = doc,
                                                index = index,
                                                session = session,
                                                engine = engine,
                                                clipboard = clipboard,
                                                focusedBlockId = focusedBlockId,
                                                focusedIsToggleTitle = focusedIsToggleTitle,
                                                onDismiss = { openBlockMenuId = null },
                                                onEnterSelectionMode = { enterSelectionMode(block.id) },
                                            )
                                        },
                                        dragHandlers = dragHandlers,
                                    )

                                    if (block.type == "checkbox") {
                                        val liveTask = noteTaskItems.firstOrNull {
                                            it.noteId == session.draft.noteId && it.blockId == block.id
                                        }
                                        val effectiveDue = if (block.pendingScheduleChanged) block.dueDate else liveTask?.dueDate ?: block.dueDate
                                        val effectiveTime = if (block.pendingScheduleChanged) {
                                            block.pendingReminderTime ?: block.reminderLocalTime
                                        } else {
                                            liveTask?.reminderLocalTime ?: block.reminderLocalTime
                                        }
                                        val effectiveStatus = block.reminderStatus ?: liveTask?.reminderStatus
                                        val hasActiveReminder = effectiveTime != null && effectiveStatus != ReminderStatus.CANCELLED
                                        val effectiveRecurrence = if (block.pendingScheduleChanged) {
                                            block.recurrenceRule
                                        } else {
                                            liveTask?.recurrence ?: block.recurrenceRule
                                        }

                                        if ((isFocusedRow && !isSelectionMode) || !effectiveDue.isNullOrBlank() || hasActiveReminder || effectiveRecurrence != RecurrenceRule.NONE) {
                                            EditorCheckboxScheduleRow(
                                                dueDate = effectiveDue,
                                                reminderTime = if (hasActiveReminder) effectiveTime else null,
                                                recurrence = effectiveRecurrence,
                                                notificationsEnabled = capability.notificationsEnabled,
                                                enabled = !session.writing && !isSelectionMode,
                                                dark = dark,
                                                startIndentDp = indentDp + 44.dp,
                                                onClick = {
                                                    clearEditorFocus()
                                                    capability = ReminderScheduler.capability(context)
                                                    scheduleBlockId = block.id
                                                },
                                            )
                                        }
                                    }
                                }
                            }

                            block.attachmentId != null -> {
                                val attachmentId = requireNotNull(block.attachmentId)
                                val activeForAttachment = voiceCleanState.activeAttachmentId == attachmentId
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(modifier = Modifier.weight(1f)) {
                                        AttachmentRow(
                                            store = model.attachments,
                                            id = attachmentId,
                                            enabled = !session.writing && !session.voiceBusy && !isSelectionMode,
                                            insertTranscript = { text -> session.insertTranscript(block, text) },
                                            aiCleanEnabled = agentUiState.remoteAiEnabled,
                                            aiCleaning = activeForAttachment && voiceCleanState.cleaning,
                                            cleanedTranscriptPreview = if (activeForAttachment) {
                                                voiceCleanState.cleanedTranscriptPreview
                                            } else {
                                                null
                                            },
                                            extractedTodoPreviews = if (activeForAttachment) {
                                                voiceCleanState.extractedTodoPreviews
                                            } else {
                                                emptyList()
                                            },
                                            aiCleanNotice = if (activeForAttachment) voiceCleanState.notice else null,
                                            onRequestAiClean = { rawText ->
                                                model.requestVoiceTranscriptClean(attachmentId, rawText)
                                            },
                                            onAcceptAiClean = { cleanedText, selectedTodos ->
                                                val before = engine.snapshot(focusedBlockId, focusedIsToggleTitle)
                                                val inserted = model.acceptVoiceTranscriptClean(
                                                    session = session,
                                                    block = block,
                                                    cleanedText = cleanedText,
                                                    selectedTodos = selectedTodos,
                                                )
                                                if (inserted) {
                                                    engine.undoStack.recordStructuralChange(before)
                                                    engine.refreshUndoFlags()
                                                }
                                                inserted
                                            },
                                            onDismissAiCleanPreview = { model.dismissVoiceCleanPreview() },
                                        ) {
                                            val before = engine.snapshot(focusedBlockId, focusedIsToggleTitle)
                                            engine.undoStack.recordStructuralChange(before)
                                            session.removeAttachment(block)
                                            engine.refreshUndoFlags()
                                        }
                                    }
                                    if (!isSelectionMode) {
                                        BlockDragOnlyHandle(
                                            dark = dark,
                                            dragHandlers = dragHandlers,
                                        )
                                    }
                                }
                            }

                            block.referenceTarget != null -> {
                                val rawTarget = notes.firstOrNull { it.id == block.referenceTarget }
                                val trashedMatch = trashedNotes.firstOrNull { it.id == block.referenceTarget }
                                val targetTrashed = block.referenceTarget in trashedNoteIds || trashedMatch != null
                                val target = rawTarget?.takeUnless { targetTrashed }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(modifier = Modifier.weight(1f)) {
                                        ReferenceBlockCard(
                                            block = block,
                                            target = target,
                                            trashedTarget = targetTrashed,
                                            trashedTitle = (trashedMatch ?: rawTarget?.takeIf { targetTrashed })?.let(::referenceLabel),
                                            dark = dark,
                                            enabled = !session.writing && !isSelectionMode,
                                            onOpen = if (target != null && onOpenRelated != null && !isSelectionMode) {
                                                { onOpenRelated(target.id) }
                                            } else null,
                                            onRemove = {
                                                val before = engine.snapshot(focusedBlockId, focusedIsToggleTitle)
                                                engine.undoStack.recordStructuralChange(before)
                                                session.removeReference(block)
                                                engine.refreshUndoFlags()
                                            },
                                        )
                                    }
                                    if (!isSelectionMode) {
                                        BlockDragOnlyHandle(
                                            dark = dark,
                                            dragHandlers = dragHandlers,
                                        )
                                    }
                                }
                            }

                            else -> {
                                Text(
                                    text = block.text.text.toString().ifBlank { "只读区块 · ${block.type}" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = scheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 6.dp),
                                )
                            }
                        }
                        }
                        }
                    }

                    if (showDropAfter) {
                        Box(Modifier.height(0.dp).wrapContentHeight(unbounded = true)) {
                            DragInsertionIndicator(color = goldDropIndicator)
                        }
                    }
                }
            }

            item(key = "editor-blank-canvas") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .heightIn(min = 240.dp)
                        .focusProperties { canFocus = false }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = !session.closing,
                            onClick = {
                                if (isSelectionMode) {
                                    exitSelectionMode()
                                } else {
                                    val currentBlocks = session.blocks.map { it.documentBlock() }
                                    val outcome = EditorBlockOps.tapBlankCanvas(currentBlocks)
                                    if (outcome.blocks.size > currentBlocks.size) {
                                        val before = engine.snapshot(focusedBlockId, focusedIsToggleTitle)
                                        engine.applyOutcome(outcome, recordUndoBefore = before)
                                    } else if (outcome.focusBlockId != null) {
                                        focusEditableBlock(
                                            outcome.focusBlockId,
                                            cursorOffset = outcome.cursorOffset,
                                            showKeyboard = true,
                                        )
                                    }
                                }
                            },
                        )
                        .testTag("editor-blank-canvas"),
                )
            }
        }

        // Floating Slash Command Menu positioned near cursor
        val activeBlockForSlash = if (isSelectionMode) null else session.blocks.firstOrNull { it.id == focusedBlockId && it.editable }
        val activeSlashTfv = if (activeBlockForSlash != null && !focusedIsToggleTitle && activeBlockForSlash.type != "toggle") {
            engine.tfvStateFor(activeBlockForSlash).value
        } else null
        val slashTrigger = if (enableSlashShortcuts && activeSlashTfv != null) {
            EditorBlockOps.detectSlashCommand(
                text = activeSlashTfv.text,
                selection = activeSlashTfv.selection,
                composition = activeSlashTfv.composition,
            )
        } else null
        val slashSuppressed = activeBlockForSlash != null &&
            slashTrigger != null &&
            engine.dismissedSlashAt == (activeBlockForSlash.id to slashTrigger.triggerIndex)
        if (activeBlockForSlash != null && slashTrigger != null && !slashSuppressed) {
            val filteredCommands = remember(slashTrigger.query) {
                SlashCommandCatalog.filter(slashTrigger.query)
            }
            if (filteredCommands.isNotEmpty()) {
                val menuMaxHeightPx = with(density) { 268.dp.toPx() }
                val marginPx = with(density) { 8.dp.toPx() }
                val startPadPx = with(density) { 20.dp.toPx() }.roundToInt()
                val rawOffsetY = when {
                    cursorAnchorBottomPx.isNaN() || editorBoxHeightPx <= 0 ->
                        (editorBoxHeightPx - menuMaxHeightPx - marginPx).coerceAtLeast(marginPx)
                    cursorAnchorBottomPx + menuMaxHeightPx + marginPx <= editorBoxHeightPx ->
                        cursorAnchorBottomPx + with(density) { 6.dp.toPx() }
                    !cursorAnchorTopPx.isNaN() ->
                        (cursorAnchorTopPx - menuMaxHeightPx - with(density) { 6.dp.toPx() }).coerceAtLeast(marginPx)
                    else -> marginPx
                }.roundToInt()

                SlashCommandPopup(
                    commands = filteredCommands,
                    dark = dark,
                    modifier = Modifier
                        .offset { IntOffset(startPadPx, rawOffsetY) }
                        .widthIn(min = 256.dp, max = 320.dp)
                        .heightIn(max = 268.dp),
                    onSelectCommand = { cmd ->
                        val idx = session.blocks.indexOfFirst { it.id == activeBlockForSlash.id }
                        val liveBlock = session.blocks.getOrNull(idx)
                        val current = liveBlock?.let { engine.tfvStateFor(it).value }
                        val liveTrigger = current?.let { EditorBlockOps.detectSlashCommand(it.text, it.selection, it.composition) }
                        if (!session.closing && focusedBlockId == activeBlockForSlash.id && liveTrigger == slashTrigger) {
                            val before = engine.snapshot(activeBlockForSlash.id, false)
                            val outcome = EditorBlockOps.applySlashCommand(
                                blocks = before.blocks,
                                blockIndex = idx,
                                trigger = slashTrigger,
                                command = cmd,
                            )
                            engine.dismissedSlashAt = null
                            engine.applyOutcome(outcome, recordUndoBefore = before)
                        }
                    },
                )
                BackHandler(enabled = openBlockMenuId == null && !moreInsertMenuOpen) {
                    engine.dismissedSlashAt = activeBlockForSlash.id to slashTrigger.triggerIndex
                }
            }
        } else if (slashTrigger == null && engine.dismissedSlashAt != null) {
            SideEffect { engine.dismissedSlashAt = null }
        }
    }

    val activeBlock = session.blocks.firstOrNull { it.id == focusedBlockId && it.editable }
    val activeBlockIndex = if (activeBlock != null) session.blocks.indexOf(activeBlock) else -1
    val activeTfv = if (activeBlock != null) {
        if (focusedIsToggleTitle && activeBlock.type == "toggle") {
            engine.titleTfvStateFor(activeBlock).value
        } else {
            engine.tfvStateFor(activeBlock).value
        }
    } else null

    val inlineTrigger = if (activeBlock != null && activeTfv != null) {
        detectInlineTrigger(
            text = activeTfv.text,
            selection = activeTfv.selection,
            composition = activeTfv.composition,
        )
    } else null

    if (activeBlock != null && inlineTrigger != null) {
        val isTitle = focusedIsToggleTitle
        fun currentTrigger(): InlineTrigger? {
            if (focusedBlockId != activeBlock.id || focusedIsToggleTitle != isTitle) return null
            val block = engine.blockForInput(activeBlock.id) ?: return null
            val value = if (isTitle) engine.titleTfvStateFor(block).value else engine.tfvStateFor(block).value
            return detectInlineTrigger(value.text, value.selection, value.composition)?.takeIf { it == inlineTrigger }
        }
        InlineTriggerSuggestionBar(
            trigger = inlineTrigger,
            availableTags = availableTags,
            attachedTagIds = noteTagIds + session.pendingTagIds,
            candidateNotes = notes.filter { it.id != session.draft.noteId },
            dark = dark,
            onPickExistingTag = { tag ->
                val latest = currentTrigger()
                if (latest != null) {
                    removeTriggerText(activeBlock, engine, focusedIsToggleTitle, latest.triggerIndex, latest.cursorIndex)
                    model.attachTagToSession(session, tag.id)
                }
            },
            onCreateTag = { tagName ->
                val latest = currentTrigger()
                if (latest != null) {
                    removeTriggerText(activeBlock, engine, focusedIsToggleTitle, latest.triggerIndex, latest.cursorIndex)
                    val existing = availableTags.firstOrNull {
                        it.parentId.isEmpty() && it.name.equals(tagName, ignoreCase = true)
                    }
                    model.createAndAttachTag(session, tagName, existing?.id)
                }
            },
            onPickNoteFull = { targetNote ->
                val latest = currentTrigger()
                if (latest != null) {
                    val before = engine.snapshot(focusedBlockId, focusedIsToggleTitle)
                    engine.undoStack.recordStructuralChange(before)
                    removeTriggerText(activeBlock, engine, focusedIsToggleTitle, latest.triggerIndex, latest.cursorIndex)
                    session.addReference(targetNote)
                    engine.refreshUndoFlags()
                }
            },
            onPickNoteExcerpt = { targetNote ->
                val latest = currentTrigger()
                if (latest != null) {
                    pendingTriggerReplace = PendingTriggerReplace(activeBlock.id, latest.triggerIndex, "@" + latest.query, isTitle)
                    excerptTarget = targetNote
                    picker = "引用笔记"
                }
            },
        )
    }

    session.importStatus?.let { status ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = status,
                modifier = Modifier.weight(1f),
                color = if (session.importFailed) scheme.error else scheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
            if (session.importing) TextButton(
                onClick = { model.cancelImport(session) },
                modifier = Modifier.focusProperties { canFocus = false },
            ) { Text("取消导入") }
        }
    }

    val bannerStatus = engine.transientHint ?: session.status
    if (bannerStatus.isNotEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = bannerStatus,
                color = if ("失败" in bannerStatus || "未开启" in bannerStatus) scheme.error else scheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            if (engine.transientHint == null && "失败" in session.status && !session.writing) {
                TextButton(
                    onClick = onSave,
                    modifier = Modifier.focusProperties { canFocus = false },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text("重试", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }

    val echoBlock = session.blocks.firstOrNull { it.id == focusedBlockId && it.editable }
        ?: session.blocks.lastOrNull { it.editable }
    val echoInitialText = remember(session.draft.id, echoBlock?.id) {
        echoBlock?.documentBlock()?.searchableText.orEmpty()
    }
    val echoCurrentText = echoBlock?.documentBlock()?.searchableText.orEmpty()
    val echoIsComposing = echoBlock?.let { blk ->
        engine.tfvStateFor(blk).value.composition != null ||
            (blk.type == "toggle" && engine.titleTfvStateFor(blk).value.composition != null)
    } ?: false
    val fullDraftText = session.blocks
        .filter { it.editable }
        .joinToString("\n") { it.documentBlock().searchableText }
    val attachedTagIdsSnapshot = noteTagIds + session.pendingTagIds
    LaunchedEffect(session.draft.id, attachedTagIdsSnapshot) {
        model.refreshEditorTagRecommendations(
            draftText = fullDraftText,
            noteId = if (session.existingNote) session.draft.noteId else null,
            attachedTagIds = attachedTagIdsSnapshot,
        )
    }
    LaunchedEffect(session.draft.id, echoBlock?.id, echoCurrentText, echoIsComposing) {
        if (echoBlock != null && echoCurrentText != echoInitialText) {
            model.onEditorTextActivity(
                noteId = if (session.existingNote) session.draft.noteId else null,
                blockText = echoCurrentText,
                isComposing = echoIsComposing,
            )
            if (!echoIsComposing) {
                model.refreshEditorTagRecommendations(
                    draftText = fullDraftText,
                    noteId = if (session.existingNote) session.draft.noteId else null,
                    attachedTagIds = attachedTagIdsSnapshot,
                )
            }
        }
    }

    EditorEdgeEchoStrip(
        hit = discoveryState.edgeEcho,
        dark = dark,
        onPreview = { hit -> echoPreviewHit = hit },
        onDismiss = { model.dismissEdgeEcho(suppressNoteForSession = true) },
    )

    if (session.existingNote) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            val aiAccent = mintAiAccent(dark)
            val relatedCount = discoveryState.relatedNotes.size
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(aiAccent.bg)
                    .focusProperties { canFocus = false }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = {
                            model.refreshEditorRelatedNotes(session.draft.noteId)
                            showRelatedSheet = true
                        },
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("editor-related-trigger"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    imageVector = MintIcons.Sparkles,
                    contentDescription = null,
                    tint = aiAccent.fg,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = if (relatedCount > 0) "相关笔记 $relatedCount" else "相关笔记",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = aiAccent.fg,
                )
            }
        }
    }

    val visibleRecommendedTags = discoveryState.recommendedTags.filter { it.tag.id !in attachedTagIdsSnapshot }
    if (visibleRecommendedTags.isNotEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp)
                .focusProperties { canFocus = false }
                .horizontalScroll(rememberScrollState())
                .testTag("editor-tag-suggestions"),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "推荐标签",
                style = MaterialTheme.typography.labelSmall,
                color = mintTertiaryTextColor(dark),
            )
            for (rec in visibleRecommendedTags) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(mintSecondaryFill(dark))
                        .focusProperties { canFocus = false }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClick = {
                                model.attachTagToSession(session, rec.tag.id)
                                model.refreshEditorTagRecommendations(
                                    draftText = fullDraftText,
                                    noteId = if (session.existingNote) session.draft.noteId else null,
                                    attachedTagIds = attachedTagIdsSnapshot + rec.tag.id,
                                )
                            },
                        )
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(
                        text = "+ #${rec.tag.name}",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                        color = scheme.primary,
                        maxLines = 1,
                    )
                }
            }
        }
    }

    // Bottom Accessory Toolbar: Fixed Undo/Redo (Left) | Horizontal Scroll Tools (Center) | Collapse Keyboard (Right)
    val activeSelection = activeTfv?.selection ?: TextRange.Zero
    val activeMarks = if (activeBlock != null && !focusedIsToggleTitle) {
        engine.activeMarksFor(activeBlock, activeSelection)
    } else emptySet()
    val hasReferenceOption = onOpenRelated != null && notes.any { it.id != session.draft.noteId && it.id !in trashedNoteIds }
    val formatTargetIdx = activeBlockIndex.takeIf { it >= 0 }
        ?: session.blocks.indexOfFirst { it.id == selectedControlBlockId && it.editable }.takeIf { it >= 0 }
        ?: session.blocks.indexOfLast { it.editable }
    val formatTargetDoc = formatTargetIdx.takeIf { it in session.blocks.indices }?.let { session.blocks[it].documentBlock() }

    fun insertOrConvertDivider() {
        val before = engine.snapshot(focusedBlockId, focusedIsToggleTitle)
        val curBlocks = session.blocks.map { it.documentBlock() }
        val idx = formatTargetIdx.takeIf { it in curBlocks.indices } ?: curBlocks.lastIndex
        val target = curBlocks.getOrNull(idx)
        if (target != null && target.editable && target.searchableText.isEmpty()) {
            val outcome = EditorBlockOps.convertFromControls(before, idx, "divider")
            engine.applyOutcome(outcome, recordUndoBefore = before)
        } else {
            engine.undoStack.recordStructuralChange(before)
            val insertAt = (idx + 1).coerceIn(0, curBlocks.size)
            val div = NoteBlock.divider()
            val nextP = NoteBlock.paragraph()
            val nextList = curBlocks.toMutableList()
            nextList.add(insertAt, div)
            nextList.add(insertAt + 1, nextP)
            engine.applyOutcome(BlockEditOutcome(nextList, nextP.id, 0))
        }
    }

    AnimatedVisibility(visible = formatPanelOpen && !isSelectionMode) {
        EditorFormatPanel(
            targetDoc = formatTargetDoc,
            activeMarks = activeMarks,
            canStyleMarks = activeBlock != null && !focusedIsToggleTitle && !session.closing,
            canOutdent = formatTargetDoc != null && formatTargetDoc.editable && formatTargetDoc.indent > 0 && !session.closing,
            canIndent = formatTargetDoc != null && formatTargetDoc.editable && formatTargetDoc.indent < NoteBlock.MAX_INDENT && !session.closing,
            dark = dark,
            onConvertType = { targetType, level ->
                if (formatTargetIdx in session.blocks.indices) {
                    val before = engine.snapshot(focusedBlockId, focusedIsToggleTitle)
                    val outcome = EditorBlockOps.convertFromControls(before, formatTargetIdx, targetType, level)
                    engine.applyOutcome(outcome, recordUndoBefore = before)
                }
            },
            onInsertDivider = { insertOrConvertDivider() },
            onToggleMark = { markType ->
                if (activeBlockIndex >= 0) {
                    engine.toggleMarkAction(activeBlockIndex, markType, activeSelection)
                }
            },
            onAdjustIndent = { delta ->
                if (formatTargetIdx in session.blocks.indices) {
                    val before = engine.snapshot(focusedBlockId, focusedIsToggleTitle)
                    val outcome = EditorBlockOps.adjustIndent(
                        before.blocks,
                        formatTargetIdx,
                        delta = delta,
                    )
                    engine.applyOutcome(BlockEditOutcome(outcome.blocks, null, 0, statusHint = outcome.statusHint), recordUndoBefore = before)
                }
            },
        )
    }

    HorizontalDivider(
        thickness = 0.5.dp,
        color = scheme.outlineVariant.copy(alpha = 0.35f),
        modifier = Modifier.padding(top = 4.dp),
    )

    if (isSelectionMode) {
        val currentDocBlocks = session.blocks.map { it.documentBlock() }
        val allSelected = currentDocBlocks.isNotEmpty() && selectedBlockIds.size >= currentDocBlocks.size
        EditorBatchActionBar(
            selectedCount = selectedBlockIds.size,
            allSelected = allSelected,
            onBatchTodo = {
                val before = engine.snapshot(focusedBlockId, focusedIsToggleTitle)
                val outcome = EditorBlockOps.batchToggleTodo(currentDocBlocks, selectedBlockIds)
                engine.applyOutcome(outcome, recordUndoBefore = before)
            },
            onOpenBatchFormat = { batchFormatSheetOpen = true },
            onToggleSelectAll = {
                selectedBlockIds = if (allSelected) emptySet() else EditorBlockOps.selectAllBlocks(currentDocBlocks)
            },
            onBatchCopy = {
                val copied = EditorBlockOps.batchCopyText(currentDocBlocks, selectedBlockIds)
                if (copied.isNotEmpty()) {
                    clipboard.setText(AnnotatedString(copied))
                    engine.transientHint = "已复制 ${selectedBlockIds.size} 段内容"
                }
            },
            onBatchDelete = {
                val before = engine.snapshot(focusedBlockId, focusedIsToggleTitle)
                val outcome = EditorBlockOps.batchDeleteBlocks(currentDocBlocks, selectedBlockIds)
                engine.applyOutcome(outcome, recordUndoBefore = before)
                exitSelectionMode()
            },
        )
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp)
                .focusProperties { canFocus = false }
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EditorAccessoryPill(
                    icon = MintIcons.Checklist,
                    label = "添加待办",
                    enabled = !session.writing && !session.closing,
                    dark = dark,
                    onClick = {
                        val before = engine.snapshot(focusedBlockId, focusedIsToggleTitle)
                        val curBlocks = session.blocks.map { it.documentBlock() }
                        val targetIdx = formatTargetIdx.takeIf { it in curBlocks.indices } ?: curBlocks.lastIndex
                        val targetBlock = curBlocks.getOrNull(targetIdx)
                        engine.undoStack.recordStructuralChange(before)
                        if (targetIdx > 0 && targetBlock?.type == "paragraph" && targetBlock.text.isEmpty()) {
                            val outcome = EditorBlockOps.convertFromControls(before, targetIdx, "checkbox")
                            engine.applyOutcome(outcome)
                        } else {
                            val insertAt = (targetIdx + 1).coerceIn(0, curBlocks.size)
                            val newCb = NoteBlock.checkbox()
                            val nextList = curBlocks.toMutableList()
                            nextList.add(insertAt, newCb)
                            if (insertAt == nextList.lastIndex) {
                                nextList.add(NoteBlock.paragraph())
                            }
                            engine.applyOutcome(BlockEditOutcome(nextList, newCb.id, 0))
                        }
                    },
                )
                EditorAccessoryPill(
                    icon = EditorIcons.TextFormat,
                    label = "格式",
                    enabled = !session.closing && session.blocks.isNotEmpty(),
                    active = formatPanelOpen,
                    dark = dark,
                    contentDescription = "展开格式面板",
                    onClick = { formatPanelOpen = !formatPanelOpen },
                )
                if (hasReferenceOption) {
                    EditorAccessoryPill(
                        icon = MintIcons.AtSign,
                        label = "@ 引用",
                        enabled = !session.writing && !session.closing,
                        dark = dark,
                        onClick = {
                            excerptTarget = null
                            pendingTriggerReplace = null
                            picker = "引用笔记"
                        },
                    )
                }
                if (!session.recording && !session.voicePending) {
                    EditorAccessoryPill(
                        icon = MintIcons.Audio,
                        label = "录音",
                        enabled = !session.writing && !session.importing && !session.voiceBusy && !session.closing,
                        dark = dark,
                        onClick = { microphone.launch(Manifest.permission.RECORD_AUDIO) },
                    )
                }
                Box {
                    ToolbarIconButton(
                        icon = MintIcons.Plus,
                        contentDescription = "更多插入",
                        enabled = !session.closing,
                        active = moreInsertMenuOpen,
                        iconSize = 22.dp,
                        onClick = { moreInsertMenuOpen = true },
                    )
                    ComposablesMenu(
                        expanded = moreInsertMenuOpen,
                        onDismiss = { moreInsertMenuOpen = false },
                        tag = "editor-more-insert-menu",
                        focusable = false,
                    ) {
                        ComposablesMenuItem(
                            label = if (session.importFailed) "重选图片/文件附件" else "插入图片/视频/附件",
                            icon = MintIcons.Paperclip,
                        ) {
                            moreInsertMenuOpen = false
                            if (!session.writing && !session.importing && !session.voiceBusy) {
                                showImport = true
                            }
                        }
                        ComposablesMenuItem(
                            label = "# 添加标签",
                            icon = MintIcons.Tag,
                        ) {
                            moreInsertMenuOpen = false
                            if (!session.writing) tagPickerOpen = true
                        }
                        ComposablesMenuItem(
                            label = "插入分割线",
                            icon = EditorIcons.DividerLine,
                        ) {
                            moreInsertMenuOpen = false
                            insertOrConvertDivider()
                        }
                        ComposablesMenuItem(
                            label = "多选整理段落",
                            icon = EditorIcons.SelectAll,
                        ) {
                            moreInsertMenuOpen = false
                            val seed = focusedBlockId ?: selectedControlBlockId ?: session.blocks.firstOrNull()?.id
                            if (seed != null) enterSelectionMode(seed)
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .width(0.8.dp)
                    .height(22.dp)
                    .background(mintDividerColor(dark)),
            )
            // Right fixed: Collapse Keyboard
            ToolbarIconButton(
                icon = EditorIcons.KeyboardHide,
                contentDescription = "收起键盘",
                enabled = true,
                iconSize = 22.dp,
                onClick = {
                    formatPanelOpen = false
                    clearEditorFocus()
                },
            )
        }
    }

    if (batchFormatSheetOpen && isSelectionMode) {
        val sampleId = selectedBlockIds.firstOrNull()
        val sampleDoc = sampleId?.let { id -> session.blocks.firstOrNull { it.id == id }?.documentBlock() }
        BlockFormatSheet(
            currentType = sampleDoc?.type,
            currentLevel = sampleDoc?.level ?: 1,
            dark = dark,
            backdrop = localBackdrop,
            onDismiss = { batchFormatSheetOpen = false },
            onSelectCommand = { cmd ->
                batchFormatSheetOpen = false
                val before = engine.snapshot(focusedBlockId, focusedIsToggleTitle)
                val outcome = EditorBlockOps.batchConvertType(
                    blocks = session.blocks.map { it.documentBlock() },
                    selectedIds = selectedBlockIds,
                    targetType = cmd.targetType,
                    headingLevel = cmd.headingLevel,
                )
                engine.applyOutcome(outcome, recordUndoBefore = before)
            },
        )
    }

    if (showImport) {
        MintAlertDialog(
            onDismissRequest = { showImport = false },
            dark = dark,
            backdrop = localBackdrop,
            title = "添加附件",
            subtitle = "可选择图片、音频或视频，每个最多 100 MB。" +
                (space?.let { "\n本机可用空间 ${it / 1_000_000} MB" } ?: ""),
            dismissLabel = "取消",
            onDismissAction = { showImport = false },
            confirmLabel = "选择文件",
            onConfirmAction = {
                showImport = false
                try {
                    attachmentPicker.launch(arrayOf("image/*", "audio/*", "video/*"))
                } catch (_: ActivityNotFoundException) {
                    session.importStatus = "本机没有可用的文件选择器"
                    session.importFailed = true
                }
            },
        )
    }

    picker?.let { mode ->
        ReferencePickerDialog(
            mode = mode,
            notes = notes.filter { it.id !in trashedNoteIds },
            backlinks = backlinks.filter { it.id !in trashedNoteIds },
            currentNoteId = session.draft.noteId,
            excerptTarget = excerptTarget,
            onSelectExcerptTarget = { target -> excerptTarget = target },
            backdrop = localBackdrop,
            dark = dark,
            onDismiss = {
                picker = null
                excerptTarget = null
                pendingTriggerReplace = null
            },
            onPickFull = { note ->
                val before = engine.snapshot(focusedBlockId, focusedIsToggleTitle)
                clearPendingInlineTrigger(session, engine, pendingTriggerReplace)
                pendingTriggerReplace = null
                picker = null
                excerptTarget = null
                if (mode == "引用笔记") {
                    engine.undoStack.recordStructuralChange(before)
                    session.addReference(note)
                    engine.refreshUndoFlags()
                } else {
                    onOpenRelated?.invoke(note.id)
                }
            },
            onPickExcerpt = { note, blockId, start, end, labelOverride ->
                val before = engine.snapshot(focusedBlockId, focusedIsToggleTitle)
                engine.undoStack.recordStructuralChange(before)
                clearPendingInlineTrigger(session, engine, pendingTriggerReplace)
                pendingTriggerReplace = null
                picker = null
                excerptTarget = null
                session.addReference(
                    note = note,
                    targetBlockId = blockId,
                    start = start,
                    end = end,
                    labelOverride = labelOverride,
                )
                engine.refreshUndoFlags()
            },
        )
    }

    if (tagPickerOpen) {
        TagPickerDialog(
            availableTags = availableTags,
            selectedTagIds = noteTagIds + session.pendingTagIds,
            backdrop = localBackdrop,
            dark = dark,
            onDismiss = { tagPickerOpen = false },
            onSelectExisting = { tag ->
                model.attachTagToSession(session, tag.id)
                tagPickerOpen = false
            },
            onCreateTag = { name ->
                val existing = availableTags.firstOrNull {
                    it.parentId.isEmpty() && it.name.equals(name, ignoreCase = true)
                }
                model.createAndAttachTag(session, name, existing?.id)
                tagPickerOpen = false
            },
            onOpenFullOrganizer = onOpenOrganize?.let { openOrg ->
                {
                    tagPickerOpen = false
                    openOrg()
                }
            },
            recommendedTags = visibleRecommendedTags,
        )
    }

    echoPreviewHit?.let { previewHit ->
        EdgeEchoPreviewSheet(
            hit = previewHit,
            dark = dark,
            backdrop = localBackdrop,
            onDismiss = { echoPreviewHit = null },
            onOpenTarget = { target ->
                echoPreviewHit = null
                model.dismissEdgeEcho(suppressNoteForSession = false)
                model.verifyAndOpenEdgeEchoTarget(target)
            },
            onMuteSession = {
                echoPreviewHit = null
                model.muteEdgeEchoForSession()
            },
        )
    }

    if (showRelatedSheet) {
        EditorRelatedNotesSheet(
            noteId = session.draft.noteId,
            state = discoveryState,
            dark = dark,
            backdrop = localBackdrop,
            onDismiss = { showRelatedSheet = false },
            onOpenRelatedNote = { match ->
                showRelatedSheet = false
                if (onOpenRelated != null) {
                    onOpenRelated(match.note.id)
                } else {
                    model.openDiscoveryNote(match.note.id, match.targetBlockId)
                }
            },
            onStartWalkFromCurrent = { nid ->
                showRelatedSheet = false
                onStartWalkFromNote?.invoke(nid)
            },
            sourceBodyText = fullDraftText,
        )
    }

    val scheduleBlock = scheduleBlockId?.let { targetId ->
        session.blocks.firstOrNull { it.id == targetId && it.type == "checkbox" }
    }
    if (scheduleBlock != null) {
        val zoneId = ZoneId.systemDefault()
        val today = LocalDate.now(zoneId)
        val liveTask = noteTaskItems.firstOrNull {
            it.noteId == session.draft.noteId && it.blockId == scheduleBlock.id
        }
        val initialDue = if (scheduleBlock.pendingScheduleChanged) {
            scheduleBlock.dueDate
        } else {
            liveTask?.dueDate ?: scheduleBlock.dueDate
        }
        val initialTime = if (scheduleBlock.pendingScheduleChanged) {
            scheduleBlock.pendingReminderTime ?: scheduleBlock.reminderLocalTime
        } else {
            liveTask?.reminderLocalTime ?: scheduleBlock.reminderLocalTime
        }
        val initialStatus = scheduleBlock.reminderStatus ?: liveTask?.reminderStatus
        val initialRecurrence = if (scheduleBlock.pendingScheduleChanged) {
            scheduleBlock.recurrenceRule
        } else {
            liveTask?.recurrence ?: scheduleBlock.recurrenceRule
        }
        val initialPolicy = if (scheduleBlock.pendingScheduleChanged) {
            scheduleBlock.reminderPolicy
        } else {
            liveTask?.policy ?: scheduleBlock.reminderPolicy
        }
        val initialReminderId = scheduleBlock.reminderId ?: liveTask?.reminderId

        val initialDraft = TasksScheduleDraft(
            noteId = session.draft.noteId,
            blockId = scheduleBlock.id,
            taskText = scheduleBlock.text.text.toString().ifBlank { "待办事项" },
            reminderId = initialReminderId,
            reminderStatus = initialStatus,
            dueDateText = initialDue.orEmpty(),
            reminderEnabled = initialTime != null && initialStatus != ReminderStatus.CANCELLED,
            reminderTimeText = initialTime ?: "09:00",
            recurrence = initialRecurrence,
            policy = initialPolicy,
        )

        TaskScheduleModal(
            initial = initialDraft,
            notificationsEnabled = capability.notificationsEnabled,
            onDismiss = { scheduleBlockId = null },
            onSave = { updated ->
                val parsedDue = TaskRecurrence.parseDueDate(updated.dueDateText)
                val parsedTime = if (updated.reminderEnabled) {
                    TaskRecurrence.parseLocalTime(updated.reminderTimeText)
                } else null
                when {
                    updated.dueDateText.isNotBlank() && parsedDue == null -> {
                        session.status = "截止日期格式需为 YYYY-MM-DD"
                    }
                    updated.reminderEnabled && parsedTime == null -> {
                        session.status = "提醒时间格式需为 HH:mm"
                    }
                    else -> {
                        if (parsedTime != null && !capability.notificationsEnabled) {
                            requestNotifications()
                        }
                        model.scheduleEditorTask(
                            session = session,
                            block = scheduleBlock,
                            dueDate = parsedDue?.toString(),
                            reminderTime = parsedTime?.let(TaskRecurrence::formatLocalTime),
                            policy = updated.policy,
                            recurrence = if (parsedTime != null) updated.recurrence else RecurrenceRule.NONE,
                        )
                        scheduleBlockId = null
                    }
                }
            },
            onClear = {
                model.clearEditorTaskSchedule(session, scheduleBlock)
                scheduleBlockId = null
            },
            onSnooze = { remId ->
                scope.launch {
                    model.snoozeReminder(remId)
                    session.status = "已延后 15 分钟提醒"
                    scheduleBlockId = null
                }
            },
            backdrop = localBackdrop,
            tier = tier.supportedOn(Build.VERSION.SDK_INT),
            dark = dark,
            zoneId = zoneId,
            today = today,
            onRequestNotificationPermission = requestNotifications,
        )
    }
}

private val LIST_BLOCK_TYPES = setOf("bullet", "numbered", "checkbox")

@Composable
private fun EditableStandardBlockRow(
    block: EditorBlock,
    doc: NoteBlock,
    index: Int,
    blockCount: Int,
    listNumber: Int,
    indentDp: androidx.compose.ui.unit.Dp,
    isFocused: Boolean,
    dark: Boolean,
    sessionClosing: Boolean,
    sessionWriting: Boolean,
    engine: EditorEngineState,
    focusRequester: FocusRequester,
    highlightBg: Color,
    highlightFg: Color,
    enableSlashShortcuts: Boolean,
    openBlockMenuId: String?,
    onOpenBlockMenu: (String?) -> Unit,
    onToggleCheckbox: (Boolean) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onCursorLayout: (LayoutCoordinates, TextLayoutResult, Int) -> Unit,
    onBackspaceAtStart: () -> Boolean,
    onEnterKey: () -> Boolean,
    blockMenuContent: @Composable () -> Unit,
    dragHandlers: BlockDragHandlers,
) {
    val scheme = MaterialTheme.colorScheme
    val tfvState = engine.tfvStateFor(block)
    val baseStyle = blockTextStyle(doc = doc, scheme = scheme)
    val visual = remember(doc.marks, highlightBg, highlightFg) {
        marksVisualTransformation(doc.marks, highlightBg, highlightFg)
    }
    val placeholder = when {
        doc.type == "checkbox" -> "待办事项"
        doc.type == "heading" -> "${doc.level} 级标题"
        doc.type == "quote" -> "输入引用内容…"
        doc.type == "bullet" || doc.type == "numbered" -> "列表项"
        index == 0 && blockCount == 1 -> "写点什么…  /快捷排版 #标签 @引用"
        else -> ""
    }

    val rowContent: @Composable RowScope.() -> Unit = {
        when (doc.type) {
            "checkbox" -> {
                val inputModeManager = LocalInputModeManager.current
                MintCheckbox(
                    checked = block.checked,
                    enabled = !sessionWriting && !sessionClosing,
                    onCheckedChange = onToggleCheckbox,
                    dark = dark,
                    modifier = Modifier
                        .focusProperties { canFocus = inputModeManager.inputMode != InputMode.Touch }
                        .testTag("checkbox-$index"),
                )
            }
            "bullet" -> {
                val bulletGlyph = when (doc.indent % 3) {
                    1 -> "◦"
                    2 -> "▪"
                    else -> "•"
                }
                Box(
                    modifier = Modifier.size(width = 24.dp, height = 26.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = bulletGlyph,
                        style = baseStyle.copy(fontWeight = FontWeight.Bold),
                        color = scheme.primary,
                    )
                }
            }
            "numbered" -> {
                Box(
                    modifier = Modifier
                        .widthIn(min = 24.dp)
                        .height(26.dp)
                        .padding(end = 4.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = "$listNumber.",
                        style = baseStyle.copy(
                            fontSize = 15.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFeatureSettings = "tnum",
                        ),
                        color = scheme.primary,
                    )
                }
            }
        }

        BlockBasicTextField(
            block = block,
            tfvState = tfvState,
            textStyle = baseStyle,
            placeholder = placeholder,
            visualTransformation = visual,
            enabled = !sessionClosing,
            engine = engine,
            focusRequester = focusRequester,
            attachedSet = engine.attachedFocusRequesters,
            testTag = if (index == 0) "capture-editor" else "block-editor-$index",
            enableSlashShortcuts = enableSlashShortcuts,
            modifier = Modifier
                .weight(1f)
                .then(
                    if (doc.type == "checkbox" && block.checked) Modifier.alpha(0.48f) else Modifier
                ),
            onFocusChange = onFocusChange,
            onCursorLayout = onCursorLayout,
            onBackspaceAtStart = onBackspaceAtStart,
            onEnterKey = onEnterKey,
        )

        if (isFocused || openBlockMenuId == block.id) {
            BlockTrailingControls(
                blockId = block.id,
                index = index,
                dark = dark,
                menuOpen = openBlockMenuId == block.id,
                onOpenMenu = { onOpenBlockMenu(block.id) },
                menuContent = blockMenuContent,
                dragHandlers = dragHandlers,
            )
        } else {
            BlockTrailingControlsSpace()
        }
    }

    val isQuote = doc.type == "quote"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = indentDp)
            .then(if (isQuote) Modifier.height(IntrinsicSize.Min).heightIn(min = BlockControlHeight) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isQuote) {
            Box(
                modifier = Modifier
                    .padding(vertical = 3.dp)
                    .width(3.5.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(scheme.primary.copy(alpha = 0.55f)),
            )
            Spacer(Modifier.width(10.dp))
        }
        Row(
            modifier = Modifier.weight(1f).then(
                if (isQuote) Modifier else Modifier.heightIn(min = if (doc.type == "checkbox") 32.dp else BlockControlHeight)
            ),
            verticalAlignment = Alignment.CenterVertically,
            content = rowContent,
        )
    }
}

@Composable
private fun CalloutBlockRow(
    block: EditorBlock,
    doc: NoteBlock,
    index: Int,
    indentDp: androidx.compose.ui.unit.Dp,
    isFocused: Boolean,
    dark: Boolean,
    sessionClosing: Boolean,
    engine: EditorEngineState,
    focusRequester: FocusRequester,
    highlightBg: Color,
    highlightFg: Color,
    enableSlashShortcuts: Boolean,
    openBlockMenuId: String?,
    onOpenBlockMenu: (String?) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onCursorLayout: (LayoutCoordinates, TextLayoutResult, Int) -> Unit,
    onBackspaceAtStart: () -> Boolean,
    onEnterKey: () -> Boolean,
    onCycleTone: () -> Unit,
    blockMenuContent: @Composable () -> Unit,
    dragHandlers: BlockDragHandlers,
) {
    val scheme = MaterialTheme.colorScheme
    val tfvState = engine.tfvStateFor(block)
    val (accentFg, cardBg) = when (doc.tone) {
        "tip" -> scheme.primary to scheme.primaryContainer.copy(alpha = if (dark) 0.52f else 0.72f)
        "warn" -> mintTodoAccent(dark).let { it.fg to it.bg }
        "important" -> mintAiAccent(dark).let { it.fg to it.bg }
        else -> mintLinkAccent(dark).let { it.fg to it.bg }
    }
    val visual = remember(doc.marks, highlightBg, highlightFg) {
        marksVisualTransformation(doc.marks, highlightBg, highlightFg)
    }
    val style = MaterialTheme.typography.bodyLarge.copy(
        fontSize = 16.5.sp,
        lineHeight = 26.sp,
        color = scheme.onSurface,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = indentDp)
            .clip(RoundedCornerShape(14.dp))
            .background(cardBg, RoundedCornerShape(14.dp))
            .border(0.6.dp, accentFg.copy(alpha = 0.28f), RoundedCornerShape(14.dp))
            .padding(start = 10.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .focusProperties { canFocus = false }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onCycleTone,
                )
                .semantics { contentDescription = "切换提示框配色" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = EditorIcons.Callout,
                contentDescription = null,
                tint = accentFg,
                modifier = Modifier.size(18.dp),
            )
        }
        BlockBasicTextField(
            block = block,
            tfvState = tfvState,
            textStyle = style,
            placeholder = "输入提示内容…",
            visualTransformation = visual,
            enabled = !sessionClosing,
            engine = engine,
            focusRequester = focusRequester,
            attachedSet = engine.attachedFocusRequesters,
            testTag = if (index == 0) "capture-editor" else "block-editor-$index",
            enableSlashShortcuts = enableSlashShortcuts,
            modifier = Modifier.weight(1f),
            onFocusChange = onFocusChange,
            onCursorLayout = onCursorLayout,
            onBackspaceAtStart = onBackspaceAtStart,
            onEnterKey = onEnterKey,
        )
        if (isFocused || openBlockMenuId == block.id) {
            BlockTrailingControls(
                blockId = block.id,
                index = index,
                dark = dark,
                menuOpen = openBlockMenuId == block.id,
                onOpenMenu = { onOpenBlockMenu(block.id) },
                menuContent = blockMenuContent,
                dragHandlers = dragHandlers,
            )
        } else {
            BlockTrailingControlsSpace()
        }
    }
}

@Composable
private fun ToggleBlockRow(
    block: EditorBlock,
    doc: NoteBlock,
    index: Int,
    indentDp: androidx.compose.ui.unit.Dp,
    isFocused: Boolean,
    dark: Boolean,
    sessionClosing: Boolean,
    engine: EditorEngineState,
    titleFocusRequester: FocusRequester,
    bodyFocusRequester: FocusRequester,
    highlightBg: Color,
    highlightFg: Color,
    openBlockMenuId: String?,
    onOpenBlockMenu: (String?) -> Unit,
    onToggleCollapse: () -> Unit,
    onFocusTitleChange: (Boolean) -> Unit,
    onFocusBodyChange: (Boolean) -> Unit,
    onCursorLayout: (LayoutCoordinates, TextLayoutResult, Int) -> Unit,
    onBackspaceAtStart: (Boolean) -> Boolean,
    onEnterKey: (Boolean) -> Boolean,
    blockMenuContent: @Composable () -> Unit,
    dragHandlers: BlockDragHandlers,
) {
    val scheme = MaterialTheme.colorScheme
    val titleTfvState = engine.titleTfvStateFor(block)
    val bodyTfvState = engine.tfvStateFor(block)
    val titleStyle = MaterialTheme.typography.titleSmall.copy(
        fontSize = 17.5.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold,
        color = scheme.onSurface,
    )
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = 16.5.sp,
        lineHeight = 26.sp,
        color = scheme.onSurface,
    )
    val bodyVisual = remember(doc.marks, highlightBg, highlightFg) {
        marksVisualTransformation(doc.marks, highlightBg, highlightFg)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = indentDp)
            .clip(RoundedCornerShape(12.dp))
            .background(mintSecondaryFill(dark).copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val inputModeManager = LocalInputModeManager.current
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .focusProperties { canFocus = inputModeManager.inputMode != InputMode.Touch }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onToggleCollapse,
                    )
                    .semantics { contentDescription = if (doc.collapsed) "展开折叠块" else "收起折叠块" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (doc.collapsed) MintIcons.ChevronRight else MintIcons.ChevronDown,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            ToggleTitleTextField(
                block = block,
                tfvState = titleTfvState,
                textStyle = titleStyle,
                enabled = !sessionClosing,
                engine = engine,
                focusRequester = titleFocusRequester,
                modifier = Modifier.weight(1f),
                onFocusChange = onFocusTitleChange,
                onBackspaceAtStart = { onBackspaceAtStart(true) },
                onEnterKey = { onEnterKey(true) },
            )
            if (isFocused || openBlockMenuId == block.id) {
                BlockTrailingControls(
                    blockId = block.id,
                    index = index,
                    dark = dark,
                    menuOpen = openBlockMenuId == block.id,
                    onOpenMenu = { onOpenBlockMenu(block.id) },
                    menuContent = blockMenuContent,
                    dragHandlers = dragHandlers,
                )
            } else {
                BlockTrailingControlsSpace()
            }
        }
        if (!doc.collapsed) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 6.dp, bottom = 4.dp)
                    .height(IntrinsicSize.Min),
            ) {
                Box(
                    modifier = Modifier
                        .padding(vertical = 4.dp)
                        .width(2.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(1.dp))
                        .background(mintDividerColor(dark)),
                )
                Spacer(Modifier.width(10.dp))
                BlockBasicTextField(
                    block = block,
                    tfvState = bodyTfvState,
                    textStyle = bodyStyle,
                    placeholder = "展开内容…",
                    visualTransformation = bodyVisual,
                    enabled = !sessionClosing,
                    engine = engine,
                    focusRequester = bodyFocusRequester,
                    attachedSet = engine.attachedFocusRequesters,
                    testTag = if (index == 0) "capture-editor" else "block-editor-$index",
                    enableSlashShortcuts = false,
                    modifier = Modifier.weight(1f),
                    onFocusChange = onFocusBodyChange,
                    onCursorLayout = onCursorLayout,
                    onBackspaceAtStart = { onBackspaceAtStart(false) },
                    onEnterKey = { onEnterKey(false) },
                )
            }
        }
    }
}

@Composable
private fun DividerBlockRow(
    block: NoteBlock,
    index: Int,
    dark: Boolean,
    selected: Boolean,
    openBlockMenuId: String?,
    onSelect: () -> Unit,
    onOpenBlockMenu: (String?) -> Unit,
    blockMenuContent: @Composable () -> Unit,
    dragHandlers: BlockDragHandlers,
) {
    val scheme = MaterialTheme.colorScheme
    val lineColor = if (selected) scheme.primary else mintDividerColor(dark)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(10.dp))
            .focusProperties { canFocus = false }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onSelect,
            )
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(2.dp),
        ) {
            val pathEffect = if (block.dividerStyle == "dashed") {
                PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
            } else null
            drawLine(
                color = lineColor,
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = pathEffect,
            )
        }
        if (selected || openBlockMenuId == block.id) {
            BlockTrailingControls(
                blockId = block.id,
                index = index,
                dark = dark,
                menuOpen = openBlockMenuId == block.id,
                onOpenMenu = { onOpenBlockMenu(block.id) },
                menuContent = blockMenuContent,
                dragHandlers = dragHandlers,
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun BlockBasicTextField(
    block: EditorBlock,
    tfvState: MutableState<TextFieldValue>,
    textStyle: TextStyle,
    placeholder: String,
    visualTransformation: VisualTransformation,
    enabled: Boolean,
    engine: EditorEngineState,
    focusRequester: FocusRequester,
    attachedSet: MutableSet<String>,
    testTag: String,
    enableSlashShortcuts: Boolean,
    modifier: Modifier = Modifier,
    onFocusChange: (Boolean) -> Unit,
    onCursorLayout: (LayoutCoordinates, TextLayoutResult, Int) -> Unit,
    onBackspaceAtStart: () -> Boolean,
    onEnterKey: () -> Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val inputBranch = block.documentBlock().editorFieldBranch()
    var fieldCoords by remember(block.id) { mutableStateOf<LayoutCoordinates?>(null) }
    var lastTextLayout by remember(block.id) { mutableStateOf<TextLayoutResult?>(null) }

    DisposableEffect(block.id) {
        onDispose { attachedSet.remove(block.id) }
    }

    var recordedCompositionEdit by remember(block.id) { mutableStateOf(false) }
    val currentTfvState by rememberUpdatedState(tfvState)
    val latestOnBackspace by rememberUpdatedState(onBackspaceAtStart)
    val latestOnEnter by rememberUpdatedState(onEnterKey)
    val interceptor = remember(block.id) {
        backspaceAtStartInterceptor(
            shouldTriggerBackspaceAtStart = {
                val currentTfv = currentTfvState.value
                currentTfv.composition == null &&
                    currentTfv.selection.collapsed &&
                    currentTfv.selection.start == 0
            },
            onBackspaceAtStart = { latestOnBackspace() },
        )
    }

    InterceptPlatformTextInput(interceptor) {
        BasicTextField(
            value = tfvState.value,
            onValueChange = { next ->
                val liveBlock = engine.blockForInput(block.id) ?: return@BasicTextField
                val liveDoc = liveBlock.documentBlock()
                if (liveDoc.editorFieldBranch() != inputBranch || (liveDoc.type == "toggle" && liveDoc.collapsed)) {
                    return@BasicTextField
                }
                val prev = tfvState.value
                if (next.text == prev.text) {
                    if (next.composition == null) recordedCompositionEdit = false
                    if (next.selection != prev.selection && prev.composition == null && next.composition == null) {
                        engine.pendingTypingMarks = emptySet()
                    }
                    tfvState.value = next
                    if (liveBlock.text.selection != next.selection) {
                        liveBlock.text.edit { selection = clampSelection(next.selection, length) }
                    }
                    val layout = lastTextLayout
                    val coords = fieldCoords
                    if (layout != null && coords != null && coords.isAttached) {
                        onCursorLayout(coords, layout, next.selection.end)
                    }
                    return@BasicTextField
                }

                // Undo/Redo or focus restores clear composition directly without passing through onValueChange.
                if (prev.composition == null) recordedCompositionEdit = false
                val idx = engine.indexOfBlock(block.id)
                val before = if (!recordedCompositionEdit) engine.snapshot(block.id, false) else null
                var nextMarks = adjustMarksForTextEdit(liveDoc.marks, prev.text, next.text, next.selection.end)
                if (before != null && prev.composition == null && next.composition == null) {
                    val splitAt = EditorBlockOps.isSingleNewlineInsertion(prev.text, next.text, prev.selection, next.selection)
                    if (splitAt != null) {
                        val outcome = EditorBlockOps.applyEnter(
                            blocks = before.blocks,
                            blockIndex = idx,
                            beforeCursor = next.text.substring(0, splitAt),
                            afterCursor = next.text.substring(splitAt + 1),
                        )
                        engine.applyOutcome(outcome, recordUndoBefore = before)
                        return@BasicTextField
                    }
                    if (enableSlashShortcuts) {
                        val current = before.blocks.toMutableList()
                        current[idx] = liveDoc.copy(text = next.text, marks = nextMarks)
                        val prefixOutcome = EditorBlockOps.tryApplyMarkdownPrefix(current, idx, next.text, next.selection.start)
                        if (prefixOutcome != null) {
                            engine.applyOutcome(prefixOutcome, recordUndoBefore = before)
                            return@BasicTextField
                        }
                    }
                }

                if (engine.pendingTypingMarks.isNotEmpty()) {
                    val insertedLen = next.text.length - (prev.text.length - (prev.selection.max - prev.selection.min))
                    val composingRange = next.composition
                    val markedRange = composingRange ?: if (insertedLen > 0) {
                        TextRange((next.selection.end - insertedLen).coerceAtLeast(0), next.selection.end)
                    } else null
                    if (markedRange != null && !markedRange.collapsed) {
                        val additions = engine.pendingTypingMarks.map { NoteTextMark(it, markedRange.min, markedRange.max) }
                        nextMarks = normalizeMarks(nextMarks + additions, next.text)
                    }
                }
                recordedCompositionEdit = next.composition != null
                tfvState.value = next
                liveBlock.replaceOriginal(liveDoc.copy(text = next.text, marks = nextMarks))
                liveBlock.text.edit {
                    replace(0, length, next.text)
                    selection = clampSelection(next.selection, length)
                }
                if (before != null) {
                    val forceBreak = next.composition != null || next.text.length != prev.text.length + 1 ||
                        next.text.getOrNull(next.selection.end - 1) in WORD_BOUNDARY_CHARS
                    engine.undoStack.recordTypingChange(before, forceBreak = forceBreak, composing = next.composition != null)
                    engine.refreshUndoFlags()
                }
            },
            enabled = enabled,
            modifier = modifier
                .onGloballyPositioned { coords ->
                    fieldCoords = coords
                    if (coords.isAttached) {
                        attachedSet.add(block.id)
                        lastTextLayout?.let { onCursorLayout(coords, it, tfvState.value.selection.end) }
                    }
                }
                .focusRequester(focusRequester)
                .onFocusChanged { state ->
                    onFocusChange(state.isFocused)
                    if (!state.isFocused) {
                        recordedCompositionEdit = false
                        if (tfvState.value.composition != null) tfvState.value = tfvState.value.copy(composition = null)
                    } else {
                        val coords = fieldCoords
                        val layout = lastTextLayout
                        if (coords != null && coords.isAttached && layout != null) {
                            onCursorLayout(coords, layout, tfvState.value.selection.end)
                        }
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val tfv = tfvState.value
                    if (tfv.composition != null) return@onPreviewKeyEvent false
                    when {
                        event.key == Key.Backspace && tfv.selection.collapsed && tfv.selection.start == 0 -> {
                            latestOnBackspace()
                        }
                        (event.key == Key.Enter || event.key == Key.NumPadEnter) && !event.isShiftPressed -> {
                            latestOnEnter()
                        }
                        else -> false
                    }
                }
                .padding(vertical = 2.dp, horizontal = 2.dp)
                .testTag(testTag),
            textStyle = textStyle,
            cursorBrush = SolidColor(scheme.primary),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Default,
            ),
            visualTransformation = visualTransformation,
            onTextLayout = { layout ->
                lastTextLayout = layout
                val coords = fieldCoords
                if (coords != null && coords.isAttached) {
                    onCursorLayout(coords, layout, tfvState.value.selection.end)
                }
            },
            decorationBox = { innerTextField ->
                Box(Modifier.fillMaxWidth()) {
                    if (tfvState.value.text.isEmpty() && placeholder.isNotEmpty()) {
                        Text(
                            text = placeholder,
                            color = scheme.onSurfaceVariant.copy(alpha = 0.65f),
                            style = textStyle.copy(fontWeight = FontWeight.Normal),
                        )
                    }
                    innerTextField()
                }
            },
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ToggleTitleTextField(
    block: EditorBlock,
    tfvState: MutableState<TextFieldValue>,
    textStyle: TextStyle,
    enabled: Boolean,
    engine: EditorEngineState,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    onFocusChange: (Boolean) -> Unit,
    onBackspaceAtStart: () -> Boolean,
    onEnterKey: () -> Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    DisposableEffect(block.id) {
        onDispose { engine.attachedTitleFocusRequesters.remove(block.id) }
    }
    var recordedCompositionEdit by remember(block.id) { mutableStateOf(false) }
    val currentTfvState by rememberUpdatedState(tfvState)
    val latestOnBackspace by rememberUpdatedState(onBackspaceAtStart)
    val latestOnEnter by rememberUpdatedState(onEnterKey)
    val interceptor = remember(block.id) {
        backspaceAtStartInterceptor(
            shouldTriggerBackspaceAtStart = {
                val currentTfv = currentTfvState.value
                currentTfv.composition == null &&
                    currentTfv.selection.collapsed &&
                    currentTfv.selection.start == 0
            },
            onBackspaceAtStart = { latestOnBackspace() },
        )
    }

    InterceptPlatformTextInput(interceptor) {
        BasicTextField(
            value = tfvState.value,
            onValueChange = { next ->
                val liveBlock = engine.blockForInput(block.id) ?: return@BasicTextField
                if (liveBlock.type != "toggle") return@BasicTextField
                val prev = tfvState.value
                if (next.text == prev.text) {
                    if (next.composition == null) recordedCompositionEdit = false
                    tfvState.value = next
                    return@BasicTextField
                }
                // Undo/Redo or focus restores clear composition directly without passing through onValueChange.
                if (prev.composition == null) recordedCompositionEdit = false
                val before = if (!recordedCompositionEdit) engine.snapshot(block.id, true) else null
                if (before != null && prev.composition == null && next.composition == null) {
                    val splitAt = EditorBlockOps.isSingleNewlineInsertion(prev.text, next.text, prev.selection, next.selection)
                    if (splitAt != null) {
                        val outcome = EditorBlockOps.applyEnter(
                            blocks = before.blocks,
                            blockIndex = engine.indexOfBlock(block.id),
                            beforeCursor = next.text.substring(0, splitAt),
                            afterCursor = next.text.substring(splitAt + 1),
                            isToggleTitle = true,
                        )
                        engine.applyOutcome(outcome, recordUndoBefore = before)
                        return@BasicTextField
                    }
                }
                recordedCompositionEdit = next.composition != null
                tfvState.value = next
                liveBlock.replaceOriginal(liveBlock.documentBlock().withToggleState(title = next.text))
                if (before != null) {
                    engine.undoStack.recordTypingChange(
                        before,
                        forceBreak = next.composition != null,
                        composing = next.composition != null,
                    )
                    engine.refreshUndoFlags()
                }
            },
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { latestOnEnter() }),
            modifier = modifier
                .onGloballyPositioned { coords ->
                    if (coords.isAttached) engine.attachedTitleFocusRequesters.add(block.id)
                }
                .focusRequester(focusRequester)
                .onFocusChanged { state ->
                    if (!state.isFocused) {
                        recordedCompositionEdit = false
                        if (tfvState.value.composition != null) tfvState.value = tfvState.value.copy(composition = null)
                    }
                    onFocusChange(state.isFocused)
                }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val tfv = tfvState.value
                    if (tfv.composition != null) return@onPreviewKeyEvent false
                    when {
                        event.key == Key.Backspace && tfv.selection.collapsed && tfv.selection.start == 0 -> latestOnBackspace()
                        event.key == Key.Enter || event.key == Key.NumPadEnter -> latestOnEnter()
                        else -> false
                    }
                }
                .padding(vertical = 3.dp, horizontal = 2.dp),
            textStyle = textStyle,
            cursorBrush = SolidColor(scheme.primary),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth()) {
                    if (tfvState.value.text.isEmpty()) {
                        Text(
                            text = "折叠标题…",
                            color = scheme.onSurfaceVariant.copy(alpha = 0.65f),
                            style = textStyle.copy(fontWeight = FontWeight.Medium),
                        )
                    }
                    inner()
                }
            },
        )
    }
}

private val WORD_BOUNDARY_CHARS = setOf(' ', '，', '。', '！', '？', '.', ',', '\n')

@Composable
private fun blockTextStyle(doc: NoteBlock, scheme: ColorScheme): TextStyle {
    return when (doc.type) {
        "heading" -> when (doc.level) {
            1 -> MaterialTheme.typography.headlineSmall.copy(
                fontSize = 26.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.4).sp,
                color = scheme.onSurface,
            )
            2 -> MaterialTheme.typography.titleLarge.copy(
                fontSize = 21.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.2).sp,
                color = scheme.onSurface,
            )
            else -> MaterialTheme.typography.titleMedium.copy(
                fontSize = 18.5.sp,
                lineHeight = 25.sp,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface,
            )
        }
        "checkbox" -> MaterialTheme.typography.bodyLarge.copy(
            fontSize = 17.sp,
            lineHeight = 24.5.sp,
            color = if (doc.checked) scheme.onSurfaceVariant else scheme.onSurface,
            textDecoration = if (doc.checked) TextDecoration.LineThrough else null,
        )
        "bullet", "numbered" -> MaterialTheme.typography.bodyLarge.copy(
            fontSize = 17.sp,
            lineHeight = 24.5.sp,
            color = scheme.onSurface,
        )
        "quote" -> MaterialTheme.typography.bodyLarge.copy(
            fontSize = 16.5.sp,
            lineHeight = 24.sp,
            color = scheme.onSurface.copy(alpha = 0.86f),
        )
        else -> MaterialTheme.typography.bodyLarge.copy(
            fontSize = 17.5.sp,
            lineHeight = 25.5.sp,
            color = scheme.onSurface,
        )
    }
}

private val BlockControlWidth = 30.dp
private val BlockControlHeight = 28.dp
private val BlockControlStartPadding = 4.dp
private val BlockControlsWidth = BlockControlStartPadding + BlockControlWidth * 2

@Composable
private fun BlockTrailingControlsSpace() {
    // Keep the same wrap width whether this row has its action and drag buttons or not.
    Spacer(Modifier.width(BlockControlsWidth))
}

@Composable
private fun BlockTrailingControls(
    blockId: String,
    index: Int,
    dark: Boolean,
    menuOpen: Boolean,
    onOpenMenu: () -> Unit,
    menuContent: @Composable () -> Unit,
    dragHandlers: BlockDragHandlers,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .width(BlockControlsWidth)
            .padding(start = BlockControlStartPadding)
            .clip(RoundedCornerShape(10.dp))
            .background(mintSecondaryFill(dark).copy(alpha = 0.8f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Box(
                modifier = Modifier
                    .size(width = BlockControlWidth, height = BlockControlHeight)
                    .focusProperties { canFocus = false }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onOpenMenu,
                    )
                    .testTag("block-actions-$index")
                    .semantics { contentDescription = "段落操作菜单" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MintIcons.More,
                    contentDescription = null,
                    tint = if (menuOpen) scheme.primary else scheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
            }
            menuContent()
        }
        BlockDragOnlyHandle(
            dark = dark,
            dragHandlers = dragHandlers,
        )
    }
}

@Composable
private fun Modifier.longPressBlockDrag(
    dragHandlers: BlockDragHandlers,
    enabled: Boolean = true,
): Modifier {
    val latest by rememberUpdatedState(dragHandlers)
    val container by rememberUpdatedState(LocalPinnableContainer.current)
    var handleCoords by remember(dragHandlers.blockId) { mutableStateOf<LayoutCoordinates?>(null) }
    val dragEnabled = enabled && dragHandlers.enabled
    return onGloballyPositioned { handleCoords = it }
        .pointerInput(dragHandlers.blockId, dragEnabled) {
            if (!dragEnabled) return@pointerInput
            var pin: PinnableContainer.PinnedHandle? = null
            var started = false
            fun rootY(position: Offset): Float? =
                handleCoords?.takeIf { it.isAttached }?.localToRoot(position)?.y?.takeIf { it.isFinite() }

            fun release() {
                pin?.release()
                pin = null
                started = false
            }
            try {
                detectDragGesturesAfterLongPress(
                    onDragStart = { position ->
                        rootY(position)?.let { y ->
                            pin = container?.pin()
                            started = true
                            latest.onStartDrag(y)
                        }
                    },
                    onDragEnd = {
                        if (started) latest.onDragFinish()
                        release()
                    },
                    onDragCancel = {
                        if (started) latest.onDragCancel()
                        release()
                    },
                    onDrag = { change, _ ->
                        if (started) {
                            rootY(change.position)?.let { latest.onDragDelta(it) }
                            change.consume()
                        }
                    },
                )
            } finally {
                if (started) latest.onDragCancel()
                release()
            }
        }
}

@Composable
private fun BlockDragOnlyHandle(
    dark: Boolean,
    controlHeight: androidx.compose.ui.unit.Dp = BlockControlHeight,
    dragHandlers: BlockDragHandlers,
) {
    Box(
        modifier = Modifier
            .size(width = BlockControlWidth, height = controlHeight)
            .focusProperties { canFocus = false }
            .longPressBlockDrag(
                dragHandlers = dragHandlers,
            )
            .semantics { contentDescription = "长按拖动调整段落顺序" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = EditorIcons.DragHandle,
            contentDescription = null,
            tint = mintTertiaryTextColor(dark).copy(alpha = 0.9f),
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun DragInsertionIndicator(color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(2.5.dp)
                .background(color),
        )
    }
}

@Composable
private fun BlockSelectionTopBanner(
    selectedCount: Int,
    dark: Boolean,
    onDone: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val bannerBg = if (dark) Color(0xFF2B2412) else Color(0xFFFFF7DC)
    val bannerBorder = if (dark) Color(0xFFE5B84B).copy(alpha = 0.35f) else Color(0xFFE2B94B).copy(alpha = 0.5f)
    Surface(
        color = bannerBg,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .border(0.8.dp, bannerBorder, RoundedCornerShape(14.dp))
            .testTag("block-selection-banner"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "已选择 $selectedCount 段",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = scheme.onSurface,
                )
                Text(
                    text = "点按段落勾选 · 长按右侧手柄上下调整顺序",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.5.sp),
                    color = mintTertiaryTextColor(dark),
                )
            }
            MintPillButton(
                label = "完成",
                primary = true,
                dark = dark,
                modifier = Modifier.testTag("block-selection-done"),
                onClick = onDone,
            )
        }
    }
}

@Composable
private fun SelectionAwareBlockSlot(
    isSelectionMode: Boolean,
    selected: Boolean,
    dark: Boolean,
    onToggleSelect: () -> Unit,
    dragHandlers: BlockDragHandlers,
    content: @Composable () -> Unit,
) {
    var lastRowDragEndedAtMs by remember { mutableLongStateOf(0L) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            content()
            if (isSelectionMode) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .focusProperties { canFocus = false }
                        .longPressBlockDrag(
                            enabled = selected,
                            dragHandlers = dragHandlers.copy(
                                onDragFinish = {
                                    lastRowDragEndedAtMs = System.currentTimeMillis()
                                    dragHandlers.onDragFinish()
                                },
                                onDragCancel = {
                                    lastRowDragEndedAtMs = System.currentTimeMillis()
                                    dragHandlers.onDragCancel()
                                },
                            ),
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Checkbox,
                            onClick = {
                                if (System.currentTimeMillis() - lastRowDragEndedAtMs > 250L) {
                                    onToggleSelect()
                                }
                            },
                        ),
                )
            }
        }
        AnimatedVisibility(
            visible = isSelectionMode,
            enter = fadeIn() + expandHorizontally(),
            exit = fadeOut() + shrinkHorizontally(),
        ) {
            BlockSelectionTrailingControls(
                selected = selected,
                dark = dark,
                onToggle = onToggleSelect,
                dragHandlers = dragHandlers,
            )
        }
    }
}

@Composable
private fun BlockSelectionTrailingControls(
    selected: Boolean,
    dark: Boolean,
    onToggle: () -> Unit,
    dragHandlers: BlockDragHandlers,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 40.dp)
                .focusProperties { canFocus = false }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Checkbox,
                    onClick = onToggle,
                )
                .semantics { contentDescription = if (selected) "取消选择该段" else "选择该段" },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .then(
                        if (selected) {
                            Modifier.background(scheme.primary, CircleShape)
                        } else {
                            Modifier.border(1.5.dp, mintTertiaryTextColor(dark).copy(alpha = 0.75f), CircleShape)
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        imageVector = MintIcons.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        BlockDragOnlyHandle(
            dark = dark,
            dragHandlers = dragHandlers,
        )
    }
}

private data class BlockDragHandlers(
    val blockId: String,
    val enabled: Boolean,
    val onStartDrag: (Float) -> Unit,
    val onDragDelta: (Float) -> Unit,
    val onDragFinish: () -> Unit,
    val onDragCancel: () -> Unit,
)

@Suppress("DEPRECATION")
@Composable
private fun WiredBlockContextMenu(
    expanded: Boolean,
    doc: NoteBlock,
    index: Int,
    session: EditorSession,
    engine: EditorEngineState,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
    focusedBlockId: String?,
    focusedIsToggleTitle: Boolean,
    onDismiss: () -> Unit,
    onEnterSelectionMode: () -> Unit,
    onCycleCalloutTone: (() -> Unit)? = null,
    onToggleDividerStyle: (() -> Unit)? = null,
) {
    val wasFocusedThisBlock = focusedBlockId == doc.id
    val moveTo: (Int) -> Unit = { targetIndex ->
        onDismiss()
        val before = engine.snapshot(focusedBlockId, focusedIsToggleTitle)
        val moved = EditorBlockOps.moveBlock(session.blocks.map { it.documentBlock() }, index, targetIndex)
        engine.applyOutcome(BlockEditOutcome(moved, null, 0), recordUndoBefore = before)
    }
    BlockContextMenu(
        expanded = expanded,
        block = doc,
        index = index,
        blockCount = session.blocks.size,
        onDismiss = onDismiss,
        onConvert = { targetType, level ->
            onDismiss()
            val before = engine.snapshot(focusedBlockId, focusedIsToggleTitle)
            val outcome = EditorBlockOps.convertFromControls(before, index, targetType, level)
            engine.applyOutcome(outcome, recordUndoBefore = before)
        },
        onCycleCalloutTone = onCycleCalloutTone,
        onToggleDividerStyle = onToggleDividerStyle,
        onCopy = if (doc.type == "divider") null else {
            {
                onDismiss()
                clipboard.setText(AnnotatedString(doc.searchableText))
                engine.transientHint = "已复制该段内容"
            }
        },
        onEnterSelectionMode = {
            onDismiss()
            onEnterSelectionMode()
        },
        onMoveUp = { if (index > 0) moveTo(index - 1) else onDismiss() },
        onMoveDown = { if (index < session.blocks.lastIndex) moveTo(index + 1) else onDismiss() },
        onDelete = {
            onDismiss()
            val before = engine.snapshot(focusedBlockId, focusedIsToggleTitle)
            val outcome = EditorBlockOps.deleteBlock(session.blocks.map { it.documentBlock() }, index)
            val edit = if (wasFocusedThisBlock) outcome else BlockEditOutcome(outcome.blocks, null, 0)
            engine.applyOutcome(edit, recordUndoBefore = before)
        },
    )
}

@Composable
private fun BlockContextMenu(
    expanded: Boolean,
    block: NoteBlock,
    index: Int,
    blockCount: Int,
    onDismiss: () -> Unit,
    onConvert: (targetType: String, level: Int) -> Unit,
    onCycleCalloutTone: (() -> Unit)?,
    onToggleDividerStyle: (() -> Unit)?,
    onCopy: (() -> Unit)?,
    onEnterSelectionMode: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    ComposablesMenu(
        expanded = expanded,
        onDismiss = onDismiss,
        tag = "block-context-menu",
        focusable = false,
    ) {
        if (block.type != "paragraph") {
            ComposablesMenuItem(label = "转化为正文", icon = EditorIcons.TextFormat) { onConvert("paragraph", 1) }
        }
        if (!(block.type == "heading" && block.level == 1)) {
            ComposablesMenuItem(label = "转化为一级标题 (H1)", icon = EditorIcons.TextFormat) { onConvert("heading", 1) }
        }
        if (!(block.type == "heading" && block.level == 2)) {
            ComposablesMenuItem(label = "转化为二级标题 (H2)", icon = EditorIcons.TextFormat) { onConvert("heading", 2) }
        }
        if (!(block.type == "heading" && block.level == 3)) {
            ComposablesMenuItem(label = "转化为三级标题 (H3)", icon = EditorIcons.TextFormat) { onConvert("heading", 3) }
        }
        if (block.type != "checkbox") {
            ComposablesMenuItem(label = "转化为待办事项", icon = MintIcons.Checklist) { onConvert("checkbox", 1) }
        }
        if (block.type != "bullet") {
            ComposablesMenuItem(label = "转化为无序列表", icon = EditorIcons.BulletList) { onConvert("bullet", 1) }
        }
        if (block.type != "numbered") {
            ComposablesMenuItem(label = "转化为有序列表", icon = EditorIcons.NumberedList) { onConvert("numbered", 1) }
        }
        if (block.type != "quote") {
            ComposablesMenuItem(label = "转化为引用", icon = EditorIcons.Quote) { onConvert("quote", 1) }
        }
        if (block.type != "callout") {
            ComposablesMenuItem(label = "转化为提示框", icon = EditorIcons.Callout) { onConvert("callout", 1) }
        } else if (onCycleCalloutTone != null) {
            ComposablesMenuItem(label = "切换提示框配色", icon = EditorIcons.Callout, onClick = onCycleCalloutTone)
        }
        if (block.type != "toggle") {
            ComposablesMenuItem(label = "转化为折叠块", icon = EditorIcons.Toggle) { onConvert("toggle", 1) }
        }
        if (block.type != "divider") {
            ComposablesMenuItem(label = "替换为分割线", icon = EditorIcons.DividerLine) { onConvert("divider", 1) }
        } else if (onToggleDividerStyle != null) {
            val styleLabel = if (block.dividerStyle == "dashed") "切换为实线分割线" else "切换为虚线分割线"
            ComposablesMenuItem(label = styleLabel, icon = EditorIcons.DividerLine, onClick = onToggleDividerStyle)
        }
        if (onCopy != null) {
            ComposablesMenuItem(label = "复制整段", icon = EditorIcons.Copy, onClick = onCopy)
        }
        ComposablesMenuItem(label = "多选调整", icon = EditorIcons.SelectAll, onClick = onEnterSelectionMode)
        if (index > 0) {
            ComposablesMenuItem(label = "上移一段", icon = EditorIcons.MoveUp, onClick = onMoveUp)
        }
        if (index < blockCount - 1) {
            ComposablesMenuItem(label = "下移一段", icon = EditorIcons.MoveDown, onClick = onMoveDown)
        }
        ComposablesMenuItem(label = "删除这一段", icon = MintIcons.Trash, destructive = true, onClick = onDelete)
    }
}

@Composable
private fun SlashCommandPopup(
    commands: List<SlashCommand>,
    dark: Boolean,
    modifier: Modifier = Modifier,
    onSelectCommand: (SlashCommand) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    LazyColumn(
        modifier = modifier
            .shadow(14.dp, shape)
            .clip(shape)
            .background(mintElevatedSurface(dark), shape)
            .border(0.6.dp, mintDividerColor(dark), shape)
            .focusProperties { canFocus = false }
            .padding(vertical = 6.dp)
            .testTag("slash-command-menu"),
    ) {
        itemsIndexed(
            items = commands,
            key = { _, cmd -> cmd.id },
        ) { idx, cmd ->
            if (idx > 0) {
                MintGroupedDivider(dark = dark, startIndent = 48.dp)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .focusProperties { canFocus = false }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = { onSelectCommand(cmd) },
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = cmd.icon,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = cmd.label,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = scheme.onSurface,
                        maxLines = 1,
                    )
                    Text(
                        text = cmd.subtitle,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.5.sp),
                        color = mintTertiaryTextColor(dark),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = cmd.shortcutHint,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.5.sp),
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BlockFormatSheet(
    currentType: String?,
    currentLevel: Int,
    dark: Boolean,
    backdrop: GlassBackdrop,
    onDismiss: () -> Unit,
    onSelectCommand: (SlashCommand) -> Unit,
) {
    MintBottomSheet(
        onDismissRequest = onDismiss,
        dark = dark,
        backdrop = backdrop,
        title = "段落转化",
        subtitle = "选择当前段落的排版样式",
        leadingLabel = "关闭",
        onLeadingClick = onDismiss,
    ) {
        LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
            val cmds = SlashCommandCatalog.commands
            itemsIndexed(
                items = cmds,
                key = { _, item -> item.id },
            ) { idx, cmd ->
                val isSelected = currentType == cmd.targetType &&
                    (cmd.targetType != "heading" || currentLevel == cmd.headingLevel)
                val shape = when {
                    cmds.size == 1 -> RoundedCornerShape(16.dp)
                    idx == 0 -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                    idx == cmds.lastIndex -> RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
                    else -> RoundedCornerShape(0.dp)
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(mintCardSurface(dark), shape),
                ) {
                    if (idx > 0) MintGroupedDivider(dark = dark)
                    MintGroupedRow(
                        label = cmd.label,
                        subtitle = cmd.subtitle,
                        leadingIcon = cmd.icon,
                        trailingText = cmd.shortcutHint,
                        selected = isSelected,
                        dark = dark,
                        onClick = { onSelectCommand(cmd) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolbarIconButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    active: Boolean = false,
    iconSize: androidx.compose.ui.unit.Dp = 20.dp,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val fg = when {
        !enabled -> scheme.onSurfaceVariant.copy(alpha = 0.35f)
        active -> scheme.primary
        else -> scheme.onSurface
    }
    val bg = if (active && enabled) scheme.primary.copy(alpha = 0.14f) else Color.Transparent
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 42.dp, minHeight = 44.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(bg, RoundedCornerShape(11.dp))
            .focusProperties { canFocus = false }
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick,
            )
            .semantics { this.contentDescription = contentDescription }
            .padding(horizontal = 9.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun InlineUndoRedoButtons(
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompactUndoRedoButton(EditorIcons.Undo, "撤销", canUndo, onUndo)
        CompactUndoRedoButton(EditorIcons.Redo, "重做", canRedo, onRedo)
    }
}

@Composable
private fun CompactUndoRedoButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(9.dp))
            .focusProperties { canFocus = false }
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
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
            tint = if (enabled) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = 0.35f),
            modifier = Modifier.size(18.dp),
        )
    }
}

private fun removeTriggerText(
    block: EditorBlock,
    engine: EditorEngineState,
    isToggleTitle: Boolean,
    triggerIndex: Int,
    cursorIndex: Int,
) {
    val liveBlock = engine.blockForInput(block.id) ?: return
    val tfvState = if (isToggleTitle && liveBlock.type == "toggle") {
        engine.titleTfvStateFor(liveBlock)
    } else {
        engine.tfvStateFor(liveBlock)
    }
    val current = tfvState.value
    if (current.composition != null) return
    val text = current.text
    if (triggerIndex !in 0..cursorIndex || cursorIndex > text.length) return
    val updatedText = text.removeRange(triggerIndex, cursorIndex)
    val nextCursor = triggerIndex.coerceIn(0, updatedText.length)
    tfvState.value = TextFieldValue(updatedText, TextRange(nextCursor))
    val doc = liveBlock.documentBlock()
    if (isToggleTitle && doc.type == "toggle") {
        liveBlock.replaceOriginal(doc.withToggleState(title = updatedText))
    } else {
        val nextMarks = adjustMarksForTextEdit(doc.marks, text, updatedText, nextCursor)
        liveBlock.replaceOriginal(doc.copy(text = updatedText, marks = nextMarks))
        liveBlock.text.edit {
            replace(0, length, updatedText)
            selection = TextRange(nextCursor)
        }
    }
}

private fun clearPendingInlineTrigger(
    session: EditorSession,
    engine: EditorEngineState,
    pending: PendingTriggerReplace?,
) {
    if (pending == null) return
    val block = session.blocks.firstOrNull { it.id == pending.blockId && it.editable } ?: return
    if (pending.toggleTitle && block.type != "toggle") return
    val tfv = if (pending.toggleTitle) engine.titleTfvStateFor(block).value else engine.tfvStateFor(block).value
    if (tfv.composition == null && tfv.text.startsWith(pending.expectedText, pending.start)) {
        removeTriggerText(block, engine, pending.toggleTitle, pending.start, pending.start + pending.expectedText.length)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorHeaderMetadata(
    activeNote: NoteEntity?,
    notebook: NotebookEntity?,
    charCount: Int,
    availableTags: List<TagEntity>,
    noteTagIds: Set<String>,
    pendingTagIds: List<String>,
    backlinksCount: Int,
    dark: Boolean,
    isAiExcluded: Boolean = false,
    onToggleAiExcluded: (() -> Unit)? = null,
    showInlineUndoRedo: Boolean = false,
    canUndo: Boolean = false,
    canRedo: Boolean = false,
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    onOpenOrganize: (() -> Unit)?,
    onOpenTagPicker: () -> Unit,
    onRemovePendingTag: (String) -> Unit,
    onOpenBacklinks: () -> Unit,
) {
    val allTagIds = (noteTagIds + pendingTagIds).toList()
    val hasTokens = notebook != null || allTagIds.isNotEmpty() || backlinksCount > 0 || isAiExcluded
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        val timeText = activeNote?.let { note ->
            val updated = formatNoteTimestamp(note.updatedAt)
            val created = formatNoteTimestamp(note.createdAt)
            if (updated == created) updated else "$updated (创建于 $created)"
        }
        val summaryParts = buildList {
            if (timeText != null) add(timeText)
            add("$charCount 字")
            if (notebook != null) add(notebook.name)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = summaryParts.joinToString(" | "),
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.5.sp),
                color = mintTertiaryTextColor(dark),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (showInlineUndoRedo) {
                InlineUndoRedoButtons(
                    canUndo = canUndo,
                    canRedo = canRedo,
                    onUndo = onUndo,
                    onRedo = onRedo,
                )
            }
        }
        if (hasTokens) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (isAiExcluded) {
                    MintChip(
                        label = "不参与 AI 整理",
                        selected = true,
                        leadingIcon = MintIcons.Lock,
                        style = MintChipStyle.Standard,
                        dark = dark,
                        onClick = { onToggleAiExcluded?.invoke() },
                    )
                }
                if (notebook != null) {
                    MintChip(
                        label = notebook.name,
                        selected = true,
                        style = MintChipStyle.SmartFolder,
                        leadingIcon = MintIcons.Folder,
                        dark = dark,
                        onClick = { onOpenOrganize?.invoke() },
                    )
                }
                for (tagId in allTagIds) {
                    val tag = availableTags.firstOrNull { it.id == tagId } ?: continue
                    val isPending = tagId in pendingTagIds && tagId !in noteTagIds
                    MintChip(
                        label = "#${tag.name}",
                        selected = true,
                        style = MintChipStyle.Selected,
                        dark = dark,
                        removable = isPending,
                        onClick = {
                            if (isPending) onRemovePendingTag(tagId)
                            else onOpenOrganize?.invoke() ?: onOpenTagPicker()
                        },
                    )
                }
                if (backlinksCount > 0) {
                    TextButton(
                        onClick = onOpenBacklinks,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text("反链 $backlinksCount")
                    }
                }
            }
        }
    }
}

@Composable
private fun InlineTriggerSuggestionBar(
    trigger: InlineTrigger,
    availableTags: List<TagEntity>,
    attachedTagIds: Set<String>,
    candidateNotes: List<NoteEntity>,
    dark: Boolean,
    onPickExistingTag: (TagEntity) -> Unit,
    onCreateTag: (String) -> Unit,
    onPickNoteFull: (NoteEntity) -> Unit,
    onPickNoteExcerpt: (NoteEntity) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val query = trigger.query.trim()
    val shape = RoundedCornerShape(14.dp)
    val bg = mintSecondaryFill(dark)

    when (trigger.kind) {
        InlineTriggerKind.TAG -> {
            val matches = remember(trigger.kind, query, availableTags, attachedTagIds) {
                availableTags.asSequence()
                    .filter {
                        it.id !in attachedTagIds &&
                            (
                                query.isEmpty() ||
                                    it.name.contains(query, ignoreCase = true) ||
                                    LocalFuzzyMatcher.matchesTagOrNoteQuery(query, it.name)
                                )
                    }
                    .take(8)
                    .toList()
            }
            val canCreate = query.isNotEmpty() &&
                query.length <= 60 &&
                '/' !in query &&
                query.none(Char::isISOControl) &&
                availableTags.none { it.parentId.isEmpty() && it.name.equals(query, ignoreCase = true) }

            if (matches.isNotEmpty() || canCreate) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .focusProperties { canFocus = false }
                        .clip(shape)
                        .background(bg, shape)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("inline-tag-suggestions"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "# 标签",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = scheme.primary,
                    )
                    if (canCreate) {
                        InlineSuggestionChip(
                            label = "创建 #$query",
                            highlight = true,
                            dark = dark,
                            onClick = { onCreateTag(query) },
                        )
                    }
                    for (tag in matches) {
                        InlineSuggestionChip(
                            label = "#${tag.name}",
                            highlight = false,
                            dark = dark,
                            onClick = { onPickExistingTag(tag) },
                        )
                    }
                }
            }
        }

        InlineTriggerKind.REFERENCE -> {
            val matches = remember(trigger.kind, query, candidateNotes) {
                candidateNotes.asSequence()
                    .filter {
                        query.isEmpty() ||
                            referenceLabel(it).contains(query, ignoreCase = true) ||
                            it.searchText.contains(query, ignoreCase = true) ||
                            LocalFuzzyMatcher.matchesTagOrNoteQuery(query, referenceLabel(it)) ||
                            LocalFuzzyMatcher.matchesTagOrNoteQuery(query, it.searchText.take(240))
                    }
                    .take(6)
                    .toList()
            }

            if (matches.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .focusProperties { canFocus = false }
                        .clip(shape)
                        .background(bg, shape)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("inline-reference-suggestions"),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "@ 引用笔记（点标题整篇引用，点「选段」框选引文）",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.primary,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        for (note in matches) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(mintCardSurface(dark))
                                    .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = referenceLabel(note),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = scheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .widthIn(max = 150.dp)
                                        .focusProperties { canFocus = false }
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            role = Role.Button,
                                            onClick = { onPickNoteFull(note) },
                                        ),
                                )
                                Text(
                                    text = "选段",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = scheme.primary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(scheme.primary.copy(alpha = 0.12f))
                                        .focusProperties { canFocus = false }
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            role = Role.Button,
                                            onClick = { onPickNoteExcerpt(note) },
                                        )
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InlineSuggestionChip(
    label: String,
    highlight: Boolean,
    dark: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    val bg = if (highlight) scheme.primary else mintCardSurface(dark)
    val fg = if (highlight) scheme.onPrimary else scheme.onSurface
    Box(
        modifier = Modifier
            .clip(shape)
            .background(bg, shape)
            .focusProperties { canFocus = false }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 11.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ReferenceBlockCard(
    block: EditorBlock,
    target: NoteEntity?,
    trashedTarget: Boolean = false,
    trashedTitle: String? = null,
    dark: Boolean,
    enabled: Boolean,
    onOpen: (() -> Unit)?,
    onRemove: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    val bg = mintSecondaryFill(dark)
    val isHealthy = target != null && !trashedTarget

    val resolvedExcerpt = remember(target?.documentJson, block.referenceTargetBlockId, block.referenceStart, block.referenceEnd) {
        val blockId = block.referenceTargetBlockId
        val rawStart = block.referenceStart
        val rawEnd = block.referenceEnd
        if (target == null || blockId == null || rawStart == null || rawEnd == null) {
            null
        } else {
            val blockText = runCatching {
                NoteDocument.decode(target.documentJson).findBlock(blockId)?.text
            }.getOrNull()
            blockText?.let { raw ->
                val safe = clampUtf16Range(raw, rawStart, rawEnd)
                if (safe.isEmpty()) null else raw.substring(safe.first, safe.last + 1)
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg, shape)
            .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = MintIcons.Link,
            contentDescription = null,
            tint = if (isHealthy) scheme.primary else scheme.error,
            modifier = Modifier.size(18.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .then(
                    if (onOpen != null && enabled && isHealthy) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClick = onOpen,
                        )
                    } else Modifier
                ),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val titleText = when {
                    target != null -> referenceLabel(target)
                    trashedTarget -> "${trashedTitle ?: "引用笔记"}（已移到废纸篓）"
                    else -> "来源已删除或不可用"
                }
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = if (isHealthy) scheme.primary else scheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (block.referenceStart != null && block.referenceEnd != null) {
                    Text(
                        text = "摘录片段",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            if (!resolvedExcerpt.isNullOrBlank()) {
                Text(
                    text = "“$resolvedExcerpt”",
                    style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                    color = scheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        TextButton(
            onClick = onRemove,
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text("移除", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun EditorCheckboxScheduleRow(
    dueDate: String?,
    reminderTime: String?,
    recurrence: RecurrenceRule,
    notificationsEnabled: Boolean,
    enabled: Boolean,
    dark: Boolean,
    startIndentDp: androidx.compose.ui.unit.Dp = 44.dp,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val hasSchedule = !dueDate.isNullOrBlank() || !reminderTime.isNullOrBlank()
    val today = remember { LocalDate.now() }
    val parsedDue = TaskRecurrence.parseDueDate(dueDate)
    val overdue = parsedDue != null && parsedDue.isBefore(today)

    val text = buildString {
        if (parsedDue != null) {
            append(
                when (parsedDue) {
                    today -> "今天"
                    today.plusDays(1) -> "明天"
                    today.minusDays(1) -> "昨天"
                    else -> "${parsedDue.monthValue}月${parsedDue.dayOfMonth}日"
                },
            )
        }
        if (!reminderTime.isNullOrBlank()) {
            if (isNotEmpty()) append(" · ")
            append(reminderTime)
            if (!notificationsEnabled) append("（提醒未开启）")
        }
        if (recurrence != RecurrenceRule.NONE) {
            if (isNotEmpty()) append(" · ")
            append(recurrence.label)
        }
        if (isEmpty()) append("设截止 / 提醒")
    }

    val shape = RoundedCornerShape(50)
    val bg = when {
        overdue && hasSchedule -> scheme.errorContainer.copy(alpha = if (dark) 0.35f else 0.75f)
        hasSchedule -> if (dark) Color(0xFF203A2E) else Color(0xFFE3F3EA)
        else -> mintSecondaryFill(dark)
    }
    val fg = when {
        overdue && hasSchedule -> scheme.onErrorContainer
        hasSchedule -> scheme.primary
        else -> scheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .padding(start = startIndentDp, bottom = 2.dp)
            .clip(shape)
            .background(bg, shape)
            .focusProperties { canFocus = false }
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector = if (!reminderTime.isNullOrBlank()) MintIcons.Bell else MintIcons.Calendar,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(13.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (hasSchedule) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = fg,
        )
    }
}

@Composable
private fun EditorAccessoryPill(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    dark: Boolean,
    active: Boolean = false,
    contentDescription: String = label,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val fg = when {
        !enabled -> scheme.onSurfaceVariant.copy(alpha = 0.45f)
        active -> scheme.primary
        else -> scheme.onSurface
    }
    val bg = if (active && enabled) scheme.primary.copy(alpha = 0.14f) else Color.Transparent
    Row(
        modifier = Modifier
            .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
            .clip(shape)
            .background(bg, shape)
            .focusProperties { canFocus = false }
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) scheme.primary else fg,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = fg,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun FormatPanelActionChip(
    label: String,
    icon: ImageVector? = null,
    active: Boolean = false,
    enabled: Boolean = true,
    dark: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val bg = when {
        !enabled -> mintSecondaryFill(dark).copy(alpha = 0.45f)
        active -> scheme.primary.copy(alpha = 0.16f)
        else -> mintCardSurface(dark)
    }
    val fg = when {
        !enabled -> scheme.onSurfaceVariant.copy(alpha = 0.4f)
        active -> scheme.primary
        else -> scheme.onSurface
    }
    val borderColor = if (active && enabled) scheme.primary.copy(alpha = 0.4f) else mintDividerColor(dark)
    Row(
        modifier = Modifier
            .heightIn(min = 38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg, RoundedCornerShape(10.dp))
            .border(0.6.dp, borderColor, RoundedCornerShape(10.dp))
            .focusProperties { canFocus = false }
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 13.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = fg,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun EditorFormatPanel(
    targetDoc: NoteBlock?,
    activeMarks: Set<NoteMarkType>,
    canStyleMarks: Boolean,
    canOutdent: Boolean,
    canIndent: Boolean,
    dark: Boolean,
    onConvertType: (targetType: String, level: Int) -> Unit,
    onInsertDivider: () -> Unit,
    onToggleMark: (NoteMarkType) -> Unit,
    onAdjustIndent: (Int) -> Unit,
) {
    val hasTarget = targetDoc != null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(mintSecondaryFill(dark).copy(alpha = 0.85f), RoundedCornerShape(16.dp))
            .border(0.6.dp, mintDividerColor(dark), RoundedCornerShape(16.dp))
            .focusProperties { canFocus = false }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val blockItems = listOf(
                Triple("H1", "heading", 1),
                Triple("H2", "heading", 2),
                Triple("H3", "heading", 3),
                Triple("正文", "paragraph", 1),
                Triple("圆点列表", "bullet", 1),
                Triple("数字列表", "numbered", 1),
                Triple("引用", "quote", 1),
                Triple("提示框", "callout", 1),
                Triple("折叠块", "toggle", 1),
            )
            blockItems.forEach { (label, type, level) ->
                val isSelected = targetDoc?.type == type && (type != "heading" || targetDoc.level == level)
                FormatPanelActionChip(
                    label = label,
                    active = isSelected,
                    enabled = hasTarget,
                    dark = dark,
                    onClick = { onConvertType(type, level) },
                )
            }
            FormatPanelActionChip(
                label = "分割线",
                icon = EditorIcons.DividerLine,
                active = targetDoc?.type == "divider",
                enabled = hasTarget,
                dark = dark,
                onClick = onInsertDivider,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FormatPanelActionChip(
                label = "加粗",
                icon = EditorIcons.Bold,
                active = NoteMarkType.BOLD in activeMarks,
                enabled = canStyleMarks,
                dark = dark,
                onClick = { onToggleMark(NoteMarkType.BOLD) },
            )
            FormatPanelActionChip(
                label = "高亮",
                icon = EditorIcons.Highlight,
                active = NoteMarkType.HIGHLIGHT in activeMarks,
                enabled = canStyleMarks,
                dark = dark,
                onClick = { onToggleMark(NoteMarkType.HIGHLIGHT) },
            )
            FormatPanelActionChip(
                label = "斜体",
                icon = EditorIcons.Italic,
                active = NoteMarkType.ITALIC in activeMarks,
                enabled = canStyleMarks,
                dark = dark,
                onClick = { onToggleMark(NoteMarkType.ITALIC) },
            )
            FormatPanelActionChip(
                label = "减少缩进",
                icon = EditorIcons.IndentDecrease,
                enabled = canOutdent,
                dark = dark,
                onClick = { onAdjustIndent(-1) },
            )
            FormatPanelActionChip(
                label = "增加缩进",
                icon = EditorIcons.IndentIncrease,
                enabled = canIndent,
                dark = dark,
                onClick = { onAdjustIndent(1) },
            )
        }
    }
}

@Composable
private fun EditorBatchActionBar(
    selectedCount: Int,
    allSelected: Boolean,
    onBatchTodo: () -> Unit,
    onOpenBatchFormat: () -> Unit,
    onToggleSelectAll: () -> Unit,
    onBatchCopy: () -> Unit,
    onBatchDelete: () -> Unit,
) {
    val hasSelection = selectedCount > 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .focusProperties { canFocus = false }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BatchActionBarButton(MintIcons.Checklist, "转待办", hasSelection, onClick = onBatchTodo)
        BatchActionBarButton(EditorIcons.TextFormat, "格式", hasSelection, onClick = onOpenBatchFormat)
        BatchActionBarButton(EditorIcons.SelectAll, if (allSelected) "全不选" else "全选", true, onClick = onToggleSelectAll)
        BatchActionBarButton(EditorIcons.Copy, "复制", hasSelection, onClick = onBatchCopy)
        BatchActionBarButton(MintIcons.Trash, "删除", hasSelection, destructive = true, onClick = onBatchDelete)
    }
}

@Composable
private fun BatchActionBarButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val fg = when {
        !enabled -> scheme.onSurfaceVariant.copy(alpha = 0.35f)
        destructive -> scheme.error
        else -> scheme.onSurface
    }
    Column(
        modifier = Modifier
            .defaultMinSize(minWidth = 52.dp, minHeight = 46.dp)
            .clip(RoundedCornerShape(12.dp))
            .focusProperties { canFocus = false }
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled && !destructive) scheme.primary else fg,
            modifier = Modifier.size(21.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = fg,
            maxLines = 1,
        )
    }
}

@Composable
private fun ReferencePickerDialog(
    mode: String,
    notes: List<NoteEntity>,
    backlinks: List<NoteEntity>,
    currentNoteId: String,
    excerptTarget: NoteEntity?,
    onSelectExcerptTarget: (NoteEntity?) -> Unit,
    backdrop: GlassBackdrop,
    dark: Boolean,
    onDismiss: () -> Unit,
    onPickFull: (NoteEntity) -> Unit,
    onPickExcerpt: (NoteEntity, String, Int, Int, String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val filter = key(mode) { rememberTextFieldState() }
    val query = filter.text.toString()
    val candidates = if (mode == "引用笔记") notes.filter { it.id != currentNoteId } else backlinks

    MintBottomSheet(
        onDismissRequest = onDismiss,
        dark = dark,
        backdrop = backdrop,
        title = if (excerptTarget != null) "选择引用选区范围" else mode,
        subtitle = excerptTarget?.let { "来源：${referenceLabel(it)}" },
        leadingLabel = if (excerptTarget != null) "返回列表" else null,
        onLeadingClick = { if (excerptTarget != null) onSelectExcerptTarget(null) else onDismiss() },
        trailingLabel = "关闭",
        onTrailingClick = onDismiss,
    ) {
        if (excerptTarget == null) {
            val trimmedQuery = query.trim()
            val filtered = candidates.filter { note ->
                trimmedQuery.isEmpty() ||
                    referenceLabel(note).contains(trimmedQuery, ignoreCase = true) ||
                    note.searchText.contains(trimmedQuery, ignoreCase = true) ||
                    LocalFuzzyMatcher.matchesTagOrNoteQuery(trimmedQuery, referenceLabel(note)) ||
                    LocalFuzzyMatcher.matchesTagOrNoteQuery(trimmedQuery, note.searchText.take(240))
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ComposablesSearchField(
                    state = filter,
                    label = "筛选笔记",
                    tag = "note-reference-filter",
                    containerColor = mintSecondaryFill(dark),
                )
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    if (filtered.isEmpty()) {
                        item(key = "ref-empty") {
                            MintGroupedSection(dark = dark, containerColor = mintCardSurface(dark)) {
                                Text(
                                    text = "没有匹配的笔记",
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        itemsIndexed(
                            items = filtered,
                            key = { _, note -> note.id },
                        ) { index, note ->
                            val itemShape = groupedItemShape(index, filtered.size)
                            val hasBlocks = remember(note.documentJson) {
                                runCatching {
                                    NoteDocument.decode(note.documentJson).blocks.any { it.searchableText.isNotEmpty() }
                                }.getOrDefault(false)
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(itemShape)
                                    .background(mintCardSurface(dark), itemShape),
                            ) {
                                if (index > 0) MintGroupedDivider(dark = dark)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .defaultMinSize(minHeight = 48.dp)
                                        .padding(start = 14.dp, end = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = referenceLabel(note),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = scheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                                role = Role.Button,
                                                onClick = { onPickFull(note) },
                                            )
                                            .padding(vertical = 12.dp),
                                    )
                                    if (mode == "引用笔记" && hasBlocks) {
                                        TextButton(onClick = { onSelectExcerptTarget(note) }) {
                                            Text("选段")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            val sourceBlocks = remember(excerptTarget.documentJson) {
                runCatching {
                    NoteDocument.decode(excerptTarget.documentJson).blocks.filter { it.text.isNotEmpty() }
                }.getOrDefault(emptyList())
            }
            var selectedBlockId by remember(excerptTarget.id, sourceBlocks) {
                mutableStateOf(sourceBlocks.firstOrNull()?.id)
            }
            val selectedBlock = sourceBlocks.firstOrNull { it.id == selectedBlockId } ?: sourceBlocks.firstOrNull()
            val sourceText = selectedBlock?.text.orEmpty()

            var rawStart by remember(selectedBlock?.id, sourceText) { mutableIntStateOf(0) }
            var rawEnd by remember(selectedBlock?.id, sourceText) { mutableIntStateOf(sourceText.length) }
            val clamped = clampUtf16Range(sourceText, rawStart, rawEnd)
            val safeStart = clamped.first
            val safeEnd = if (clamped.isEmpty()) clamped.first else clamped.last + 1
            val excerpt = if (safeEnd > safeStart) sourceText.substring(safeStart, safeEnd) else ""

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (sourceBlocks.isEmpty()) {
                    Text("该笔记暂无可引用的文字区块。", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                } else {
                    if (sourceBlocks.size > 1) {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            sourceBlocks.forEachIndexed { idx, blk ->
                                MintChip(
                                    label = "段落 ${idx + 1}",
                                    selected = blk.id == selectedBlock?.id,
                                    style = if (blk.id == selectedBlock?.id) MintChipStyle.Selected else MintChipStyle.Standard,
                                    dark = dark,
                                    onClick = { selectedBlockId = blk.id },
                                )
                            }
                        }
                    }

                    val preview = remember(sourceText, safeStart, safeEnd, scheme) {
                        buildAnnotatedString {
                            if (safeStart > 0) {
                                withStyle(SpanStyle(color = scheme.onSurfaceVariant.copy(alpha = 0.65f))) {
                                    append(sourceText.substring(0, safeStart))
                                }
                            }
                            if (safeEnd > safeStart) {
                                withStyle(
                                    SpanStyle(
                                        background = scheme.primaryContainer,
                                        color = scheme.onPrimaryContainer,
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                                ) {
                                    append(sourceText.substring(safeStart, safeEnd))
                                }
                            }
                            if (safeEnd < sourceText.length) {
                                withStyle(SpanStyle(color = scheme.onSurfaceVariant.copy(alpha = 0.65f))) {
                                    append(sourceText.substring(safeEnd))
                                }
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(mintSecondaryFill(dark))
                            .padding(12.dp),
                    ) {
                        Text(text = preview, style = MaterialTheme.typography.bodyMedium)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "已选 ${safeEnd - safeStart} / ${sourceText.length} 字",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.primary,
                        )
                        TextButton(
                            onClick = {
                                rawStart = 0
                                rawEnd = sourceText.length
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        ) {
                            Text("全选", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    if (sourceText.length > 1) {
                        RangeSlider(
                            value = safeStart.toFloat()..safeEnd.toFloat(),
                            onValueChange = { range ->
                                val adjusted = clampUtf16Range(
                                    sourceText,
                                    range.start.toInt(),
                                    range.endInclusive.toInt(),
                                )
                                rawStart = adjusted.first
                                rawEnd = if (adjusted.isEmpty()) adjusted.first else adjusted.last + 1
                            },
                            valueRange = 0f..sourceText.length.toFloat(),
                            modifier = Modifier.testTag("excerpt-range-slider"),
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("起点", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                            TextButton(
                                onClick = {
                                    val next = clampUtf16Range(sourceText, safeStart - 1, safeEnd)
                                    rawStart = next.first
                                    rawEnd = if (next.isEmpty()) next.first else next.last + 1
                                },
                                enabled = safeStart > 0,
                            ) { Text("-") }
                            TextButton(
                                onClick = {
                                    val step = if (safeStart < sourceText.length && Character.isHighSurrogate(sourceText[safeStart])) 2 else 1
                                    val nextStart = (safeStart + step).coerceAtMost(safeEnd)
                                    val next = clampUtf16Range(sourceText, nextStart, safeEnd)
                                    rawStart = next.first
                                    rawEnd = if (next.isEmpty()) next.first else next.last + 1
                                },
                                enabled = safeStart < safeEnd,
                            ) { Text("+") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("终点", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                            TextButton(
                                onClick = {
                                    val step = if (safeEnd > 0 && Character.isLowSurrogate(sourceText[safeEnd - 1])) 2 else 1
                                    val nextEnd = (safeEnd - step).coerceAtLeast(safeStart)
                                    val next = clampUtf16Range(sourceText, safeStart, nextEnd)
                                    rawStart = next.first
                                    rawEnd = if (next.isEmpty()) next.first else next.last + 1
                                },
                                enabled = safeEnd > safeStart,
                            ) { Text("-") }
                            TextButton(
                                onClick = {
                                    val next = clampUtf16Range(sourceText, safeStart, safeEnd + 1)
                                    rawStart = next.first
                                    rawEnd = if (next.isEmpty()) next.first else next.last + 1
                                },
                                enabled = safeEnd < sourceText.length,
                            ) { Text("+") }
                        }
                    }

                    MintPillButton(
                        label = "插入引用片段",
                        primary = true,
                        dark = dark,
                        enabled = selectedBlock != null && safeEnd > safeStart,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            if (selectedBlock != null && safeEnd > safeStart) {
                                val title = referenceLabel(excerptTarget)
                                val labelOverride = "$title · “${safeUtf16Slice(excerpt, 0, 40)}”"
                                onPickExcerpt(excerptTarget, selectedBlock.id, safeStart, safeEnd, labelOverride)
                            }
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagPickerDialog(
    availableTags: List<TagEntity>,
    selectedTagIds: Set<String>,
    backdrop: GlassBackdrop,
    dark: Boolean,
    onDismiss: () -> Unit,
    onSelectExisting: (TagEntity) -> Unit,
    onCreateTag: (String) -> Unit,
    onOpenFullOrganizer: (() -> Unit)?,
    recommendedTags: List<TagRecommendation> = emptyList(),
) {
    val scheme = MaterialTheme.colorScheme
    val filter = rememberTextFieldState()
    val query = filter.text.toString().trim()
    val matches = availableTags.filter {
        query.isEmpty() ||
            it.name.contains(query, ignoreCase = true) ||
            LocalFuzzyMatcher.matchesTagOrNoteQuery(query, it.name)
    }
    val visibleRecommendations = recommendedTags.filter { it.tag.id !in selectedTagIds }
    val canCreate = query.isNotEmpty() &&
        query.length <= 60 &&
        '/' !in query &&
        query.none(Char::isISOControl) &&
        availableTags.none { it.parentId.isEmpty() && it.name.equals(query, ignoreCase = true) }

    MintBottomSheet(
        onDismissRequest = onDismiss,
        dark = dark,
        backdrop = backdrop,
        title = "添加标签",
        leadingLabel = if (onOpenFullOrganizer != null) "全部整理" else null,
        onLeadingClick = { onOpenFullOrganizer?.invoke() },
        trailingLabel = "关闭",
        onTrailingClick = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (visibleRecommendations.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "智能推荐标签",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = scheme.primary,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        visibleRecommendations.forEach { rec ->
                            MintChip(
                                label = "+ #${rec.tag.name}",
                                dark = dark,
                                onClick = { onSelectExisting(rec.tag) },
                            )
                        }
                    }
                }
            }
            ComposablesSearchField(
                state = filter,
                label = "搜索或创建标签",
                tag = "note-tag-filter",
                containerColor = mintSecondaryFill(dark),
            )
            if (canCreate) {
                MintPillButton(
                    label = "创建并添加 #$query",
                    primary = true,
                    dark = dark,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onCreateTag(query) },
                )
            }
            LazyColumn(Modifier.heightIn(max = 300.dp)) {
                if (matches.isEmpty()) {
                    item(key = "tag-empty") {
                        MintGroupedSection(dark = dark, containerColor = mintCardSurface(dark)) {
                            Text(
                                text = "输入关键词可快速新建标签",
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    itemsIndexed(
                        items = matches,
                        key = { _, tag -> tag.id },
                    ) { index, tag ->
                        val itemShape = groupedItemShape(index, matches.size)
                        val alreadyAttached = tag.id in selectedTagIds
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(itemShape)
                                .background(mintCardSurface(dark), itemShape),
                        ) {
                            if (index > 0) MintGroupedDivider(dark = dark)
                            MintGroupedRow(
                                label = "#${tag.name}",
                                dark = dark,
                                selected = alreadyAttached,
                                trailingText = if (alreadyAttached) "已添加" else null,
                                enabled = !alreadyAttached,
                                onClick = { if (!alreadyAttached) onSelectExisting(tag) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun groupedItemShape(index: Int, totalCount: Int): RoundedCornerShape = when {
    totalCount <= 1 -> RoundedCornerShape(16.dp)
    index == 0 -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    index == totalCount - 1 -> RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
    else -> RoundedCornerShape(0.dp)
}

private fun formatNoteTimestamp(iso: String): String = runCatching {
    val zdt = Instant.parse(iso).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    val time = DateTimeFormatter.ofPattern("HH:mm").format(zdt)
    when (zdt.toLocalDate()) {
        today -> "今天 $time"
        today.minusDays(1) -> "昨天 $time"
        else -> if (zdt.year == today.year) {
            "${zdt.monthValue}月${zdt.dayOfMonth}日 $time"
        } else {
            "${zdt.year}-${zdt.monthValue.toString().padStart(2, '0')}-${zdt.dayOfMonth.toString().padStart(2, '0')} $time"
        }
    }
}.getOrElse { iso }

internal fun referenceLabel(note: NoteEntity): String = note.title?.takeIf(String::isNotBlank)
    ?: note.searchText.lineSequence().firstOrNull { it.isNotBlank() }?.let { safeUtf16Slice(it, 0, 80) }
    ?: "无标题笔记"
