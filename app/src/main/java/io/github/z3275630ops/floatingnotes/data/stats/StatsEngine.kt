package io.github.z3275630ops.floatingnotes.data.stats

import io.github.z3275630ops.floatingnotes.data.discovery.LocalRetrievalEngine
import io.github.z3275630ops.floatingnotes.data.local.NoteEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteTagEntity
import io.github.z3275630ops.floatingnotes.data.local.TagEntity
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.roundToInt

/** Source origin filter so personal writing cadence stays separated from imported or AI-generated notes (Q039). */
internal enum class StatsOriginScope(val label: String) {
    Manual("仅手写与随记"),
    All("全部来源"),
    Imported("外部导入"),
    AiGenerated("Agent / 分析"),
}

internal enum class StatsPeriodMode(val label: String) {
    HeatmapWeeks("热力节奏"),
    MonthCalendar("月度日历"),
}

internal enum class NoteOriginCategory {
    Manual,
    Imported,
    AiGenerated,
}

internal data class HeatmapDayCell(
    val date: LocalDate,
    val count: Int,
    val inFocusedPeriod: Boolean,
    val isFuture: Boolean,
)

internal data class StatsPeriodComparison(
    val currentCount: Int,
    val previousCount: Int,
    val absoluteDelta: Int,
    /** Null whenever previousCount == 0 so zero-baseline never fabricates an artificial percentage (Q040). */
    val deltaPercent: Int?,
) {
    fun formatSummary(periodNoun: String): String = when {
        currentCount == 0 && previousCount == 0 -> "${periodNoun}暂无新增记录"
        previousCount == 0 -> "${periodNoun}共 $currentCount 条（上期基数为 0，仅展示绝对数量）"
        deltaPercent != null -> {
            val sign = if (absoluteDelta >= 0) "+" else ""
            "${periodNoun} $currentCount 条（较上期 ${sign}$absoluteDelta，${sign}$deltaPercent%）"
        }
        else -> "${periodNoun}共 $currentCount 条"
    }
}

internal data class StatsTagSummary(
    val tagId: String,
    val name: String,
    val count: Int,
)

internal data class StatsTimeBucket(
    val label: String,
    val count: Int,
)

internal data class StatsDayNoteItem(
    val note: NoteEntity,
    val title: String,
    val excerpt: String,
    val category: NoteOriginCategory,
    val timeLabel: String,
)

internal data class StatsSnapshot(
    val zoneId: ZoneId,
    val today: LocalDate,
    val focusedMonth: YearMonth,
    val originScope: StatsOriginScope,
    val periodMode: StatsPeriodMode,
    val totalActiveNotes: Int,
    val manualCount: Int,
    val importedCount: Int,
    val aiGeneratedCount: Int,
    val scopedTotalCount: Int,
    val activeDaysInScope: Int,
    val consecutiveActiveDays: Int,
    val currentMonthComparison: StatsPeriodComparison,
    val heatmapCells: List<HeatmapDayCell>,
    val maxDayCount: Int,
    val timeBuckets: List<StatsTimeBucket>,
    val topTags: List<StatsTagSummary>,
    val dayNotesByDate: Map<LocalDate, List<StatsDayNoteItem>>,
) {
    fun notesForDate(date: LocalDate): List<StatsDayNoteItem> = dayNotesByDate[date].orEmpty()
}

internal object StatsEngine {

    fun classifyOrigin(note: NoteEntity): NoteOriginCategory {
        val author = note.authorType.lowercase()
        val source = note.sourceType.lowercase()
        if (
            author != "user" ||
            source == "agent_local" ||
            source == "walk_collision_ai" ||
            source == "ai" ||
            source.startsWith("ai_")
        ) {
            return NoteOriginCategory.AiGenerated
        }
        if (
            source.startsWith("import") ||
            source == "backup" ||
            source == "flomo" ||
            source == "markdown"
        ) {
            return NoteOriginCategory.Imported
        }
        return NoteOriginCategory.Manual
    }

