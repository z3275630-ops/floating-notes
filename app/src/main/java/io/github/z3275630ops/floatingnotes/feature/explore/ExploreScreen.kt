package io.github.z3275630ops.floatingnotes.feature.explore

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.z3275630ops.floatingnotes.data.discovery.EvidenceHit
import io.github.z3275630ops.floatingnotes.data.discovery.LocalRetrievalEngine
import io.github.z3275630ops.floatingnotes.data.discovery.ReviewCardModel
import io.github.z3275630ops.floatingnotes.data.discovery.ReviewConfig
import io.github.z3275630ops.floatingnotes.data.local.NoteEntity
import io.github.z3275630ops.floatingnotes.data.local.NoteScope
import io.github.z3275630ops.floatingnotes.data.local.TagEntity
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
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintCardSurface
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintDestructiveColor
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintSecondaryFill

@Composable
internal fun ExploreScreen(
    state: ExploreUiState,
    tags: List<TagEntity>,
    notesCount: Int,
    linksCount: Int,
    openTodosCount: Int,
    dark: Boolean,
    backdrop: GlassBackdrop,
    onEnsureLoaded: () -> Unit,
    onClearMessage: () -> Unit,
    onStartExtraReviewRound: () -> Unit,
    onSelectReviewIndex: (Int) -> Unit,
    onDeferReviewCard: (String) -> Unit,
    onSuppressReviewCard7Days: (String) -> Unit,
    onExcludeReviewCard: (String) -> Unit,
    onExcludeReviewTag: (String, String) -> Unit,
    onUpdateReviewConfig: (ReviewConfig) -> Unit,
    onRestoreAllExclusions: () -> Unit,
    onAnnotateReviewCard: (NoteEntity, String) -> Unit,
    onQueryChange: (String) -> Unit,
    onScopeChange: (NoteScope) -> Unit,
    onSearchFind: (String, NoteScope) -> Unit,
    onRefreshIndex: () -> Unit,
    onToggleBasket: (EvidenceHit) -> Unit,
    onClearBasket: () -> Unit,
    onSaveBasketAsNote: () -> Unit,
    onExcludeFindNote: (String) -> Unit,
    onStartWalk: (seedNoteId: String?, seedTagId: String?) -> Unit,
    onStepWalkForward: (Boolean) -> Unit,
    onStepWalkBack: () -> Unit,
    onSaveWalkMemo: (String) -> Unit,
    onFinishWalkPostcard: () -> Unit,
    onToggleWalkPrivacy: (Boolean) -> Unit,
    onSaveWalkPostcardAsNote: () -> Unit,
    onDismissWalkPostcard: () -> Unit,
    onOpenNote: (String, String?) -> Unit,
    onOpenTodos: () -> Unit,
    onSelectWalkMode: (WalkMode) -> Unit = {},
    onDrawNextDualPair: () -> Unit = {},
    onStepDualPairBack: () -> Unit = {},
    onAnalyzeDualPair: () -> Unit = {},
    onMarkDualFarFetched: () -> Unit = {},
    onSaveDualAsNote: (String) -> Unit = {},
    onOpenStats: () -> Unit = {},
    onShareNote: (NoteEntity) -> Unit = {},
    selectedSectionIndex: Int? = null,
    onSelectSection: ((Int) -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    var internalSectionIndex by rememberSaveable { mutableStateOf(0) }
    val activeSectionIndex = selectedSectionIndex ?: internalSectionIndex
    val updateSection: (Int) -> Unit = { idx ->
        internalSectionIndex = idx
        onSelectSection?.invoke(idx)
    }

    LaunchedEffect(Unit) {
        onEnsureLoaded()
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "知识库 $notesCount 篇 · $linksCount 条关联",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
            MintChip(
                label = "统计与热力图",
                leadingIcon = MintIcons.Chart,
                selected = false,
                dark = dark,
                modifier = Modifier.testTag("explore-open-stats"),
                onClick = onOpenStats,
            )
        }
        MintSegmentedBar(
            options = ExploreSection.entries.map { it.label },
            selectedIndex = activeSectionIndex,
            onSelect = updateSection,
            dark = dark,
            optionTags = listOf("explore-tab-review", "explore-tab-find", "explore-tab-walk"),
        )

        state.statusMessage?.let { msg ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(scheme.primary.copy(alpha = if (dark) 0.22f else 0.10f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClearMessage,
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = scheme.primary,
                )
            }
        }

        state.errorMessage?.let { err ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(mintDestructiveColor(dark).copy(alpha = 0.12f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClearMessage,
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = mintDestructiveColor(dark),
                )
            }
        }

        when (ExploreSection.entries[activeSectionIndex]) {
            ExploreSection.Review -> {
                ReviewTabContent(
                    state = state,
                    tags = tags,
                    notesCount = notesCount,
                    linksCount = linksCount,
                    openTodosCount = openTodosCount,
                    dark = dark,
                    backdrop = backdrop,
                    onStartExtraRound = onStartExtraReviewRound,
                    onSelectIndex = onSelectReviewIndex,
                    onDefer = onDeferReviewCard,
                    onSuppress7Days = onSuppressReviewCard7Days,
                    onExcludeCard = onExcludeReviewCard,
                    onExcludeTag = onExcludeReviewTag,
                    onUpdateConfig = onUpdateReviewConfig,
                    onRestoreExclusions = onRestoreAllExclusions,
                    onAnnotate = onAnnotateReviewCard,
                    onOpenNote = { noteId -> onOpenNote(noteId, null) },
                    onStartWalkFromNote = { noteId ->
                        updateSection(2)
                        onStartWalk(noteId, null)
                    },
                    onOpenTodos = onOpenTodos,
                    onOpenStats = onOpenStats,
                    onShareNote = onShareNote,
                )
            }
            ExploreSection.Find -> {
                FindTabContent(
                    state = state,
                    tags = tags,
                    dark = dark,
                    onQueryChange = onQueryChange,
                    onScopeChange = onScopeChange,
                    onSearch = onSearchFind,
                    onRefreshIndex = onRefreshIndex,
                    onToggleBasket = onToggleBasket,
                    onClearBasket = onClearBasket,
                    onSaveBasketAsNote = onSaveBasketAsNote,
                    onOpenNote = onOpenNote,
                    onExcludeNote = onExcludeFindNote,
                )
            }
            ExploreSection.Walk -> {
                WalkTabContent(
                    state = state,
                    tags = tags,
                    dark = dark,
                    backdrop = backdrop,
                    onSelectWalkMode = onSelectWalkMode,
                    onStartWalk = { tagId -> onStartWalk(null, tagId) },
                    onStepForward = onStepWalkForward,
                    onStepBack = onStepWalkBack,
                    onSaveMemo = onSaveWalkMemo,
                    onFinishPostcard = onFinishWalkPostcard,
                    onTogglePrivacy = onToggleWalkPrivacy,
                    onSavePostcardAsNote = onSaveWalkPostcardAsNote,
                    onDismissPostcard = onDismissWalkPostcard,
                    onDrawNextDualPair = onDrawNextDualPair,
                    onStepDualBack = onStepDualPairBack,
                    onAnalyzeDualPair = onAnalyzeDualPair,
                    onMarkDualFarFetched = onMarkDualFarFetched,
                    onSaveDualAsNote = onSaveDualAsNote,
                    onOpenNote = { noteId -> onOpenNote(noteId, null) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewTabContent(
    state: ExploreUiState,
    tags: List<TagEntity>,
    notesCount: Int,
    linksCount: Int,
    openTodosCount: Int,
    dark: Boolean,
    backdrop: GlassBackdrop,
    onStartExtraRound: () -> Unit,
    onSelectIndex: (Int) -> Unit,
    onDefer: (String) -> Unit,
    onSuppress7Days: (String) -> Unit,
    onExcludeCard: (String) -> Unit,
    onExcludeTag: (String, String) -> Unit,
    onUpdateConfig: (ReviewConfig) -> Unit,
    onRestoreExclusions: () -> Unit,
    onAnnotate: (NoteEntity, String) -> Unit,
    onOpenNote: (String) -> Unit,
    onStartWalkFromNote: (String) -> Unit,
    onOpenTodos: () -> Unit,
    onOpenStats: () -> Unit,
    onShareNote: (NoteEntity) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val review = state.review
    var expandedCardId by rememberSaveable { mutableStateOf<String?>(null) }
    var annotatingCard by remember { mutableStateOf<ReviewCardModel?>(null) }
    var showReviewSettings by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.loading && review == null) {
            MintGroupedSection(dark = dark) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MintActivityIndicator(color = scheme.primary)
                    Text(
                        text = "正在整理今日回顾卡片…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        } else if (review == null || review.cards.isEmpty()) {
            MintGroupedSection(
                dark = dark,
                modifier = Modifier.testTag("review-empty-card"),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = if (notesCount == 0) "写下几条笔记，明天就能在这里重温" else "今日回顾队列已全部完成或暂无可回顾卡片",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = scheme.onSurface,
                    )
                    Text(
                        text = "你可以在回顾设置中调整时间范围或恢复已排除的内容。",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MintPillButton(
                            label = "再抽一轮",
                            primary = true,
                            dark = dark,
                            onClick = onStartExtraRound,
                        )
                        MintPillButton(
                            label = "回顾设置",
                            primary = false,
                            dark = dark,
                            modifier = Modifier.testTag("review-settings-button"),
                            onClick = { showReviewSettings = true },
                        )
                    }
                }
            }
        } else {
            val cards = review.cards
            val idx = review.currentIndex.coerceIn(0, cards.lastIndex)
            val card = cards[idx]
            val expanded = expandedCardId == card.note.id

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "今日回顾 · 第 ${idx + 1} / ${cards.size} 篇（第 ${review.session.round} 轮）",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.testTag("review-progress-label"),
                )
                Text(
                    text = "偏好设置",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = scheme.primary,
                    modifier = Modifier
                        .testTag("review-settings-button")
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClick = { showReviewSettings = true },
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }

            MintGroupedSection(
                dark = dark,
                containerColor = mintCardSurface(dark),
                modifier = Modifier.testTag("review-active-card"),
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
                        Text(
                            text = formatShortDate(card.note.createdAt),
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.primary,
                        )
                        Text(
                            text = "跳回原笔记 ›",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = scheme.primary,
                            modifier = Modifier
                                .testTag("review-open-source")
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    role = Role.Button,
                                    onClick = { onOpenNote(card.note.id) },
                                )
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }

                    Text(
                        text = card.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = scheme.onSurface,
                        modifier = Modifier.testTag("review-card-title"),
                    )

                    if (card.tagNames.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            card.tagNames.forEachIndexed { tIdx, tName ->
                                val tId = card.tagIds.getOrNull(tIdx)
                                MintChip(
                                    label = "#$tName",
                                    dark = dark,
                                    onClick = {
                                        if (tId != null) onExcludeTag(tId, tName)
                                    },
                                )
                            }
                        }
                    }

                    val displayBody = LocalRetrievalEngine.bodyExcludingTitle(
                        note = card.note,
                        rawBody = if (expanded) card.fullText else card.previewExcerpt,
                        title = card.title,
                    )
                    if (displayBody.isNotEmpty()) {
                        Text(
                            text = displayBody,
                            style = MaterialTheme.typography.bodyLarge,
                            color = scheme.onSurface,
                            maxLines = if (expanded) 18 else 5,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    if (card.fullText.length > 140) {
                        Text(
                            text = if (expanded) "收起原文" else "展开原文",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = scheme.primary,
                            modifier = Modifier
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    role = Role.Button,
                                    onClick = {
                                        expandedCardId = if (expanded) null else card.note.id
                                    },
                                )
                                .padding(vertical = 4.dp),
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MintPillButton(
                            label = "写批注",
                            primary = true,
                            dark = dark,
                            modifier = Modifier.weight(1f).testTag("review-annotate-button"),
                            onClick = { annotatingCard = card },
                        )
                        MintPillButton(
                            label = "稍后再看",
                            primary = false,
                            dark = dark,
                            modifier = Modifier.weight(1f).testTag("review-defer-button"),
                            onClick = { onDefer(card.note.id) },
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MintPillButton(
                            label = "上一张",
                            primary = false,
                            dark = dark,
                            enabled = idx > 0,
                            modifier = Modifier.weight(1f),
                            onClick = { onSelectIndex(idx - 1) },
                        )
                        if (idx < cards.lastIndex) {
                            MintPillButton(
                                label = "下一张",
                                primary = false,
                                dark = dark,
                                modifier = Modifier.weight(1f),
                                onClick = { onSelectIndex(idx + 1) },
                            )
                        } else {
                            MintPillButton(
                                label = "再抽一轮",
                                primary = false,
                                dark = dark,
                                modifier = Modifier.weight(1f),
                                onClick = onStartExtraRound,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "分享卡片",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                            color = scheme.primary,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier
                                .testTag("review-share-card-button")
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    role = Role.Button,
                                    onClick = { onShareNote(card.note) },
                                )
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                        Text(
                            text = "从这里漫步",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                            color = scheme.primary,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    role = Role.Button,
                                    onClick = { onStartWalkFromNote(card.note.id) },
                                )
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }

                    MintGroupedDivider(dark = dark, startIndent = 0.dp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "7 天内不再出现",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier
                                .testTag("review-suppress-button")
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    role = Role.Button,
                                    onClick = { onSuppress7Days(card.note.id) },
                                )
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                        )
                        Text(
                            text = "不再出现在回顾中",
                            style = MaterialTheme.typography.labelSmall,
                            color = mintDestructiveColor(dark),
                            modifier = Modifier
                                .testTag("review-exclude-button")
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    role = Role.Button,
                                    onClick = { onExcludeCard(card.note.id) },
                                )
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                        )
                    }
                }
            }
        }

        // On This Day ("那年今日") - only real historical entries, never fabricated
        MintGroupedSection(
            dark = dark,
            containerColor = mintCardSurface(dark),
            modifier = Modifier.testTag("review-on-this-day"),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = MintIcons.Calendar,
                        contentDescription = null,
                        tint = scheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "那年今日",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = scheme.onSurface,
                    )
                }
                val historyEntries = review?.onThisDay.orEmpty()
                if (historyEntries.isEmpty()) {
                    Text(
                        text = "往年今天还没有留下笔记。今天写下的一条记录，将在明年此时回到这里与你重逢。",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                } else {
                    historyEntries.take(4).forEachIndexed { i, entry ->
                        if (i > 0) MintGroupedDivider(dark = dark, startIndent = 0.dp)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    role = Role.Button,
                                    onClick = { onOpenNote(entry.note.id) },
                                )
                                .padding(vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                text = entry.yearLabel,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = scheme.primary,
                            )
                            Text(
                                text = entry.excerpt,
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        // Compact knowledge pulse summary card + stats entry (T26 / S17)
        MintGroupedSection(dark = dark, containerColor = mintCardSurface(dark)) {
            MintGroupedRow(
                label = "记录统计与热力图",
                subtitle = "$notesCount 篇笔记 · $linksCount 条引用 · " +
                    if (openTodosCount > 0) "待办 $openTodosCount 项" else "待办已清空",
                leadingIcon = MintIcons.Chart,
                showChevron = true,
                dark = dark,
                onClick = onOpenStats,
            )
            if (openTodosCount > 0) {
                MintGroupedDivider(dark = dark, startIndent = 48.dp)
                MintGroupedRow(
                    label = "待办清单",
                    subtitle = "当前仍有 $openTodosCount 项未完成待办",
                    leadingIcon = MintIcons.Checklist,
                    showChevron = true,
                    dark = dark,
                    onClick = onOpenTodos,
                )
            }
        }
    }

    annotatingCard?.let { card ->
        ReviewAnnotateSheet(
            targetTitle = card.title,
            dark = dark,
            backdrop = backdrop,
            onDismiss = { annotatingCard = null },
            onSubmit = { text ->
                onAnnotate(card.note, text)
                annotatingCard = null
            },
        )
    }

    if (showReviewSettings) {
        ReviewSettingsSheet(
            config = review?.config ?: ReviewConfig(),
            suppressedCount = review?.suppressedCount ?: 0,
            tags = tags,
            dark = dark,
            backdrop = backdrop,
            onUpdateConfig = onUpdateConfig,
            onRestoreExclusions = onRestoreExclusions,
            onDismiss = { showReviewSettings = false },
        )
    }
}

@Composable
private fun ReviewAnnotateSheet(
    targetTitle: String,
    dark: Boolean,
    backdrop: GlassBackdrop,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    MintBottomSheet(
        onDismissRequest = onDismiss,
        dark = dark,
        backdrop = backdrop,
        title = "写一条回顾批注",
        subtitle = "关联原笔记：$targetTitle",
        leadingLabel = "取消",
        trailingLabel = "保存笔记",
        onTrailingClick = { onSubmit(text) },
        trailingEnabled = text.isNotBlank(),
    ) {
        MintTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = "现在重读这条笔记，你有什么新想法？",
            dark = dark,
            singleLine = false,
            modifier = Modifier.fillMaxWidth().testTag("review-annotate-input"),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewSettingsSheet(
    config: ReviewConfig,
    suppressedCount: Int,
    tags: List<TagEntity>,
    dark: Boolean,
    backdrop: GlassBackdrop,
    onUpdateConfig: (ReviewConfig) -> Unit,
    onRestoreExclusions: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val tagsById = remember(tags) { tags.associateBy { it.id } }

    MintBottomSheet(
        onDismissRequest = onDismiss,
        dark = dark,
        backdrop = backdrop,
        title = "回顾偏好设置",
        subtitle = "每日卡片数量与内容范围",
        leadingLabel = "完成",
        onLeadingClick = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MintGroupedSection(dark = dark) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "每日抽取数量",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = scheme.onSurface,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(3, 5, 8).forEach { count ->
                            MintChip(
                                label = "${count} 张/天",
                                dark = dark,
                                selected = config.dailyCount == count,
                                style = if (config.dailyCount == count) MintChipStyle.Selected else MintChipStyle.Standard,
                                onClick = { onUpdateConfig(config.copy(dailyCount = count)) },
                            )
                        }
                    }
                }
                MintGroupedDivider(dark = dark, startIndent = 14.dp)
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "回顾时间跨度",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = scheme.onSurface,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "全部时间", 7 to "近 7 天", 30 to "近 30 天", 90 to "近 90 天").forEach { (days, label) ->
                            MintChip(
                                label = label,
                                dark = dark,
                                selected = config.rangeDays == days,
                                style = if (config.rangeDays == days) MintChipStyle.Selected else MintChipStyle.Standard,
                                onClick = { onUpdateConfig(config.copy(rangeDays = days)) },
                            )
                        }
                    }
                }
            }

            MintGroupedSection(dark = dark) {
                MintGroupedRow(
                    label = "包含无标签笔记",
                    dark = dark,
                    trailingContent = {
                        MintSwitch(
                            checked = config.includeUntagged,
                            onCheckedChange = { onUpdateConfig(config.copy(includeUntagged = it)) },
                            dark = dark,
                        )
                    },
                    onClick = { onUpdateConfig(config.copy(includeUntagged = !config.includeUntagged)) },
                )
                MintGroupedDivider(dark = dark)
                MintGroupedRow(
                    label = "仅看含未完成待办的笔记",
                    dark = dark,
                    trailingContent = {
                        MintSwitch(
                            checked = config.includeTodosOnly,
                            onCheckedChange = { onUpdateConfig(config.copy(includeTodosOnly = it)) },
                            dark = dark,
                        )
                    },
                    onClick = { onUpdateConfig(config.copy(includeTodosOnly = !config.includeTodosOnly)) },
                )
            }

            if (config.excludedTagIds.isNotEmpty()) {
                MintGroupedSection(dark = dark) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "已排除的标签（点击可恢复）",
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            config.excludedTagIds.forEach { tid ->
                                val name = tagsById[tid]?.name ?: tid.take(6)
                                MintChip(
                                    label = "#$name",
                                    dark = dark,
                                    removable = true,
                                    onClick = {
                                        onUpdateConfig(config.copy(excludedTagIds = config.excludedTagIds - tid))
                                    },
                                )
                            }
                        }
                    }
                }
            }

            MintGroupedSection(dark = dark) {
                MintGroupedRow(
                    label = "恢复已排除的内容",
                    subtitle = if (suppressedCount > 0) "当前共隐藏 $suppressedCount 条笔记/标签" else "当前没有被隐藏的内容",
                    leadingIcon = MintIcons.Restore,
                    dark = dark,
                    enabled = suppressedCount > 0,
                    modifier = Modifier.testTag("review-restore-exclusions"),
                    onClick = onRestoreExclusions,
                )
            }
        }
    }
}
