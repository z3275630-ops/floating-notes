package io.github.z3275630ops.floatingnotes.feature.tasks

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import android.provider.AlarmClock
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import io.github.z3275630ops.floatingnotes.MainActivity
import io.github.z3275630ops.floatingnotes.data.local.RecurrenceRule
import io.github.z3275630ops.floatingnotes.data.local.ReminderEntity
import io.github.z3275630ops.floatingnotes.data.local.ReminderStatus
import io.github.z3275630ops.floatingnotes.data.local.TaskScheduleResult
import io.github.z3275630ops.floatingnotes.data.local.TaskToggleResult
import io.github.z3275630ops.floatingnotes.data.local.TimeReconcileResult

internal data class ReminderCapability(
    val notificationsEnabled: Boolean,
    val exactAlarmAllowed: Boolean,
) {
    val remindersActive: Boolean get() = notificationsEnabled
}

internal enum class AlarmScheduleMode {
    ALARM_CLOCK,
    EXACT_IDLE,
    INEXACT_IDLE,
}

internal enum class AlarmDeliveryMode {
    STRONG_FULL_SCREEN,
    STRONG_HEADS_UP_DEGRADED,
    STANDARD_NOTIFICATION,
}

internal data class AlarmHealthStatus(
    val notificationsGranted: Boolean,
    val exactAlarmAllowed: Boolean,
    val fullScreenIntentAllowed: Boolean,
    val ignoringBatteryOptimizations: Boolean,
) {
    val allHealthy: Boolean
        get() = notificationsGranted && exactAlarmAllowed && fullScreenIntentAllowed && ignoringBatteryOptimizations

    val coreAlarmReady: Boolean
        get() = notificationsGranted && exactAlarmAllowed

    val issueCount: Int
        get() = listOf(
            notificationsGranted,
            exactAlarmAllowed,
            fullScreenIntentAllowed,
            ignoringBatteryOptimizations,
        ).count { !it }

    fun deliveryMode(
        strongAlarmEnabled: Boolean,
        underInstrumentationTest: Boolean = false,
    ): AlarmDeliveryMode = when {
        !strongAlarmEnabled || underInstrumentationTest -> AlarmDeliveryMode.STANDARD_NOTIFICATION
        fullScreenIntentAllowed -> AlarmDeliveryMode.STRONG_FULL_SCREEN
        else -> AlarmDeliveryMode.STRONG_HEADS_UP_DEGRADED
    }
}

internal data class SystemClockAlarmSpec(
    val hour: Int,
    val minute: Int,
    val label: String,
    val skipUi: Boolean = false,
)

internal data class ScheduleOutcome(
    val scheduled: Boolean,
    val exactUsed: Boolean,
    val alarmClockUsed: Boolean = false,
)

internal object ReminderScheduler {
    const val CHANNEL_ID = "task_reminders"
    const val STRONG_ALARM_CHANNEL_ID = "task_strong_alarms"
    const val SUMMARY_NOTIFICATION_ID = 40999

    const val PREFS_APP_NAME = "floating_notes_prefs"
    const val KEY_STRONG_ALARM_ENABLED = "strong_alarm_enabled"

    const val ACTION_FIRE_REMINDER = "io.github.z3275630ops.floatingnotes.action.FIRE_REMINDER"
    const val ACTION_COMPLETE_TASK = "io.github.z3275630ops.floatingnotes.action.COMPLETE_TASK"
    const val ACTION_SNOOZE_TASK = "io.github.z3275630ops.floatingnotes.action.SNOOZE_TASK"
    const val ACTION_DISMISS_ALARM = "io.github.z3275630ops.floatingnotes.action.DISMISS_ALARM"

    const val EXTRA_NOTE_ID = "extra_note_id"
    const val EXTRA_BLOCK_ID = "extra_block_id"
    const val EXTRA_OPEN_TASKS = "extra_open_tasks"
    const val EXTRA_REMINDER_ID = "extra_reminder_id"
    const val EXTRA_OCCURRENCE_ID = "extra_occurrence_id"
    const val EXTRA_REMINDER_REVISION = "extra_reminder_revision"
    const val EXTRA_SNOOZE_MINUTES = "extra_snooze_minutes"
    const val EXTRA_TASK_TEXT = "extra_task_text"
    const val EXTRA_NOTE_LABEL = "extra_note_label"
    const val EXTRA_RECURRENCE = "extra_recurrence"

    const val DEFAULT_SNOOZE_MINUTES = 15L
    const val ALARM_SNOOZE_MINUTES = 10L

