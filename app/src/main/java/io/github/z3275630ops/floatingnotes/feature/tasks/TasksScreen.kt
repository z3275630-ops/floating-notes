package io.github.z3275630ops.floatingnotes.feature.tasks

import android.content.Context
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.z3275630ops.floatingnotes.core.model.NoteBlock
import io.github.z3275630ops.floatingnotes.core.model.NoteDocument
import io.github.z3275630ops.floatingnotes.data.local.DraftEntity
import io.github.z3275630ops.floatingnotes.data.local.NotesRepository
import io.github.z3275630ops.floatingnotes.data.local.RecurrenceRule
import io.github.z3275630ops.floatingnotes.data.local.ReminderPolicy
import io.github.z3275630ops.floatingnotes.data.local.ReminderStatus
import io.github.z3275630ops.floatingnotes.data.local.TaskItemProjection
import io.github.z3275630ops.floatingnotes.data.local.TaskOccurrenceEntity
import io.github.z3275630ops.floatingnotes.data.local.TaskPreferences
import io.github.z3275630ops.floatingnotes.data.local.TaskToggleResult
import io.github.z3275630ops.floatingnotes.ui.designsystem.ComposablesMenu
import io.github.z3275630ops.floatingnotes.ui.designsystem.GlassBackdrop
import io.github.z3275630ops.floatingnotes.ui.designsystem.GlassTier
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintAlertDialog
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintBottomSheet
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintChip
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintChipStyle
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGroupedDivider
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGroupedRow
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGroupedSection
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintIcons
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintPillButton
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintSegmentedBar
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintTextField
import io.github.z3275630ops.floatingnotes.ui.designsystem.NoteMenuTrigger
import io.github.z3275630ops.floatingnotes.ui.designsystem.glassSource
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintAnimatorsEnabled
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintCanvasColor
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintCardSurface
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintDestructiveColor
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintSecondaryFill
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintSpringSpec
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintTodoAccent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

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
    alarmHealthProvider: (Context) -> AlarmHealthStatus = ReminderScheduler::inspectAlarmHealth,
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
    var alarmHealth by remember { mutableStateOf(alarmHealthProvider(context)) }
    var strongAlarmEnabled by remember { mutableStateOf(ReminderScheduler.isStrongAlarmEnabled(context)) }
    var alarmHealthDismissed by remember { mutableStateOf(false) }
    var currentZone by remember { mutableStateOf(ZoneId.systemDefault()) }
    var currentToday by remember { mutableStateOf(LocalDate.now(currentZone)) }
    var statusBanner by remember { mutableStateOf<String?>(null) }
    var editingDraft by remember { mutableStateOf<TasksScheduleDraft?>(null) }

    DisposableEffect(lifecycleOwner, context, capabilityProvider, alarmHealthProvider) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                capability = capabilityProvider(context)
                alarmHealth = alarmHealthProvider(context)
                strongAlarmEnabled = ReminderScheduler.isStrongAlarmEnabled(context)
                val latestZone = ZoneId.systemDefault()
                currentZone = latestZone
                currentToday = LocalDate.now(latestZone)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused, capabilityProvider, alarmHealthProvider) {
        if (windowFocused) {
            capability = capabilityProvider(context)
            alarmHealth = alarmHealthProvider(context)
            strongAlarmEnabled = ReminderScheduler.isStrongAlarmEnabled(context)
        }
    }

    LaunchedEffect(currentToday, currentZone) {
        val nowZoned = ZonedDateTime.now(currentZone)
        val nextMidnight = currentToday.plusDays(1).atStartOfDay(currentZone)
        val waitMs = (Duration.between(nowZoned, nextMidnight).toMillis() + 1_000L).coerceAtLeast(1_000L)
        delay(waitMs)
        val latestZone = ZoneId.systemDefault()
        currentZone = latestZone
        currentToday = LocalDate.now(latestZone)
    }

    val openNotificationSettings = {
        ReminderScheduler.launchNotificationSettings(context)
        Unit
    }

    val requestNotifications = onRequestNotificationPermission ?: openNotificationSettings

    val openExactAlarmSettings = {
        ReminderScheduler.launchExactAlarmSettings(context)
        Unit
    }

    val openFullScreenSettings = {
        ReminderScheduler.launchFullScreenIntentSettings(context)
        Unit
    }

    val openBatterySettings = {
        ReminderScheduler.launchBatteryOptimizationSettings(context)
        Unit
    }

    val syncSystemClockAlarm: (Int, Int, String) -> Unit = { hour, minute, label ->
        val intent = ReminderScheduler.buildSystemClockAlarmIntent(hour = hour, minute = minute, label = label)
        runCatching { context.startActivity(intent) }
            .onFailure { statusBanner = "未找到可用的系统时钟应用" }
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
            editingDraft = TasksScheduleDraft.fromItem(item, currentToday)
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
        onQuickAddTask = { taskText ->
            scope.launch {
                val trimmed = taskText.trim()
                if (trimmed.isNotEmpty()) {
                    val zone = ZoneId.systemDefault()
                    val block = NoteBlock.checkbox(text = trimmed, dueDate = LocalDate.now(zone).toString())
                    val doc = NoteDocument(listOf(block))
                    val noteId = UUID.randomUUID().toString()
                    val draft = DraftEntity(
                        id = UUID.randomUUID().toString(),
                        slot = "quick-task-$noteId",
                        noteId = noteId,
                        baseRevision = 0L,
                        documentJson = doc.encode(),
                        editSeq = 1L,
                        updatedAt = Instant.now().toString(),
                    )
                    repository.commit(draft)
                    statusBanner = "已添加到今日待办"
                }
            }
        },
        onClose = onClose,
        backdrop = backdrop,
        tier = tier,
        dark = dark,
        modifier = modifier,
        zoneId = currentZone,
        today = currentToday,
        onRequestNotificationPermission = requestNotifications,
        alarmHealth = alarmHealth,
        strongAlarmEnabled = strongAlarmEnabled,
        alarmHealthDismissed = alarmHealthDismissed,
        onDismissAlarmHealth = { alarmHealthDismissed = true },
        onOpenFullScreenSettings = openFullScreenSettings,
        onOpenBatterySettings = openBatterySettings,
        onSyncSystemClockAlarm = syncSystemClockAlarm,
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
    onQuickAddTask: ((String) -> Unit)? = null,
    alarmHealth: AlarmHealthStatus = AlarmHealthStatus(
        notificationsGranted = capability.notificationsEnabled,
        exactAlarmAllowed = capability.exactAlarmAllowed,
        fullScreenIntentAllowed = true,
        ignoringBatteryOptimizations = true,
    ),
    strongAlarmEnabled: Boolean = true,
    alarmHealthDismissed: Boolean = false,
    onDismissAlarmHealth: () -> Unit = {},
    onOpenFullScreenSettings: () -> Unit = {},
    onOpenBatterySettings: () -> Unit = {},
    onSyncSystemClockAlarm: ((hour: Int, minute: Int, label: String) -> Unit)? = null,
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
    val overdueItems = remember(classified, today, zoneId) {
        TaskUrgencyEngine.sortByUrgency(
            classified.filter { it.first == TaskBucket.OVERDUE }.map { it.second },
            today = today,
            zoneId = zoneId,
        )
    }
    val todayItems = remember(classified, today, zoneId) {
        TaskUrgencyEngine.sortByUrgency(
            classified.filter { it.first == TaskBucket.TODAY }.map { it.second },
            today = today,
            zoneId = zoneId,
        )
    }
    val unscheduledItems = remember(classified, today, zoneId) {
        TaskUrgencyEngine.sortByUrgency(
            classified.filter { it.first == TaskBucket.UNSCHEDULED }.map { it.second },
            today = today,
            zoneId = zoneId,
        )
    }
    val upcomingItems = remember(classified, today, zoneId) {
        classified.filter { it.first == TaskBucket.UPCOMING }
            .map { it.second }
            .sortedWith(
                compareBy<TaskItemProjection> {
                    TaskRecurrence.effectiveLocalDate(
                        it.dueDate,
                        it.reminderLocalDate,
                        it.triggerInstantMs,
                        zoneId,
                    ) ?: LocalDate.MAX
                }.thenByDescending {
                    TaskUrgencyEngine.evaluate(it, today, zoneId).score
                },
            )
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

    val opaqueCanvas = mintCanvasColor(dark)
    var showQuickAddDialog by remember { mutableStateOf(false) }
    var quickAddText by remember { mutableStateOf("") }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(opaqueCanvas)
            .testTag("tasks_screen"),
    ) {
        val openTasksCount = remember(items) { items.count { !it.checked } }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(opaqueCanvas)
                .glassSource(backdrop),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clearAndSetSemantics { }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                    )
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            TasksHeaderBar(
                openCount = openTasksCount,
                todoStartupEnabled = todoStartupEnabled,
                onToggleTodoStartup = onToggleTodoStartup,
                onClose = onClose,
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
                    dark = dark,
                    testTag = "tasks_exact_alarm_banner",
                    actionTag = "tasks_open_exact_alarm_settings",
                )
            } else if (
                strongAlarmEnabled &&
                activeRemindersCount > 0 &&
                !alarmHealthDismissed &&
                (!alarmHealth.fullScreenIntentAllowed || !alarmHealth.ignoringBatteryOptimizations)
            ) {
                val missingFullScreen = !alarmHealth.fullScreenIntentAllowed
                PermissionGuidanceBanner(
                    title = if (missingFullScreen) "锁屏全屏提醒权限未开启" else "系统忽略电池优化未授权",
                    message = if (missingFullScreen) {
                        "未开启全屏通知权限时，强闹钟将降级为横幅提醒，建议前往系统设置开启。"
                    } else {
                        "系统忽略电池优化（Doze 白名单）未开启，这与手机管家的自启动管理相互独立；点击一键允许后台运行可防止息屏漏响。"
                    },
                    actionLabel = if (missingFullScreen) "开启全屏提醒" else "一键允许",
                    dismissLabel = "暂不提醒",
                    onAction = if (missingFullScreen) onOpenFullScreenSettings else onOpenBatterySettings,
                    onDismiss = onDismissAlarmHealth,
                    dark = dark,
                    testTag = "tasks_alarm_health_banner",
                    actionTag = "tasks_open_alarm_health_settings",
                )
            }

            AnimatedVisibility(visible = statusFeedback != null) {
                statusFeedback?.let { message ->
                    MintGroupedSection(
                        dark = dark,
                        containerColor = mintSecondaryFill(dark),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = onDismissStatusFeedback,
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                            ) {
                                Text(
                                    text = "知道了",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(top = 2.dp, bottom = 24.dp),
            ) {
                when (selectedFilter) {
                    TasksFilter.TODAY -> {
                        if (overdueItems.isEmpty() && todayItems.isEmpty() && unscheduledItems.isEmpty()) {
                            item("empty_today") {
                                val doneCount = completedItems.size + extraOccurrences.size
                                TasksEmptyCard(
                                    title = "今天没有待办",
                                    subtitle = if (doneCount > 0) {
                                        "今日已清空 · 已完成 $doneCount 项"
                                    } else {
                                        "随时记一条待办事项，或者在笔记里插入复选框"
                                    },
                                    dark = dark,
                                    actionLabel = if (onQuickAddTask != null) "添加待办" else null,
                                    onAction = if (onQuickAddTask != null) {
                                        {
                                            quickAddText = ""
                                            showQuickAddDialog = true
                                        }
                                    } else null,
                                )
                            }
                        } else {
                            if (overdueItems.isNotEmpty()) {
                                item("hdr_overdue") {
                                    TaskSectionHeader("已逾期 · ${overdueItems.size}", overdue = true, topSpacing = 2.dp)
                                }
                                groupedTaskItems(
                                    items = overdueItems,
                                    keyPrefix = "ov",
                                    itemKey = { "${it.noteId}_${it.blockId}" },
                                    dark = dark,
                                ) { item, rowMod ->
                                    TaskRowCard(
                                        item = item,
                                        today = today,
                                        zoneId = zoneId,
                                        notificationsEnabled = capability.notificationsEnabled,
                                        isOverdue = true,
                                        onToggle = { onToggleTask(item) },
                                        onOpenNote = { onOpenNote(item.noteId, item.blockId) },
                                        onEditSchedule = { onEditSchedule(item) },
                                        dark = dark,
                                        modifier = rowMod,
                                    )
                                }
                            }
                            if (todayItems.isNotEmpty()) {
                                item("hdr_today") {
                                    TaskSectionHeader(
                                        title = "今天 · ${todayItems.size}",
                                        overdue = false,
                                        topSpacing = if (overdueItems.isNotEmpty()) 16.dp else 2.dp,
                                    )
                                }
                                groupedTaskItems(
                                    items = todayItems,
                                    keyPrefix = "td",
                                    itemKey = { "${it.noteId}_${it.blockId}" },
                                    dark = dark,
                                ) { item, rowMod ->
                                    TaskRowCard(
                                        item = item,
                                        today = today,
                                        zoneId = zoneId,
                                        notificationsEnabled = capability.notificationsEnabled,
                                        isOverdue = false,
                                        onToggle = { onToggleTask(item) },
                                        onOpenNote = { onOpenNote(item.noteId, item.blockId) },
                                        onEditSchedule = { onEditSchedule(item) },
                                        dark = dark,
                                        modifier = rowMod,
                                    )
                                }
                            }
                            if (unscheduledItems.isNotEmpty()) {
                                item("hdr_unscheduled") {
                                    TaskSectionHeader(
                                        title = "未设日期 · ${unscheduledItems.size}",
                                        overdue = false,
                                        topSpacing = if (overdueItems.isNotEmpty() || todayItems.isNotEmpty()) 16.dp else 2.dp,
                                    )
                                }
                                groupedTaskItems(
                                    items = unscheduledItems,
                                    keyPrefix = "un",
                                    itemKey = { "${it.noteId}_${it.blockId}" },
                                    dark = dark,
                                ) { item, rowMod ->
                                    TaskRowCard(
                                        item = item,
                                        today = today,
                                        zoneId = zoneId,
                                        notificationsEnabled = capability.notificationsEnabled,
                                        isOverdue = false,
                                        onToggle = { onToggleTask(item) },
                                        onOpenNote = { onOpenNote(item.noteId, item.blockId) },
                                        onEditSchedule = { onEditSchedule(item) },
                                        dark = dark,
                                        modifier = rowMod,
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
                                    dark = dark,
                                )
                            }
                        } else {
                            item("hdr_upcoming") {
                                TaskSectionHeader("即将到来 · ${upcomingItems.size}", overdue = false, topSpacing = 2.dp)
                            }
                            groupedTaskItems(
                                items = upcomingItems,
                                keyPrefix = "up",
                                itemKey = { "${it.noteId}_${it.blockId}" },
                                dark = dark,
                            ) { item, rowMod ->
                                TaskRowCard(
                                    item = item,
                                    today = today,
                                    zoneId = zoneId,
                                    notificationsEnabled = capability.notificationsEnabled,
                                    isOverdue = false,
                                    onToggle = { onToggleTask(item) },
                                    onOpenNote = { onOpenNote(item.noteId, item.blockId) },
                                    onEditSchedule = { onEditSchedule(item) },
                                    dark = dark,
                                    modifier = rowMod,
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
                                    dark = dark,
                                )
                            }
                        } else {
                            if (completedItems.isNotEmpty()) {
                                item("hdr_completed") {
                                    TaskSectionHeader("已完成待办 · ${completedItems.size}", overdue = false, topSpacing = 2.dp)
                                }
                                groupedTaskItems(
                                    items = completedItems,
                                    keyPrefix = "done",
                                    itemKey = { "${it.noteId}_${it.blockId}" },
                                    dark = dark,
                                ) { item, rowMod ->
                                    TaskRowCard(
                                        item = item,
                                        today = today,
                                        zoneId = zoneId,
                                        notificationsEnabled = capability.notificationsEnabled,
                                        isOverdue = false,
                                        onToggle = { onToggleTask(item) },
                                        onOpenNote = { onOpenNote(item.noteId, item.blockId) },
                                        onEditSchedule = { onEditSchedule(item) },
                                        dark = dark,
                                        modifier = rowMod,
                                    )
                                }
                            }
                            if (extraOccurrences.isNotEmpty()) {
                                item("hdr_occ") {
                                    TaskSectionHeader(
                                        title = "循环任务完成记录 · ${extraOccurrences.size}",
                                        overdue = false,
                                        topSpacing = if (completedItems.isNotEmpty()) 16.dp else 2.dp,
                                    )
                                }
                                groupedTaskItems(
                                    items = extraOccurrences,
                                    keyPrefix = "occ",
                                    itemKey = { it.id },
                                    dark = dark,
                                    dividerIndent = 16.dp,
                                ) { occ, rowMod ->
                                    CompletedOccurrenceCard(
                                        occurrence = occ,
                                        zoneId = zoneId,
                                        onOpenNote = { onOpenNote(occ.noteId, occ.blockId) },
                                        modifier = rowMod,
                                    )
                                }
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
                alarmHealth = alarmHealth,
                strongAlarmEnabled = strongAlarmEnabled,
                onOpenExactAlarmSettings = onOpenExactAlarmSettings,
                onOpenFullScreenSettings = onOpenFullScreenSettings,
                onOpenBatterySettings = onOpenBatterySettings,
                onSyncSystemClockAlarm = onSyncSystemClockAlarm,
            )
        }

        if (showQuickAddDialog && onQuickAddTask != null) {
            MintAlertDialog(
                onDismissRequest = { showQuickAddDialog = false },
                dark = dark,
                backdrop = backdrop,
                title = "添加今日待办",
                subtitle = "会自动保存为一条带复选框的轻量记录",
                dismissLabel = "取消",
                onDismissAction = { showQuickAddDialog = false },
                confirmLabel = "保存",
                onConfirmAction = {
                    if (quickAddText.isNotBlank()) {
                        onQuickAddTask(quickAddText)
                        showQuickAddDialog = false
                    }
                },
            ) {
                MintTextField(
                    value = quickAddText,
                    onValueChange = { quickAddText = it },
                    placeholder = "例如：明天提交季度总结",
                    dark = dark,
                )
            }
        }
    }
}

@Composable
private fun TasksHeaderBar(
    openCount: Int,
    todoStartupEnabled: Boolean,
    onToggleTodoStartup: (Boolean) -> Unit,
    onClose: (() -> Unit)?,
    dark: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    var showMoreMenu by remember { mutableStateOf(false) }
    val backInteraction = remember { MutableInteractionSource() }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (onClose != null) {
                Row(
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(
                            interactionSource = backInteraction,
                            indication = null,
                            role = Role.Button,
                            onClick = onClose,
                        )
                        .padding(end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = MintIcons.Back,
                        contentDescription = "返回笔记",
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "笔记",
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = scheme.onSurfaceVariant,
                    )
                }
            } else {
                Spacer(Modifier.width(1.dp))
            }

            Box {
                NoteMenuTrigger(
                    label = "待办设置",
                    tag = "tasks_more_menu",
                    icon = MintIcons.More,
                    containerColor = mintSecondaryFill(dark),
                    tint = scheme.onSurfaceVariant,
                    onClick = { showMoreMenu = true },
                )
                ComposablesMenu(
                    expanded = showMoreMenu,
                    onDismiss = { showMoreMenu = false },
                    tag = "tasks_more_dropdown",
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "启动默认进入待办",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = scheme.onSurface,
                            )
                        },
                        trailingIcon = if (todoStartupEnabled) {
                            {
                                Icon(
                                    imageVector = MintIcons.Check,
                                    contentDescription = "已启用",
                                    tint = scheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        } else null,
                        onClick = {
                            showMoreMenu = false
                            onToggleTodoStartup(!todoStartupEnabled)
                        },
                        modifier = Modifier.testTag("tasks_startup_toggle"),
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "待办",
                style = MaterialTheme.typography.displayLarge,
                color = scheme.onSurface,
            )
            Text(
                text = "$openCount 项未完成",
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                color = if (openCount > 0) mintTodoAccent(dark).fg else scheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
    }
}

@Composable
private fun TasksSegmentedFilter(
    selected: TasksFilter,
    onSelect: (TasksFilter) -> Unit,
    counts: Map<TasksFilter, Int>,
    dark: Boolean,
) {
    val entries = TasksFilter.entries
    MintSegmentedBar(
        options = entries.map { it.label },
        selectedIndex = entries.indexOf(selected).coerceAtLeast(0),
        onSelect = { index -> entries.getOrNull(index)?.let(onSelect) },
        dark = dark,
        modifier = Modifier.fillMaxWidth(),
        badges = entries.map { counts[it] },
        optionTags = entries.map { "tasks_filter_${it.name.lowercase()}" },
    )
}

@Composable
private fun PermissionGuidanceBanner(
    title: String,
    message: String,
    actionLabel: String,
    dismissLabel: String?,
    onAction: () -> Unit,
    onDismiss: (() -> Unit)?,
    dark: Boolean,
    testTag: String,
    actionTag: String,
) {
    val container = if (dark) Color(0xFF282319) else Color(0xFFFFF8E8)
    val contentTint = if (dark) Color(0xFFF3CF88) else Color(0xFF8C5A00)

    MintGroupedSection(
        dark = dark,
        containerColor = container,
        modifier = Modifier.testTag(testTag),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = MintIcons.Bell,
                    contentDescription = null,
                    tint = contentTint,
                    modifier = Modifier.size(17.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onAction,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag(actionTag),
                ) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (dismissLabel != null && onDismiss != null) {
                    TextButton(
                        onClick = onDismiss,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    ) {
                        Text(
                            text = dismissLabel,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskSectionHeader(
    title: String,
    overdue: Boolean,
    topSpacing: Dp = 12.dp,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = topSpacing, bottom = 6.dp),
    )
}

private fun <T> LazyListScope.groupedTaskItems(
    items: List<T>,
    keyPrefix: String,
    itemKey: (T) -> Any,
    dark: Boolean,
    dividerIndent: Dp = 56.dp,
    rowContent: @Composable (item: T, modifier: Modifier) -> Unit,
) {
    itemsIndexed(
        items = items,
        key = { _, item -> "${keyPrefix}_${itemKey(item)}" },
    ) { index, item ->
        val isFirst = index == 0
        val isLast = index == items.lastIndex
        val shape = RoundedCornerShape(
            topStart = if (isFirst) 16.dp else 0.dp,
            topEnd = if (isFirst) 16.dp else 0.dp,
            bottomStart = if (isLast) 16.dp else 0.dp,
            bottomEnd = if (isLast) 16.dp else 0.dp,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(mintCardSurface(dark)),
        ) {
            rowContent(item, Modifier.fillMaxWidth())
            if (!isLast) {
                MintGroupedDivider(dark = dark, startIndent = dividerIndent)
            }
        }
    }
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
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    val parsedDue = TaskRecurrence.parseDueDate(item.dueDate)
    val hasActiveReminder = item.reminderId != null &&
        item.reminderStatus != ReminderStatus.CANCELLED &&
        item.reminderStatus != ReminderStatus.COMPLETED &&
        item.reminderLocalTime != null
    val urgency = remember(item, today, zoneId) {
        if (!item.checked) TaskUrgencyEngine.evaluate(item, today, zoneId) else null
    }
    val urgencyBadge = urgency?.badgeLabel
    val rowInteraction = remember { MutableInteractionSource() }
    val scheme = MaterialTheme.colorScheme

    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("task_row_${item.noteId}_${item.blockId}")
            .clickable(
                interactionSource = rowInteraction,
                indication = null,
                onClick = onOpenNote,
            )
            .padding(start = 6.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TaskCircleCheckbox(
            checked = item.checked,
            overdue = isOverdue && !item.checked,
            onClick = onToggle,
            modifier = Modifier.testTag("task_toggle_${item.noteId}_${item.blockId}"),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 10.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = item.text.ifBlank { "未命名待办" },
                style = MaterialTheme.typography.bodyLarge,
                color = if (item.checked) scheme.onSurfaceVariant else scheme.onSurface,
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
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (item.noteArchived) {
                    MetaBadge(
                        label = "已归档",
                        container = mintSecondaryFill(dark),
                        content = scheme.onSurfaceVariant,
                        modifier = Modifier.testTag("task_archived_badge_${item.noteId}"),
                    )
                }
            }

            if (urgencyBadge != null || parsedDue != null || hasActiveReminder || item.recurrence != RecurrenceRule.NONE) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (urgencyBadge != null && urgency != null) {
                        val isCritical = urgency.tier == TaskUrgencyTier.CRITICAL
                        val todoAccent = mintTodoAccent(dark)
                        MetaBadge(
                            label = urgencyBadge,
                            container = if (isCritical) {
                                mintDestructiveColor(dark).copy(alpha = 0.14f)
                            } else {
                                todoAccent.bg
                            },
                            content = if (isCritical) {
                                mintDestructiveColor(dark)
                            } else {
                                todoAccent.fg
                            },
                            modifier = Modifier.testTag("task_urgency_badge_${item.noteId}_${item.blockId}"),
                        )
                    }
                    if (parsedDue != null) {
                        val overdueActive = isOverdue && !item.checked
                        MetaBadge(
                            label = "截止 ${formatFriendlyDate(parsedDue, today)}",
                            container = if (overdueActive) {
                                mintDestructiveColor(dark).copy(alpha = 0.14f)
                            } else {
                                mintSecondaryFill(dark)
                            },
                            content = if (overdueActive) {
                                mintDestructiveColor(dark)
                            } else {
                                scheme.onSurfaceVariant
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
                                scheme.primary.copy(alpha = if (dark) 0.18f else 0.12f)
                            } else {
                                Color(0xFFB36B00).copy(alpha = if (dark) 0.22f else 0.12f)
                            },
                            content = if (notificationsEnabled) {
                                scheme.primary
                            } else {
                                if (dark) Color(0xFFF3CF88) else Color(0xFF8C5A00)
                            },
                            modifier = Modifier.testTag("task_reminder_badge_${item.noteId}_${item.blockId}"),
                        )
                    }
                    if (item.recurrence != RecurrenceRule.NONE) {
                        MetaBadge(
                            label = item.recurrence.label,
                            container = mintSecondaryFill(dark),
                            content = scheme.primary,
                        )
                    }
                    if (hasActiveReminder && item.policy == ReminderPolicy.KEEP_ORIGIN_ZONE) {
                        MetaBadge(
                            label = item.policy.label,
                            container = mintSecondaryFill(dark),
                            content = scheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (!item.checked) {
            TextButton(
                onClick = onEditSchedule,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                modifier = Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .testTag("task_schedule_${item.noteId}_${item.blockId}"),
            ) {
                Text(
                    text = if (parsedDue != null || hasActiveReminder) "改期" else "排期",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                    color = scheme.primary,
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
    modifier: Modifier = Modifier,
) {
    val completedLabel = remember(occurrence.completedAt, zoneId) {
        runCatching {
            val zdt = Instant.parse(occurrence.completedAt).atZone(zoneId)
            "${zdt.monthValue}/${zdt.dayOfMonth} ${TIME_FORMAT.format(zdt.toLocalTime())}"
        }.getOrDefault(occurrence.completedAt)
    }
    val interactionSource = remember { MutableInteractionSource() }
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("task_occurrence_${occurrence.id}")
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onOpenNote,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
                color = scheme.onSurfaceVariant,
                textDecoration = TextDecoration.LineThrough,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val rule = RecurrenceRule.fromStored(occurrence.recurrence)
                if (rule != RecurrenceRule.NONE) {
                    MetaBadge(
                        label = rule.label,
                        container = scheme.primary.copy(alpha = 0.12f),
                        content = scheme.primary,
                    )
                }
                MetaBadge(
                    label = "完成于 $completedLabel",
                    container = scheme.surfaceVariant.copy(alpha = 0.65f),
                    content = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TasksEmptyCard(
    title: String,
    subtitle: String,
    dark: Boolean,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val todoAccent = mintTodoAccent(dark)
    MintGroupedSection(
        dark = dark,
        modifier = Modifier
            .padding(top = 6.dp)
            .testTag("tasks_empty_state"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(todoAccent.bg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MintIcons.Checklist,
                    contentDescription = null,
                    tint = todoAccent.fg,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (actionLabel != null && onAction != null) {
                MintPillButton(
                    label = actionLabel,
                    primary = true,
                    dark = dark,
                    icon = MintIcons.Compose,
                    onClick = onAction,
                )
            }
        }
    }
}

@Composable
private fun TaskCircleCheckbox(
    checked: Boolean,
    overdue: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val ringColor = if (overdue) {
        MaterialTheme.colorScheme.error.copy(alpha = 0.72f)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.48f)
    }
    val checkColor = MaterialTheme.colorScheme.onPrimary
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .toggleable(
                value = checked,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Checkbox,
                onValueChange = { onClick() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(22.dp)) {
            if (checked) {
                drawCircle(color = primary.copy(alpha = 0.55f))
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
                    color = ringColor,
                    style = Stroke(width = 1.75.dp.toPx()),
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
        checked -> scheme.primary
        dark -> Color(0xFF32302B)
        else -> Color(0xFFD8D4CA)
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
                .background(activeTrack, RoundedCornerShape(50)),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .offset(x = activeOffset)
                    .size(24.dp)
                    .then(
                        if (!dark) Modifier.shadow(1.5.dp, CircleShape, ambientColor = Color(0x22000000), spotColor = Color(0x22000000))
                        else Modifier
                    )
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
        shape = RoundedCornerShape(6.dp),
        color = container,
        modifier = modifier,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
            color = content,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
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
    alarmHealth: AlarmHealthStatus = AlarmHealthStatus(
        notificationsGranted = notificationsEnabled,
        exactAlarmAllowed = true,
        fullScreenIntentAllowed = true,
        ignoringBatteryOptimizations = true,
    ),
    strongAlarmEnabled: Boolean = true,
    onOpenExactAlarmSettings: (() -> Unit)? = null,
    onOpenFullScreenSettings: (() -> Unit)? = null,
    onOpenBatterySettings: (() -> Unit)? = null,
    onSyncSystemClockAlarm: ((hour: Int, minute: Int, label: String) -> Unit)? = null,
) {
    val context = LocalContext.current
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

    MintBottomSheet(
        onDismissRequest = onDismiss,
        dark = dark,
        backdrop = backdrop,
        title = "待办排期与提醒",
        subtitle = initial.taskText,
        leadingLabel = "取消",
        onLeadingClick = onDismiss,
        trailingLabel = "保存",
        onTrailingClick = {
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
        trailingTag = "schedule_save",
        modifier = Modifier.testTag("task_schedule_modal"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MintGroupedSection(dark = dark) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "截止日期",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
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
                        placeholder = "YYYY-MM-DD（如 $today）",
                        dark = dark,
                        tag = "schedule_due_input",
                    )
                }
            }

            MintGroupedSection(dark = dark) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = "本地定时提醒",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (!notificationsEnabled) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = "提醒未开启，保存后暂不弹窗",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.align(Alignment.CenterVertically),
                                )
                                if (onRequestNotificationPermission != null) {
                                    TextButton(
                                        onClick = onRequestNotificationPermission,
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                    ) {
                                        Text(
                                            text = "开启通知",
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
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
                    MintGroupedDivider(dark = dark, startIndent = 14.dp)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
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
                    MintGroupedDivider(dark = dark, startIndent = 14.dp)
                    val targetClockTime = parsedTime ?: LocalTime.of(9, 0)
                    MintGroupedRow(
                        label = "设为手机系统闹钟",
                        subtitle = "将写入手机时钟，需在时钟中手动关闭",
                        leadingIcon = MintIcons.Bell,
                        trailingText = TIME_FORMAT.format(targetClockTime),
                        showChevron = true,
                        dark = dark,
                        onClick = {
                            if (onSyncSystemClockAlarm != null) {
                                onSyncSystemClockAlarm(targetClockTime.hour, targetClockTime.minute, initial.taskText)
                            } else {
                                runCatching {
                                    context.startActivity(
                                        ReminderScheduler.buildSystemClockAlarmIntent(
                                            hour = targetClockTime.hour,
                                            minute = targetClockTime.minute,
                                            label = initial.taskText,
                                        ),
                                    )
                                }
                            }
                        },
                        modifier = Modifier.testTag("schedule_set_system_alarm"),
                    )
                }
            }

            val needsHealthAttention = !alarmHealth.notificationsGranted ||
                !alarmHealth.exactAlarmAllowed ||
                (strongAlarmEnabled && (!alarmHealth.fullScreenIntentAllowed || !alarmHealth.ignoringBatteryOptimizations))
            if (reminderEnabled && needsHealthAttention) {
                Text(
                    text = "闹钟防漏响检查",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
                MintGroupedSection(
                    dark = dark,
                    modifier = Modifier.testTag("schedule_alarm_health_card"),
                ) {
                    var hasPreviousRow = false
                    if (!alarmHealth.notificationsGranted) {
                        hasPreviousRow = true
                        MintGroupedRow(
                            label = "通知权限未开启",
                            subtitle = "未授权将无法弹出提醒与闹钟卡片",
                            trailingText = "去开启",
                            showChevron = true,
                            dark = dark,
                            onClick = { onRequestNotificationPermission?.invoke() },
                        )
                    }
                    if (!alarmHealth.exactAlarmAllowed) {
                        if (hasPreviousRow) MintGroupedDivider(dark = dark, startIndent = 16.dp)
                        hasPreviousRow = true
                        MintGroupedRow(
                            label = "精确闹钟权限未开启",
                            subtitle = "可能被系统延迟数分钟触发",
                            trailingText = "去开启",
                            showChevron = true,
                            dark = dark,
                            onClick = { onOpenExactAlarmSettings?.invoke() },
                        )
                    }
                    if (strongAlarmEnabled && !alarmHealth.fullScreenIntentAllowed) {
                        if (hasPreviousRow) MintGroupedDivider(dark = dark, startIndent = 16.dp)
                        hasPreviousRow = true
                        MintGroupedRow(
                            label = "锁屏全屏提醒未开启",
                            subtitle = "熄屏时将降级为横幅通知",
                            trailingText = "去设置",
                            showChevron = true,
                            dark = dark,
                            onClick = { onOpenFullScreenSettings?.invoke() },
                        )
                    }
                    if (strongAlarmEnabled && !alarmHealth.ignoringBatteryOptimizations) {
                        if (hasPreviousRow) MintGroupedDivider(dark = dark, startIndent = 16.dp)
                        MintGroupedRow(
                            label = "系统忽略电池优化（Doze 白名单）",
                            subtitle = "独立于手机管家自启动管理，点此一键弹窗允许后台运行",
                            trailingText = "一键允许",
                            showChevron = true,
                            dark = dark,
                            onClick = { onOpenBatterySettings?.invoke() },
                        )
                    }
                }
            }

            if (reminderEnabled) {
                Text(
                    text = "重复频率",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
                MintGroupedSection(dark = dark) {
                    RecurrenceRule.entries.forEachIndexed { index, rule ->
                        MintGroupedRow(
                            label = rule.label,
                            dark = dark,
                            selected = recurrence == rule,
                            onClick = { recurrence = rule },
                        )
                        if (index < RecurrenceRule.entries.lastIndex) {
                            MintGroupedDivider(dark = dark, startIndent = 16.dp)
                        }
                    }
                }

                Text(
                    text = "时区变更策略",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
                MintGroupedSection(dark = dark) {
                    ReminderPolicy.entries.forEachIndexed { index, option ->
                        MintGroupedRow(
                            label = option.label,
                            dark = dark,
                            selected = policy == option,
                            onClick = { policy = option },
                        )
                        if (index < ReminderPolicy.entries.lastIndex) {
                            MintGroupedDivider(dark = dark, startIndent = 16.dp)
                        }
                    }
                }
            }

            MintGroupedSection(dark = dark) {
                val hasActiveSnooze = initial.reminderId != null &&
                    (initial.reminderStatus == ReminderStatus.ACTIVE || initial.reminderStatus == ReminderStatus.MISSED)
                if (hasActiveSnooze) {
                    MintGroupedRow(
                        label = "延后 15 分钟",
                        dark = dark,
                        leadingIcon = MintIcons.Bell,
                        onClick = { onSnooze(initial.reminderId!!) },
                        modifier = Modifier.testTag("schedule_snooze_15"),
                    )
                    MintGroupedDivider(dark = dark, startIndent = 16.dp)
                }
                MintGroupedRow(
                    label = "清除排期",
                    dark = dark,
                    destructive = true,
                    onClick = onClear,
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
