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
        private fun isRunningInstrumentationTest(): Boolean =
            runCatching {
                Class.forName("androidx.test.platform.app.InstrumentationRegistry")
            }.isSuccess

        suspend fun handleIntent(
            appContext: Context,
            intent: Intent,
            repository: NotesRepository,
            zoneId: ZoneId = ZoneId.systemDefault(),
            now: Instant = Instant.now(),
            allowStrongAlarmEffects: Boolean = !isRunningInstrumentationTest(),
        ) {
            when (intent.action) {
                ReminderScheduler.ACTION_FIRE_REMINDER -> {
                    val reminderId = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_ID) ?: return
                    val expectedOccurrenceId = intent.getStringExtra(ReminderScheduler.EXTRA_OCCURRENCE_ID)
                    val expectedRevision = intent.getLongExtra(ReminderScheduler.EXTRA_REMINDER_REVISION, -1L)
                        .takeIf { it > 0L }
                    val payload = repository.prepareReminderDelivery(
                        reminderId = reminderId,
                        expectedOccurrenceId = expectedOccurrenceId,
                        expectedRevision = expectedRevision,
                        now = now,
                    ) ?: run {
                        AlarmPlaybackService.stopForReminder(appContext, reminderId)
                        ReminderScheduler.cancelNotification(appContext, reminderId)
                        return
                    }

                    val health = ReminderScheduler.inspectAlarmHealth(appContext)
                    val strongEnabled = ReminderScheduler.isStrongAlarmEnabled(appContext)
                    val deliveryMode = health.deliveryMode(
                        strongAlarmEnabled = strongEnabled,
                        underInstrumentationTest = !allowStrongAlarmEffects,
                    )

                    val notified = when (deliveryMode) {
                        AlarmDeliveryMode.STRONG_FULL_SCREEN,
                        AlarmDeliveryMode.STRONG_HEADS_UP_DEGRADED -> {
                            val includeFullScreen = deliveryMode == AlarmDeliveryMode.STRONG_FULL_SCREEN
                            val serviceStarted = AlarmPlaybackService.start(
                                context = appContext,
                                payload = payload,
                                includeFullScreenIntent = includeFullScreen,
                            )
                            if (serviceStarted) {
                                true
                            } else {
                                ReminderScheduler.showStrongAlarmNotification(
                                    context = appContext,
                                    reminder = payload.reminder,
                                    taskText = payload.taskText,
                                    noteLabel = payload.noteLabel,
                                    includeFullScreenIntent = includeFullScreen,
                                    silent = false,
                                    ongoing = false,
                                )
                            }
                        }

                        AlarmDeliveryMode.STANDARD_NOTIFICATION -> {
                            ReminderScheduler.showReminderNotification(
                                context = appContext,
                                reminder = payload.reminder,
                                taskText = payload.taskText,
                                noteLabel = payload.noteLabel,
                            )
                        }
                    }

                    if (notified) {
                        repository.markReminderNotifiedOnTime(payload.reminder.id, now = now)
                    }
                }

                ReminderScheduler.ACTION_COMPLETE_TASK -> {
                    val noteId = intent.getStringExtra(ReminderScheduler.EXTRA_NOTE_ID) ?: return
                    val blockId = intent.getStringExtra(ReminderScheduler.EXTRA_BLOCK_ID) ?: return
                    val reminderId = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_ID)
                    val occurrenceId = intent.getStringExtra(ReminderScheduler.EXTRA_OCCURRENCE_ID)
                    val expectedRevision = intent.getLongExtra(ReminderScheduler.EXTRA_REMINDER_REVISION, -1L)
                        .takeIf { it > 0L }
                    AlarmPlaybackService.stopForReminder(appContext, reminderId)
                    reminderId?.let { ReminderScheduler.cancelNotification(appContext, it) }
                    val result = repository.completeFromNotification(
                        noteId = noteId,
                        blockId = blockId,
                        expectedReminderId = reminderId,
                        expectedOccurrenceId = occurrenceId,
                        expectedRevision = expectedRevision,
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
                    val expectedRevision = intent.getLongExtra(ReminderScheduler.EXTRA_REMINDER_REVISION, -1L)
                        .takeIf { it > 0L }
                    val requestedSnooze = if (intent.hasExtra(ReminderScheduler.EXTRA_SNOOZE_MINUTES)) {
                        intent.getLongExtra(ReminderScheduler.EXTRA_SNOOZE_MINUTES, ReminderScheduler.DEFAULT_SNOOZE_MINUTES)
                    } else {
                        null
                    }
                    val snoozeMinutes = ReminderScheduler.resolveSnoozeMinutes(requestedSnooze)
                    AlarmPlaybackService.stopForReminder(appContext, reminderId)
                    ReminderScheduler.cancelNotification(appContext, reminderId)
                    val snoozed = repository.snoozeReminder(
                        reminderId = reminderId,
                        snoozeMinutes = snoozeMinutes,
                        now = now,
                        expectedOccurrenceId = occurrenceId,
                        expectedRevision = expectedRevision,
                    )
                    if (snoozed != null) {
                        ReminderScheduler.scheduleReminder(appContext, snoozed)
                    }
                }

                ReminderScheduler.ACTION_DISMISS_ALARM -> {
                    val reminderId = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_ID)
                    AlarmPlaybackService.stopForReminder(appContext, reminderId)
                    reminderId?.let {
                        ReminderScheduler.cancelNotification(appContext, it)
                        repository.markReminderNotifiedOnTime(it, now = now)
                    }
                }
            }
        }
    }
}
