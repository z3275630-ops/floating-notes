package io.github.z3275630ops.floatingnotes.feature.tasks

import io.github.z3275630ops.floatingnotes.data.local.MissedReminderSummaryItem
import io.github.z3275630ops.floatingnotes.data.local.RecurrenceRule
import io.github.z3275630ops.floatingnotes.data.local.ReminderPolicy
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal enum class TaskBucket {
    OVERDUE,
    TODAY,
    UPCOMING,
    UNSCHEDULED,
    COMPLETED,
}

internal enum class TasksFilter(val label: String) {
    TODAY("今天"),
    UPCOMING("未来"),
    COMPLETED("已完成"),
}

internal data class ResolvedTrigger(
    val instant: Instant,
    val zonedDateTime: ZonedDateTime,
    val wasGapAdjusted: Boolean = false,
    val wasOverlap: Boolean = false,
)

internal data class MissedSummaryBatch(
    val count: Int,
    val headline: String,
    val detailLines: List<String>,
    val items: List<MissedReminderSummaryItem>,
)

internal object TaskRecurrence {
    private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

    fun parseDueDate(raw: String?): LocalDate? =
        raw?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    fun parseLocalTime(raw: String?): LocalTime? =
        raw?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalTime.parse(it) }.getOrNull() }

    fun formatLocalTime(time: LocalTime): String = TIME_FORMAT.format(time)

    fun parseZoneOrDefault(zoneId: String?, fallback: ZoneId = ZoneId.systemDefault()): ZoneId =
        zoneId?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: fallback

    /**
     * Resolves a wall-clock [localDate] + [localTime] in [zoneId] using real [java.time.zone.ZoneRules]:
     * - Spring-forward DST gap advances to the first valid instant on that day.
     * - Fall-back DST overlap fires once at the earlier offset.
     */
    fun resolveTrigger(localDate: LocalDate, localTime: LocalTime, zoneId: ZoneId): ResolvedTrigger {
        val wallTime = LocalDateTime.of(localDate, localTime)
        val rules = zoneId.rules
        val transition = rules.getTransition(wallTime)
        if (transition != null) {
            if (transition.isGap) {
                val resolved = ZonedDateTime.ofInstant(transition.instant, zoneId)
                return ResolvedTrigger(
                    instant = transition.instant,
                    zonedDateTime = resolved,
                    wasGapAdjusted = true,
                    wasOverlap = false,
                )
            }
            if (transition.isOverlap) {
                val resolved = ZonedDateTime.ofLocal(wallTime, zoneId, transition.offsetBefore)
                return ResolvedTrigger(
                    instant = resolved.toInstant(),
                    zonedDateTime = resolved,
                    wasGapAdjusted = false,
                    wasOverlap = true,
                )
            }
        }
        val offset = rules.getOffset(wallTime)
        val resolved = ZonedDateTime.ofLocal(wallTime, zoneId, offset)
        return ResolvedTrigger(
            instant = resolved.toInstant(),
            zonedDateTime = resolved,
            wasGapAdjusted = false,
            wasOverlap = false,
        )
    }

    fun recomputeForZoneChange(
        localDate: LocalDate,
        localTime: LocalTime,
        originZoneId: String,
        currentZone: ZoneId,
        policy: ReminderPolicy,
    ): Pair<ZoneId, ResolvedTrigger> {
        val targetZone = when (policy) {
            ReminderPolicy.FOLLOW_LOCAL -> currentZone
            ReminderPolicy.KEEP_ORIGIN_ZONE -> parseZoneOrDefault(originZoneId, currentZone)
        }
        return targetZone to resolveTrigger(localDate, localTime, targetZone)
    }

    fun nextDate(baseDate: LocalDate, rule: RecurrenceRule): LocalDate = when (rule) {
        RecurrenceRule.NONE -> baseDate
        RecurrenceRule.DAILY -> baseDate.plusDays(1)
        RecurrenceRule.WEEKLY -> baseDate.plusWeeks(1)
    }

    /**
     * Classifies tasks strictly by date-only [LocalDate] comparison in the user's local zone
     * (never UTC midnight).
     */
    fun classifyTask(
        dueDate: LocalDate?,
        reminderDate: LocalDate?,
        checked: Boolean,
        today: LocalDate,
    ): TaskBucket {
        if (checked) return TaskBucket.COMPLETED
        val anchorDate = dueDate ?: reminderDate ?: return TaskBucket.UNSCHEDULED
        return when {
            anchorDate.isBefore(today) -> TaskBucket.OVERDUE
            anchorDate == today -> TaskBucket.TODAY
            else -> TaskBucket.UPCOMING
        }
    }

    fun effectiveLocalDate(
        dueDateRaw: String?,
        reminderLocalDateRaw: String?,
        triggerInstantMs: Long?,
        zoneId: ZoneId,
    ): LocalDate? {
        parseDueDate(dueDateRaw)?.let { return it }
        parseDueDate(reminderLocalDateRaw)?.let { return it }
        return triggerInstantMs?.let { Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate() }
    }

    fun batchMissedReminders(
        items: List<MissedReminderSummaryItem>,
        currentZone: ZoneId = ZoneId.systemDefault(),
    ): MissedSummaryBatch? {
        val unique = items.distinctBy { it.noteId to it.blockId }
        if (unique.isEmpty()) return null
        val sorted = unique.sortedBy { it.scheduledInstantMs ?: Long.MAX_VALUE }
        val headline = if (sorted.size == 1) {
            "错过了 1 条待办提醒"
        } else {
            "错过了 ${sorted.size} 条待办提醒"
        }
        val lines = sorted.take(4).map { item ->
            val timeTag = item.scheduledInstantMs?.let { ms ->
                val local = Instant.ofEpochMilli(ms).atZone(currentZone)
                "${local.monthValue}/${local.dayOfMonth} ${TIME_FORMAT.format(local.toLocalTime())}"
            }
            val title = item.taskText.ifBlank { item.noteLabel }
            if (timeTag != null) "$title · $timeTag" else title
        }.let { preview ->
            if (sorted.size > 4) preview + "等共 ${sorted.size} 项，点此查看待办" else preview
        }
        return MissedSummaryBatch(
            count = sorted.size,
            headline = headline,
            detailLines = lines,
            items = sorted,
        )
    }
}
