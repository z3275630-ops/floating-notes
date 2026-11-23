package io.github.z3275630ops.floatingnotes.data.discovery

import io.github.z3275630ops.floatingnotes.data.local.NoteEntity
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.round

internal data class FsrsNoteState(
    val noteId: String,
    val stabilityDays: Double,
    val difficulty: Double,
    val reps: Int,
    val lapses: Int,
    val lastReviewAt: Instant?,
    val nextDueAt: Instant,
    val retrievability: Double,
    val urgencyScore: Double,
    val scheduleReason: String,
)

/**
 * Deterministic FSRS-4.5 / SM-2 inspired spaced repetition scheduler computed directly
 * from existing [ReviewEventEntity] logs without schema changes.
 */
internal object FsrsReviewScheduler {

    fun computeNoteStates(
        notes: List<NoteEntity>,
        events: List<ReviewEventEntity>,
        now: Instant,
        zoneId: ZoneId,
    ): Map<String, FsrsNoteState> {
        val localDate = now.atZone(zoneId).toLocalDate()
        return computeNoteStatesForDate(
            notes = notes,
            events = events,
            now = now,
            zoneId = zoneId,
            localDate = localDate,
        )
    }

    fun rankForDailyReview(
        eligibleNotes: List<NoteEntity>,
        events: List<ReviewEventEntity>,
        localDate: LocalDate,
        profileKey: String,
        configHash: String,
        round: Int,
        now: Instant,
        zoneId: ZoneId,
        dailyCount: Int,
    ): List<FsrsNoteState> {
        if (eligibleNotes.isEmpty()) return emptyList()
        val states = computeNoteStatesForDate(
            notes = eligibleNotes,
            events = events,
            now = now,
            zoneId = zoneId,
            localDate = localDate,
        )
        val seedString = "$localDate|$profileKey|$configHash|$round"
        return eligibleNotes
            .mapNotNull { states[it.id] }
            .sortedWith(
                compareByDescending<FsrsNoteState> { floor(it.urgencyScore / 10.0).toInt() }
                    .thenBy { deterministicJitter(seedString, it.noteId) }
                    .thenBy { it.noteId },
            )
            .take(dailyCount.coerceAtLeast(1))
    }

    private fun computeNoteStatesForDate(
        notes: List<NoteEntity>,
        events: List<ReviewEventEntity>,
        now: Instant,
        zoneId: ZoneId,
        localDate: LocalDate,
    ): Map<String, FsrsNoteState> {
        if (notes.isEmpty()) return emptyMap()
        val eventsByNote = events.groupBy { it.noteId }
        val result = LinkedHashMap<String, FsrsNoteState>(notes.size)
        for (note in notes) {
            result[note.id] = computeSingleNoteState(
                note = note,
                noteEvents = eventsByNote[note.id].orEmpty(),
                now = now,
                zoneId = zoneId,
                localDate = localDate,
            )
        }
        return result
    }

