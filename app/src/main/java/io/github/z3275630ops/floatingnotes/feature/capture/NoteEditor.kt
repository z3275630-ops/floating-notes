package io.github.z3275630ops.floatingnotes.feature.capture

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.os.Build
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import io.github.z3275630ops.floatingnotes.core.model.clampUtf16Range
import io.github.z3275630ops.floatingnotes.core.model.safeUtf16Slice
import io.github.z3275630ops.floatingnotes.data.local.NoteEntity
import io.github.z3275630ops.floatingnotes.data.local.NotebookEntity
import io.github.z3275630ops.floatingnotes.data.local.RecurrenceRule
import io.github.z3275630ops.floatingnotes.data.local.ReminderStatus
import io.github.z3275630ops.floatingnotes.data.local.TagEntity
import io.github.z3275630ops.floatingnotes.data.discovery.EvidenceHit
import io.github.z3275630ops.floatingnotes.feature.explore.EdgeEchoPreviewSheet
import io.github.z3275630ops.floatingnotes.feature.explore.EditorEdgeEchoStrip
import io.github.z3275630ops.floatingnotes.feature.explore.EditorRelatedNotesSheet
import io.github.z3275630ops.floatingnotes.feature.tasks.ReminderScheduler
import io.github.z3275630ops.floatingnotes.feature.tasks.TaskRecurrence
import io.github.z3275630ops.floatingnotes.feature.tasks.TaskScheduleModal
import io.github.z3275630ops.floatingnotes.feature.tasks.TasksScheduleDraft
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
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintSecondaryFill
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintTertiaryTextColor
import io.github.z3275630ops.floatingnotes.ui.designsystem.rememberGlassBackdrop
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    val end: Int,
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

