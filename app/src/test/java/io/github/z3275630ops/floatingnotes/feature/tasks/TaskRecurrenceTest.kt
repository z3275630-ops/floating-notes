package io.github.z3275630ops.floatingnotes.feature.tasks

import io.github.z3275630ops.floatingnotes.data.local.MissedReminderSummaryItem
import io.github.z3275630ops.floatingnotes.data.local.RecurrenceRule
import io.github.z3275630ops.floatingnotes.data.local.ReminderPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class TaskRecurrenceTest {

    @Test
    fun dateOnlyDueDateNeverShiftsToUtcMidnightAcrossTimezones() {
        val dueDate = TaskRecurrence.parseDueDate("2026-11-14")
        assertNotNull(dueDate)

        // Same calendar day in UTC-8 and UTC+8 must both classify as TODAY.
        val westDay = LocalDate.of(2026, 11, 14)
        val eastDay = LocalDate.of(2026, 11, 14)
        assertEquals(TaskBucket.TODAY, TaskRecurrence.classifyTask(dueDate, null, checked = false, today = westDay))
        assertEquals(TaskBucket.TODAY, TaskRecurrence.classifyTask(dueDate, null, checked = false, today = eastDay))

        assertEquals(
            TaskBucket.OVERDUE,
            TaskRecurrence.classifyTask(dueDate, null, checked = false, today = LocalDate.of(2026, 11, 15)),
        )
        assertEquals(
            TaskBucket.UPCOMING,
            TaskRecurrence.classifyTask(dueDate, null, checked = false, today = LocalDate.of(2026, 11, 13)),
        )
        assertEquals(
            TaskBucket.UNSCHEDULED,
            TaskRecurrence.classifyTask(null, null, checked = false, today = westDay),
        )
        assertEquals(
            TaskBucket.COMPLETED,
            TaskRecurrence.classifyTask(dueDate, null, checked = true, today = westDay),
        )
    }

    @Test
    fun dstSpringForwardGapResolvesToFirstValidInstantOnSameDay() {
        val newYork = ZoneId.of("America/New_York")
        // 2026-03-08: 02:00 jumps directly to 03:00 EDT.
        val resolved = TaskRecurrence.resolveTrigger(
            localDate = LocalDate.of(2026, 3, 8),
            localTime = LocalTime.of(2, 30),
            zoneId = newYork,
        )

        assertTrue(resolved.wasGapAdjusted)
        assertFalse(resolved.wasOverlap)
        assertEquals(LocalDate.of(2026, 3, 8), resolved.zonedDateTime.toLocalDate())
        assertEquals(LocalTime.of(3, 0), resolved.zonedDateTime.toLocalTime())
        assertEquals(
            ZonedDateTime.of(2026, 3, 8, 3, 0, 0, 0, newYork).toInstant(),
            resolved.instant,
        )
    }

    @Test
    fun dstFallBackOverlapFiresOnceAtEarlierOffset() {
        val newYork = ZoneId.of("America/New_York")
        // 2026-11-01: 01:00..02:00 occurs in -04:00 (EDT) then -05:00 (EST).
        val resolved = TaskRecurrence.resolveTrigger(
            localDate = LocalDate.of(2026, 11, 1),
            localTime = LocalTime.of(1, 30),
            zoneId = newYork,
        )

        assertFalse(resolved.wasGapAdjusted)
        assertTrue(resolved.wasOverlap)
        assertEquals(ZoneOffset.ofHours(-4), resolved.zonedDateTime.offset)
        assertEquals(LocalTime.of(1, 30), resolved.zonedDateTime.toLocalTime())
    }

    @Test
    fun dailyAndWeeklyRecurrencePreservesWallClockDateAcrossDst() {
        val start = LocalDate.of(2026, 3, 7)
        val nextDay = TaskRecurrence.nextDate(start, RecurrenceRule.DAILY)
        assertEquals(LocalDate.of(2026, 3, 8), nextDay)

        val newYork = ZoneId.of("America/New_York")
        val nextTrigger = TaskRecurrence.resolveTrigger(nextDay, LocalTime.of(9, 0), newYork)
        assertEquals(LocalTime.of(9, 0), nextTrigger.zonedDateTime.toLocalTime())
        assertEquals(ZoneOffset.ofHours(-4), nextTrigger.zonedDateTime.offset)

        assertEquals(LocalDate.of(2026, 3, 14), TaskRecurrence.nextDate(start, RecurrenceRule.WEEKLY))
        assertEquals(start, TaskRecurrence.nextDate(start, RecurrenceRule.NONE))
    }

    @Test
    fun timezonePolicyDistinguishesFollowLocalAndKeepOriginZone() {
        val localDate = LocalDate.of(2026, 11, 14)
        val localTime = LocalTime.of(9, 0)
        val tokyo = ZoneId.of("Asia/Tokyo")
        val losAngeles = ZoneId.of("America/Los_Angeles")

        val (followZone, followTrigger) = TaskRecurrence.recomputeForZoneChange(
            localDate = localDate,
            localTime = localTime,
            originZoneId = tokyo.id,
            currentZone = losAngeles,
            policy = ReminderPolicy.FOLLOW_LOCAL,
        )
        assertEquals(losAngeles, followZone)
        assertEquals(
            ZonedDateTime.of(2026, 11, 14, 9, 0, 0, 0, losAngeles).toInstant(),
            followTrigger.instant,
        )

        val (keepZone, keepTrigger) = TaskRecurrence.recomputeForZoneChange(
            localDate = localDate,
            localTime = localTime,
            originZoneId = tokyo.id,
            currentZone = losAngeles,
            policy = ReminderPolicy.KEEP_ORIGIN_ZONE,
        )
        assertEquals(tokyo, keepZone)
        assertEquals(
            ZonedDateTime.of(2026, 11, 14, 9, 0, 0, 0, tokyo).toInstant(),
            keepTrigger.instant,
        )
    }

    @Test
    fun missedRemindersBatchIntoSingleDeduplicatedSummary() {
        val base = Instant.parse("2026-11-14T01:00:00Z")
        val items = (1..6).map { index ->
            MissedReminderSummaryItem(
                reminderId = "r$index",
                noteId = "n$index",
                blockId = "b$index",
                taskText = "待办 $index",
                noteLabel = "笔记 $index",
                scheduledInstantMs = base.plusSeconds(index * 600L).toEpochMilli(),
            )
        } + MissedReminderSummaryItem(
            reminderId = "r1-dup",
            noteId = "n1",
            blockId = "b1",
            taskText = "待办 1",
            noteLabel = "笔记 1",
            scheduledInstantMs = base.plusSeconds(600L).toEpochMilli(),
        )

        val batch = TaskRecurrence.batchMissedReminders(items, ZoneId.of("Asia/Shanghai"))
        assertNotNull(batch)
        assertEquals(6, batch!!.count)
        assertEquals("错过了 6 条待办提醒", batch.headline)
        assertEquals(5, batch.detailLines.size)
        assertTrue(batch.detailLines.last().contains("等共 6 项"))
    }
}
