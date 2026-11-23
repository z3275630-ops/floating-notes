package io.github.z3275630ops.floatingnotes.feature.tasks

import io.github.z3275630ops.floatingnotes.data.local.RecurrenceRule
import io.github.z3275630ops.floatingnotes.data.local.ReminderStatus
import io.github.z3275630ops.floatingnotes.data.local.TaskItemProjection
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

internal enum class TaskUrgencyTier(val label: String) {
    CRITICAL("紧急逾期"),
    HIGH("今日优先"),
    MEDIUM("近期关注"),
    STALE("搁置待清"),
    NORMAL("常规待办"),
}

internal data class TaskUrgencyScore(
    val score: Int,
    val tier: TaskUrgencyTier,
    val badgeLabel: String?,
    val reason: String,
)

internal object TaskUrgencyEngine {
    private val URGENT_KEYWORDS = listOf(
        "紧急",
        "必须",
        "截止",
        "ddl",
        "urgent",
        "asap",
        "提交",
        "缴费",
        "报销",
        "面试",
        "体检",
        "续费",
    )

    fun evaluate(
        item: TaskItemProjection,
        today: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): TaskUrgencyScore {
        val effectiveDate = TaskRecurrence.effectiveLocalDate(
            dueDateRaw = item.dueDate,
            reminderLocalDateRaw = item.reminderLocalDate,
            triggerInstantMs = item.triggerInstantMs,
            zoneId = zoneId,
        )
        return evaluateForLocalDate(
            text = item.text,
            due = effectiveDate,
            updatedAtIso = item.noteUpdatedAt,
            hasActiveReminder = item.reminderStatus == ReminderStatus.ACTIVE,
            reminderMissed = item.reminderStatus == ReminderStatus.MISSED,
            isSnoozed = item.snoozedUntilMs != null,
            isRecurring = item.recurrence != RecurrenceRule.NONE,
            today = today,
            zoneId = zoneId,
        )
    }

    fun evaluateRaw(
        text: String,
        dueDate: String?,
        updatedAtIso: String?,
        hasActiveReminder: Boolean,
        reminderMissed: Boolean,
        isSnoozed: Boolean,
        isRecurring: Boolean,
        today: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): TaskUrgencyScore {
        return evaluateForLocalDate(
            text = text,
            due = TaskRecurrence.parseDueDate(dueDate),
            updatedAtIso = updatedAtIso,
            hasActiveReminder = hasActiveReminder,
            reminderMissed = reminderMissed,
            isSnoozed = isSnoozed,
            isRecurring = isRecurring,
            today = today,
            zoneId = zoneId,
        )
    }

    fun sortByUrgency(
        items: List<TaskItemProjection>,
        today: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): List<TaskItemProjection> {
        if (items.size <= 1) return items
        val scored = items.map { item -> item to evaluate(item, today, zoneId).score }
        return scored.sortedWith(
            compareByDescending<Pair<TaskItemProjection, Int>> { it.second }
                .thenBy { it.first.dueDate ?: "9999-12-31" }
                .thenBy { it.first.reminderLocalTime ?: "99:99" }
                .thenByDescending { it.first.noteUpdatedAt },
        ).map { it.first }
    }

    private fun evaluateForLocalDate(
        text: String,
        due: LocalDate?,
        updatedAtIso: String?,
        hasActiveReminder: Boolean,
        reminderMissed: Boolean,
        isSnoozed: Boolean,
        isRecurring: Boolean,
        today: LocalDate,
        zoneId: ZoneId,
    ): TaskUrgencyScore {
        val lowerText = text.lowercase(Locale.ROOT)
        val hasUrgentKeyword = URGENT_KEYWORDS.any { lowerText.contains(it) }
        val reasons = ArrayList<String>()

        // Signed days from today to due date: negative = overdue, 0 = today, positive = future.
        val daysUntilDue: Int? = due?.let { ChronoUnit.DAYS.between(today, it).toInt() }

        var score: Int
        var tier: TaskUrgencyTier
        var badge: String?

        if (daysUntilDue != null) {
            when {
                daysUntilDue < 0 -> {
                    val overdueDays = -daysUntilDue
                    score = 80 + minOf(overdueDays * 6, 40)
                    tier = TaskUrgencyTier.CRITICAL
                    badge = "逾期 ${overdueDays} 天"
                    reasons.add("已逾期 ${overdueDays} 天")
                }
                daysUntilDue == 0 -> {
                    score = 72
                    tier = TaskUrgencyTier.HIGH
                    badge = if (hasUrgentKeyword) "今日紧急" else null
                    reasons.add("今日截止")
                }
                daysUntilDue == 1 -> {
                    score = 58
                    tier = TaskUrgencyTier.MEDIUM
                    badge = "明日截止"
                    reasons.add("明日截止")
                }
                daysUntilDue in 2..3 -> {
                    score = 46
                    tier = TaskUrgencyTier.MEDIUM
                    badge = "${daysUntilDue} 天内"
                    reasons.add("${daysUntilDue} 天内截止")
                }
                daysUntilDue in 4..7 -> {
                    score = 32
                    tier = TaskUrgencyTier.NORMAL
                    badge = null
                    reasons.add("${daysUntilDue} 天后截止")
                }
                else -> {
                    score = 16
                    tier = TaskUrgencyTier.NORMAL
                    badge = null
                    reasons.add("远期截止")
                }
            }
        } else {
            val updatedDate = parseLocalDateFromIso(updatedAtIso, zoneId)
            val staleDays = if (updatedDate != null) {
                ChronoUnit.DAYS.between(updatedDate, today).toInt().coerceAtLeast(0)
            } else {
                0
            }
            if (staleDays >= 14) {
                score = 28 + minOf(staleDays - 14, 20)
                tier = TaskUrgencyTier.STALE
                badge = "搁置 ${staleDays} 天"
                reasons.add("已搁置 ${staleDays} 天")
            } else {
                score = 10
                tier = TaskUrgencyTier.NORMAL
                badge = null
                reasons.add("未排期常规待办")
            }
        }

        if (reminderMissed) {
            score += 15
            reasons.add("错过提醒")
        }
        if (hasActiveReminder && daysUntilDue != null && daysUntilDue <= 0) {
            score += 10
            reasons.add("含当日提醒")
        }
        if (isSnoozed) {
            score += 6
            reasons.add("已延后提醒")
        }
        if (isRecurring) {
            score += 4
            reasons.add("周期重复")
        }
        if (hasUrgentKeyword) {
            score += 12
            if (badge == null && tier == TaskUrgencyTier.NORMAL) {
                badge = "重要"
                tier = TaskUrgencyTier.MEDIUM
            }
            reasons.add("含重要关键词")
        }

        return TaskUrgencyScore(
            score = score,
            tier = tier,
            badgeLabel = badge,
            reason = reasons.joinToString(" · "),
        )
    }

    private fun parseLocalDateFromIso(raw: String?, zoneId: ZoneId): LocalDate? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        runCatching { return Instant.parse(trimmed).atZone(zoneId).toLocalDate() }
        runCatching { return OffsetDateTime.parse(trimmed).atZoneSameInstant(zoneId).toLocalDate() }
        return TaskRecurrence.parseDueDate(trimmed)
    }
}
