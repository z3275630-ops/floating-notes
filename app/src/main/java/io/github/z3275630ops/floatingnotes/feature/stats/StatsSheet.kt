package io.github.z3275630ops.floatingnotes.feature.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.z3275630ops.floatingnotes.data.local.NoteEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteTagEntity
import io.github.z3275630ops.floatingnotes.data.local.TagEntity
import io.github.z3275630ops.floatingnotes.data.stats.HeatmapDayCell
import io.github.z3275630ops.floatingnotes.data.stats.NoteOriginCategory
import io.github.z3275630ops.floatingnotes.data.stats.StatsEngine
import io.github.z3275630ops.floatingnotes.data.stats.StatsOriginScope
import io.github.z3275630ops.floatingnotes.data.stats.StatsPeriodMode
import io.github.z3275630ops.floatingnotes.ui.designsystem.GlassBackdrop
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintBottomSheet
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintChip
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintChipStyle
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGroupedDivider
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGroupedRow
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGroupedSection
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintIcons
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintSegmentedBar
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintCardSurface
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintHeatmapPalette
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintLinkAccent
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintSecondaryFill
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintTertiaryTextColor
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintTodoAccent
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StatsSheet(
    allNotes: List<NoteEntity>,
    tagRefs: List<NoteTagEntity>,
    tags: List<TagEntity>,
    dark: Boolean,
    backdrop: GlassBackdrop,
    onDismiss: () -> Unit,
    onOpenNote: (String) -> Unit,
    onSelectTag: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val systemZone = remember { ZoneId.systemDefault() }
    var useUtcZone by remember { mutableStateOf(false) }
    val activeZone = if (useUtcZone) ZoneId.of("UTC") else systemZone
    var originScope by remember { mutableStateOf(StatsOriginScope.Manual) }
    var periodMode by remember { mutableStateOf(StatsPeriodMode.HeatmapWeeks) }
    var focusedMonth by remember(activeZone) {
        mutableStateOf(YearMonth.from(Instant.now().atZone(activeZone)))
    }
    var selectedDate by remember(activeZone, originScope) {
        mutableStateOf<LocalDate?>(Instant.now().atZone(activeZone).toLocalDate())
    }

    val tagsById = remember(tags) { tags.associateBy { it.id } }
    val snapshot = remember(allNotes, tagRefs, tagsById, originScope, periodMode, focusedMonth, activeZone) {
        StatsEngine.computeSnapshot(
            allNotes = allNotes,
            tagRefs = tagRefs,
            tagsById = tagsById,
            originScope = originScope,
            periodMode = periodMode,
            focusedMonth = focusedMonth,
            zoneId = activeZone,
        )
    }

    val effectiveSelectedDate = selectedDate ?: snapshot.today
    val dayItems = snapshot.notesForDate(effectiveSelectedDate)

    MintBottomSheet(
        onDismissRequest = onDismiss,
        dark = dark,
        backdrop = backdrop,
        modifier = Modifier.testTag("stats-sheet"),
        title = "统计与热力图",
        subtitle = null,
        leadingLabel = "关闭",
        onLeadingClick = onDismiss,
        maxHeightFraction = 0.86f,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 580.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 1. Source composition & scope filter (Q039)
            MintGroupedSection(dark = dark, containerColor = mintCardSurface(dark)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "来源口径 · 共 ${snapshot.totalActiveNotes} 篇",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = scheme.onSurface,
                        )
                        MintChip(
                            label = if (useUtcZone) "时区: UTC" else "时区: ${activeZone.id.substringAfterLast('/')}",
                            selected = useUtcZone,
                            dark = dark,
                            style = if (useUtcZone) MintChipStyle.Selected else MintChipStyle.Standard,
                            modifier = Modifier.testTag("stats-timezone-toggle"),
                            onClick = { useUtcZone = !useUtcZone },
                        )
                    }
                    Text(
                        text = "自己写与随记 ${snapshot.manualCount} · 外部导入 ${snapshot.importedCount} · Agent/本机生成 ${snapshot.aiGeneratedCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatsOriginScope.entries.forEach { scope ->
                            MintChip(
                                label = scope.label,
                                selected = originScope == scope,
                                dark = dark,
                                style = if (originScope == scope) MintChipStyle.Selected else MintChipStyle.Standard,
                                modifier = Modifier.testTag("stats-origin-${scope.name}"),
                                onClick = { originScope = scope },
                            )
                        }
                    }
                }
            }

            // 2. Summary metrics without zero-base bogus percentages or streak guilt (Q040)
            MintGroupedSection(dark = dark, containerColor = mintCardSurface(dark)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        StatMetricBox(
                            title = "总记录",
                            value = "${snapshot.scopedTotalCount}",
                            accent = scheme.primary,
                            dark = dark,
                            modifier = Modifier.weight(1f),
                        )
                        StatMetricBox(
                            title = "${focusedMonth.monthValue} 月新增",
                            value = "${snapshot.currentMonthComparison.currentCount}",
                            accent = mintLinkAccent(dark).fg,
                            dark = dark,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        StatMetricBox(
                            title = "连续天数",
                            value = "${snapshot.consecutiveActiveDays}",
                            accent = mintTodoAccent(dark).fg,
                            dark = dark,
                            modifier = Modifier.weight(1f),
                        )
                        StatMetricBox(
                            title = "活跃天数",
                            value = "${snapshot.activeDaysInScope}",
                            accent = scheme.onSurface,
                            dark = dark,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    val cmp = snapshot.currentMonthComparison
                    val summaryLabel = if (cmp.previousCount == 0) {
                        "${focusedMonth.year} 年 ${focusedMonth.monthValue} 月共 ${cmp.currentCount} 条"
                    } else {
                        cmp.formatSummary("${focusedMonth.year}年${focusedMonth.monthValue}月")
                    }
                    Text(
                        text = summaryLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = mintTertiaryTextColor(dark),
                        modifier = Modifier.testTag("stats-comparison-summary"),
                    )
                }
            }

            // 3. Period mode switch & interactive heatmap / calendar (Q038)
            MintSegmentedBar(
                options = StatsPeriodMode.entries.map { it.label },
                selectedIndex = StatsPeriodMode.entries.indexOf(periodMode),
                onSelect = { idx -> periodMode = StatsPeriodMode.entries[idx] },
                dark = dark,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("stats-mode-bar"),
            )

            MintGroupedSection(dark = dark, containerColor = mintCardSurface(dark)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (periodMode == StatsPeriodMode.MonthCalendar) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "‹ 上个月",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                                color = scheme.primary,
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 40.dp)
                                    .testTag("stats-month-prev")
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        role = Role.Button,
                                    ) { focusedMonth = focusedMonth.minusMonths(1) }
                                    .padding(horizontal = 6.dp, vertical = 8.dp),
                            )
                            Text(
                                text = "${focusedMonth.year} 年 ${focusedMonth.monthValue} 月",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = scheme.onSurface,
                            )
                            Text(
                                text = "下个月 ›",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                                color = scheme.primary,
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 40.dp)
                                    .testTag("stats-month-next")
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        role = Role.Button,
                                    ) { focusedMonth = focusedMonth.plusMonths(1) }
                                    .padding(horizontal = 6.dp, vertical = 8.dp),
                            )
                        }
                        MonthCalendarGrid(
                            cells = snapshot.heatmapCells,
                            selectedDate = effectiveSelectedDate,
                            maxDayCount = snapshot.maxDayCount,
                            dark = dark,
                            onSelectDate = { selectedDate = it },
                        )
                    } else {
                        Text(
                            text = "近 16 周记录节奏（点击任意方块查看当天笔记）",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = scheme.onSurfaceVariant,
                        )
                        WeeksHeatmapGrid(
                            cells = snapshot.heatmapCells,
                            selectedDate = effectiveSelectedDate,
                            maxDayCount = snapshot.maxDayCount,
                            dark = dark,
                            onSelectDate = { selectedDate = it },
                        )
                    }

                    // Legend
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("少 ", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                        listOf(0, 1, 2, 3, 4).forEach { level ->
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 2.dp)
                                    .size(12.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(heatmapLevelColor(level, dark)),
                            )
                        }
                        Text(" 多", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    }
                }
            }

            // 4. Clicked day drill-down notes (Q038)
            val dateText = effectiveSelectedDate.format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))
            MintGroupedSection(
                dark = dark,
                containerColor = mintCardSurface(dark),
                modifier = Modifier.testTag("stats-day-notes-list"),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "$dateText 当日记录（${dayItems.size} 篇）",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = scheme.onSurface,
                        )
                        Text(
                            text = originScope.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.primary,
                        )
                    }
                    if (dayItems.isEmpty()) {
                        MintGroupedDivider(dark = dark)
                        Text(
                            text = "这一天在当前筛选口径下没有记笔记，点上方任意深色格子可查看当日列表。",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(14.dp),
                        )
                    } else {
                        dayItems.forEach { item ->
                            MintGroupedDivider(dark = dark)
                            val badge = when (item.category) {
                                NoteOriginCategory.Manual -> "手记"
                                NoteOriginCategory.Imported -> "导入"
                                NoteOriginCategory.AiGenerated -> "本机分析"
                            }
                            MintGroupedRow(
                                label = item.title,
                                subtitle = "${item.timeLabel} · $badge" +
                                    if (item.excerpt.isNotBlank()) " · ${item.excerpt}" else "",
                                leadingIcon = MintIcons.Records,
                                showChevron = true,
                                dark = dark,
                                onClick = {
                                    onDismiss()
                                    onOpenNote(item.note.id)
                                },
                            )
                        }
                    }
                }
            }

            // 5. Time-of-day distribution & Top tags
            if (snapshot.scopedTotalCount > 0) {
                MintGroupedSection(dark = dark, containerColor = mintCardSurface(dark)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "时段偏好",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = scheme.onSurface,
                        )
                        val maxBucket = snapshot.timeBuckets.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
                        snapshot.timeBuckets.forEach { bucket ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(bucket.label, style = MaterialTheme.typography.bodySmall, color = scheme.onSurface)
                                    Text("${bucket.count} 条", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(mintSecondaryFill(dark)),
                                ) {
                                    val fraction = (bucket.count.toFloat() / maxBucket.toFloat()).coerceIn(0f, 1f)
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(if (bucket.count == 0) 0f else fraction.coerceAtLeast(0.04f))
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(scheme.primary),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (snapshot.topTags.isNotEmpty()) {
                MintGroupedSection(dark = dark, containerColor = mintCardSurface(dark)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "高频标签（点击进入筛选）",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = scheme.onSurface,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            snapshot.topTags.forEach { tag ->
                                MintChip(
                                    label = "#${tag.name} (${tag.count})",
                                    selected = false,
                                    dark = dark,
                                    style = MintChipStyle.Standard,
                                    onClick = {
                                        onDismiss()
                                        onSelectTag(tag.tagId)
                                    },
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

@Composable
private fun StatMetricBox(
    title: String,
    value: String,
    accent: Color,
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(mintSecondaryFill(dark))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.displayLarge.copy(
                fontSize = 32.sp,
                lineHeight = 36.sp,
                fontWeight = FontWeight.Bold,
                fontFeatureSettings = "tnum",
            ),
            color = accent,
        )
    }
}

@Composable
private fun WeeksHeatmapGrid(
    cells: List<HeatmapDayCell>,
    selectedDate: LocalDate,
    maxDayCount: Int,
    dark: Boolean,
    onSelectDate: (LocalDate) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val weekColumns = remember(cells) { cells.chunked(7) }
    val scrollState = rememberScrollState(initial = Int.MAX_VALUE)
    val weekdayLabels = listOf("一", "二", "三", "四", "五", "六", "日")
    val labelStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 14.sp)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(
            modifier = Modifier.padding(top = 21.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            weekdayLabels.forEach { label ->
                Box(modifier = Modifier.size(width = 18.dp, height = 14.dp), contentAlignment = Alignment.Center) {
                    Text(label, style = labelStyle, color = mintTertiaryTextColor(dark))
                }
            }
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(scrollState)
                .padding(end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            var lastLabeledMonth = -1
            weekColumns.forEach { week ->
                val firstDay = week.firstOrNull()?.date
                val monthLabel = if (firstDay != null && firstDay.monthValue != lastLabeledMonth) {
                    lastLabeledMonth = firstDay.monthValue
                    "${firstDay.monthValue}月"
                } else ""
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(modifier = Modifier.height(18.dp), contentAlignment = Alignment.CenterStart) {
                        if (monthLabel.isNotEmpty()) {
                            Text(
                                text = monthLabel,
                                style = labelStyle,
                                color = mintTertiaryTextColor(dark),
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                    week.forEach { cell ->
                        val level = heatmapLevel(cell.count, maxDayCount)
                        val selected = cell.date == selectedDate
                        val bg = if (cell.isFuture) Color.Transparent else heatmapLevelColor(level, dark)
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(RoundedCornerShape(3.5.dp))
                                .background(bg)
                                .then(
                                    if (selected) {
                                        Modifier.border(1.5.dp, scheme.primary, RoundedCornerShape(3.5.dp))
                                    } else {
                                        Modifier
                                    },
                                )
                                .testTag("stats-day-cell-${cell.date}")
                                .clickable(
                                    enabled = !cell.isFuture,
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    role = Role.Button,
                                ) { onSelectDate(cell.date) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthCalendarGrid(
    cells: List<HeatmapDayCell>,
    selectedDate: LocalDate,
    maxDayCount: Int,
    dark: Boolean,
    onSelectDate: (LocalDate) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val headers = listOf("一", "二", "三", "四", "五", "六", "日")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            headers.forEach { h ->
                Text(
                    text = h,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        cells.chunked(7).forEach { week ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                week.forEach { cell ->
                    val selected = cell.date == selectedDate && cell.inFocusedPeriod
                    val level = heatmapLevel(cell.count, maxDayCount)
                    val bg = if (!cell.inFocusedPeriod) Color.Transparent else heatmapLevelColor(level, dark)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(bg)
                            .then(
                                if (selected) {
                                    Modifier.border(1.8.dp, scheme.primary, RoundedCornerShape(10.dp))
                                } else {
                                    Modifier
                                },
                            )
                            .testTag("stats-day-cell-${cell.date}")
                            .clickable(
                                enabled = cell.inFocusedPeriod,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Button,
                            ) { onSelectDate(cell.date) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (cell.inFocusedPeriod) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "${cell.date.dayOfMonth}",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = if (selected || cell.count > 0) FontWeight.Bold else FontWeight.Normal,
                                    ),
                                    color = if (level >= 3 && !dark) Color.White else scheme.onSurface,
                                )
                                if (cell.count > 0) {
                                    Text(
                                        text = "${cell.count}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (level >= 3 && !dark) Color.White.copy(alpha = 0.9f) else scheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun heatmapLevel(count: Int, maxCount: Int): Int {
    if (count <= 0) return 0
    if (maxCount <= 1) return 3
    val ratio = count.toFloat() / maxCount.toFloat()
    return when {
        ratio <= 0.25f -> 1
        ratio <= 0.55f -> 2
        ratio <= 0.8f -> 3
        else -> 4
    }
}

private fun heatmapLevelColor(level: Int, dark: Boolean): Color {
    val palette = mintHeatmapPalette(dark)
    return palette[level.coerceIn(0, palette.lastIndex)]
}
