package io.github.z3275630ops.floatingnotes.feature.tasks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.z3275630ops.floatingnotes.data.local.NotesDatabase
import io.github.z3275630ops.floatingnotes.data.local.NotesRepository
import io.github.z3275630ops.floatingnotes.data.local.TaskToggleResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

internal class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                runCatching {
                    val database = NotesDatabase.open(appContext)
                    val repository = NotesRepository(database)
                    handleIntent(appContext, intent, repository)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        suspend fun handleIntent(
            appContext: Context,
            intent: Intent,
            repository: NotesRepository,
            zoneId: ZoneId = ZoneId.systemDefault(),
            now: Instant = Instant.now(),
        ) {
            when (intent.action) {
                ReminderScheduler.ACTION_FIRE_REMINDER -> {
                    val reminderId = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_ID) ?: return
                    val expectedOccurrenceId = intent.getStringExtra(ReminderScheduler.EXTRA_OCCURRENCE_ID)
                    val payload = repository.prepareReminderDelivery(
                        reminderId = reminderId,
                        expectedOccurrenceId = expectedOccurrenceId,
                        now = now,
                    ) ?: run {
                        ReminderScheduler.cancelNotification(appContext, reminderId)
                        return
                    }
                    val shown = ReminderScheduler.showReminderNotification(
                        context = appContext,
                        reminder = payload.reminder,
                        taskText = payload.taskText,
                        noteLabel = payload.noteLabel,
                    )
                    if (shown) {
                        repository.markReminderNotifiedOnTime(payload.reminder.id, now = now)
                    }
                }

                ReminderScheduler.ACTION_COMPLETE_TASK -> {
                    val noteId = intent.getStringExtra(ReminderScheduler.EXTRA_NOTE_ID) ?: return
                    val blockId = intent.getStringExtra(ReminderScheduler.EXTRA_BLOCK_ID) ?: return
                    val reminderId = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_ID)
                    val occurrenceId = intent.getStringExtra(ReminderScheduler.EXTRA_OCCURRENCE_ID)
                    reminderId?.let { ReminderScheduler.cancelNotification(appContext, it) }
                    val result = repository.completeFromNotification(
                        noteId = noteId,
                        blockId = blockId,
                        expectedReminderId = reminderId,
                        expectedOccurrenceId = occurrenceId,
                        zoneId = zoneId,
                        now = now,
                    )
                    if (result is TaskToggleResult.Updated) {
                        ReminderScheduler.syncToggleResult(appContext, result)
                    }
                }

                ReminderScheduler.ACTION_SNOOZE_TASK -> {
                    val reminderId = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_ID) ?: return
                    val occurrenceId = intent.getStringExtra(ReminderScheduler.EXTRA_OCCURRENCE_ID)
                    ReminderScheduler.cancelNotification(appContext, reminderId)
                    val snoozed = repository.snoozeReminder(
                        reminderId = reminderId,
                        snoozeMinutes = 15L,
                        now = now,
                        expectedOccurrenceId = occurrenceId,
                    )
                    if (snoozed != null) {
                        ReminderScheduler.scheduleReminder(appContext, snoozed)
                    }
                }
            }
        }
    }
}