    fun matchesScope(note: NoteEntity, scope: StatsOriginScope): Boolean = when (scope) {
        StatsOriginScope.All -> true
        StatsOriginScope.Manual -> classifyOrigin(note) == NoteOriginCategory.Manual
        StatsOriginScope.Imported -> classifyOrigin(note) == NoteOriginCategory.Imported
        StatsOriginScope.AiGenerated -> classifyOrigin(note) == NoteOriginCategory.AiGenerated
    }

    fun computeSnapshot(
        allNotes: List<NoteEntity>,
        tagRefs: List<NoteTagEntity>,
        tagsById: Map<String, TagEntity>,
        originScope: StatsOriginScope,
        periodMode: StatsPeriodMode,
        focusedMonth: YearMonth,
        zoneId: ZoneId = ZoneId.systemDefault(),
        now: Instant = Instant.now(),
    ): StatsSnapshot {
        val activeNotes = allNotes.filter { it.deletedAt == null }
        val today = now.atZone(zoneId).toLocalDate()

        var manualCount = 0
        var importedCount = 0
        var aiGeneratedCount = 0
        activeNotes.forEach { note ->
            when (classifyOrigin(note)) {
                NoteOriginCategory.Manual -> manualCount++
                NoteOriginCategory.Imported -> importedCount++
                NoteOriginCategory.AiGenerated -> aiGeneratedCount++
            }
        }

        val scopedNotes = activeNotes.filter { matchesScope(it, originScope) }
        val groupedByDate = linkedMapOf<LocalDate, MutableList<Pair<Instant, NoteEntity>>>()
        val bucketCounts = IntArray(4)

        scopedNotes.forEach { note ->
            val createdInstant = parseInstant(note.createdAt) ?: parseInstant(note.updatedAt) ?: return@forEach
            val zoned = createdInstant.atZone(zoneId)
            val date = zoned.toLocalDate()
            groupedByDate.getOrPut(date) { ArrayList() }.add(createdInstant to note)
            val bucketIdx = when (zoned.hour) {
                in 5..11 -> 0 // 清晨/上午
                in 12..17 -> 1 // 下午
                in 18..22 -> 2 // 晚间
                else -> 3 // 深夜
            }
            bucketCounts[bucketIdx]++
        }

        val dayNotesByDate = groupedByDate.mapValues { (_, entries) ->
            entries.sortedByDescending { it.first }.map { (instant, note) ->
                val title = LocalRetrievalEngine.displayNoteTitle(note)
                val body = LocalRetrievalEngine.bodyExcludingTitle(note, title = title)
                val localTime = instant.atZone(zoneId).toLocalTime()
                StatsDayNoteItem(
                    note = note,
                    title = title,
                    excerpt = body.ifBlank { "" },
                    category = classifyOrigin(note),
                    timeLabel = "%02d:%02d".format(localTime.hour, localTime.minute),
                )
            }
        }

        val currentMonthCount = countNotesInMonth(groupedByDate, focusedMonth)
        val prevMonthCount = countNotesInMonth(groupedByDate, focusedMonth.minusMonths(1))
        val delta = currentMonthCount - prevMonthCount
        val pct = if (prevMonthCount > 0) ((delta.toDouble() / prevMonthCount) * 100.0).roundToInt() else null
        val comparison = StatsPeriodComparison(
            currentCount = currentMonthCount,
            previousCount = prevMonthCount,
            absoluteDelta = delta,
            deltaPercent = pct,
        )

        val heatmapCells = when (periodMode) {
            StatsPeriodMode.HeatmapWeeks -> buildWeeksHeatmap(groupedByDate, today, weeks = 16)
            StatsPeriodMode.MonthCalendar -> buildMonthCalendarCells(groupedByDate, focusedMonth, today)
        }
        val maxDayCount = heatmapCells.maxOfOrNull { it.count } ?: 0

        // Gentle streak count from today (or yesterday if nothing recorded yet today) without pressure
        var consecutive = 0
        var cursor = if (groupedByDate.containsKey(today)) today else today.minusDays(1)
        while (groupedByDate.containsKey(cursor)) {
            consecutive++
            cursor = cursor.minusDays(1)
        }

        val scopedIds = scopedNotes.mapTo(HashSet()) { it.id }
        val tagCounts = LinkedHashMap<String, Int>()
        tagRefs.forEach { ref ->
            if (ref.noteId in scopedIds) {
                tagCounts[ref.tagId] = (tagCounts[ref.tagId] ?: 0) + 1
            }
        }
        val topTags = tagCounts.entries
            .mapNotNull { (tagId, count) ->
                val name = tagsById[tagId]?.name ?: return@mapNotNull null
                StatsTagSummary(tagId = tagId, name = name, count = count)
            }
            .sortedWith(compareByDescending<StatsTagSummary> { it.count }.thenBy { it.name })
            .take(8)

        return StatsSnapshot(
            zoneId = zoneId,
            today = today,
            focusedMonth = focusedMonth,
            originScope = originScope,
            periodMode = periodMode,
            totalActiveNotes = activeNotes.size,
            manualCount = manualCount,
            importedCount = importedCount,
            aiGeneratedCount = aiGeneratedCount,
            scopedTotalCount = scopedNotes.size,
            activeDaysInScope = groupedByDate.size,
            consecutiveActiveDays = consecutive,
            currentMonthComparison = comparison,
            heatmapCells = heatmapCells,
            maxDayCount = maxDayCount,
            timeBuckets = listOf(
                StatsTimeBucket("清晨上午 (05–11)", bucketCounts[0]),
                StatsTimeBucket("午后 (12–17)", bucketCounts[1]),
                StatsTimeBucket("晚间 (18–22)", bucketCounts[2]),
                StatsTimeBucket("深夜 (23–04)", bucketCounts[3]),
            ),
            topTags = topTags,
            dayNotesByDate = dayNotesByDate,
        )
    }