@OptIn(ExperimentalLayoutApi::class)
@Suppress("UNUSED_PARAMETER")
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
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val localBackdrop = backdrop ?: rememberGlassBackdrop()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val liveTags by model.organization.tags.collectAsStateWithLifecycle(initialValue = emptyList())
    val availableTags = if (tags.isNotEmpty()) tags else liveTags
    val noteTaskItems by model.taskItems.collectAsStateWithLifecycle()
    val discoveryState by model.editorDiscoveryState.collectAsStateWithLifecycle()

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
    var excerptTarget by remember { mutableStateOf<NoteEntity?>(null) }
    var pendingTriggerReplace by remember { mutableStateOf<PendingTriggerReplace?>(null) }
    var showRelatedSheet by remember { mutableStateOf(false) }
    var echoPreviewHit by remember { mutableStateOf<EvidenceHit?>(null) }

    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        model.importAttachments(session, it)
    }
    var showImport by remember { mutableStateOf(false) }
    var space by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(showImport) {
        if (showImport) space = model.attachments.availableSpace()
    }

    if (session.existingNote) {
        EditorHeaderMetadata(
            activeNote = activeNote,
            notebook = notebooks.firstOrNull { it.id == noteNotebookId },
            availableTags = availableTags,
            noteTagIds = noteTagIds,
            pendingTagIds = session.pendingTagIds,
            backlinksCount = backlinks.size,
            relatedCount = discoveryState.relatedNotes.size,
            dark = dark,
            onOpenOrganize = onOpenOrganize,
            onOpenTagPicker = { tagPickerOpen = true },
            onRemovePendingTag = session::removePendingTag,
            onOpenBacklinks = { picker = "引用此笔记的记录" },
            onOpenRelatedNotes = {
                model.refreshEditorRelatedNotes(session.draft.noteId)
                showRelatedSheet = true
            },
        )
    } else if (session.pendingTagIds.isNotEmpty() || noteTagIds.isNotEmpty() || backlinks.isNotEmpty()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
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

    fun focusEditableBlock(targetId: String, moveToEnd: Boolean = false) {
        val targetBlock = session.blocks.firstOrNull { it.id == targetId && it.editable } ?: return
        focusedBlockId = targetBlock.id
        if (moveToEnd) {
            val end = targetBlock.text.text.length
            targetBlock.text.edit { selection = TextRange(end) }
        }
        runCatching {
            blockFocusRequesters[targetBlock.id]?.requestFocus()
        }
        keyboard?.show()
        view.post {
            val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    LaunchedEffect(session.focusBlockId) {
        val targetId = session.focusBlockId ?: return@LaunchedEffect
        withFrameNanos { }
        focusBlockRequesters[targetId]?.bringIntoView()
        focusEditableBlock(targetId, moveToEnd = true)
        session.focusBlockId = null
    }

    LaunchedEffect(session.draft.id, autoFocusKeyboard) {
        if (autoFocusKeyboard && !session.existingNote && !session.writing) {
            val firstEditable = session.blocks.firstOrNull { it.editable } ?: return@LaunchedEffect
            withFrameNanos { }
            kotlinx.coroutines.delay(90)
            focusEditableBlock(firstEditable.id, moveToEnd = false)
        }
    }

    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = if (session.existingNote) 20.dp else 14.dp,
                vertical = if (session.existingNote) 8.dp else 6.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(if (session.existingNote) 8.dp else 6.dp),
    ) {
        session.blocks.forEachIndexed { index, block ->
            key(block.id) {
                val requester = remember(block.id) {
                    focusBlockRequesters.getOrPut(block.id) { BringIntoViewRequester() }
                }
                val blockFocusReq = remember(block.id) {
                    blockFocusRequesters.getOrPut(block.id) { FocusRequester() }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .bringIntoViewRequester(requester),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (block.type == "checkbox") {
                            MintCheckbox(
                                checked = block.checked,
                                enabled = block.editable && !session.writing,
                                onCheckedChange = { checked -> model.toggleEditorTask(session, block, checked) },
                                dark = dark,
                                modifier = Modifier.testTag("checkbox-$index"),
                            )
                        }
                        if (block.editable) {
                            val isHeroTitle = index == 0 && block.type == "paragraph"
                            val blockTextStyle = when {
                                isHeroTitle && session.existingNote -> MaterialTheme.typography.headlineSmall.copy(
                                    fontSize = 24.sp,
                                    lineHeight = 32.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = (-0.3).sp,
                                    color = scheme.onSurface,
                                    textDecoration = if (block.checked) TextDecoration.LineThrough else null,
                                )
                                isHeroTitle -> MaterialTheme.typography.titleMedium.copy(
                                    fontSize = 18.sp,
                                    lineHeight = 26.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = scheme.onSurface,
                                    textDecoration = if (block.checked) TextDecoration.LineThrough else null,
                                )
                                else -> MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = 17.sp,
                                    lineHeight = 28.sp,
                                    color = if (block.checked) scheme.onSurfaceVariant else scheme.onSurface,
                                    textDecoration = if (block.checked) TextDecoration.LineThrough else null,
                                )
                            }
                            BasicTextField(
                                state = block.text,
                                enabled = !session.writing,
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(blockFocusReq)
                                    .onFocusChanged { focusState ->
                                        if (focusState.isFocused) {
                                            focusedBlockId = block.id
                                        }
                                    }
                                    .padding(vertical = 8.dp, horizontal = 2.dp)
                                    .testTag(if (index == 0) "capture-editor" else "block-editor-$index"),
                                textStyle = blockTextStyle,
                                cursorBrush = SolidColor(scheme.primary),
                                decorator = TextFieldDecorator { inner ->
                                    Box(Modifier.fillMaxWidth()) {
                                        if (block.text.text.isEmpty()) {
                                            val placeholder = when {
                                                block.type == "checkbox" -> "待办事项"
                                                index == 0 && session.blocks.size == 1 -> "写点什么…  #标签 @引用"
                                                else -> ""
                                            }
                                            if (placeholder.isNotEmpty()) {
                                                Text(
                                                    text = placeholder,
                                                    color = scheme.onSurfaceVariant.copy(alpha = 0.72f),
                                                    style = blockTextStyle.copy(fontWeight = FontWeight.Normal),
                                                )
                                            }
                                        }
                                        inner()
                                    }
                                },
                            )
                        } else if (block.attachmentId != null) {
                            AttachmentRow(
                                model.attachments,
                                requireNotNull(block.attachmentId),
                                !session.writing && !session.voiceBusy,
                                insertTranscript = { text -> session.insertTranscript(block, text) },
                            ) {
                                session.removeAttachment(block)
                            }
                        } else if (block.referenceTarget != null) {
                            val rawTarget = notes.firstOrNull { it.id == block.referenceTarget }
                            val trashedMatch = trashedNotes.firstOrNull { it.id == block.referenceTarget }
                            val targetTrashed = block.referenceTarget in trashedNoteIds || trashedMatch != null
                            val target = rawTarget?.takeUnless { targetTrashed }
                            ReferenceBlockCard(
                                block = block,
                                target = target,
                                trashedTarget = targetTrashed,
                                trashedTitle = (trashedMatch ?: rawTarget?.takeIf { targetTrashed })?.let(::referenceLabel),
                                dark = dark,
                                enabled = !session.writing,
                                onOpen = if (target != null && onOpenRelated != null) {
                                    { onOpenRelated(target.id) }
                                } else null,
                                onRemove = { session.removeReference(block) },
                            )
                        } else {
                            Text(
                                text = block.text.text.toString().ifBlank { "只读区块 · ${block.type}" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }

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

                        EditorCheckboxScheduleRow(
                            dueDate = effectiveDue,
                            reminderTime = if (hasActiveReminder) effectiveTime else null,
                            recurrence = effectiveRecurrence,
                            notificationsEnabled = capability.notificationsEnabled,
                            enabled = !session.writing,
                            dark = dark,
                            onClick = {
                                focusManager.clearFocus(force = true)
                                keyboard?.hide()
                                capability = ReminderScheduler.capability(context)
                                scheduleBlockId = block.id
                            },
                        )
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 280.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = !session.writing,
                    onClick = {
                        val existingTarget = session.blocks.lastOrNull { it.editable }
                        if (existingTarget != null && blockFocusRequesters.containsKey(existingTarget.id)) {
                            focusEditableBlock(existingTarget.id, moveToEnd = true)
                        } else {
                            val tail = session.ensureTailEditableBlock()
                            session.focusBlockId = tail.id
                        }
                    },
                )
                .testTag("editor-blank-canvas"),
        ) {
            if (session.existingNote && session.blocks.size == 1 && session.blocks.first().text.text.isNotBlank()) {
                Text(
                    text = "轻点此处继续书写正文…",
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                    color = scheme.onSurfaceVariant.copy(alpha = 0.42f),
                    modifier = Modifier.padding(top = 6.dp, start = 2.dp),
                )
            }
        }
    }

    val activeBlock = session.blocks.firstOrNull { it.id == focusedBlockId && it.editable }
    val inlineTrigger = activeBlock?.let { block ->
        detectInlineTrigger(
            text = block.text.text.toString(),
            selection = block.text.selection,
            composition = block.text.composition,
        )
    }

    if (activeBlock != null && inlineTrigger != null) {
        InlineTriggerSuggestionBar(
            trigger = inlineTrigger,
            availableTags = availableTags,
            attachedTagIds = noteTagIds + session.pendingTagIds,
            candidateNotes = notes.filter { it.id != session.draft.noteId },
            dark = dark,
            onPickExistingTag = { tag ->
                val latest = detectInlineTrigger(
                    activeBlock.text.text.toString(),
                    activeBlock.text.selection,
                    activeBlock.text.composition,
                )
                if (latest != null && activeBlock.text.composition == null) {
                    activeBlock.text.edit { replace(latest.triggerIndex, latest.cursorIndex, "") }
                    model.attachTagToSession(session, tag.id)
                }
            },
            onCreateTag = { tagName ->
                val latest = detectInlineTrigger(
                    activeBlock.text.text.toString(),
                    activeBlock.text.selection,
                    activeBlock.text.composition,
                )
                if (latest != null && activeBlock.text.composition == null) {
                    activeBlock.text.edit { replace(latest.triggerIndex, latest.cursorIndex, "") }
                    val existing = availableTags.firstOrNull {
                        it.parentId.isEmpty() && it.name.equals(tagName, ignoreCase = true)
                    }
                    model.createAndAttachTag(session, tagName, existing?.id)
                }
            },
            onPickNoteFull = { targetNote ->
                val latest = detectInlineTrigger(
                    activeBlock.text.text.toString(),
                    activeBlock.text.selection,
                    activeBlock.text.composition,
                )
                if (latest != null && activeBlock.text.composition == null) {
                    activeBlock.text.edit { replace(latest.triggerIndex, latest.cursorIndex, "") }
                    session.addReference(targetNote)
                }
            },
            onPickNoteExcerpt = { targetNote ->
                val latest = detectInlineTrigger(
                    activeBlock.text.text.toString(),
                    activeBlock.text.selection,
                    activeBlock.text.composition,
                )
                pendingTriggerReplace = latest?.let {
                    PendingTriggerReplace(activeBlock.id, it.triggerIndex, it.cursorIndex)
                }
                excerptTarget = targetNote
                picker = "引用笔记"
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
            if (session.importing) TextButton(onClick = { model.cancelImport(session) }) { Text("取消导入") }
        }
    }

    if (session.status.isNotEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = session.status,
                color = if ("失败" in session.status || "未开启" in session.status) scheme.error else scheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            if ("失败" in session.status && !session.writing) {
                TextButton(
                    onClick = onSave,
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
        echoBlock?.text?.text?.toString().orEmpty()
    }
    val echoCurrentText = echoBlock?.text?.text?.toString().orEmpty()
    val echoIsComposing = echoBlock?.text?.composition != null
    LaunchedEffect(session.draft.id, echoBlock?.id, echoCurrentText, echoIsComposing) {
        if (echoBlock != null && echoCurrentText != echoInitialText) {
            model.onEditorTextActivity(
                noteId = if (session.existingNote) session.draft.noteId else null,
                blockText = echoCurrentText,
                isComposing = echoIsComposing,
            )
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

    HorizontalDivider(
        thickness = 0.5.dp,
        color = scheme.outlineVariant.copy(alpha = 0.35f),
        modifier = Modifier.padding(top = 4.dp),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = if (session.existingNote) 8.dp else 0.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EditorAccessoryPill(
            icon = MintIcons.Checklist,
            label = "添加待办",
            enabled = !session.writing,
            dark = dark,
            onClick = session::addCheckbox,
        )
        EditorAccessoryPill(
            icon = MintIcons.Paperclip,
            label = if (session.importFailed) "重选" else "附件",
            enabled = !session.writing && !session.importing && !session.voiceBusy,
            dark = dark,
            onClick = { showImport = true },
        )
        if (!session.recording && !session.voicePending) {
            EditorAccessoryPill(
                icon = MintIcons.Audio,
                label = "录音",
                enabled = !session.writing && !session.importing && !session.voiceBusy,
                dark = dark,
                onClick = { microphone.launch(Manifest.permission.RECORD_AUDIO) },
            )
        }
        if (onOpenRelated != null && notes.any { it.id != session.draft.noteId && it.id !in trashedNoteIds }) {
            EditorAccessoryPill(
                icon = MintIcons.AtSign,
                label = "@ 引用",
                enabled = !session.writing,
                dark = dark,
                onClick = {
                    excerptTarget = null
                    pendingTriggerReplace = null
                    picker = "引用笔记"
                },
            )
        }
        EditorAccessoryPill(
            icon = MintIcons.Tag,
            label = "标签",
            enabled = !session.writing,
            dark = dark,
            onClick = { tagPickerOpen = true },
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
                clearPendingInlineTrigger(session, pendingTriggerReplace)
                pendingTriggerReplace = null
                picker = null
                excerptTarget = null
                if (mode == "引用笔记") session.addReference(note) else onOpenRelated?.invoke(note.id)
            },
            onPickExcerpt = { note, blockId, start, end, labelOverride ->
                clearPendingInlineTrigger(session, pendingTriggerReplace)
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

private fun clearPendingInlineTrigger(session: EditorSession, pending: PendingTriggerReplace?) {
    if (pending == null) return
    val block = session.blocks.firstOrNull { it.id == pending.blockId && it.editable } ?: return
    if (block.text.composition != null) return
    val currentText = block.text.text.toString()
    if (pending.start in 0..pending.end && pending.end <= currentText.length) {
        val slice = currentText.substring(pending.start, pending.end)
        if (slice.startsWith("@") || slice.startsWith("#")) {
            block.text.edit { replace(pending.start, pending.end, "") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorHeaderMetadata(
    activeNote: NoteEntity?,
    notebook: NotebookEntity?,
    availableTags: List<TagEntity>,
    noteTagIds: Set<String>,
    pendingTagIds: List<String>,
    backlinksCount: Int,
    relatedCount: Int = 0,
    dark: Boolean,
    onOpenOrganize: (() -> Unit)?,
    onOpenTagPicker: () -> Unit,
    onRemovePendingTag: (String) -> Unit,
    onOpenBacklinks: () -> Unit,
    onOpenRelatedNotes: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val allTagIds = (noteTagIds + pendingTagIds).toList()
    val hasTokens = notebook != null || allTagIds.isNotEmpty() || backlinksCount > 0
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (activeNote != null) {
            val updated = remember(activeNote.updatedAt) { formatNoteTimestamp(activeNote.updatedAt) }
            val created = remember(activeNote.createdAt) { formatNoteTimestamp(activeNote.createdAt) }
            val timeSummary = if (updated == created) updated else "编辑于 $updated · 创建于 $created"
            Text(
                text = timeSummary,
                style = MaterialTheme.typography.labelSmall,
                color = mintTertiaryTextColor(dark),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (hasTokens) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
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
            val matches = availableTags.filter {
                it.id !in attachedTagIds &&
                    (query.isEmpty() || it.name.contains(query, ignoreCase = true))
            }.take(8)
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
            val matches = candidateNotes.filter {
                query.isEmpty() || referenceLabel(it).contains(query, ignoreCase = true) ||
                    it.searchText.contains(query, ignoreCase = true)
            }.take(6)

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
                    } else {
                        Modifier
                    },
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
                        text = "[${block.referenceStart}, ${block.referenceEnd})",
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
            .padding(start = 48.dp, bottom = 2.dp)
            .clip(shape)
            .background(bg, shape)
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
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    val fg = if (enabled) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = 0.45f)
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 5.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) scheme.primary else fg, modifier = Modifier.size(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = fg,
            maxLines = 1,
            softWrap = false,
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
            val filtered = candidates.filter { note ->
                query.isBlank() ||
                    referenceLabel(note).contains(query, ignoreCase = true) ||
                    note.searchText.contains(query, ignoreCase = true)
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
                        item {
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
                        item {
                            MintGroupedSection(dark = dark, containerColor = mintCardSurface(dark)) {
                                filtered.forEachIndexed { index, note ->
                                    if (index > 0) MintGroupedDivider(dark = dark)
                                    val hasBlocks = remember(note.documentJson) {
                                        runCatching {
                                            NoteDocument.decode(note.documentJson).blocks.any { it.text.isNotEmpty() }
                                        }.getOrDefault(false)
                                    }
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
                            text = "UTF-16 选区 [$safeStart, $safeEnd) · ${safeEnd - safeStart}/${sourceText.length}",
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
) {
    val scheme = MaterialTheme.colorScheme
    val filter = rememberTextFieldState()
    val query = filter.text.toString().trim()
    val matches = availableTags.filter {
        query.isEmpty() || it.name.contains(query, ignoreCase = true)
    }
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
                    item {
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
                    item {
                        MintGroupedSection(dark = dark, containerColor = mintCardSurface(dark)) {
                            matches.forEachIndexed { index, tag ->
                                if (index > 0) MintGroupedDivider(dark = dark)
                                val alreadyAttached = tag.id in selectedTagIds
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
