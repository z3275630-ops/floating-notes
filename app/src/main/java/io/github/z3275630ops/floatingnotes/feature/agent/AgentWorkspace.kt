package io.github.z3275630ops.floatingnotes.feature.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.z3275630ops.floatingnotes.data.agent.AgentActionEntity
import io.github.z3275630ops.floatingnotes.data.agent.AgentRunEntity
import io.github.z3275630ops.floatingnotes.data.agent.AgentRunResult
import io.github.z3275630ops.floatingnotes.data.agent.AgentSkill
import io.github.z3275630ops.floatingnotes.data.agent.AgentTimeRange
import io.github.z3275630ops.floatingnotes.data.discovery.DuplicateNoteGroup
import io.github.z3275630ops.floatingnotes.data.discovery.LocalRetrievalEngine
import io.github.z3275630ops.floatingnotes.data.discovery.LocalTextIntelligence
import io.github.z3275630ops.floatingnotes.data.discovery.MergedDuplicateContent
import io.github.z3275630ops.floatingnotes.data.local.TagEntity
import io.github.z3275630ops.floatingnotes.ui.designsystem.GlassBackdrop
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintActivityIndicator
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintBottomSheet
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintCheckbox
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
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintAiAccent
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintCardSurface
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintDestructiveColor
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintDividerColor
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintLinkAccent
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintReviewAccent
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintSecondaryFill
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintTertiaryTextColor
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintTodoAccent
import io.github.z3275630ops.floatingnotes.ui.designsystem.rememberGlassBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class AgentWorkspaceTab(val index: Int, val label: String) {
    Research(0, "研究与对话"),
    Insights(1, "多视角洞察"),
    Memory(2, "记忆档案"),
}

internal data class AgentProgressStepUi(
    val stepIndex: Int,
    val totalSteps: Int = 8,
    val toolName: String,
    val summary: String,
    val elapsedMs: Long = 0L,
)

internal data class AgentCitationUi(
    val noteId: String,
    val noteTitle: String,
    val excerpt: String = "",
    val reason: String = "",
)

internal sealed interface AgentWriteProposalUi {
    val proposalId: String
    val reason: String
    val applied: Boolean
    val dismissed: Boolean

    data class NewNote(
        override val proposalId: String,
        val title: String,
        val markdownBody: String,
        val suggestedTags: List<String> = emptyList(),
        override val reason: String = "",
        override val applied: Boolean = false,
        override val dismissed: Boolean = false,
    ) : AgentWriteProposalUi

    data class PatchNote(
        override val proposalId: String,
        val targetNoteId: String,
        val targetTitle: String,
        val beforeExcerpt: String,
        val afterContent: String,
        val appendMode: Boolean = false,
        override val reason: String = "",
        override val applied: Boolean = false,
        override val dismissed: Boolean = false,
    ) : AgentWriteProposalUi

    data class ExtractTodos(
        override val proposalId: String,
        val noteId: String? = null,
        val targetTitle: String,
        val items: List<String>,
        override val reason: String = "",
        override val applied: Boolean = false,
        override val dismissed: Boolean = false,
    ) : AgentWriteProposalUi
}

internal data class AgentChatTurnUi(
    val id: String,
    val role: String,
    val text: String,
    val citations: List<AgentCitationUi> = emptyList(),
    val proposals: List<AgentWriteProposalUi> = emptyList(),
    val usedRemoteAi: Boolean = false,
    val modelUsed: String? = null,
    val steps: List<AgentProgressStepUi> = emptyList(),
    val createdAtLabel: String = "",
)

internal data class AgentPresetUi(
    val id: String,
    val name: String,
    val description: String,
    val promptTemplate: String,
    val defaultRangeDays: Int? = null,
    val builtIn: Boolean = true,
    val iconKey: String = "",
)

internal val defaultBuiltInInsightPresets: List<AgentPresetUi> = listOf(
    AgentPresetUi(
        id = "blind_spots",
        name = "找思维盲点",
        description = "对照近期笔记，找出被忽视的前提与盲区",
        promptTemplate = "请基于我近期的笔记，指出反复回避的前提假设、思维盲点以及反面证据。",
        defaultRangeDays = 30,
        builtIn = true,
        iconKey = "explore",
    ),
    AgentPresetUi(
        id = "sharp_questions",
        name = "3 个尖锐追问",
        description = "直击核心矛盾，给出 3 个值得深思的问题",
        promptTemplate = "请阅读我近期的记录，提出 3 个直击本质、能帮我打破现状的尖锐追问，并附引用依据。",
        defaultRangeDays = 14,
        builtIn = true,
        iconKey = "sparkles",
    ),
    AgentPresetUi(
        id = "viewpoint_evolution",
        name = "观点变化对比",
        description = "对比同一主题早晚想法的偏移与演化轨迹",
        promptTemplate = "梳理我近期同一主题在早期与最新笔记中的观点变化，列出具体转折节点。",
        defaultRangeDays = null,
        builtIn = true,
        iconKey = "history",
    ),
    AgentPresetUi(
        id = "meaning_radar",
        name = "意义雷达",
        description = "扫描高频情绪、价值信号与长期关心之事",
        promptTemplate = "从近期碎片记录中扫描反复出现的核心关注点、内在动机与长期意义信号。",
        defaultRangeDays = 30,
        builtIn = true,
        iconKey = "chart",
    ),
)

internal data class AgentInsightResultUi(
    val presetId: String,
    val presetName: String,
    val summaryMarkdown: String,
    val highlights: List<String> = emptyList(),
    val citations: List<AgentCitationUi> = emptyList(),
    val usedRemoteAi: Boolean = false,
    val modelUsed: String? = null,
)

internal data class AgentMemoryItemUi(
    val id: String,
    val category: String,
    val content: String,
    val sourceNoteId: String? = null,
    val sourceNoteTitle: String? = null,
    val enabled: Boolean = true,
    val userEdited: Boolean = false,
    val updatedAtLabel: String = "",
)

internal data class AgentMemoryCandidateUi(
    val candidateId: String,
    val category: String,
    val proposedContent: String,
    val existingContent: String? = null,
    val sourceNoteId: String? = null,
    val sourceNoteTitle: String? = null,
    val isConflictWithUserEdited: Boolean = false,
    val existingMemoryId: String? = null,
)