    private fun computeSingleNoteState(
        note: NoteEntity,
        noteEvents: List<ReviewEventEntity>,
        now: Instant,
        zoneId: ZoneId,
        localDate: LocalDate,
    ): FsrsNoteState {
        var stability = 1.0
        var difficulty = 5.0
        var reps = 0
        var lapses = 0
        val createdInstant = parseInstant(note.createdAt)
        var anchor = createdInstant ?: now
        var lastReviewAt: Instant? = null
        var lastAction: String? = null

        val deduplicatedEvents = deduplicateSameSessionEvents(noteEvents)
        val sortedEvents = deduplicatedEvents.sortedWith(
            compareBy<ReviewEventEntity>(
                { parseInstant(it.createdAt) ?: Instant.EPOCH },
                { it.createdAt },
                { it.id },
            ),
        )

        for (ev in sortedEvents) {
            when (ev.action) {
                "excluded" -> continue
                "annotated" -> {
                    stability = if (reps == 0) {
                        5.0
                    } else {
                        (stability * (2.2 + 0.15 * (10.0 - difficulty))).coerceAtMost(180.0)
                    }
                    difficulty = (difficulty - 0.6).coerceIn(1.0, 10.0)
                    reps += 1
                    parseInstant(ev.createdAt)?.let { ts ->
                        anchor = ts
                        lastReviewAt = ts
                    }
                    lastAction = ev.action
                }
                "seen", "reviewed" -> {
                    stability = if (reps == 0) {
                        3.0
                    } else {
                        (stability * (1.6 + 0.10 * (10.0 - difficulty))).coerceAtMost(120.0)
                    }
                    difficulty = (difficulty - 0.1).coerceIn(1.0, 10.0)
                    reps += 1
                    parseInstant(ev.createdAt)?.let { ts ->
                        anchor = ts
                        lastReviewAt = ts
                    }
                    lastAction = ev.action
                }
                "deferred" -> {
                    stability = (stability * 0.65).coerceIn(1.0, 14.0)
                    difficulty = (difficulty + 0.8).coerceIn(1.0, 10.0)
                    lapses += 1
                    parseInstant(ev.createdAt)?.let { ts ->
                        anchor = ts
                        lastReviewAt = ts
                    }
                    lastAction = ev.action
                }
                "suppressed_7d" -> {
                    stability = maxOf(stability * 1.8, 14.0).coerceAtMost(180.0)
                    difficulty = (difficulty - 0.3).coerceIn(1.0, 10.0)
                    parseInstant(ev.createdAt)?.let { ts ->
                        anchor = ts
                        lastReviewAt = ts
                    }
                    lastAction = ev.action
                }
            }
        }

        val elapsedDays = maxOf(0.0, Duration.between(anchor, now).toMinutes() / 1440.0)
        val retrievability = (1.0 + elapsedDays / (9.0 * stability)).pow(-1.0)
        val nextDueAt = anchor.plus(round(stability).toLong().coerceAtLeast(1L), ChronoUnit.DAYS)

        val createdDate = (createdInstant ?: now).atZone(zoneId).toLocalDate()
        val ageDays = ChronoUnit.DAYS.between(createdDate, localDate).coerceAtLeast(0L)

        val urgencyScore = if (reps > 0) {
            if (elapsedDays >= stability) {
                100.0 + (1.0 - retrievability) * 100.0 + minOf(lapses * 5.0, 20.0)
            } else {
                (1.0 - retrievability) * 40.0
            }
        } else {
            when {
                ageDays in 7L..90L -> 92.0
                ageDays > 90L -> 86.0
                ageDays in 2L..6L -> 78.0
                else -> 50.0
            }
        }

        val scheduleReason = when {
            reps == 0 && ageDays >= 7L -> "首次重温 · 写于 ${ageDays} 天前"
            reps == 0 && ageDays in 1L..6L -> "新近记录 · 适合巩固"
            reps == 0 -> "今日新记"
            elapsedDays >= stability && lastAction == "annotated" ->
                "批注巩固到期 · 距上次 ${elapsedDays.toInt()} 天"
            elapsedDays >= stability && lastAction == "deferred" ->
                "此前稍后再看 · 今日重温"
            elapsedDays >= stability ->
                "记忆曲线到期 · 距上次 ${elapsedDays.toInt()} 天"
            else ->
                "提前巩固 · 记忆留存 ${(retrievability * 100).toInt()}%"
        }

        return FsrsNoteState(
            noteId = note.id,
            stabilityDays = stability,
            difficulty = difficulty,
            reps = reps,
            lapses = lapses,
            lastReviewAt = lastReviewAt,
            nextDueAt = nextDueAt,
            retrievability = retrievability,
            urgencyScore = urgencyScore,
            scheduleReason = scheduleReason,
        )
    }

    private fun deduplicateSameSessionEvents(noteEvents: List<ReviewEventEntity>): List<ReviewEventEntity> {
        if (noteEvents.size <= 1) return noteEvents
        val result = ArrayList<ReviewEventEntity>(noteEvents.size)
        val bySessionDay = LinkedHashMap<Pair<String, String>, MutableList<ReviewEventEntity>>()
        for (ev in noteEvents) {
            if (ev.sessionId.isBlank()) {
                result.add(ev)
            } else {
                val dayKey = ev.createdAt.take(10)
                bySessionDay.getOrPut(ev.sessionId to dayKey) { ArrayList() }.add(ev)
            }
        }
        for (group in bySessionDay.values) {
            if (group.size == 1) {
                result.add(group[0])
                continue
            }
            // Keep at most one exposure/positive-rep signal per session-day ("annotated" subsumes "seen"/"reviewed")
            // without discarding "deferred" lapses that rely on a prior exposure in the same session.
            val annotated = group.filter { it.action == "annotated" }.maxByOrNull { it.createdAt }
            val passive = if (annotated == null) {
                group.filter { it.action == "seen" || it.action == "reviewed" }.maxByOrNull { it.createdAt }
            } else {
                null
            }
            annotated?.let(result::add)
            passive?.let(result::add)

            val byOtherAction = LinkedHashMap<String, ReviewEventEntity>()
            for (ev in group) {
                if (ev.action == "annotated" || ev.action == "seen" || ev.action == "reviewed") continue
                val existing = byOtherAction[ev.action]
                if (existing == null || ev.createdAt > existing.createdAt) {
                    byOtherAction[ev.action] = ev
                }
            }
            result.addAll(byOtherAction.values)
        }
        return result
    }

    private fun parseInstant(raw: String?): Instant? =
        raw?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private fun deterministicJitter(seedString: String, noteId: String): Long {
        var hash = -3750763034362895579L
        val prime = 1099511628211L
        val input = "$seedString|$noteId"
        for (i in input.indices) {
            val code = input[i].code
            hash = (hash xor (code and 0xff).toLong()) * prime
            hash = (hash xor ((code ushr 8) and 0xff).toLong()) * prime
        }
        hash = hash xor (hash ushr 33)
        hash *= -49064778989728563L
        hash = hash xor (hash ushr 33)
        return hash ushr 1
    }
}
