package io.github.z3275630ops.floatingnotes.feature.tasks

import android.content.Context
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.z3275630ops.floatingnotes.data.local.NotesRepository
import io.github.z3275630ops.floatingnotes.data.local.RecurrenceRule
import io.github.z3275630ops.floatingnotes.data.local.ReminderPolicy
import io.github.z3275630ops.floatingnotes.data.local.ReminderStatus
import io.github.z3275630ops.floatingnotes.data.local.TaskItemProjection
import io.github.z3275630ops.floatingnotes.data.local.TaskOccurrenceEntity
import io.github.z3275630ops.floatingnotes.data.local.TaskPreferences
import io.github.z3275630ops.floatingnotes.data.local.TaskToggleResult
import io.github.z3275630ops.floatingnotes.ui.designsystem.GlassAction
import io.github.z3275630ops.floatingnotes.ui.designsystem.GlassBackdrop
import io.github.z3275630ops.floatingnotes.ui.designsystem.GlassSurface
import io.github.z3275630ops.floatingnotes.ui.designsystem.GlassTier
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintChip
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintChipStyle
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGlassDialog
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintPillButton
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintTextField
import io.github.z3275630ops.floatingnotes.ui.designsystem.glassSource
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintAmbient
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintAnimatorsEnabled
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintSpringSpec
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class TasksScheduleDraft(
    val noteId: String,
    val blockId: String,
    val taskText: String,
    val reminderId: String?,
    val reminderStatus: ReminderStatus?,
    val dueDateText: String,
    val reminderEnabled: Boolean,
    val reminderTimeText: String,
    val recurrence: RecurrenceRule,
    val policy: ReminderPolicy,
) {
    companion object {
        fun fromItem(item: TaskItemProjection, today: LocalDate): TasksScheduleDraft = TasksScheduleDraft(
            noteId = item.noteId,
            blockId = item.blockId,
            taskText = item.text.ifBlank { item.notePreview },
            reminderId = item.reminderId,
            reminderStatus = item.reminderStatus,
            dueDateText = item.dueDate.orEmpty(),
            reminderEnabled = item.reminderId != null &&
                item.reminderStatus != ReminderStatus.CANCELLED &&
                item.reminderLocalTime != null,
            reminderTimeText = item.reminderLocalTime ?: "09:00",
            recurrence = item.recurrence,
            policy = item.policy,
        )
    }
}