internal data class AgentUiState(
    val running: Boolean = false,
    val promptDraft: String = "",
    val timeRange: AgentTimeRange = AgentTimeRange.All,
    val selectedTagId: String? = null,
    val activeRun: AgentRunEntity? = null,
    val activeResult: AgentRunResult? = null,
    val activeActions: List<AgentActionEntity> = emptyList(),
    val recentRuns: List<AgentRunEntity> = emptyList(),
    val duplicateGroups: List<DuplicateNoteGroup> = emptyList(),
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    // --- 浮记 2.0 扩展字段（带默认值，保持原有构造与测试 100% 兼容）---
    val activeTabIndex: Int = 0,
    val remoteAiEnabled: Boolean = false,
    val remoteModelName: String? = null,
    val lastUsedRemoteAi: Boolean = false,
    val fallbackReason: String? = null,
    val liveSteps: List<AgentProgressStepUi> = emptyList(),
    val maxSteps: Int = 8,
    val chatTurns: List<AgentChatTurnUi> = emptyList(),
    val followUpDraft: String = "",
    val writeProposals: List<AgentWriteProposalUi> = emptyList(),
    val insightPresets: List<AgentPresetUi> = defaultBuiltInInsightPresets,
    val selectedPresetId: String? = null,
    val activeInsightResult: AgentInsightResultUi? = null,
    val memoryAutoReference: Boolean = true,
    val memoryExtracting: Boolean = false,
    val memoryItems: List<AgentMemoryItemUi> = emptyList(),
    val memoryCandidates: List<AgentMemoryCandidateUi> = emptyList(),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AgentWorkspace(
    state: AgentUiState,
    notesCount: Int,
    tags: List<TagEntity>,
    dark: Boolean,
    onPromptChange: (String) -> Unit,
    onSelectTimeRange: (AgentTimeRange) -> Unit,
    onSelectTagId: (String?) -> Unit,
    onRunSkill: (AgentSkill) -> Unit,
    onRunQuestion: (String) -> Unit,
    onStopRun: () -> Unit,
    onSelectPastRun: (AgentRunEntity) -> Unit,
    onSaveAsNote: () -> Unit,
    onExtractTodos: () -> Unit,
    onOpenNote: (String) -> Unit,
    onClearMessage: () -> Unit,
    onQuickCapture: () -> Unit,
    onMergeDuplicateGroup: (DuplicateNoteGroup, Boolean) -> Unit = { _, _ -> },
    onSelectTab: (Int) -> Unit = {},
    onOpenAiSettings: () -> Unit = {},
    onRerunLocally: () -> Unit = {},
    onFollowUpDraftChange: (String) -> Unit = {},
    onSendFollowUp: (String) -> Unit = {},
    onApplyWriteProposal: (AgentWriteProposalUi) -> Unit = {},
    onDismissWriteProposal: (AgentWriteProposalUi) -> Unit = {},
    onRunInsightPreset: (AgentPresetUi) -> Unit = {},
    onSaveCustomPreset: (
        id: String?,
        name: String,
        description: String,
        promptTemplate: String,
        defaultRangeDays: Int?,
    ) -> Unit = { _, _, _, _, _ -> },
    onDeleteCustomPreset: (String) -> Unit = {},
    onContinueChatFromInsight: (AgentInsightResultUi) -> Unit = {},
    onSaveInsightAsNote: (AgentInsightResultUi) -> Unit = {},
    onToggleMemoryAutoReference: (Boolean) -> Unit = {},
    onExtractMemoryUpdates: () -> Unit = {},
    onCommitMemoryCandidates: (List<AgentMemoryCandidateUi>) -> Unit = {},
    onDismissMemoryCandidates: () -> Unit = {},
    onToggleMemoryItem: (String, Boolean) -> Unit = { _, _ -> },
    onEditMemoryItem: (String, String, String) -> Unit = { _, _, _ -> },
    onDeleteMemoryItem: (String) -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    val backdrop = rememberGlassBackdrop()
    var showToolLog by rememberSaveable(state.activeRun?.id) { mutableStateOf(false) }
    var showPrivacyDetails by rememberSaveable { mutableStateOf(false) }
    var showLiveSteps by rememberSaveable { mutableStateOf(true) }
    var previewDuplicateGroup by remember { mutableStateOf<DuplicateNoteGroup?>(null) }
    var editingPreset by remember { mutableStateOf<AgentPresetUi?>(null) }
    var showCreatePresetSheet by remember { mutableStateOf(false) }
    var editingMemoryItem by remember { mutableStateOf<AgentMemoryItemUi?>(null) }
    var localTabIndex by rememberSaveable { mutableIntStateOf(state.activeTabIndex) }
    LaunchedEffect(state.activeTabIndex) {
        localTabIndex = state.activeTabIndex
    }

    val aiAccent = mintAiAccent(dark)
    val isRemoteActive = state.remoteAiEnabled && !state.remoteModelName.isNullOrBlank()
    val privacySummary = if (isRemoteActive) {
        "外部模型：${state.remoteModelName} · ${notesCount} 篇可检索"
    } else {
        "本机离线运行 · 不上传 · ${notesCount} 篇可检索"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("agent-workspace"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 顶部模式胶囊 + 快捷配置模型 / 切回本机重跑
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(aiAccent.bg)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = { showPrivacyDetails = !showPrivacyDetails },
                )
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag("agent-privacy-pill"),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (isRemoteActive) MintIcons.Sparkles else MintIcons.Lock,
                    contentDescription = null,
                    tint = aiAccent.fg,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = privacySummary,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                    color = aiAccent.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "配置模型",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = scheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(scheme.surface.copy(alpha = if (dark) 0.35f else 0.75f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClick = onOpenAiSettings,
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                        .testTag("agent-open-ai-settings"),
                )
                Text(
                    text = if (showPrivacyDetails) "收起" else "说明",
                    style = MaterialTheme.typography.labelSmall,
                    color = mintTertiaryTextColor(dark),
                )
            }
            if ((state.lastUsedRemoteAi || !state.fallbackReason.isNullOrBlank()) && !state.running) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = state.fallbackReason?.let { "已自动切回本机：$it" } ?: "本轮由外部模型协同生成",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (!state.fallbackReason.isNullOrBlank()) mintReviewAccent(dark).fg else scheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "切回本机重跑",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = aiAccent.fg,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Button,
                                onClick = onRerunLocally,
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .testTag("agent-rerun-local"),
                    )
                }
            }
            AnimatedVisibility(visible = showPrivacyDetails) {
                Text(
                    text = if (isRemoteActive) {
                        "当前已开启外部大模型（BYOK）。所有检索与写入提案仍由本机控制，标记为「不参与 AI」的笔记永远不会发往任何外部接口，修改笔记前均需你手动确认。"
                    } else {
                        "所有检索、归纳、回响、标签推荐与双卡对撞都在手机本地完成，默认遵守每条笔记的「不参与 AI 整理」策略，写入新笔记前都会先由你确认。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }

        // Status / Error banner
        val feedbackMsg = state.errorMessage ?: state.statusMessage
        if (!feedbackMsg.isNullOrBlank()) {
            val isErr = state.errorMessage != null
            MintGroupedSection(
                dark = dark,
                containerColor = if (isErr) scheme.errorContainer.copy(alpha = 0.65f) else mintSecondaryFill(dark),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = feedbackMsg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isErr) scheme.onErrorContainer else scheme.onSurface,
                        modifier = Modifier.weight(1f).testTag("agent-status-message"),
                    )
                    Text(
                        text = "知道了",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = if (isErr) scheme.onErrorContainer else scheme.primary,
                        modifier = Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Button,
                                onClick = onClearMessage,
                            )
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
            }
        }

        // 三段式 Apple 分段标签（默认选中 0: 研究与对话）
        MintSegmentedBar(
            options = AgentWorkspaceTab.entries.map { it.label },
            selectedIndex = localTabIndex.coerceIn(0, AgentWorkspaceTab.entries.lastIndex),
            onSelect = { idx ->
                localTabIndex = idx
                onSelectTab(idx)
            },
            dark = dark,
            optionTags = listOf("agent-tab-research", "agent-tab-insights", "agent-tab-memory"),
            badges = listOf(
                state.writeProposals.count { !it.applied && !it.dismissed }.takeIf { it > 0 },
                null,
                state.memoryCandidates.size.takeIf { it > 0 },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("agent-segmented-tabs"),
        )

        when (localTabIndex) {
            AgentWorkspaceTab.Insights.index -> {
                InsightsTabContent(
                    state = state,
                    dark = dark,
                    onRunPreset = { preset ->
                        focusManager.clearFocus()
                        onRunInsightPreset(preset)
                    },
                    onOpenCreatePreset = {
                        editingPreset = null
                        showCreatePresetSheet = true
                    },
                    onEditPreset = { preset ->
                        editingPreset = preset
                        showCreatePresetSheet = true
                    },
                    onDeletePreset = onDeleteCustomPreset,
                    onOpenNote = onOpenNote,
                    onContinueChat = { result ->
                        onContinueChatFromInsight(result)
                        localTabIndex = AgentWorkspaceTab.Research.index
                        onSelectTab(AgentWorkspaceTab.Research.index)
                    },
                    onSaveInsightAsNote = onSaveInsightAsNote,
                )
            }

            AgentWorkspaceTab.Memory.index -> {
                MemoryTabContent(
                    state = state,
                    dark = dark,
                    onToggleAutoReference = onToggleMemoryAutoReference,
                    onExtractUpdates = onExtractMemoryUpdates,
                    onCommitCandidates = onCommitMemoryCandidates,
                    onDismissCandidates = onDismissMemoryCandidates,
                    onToggleMemoryItem = onToggleMemoryItem,
                    onEditMemoryItem = { item -> editingMemoryItem = item },
                    onDeleteMemoryItem = onDeleteMemoryItem,
                    onOpenNote = onOpenNote,
                )
            }

            else -> {
                // Tab 1: 研究与对话（保留原有全部功能与 testTag + 多步进度 + 多轮对话 + Safe Mode 提案）
                MintGroupedSection(dark = dark, containerColor = mintCardSurface(dark)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(mintSecondaryFill(dark))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = MintIcons.Search,
                                contentDescription = null,
                                tint = scheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                            BasicTextField(
                                value = state.promptDraft,
                                onValueChange = onPromptChange,
                                enabled = !state.running,
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
                                cursorBrush = SolidColor(scheme.primary),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(
                                    onSearch = {
                                        focusManager.clearFocus()
                                        onRunQuestion(state.promptDraft)
                                    },
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("agent-prompt-input"),
                                decorationBox = { inner ->
                                    Box(modifier = Modifier.fillMaxWidth()) {
                                        if (state.promptDraft.isEmpty()) {
                                            Text(
                                                text = "向自己的笔记库提一个具体问题…",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = scheme.onSurfaceVariant.copy(alpha = 0.72f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        inner()
                                    }
                                },
                            )
                            if (state.promptDraft.isNotEmpty() && !state.running) {
                                Text(
                                    text = "清空",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            role = Role.Button,
                                            onClick = { onPromptChange("") },
                                        )
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AgentTimeRange.entries.forEach { range ->
                                MintChip(
                                    label = range.label,
                                    selected = state.timeRange == range,
                                    dark = dark,
                                    onClick = { onSelectTimeRange(range) },
                                )
                            }
                            tags.take(4).forEach { tag ->
                                val shortName = if (tag.name.length > 9) tag.name.take(8) + "…" else tag.name
                                MintChip(
                                    label = "#$shortName",
                                    selected = state.selectedTagId == tag.id,
                                    dark = dark,
                                    onClick = {
                                        onSelectTagId(if (state.selectedTagId == tag.id) null else tag.id)
                                    },
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (state.running) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = scheme.primary,
                                    )
                                    val latestStep = state.liveSteps.lastOrNull()
                                    Text(
                                        text = if (latestStep != null) {
                                            "步骤 ${latestStep.stepIndex}/${latestStep.totalSteps} · ${latestStep.toolName}"
                                        } else if (isRemoteActive) {
                                            "正在调度多步检索与分析…"
                                        } else {
                                            "正在检索并整理本机线索…"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = scheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                MintPillButton(
                                    label = "停止",
                                    primary = false,
                                    dark = dark,
                                    modifier = Modifier.testTag("agent-stop-button"),
                                    onClick = onStopRun,
                                )
                            } else {
                                MintPillButton(
                                    label = if (isRemoteActive) "开始深度分析" else "开始本机分析",
                                    primary = true,
                                    dark = dark,
                                    icon = MintIcons.Agent,
                                    enabled = state.promptDraft.isNotBlank() && notesCount > 0,
                                    modifier = Modifier.weight(1f).testTag("agent-run-button"),
                                    onClick = {
                                        focusManager.clearFocus()
                                        onRunQuestion(state.promptDraft)
                                    },
                                )
                            }
                        }

                        // 多步工具进度条与可折叠步骤追踪
                        if (state.running || state.liveSteps.isNotEmpty()) {
                            LiveStepsTracePanel(
                                steps = state.liveSteps,
                                maxSteps = state.maxSteps,
                                running = state.running,
                                expanded = showLiveSteps,
                                onToggleExpanded = { showLiveSteps = !showLiveSteps },
                                dark = dark,
                            )
                        }
                    }
                }

                // Quick 1-tap built-in skills (Apple Notes grouped rows inside white card)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "常用场景 · 一键整理",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    MintGroupedSection(dark = dark, containerColor = mintCardSurface(dark)) {
                        AgentSkill.quickSkills.forEachIndexed { idx, skill ->
                            if (idx > 0) MintGroupedDivider(dark = dark, startIndent = 46.dp)
                            val icon = when (skill) {
                                AgentSkill.DailyReflection -> MintIcons.Explore
                                AgentSkill.EvolutionTrack -> MintIcons.History
                                AgentSkill.CounterEvidence -> MintIcons.Link
                                AgentSkill.TodoTriage -> MintIcons.Checklist
                                AgentSkill.WeeklyDraft -> MintIcons.Records
                                AgentSkill.DuplicateCleanup -> MintIcons.Library
                                AgentSkill.FreeQuestion -> MintIcons.Agent
                            }
                            MintGroupedRow(
                                label = skill.title,
                                subtitle = skill.subtitle,
                                leadingIcon = icon,
                                showChevron = true,
                                dark = dark,
                                modifier = Modifier.testTag("agent-skill-${skill.id}"),
                                onClick = if (!state.running) {
                                    {
                                        focusManager.clearFocus()
                                        onRunSkill(skill)
                                    }
                                } else null,
                            )
                        }
                    }
                }

                // 多轮追问对话流
                if (state.chatTurns.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("agent-chat-thread"),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "研究对话流（${state.chatTurns.size} 轮消息）",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                        state.chatTurns.forEach { turn ->
                            ChatTurnCard(
                                turn = turn,
                                dark = dark,
                                onOpenNote = onOpenNote,
                                onApplyProposal = onApplyWriteProposal,
                                onDismissProposal = onDismissWriteProposal,
                            )
                        }
                    }
                }

                // Active run output card (保持原有全部结构与 testTags)
                val activeRun = state.activeRun
                val result = state.activeResult
                if (activeRun != null && result != null) {
                    val savedNoteAction = state.activeActions.firstOrNull {
                        it.actionType == "save_note" && it.status == "committed"
                    }
                    val savedTodosAction = state.activeActions.firstOrNull {
                        it.actionType == "extract_todos" && it.status == "committed"
                    }
                    val skill = AgentSkill.fromId(activeRun.skillId)
                    val statusLabel = when (activeRun.status) {
                        "completed" -> "已完成"
                        "insufficient_evidence" -> "证据不足"
                        "cancelled" -> "已停止"
                        "failed" -> "异常中止"
                        else -> activeRun.status
                    }

                    MintGroupedSection(
                        dark = dark,
                        containerColor = mintCardSurface(dark),
                        modifier = Modifier.testTag("agent-result-card"),
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
                                        .padding(horizontal = 9.dp, vertical = 4.dp),
                                ) {
                                    Text(
                                        text = "${skill.title} · $statusLabel",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = scheme.primary,
                                    )
                                }
                                if (result.toolSteps.isNotEmpty()) {
                                    Text(
                                        text = if (showToolLog) "收起过程 (${result.toolSteps.size}步)" else "查看过程 (${result.toolSteps.size}步)",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                                        color = scheme.primary,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                                role = Role.Button,
                                                onClick = { showToolLog = !showToolLog },
                                            )
                                            .padding(horizontal = 6.dp, vertical = 4.dp)
                                            .testTag("agent-toollog-toggle"),
                                    )
                                }
                            }

                            AnimatedVisibility(visible = showToolLog && result.toolSteps.isNotEmpty()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(mintSecondaryFill(dark))
                                        .padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    result.toolSteps.forEachIndexed { idx, step ->
                                        Text(
                                            text = "${idx + 1}. ${step.label}（${step.itemCount} 项 · ${step.status}）",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = scheme.onSurfaceVariant,
                                        )
                                    }
                                    if (result.filteredByPolicyCount > 0) {
                                        Text(
                                            text = "已按 AI 访问策略跳过 ${result.filteredByPolicyCount} 条限制笔记",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = scheme.primary,
                                        )
                                    }
                                }
                            }

                            Text(
                                text = result.headline,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = scheme.onSurface,
                                modifier = Modifier.testTag("agent-result-headline"),
                            )

                            if (result.observations.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    result.observations.forEach { bullet ->
                                        Text(
                                            text = "• $bullet",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = scheme.onSurface,
                                        )
                                    }
                                }
                            }

                            if (result.evidence.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = "引用依据（点按打开原笔记）：",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = scheme.onSurfaceVariant,
                                    )
                                    result.evidence.forEach { ev ->
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(mintSecondaryFill(dark))
                                                .clickable(
                                                    interactionSource = remember { MutableInteractionSource() },
                                                    indication = null,
                                                    role = Role.Button,
                                                    onClick = { onOpenNote(ev.noteId) },
                                                )
                                                .padding(10.dp)
                                                .testTag("agent-evidence-item"),
                                            verticalArrangement = Arrangement.spacedBy(3.dp),
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                            ) {
                                                Text(
                                                    text = ev.noteTitle,
                                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                                    color = scheme.primary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f),
                                                )
                                                Text(
                                                    text = ev.reason,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = scheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                )
                                            }
                                            if (ev.excerpt.isNotBlank() && ev.excerpt != ev.noteTitle) {
                                                Text(
                                                    text = ev.excerpt,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = scheme.onSurface,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            if (result.alternativeView.isNotBlank()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(mintSecondaryFill(dark))
                                        .padding(10.dp),
                                ) {
                                    Text(
                                        text = "另一种视角与边界：${result.alternativeView}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = scheme.onSurfaceVariant,
                                    )
                                }
                            }

                            if (result.nextQuestions.isNotEmpty()) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    result.nextQuestions.forEach { q ->
                                        MintChip(
                                            label = q,
                                            dark = dark,
                                            onClick = { if (!state.running) onRunQuestion(q) },
                                        )
                                    }
                                }
                            }

                            if (activeRun.status == "completed") {
                                MintGroupedDivider(dark = dark, startIndent = 0.dp)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    if (savedNoteAction != null) {
                                        MintPillButton(
                                            label = "已存为笔记 · 打开",
                                            primary = false,
                                            dark = dark,
                                            modifier = Modifier.weight(1f).testTag("agent-open-saved-note"),
                                            onClick = {
                                                savedNoteAction.targetNoteId?.let(onOpenNote)
                                            },
                                        )
                                    } else {
                                        MintPillButton(
                                            label = "确认存为新笔记",
                                            primary = true,
                                            dark = dark,
                                            modifier = Modifier.weight(1f).testTag("agent-save-note-button"),
                                            onClick = onSaveAsNote,
                                        )
                                    }
                                    if (result.suggestedTodos.isNotEmpty()) {
                                        if (savedTodosAction != null) {
                                            MintPillButton(
                                                label = "已生成待办",
                                                primary = false,
                                                dark = dark,
                                                modifier = Modifier.weight(1f),
                                                onClick = { savedTodosAction.targetNoteId?.let(onOpenNote) },
                                            )
                                        } else {
                                            MintPillButton(
                                                label = "提取 ${result.suggestedTodos.size} 项待办",
                                                primary = false,
                                                dark = dark,
                                                modifier = Modifier.weight(1f).testTag("agent-extract-todos-button"),
                                                onClick = onExtractTodos,
                                            )
                                        }
                                    }
                                }
                            } else if (notesCount == 0) {
                                MintPillButton(
                                    label = "先去记一条笔记",
                                    primary = true,
                                    dark = dark,
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = onQuickCapture,
                                )
                            }
                        }
                    }
                }

                // Safe Mode 写入提案确认卡列表
                val visibleProposals = state.writeProposals.filter { !it.dismissed }
                if (visibleProposals.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("agent-write-proposals"),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Safe Mode 写入提案（需你手动确认才会修改笔记）",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                        visibleProposals.forEach { proposal ->
                            WriteProposalCard(
                                proposal = proposal,
                                dark = dark,
                                onApply = { onApplyWriteProposal(proposal) },
                                onDismiss = { onDismissWriteProposal(proposal) },
                                onOpenNote = onOpenNote,
                            )
                        }
                    }
                }

                // 底部「继续追问当前结论…」快捷输入框
                if (result != null || state.chatTurns.isNotEmpty()) {
                    MintGroupedSection(dark = dark, containerColor = mintCardSurface(dark)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            BasicTextField(
                                value = state.followUpDraft,
                                onValueChange = onFollowUpDraftChange,
                                enabled = !state.running,
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
                                cursorBrush = SolidColor(scheme.primary),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(
                                    onSend = {
                                        if (state.followUpDraft.isNotBlank() && !state.running) {
                                            focusManager.clearFocus()
                                            onSendFollowUp(state.followUpDraft)
                                        }
                                    },
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(mintSecondaryFill(dark))
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                                    .testTag("agent-followup-input"),
                                decorationBox = { inner ->
                                    Box(modifier = Modifier.fillMaxWidth()) {
                                        if (state.followUpDraft.isEmpty()) {
                                            Text(
                                                text = "继续追问当前结论…",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = scheme.onSurfaceVariant.copy(alpha = 0.72f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        inner()
                                    }
                                },
                            )
                            MintPillButton(
                                label = "追问",
                                primary = true,
                                dark = dark,
                                enabled = state.followUpDraft.isNotBlank() && !state.running,
                                modifier = Modifier.testTag("agent-followup-send"),
                                onClick = {
                                    focusManager.clearFocus()
                                    onSendFollowUp(state.followUpDraft)
                                },
                            )
                        }
                    }
                }

                // Duplicate cleanup groups section
                if (activeRun?.skillId == AgentSkill.DuplicateCleanup.id && state.duplicateGroups.isNotEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "发现 ${state.duplicateGroups.size} 组高度相似碎片",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                        state.duplicateGroups.forEach { group ->
                            val primaryTitle = LocalRetrievalEngine.displayNoteTitle(group.primaryNote)
                            MintGroupedSection(
                                dark = dark,
                                containerColor = mintCardSurface(dark),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
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
                                                text = "${group.similarityPercent}% · ${group.reason}",
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                                color = scheme.primary,
                                            )
                                        }
                                        Text(
                                            text = "共 ${1 + group.duplicateNotes.size} 条",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = scheme.onSurfaceVariant,
                                        )
                                    }

                                    Text(
                                        text = "主笔记：$primaryTitle",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = scheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                                role = Role.Button,
                                                onClick = { onOpenNote(group.primaryNote.id) },
                                            )
                                            .padding(vertical = 2.dp),
                                    )

                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        group.duplicateNotes.forEach { dup ->
                                            val dupTitle = LocalRetrievalEngine.displayNoteTitle(dup)
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(mintSecondaryFill(dark))
                                                    .clickable(
                                                        interactionSource = remember { MutableInteractionSource() },
                                                        indication = null,
                                                        role = Role.Button,
                                                        onClick = { onOpenNote(dup.id) },
                                                    )
                                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                            ) {
                                                Text(
                                                    text = "相似：$dupTitle",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = scheme.onSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                    }

                                    if (group.previewText.isNotBlank()) {
                                        Text(
                                            text = group.previewText,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = scheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }

                                    MintPillButton(
                                        label = "预览并安全合并",
                                        primary = true,
                                        dark = dark,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("agent-dup-merge-trigger-${group.groupId}"),
                                        onClick = { previewDuplicateGroup = group },
                                    )
                                }
                            }
                        }
                    }
                }

                // Persisted history list (across restarts)
                if (state.recentRuns.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "最近梳理记录（可回看）",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                        MintGroupedSection(dark = dark, containerColor = mintCardSurface(dark)) {
                            state.recentRuns.take(5).forEachIndexed { idx, past ->
                                if (idx > 0) MintGroupedDivider(dark = dark, startIndent = 16.dp)
                                val pastSkill = AgentSkill.fromId(past.skillId)
                                val pastStatus = when (past.status) {
                                    "completed" -> "已完成"
                                    "insufficient_evidence" -> "证据不足"
                                    "cancelled" -> "已停止"
                                    "running" -> "中断待查"
                                    else -> past.status
                                }
                                MintGroupedRow(
                                    label = past.prompt.ifBlank { pastSkill.title },
                                    subtitle = "${pastSkill.title} · $pastStatus · ${past.updatedAt.take(10)}",
                                    showChevron = true,
                                    dark = dark,
                                    onClick = { onSelectPastRun(past) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 新建/编辑自定义 Agent 弹窗
    if (showCreatePresetSheet) {
        CustomPresetEditorSheet(
            initial = editingPreset,
            backdrop = backdrop,
            dark = dark,
            onDismiss = { showCreatePresetSheet = false },
            onSave = { name, desc, prompt, days ->
                showCreatePresetSheet = false
                onSaveCustomPreset(editingPreset?.id, name, desc, prompt, days)
            },
        )
    }

    // 编辑记忆条目弹窗
    editingMemoryItem?.let { item ->
        MemoryItemEditorSheet(
            item = item,
            backdrop = backdrop,
            dark = dark,
            onDismiss = { editingMemoryItem = null },
            onSave = { category, content ->
                editingMemoryItem = null
                onEditMemoryItem(item.id, category, content)
            },
        )
    }

    previewDuplicateGroup?.let { group ->
        var moveToTrash by remember(group.groupId) { mutableStateOf(false) }
        val mergedContent by produceState<MergedDuplicateContent?>(
            initialValue = null,
            group.groupId,
            moveToTrash,
        ) {
            value = withContext(Dispatchers.Default) {
                LocalTextIntelligence.buildMergedContent(group.primaryNote, group.duplicateNotes, moveToTrash)
            }
        }
        val mergedLines = mergedContent?.previewLines.orEmpty()
        val preservedParts = buildList {
            if ((mergedContent?.preservedCheckboxCount ?: 0) > 0) add("${mergedContent?.preservedCheckboxCount} 项待办")
            if ((mergedContent?.preservedAttachmentCount ?: 0) > 0) add("${mergedContent?.preservedAttachmentCount} 个附件")
            if ((mergedContent?.preservedReferenceCount ?: 0) > 0) add("${mergedContent?.preservedReferenceCount} 条引用")
        }
        MintBottomSheet(
            onDismissRequest = { previewDuplicateGroup = null },
            dark = dark,
            backdrop = backdrop,
            title = "预览并安全合并",
            subtitle = "共 ${1 + group.duplicateNotes.size} 篇 · ${group.similarityPercent}% · ${group.reason}",
            leadingLabel = "取消",
            modifier = Modifier.testTag("agent-dup-merge-sheet"),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MintGroupedSection(dark = dark, containerColor = mintSecondaryFill(dark)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "合并后内容预览",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = scheme.primary,
                        )
                        if (preservedParts.isNotEmpty()) {
                            Text(
                                text = "已保留原格式：${preservedParts.joinToString(" · ")}" +
                                    if (mergedContent?.hadRemindersInSources == true) "（原定时闹钟仍留在原笔记）" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.primary,
                            )
                        }
                        if (mergedLines.isEmpty()) {
                            Text(
                                text = group.previewText.ifBlank { "（无正文段落）" },
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                            )
                        } else {
                            mergedLines.forEach { para ->
                                Text(
                                    text = para,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = scheme.onSurface,
                                )
                            }
                        }
                    }
                }

                MintGroupedSection(dark = dark, containerColor = mintCardSurface(dark)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Switch,
                                onClick = { moveToTrash = !moveToTrash },
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = "合并后将原碎片移入 30 天回收站",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = scheme.onSurface,
                            )
                            Text(
                                text = if (moveToTrash) "原笔记将移入回收站，30 天内可随时还原" else "默认保留原笔记，并在新笔记中附带引用",
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        MintSwitch(
                            checked = moveToTrash,
                            onCheckedChange = { moveToTrash = it },
                            dark = dark,
                            modifier = Modifier.testTag("agent-dup-merge-trash-switch"),
                        )
                    }
                }

                MintPillButton(
                    label = "确认生成合并笔记",
                    primary = true,
                    dark = dark,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("agent-dup-merge-confirm"),
                    onClick = {
                        previewDuplicateGroup = null
                        onMergeDuplicateGroup(group, moveToTrash)
                    },
                )
            }
        }
    }
}