    fun isStrongAlarmEnabled(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS_APP_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_STRONG_ALARM_ENABLED, true)

    fun resolveScheduleMode(
        strongAlarmEnabled: Boolean,
        exactAlarmAllowed: Boolean,
    ): AlarmScheduleMode = when {
        strongAlarmEnabled && exactAlarmAllowed -> AlarmScheduleMode.ALARM_CLOCK
        exactAlarmAllowed -> AlarmScheduleMode.EXACT_IDLE
        else -> AlarmScheduleMode.INEXACT_IDLE
    }

    fun resolveSnoozeMinutes(requestedMinutes: Long?): Long =
        (requestedMinutes ?: DEFAULT_SNOOZE_MINUTES).coerceAtLeast(1L)

    fun normalizeSystemClockAlarm(hour: Int, minute: Int, label: String): SystemClockAlarmSpec =
        SystemClockAlarmSpec(
            hour = hour.coerceIn(0, 23),
            minute = minute.coerceIn(0, 59),
            label = label.trim().ifBlank { "浮记待办" }.take(80),
            skipUi = false,
        )

    fun capability(context: Context): ReminderCapability = ReminderCapability(
        notificationsEnabled = notificationsGranted(context),
        exactAlarmAllowed = exactAlarmAllowed(context),
    )

    fun inspectAlarmHealth(context: Context): AlarmHealthStatus = AlarmHealthStatus(
        notificationsGranted = notificationsGranted(context),
        exactAlarmAllowed = exactAlarmAllowed(context),
        fullScreenIntentAllowed = canUseFullScreenIntent(context),
        ignoringBatteryOptimizations = isIgnoringBatteryOptimizations(context),
    )

    fun notificationsGranted(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        val systemEnabled = manager?.areNotificationsEnabled() ?: true
        if (!systemEnabled) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun exactAlarmAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return false
        return alarmManager.canScheduleExactAlarms()
    }

    fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val manager = context.getSystemService(NotificationManager::class.java) ?: return true
        return manager.canUseFullScreenIntent()
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return true
        return runCatching { powerManager.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(true)
    }

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "待办提醒",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "待办事项定时提醒与未读汇总"
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    fun ensureStrongAlarmChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(STRONG_ALARM_CHANNEL_ID) != null) return
        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val channel = NotificationChannel(
            STRONG_ALARM_CHANNEL_ID,
            "待办强提醒闹钟",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "待办到期时的锁屏全屏与强响铃闹钟"
            setBypassDnd(true)
            enableVibration(true)
            enableLights(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            if (alarmUri != null) {
                setSound(alarmUri, audioAttributes)
            }
        }
        manager.createNotificationChannel(channel)
    }

    fun scheduleReminder(context: Context, reminder: ReminderEntity): ScheduleOutcome {
        val triggerMs = reminder.triggerInstantMs
        if (reminder.statusEnum != ReminderStatus.ACTIVE || triggerMs == null) {
            cancelReminder(context, reminder.id)
            return ScheduleOutcome(scheduled = false, exactUsed = false, alarmClockUsed = false)
        }
        val alarmManager = context.getSystemService(AlarmManager::class.java)
            ?: return ScheduleOutcome(scheduled = false, exactUsed = false, alarmClockUsed = false)
        val pendingIntent = alarmPendingIntent(context, reminder)
        val mode = resolveScheduleMode(
            strongAlarmEnabled = isStrongAlarmEnabled(context),
            exactAlarmAllowed = exactAlarmAllowed(context),
        )

        var exactUsed = false
        var alarmClockUsed = false
        when (mode) {
            AlarmScheduleMode.ALARM_CLOCK -> {
                try {
                    val showIntent = alarmClockShowIntent(context, reminder)
                    alarmManager.setAlarmClock(
                        AlarmManager.AlarmClockInfo(triggerMs, showIntent),
                        pendingIntent,
                    )
                    exactUsed = true
                    alarmClockUsed = true
                } catch (_: SecurityException) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pendingIntent)
                }
            }

            AlarmScheduleMode.EXACT_IDLE -> {
                exactUsed = try {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pendingIntent)
                    true
                } catch (_: SecurityException) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pendingIntent)
                    false
                }
            }

            AlarmScheduleMode.INEXACT_IDLE -> {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pendingIntent)
            }
        }
        return ScheduleOutcome(scheduled = true, exactUsed = exactUsed, alarmClockUsed = alarmClockUsed)
    }

    fun cancelReminder(context: Context, reminderId: String) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val existing = PendingIntent.getBroadcast(
            context,
            requestCodeFor(reminderId, KIND_FIRE),
            fireIntent(context, reminderId, noteId = "", blockId = "", occurrenceId = null),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (existing != null) {
            alarmManager?.cancel(existing)
            existing.cancel()
        }
        AlarmPlaybackService.stopForReminder(context, reminderId)
        cancelNotification(context, reminderId)
    }

    fun cancelNotification(context: Context, reminderId: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(notificationIdFor(reminderId))
    }

    fun syncScheduleResult(context: Context, result: TaskScheduleResult) {
        result.cancelledReminderId?.let { cancelReminder(context, it) }
        result.reminder?.let { reminder ->
            if (reminder.statusEnum == ReminderStatus.ACTIVE) {
                scheduleReminder(context, reminder)
            } else {
                cancelReminder(context, reminder.id)
            }
        }
    }

    fun syncToggleResult(context: Context, result: TaskToggleResult.Updated) {
        result.cancelledReminderId?.let { cancelReminder(context, it) }
        val reminder = result.reminder
        if (reminder != null) {
            AlarmPlaybackService.stopForReminder(context, reminder.id)
            cancelNotification(context, reminder.id)
            if (reminder.statusEnum == ReminderStatus.ACTIVE) {
                scheduleReminder(context, reminder)
            } else if (result.cancelledReminderId == null) {
                cancelReminder(context, reminder.id)
            }
        }
    }

    fun syncReconcile(context: Context, result: TimeReconcileResult) {
        result.cancelledReminderIds.forEach { cancelReminder(context, it) }
        result.activeReminders.forEach { scheduleReminder(context, it) }
    }

    @SuppressLint("MissingPermission")
    fun showReminderNotification(
        context: Context,
        reminder: ReminderEntity,
        taskText: String,
        noteLabel: String,
    ): Boolean {
        if (!notificationsGranted(context)) return false
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false

        val contentPending = openTaskPendingIntent(
            context = context,
            reminderId = reminder.id,
            noteId = reminder.noteId,
            blockId = reminder.blockId,
        )

        val completePending = PendingIntent.getBroadcast(
            context,
            requestCodeFor(reminder.id, KIND_COMPLETE),
            actionIntent(context, ACTION_COMPLETE_TASK, reminder),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val snoozePending = PendingIntent.getBroadcast(
            context,
            requestCodeFor(reminder.id, KIND_SNOOZE),
            actionIntent(context, ACTION_SNOOZE_TASK, reminder),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val recurrence = reminder.recurrenceEnum
        val body = buildString {
            append(noteLabel.ifBlank { "轻点打开笔记" })
            if (recurrence != RecurrenceRule.NONE) {
                append(" · ")
                append(recurrence.label)
            }
        }

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(taskText.ifBlank { "待办提醒" })
            .setContentText(body)
            .setContentIntent(contentPending)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .addAction(
                Notification.Action.Builder(null as Icon?, "完成", completePending).build(),
            )
            .addAction(
                Notification.Action.Builder(null as Icon?, "延后 15 分钟", snoozePending).build(),
            )
            .build()

        return try {
            manager.notify(notificationIdFor(reminder.id), notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun buildStrongAlarmNotification(
        context: Context,
        reminderId: String,
        noteId: String,
        blockId: String,
        occurrenceId: String?,
        revision: Long,
        recurrence: RecurrenceRule,
        taskText: String,
        noteLabel: String,
        includeFullScreenIntent: Boolean,
        silent: Boolean,
        ongoing: Boolean,
    ): Notification {
        ensureStrongAlarmChannel(context)
        val alertIntent = AlarmAlertActivity.createIntent(
            context = context,
            reminderId = reminderId,
            noteId = noteId,
            blockId = blockId,
            occurrenceId = occurrenceId,
            revision = revision,
            taskText = taskText,
            noteLabel = noteLabel,
        )
        val alertPending = PendingIntent.getActivity(
            context,
            requestCodeFor(reminderId, KIND_FULL_SCREEN),
            alertIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val completePending = PendingIntent.getBroadcast(
            context,
            requestCodeFor(reminderId, KIND_COMPLETE),
            actionIntent(
                context = context,
                actionName = ACTION_COMPLETE_TASK,
                reminderId = reminderId,
                noteId = noteId,
                blockId = blockId,
                occurrenceId = occurrenceId,
                revision = revision,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val snoozeIntent = actionIntent(
            context = context,
            actionName = ACTION_SNOOZE_TASK,
            reminderId = reminderId,
            noteId = noteId,
            blockId = blockId,
            occurrenceId = occurrenceId,
            revision = revision,
        ).putExtra(EXTRA_SNOOZE_MINUTES, ALARM_SNOOZE_MINUTES)
        val snoozePending = PendingIntent.getBroadcast(
            context,
            requestCodeFor(reminderId, KIND_SNOOZE),
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val dismissPending = PendingIntent.getBroadcast(
            context,
            requestCodeFor(reminderId, KIND_DISMISS),
            actionIntent(
                context = context,
                actionName = ACTION_DISMISS_ALARM,
                reminderId = reminderId,
                noteId = noteId,
                blockId = blockId,
                occurrenceId = occurrenceId,
                revision = revision,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val body = buildString {
            append(noteLabel.ifBlank { "点击查看待办闹钟" })
            if (recurrence != RecurrenceRule.NONE) {
                append(" · ")
                append(recurrence.label)
            }
        }

        val builder = Notification.Builder(context, STRONG_ALARM_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(taskText.ifBlank { "待办强提醒闹钟" })
            .setContentText(body)
            .setContentIntent(alertPending)
            .setDeleteIntent(dismissPending)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(silent)
            .apply {
                if (silent) {
                    setGroup("strong_alarm_active_$reminderId")
                    setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
                }
            }
            .addAction(
                Notification.Action.Builder(null as Icon?, "完成待办", completePending).build(),
            )
            .addAction(
                Notification.Action.Builder(null as Icon?, "延后 10 分钟", snoozePending).build(),
            )
            .addAction(
                Notification.Action.Builder(null as Icon?, "关闭闹钟", dismissPending).build(),
            )

        if (includeFullScreenIntent && canUseFullScreenIntent(context)) {
            builder.setFullScreenIntent(alertPending, true)
        }
        return builder.build()
    }

    @SuppressLint("MissingPermission")
    fun showStrongAlarmNotification(
        context: Context,
        reminder: ReminderEntity,
        taskText: String,
        noteLabel: String,
        includeFullScreenIntent: Boolean = true,
        silent: Boolean = false,
        ongoing: Boolean = false,
    ): Boolean {
        if (!notificationsGranted(context)) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val notification = buildStrongAlarmNotification(
            context = context,
            reminderId = reminder.id,
            noteId = reminder.noteId,
            blockId = reminder.blockId,
            occurrenceId = reminder.occurrenceId,
            revision = reminder.revision,
            recurrence = reminder.recurrenceEnum,
            taskText = taskText,
            noteLabel = noteLabel,
            includeFullScreenIntent = includeFullScreenIntent,
            silent = silent,
            ongoing = ongoing,
        )
        return try {
            manager.notify(notificationIdFor(reminder.id), notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun showMissedSummaryNotification(
        context: Context,
        batch: MissedSummaryBatch,
    ): Boolean {
        if (batch.count <= 0 || !notificationsGranted(context)) return false
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = "floatingnotes://tasks/missed".toUri()
            putExtra(EXTRA_OPEN_TASKS, true)
        }
        val contentPending = PendingIntent.getActivity(
            context,
            SUMMARY_NOTIFICATION_ID,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val inboxStyle = Notification.InboxStyle().setBigContentTitle(batch.headline)
        batch.detailLines.forEach(inboxStyle::addLine)

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(batch.headline)
            .setContentText(batch.detailLines.firstOrNull().orEmpty())
            .setStyle(inboxStyle)
            .setContentIntent(contentPending)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .build()

        return try {
            manager.notify(SUMMARY_NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun buildSystemClockAlarmIntent(hour: Int, minute: Int, label: String): Intent {
        val spec = normalizeSystemClockAlarm(hour = hour, minute = minute, label = label)
        return Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, spec.hour)
            putExtra(AlarmClock.EXTRA_MINUTES, spec.minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, spec.label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, spec.skipUi)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun openNotificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    @RequiresApi(Build.VERSION_CODES.S)
    fun openExactAlarmSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = "package:${context.packageName}".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun openFullScreenIntentSettingsIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                data = "package:${context.packageName}".toUri()
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            openNotificationSettingsIntent(context)
        }

    fun openBatteryOptimizationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = "package:${context.packageName}".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun batteryOptimizationActionOrder(): List<String> = listOf(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
    )

    fun safeLaunchSettingsIntent(context: Context, vararg intents: Intent): Boolean {
        for (intent in intents) {
            if (runCatching { context.startActivity(intent) }.isSuccess) {
                return true
            }
        }
        return false
    }

    fun launchNotificationSettings(context: Context): Boolean =
        safeLaunchSettingsIntent(
            context,
            openNotificationSettingsIntent(context),
            openAppDetailsIntent(context),
        )

    fun launchExactAlarmSettings(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            safeLaunchSettingsIntent(
                context,
                openExactAlarmSettingsIntent(context),
                openAppDetailsIntent(context),
            )
        } else {
            safeLaunchSettingsIntent(context, openAppDetailsIntent(context))
        }

    fun launchFullScreenIntentSettings(context: Context): Boolean =
        safeLaunchSettingsIntent(
            context,
            openFullScreenIntentSettingsIntent(context),
            openNotificationSettingsIntent(context),
            openAppDetailsIntent(context),
        )

    fun launchBatteryOptimizationSettings(context: Context): Boolean =
        safeLaunchSettingsIntent(
            context,
            openBatteryOptimizationSettingsIntent(context),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            openAppDetailsIntent(context),
        )

    fun openAppDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = "package:${context.packageName}".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun openTaskPendingIntent(
        context: Context,
        reminderId: String,
        noteId: String,
        blockId: String,
    ): PendingIntent {
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = "floatingnotes://task/$noteId/$blockId".toUri()
            putExtra(EXTRA_OPEN_TASKS, true)
            putExtra(EXTRA_NOTE_ID, noteId)
            putExtra(EXTRA_BLOCK_ID, blockId)
            putExtra(EXTRA_REMINDER_ID, reminderId)
        }
        return PendingIntent.getActivity(
            context,
            requestCodeFor(reminderId, KIND_OPEN),
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun alarmClockShowIntent(context: Context, reminder: ReminderEntity): PendingIntent =
        openTaskPendingIntent(
            context = context,
            reminderId = reminder.id,
            noteId = reminder.noteId,
            blockId = reminder.blockId,
        )

    private fun alarmPendingIntent(context: Context, reminder: ReminderEntity): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCodeFor(reminder.id, KIND_FIRE),
            fireIntent(
                context = context,
                reminderId = reminder.id,
                noteId = reminder.noteId,
                blockId = reminder.blockId,
                occurrenceId = reminder.occurrenceId,
                revision = reminder.revision,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun fireIntent(
        context: Context,
        reminderId: String,
        noteId: String,
        blockId: String,
        occurrenceId: String?,
        revision: Long = -1L,
    ): Intent = Intent(context, ReminderReceiver::class.java).apply {
        action = ACTION_FIRE_REMINDER
        data = "floatingnotes://reminder/$reminderId/fire".toUri()
        putExtra(EXTRA_REMINDER_ID, reminderId)
        putExtra(EXTRA_NOTE_ID, noteId)
        putExtra(EXTRA_BLOCK_ID, blockId)
        occurrenceId?.let { putExtra(EXTRA_OCCURRENCE_ID, it) }
        if (revision > 0L) putExtra(EXTRA_REMINDER_REVISION, revision)
    }

    private fun actionIntent(
        context: Context,
        actionName: String,
        reminder: ReminderEntity,
    ): Intent = actionIntent(
        context = context,
        actionName = actionName,
        reminderId = reminder.id,
        noteId = reminder.noteId,
        blockId = reminder.blockId,
        occurrenceId = reminder.occurrenceId,
        revision = reminder.revision,
    )

    internal fun actionIntent(
        context: Context,
        actionName: String,
        reminderId: String,
        noteId: String,
        blockId: String,
        occurrenceId: String?,
        revision: Long,
    ): Intent = Intent(context, ReminderReceiver::class.java).apply {
        action = actionName
        data = "floatingnotes://reminder/$reminderId/$actionName".toUri()
        putExtra(EXTRA_REMINDER_ID, reminderId)
        putExtra(EXTRA_NOTE_ID, noteId)
        putExtra(EXTRA_BLOCK_ID, blockId)
        occurrenceId?.let { putExtra(EXTRA_OCCURRENCE_ID, it) }
        if (revision > 0L) putExtra(EXTRA_REMINDER_REVISION, revision)
    }

    private fun requestCodeFor(reminderId: String, kind: Int): Int =
        ((reminderId.hashCode() * 31 + kind) and 0x3FFFFFFF)

    internal fun notificationIdFor(reminderId: String): Int {
        val raw = (reminderId.hashCode() and 0x0FFFFFFF)
        return if (raw == 0 || raw == SUMMARY_NOTIFICATION_ID) raw + 100 else raw
    }

    private const val KIND_FIRE = 1
    private const val KIND_OPEN = 2
    private const val KIND_COMPLETE = 3
    private const val KIND_SNOOZE = 4
    private const val KIND_FULL_SCREEN = 5
    private const val KIND_DISMISS = 6
}
