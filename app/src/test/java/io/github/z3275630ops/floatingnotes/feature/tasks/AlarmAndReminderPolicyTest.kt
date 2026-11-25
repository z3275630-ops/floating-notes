package io.github.z3275630ops.floatingnotes.feature.tasks

import android.content.Intent
import android.provider.Settings
import io.github.z3275630ops.floatingnotes.feature.widget.WidgetContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmAndReminderPolicyTest {

    @Test
    fun resolveScheduleModePrefersAlarmClockOnlyWhenStrongAndExactAllowed() {
        assertEquals(
            AlarmScheduleMode.ALARM_CLOCK,
            ReminderScheduler.resolveScheduleMode(strongAlarmEnabled = true, exactAlarmAllowed = true),
        )
        assertEquals(
            AlarmScheduleMode.EXACT_IDLE,
            ReminderScheduler.resolveScheduleMode(strongAlarmEnabled = false, exactAlarmAllowed = true),
        )
        assertEquals(
            AlarmScheduleMode.INEXACT_IDLE,
            ReminderScheduler.resolveScheduleMode(strongAlarmEnabled = true, exactAlarmAllowed = false),
        )
        assertEquals(
            AlarmScheduleMode.INEXACT_IDLE,
            ReminderScheduler.resolveScheduleMode(strongAlarmEnabled = false, exactAlarmAllowed = false),
        )
    }

    @Test
    fun alarmHealthDeliveryModeDegradesCleanlyAndRespectsTestGuard() {
        val fullHealth = AlarmHealthStatus(
            notificationsGranted = true,
            exactAlarmAllowed = true,
            fullScreenIntentAllowed = true,
            ignoringBatteryOptimizations = true,
        )
        assertTrue(fullHealth.allHealthy)
        assertTrue(fullHealth.coreAlarmReady)
        assertEquals(0, fullHealth.issueCount)
        assertEquals(
            AlarmDeliveryMode.STRONG_FULL_SCREEN,
            fullHealth.deliveryMode(strongAlarmEnabled = true, underInstrumentationTest = false),
        )
        assertEquals(
            AlarmDeliveryMode.STANDARD_NOTIFICATION,
            fullHealth.deliveryMode(strongAlarmEnabled = false, underInstrumentationTest = false),
        )
        assertEquals(
            AlarmDeliveryMode.STANDARD_NOTIFICATION,
            fullHealth.deliveryMode(strongAlarmEnabled = true, underInstrumentationTest = true),
        )

        val noFullScreen = fullHealth.copy(
            fullScreenIntentAllowed = false,
            ignoringBatteryOptimizations = false,
        )
        assertFalse(noFullScreen.allHealthy)
        assertTrue(noFullScreen.coreAlarmReady)
        assertEquals(2, noFullScreen.issueCount)
        assertEquals(
            AlarmDeliveryMode.STRONG_HEADS_UP_DEGRADED,
            noFullScreen.deliveryMode(strongAlarmEnabled = true, underInstrumentationTest = false),
        )
    }

    @Test
    fun normalizeSystemClockAlarmClampsFieldsAndKeepsSkipUiFalse() {
        val clamped = ReminderScheduler.normalizeSystemClockAlarm(
            hour = 29,
            minute = -3,
            label = "   ",
        )
        assertEquals(23, clamped.hour)
        assertEquals(0, clamped.minute)
        assertEquals("浮记待办", clamped.label)
        assertFalse(clamped.skipUi)

        val longLabel = "待办".repeat(60)
        val trimmed = ReminderScheduler.normalizeSystemClockAlarm(
            hour = 9,
            minute = 30,
            label = "  $longLabel  ",
        )
        assertEquals(9, trimmed.hour)
        assertEquals(30, trimmed.minute)
        assertEquals(80, trimmed.label.length)
        assertFalse(trimmed.skipUi)
    }

    @Test
    fun resolveSnoozeMinutesSupportsDefault15AndAlarm10() {
        assertEquals(15L, ReminderScheduler.resolveSnoozeMinutes(null))
        assertEquals(10L, ReminderScheduler.resolveSnoozeMinutes(ReminderScheduler.ALARM_SNOOZE_MINUTES))
        assertEquals(1L, ReminderScheduler.resolveSnoozeMinutes(0L))
        assertEquals(1L, ReminderScheduler.resolveSnoozeMinutes(-20L))
    }

    @Test
    fun notificationIdForIsStableAndAvoidsReservedIds() {
        val ids = listOf("", "rem-1", "rem-2", "summary", "40999")
        for (id in ids) {
            val notifId = ReminderScheduler.notificationIdFor(id)
            assertNotEquals(0, notifId)
            assertNotEquals(ReminderScheduler.SUMMARY_NOTIFICATION_ID, notifId)
            assertEquals(notifId, ReminderScheduler.notificationIdFor(id))
        }
    }

    @Test
    fun bootTimezoneReceiverHandledActionsFilterCorrectly() {
        assertTrue(BootTimezoneReceiver.isHandledAction(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(BootTimezoneReceiver.isHandledAction(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertTrue(BootTimezoneReceiver.isHandledAction(Intent.ACTION_TIMEZONE_CHANGED))
        assertTrue(BootTimezoneReceiver.isHandledAction(Intent.ACTION_TIME_CHANGED))
        assertFalse(BootTimezoneReceiver.isHandledAction(null))
        assertFalse(BootTimezoneReceiver.isHandledAction("io.github.z3275630ops.OTHER"))
    }

    @Test
    fun shortcutActionsAreDistinctAndNonBlank() {
        val actions = listOf(
            WidgetContract.ACTION_SHORTCUT_CAPTURE,
            WidgetContract.ACTION_SHORTCUT_VOICE,
            WidgetContract.ACTION_SHORTCUT_TASKS,
        )
        assertEquals(3, actions.distinct().size)
        assertTrue(actions.all { it.startsWith("io.github.z3275630ops.floatingnotes.ACTION_SHORTCUT_") })
    }

    @Test
    fun batteryOptimizationActionOrderPrefersDirectPromptWithSafeFallbacks() {
        assertEquals(
            listOf(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            ),
            ReminderScheduler.batteryOptimizationActionOrder(),
        )
    }
}