@Composable
private fun LiveStepsTracePanel(
    steps: List<AgentProgressStepUi>,
    maxSteps: Int,
    running: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    dark: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val latest = steps.lastOrNull()
    val safeMax = maxSteps.coerceAtLeast(1)
    val progress = ((latest?.stepIndex ?: 0).toFloat() / safeMax.toFloat()).coerceIn(0.08f, 1f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(mintSecondaryFill(dark))
            .padding(10.dp)
            .testTag("agent-live-steps"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (latest != null) {
                    "步骤 ${latest.stepIndex}/$safeMax · ${latest.toolName}"
                } else {
                    "准备工具检索…"
                },
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = scheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (expanded) "收起追踪 (${steps.size})" else "展开追踪 (${steps.size})",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.primary,
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onToggleExpanded,
                    )
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
        if (running) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(50)),
                color = scheme.primary,
                trackColor = mintDividerColor(dark),
            )
        }
        AnimatedVisibility(visible = expanded && steps.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                steps.forEach { step ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${step.stepIndex}. ${step.toolName} · ${step.summary}",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (step.elapsedMs > 0L) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "${step.elapsedMs}ms",
                                style = MaterialTheme.typography.labelSmall,
                                color = mintTertiaryTextColor(dark),
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
private fun ChatTurnCard(
    turn: AgentChatTurnUi,
    dark: Boolean,
    onOpenNote: (String) -> Unit,
    onApplyProposal: (AgentWriteProposalUi) -> Unit,
    onDismissProposal: (AgentWriteProposalUi) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val isUser = turn.role == "user"
    val aiAccent = mintAiAccent(dark)
    var showSteps by rememberSaveable(turn.id) { mutableStateOf(false) }

    MintGroupedSection(
        dark = dark,
        containerColor = if (isUser) scheme.primaryContainer.copy(alpha = if (dark) 0.45f else 0.72f) else mintCardSurface(dark),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (isUser) "你的追问" else if (turn.usedRemoteAi) {
                        "深度研究 · ${turn.modelUsed ?: "外部模型"}"
                    } else {
                        "本机整理"
                    },
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = if (isUser) scheme.primary else if (turn.usedRemoteAi) aiAccent.fg else scheme.onSurfaceVariant,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (turn.steps.isNotEmpty()) {
                        Text(
                            text = if (showSteps) "收起步骤" else "过程 (${turn.steps.size}步)",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.primary,
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Button,
                                onClick = { showSteps = !showSteps },
                            ),
                        )
                    }
                    if (turn.createdAtLabel.isNotBlank()) {
                        Text(
                            text = turn.createdAtLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = mintTertiaryTextColor(dark),
                        )
                    }
                }
            }

            AnimatedVisibility(visible = showSteps && turn.steps.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(mintSecondaryFill(dark))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    turn.steps.forEach { step ->
                        Text(
                            text = "步骤 ${step.stepIndex} · ${step.toolName}：${step.summary}",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Text(
                text = turn.text,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
            )

            if (turn.citations.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    turn.citations.forEach { citation ->
                        MintChip(
                            label = "↗ ${citation.noteTitle}",
                            dark = dark,
                            leadingIcon = MintIcons.Records,
                            onClick = { onOpenNote(citation.noteId) },
                        )
                    }
                }
            }

            turn.proposals.filter { !it.dismissed }.forEach { proposal ->
                WriteProposalCard(
                    proposal = proposal,
                    dark = dark,
                    onApply = { onApplyProposal(proposal) },
                    onDismiss = { onDismissProposal(proposal) },
                    onOpenNote = onOpenNote,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WriteProposalCard(
    proposal: AgentWriteProposalUi,
    dark: Boolean,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
    onOpenNote: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val todoAccent = mintTodoAccent(dark)
    val badgeTitle = when (proposal) {
        is AgentWriteProposalUi.PatchNote -> if (proposal.appendMode) "提案 · 追加修改原笔记" else "提案 · 修订原笔记"
        is AgentWriteProposalUi.NewNote -> "提案 · 新建笔记"
        is AgentWriteProposalUi.ExtractTodos -> "提案 · 提取待办"
    }

    MintGroupedSection(
        dark = dark,
        containerColor = mintCardSurface(dark),
        modifier = Modifier.testTag("agent-proposal-${proposal.proposalId}"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
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
                        .background(todoAccent.bg)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = badgeTitle,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = todoAccent.fg,
                    )
                }
                if (proposal.applied) {
                    Text(
                        text = "已安全应用",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = scheme.primary,
                    )
                }
            }

            if (proposal.reason.isNotBlank()) {
                Text(
                    text = proposal.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }

            when (proposal) {
                is AgentWriteProposalUi.PatchNote -> {
                    Text(
                        text = "目标笔记：${proposal.targetTitle}",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = scheme.primary,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClick = { onOpenNote(proposal.targetNoteId) },
                        ),
                    )
                    if (proposal.beforeExcerpt.isNotBlank()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(mintDestructiveColor(dark).copy(alpha = 0.09f))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = "− 原文摘录",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = mintDestructiveColor(dark),
                            )
                            Text(
                                text = proposal.beforeExcerpt,
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurface,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(scheme.primaryContainer.copy(alpha = if (dark) 0.45f else 0.72f))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = if (proposal.appendMode) "+ 拟追加内容" else "+ 拟修改后内容",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = scheme.primary,
                        )
                        Text(
                            text = proposal.afterContent,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurface,
                        )
                    }
                }

                is AgentWriteProposalUi.NewNote -> {
                    Text(
                        text = proposal.title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = scheme.onSurface,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(mintSecondaryFill(dark))
                            .padding(10.dp),
                    ) {
                        Text(
                            text = proposal.markdownBody,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurface,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (proposal.suggestedTags.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            proposal.suggestedTags.forEach { tag ->
                                MintChip(label = "#$tag", dark = dark, onClick = {})
                            }
                        }
                    }
                }

                is AgentWriteProposalUi.ExtractTodos -> {
                    Text(
                        text = "目标：${proposal.targetTitle}",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = scheme.onSurface,
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(mintSecondaryFill(dark))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        proposal.items.forEach { todo ->
                            Text(
                                text = "☐ $todo",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurface,
                            )
                        }
                    }
                }
            }

            if (!proposal.applied) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MintPillButton(
                        label = "忽略",
                        primary = false,
                        dark = dark,
                        modifier = Modifier.weight(1f),
                        onClick = onDismiss,
                    )
                    MintPillButton(
                        label = when (proposal) {
                            is AgentWriteProposalUi.PatchNote -> "确认应用"
                            is AgentWriteProposalUi.NewNote -> "确认写入"
                            is AgentWriteProposalUi.ExtractTodos -> "确认创建待办"
                        },
                        primary = true,
                        icon = MintIcons.Check,
                        dark = dark,
                        modifier = Modifier.weight(1.3f),
                        onClick = onApply,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InsightsTabContent(
    state: AgentUiState,
    dark: Boolean,
    onRunPreset: (AgentPresetUi) -> Unit,
    onOpenCreatePreset: () -> Unit,
    onEditPreset: (AgentPresetUi) -> Unit,
    onDeletePreset: (String) -> Unit,
    onOpenNote: (String) -> Unit,
    onContinueChat: (AgentInsightResultUi) -> Unit,
    onSaveInsightAsNote: (AgentInsightResultUi) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val presets = state.insightPresets.ifEmpty { defaultBuiltInInsightPresets }
    val builtInPresets = presets.filter { it.builtIn }
    val customPresets = presets.filter { !it.builtIn }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("agent-insights-tab"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "flomo 多视角洞察",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            MintChip(
                label = "+ 新建自定义 Agent",
                style = MintChipStyle.Selected,
                dark = dark,
                modifier = Modifier.testTag("agent-new-preset-button"),
                onClick = onOpenCreatePreset,
            )
        }

        // 4 个内置视角卡（2 列紧凑排列）
        builtInPresets.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                pair.forEach { preset ->
                    val selected = state.selectedPresetId == preset.id
                    val icon = when (preset.iconKey) {
                        "explore" -> MintIcons.Explore
                        "history" -> MintIcons.History
                        "chart" -> MintIcons.Chart
                        else -> MintIcons.Sparkles
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (selected) scheme.primaryContainer.copy(alpha = if (dark) 0.55f else 0.85f)
                                else mintCardSurface(dark),
                            )
                            .border(
                                0.7.dp,
                                if (selected) scheme.primary else mintDividerColor(dark),
                                RoundedCornerShape(16.dp),
                            )
                            .clickable(
                                enabled = !state.running,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Button,
                                onClick = { onRunPreset(preset) },
                            )
                            .padding(14.dp)
                            .testTag("agent-insight-preset-${preset.id}"),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = scheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = preset.name,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = scheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = preset.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = preset.defaultRangeDays?.let { "默认近 $it 天" } ?: "全部范围",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.primary,
                        )
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        // 自定义 Agent 列表
        if (customPresets.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "我的自定义 Agent",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                MintGroupedSection(dark = dark, containerColor = mintCardSurface(dark)) {
                    customPresets.forEachIndexed { idx, preset ->
                        if (idx > 0) MintGroupedDivider(dark = dark, startIndent = 16.dp)
                        MintGroupedRow(
                            label = preset.name,
                            subtitle = preset.description.ifBlank { preset.promptTemplate },
                            leadingIcon = MintIcons.Agent,
                            selected = state.selectedPresetId == preset.id,
                            dark = dark,
                            onClick = if (!state.running) {
                                { onRunPreset(preset) }
                            } else null,
                            trailingContent = {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = "编辑",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                        color = scheme.primary,
                                        modifier = Modifier.clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            role = Role.Button,
                                            onClick = { onEditPreset(preset) },
                                        ),
                                    )
                                    Text(
                                        text = "删除",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                        color = mintDestructiveColor(dark),
                                        modifier = Modifier.clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            role = Role.Button,
                                            onClick = { onDeletePreset(preset.id) },
                                        ),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }

        if (state.running) {
            MintGroupedSection(dark = dark, containerColor = mintSecondaryFill(dark)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MintActivityIndicator(color = scheme.primary)
                    Text(
                        text = "正在生成多视角洞察…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurface,
                    )
                }
            }
        }

        // 洞察结果展示区
        state.activeInsightResult?.let { insight ->
            MintGroupedSection(
                dark = dark,
                containerColor = mintCardSurface(dark),
                modifier = Modifier.testTag("agent-insight-result"),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
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
                                .background(mintAiAccent(dark).bg)
                                .padding(horizontal = 9.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = insight.presetName,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = mintAiAccent(dark).fg,
                            )
                        }
                        Text(
                            text = if (insight.usedRemoteAi) insight.modelUsed ?: "外部模型" else "本机规则",
                            style = MaterialTheme.typography.labelSmall,
                            color = mintTertiaryTextColor(dark),
                        )
                    }

                    Text(
                        text = insight.summaryMarkdown,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurface,
                    )

                    if (insight.highlights.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            insight.highlights.forEach { point ->
                                Text(
                                    text = "• $point",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurface,
                                )
                            }
                        }
                    }

                    if (insight.citations.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "引用原文（点击跳转）：",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = scheme.onSurfaceVariant,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                insight.citations.forEach { citation ->
                                    MintChip(
                                        label = "↗ ${citation.noteTitle}",
                                        dark = dark,
                                        leadingIcon = MintIcons.Records,
                                        onClick = { onOpenNote(citation.noteId) },
                                    )
                                }
                            }
                        }
                    }

                    MintGroupedDivider(dark = dark, startIndent = 0.dp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MintPillButton(
                            label = "带着原文继续聊",
                            primary = true,
                            icon = MintIcons.Agent,
                            dark = dark,
                            modifier = Modifier
                                .weight(1.2f)
                                .testTag("agent-insight-continue-chat"),
                            onClick = { onContinueChat(insight) },
                        )
                        MintPillButton(
                            label = "存为新笔记",
                            primary = false,
                            dark = dark,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("agent-insight-save-note"),
                            onClick = { onSaveInsightAsNote(insight) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryTabContent(
    state: AgentUiState,
    dark: Boolean,
    onToggleAutoReference: (Boolean) -> Unit,
    onExtractUpdates: () -> Unit,
    onCommitCandidates: (List<AgentMemoryCandidateUi>) -> Unit,
    onDismissCandidates: () -> Unit,
    onToggleMemoryItem: (String, Boolean) -> Unit,
    onEditMemoryItem: (AgentMemoryItemUi) -> Unit,
    onDeleteMemoryItem: (String) -> Unit,
    onOpenNote: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val linkAccent = mintLinkAccent(dark)
    val reviewAccent = mintReviewAccent(dark)

    // 冲突项默认不勾选，新增项默认勾选
    var selectedCandidateIds by remember(state.memoryCandidates) {
        mutableStateOf(
            state.memoryCandidates
                .filter { !it.isConflictWithUserEdited }
                .map { it.candidateId }
                .toSet(),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("agent-memory-tab"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 全局开关 + 手动提炼按钮
        MintGroupedSection(dark = dark, containerColor = mintCardSurface(dark)) {
            Column(modifier = Modifier.fillMaxWidth()) {
                MintGroupedRow(
                    label = "分析时自动参考长期记忆档案",
                    subtitle = "开启后 Agent 回答会温和参考已启用的长期记忆偏好与背景",
                    leadingIcon = MintIcons.Library,
                    dark = dark,
                    trailingContent = {
                        MintSwitch(
                            checked = state.memoryAutoReference,
                            onCheckedChange = onToggleAutoReference,
                            dark = dark,
                            modifier = Modifier.testTag("agent-memory-auto-switch"),
                        )
                    },
                    onClick = { onToggleAutoReference(!state.memoryAutoReference) },
                )
                MintGroupedDivider(dark = dark, startIndent = 16.dp)
                Box(modifier = Modifier.padding(14.dp)) {
                    MintPillButton(
                        label = if (state.memoryExtracting) "正在提炼近期记忆…" else "从近期笔记提炼更新",
                        primary = true,
                        icon = MintIcons.Sparkles,
                        enabled = !state.memoryExtracting && !state.running,
                        dark = dark,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("agent-memory-extract-button"),
                        onClick = onExtractUpdates,
                    )
                }
            }
        }

        // 提炼待确认列表（新增 vs 与手动修改冲突）
        if (state.memoryCandidates.isNotEmpty()) {
            MintGroupedSection(
                dark = dark,
                containerColor = mintCardSurface(dark),
                modifier = Modifier.testTag("agent-memory-candidates"),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "提炼出 ${state.memoryCandidates.size} 条记忆更新建议（请勾选确认写入）",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = scheme.onSurface,
                    )
                    state.memoryCandidates.forEach { candidate ->
                        val checked = candidate.candidateId in selectedCandidateIds
                        val badgeBg = if (candidate.isConflictWithUserEdited) reviewAccent.bg else linkAccent.bg
                        val badgeFg = if (candidate.isConflictWithUserEdited) reviewAccent.fg else linkAccent.fg
                        val badgeText = if (candidate.isConflictWithUserEdited) {
                            "与你手动改过的记忆存在冲突"
                        } else {
                            "新增"
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(mintSecondaryFill(dark))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    role = Role.Checkbox,
                                    onClick = {
                                        selectedCandidateIds = if (checked) {
                                            selectedCandidateIds - candidate.candidateId
                                        } else {
                                            selectedCandidateIds + candidate.candidateId
                                        }
                                    },
                                )
                                .padding(10.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            MintCheckbox(
                                checked = checked,
                                onCheckedChange = { next ->
                                    selectedCandidateIds = if (next) {
                                        selectedCandidateIds + candidate.candidateId
                                    } else {
                                        selectedCandidateIds - candidate.candidateId
                                    }
                                },
                                dark = dark,
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(badgeBg)
                                            .padding(horizontal = 7.dp, vertical = 2.dp),
                                    ) {
                                        Text(
                                            text = badgeText,
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                            color = badgeFg,
                                        )
                                    }
                                    Text(
                                        text = candidate.category,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = scheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    text = candidate.proposedContent,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurface,
                                )
                                if (candidate.isConflictWithUserEdited && !candidate.existingContent.isNullOrBlank()) {
                                    Text(
                                        text = "当前手动版：${candidate.existingContent}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = reviewAccent.fg,
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MintPillButton(
                            label = "暂不写入",
                            primary = false,
                            dark = dark,
                            modifier = Modifier.weight(1f),
                            onClick = onDismissCandidates,
                        )
                        MintPillButton(
                            label = "确认写入档案 (${selectedCandidateIds.size})",
                            primary = true,
                            icon = MintIcons.Check,
                            enabled = selectedCandidateIds.isNotEmpty(),
                            dark = dark,
                            modifier = Modifier
                                .weight(1.4f)
                                .testTag("agent-memory-commit-button"),
                            onClick = {
                                val chosen = state.memoryCandidates.filter { it.candidateId in selectedCandidateIds }
                                onCommitCandidates(chosen)
                            },
                        )
                    }
                }
            }
        }

        // 分类分组记忆档案卡片
        if (state.memoryItems.isEmpty()) {
            MintGroupedSection(dark = dark, containerColor = mintCardSurface(dark)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "尚未建立长期记忆档案",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = scheme.onSurface,
                    )
                    Text(
                        text = "点击上方「从近期笔记提炼更新」，你确认后才会把重要背景、关注主题与工作偏好写入这里。",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            val grouped = state.memoryItems.groupBy { it.category.ifBlank { "常规背景" } }
            grouped.forEach { (category, items) ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "$category（${items.size}）",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    MintGroupedSection(dark = dark, containerColor = mintCardSurface(dark)) {
                        items.forEachIndexed { idx, item ->
                            if (idx > 0) MintGroupedDivider(dark = dark, startIndent = 16.dp)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = item.content,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (item.enabled) scheme.onSurface else scheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    MintSwitch(
                                        checked = item.enabled,
                                        onCheckedChange = { next -> onToggleMemoryItem(item.id, next) },
                                        dark = dark,
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    if (item.userEdited) {
                                        Text(
                                            text = "已手动编辑",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = scheme.primary,
                                        )
                                    }
                                    if (!item.sourceNoteId.isNullOrBlank()) {
                                        Text(
                                            text = "来源：${item.sourceNoteTitle ?: "查看原笔记"}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = linkAccent.fg,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier
                                                .weight(1f, fill = false)
                                                .clickable(
                                                    interactionSource = remember { MutableInteractionSource() },
                                                    indication = null,
                                                    role = Role.Button,
                                                    onClick = { onOpenNote(item.sourceNoteId) },
                                                ),
                                        )
                                    }
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        text = "编辑",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                        color = scheme.primary,
                                        modifier = Modifier.clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            role = Role.Button,
                                            onClick = { onEditMemoryItem(item) },
                                        ),
                                    )
                                    Text(
                                        text = "删除",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                        color = mintDestructiveColor(dark),
                                        modifier = Modifier.clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            role = Role.Button,
                                            onClick = { onDeleteMemoryItem(item.id) },
                                        ),
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

@Composable
private fun CustomPresetEditorSheet(
    initial: AgentPresetUi?,
    backdrop: GlassBackdrop,
    dark: Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, description: String, promptTemplate: String, defaultRangeDays: Int?) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var description by remember(initial) { mutableStateOf(initial?.description.orEmpty()) }
    var prompt by remember(initial) { mutableStateOf(initial?.promptTemplate.orEmpty()) }
    var days by remember(initial) { mutableStateOf(initial?.defaultRangeDays) }

    MintBottomSheet(
        onDismissRequest = onDismiss,
        dark = dark,
        backdrop = backdrop,
        title = if (initial == null) "新建自定义 Agent" else "编辑自定义 Agent",
        subtitle = "打造你的专属思考视角",
        leadingLabel = "取消",
        trailingLabel = "保存",
        trailingEnabled = name.isNotBlank() && prompt.isNotBlank(),
        onTrailingClick = { onSave(name.trim(), description.trim(), prompt.trim(), days) },
        modifier = Modifier.testTag("agent-custom-preset-sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MintTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = "名称（如：产品复盘教练）",
                dark = dark,
                tag = "agent-preset-name-input",
            )
            MintTextField(
                value = description,
                onValueChange = { description = it },
                placeholder = "一句话说明（选填）",
                dark = dark,
                tag = "agent-preset-desc-input",
            )
            MintTextField(
                value = prompt,
                onValueChange = { prompt = it },
                placeholder = "分析指令 Prompt（告诉 AI 从什么角度审视你的笔记）",
                singleLine = false,
                dark = dark,
                tag = "agent-preset-prompt-input",
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "默认回顾天数",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(null to "全部", 7 to "近 7 天", 30 to "近 30 天").forEach { (d, label) ->
                        MintChip(
                            label = label,
                            selected = days == d,
                            dark = dark,
                            onClick = { days = d },
                        )
                    }
                }
            }
            MintPillButton(
                label = "保存 Agent",
                primary = true,
                enabled = name.isNotBlank() && prompt.isNotBlank(),
                dark = dark,
                modifier = Modifier.fillMaxWidth(),
                onClick = { onSave(name.trim(), description.trim(), prompt.trim(), days) },
            )
        }
    }
}

@Composable
private fun MemoryItemEditorSheet(
    item: AgentMemoryItemUi,
    backdrop: GlassBackdrop,
    dark: Boolean,
    onDismiss: () -> Unit,
    onSave: (category: String, content: String) -> Unit,
) {
    var category by remember(item) { mutableStateOf(item.category) }
    var content by remember(item) { mutableStateOf(item.content) }

    MintBottomSheet(
        onDismissRequest = onDismiss,
        dark = dark,
        backdrop = backdrop,
        title = "编辑记忆条目",
        subtitle = "手动修改的记忆不会被自动提炼静默覆盖",
        leadingLabel = "取消",
        trailingLabel = "保存",
        trailingEnabled = content.isNotBlank(),
        onTrailingClick = { onSave(category.trim().ifBlank { "常规背景" }, content.trim()) },
        modifier = Modifier.testTag("agent-memory-editor-sheet"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MintTextField(
                value = category,
                onValueChange = { category = it },
                placeholder = "分类（如：工作偏好、长期目标）",
                dark = dark,
            )
            MintTextField(
                value = content,
                onValueChange = { content = it },
                placeholder = "记忆内容",
                singleLine = false,
                dark = dark,
            )
            MintPillButton(
                label = "保存修改",
                primary = true,
                enabled = content.isNotBlank(),
                dark = dark,
                modifier = Modifier.fillMaxWidth(),
                onClick = { onSave(category.trim().ifBlank { "常规背景" }, content.trim()) },
            )
        }
    }
}