@Composable
internal fun TasksScreen(
    repository: NotesRepository,
    preferences: TaskPreferences,
    backdrop: GlassBackdrop,
    tier: GlassTier,
    dark: Boolean,
    onOpenNote: (noteId: String, blockId: String?) -> Unit,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    capabilityProvider: (Context) -> ReminderCapability = ReminderScheduler::capability,
    onRequestNotificationPermission: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    val items by repository.observeTaskItems().collectAsState(initial = emptyList())
    val occurrences by repository.observeCompletedOccurrences().collectAsState(initial = emptyList())
    val todoStartupEnabled by preferences.observeTodoStartupEnabled().collectAsState(
        initial = preferences.todoStartupEnabled,
    )
    val exactAlarmDismissed by preferences.observeExactAlarmGuidanceDismissed().collectAsState(
        initial = preferences.exactAlarmGuidanceDismissed,
    )

    var selectedFilter by remember { mutableStateOf(TasksFilter.TODAY) }
    var capability by remember { mutableStateOf(capabilityProvider(context)) }
    var statusBanner by remember { mutableStateOf<String?>(null) }
    var editingDraft by remember { mutableStateOf<TasksScheduleDraft?>(null) }

    DisposableEffect(lifecycleOwner, context, capabilityProvider) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                capability = capabilityProvider(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val openNotificationSettings = {
        val intent = ReminderScheduler.openNotificationSettingsIntent(context)
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching { context.startActivity(ReminderScheduler.openAppDetailsIntent(context)) }
        }
        Unit
    }

    val requestNotifications = onRequestNotificationPermission ?: openNotificationSettings

    val openExactAlarmSettings = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { context.startActivity(ReminderScheduler.openExactAlarmSettingsIntent(context)) }
                .onFailure {
                    runCatching { context.startActivity(ReminderScheduler.openAppDetailsIntent(context)) }
                }
        }
    }

    TasksScreenContent(
        items = items,
        occurrences = occurrences,
        selectedFilter = selectedFilter,
        onSelectFilter = { selectedFilter = it },
        todoStartupEnabled = todoStartupEnabled,
        onToggleTodoStartup = { enabled -> preferences.todoStartupEnabled = enabled },
        capability = capability,
        exactAlarmGuidanceDismissed = exactAlarmDismissed,
        onDismissExactAlarmGuidance = { preferences.exactAlarmGuidanceDismissed = true },
        onOpenNotificationSettings = requestNotifications,
        onOpenExactAlarmSettings = openExactAlarmSettings,
        statusFeedback = statusBanner,
        onDismissStatusFeedback = { statusBanner = null },
        editingDraft = editingDraft,
        onEditSchedule = { item ->
            editingDraft = TasksScheduleDraft.fromItem(item, LocalDate.now())
        },
        onDismissScheduleEditor = { editingDraft = null },
        onSaveSchedule = { draft ->
            val parsedDue = TaskRecurrence.parseDueDate(draft.dueDateText)
            val parsedTime = if (draft.reminderEnabled) TaskRecurrence.parseLocalTime(draft.reminderTimeText) else null
            if (draft.dueDateText.isNotBlank() && parsedDue == null) {
                statusBanner = "截止日期格式需为 YYYY-MM-DD"
                return@TasksScreenContent
            }
            if (draft.reminderEnabled && parsedTime == null) {
                statusBanner = "提醒时间格式需为 HH:mm"
                return@TasksScreenContent
            }
            editingDraft = null
            val currentZone = ZoneId.systemDefault()
            val needsNotificationGrant = parsedTime != null && !capability.notificationsEnabled
            if (needsNotificationGrant) {
                requestNotifications()
            }
            scope.launch {
                val result = repository.setTaskSchedule(
                    noteId = draft.noteId,
                    blockId = draft.blockId,
                    dueDate = parsedDue?.toString(),
                    reminderTime = parsedTime?.let(TaskRecurrence::formatLocalTime),
                    zoneId = currentZone,
                    policy = draft.policy,
                    recurrence = if (parsedTime != null) draft.recurrence else RecurrenceRule.NONE,
                )
                ReminderScheduler.syncScheduleResult(context, result)
                statusBanner = when {
                    needsNotificationGrant -> "已保存待办，若未授予通知权限则暂不弹窗"
                    parsedTime != null -> "已设置提醒"
                    parsedDue != null -> "已更新截止日期"
                    else -> "已更新待办排期"
                }
            }
        },
        onClearSchedule = { draft ->
            editingDraft = null
            scope.launch {
                val result = repository.clearTaskSchedule(draft.noteId, draft.blockId)
                ReminderScheduler.syncScheduleResult(context, result)
                statusBanner = "已清除日期与提醒"
            }
        },
        onSnoozeReminder = { reminderId ->
            editingDraft = null
            scope.launch {
                val snoozed = repository.snoozeReminder(reminderId, snoozeMinutes = 15L)
                if (snoozed != null) {
                    ReminderScheduler.scheduleReminder(context, snoozed)
                    statusBanner = "已延后 15 分钟提醒"
                }
            }
        },
        onToggleTask = { item ->
            scope.launch {
                val result = repository.toggleTaskChecked(
                    noteId = item.noteId,
                    blockId = item.blockId,
                    checked = !item.checked,
                )
                if (result is TaskToggleResult.Updated) {
                    ReminderScheduler.syncToggleResult(context, result)
                    statusBanner = result.advancedToDate?.let { next ->
                        "已完成本次重复任务，已推进至 $next"
                    }
                }
            }
        },
        onOpenNote = onOpenNote,
        onClose = onClose,
        backdrop = backdrop,
        tier = tier,
        dark = dark,
        modifier = modifier,
        onRequestNotificationPermission = requestNotifications,
    )
}

