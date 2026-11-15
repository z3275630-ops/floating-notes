package io.github.z3275630ops.floatingnotes.feature.explore

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.z3275630ops.floatingnotes.data.discovery.DualCollisionBridge
import io.github.z3275630ops.floatingnotes.data.discovery.DualCollisionPair
import io.github.z3275630ops.floatingnotes.data.discovery.EvidenceHit
import io.github.z3275630ops.floatingnotes.data.discovery.LocalRetrievalEngine
import io.github.z3275630ops.floatingnotes.data.discovery.WalkPostcardSummary
import io.github.z3275630ops.floatingnotes.data.local.NoteScope
import io.github.z3275630ops.floatingnotes.data.local.TagEntity
import io.github.z3275630ops.floatingnotes.ui.designsystem.ComposablesSearchField
import io.github.z3275630ops.floatingnotes.ui.designsystem.GlassBackdrop
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintActivityIndicator
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintBottomSheet
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintChip
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintChipStyle
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGroupedDivider
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGroupedRow
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGroupedSection
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintIcons
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintPillButton
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintSegmentedBar
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintSwitch
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintTextField
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintAnimatorsEnabled
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintCardSurface
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintDividerColor
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintLinkAccent
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintSecondaryFill
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintTertiaryTextColor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FindTabContent(
    state: ExploreUiState,
    tags: List<TagEntity>,
    dark: Boolean,
    onQueryChange: (String) -> Unit,
    onScopeChange: (NoteScope) -> Unit,
    onSearch: (String, NoteScope) -> Unit,
    onRefreshIndex: () -> Unit,
    onToggleBasket: (EvidenceHit) -> Unit,
    onClearBasket: () -> Unit,
    onSaveBasketAsNote: () -> Unit,
    onOpenNote: (String, String?) -> Unit,
    onExcludeNote: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val fieldState = rememberTextFieldState(state.findQuery)
    LaunchedEffect(fieldState.text.toString()) {
        val current = fieldState.text.toString()
        if (current != state.findQuery) {
            onQueryChange(current)
        }
    }
    var last30DaysOnly by rememberSaveable { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MintGroupedSection(dark = dark, containerColor = mintCardSurface(dark)) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        ComposablesSearchField(
                            state = fieldState,
                            label = "例如：项目风险、以前关于旅行的灵感…",
                            tag = "find-query-input",
                            containerColor = mintSecondaryFill(dark),
                        )
                    }
                    MintPillButton(
                        label = "找一找",
                        primary = true,
                        dark = dark,
                        enabled = fieldState.text.isNotBlank() && !state.indexing,
                        modifier = Modifier.testTag("find-submit-button"),
                        onClick = { onSearch(fieldState.text.toString(), state.findScope) },
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MintChip(
                        label = if (state.findScope.archived) "含已归档" else "仅活跃笔记",
                        dark = dark,
                        selected = state.findScope.archived,
                        style = if (state.findScope.archived) MintChipStyle.Selected else MintChipStyle.Standard,
                        onClick = {
                            onScopeChange(state.findScope.copy(archived = !state.findScope.archived))
                        },
                    )
                    MintChip(
                        label = if (last30DaysOnly) "近 30 天" else "全部时间",
                        dark = dark,
                        selected = last30DaysOnly,
                        style = if (last30DaysOnly) MintChipStyle.Selected else MintChipStyle.Standard,
                        onClick = {
                            val next = !last30DaysOnly
                            last30DaysOnly = next
                            val from = if (next) Instant.now().minus(30, ChronoUnit.DAYS) else null
                            onScopeChange(state.findScope.copy(from = from, until = null))
                        },
                    )
                    tags.take(6).forEach { tag ->
                        val active = tag.id in state.findScope.includeTags
                        MintChip(
                            label = "#${tag.name}",
                            dark = dark,
                            selected = active,
                            style = if (active) MintChipStyle.Selected else MintChipStyle.Standard,
                            onClick = {
                                val nextTags = if (active) state.findScope.includeTags - tag.id else state.findScope.includeTags + tag.id
                                onScopeChange(state.findScope.copy(includeTags = nextTags, untagged = false))
                            },
                        )
                    }
                }

                state.coverage?.let { cov ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = if (cov.isComplete) {
                                "已就绪 · ${cov.indexedCount}/${cov.eligibleCount} 篇可秒搜"
                            } else {
                                "同步中 · ${cov.indexedCount}/${cov.eligibleCount} 篇"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = mintTertiaryTextColor(dark),
                            modifier = Modifier.testTag("find-coverage-label"),
                        )
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(
                                    enabled = !state.indexing,
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    role = Role.Button,
                                    onClick = onRefreshIndex,
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (state.indexing) {
                                MintActivityIndicator(sizeDp = 14.dp, color = scheme.primary)
                            } else {
                                Icon(
                                    imageVector = MintIcons.Refresh,
                                    contentDescription = "刷新索引",
                                    tint = scheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                            Text(
                                text = "刷新",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (state.evidenceBasket.isNotEmpty()) {
            val basketAccent = mintLinkAccent(dark)
            MintGroupedSection(
                dark = dark,
                containerColor = basketAccent.bg,
                modifier = Modifier.testTag("find-evidence-basket-bar"),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "已收集 ${state.evidenceBasket.size} 条线索",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = basketAccent.fg,
                        )
                        Text(
                            text = "可一键汇成带引用的整理笔记",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    MintPillButton(
                        label = "清空",
                        primary = false,
                        dark = dark,
                        onClick = onClearBasket,
                    )
                    MintPillButton(
                        label = "整理成笔记",
                        primary = true,
                        dark = dark,
                        modifier = Modifier.testTag("find-basket-save-note"),
                        onClick = onSaveBasketAsNote,
                    )
                }
            }
        }

        val result = state.findResult
        if (result == null) {
            MintGroupedSection(dark = dark, containerColor = mintCardSurface(dark)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "试试这样提问",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = scheme.onSurface,
                    )
                    Text(
                        text = "只在你勾选的范围内检索，绝不编造不存在的内容。",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf("待办和下一步计划", "灵感或读书摘录", "需要注意的问题").forEach { prompt ->
                            MintChip(
                                label = prompt,
                                dark = dark,
                                onClick = {
                                    fieldState.edit { replace(0, length, prompt) }
                                    onSearch(prompt, state.findScope)
                                },
                            )
                        }
                    }
                }
            }
        } else if (result.hits.isEmpty()) {
            MintGroupedSection(
                dark = dark,
                containerColor = mintCardSurface(dark),
                modifier = Modifier.testTag("find-empty-state"),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "在当前范围内没有找到相关笔记",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = scheme.onSurface,
                    )
                    Text(
                        text = "提取关键词：${result.extractedKeywords.ifEmpty { listOf(result.query) }.joinToString("、")}。可以试试更换关键词，或开启「含已归档」、放宽时间范围。",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            Text(
                text = "找到 ${result.hits.size} 条依据（按关联度与相邻段落合并）",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            result.hits.forEach { hit ->
                val inBasket = state.evidenceBasket.any { it.noteId == hit.note.id }
                MintGroupedSection(
                    dark = dark,
                    containerColor = mintCardSurface(dark),
                    modifier = Modifier.testTag("find-hit-card-${hit.note.id}"),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = hit.noteTitle,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = scheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = formatShortDate(hit.note.updatedAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        if (hit.reasons.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                hit.reasons.forEach { reason ->
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(mintSecondaryFill(dark))
                                            .padding(horizontal = 8.dp, vertical = 3.dp),
                                    ) {
                                        Text(
                                            text = reason,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = scheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                        Text(
                            text = hit.mergedExcerpt,
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurface,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MintPillButton(
                                label = if (inBasket) "✓ 已选入" else "+ 选入证据篮",
                                primary = inBasket,
                                dark = dark,
                                modifier = Modifier.testTag("find-basket-toggle-${hit.note.id}"),
                                onClick = { onToggleBasket(hit) },
                            )
                            MintPillButton(
                                label = "打开原笔记",
                                primary = false,
                                dark = dark,
                                onClick = { onOpenNote(hit.note.id, hit.matchedBlockId) },
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = "排除",
                                style = MaterialTheme.typography.labelMedium,
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        role = Role.Button,
                                        onClick = { onExcludeNote(hit.note.id) },
                                    )
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WalkTabContent(
    state: ExploreUiState,
    tags: List<TagEntity>,
    dark: Boolean,
    backdrop: GlassBackdrop,
    onStartWalk: (String?) -> Unit,
    onStepForward: (Boolean) -> Unit,
    onStepBack: () -> Unit,
    onSaveMemo: (String) -> Unit,
    onFinishPostcard: () -> Unit,
    onTogglePrivacy: (Boolean) -> Unit,
    onSavePostcardAsNote: () -> Unit,
    onDismissPostcard: () -> Unit,
    onOpenNote: (String) -> Unit,
    onSelectWalkMode: (WalkMode) -> Unit = {},
    onDrawNextDualPair: () -> Unit = {},
    onStepDualBack: () -> Unit = {},
    onAnalyzeDualPair: () -> Unit = {},
    onMarkDualFarFetched: () -> Unit = {},
    onSaveDualAsNote: (String) -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val walk = state.walk
    var showMemoSheet by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WalkMode.entries.forEach { mode ->
                val selected = state.walkMode == mode
                MintChip(
                    label = mode.label,
                    selected = selected,
                    style = if (selected) MintChipStyle.Selected else MintChipStyle.Standard,
                    dark = dark,
                    role = Role.Tab,
                    modifier = Modifier.testTag(if (mode == WalkMode.Single) "walk-mode-single" else "walk-mode-dual"),
                    onClick = { onSelectWalkMode(mode) },
                )
            }
        }

        if (state.walkMode == WalkMode.Dual) {
            DualWalkContent(
                state = state,
                dark = dark,
                onDrawNext = onDrawNextDualPair,
                onStepBack = onStepDualBack,
                onAnalyze = onAnalyzeDualPair,
                onMarkFarFetched = onMarkDualFarFetched,
                onSaveAsNote = onSaveDualAsNote,
                onOpenNote = onOpenNote,
            )
            return@Column
        }

        if (walk == null || walk.currentStep == null || walk.currentNote == null) {
            MintGroupedSection(
                dark = dark,
                containerColor = mintCardSurface(dark),
                modifier = Modifier.testTag("walk-start-hero"),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(mintSecondaryFill(dark)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = MintIcons.Explore,
                                contentDescription = null,
                                tint = scheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Column {
                            Text(
                                text = "随手漫步",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = scheme.onSurface,
                            )
                            Text(
                                text = "沿着共同标签、引用与相似主题串读旧笔记",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        text = "从一篇过往笔记出发，沿着共同主题与时间线索慢慢串联，随时可退回上一站，留住沿途冒出的灵感。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                    MintPillButton(
                        label = "开始随机漫步",
                        primary = true,
                        dark = dark,
                        icon = MintIcons.Explore,
                        modifier = Modifier.fillMaxWidth().testTag("walk-start-button"),
                        onClick = { onStartWalk(null) },
                    )
                    if (tags.isNotEmpty()) {
                        Text(
                            text = "或选一个标签主题出发：",
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            tags.take(8).forEach { tag ->
                                MintChip(
                                    label = "#${tag.name}",
                                    dark = dark,
                                    onClick = { onStartWalk(tag.id) },
                                )
                            }
                        }
                    }
                }
            }
        } else {
            val animEnabled = mintAnimatorsEnabled()

            AnimatedContent(
                targetState = walk,
                contentKey = { (it.currentStep?.id ?: "") to it.currentStep?.userMemo },
                transitionSpec = {
                    if (!animEnabled) {
                        fadeIn(snap()) togetherWith fadeOut(snap())
                    } else {
                        (slideInHorizontally(animationSpec = tween(220)) { it / 16 } + fadeIn(tween(220))) togetherWith
                            (slideOutHorizontally(animationSpec = tween(180)) { -it / 16 } + fadeOut(tween(180)))
                    }
                },
                label = "walk-step-transition",
            ) { renderedWalk ->
                val step = renderedWalk.currentStep ?: return@AnimatedContent
                val note = renderedWalk.currentNote ?: return@AnimatedContent
                MintGroupedSection(
                    dark = dark,
                    containerColor = mintCardSurface(dark),
                    modifier = Modifier.testTag("walk-active-card"),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(mintSecondaryFill(dark))
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    text = "${renderedWalk.stepPositionLabel} · ${step.reasonLabel}",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = scheme.primary,
                                    modifier = Modifier.testTag("walk-step-reason"),
                                )
                            }
                            Text(
                                text = formatShortDate(note.createdAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = LocalRetrievalEngine.displayNoteTitle(note),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = scheme.onSurface,
                        )
                        if (renderedWalk.currentNoteTags.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                renderedWalk.currentNoteTags.forEach { t ->
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(mintSecondaryFill(dark))
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                    ) {
                                        Text(
                                            text = "#$t",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = scheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                        val walkTitle = LocalRetrievalEngine.displayNoteTitle(note)
                        val body = LocalRetrievalEngine.bodyExcludingTitle(note, note.searchText, walkTitle)
                        if (body.isNotEmpty()) {
                            Text(
                                text = body,
                                style = MaterialTheme.typography.bodyLarge,
                                color = scheme.onSurface,
                                maxLines = 10,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        step.userMemo?.takeIf { it.isNotBlank() }?.let { memo ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(mintSecondaryFill(dark))
                                    .padding(10.dp),
                            ) {
                                Text(
                                    text = "途中随想：$memo",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.primary,
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            MintPillButton(
                                label = "打开原笔记",
                                primary = false,
                                dark = dark,
                                onClick = { onOpenNote(note.id) },
                            )
                            MintPillButton(
                                label = if (step.userMemo.isNullOrBlank()) "记一句想法" else "改随想",
                                primary = false,
                                dark = dark,
                                onClick = { showMemoSheet = true },
                            )
                        }
                    }
                }
            }

            MintGroupedSection(dark = dark, containerColor = mintCardSurface(dark)) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MintPillButton(
                            label = "回到上一条",
                            primary = false,
                            dark = dark,
                            enabled = walk.canStepBack,
                            modifier = Modifier.weight(1f).testTag("walk-back-button"),
                            onClick = onStepBack,
                        )
                        MintPillButton(
                            label = "继续走",
                            primary = true,
                            dark = dark,
                            modifier = Modifier.weight(1f).testTag("walk-next-button"),
                            onClick = { onStepForward(false) },
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MintPillButton(
                            label = "换个方向",
                            primary = false,
                            dark = dark,
                            modifier = Modifier.weight(1f).testTag("walk-branch-button"),
                            onClick = { onStepForward(true) },
                        )
                        MintPillButton(
                            label = "结束生成明信片",
                            primary = false,
                            dark = dark,
                            modifier = Modifier.weight(1f).testTag("walk-finish-button"),
                            onClick = onFinishPostcard,
                        )
                    }
                }
            }
        }
    }

    if (showMemoSheet) {
        WalkMemoSheet(
            initial = state.walk?.currentStep?.userMemo.orEmpty(),
            dark = dark,
            backdrop = backdrop,
            onDismiss = { showMemoSheet = false },
            onSave = {
                onSaveMemo(it)
                showMemoSheet = false
            },
        )
    }

    state.walkPostcard?.let { postcard ->
        WalkPostcardSheet(
            postcard = postcard,
            dark = dark,
            backdrop = backdrop,
            onTogglePrivacy = onTogglePrivacy,
            onSaveAsNote = onSavePostcardAsNote,
            onDismiss = onDismissPostcard,
        )
    }
}

@Composable
private fun WalkMemoSheet(
    initial: String,
    dark: Boolean,
    backdrop: GlassBackdrop,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    MintBottomSheet(
        onDismissRequest = onDismiss,
        dark = dark,
        backdrop = backdrop,
        title = "写一句此刻的想法",
        subtitle = "将随漫步卡片一起收进明信片",
        leadingLabel = "取消",
        trailingLabel = "保存",
        onTrailingClick = { onSave(text) },
    ) {
        MintTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = "比如：这个想法正好可以接上上周的笔记…",
            dark = dark,
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun WalkPostcardSheet(
    postcard: WalkPostcardSummary,
    dark: Boolean,
    backdrop: GlassBackdrop,
    onTogglePrivacy: (Boolean) -> Unit,
    onSaveAsNote: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    var copiedNotice by remember { mutableStateOf(false) }

    MintBottomSheet(
        onDismissRequest = onDismiss,
        dark = dark,
        backdrop = backdrop,
        title = postcard.title,
        subtitle = postcard.dateSpanLabel,
        leadingLabel = "关闭",
        trailingLabel = "存为笔记",
        onTrailingClick = onSaveAsNote,
        modifier = Modifier.testTag("walk-postcard-sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MintGroupedSection(dark = dark) {
                MintGroupedRow(
                    label = "隐私保护：隐藏正文摘录与标签",
                    subtitle = "导出或分享路径前隐藏具体笔记内容",
                    dark = dark,
                    trailingContent = {
                        MintSwitch(
                            checked = postcard.privacyHidden,
                            onCheckedChange = onTogglePrivacy,
                            dark = dark,
                            modifier = Modifier.testTag("walk-postcard-privacy-toggle"),
                        )
                    },
                    onClick = { onTogglePrivacy(!postcard.privacyHidden) },
                )
            }
            MintGroupedSection(dark = dark) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = postcard.markdownBody,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurface,
                        modifier = Modifier.testTag("walk-postcard-body"),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MintPillButton(
                    label = if (copiedNotice) "已复制文字" else "复制明信片内容",
                    primary = false,
                    dark = dark,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        clipboard.setText(AnnotatedString(postcard.markdownBody))
                        copiedNotice = true
                    },
                )
                MintPillButton(
                    label = "存为新笔记",
                    primary = true,
                    dark = dark,
                    modifier = Modifier.weight(1f).testTag("walk-postcard-save-note"),
                    onClick = onSaveAsNote,
                )
            }
        }
    }
}

internal fun formatShortDate(iso: String): String = runCatching {
    val zdt = Instant.parse(iso).atZone(ZoneId.systemDefault())
    DateTimeFormatter.ofPattern("yyyy/MM/dd").format(zdt)
}.getOrElse { iso.take(10) }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DualWalkContent(
    state: ExploreUiState,
    dark: Boolean,
    onDrawNext: () -> Unit,
    onStepBack: () -> Unit,
    onAnalyze: () -> Unit,
    onMarkFarFetched: () -> Unit,
    onSaveAsNote: (String) -> Unit,
    onOpenNote: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val pair = state.dualPair
    val insufficient = state.dualInsufficientCount

    if (insufficient != null || pair == null) {
        MintGroupedSection(
            dark = dark,
            containerColor = mintCardSurface(dark),
            modifier = Modifier.testTag("dual-walk-empty"),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "双卡概念对撞",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = scheme.onSurface,
                )
                Text(
                    text = "至少需要 2 条不同的非空笔记才能随机配对对撞（当前可用 ${insufficient ?: 0} 条），先去记一两条想法吧。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
                MintPillButton(
                    label = "重新检查笔记数量",
                    primary = true,
                    dark = dark,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onDrawNext,
                )
            }
        }
        return
    }

    var reflectionDraft by rememberSaveable(pair.pairId) { mutableStateOf("") }
    val cfg = LocalConfiguration.current
    val density = LocalDensity.current
    val sideBySide = cfg.screenWidthDp >= 540 && density.fontScale < 1.35f

    val linkAccent = mintLinkAccent(dark)

    Column(
        modifier = Modifier.testTag("dual-walk-workspace"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MintChip(
                label = "上一组",
                leadingIcon = MintIcons.Back,
                selected = false,
                dark = dark,
                modifier = Modifier.testTag("dual-back-button"),
                onClick = { if (state.dualHistory.isNotEmpty()) onStepBack() },
            )
            MintChip(
                label = "换一组",
                leadingIcon = MintIcons.Refresh,
                selected = true,
                dark = dark,
                modifier = Modifier.testTag("dual-next-button"),
                onClick = onDrawNext,
            )
        }

        if (sideBySide) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DualCollisionNoteCard(
                    badge = "卡片 A",
                    title = pair.leftTitle,
                    body = pair.leftBody,
                    tags = pair.leftTags,
                    dateLabel = formatShortDate(pair.leftNote.createdAt),
                    dark = dark,
                    onOpen = { onOpenNote(pair.leftNote.id) },
                    modifier = Modifier.weight(1f).testTag("dual-card-left"),
                )
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(linkAccent.bg)
                        .border(1.dp, linkAccent.fg.copy(alpha = 0.35f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = MintIcons.Link,
                        contentDescription = null,
                        tint = linkAccent.fg,
                        modifier = Modifier.size(14.dp),
                    )
                }
                DualCollisionNoteCard(
                    badge = "卡片 B",
                    title = pair.rightTitle,
                    body = pair.rightBody,
                    tags = pair.rightTags,
                    dateLabel = formatShortDate(pair.rightNote.createdAt),
                    dark = dark,
                    onOpen = { onOpenNote(pair.rightNote.id) },
                    modifier = Modifier.weight(1f).testTag("dual-card-right"),
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                DualCollisionNoteCard(
                    badge = "卡片 A",
                    title = pair.leftTitle,
                    body = pair.leftBody,
                    tags = pair.leftTags,
                    dateLabel = formatShortDate(pair.leftNote.createdAt),
                    dark = dark,
                    onOpen = { onOpenNote(pair.leftNote.id) },
                    modifier = Modifier.fillMaxWidth().testTag("dual-card-left"),
                )
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .width(1.5.dp)
                            .height(10.dp)
                            .background(linkAccent.fg.copy(alpha = 0.45f)),
                    )
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(linkAccent.bg)
                            .border(0.6.dp, linkAccent.fg.copy(alpha = 0.32f), RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = MintIcons.Link,
                            contentDescription = null,
                            tint = linkAccent.fg,
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            text = "跨笔记碰撞",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = linkAccent.fg,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .width(1.5.dp)
                            .height(10.dp)
                            .background(linkAccent.fg.copy(alpha = 0.45f)),
                    )
                }
                DualCollisionNoteCard(
                    badge = "卡片 B",
                    title = pair.rightTitle,
                    body = pair.rightBody,
                    tags = pair.rightTags,
                    dateLabel = formatShortDate(pair.rightNote.createdAt),
                    dark = dark,
                    onOpen = { onOpenNote(pair.rightNote.id) },
                    modifier = Modifier.fillMaxWidth().testTag("dual-card-right"),
                )
            }
        }

        // Reflection + Explicit Analyze + Save section
        MintGroupedSection(dark = dark, containerColor = mintCardSurface(dark)) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(mintSecondaryFill(dark))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    BasicTextField(
                        value = reflectionDraft,
                        onValueChange = { reflectionDraft = it },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
                        cursorBrush = SolidColor(scheme.primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 42.dp)
                            .testTag("dual-reflection-input"),
                        decorationBox = { inner ->
                            Box(Modifier.fillMaxWidth()) {
                                if (reflectionDraft.isEmpty()) {
                                    Text(
                                        text = "写下这两张卡片激发的新联想（不点分析也能直接存笔记）…",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = scheme.onSurfaceVariant.copy(alpha = 0.72f),
                                    )
                                }
                                inner()
                            }
                        },
                    )
                }

                val bridge = state.dualBridge
                if (bridge != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(mintSecondaryFill(dark))
                            .padding(12.dp)
                            .testTag("dual-bridge-panel"),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = bridge.headline,
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = scheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = if (state.dualBridgeFarFetched) "已标牵强" else "牵强",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                color = if (state.dualBridgeFarFetched) scheme.onSurfaceVariant else scheme.primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(
                                        enabled = !state.dualBridgeFarFetched,
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        role = Role.Button,
                                        onClick = onMarkFarFetched,
                                    )
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                                    .testTag("dual-far-fetched-button"),
                            )
                        }
                        if (!state.dualBridgeFarFetched) {
                            bridge.points.forEach { pt ->
                                Text(
                                    text = "• $pt",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurface,
                                )
                            }
                        } else {
                            Text(
                                text = "你标记了这条联系偏牵强，可以忽略上面提示，直接写自己的想法或换一组。",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = bridge.engineNotice,
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MintPillButton(
                        label = if (bridge == null) "寻找联系" else "重新分析",
                        primary = false,
                        dark = dark,
                        icon = MintIcons.Sparkles,
                        modifier = Modifier.weight(1f).testTag("dual-analyze-button"),
                        onClick = onAnalyze,
                    )
                    MintPillButton(
                        label = "建立链接",
                        primary = true,
                        dark = dark,
                        icon = MintIcons.Link,
                        modifier = Modifier.weight(1.15f).testTag("dual-save-button"),
                        onClick = { onSaveAsNote(reflectionDraft) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DualCollisionNoteCard(
    badge: String,
    title: String,
    body: String,
    tags: List<String>,
    dateLabel: String,
    dark: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    MintGroupedSection(
        dark = dark,
        containerColor = mintCardSurface(dark),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(mintSecondaryFill(dark))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = mintLinkAccent(dark).fg,
                    )
                }
                Text(
                    text = dateLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = mintTertiaryTextColor(dark),
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (body.isNotEmpty()) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (tags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    tags.take(3).forEach { t ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(7.dp))
                                .background(mintSecondaryFill(dark))
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "#$t",
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.primary,
                            )
                        }
                    }
                }
            }
            Text(
                text = "查看原笔记",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = scheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onOpen,
                    )
                    .padding(vertical = 4.dp),
            )
        }
    }
}
