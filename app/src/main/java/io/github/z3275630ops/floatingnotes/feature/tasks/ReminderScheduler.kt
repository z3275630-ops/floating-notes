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
import android.os.Build
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

internal data class ScheduleOutcome(
    val scheduled: Boolean,
    val exactUsed: Boolean,
)

internal object ReminderScheduler {
    const val CHANNEL_ID = "task_reminders"
    const val SUMMARY_NOTIFICATION_ID = 40999

    const val ACTION_FIRE_REMINDER = "io.github.z3275630ops.floatingnotes.action.FIRE_REMINDER"
    const val ACTION_COMPLETE_TASK = "io.github.z3275630ops.floatingnotes.action.COMPLETE_TASK"
    const val ACTION_SNOOZE_TASK = "io.github.z3275630ops.floatingnotes.action.SNOOZE_TASK"

    const val EXTRA_NOTE_ID = "extra_note_id"
    const val EXTRA_BLOCK_ID = "extra_block_id"
    const val EXTRA_OPEN_TASKS = "extra_open_tasks"
    const val EXTRA_REMINDER_ID = "extra_reminder_id"
    const val EXTRA_OCCURRENCE_ID = "extra_occurrence_id"
    const val EXTRA_REMINDER_REVISION = "extra_reminder_revision"

    fun capability(context: Context): ReminderCapability = ReminderCapability(
        notificationsEnabled = notificationsGranted(context),
        exactAlarmAllowed = exactAlarmAllowed(context),
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

    fun scheduleReminder(context: Context, reminder: ReminderEntity): ScheduleOutcome {
        val triggerMs = reminder.triggerInstantMs
        if (reminder.statusEnum != ReminderStatus.ACTIVE || triggerMs == null) {
            cancelReminder(context, reminder.id)
            return ScheduleOutcome(scheduled = false, exactUsed = false)
        }
        val alarmManager = context.getSystemService(AlarmManager::class.java)
            ?: return ScheduleOutcome(scheduled = false, exactUsed = false)
        val pendingIntent = alarmPendingIntent(context, reminder)

        var exactUsed = false
        if (exactAlarmAllowed(context)) {
            exactUsed = try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pendingIntent)
                true
            } catch (_: SecurityException) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pendingIntent)
                false
            }
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pendingIntent)
        }
        return ScheduleOutcome(scheduled = true, exactUsed = exactUsed)
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

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = "floatingnotes://task/${reminder.noteId}/${reminder.blockId}".toUri()
            putExtra(EXTRA_OPEN_TASKS, true)
            putExtra(EXTRA_NOTE_ID, reminder.noteId)
            putExtra(EXTRA_BLOCK_ID, reminder.blockId)
            putExtra(EXTRA_REMINDER_ID, reminder.id)
        }
        val contentPending = PendingIntent.getActivity(
            context,
            requestCodeFor(reminder.id, KIND_OPEN),
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
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

    fun openAppDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = "package:${context.packageName}".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

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
    ): Intent = Intent(context, ReminderReceiver::class.java).apply {
        action = actionName
        data = "floatingnotes://reminder/${reminder.id}/$actionName".toUri()
        putExtra(EXTRA_REMINDER_ID, reminder.id)
        putExtra(EXTRA_NOTE_ID, reminder.noteId)
        putExtra(EXTRA_BLOCK_ID, reminder.blockId)
        reminder.occurrenceId?.let { putExtra(EXTRA_OCCURRENCE_ID, it) }
        if (reminder.revision > 0L) putExtra(EXTRA_REMINDER_REVISION, reminder.revision)
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
}