@Composable
internal fun TasksScreenContent(
    items: List<TaskItemProjection>,
    occurrences: List<TaskOccurrenceEntity>,
    selectedFilter: TasksFilter,
    onSelectFilter: (TasksFilter) -> Unit,
    todoStartupEnabled: Boolean,
    onToggleTodoStartup: (Boolean) -> Unit,
    capability: ReminderCapability,
    exactAlarmGuidanceDismissed: Boolean,
    onDismissExactAlarmGuidance: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    statusFeedback: String?,
    onDismissStatusFeedback: () -> Unit,
    editingDraft: TasksScheduleDraft?,
    onEditSchedule: (TaskItemProjection) -> Unit,
    onDismissScheduleEditor: () -> Unit,
    onSaveSchedule: (TasksScheduleDraft) -> Unit,
    onClearSchedule: (TasksScheduleDraft) -> Unit,
    onSnoozeReminder: (String) -> Unit,
    onToggleTask: (TaskItemProjection) -> Unit,
    onOpenNote: (noteId: String, blockId: String?) -> Unit,
    onClose: (() -> Unit)?,
    backdrop: GlassBackdrop,
    tier: GlassTier,
    dark: Boolean,
    modifier: Modifier = Modifier,
    zoneId: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zoneId),
    onRequestNotificationPermission: (() -> Unit)? = null,
) {
    val activeRemindersCount = remember(items) {
        items.count { !it.checked && it.reminderId != null && it.reminderStatus == ReminderStatus.ACTIVE }
    }
    val classified = remember(items, today, zoneId) {
        items.map { item ->
            val due = TaskRecurrence.parseDueDate(item.dueDate)
            val remDate = TaskRecurrence.parseDueDate(item.reminderLocalDate)
            val bucket = TaskRecurrence.classifyTask(due, remDate, item.checked, today)
            bucket to item
        }
    }
    val overdueItems = remember(classified) {
        classified.filter { it.first == TaskBucket.OVERDUE }.map { it.second }
    }
    val todayItems = remember(classified) {
        classified.filter { it.first == TaskBucket.TODAY }.map { it.second }
    }
    val unscheduledItems = remember(classified) {
        classified.filter { it.first == TaskBucket.UNSCHEDULED }.map { it.second }
    }
    val upcomingItems = remember(classified, zoneId) {
        classified.filter { it.first == TaskBucket.UPCOMING }
            .map { it.second }
            .sortedBy {
                TaskRecurrence.effectiveLocalDate(
                    it.dueDate,
                    it.reminderLocalDate,
                    it.triggerInstantMs,
                    zoneId,
                ) ?: LocalDate.MAX
            }
    }
    val completedItems = remember(classified) {
        classified.filter { it.first == TaskBucket.COMPLETED }.map { it.second }
    }
    val completedBlockKeys = remember(completedItems) {
        completedItems.mapTo(mutableSetOf()) { it.noteId to it.blockId }
    }
    val extraOccurrences = remember(occurrences, completedBlockKeys) {
        occurrences.filter { occ ->
            occ.recurrence != RecurrenceRule.NONE.name || (occ.noteId to occ.blockId) !in completedBlockKeys
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("tasks_screen"),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .mintAmbient(dark)
                .glassSource(backdrop),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TasksHeaderBar(
                todayCount = overdueItems.size + todayItems.size + unscheduledItems.size,
                todoStartupEnabled = todoStartupEnabled,
                onToggleTodoStartup = onToggleTodoStartup,
                onClose = onClose,
                backdrop = backdrop,
                tier = tier,
                dark = dark,
            )

            TasksSegmentedFilter(
                selected = selectedFilter,
                onSelect = onSelectFilter,
                counts = mapOf(
                    TasksFilter.TODAY to (overdueItems.size + todayItems.size + unscheduledItems.size),
                    TasksFilter.UPCOMING to upcomingItems.size,
                    TasksFilter.COMPLETED to (completedItems.size + extraOccurrences.size),
                ),
                backdrop = backdrop,
                tier = tier,
                dark = dark,
            )

            if (!capability.notificationsEnabled) {
                PermissionGuidanceBanner(
                    title = "提醒未开启",
                    message = "通知权限未开启，到期不会弹出提醒；待办项、截止日期与笔记清单仍可正常保存。",
                    actionLabel = "开启通知",
                    dismissLabel = null,
                    onAction = onOpenNotificationSettings,
                    onDismiss = null,
                    backdrop = backdrop,
                    tier = tier,
                    dark = dark,
                    testTag = "tasks_notification_banner",
                    actionTag = "tasks_open_notification_settings",
                )
            } else if (!capability.exactAlarmAllowed && !exactAlarmGuidanceDismissed && activeRemindersCount > 0) {
                PermissionGuidanceBanner(
                    title = "精确定时权限未开启",
                    message = "系统可能将提醒延迟数分钟送达，可前往开启闹钟与提醒权限。",
                    actionLabel = "前往开启",
                    dismissLabel = "暂不提醒",
                    onAction = onOpenExactAlarmSettings,
                    onDismiss = onDismissExactAlarmGuidance,
                    backdrop = backdrop,
                    tier = tier,
                    dark = dark,
                    testTag = "tasks_exact_alarm_banner",
                    actionTag = "tasks_open_exact_alarm_settings",
                )
            }

            AnimatedVisibility(visible = statusFeedback != null) {
                statusFeedback?.let { message ->
                    GlassSurface(
                        backdrop = backdrop,
                        tier = tier,
                        dark = dark,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            MintChip(
                                label = "知道了",
                                onClick = onDismissStatusFeedback,
                                dark = dark,
                                style = MintChipStyle.Standard,
                            )
                        }
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (selectedFilter) {
                    TasksFilter.TODAY -> {
                        if (overdueItems.isEmpty() && todayItems.isEmpty() && unscheduledItems.isEmpty()) {
                            item("empty_today") {
                                TasksEmptyCard(
                                    title = "今天没有待办",
                                    subtitle = "在笔记正文添加复选框，或从「未来」调整日期后会自动出现在这里。",
                                    backdrop = backdrop,
                                    tier = tier,
                                    dark = dark,
                                )
                            }
                        } else {
                            if (overdueItems.isNotEmpty()) {
                                item("hdr_overdue") {
                                    TaskSectionHeader("已逾期 · ${overdueItems.size}", overdue = true)
                                }
                                items(overdueItems, key = { "ov_${it.noteId}_${it.blockId}" }) { item ->
                                    TaskRowCard(
                                        item = item,
                                        today = today,
                                        zoneId = zoneId,
                                        notificationsEnabled = capability.notificationsEnabled,
                                        isOverdue = true,
                                        onToggle = { onToggleTask(item) },
                                        onOpenNote = { onOpenNote(item.noteId, item.blockId) },
                                        onEditSchedule = { onEditSchedule(item) },
                                        backdrop = backdrop,
                                        tier = tier,
                                        dark = dark,
                                    )
                                }
                            }
                            if (todayItems.isNotEmpty()) {
                                item("hdr_today") {
                                    TaskSectionHeader("今天 · ${todayItems.size}", overdue = false)
                                }
                                items(todayItems, key = { "td_${it.noteId}_${it.blockId}" }) { item ->
                                    TaskRowCard(
                                        item = item,
                                        today = today,
                                        zoneId = zoneId,
                                        notificationsEnabled = capability.notificationsEnabled,
                                        isOverdue = false,
                                        onToggle = { onToggleTask(item) },
                                        onOpenNote = { onOpenNote(item.noteId, item.blockId) },
                                        onEditSchedule = { onEditSchedule(item) },
                                        backdrop = backdrop,
                                        tier = tier,
                                        dark = dark,
                                    )
                                }
                            }
                            if (unscheduledItems.isNotEmpty()) {
                                item("hdr_unscheduled") {
                                    TaskSectionHeader("未设日期 · ${unscheduledItems.size}", overdue = false)
                                }
                                items(unscheduledItems, key = { "un_${it.noteId}_${it.blockId}" }) { item ->
                                    TaskRowCard(
                                        item = item,
                                        today = today,
                                        zoneId = zoneId,
                                        notificationsEnabled = capability.notificationsEnabled,
                                        isOverdue = false,
                                        onToggle = { onToggleTask(item) },
                                        onOpenNote = { onOpenNote(item.noteId, item.blockId) },
                                        onEditSchedule = { onEditSchedule(item) },
                                        backdrop = backdrop,
                                        tier = tier,
                                        dark = dark,
                                    )
                                }
                            }
                        }
                    }

                    TasksFilter.UPCOMING -> {
                        if (upcomingItems.isEmpty()) {
                            item("empty_upcoming") {
                                TasksEmptyCard(
                                    title = "暂无未来排期待办",
                                    subtitle = "点击任意待办的「排期」，即可设置未来截止日期与定时提醒。",
                                    backdrop = backdrop,
                                    tier = tier,
                                    dark = dark,
                                )
                            }
                        } else {
                            items(upcomingItems, key = { "up_${it.noteId}_${it.blockId}" }) { item ->
                                TaskRowCard(
                                    item = item,
                                    today = today,
                                    zoneId = zoneId,
                                    notificationsEnabled = capability.notificationsEnabled,
                                    isOverdue = false,
                                    onToggle = { onToggleTask(item) },
                                    onOpenNote = { onOpenNote(item.noteId, item.blockId) },
                                    onEditSchedule = { onEditSchedule(item) },
                                    backdrop = backdrop,
                                    tier = tier,
                                    dark = dark,
                                )
                            }
                        }
                    }

                    TasksFilter.COMPLETED -> {
                        if (completedItems.isEmpty() && extraOccurrences.isEmpty()) {
                            item("empty_completed") {
                                TasksEmptyCard(
                                    title = "还没有已完成待办",
                                    subtitle = "勾选待办或在提醒通知点击「完成」后，会保留在这里以便核对。",
                                    backdrop = backdrop,
                                    tier = tier,
                                    dark = dark,
                                )
                            }
                        } else {
                            if (completedItems.isNotEmpty()) {
                                item("hdr_completed") {
                                    TaskSectionHeader("已完成待办 · ${completedItems.size}", overdue = false)
                                }
                                items(completedItems, key = { "done_${it.noteId}_${it.blockId}" }) { item ->
                                    TaskRowCard(
                                        item = item,
                                        today = today,
                                        zoneId = zoneId,
                                        notificationsEnabled = capability.notificationsEnabled,
                                        isOverdue = false,
                                        onToggle = { onToggleTask(item) },
                                        onOpenNote = { onOpenNote(item.noteId, item.blockId) },
                                        onEditSchedule = { onEditSchedule(item) },
                                        backdrop = backdrop,
                                        tier = tier,
                                        dark = dark,
                                    )
                                }
                            }
                            if (extraOccurrences.isNotEmpty()) {
                                item("hdr_occ") {
                                    TaskSectionHeader("循环任务完成记录 · ${extraOccurrences.size}", overdue = false)
                                }
                                items(extraOccurrences, key = { "occ_${it.id}" }) { occ ->
                                    CompletedOccurrenceCard(
                                        occurrence = occ,
                                        zoneId = zoneId,
                                        onOpenNote = { onOpenNote(occ.noteId, occ.blockId) },
                                        backdrop = backdrop,
                                        tier = tier,
                                        dark = dark,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (editingDraft != null) {
            TaskScheduleModal(
                initial = editingDraft,
                notificationsEnabled = capability.notificationsEnabled,
                onDismiss = onDismissScheduleEditor,
                onSave = onSaveSchedule,
                onClear = { onClearSchedule(editingDraft) },
                onSnooze = onSnoozeReminder,
                backdrop = backdrop,
                tier = tier,
                dark = dark,
                zoneId = zoneId,
                today = today,
                onRequestNotificationPermission = onRequestNotificationPermission,
            )
        }
    }
}

@Composable
private fun TasksHeaderBar(
    todayCount: Int,
    todoStartupEnabled: Boolean,
    onToggleTodoStartup: (Boolean) -> Unit,
    onClose: (() -> Unit)?,
    backdrop: GlassBackdrop,
    tier: GlassTier,
    dark: Boolean,
) {
    GlassSurface(
        backdrop = backdrop,
        tier = tier,
        dark = dark,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "待办",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            text = "$todayCount 项待处理",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
                if (onClose != null) {
                    GlassAction(
                        label = "返回笔记",
                        dark = dark,
                        onClick = onClose,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "启动默认进入待办",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MintCapsuleSwitch(
                    checked = todoStartupEnabled,
                    onCheckedChange = onToggleTodoStartup,
                    dark = dark,
                    modifier = Modifier.testTag("tasks_startup_toggle"),
                )
            }
        }
    }
}

@Composable
private fun TasksSegmentedFilter(
    selected: TasksFilter,
    onSelect: (TasksFilter) -> Unit,
    counts: Map<TasksFilter, Int>,
    backdrop: GlassBackdrop,
    tier: GlassTier,
    dark: Boolean,
) {
    val trackColor = if (dark) Color(0xFF172720) else Color(0xFFE7F0EA)
    val thumbColor = if (dark) Color(0xFF285845) else Color(0xFFFFFFFF)
    val borderColor = if (dark) Color(0xFF2D4A3D) else Color(0xFFCDE2D6)
    val scheme = MaterialTheme.colorScheme

    GlassSurface(
        backdrop = backdrop,
        tier = tier,
        dark = dark,
        shapeRadius = 26.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .background(trackColor.copy(alpha = 0.72f), RoundedCornerShape(26.dp))
                .border(1.dp, borderColor, RoundedCornerShape(26.dp))
                .padding(5.dp),
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(38.dp)) {
                val options = TasksFilter.entries
                val segmentWidth = maxWidth / options.size
                val safeIndex = options.indexOf(selected).coerceAtLeast(0)
                val animatedOffset by animateDpAsState(
                    targetValue = segmentWidth * safeIndex,
                    animationSpec = mintSpringSpec(),
                    label = "tasks-segment-offset",
                )
                val activeOffset = if (mintAnimatorsEnabled()) animatedOffset else segmentWidth * safeIndex

                Box(
                    modifier = Modifier
                        .offset(x = activeOffset)
                        .width(segmentWidth)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(thumbColor, RoundedCornerShape(50))
                        .border(
                            width = 0.8.dp,
                            color = if (dark) Color(0xFF53A884).copy(alpha = 0.45f)
                            else scheme.primary.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(50),
                        ),
                )

                Row(modifier = Modifier.fillMaxSize()) {
                    options.forEach { filter ->
                        val isSelected = filter == selected
                        val count = counts[filter] ?: 0
                        val interactionSource = remember { MutableInteractionSource() }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(50))
                                .selectable(
                                    selected = isSelected,
                                    interactionSource = interactionSource,
                                    indication = null,
                                    role = Role.Tab,
                                    onClick = { onSelect(filter) },
                                )
                                .padding(horizontal = 6.dp)
                                .testTag("tasks_filter_${filter.name.lowercase()}"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "${filter.label} ($count)",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                ),
                                color = if (isSelected) scheme.onSurface else scheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionGuidanceBanner(
    title: String,
    message: String,
    actionLabel: String,
    dismissLabel: String?,
    onAction: () -> Unit,
    onDismiss: (() -> Unit)?,
    backdrop: GlassBackdrop,
    tier: GlassTier,
    dark: Boolean,
    testTag: String,
    actionTag: String,
) {
    GlassSurface(
        backdrop = backdrop,
        tier = tier,
        dark = dark,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = if (dark) 0.42f else 0.65f),
                )
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MintPillButton(
                    label = actionLabel,
                    onClick = onAction,
                    dark = dark,
                    primary = true,
                    modifier = Modifier.testTag(actionTag),
                )
                if (dismissLabel != null && onDismiss != null) {
                    MintPillButton(
                        label = dismissLabel,
                        onClick = onDismiss,
                        dark = dark,
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskSectionHeader(title: String, overdue: Boolean) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

@Composable
private fun TaskRowCard(
    item: TaskItemProjection,
    today: LocalDate,
    zoneId: ZoneId,
    notificationsEnabled: Boolean,
    isOverdue: Boolean,
    onToggle: () -> Unit,
    onOpenNote: () -> Unit,
    onEditSchedule: () -> Unit,
    backdrop: GlassBackdrop,
    tier: GlassTier,
    dark: Boolean,
) {
    val parsedDue = TaskRecurrence.parseDueDate(item.dueDate)
    val hasActiveReminder = item.reminderId != null &&
        item.reminderStatus != ReminderStatus.CANCELLED &&
        item.reminderStatus != ReminderStatus.COMPLETED &&
        item.reminderLocalTime != null
    val rowInteraction = remember { MutableInteractionSource() }

    GlassSurface(
        backdrop = backdrop,
        tier = tier,
        dark = dark,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("task_row_${item.noteId}_${item.blockId}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = rowInteraction,
                    indication = null,
                    onClick = onOpenNote,
                )
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TaskCircleCheckbox(
                checked = item.checked,
                onClick = onToggle,
                modifier = Modifier.testTag("task_toggle_${item.noteId}_${item.blockId}"),
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = item.text.ifBlank { "未命名待办" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (item.checked) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    textDecoration = if (item.checked) TextDecoration.LineThrough else TextDecoration.None,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "来自：${item.notePreview}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (item.noteArchived) {
                        MetaBadge(
                            label = "已归档",
                            container = MaterialTheme.colorScheme.secondaryContainer,
                            content = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.testTag("task_archived_badge_${item.noteId}"),
                        )
                    }
                }

                if (parsedDue != null || hasActiveReminder || item.recurrence != RecurrenceRule.NONE) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (parsedDue != null) {
                            MetaBadge(
                                label = "截止 ${formatFriendlyDate(parsedDue, today)}",
                                container = if (isOverdue && !item.checked) {
                                    MaterialTheme.colorScheme.errorContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                content = if (isOverdue && !item.checked) {
                                    MaterialTheme.colorScheme.onErrorContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.testTag("task_due_badge_${item.noteId}_${item.blockId}"),
                            )
                        }
                        if (hasActiveReminder) {
                            val snoozedMs = item.snoozedUntilMs
                            val timeLabel = if (snoozedMs != null) {
                                val snoozedLocal = Instant.ofEpochMilli(snoozedMs).atZone(zoneId)
                                "已延后至 ${TIME_FORMAT.format(snoozedLocal.toLocalTime())}"
                            } else {
                                val remDate = TaskRecurrence.parseDueDate(item.reminderLocalDate)
                                val datePart = remDate?.let { "${formatFriendlyDate(it, today)} " }.orEmpty()
                                "提醒 $datePart${item.reminderLocalTime}"
                            }
                            val suffix = if (!notificationsEnabled) "（提醒未开启）" else ""
                            MetaBadge(
                                label = timeLabel + suffix,
                                container = if (notificationsEnabled) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.tertiaryContainer
                                },
                                content = if (notificationsEnabled) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onTertiaryContainer
                                },
                                modifier = Modifier.testTag("task_reminder_badge_${item.noteId}_${item.blockId}"),
                            )
                        }
                        if (item.recurrence != RecurrenceRule.NONE) {
                            MetaBadge(
                                label = item.recurrence.label,
                                container = MaterialTheme.colorScheme.secondaryContainer,
                                content = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                        if (hasActiveReminder && item.policy == ReminderPolicy.KEEP_ORIGIN_ZONE) {
                            MetaBadge(
                                label = item.policy.label,
                                container = MaterialTheme.colorScheme.surfaceVariant,
                                content = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (!item.checked) {
                GlassAction(
                    label = if (parsedDue != null || hasActiveReminder) "改期" else "排期",
                    dark = dark,
                    modifier = Modifier.testTag("task_schedule_${item.noteId}_${item.blockId}"),
                    onClick = onEditSchedule,
                )
            }
        }
    }
}

@Composable
private fun CompletedOccurrenceCard(
    occurrence: TaskOccurrenceEntity,
    zoneId: ZoneId,
    onOpenNote: () -> Unit,
    backdrop: GlassBackdrop,
    tier: GlassTier,
    dark: Boolean,
) {
    val completedLabel = remember(occurrence.completedAt, zoneId) {
        runCatching {
            val zdt = Instant.parse(occurrence.completedAt).atZone(zoneId)
            "${zdt.monthValue}/${zdt.dayOfMonth} ${TIME_FORMAT.format(zdt.toLocalTime())}"
        }.getOrDefault(occurrence.completedAt)
    }
    val interactionSource = remember { MutableInteractionSource() }
    GlassSurface(
        backdrop = backdrop,
        tier = tier,
        dark = dark,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("task_occurrence_${occurrence.id}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onOpenNote,
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = occurrence.titleSnapshot.ifBlank { "已完成重复待办" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textDecoration = TextDecoration.LineThrough,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val rule = RecurrenceRule.fromStored(occurrence.recurrence)
                    if (rule != RecurrenceRule.NONE) {
                        MetaBadge(
                            label = rule.label,
                            container = MaterialTheme.colorScheme.secondaryContainer,
                            content = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    MetaBadge(
                        label = "完成于 $completedLabel",
                        container = MaterialTheme.colorScheme.surfaceVariant,
                        content = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TasksEmptyCard(
    title: String,
    subtitle: String,
    backdrop: GlassBackdrop,
    tier: GlassTier,
    dark: Boolean,
) {
    GlassSurface(
        backdrop = backdrop,
        tier = tier,
        dark = dark,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("tasks_empty_state"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TaskCircleCheckbox(
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    val checkColor = MaterialTheme.colorScheme.onPrimary
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Checkbox,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(22.dp)) {
            if (checked) {
                drawCircle(color = primary)
                drawLine(
                    color = checkColor,
                    start = Offset(size.width * 0.26f, size.height * 0.52f),
                    end = Offset(size.width * 0.44f, size.height * 0.70f),
                    strokeWidth = 2.4.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = checkColor,
                    start = Offset(size.width * 0.44f, size.height * 0.70f),
                    end = Offset(size.width * 0.76f, size.height * 0.32f),
                    strokeWidth = 2.4.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            } else {
                drawCircle(
                    color = outline,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
    }
}

@Composable
private fun MintCapsuleSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val targetTrack = when {
        checked && dark -> Color(0xFF2E7657)
        checked -> scheme.primary
        dark -> Color(0xFF23382D)
        else -> Color(0xFFD5E4DA)
    }
    val trackColor by animateColorAsState(
        targetValue = targetTrack,
        animationSpec = mintSpringSpec(),
        label = "mint-switch-track",
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 21.dp else 3.dp,
        animationSpec = mintSpringSpec(),
        label = "mint-switch-thumb",
    )
    val activeTrack = if (mintAnimatorsEnabled()) trackColor else targetTrack
    val activeOffset = if (mintAnimatorsEnabled()) thumbOffset else if (checked) 21.dp else 3.dp

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 52.dp, minHeight = 48.dp)
            .toggleable(
                value = checked,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(50.dp)
                .height(30.dp)
                .clip(RoundedCornerShape(50))
                .background(activeTrack, RoundedCornerShape(50))
                .border(
                    width = 1.dp,
                    color = if (checked) Color.White.copy(alpha = 0.25f) else scheme.outline.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(50),
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .offset(x = activeOffset)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color.White, CircleShape),
            )
        }
    }
}

@Composable
private fun MetaBadge(
    label: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = container,
        modifier = modifier,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TaskScheduleModal(
    initial: TasksScheduleDraft,
    notificationsEnabled: Boolean,
    onDismiss: () -> Unit,
    onSave: (TasksScheduleDraft) -> Unit,
    onClear: () -> Unit,
    onSnooze: (String) -> Unit,
    backdrop: GlassBackdrop,
    @Suppress("UNUSED_PARAMETER") tier: GlassTier,
    dark: Boolean,
    zoneId: ZoneId,
    today: LocalDate,
    onRequestNotificationPermission: (() -> Unit)? = null,
) {
    var dueDateText by remember(initial) { mutableStateOf(initial.dueDateText) }
    var reminderEnabled by remember(initial) { mutableStateOf(initial.reminderEnabled) }
    var reminderTimeText by remember(initial) { mutableStateOf(initial.reminderTimeText) }
    var recurrence by remember(initial) { mutableStateOf(initial.recurrence) }
    var policy by remember(initial) { mutableStateOf(initial.policy) }

    val parsedDue = TaskRecurrence.parseDueDate(dueDateText)
    val parsedTime = TaskRecurrence.parseLocalTime(reminderTimeText)
    val effectiveReminderDate = parsedDue ?: today
    val resolvedPreview = if (reminderEnabled && parsedTime != null) {
        TaskRecurrence.resolveTrigger(effectiveReminderDate, parsedTime, zoneId)
    } else {
        null
    }

    MintGlassDialog(
        onDismissRequest = onDismiss,
        dark = dark,
        backdrop = backdrop,
        title = "待办排期与提醒",
        subtitle = initial.taskText,
        modifier = Modifier.testTag("task_schedule_modal"),
        actions = {
            MintPillButton(
                label = "取消",
                onClick = onDismiss,
                dark = dark,
            )
            MintPillButton(
                label = "保存",
                onClick = {
                    onSave(
                        initial.copy(
                            dueDateText = dueDateText,
                            reminderEnabled = reminderEnabled,
                            reminderTimeText = reminderTimeText,
                            recurrence = recurrence,
                            policy = policy,
                        ),
                    )
                },
                dark = dark,
                primary = true,
                modifier = Modifier.testTag("schedule_save"),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "截止日期（可独立于提醒设置）",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                ScheduleChoiceChip("今天", dueDateText == today.toString(), dark) {
                    dueDateText = today.toString()
                }
                ScheduleChoiceChip("明天", dueDateText == today.plusDays(1).toString(), dark) {
                    dueDateText = today.plusDays(1).toString()
                }
                ScheduleChoiceChip("下周", dueDateText == today.plusWeeks(1).toString(), dark) {
                    dueDateText = today.plusWeeks(1).toString()
                }
                ScheduleChoiceChip("不设日期", dueDateText.isBlank(), dark) {
                    dueDateText = ""
                }
            }
            MintTextField(
                value = dueDateText,
                onValueChange = { dueDateText = it.trim() },
                placeholder = "截止日期 YYYY-MM-DD（如 $today）",
                dark = dark,
                tag = "schedule_due_input",
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "本地定时提醒",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    if (!notificationsEnabled) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "提醒未开启，保存后暂不弹窗",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.align(Alignment.CenterVertically),
                            )
                            if (onRequestNotificationPermission != null) {
                                MintChip(
                                    label = "开启通知",
                                    onClick = onRequestNotificationPermission,
                                    dark = dark,
                                    style = MintChipStyle.Selected,
                                )
                            }
                        }
                    }
                }
                MintCapsuleSwitch(
                    checked = reminderEnabled,
                    onCheckedChange = { checked ->
                        reminderEnabled = checked
                        if (checked && !notificationsEnabled) {
                            onRequestNotificationPermission?.invoke()
                        }
                    },
                    dark = dark,
                    modifier = Modifier.testTag("schedule_reminder_switch"),
                )
            }

            if (reminderEnabled) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    listOf("09:00", "14:00", "18:00", "21:00").forEach { preset ->
                        ScheduleChoiceChip(preset, reminderTimeText == preset, dark) {
                            reminderTimeText = preset
                        }
                    }
                }
                MintTextField(
                    value = reminderTimeText,
                    onValueChange = { reminderTimeText = it.trim() },
                    placeholder = "提醒时间 HH:mm（例如 09:00）",
                    dark = dark,
                    tag = "schedule_time_input",
                )

                Text("重复频率", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    RecurrenceRule.entries.forEach { rule ->
                        ScheduleChoiceChip(rule.label, recurrence == rule, dark) {
                            recurrence = rule
                        }
                    }
                }

                Text("时区变更策略", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    ReminderPolicy.entries.forEach { option ->
                        ScheduleChoiceChip(option.label, policy == option, dark) {
                            policy = option
                        }
                    }
                }

                if (resolvedPreview != null) {
                    val zdt = resolvedPreview.zonedDateTime
                    val previewText = buildString {
                        append("下次提醒：${zdt.toLocalDate()} ${TIME_FORMAT.format(zdt.toLocalTime())}")
                        if (resolvedPreview.wasGapAdjusted) {
                            append("（夏令时跳过时刻，已顺延至首个有效时刻）")
                        }
                    }
                    Text(
                        text = previewText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (
                    initial.reminderId != null &&
                    (initial.reminderStatus == ReminderStatus.ACTIVE || initial.reminderStatus == ReminderStatus.MISSED)
                ) {
                    MintPillButton(
                        label = "延后 15 分钟",
                        onClick = { onSnooze(initial.reminderId) },
                        dark = dark,
                        modifier = Modifier.testTag("schedule_snooze_15"),
                    )
                }
                MintPillButton(
                    label = "清除排期",
                    onClick = onClear,
                    dark = dark,
                    destructive = true,
                    modifier = Modifier.testTag("schedule_clear"),
                )
            }
        }
    }
}

@Composable
private fun ScheduleChoiceChip(
    label: String,
    selected: Boolean,
    dark: Boolean,
    onClick: () -> Unit,
) {
    MintChip(
        label = label,
        onClick = onClick,
        dark = dark,
        style = if (selected) MintChipStyle.Selected else MintChipStyle.Standard,
        selected = selected,
        role = Role.RadioButton,
    )
}

private fun formatFriendlyDate(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "今天"
    today.plusDays(1) -> "明天"
    today.minusDays(1) -> "昨天"
    else -> if (date.year == today.year) "${date.monthValue}月${date.dayOfMonth}日" else date.toString()
}

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
