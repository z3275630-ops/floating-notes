package io.github.z3275630ops.floatingnotes.feature.tasks

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.z3275630ops.floatingnotes.data.local.NotesDatabase
import io.github.z3275630ops.floatingnotes.data.local.NotesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

internal class BootTimezoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!isHandledAction(intent.action)) return
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                runCatching {
                    val database = NotesDatabase.open(appContext)
                    val repository = NotesRepository(database)
                    reconcileAndNotify(appContext, repository)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        fun isHandledAction(action: String?): Boolean {
            if (action == null) return false
            if (action == Intent.ACTION_BOOT_COMPLETED ||
                action == Intent.ACTION_MY_PACKAGE_REPLACED ||
                action == Intent.ACTION_TIMEZONE_CHANGED ||
                action == Intent.ACTION_TIME_CHANGED
            ) {
                return true
            }
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        }

        suspend fun reconcileAndNotify(
            appContext: Context,
            repository: NotesRepository,
            currentZone: ZoneId = ZoneId.systemDefault(),
            now: Instant = Instant.now(),
            canPostNotifications: (Context) -> Boolean = ReminderScheduler::notificationsGranted,
            postSummary: (Context, MissedSummaryBatch) -> Boolean = ReminderScheduler::showMissedSummaryNotification,
        ) {
            val reconciled = repository.reconcileTimeChange(currentZone = currentZone, now = now)
            ReminderScheduler.syncReconcile(appContext, reconciled)

            if (!canPostNotifications(appContext)) return
            ReminderScheduler.ensureChannel(appContext)
            ReminderScheduler.ensureStrongAlarmChannel(appContext)
            val missedItems = repository.pendingMissedReminders(
                currentZone = currentZone,
                now = now,
            )
            val batch = TaskRecurrence.batchMissedReminders(missedItems, currentZone) ?: return
            if (postSummary(appContext, batch)) {
                repository.markMissedSummarized(
                    reminderIds = missedItems.map { it.reminderId },
                    now = now,
                )
            }
        }
    }
}