    private fun countNotesInMonth(
        groupedByDate: Map<LocalDate, List<Pair<Instant, NoteEntity>>>,
        month: YearMonth,
    ): Int = groupedByDate.entries.sumOf { (date, list) ->
        if (YearMonth.from(date) == month) list.size else 0
    }

    private fun buildWeeksHeatmap(
        groupedByDate: Map<LocalDate, List<Pair<Instant, NoteEntity>>>,
        today: LocalDate,
        weeks: Int,
    ): List<HeatmapDayCell> {
        val currentWeekMonday = today.minusDays(((today.dayOfWeek.value - 1).toLong()))
        val startMonday = currentWeekMonday.minusWeeks((weeks - 1).toLong())
        val totalDays = weeks * 7
        return List(totalDays) { offset ->
            val date = startMonday.plusDays(offset.toLong())
            val count = groupedByDate[date]?.size ?: 0
            HeatmapDayCell(
                date = date,
                count = count,
                inFocusedPeriod = !date.isAfter(today),
                isFuture = date.isAfter(today),
            )
        }
    }

    private fun buildMonthCalendarCells(
        groupedByDate: Map<LocalDate, List<Pair<Instant, NoteEntity>>>,
        month: YearMonth,
        today: LocalDate,
    ): List<HeatmapDayCell> {
        val firstDay = month.atDay(1)
        val leadingEmpty = firstDay.dayOfWeek.value - 1
        val gridStart = firstDay.minusDays(leadingEmpty.toLong())
        val daysInMonth = month.lengthOfMonth()
        val totalCells = ((leadingEmpty + daysInMonth + 6) / 7) * 7
        return List(totalCells) { offset ->
            val date = gridStart.plusDays(offset.toLong())
            val inMonth = YearMonth.from(date) == month
            HeatmapDayCell(
                date = date,
                count = if (inMonth) (groupedByDate[date]?.size ?: 0) else 0,
                inFocusedPeriod = inMonth,
                isFuture = date.isAfter(today),
            )
        }
    }

    private fun parseInstant(iso: String?): Instant? = iso?.let {
        runCatching { Instant.parse(it) }.getOrNull()
    }
}
