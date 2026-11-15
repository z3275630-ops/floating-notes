package io.github.z3275630ops.floatingnotes.feature.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import io.github.z3275630ops.floatingnotes.data.agent.LocalAgentEngine
import io.github.z3275630ops.floatingnotes.data.local.NoteScope
import io.github.z3275630ops.floatingnotes.data.local.TagEntity
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintChip
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGroupedDivider
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGroupedRow
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGroupedSection
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintIcons
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintPillButton
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintCardSurface
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintSecondaryFill

internal data class AgentUiState(
    val running: Boolean = false,
    val promptDraft: String = "",
    val timeRange: AgentTimeRange = AgentTimeRange.All,
    val selectedTagId: String? = null,
    val activeRun: AgentRunEntity? = null,
    val activeResult: AgentRunResult? = null,
    val activeActions: List<AgentActionEntity> = emptyList(),
    val recentRuns: List<AgentRunEntity> = emptyList(),
    val statusMessage: String? = null,
    val errorMessage: String? = null,
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
) {
    val scheme = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    var showToolLog by rememberSaveable(state.activeRun?.id) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("agent-workspace"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Honest local engine & privacy boundary header card
        MintGroupedSection(dark = dark, containerColor = mintCardSurface(dark)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(mintSecondaryFill(dark)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = MintIcons.Agent,
                            contentDescription = null,
                            tint = scheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "本机洞察助手",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = scheme.onSurface,
                        )
                        Text(
                            text = "${LocalAgentEngine.ENGINE_NOTICE} · 共 $notesCount 条可浏览笔记",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.primary,
                        )
                    }
                }
                Text(
                    text = "所有检索、归纳与待办梳理都在手机本地完成，默认遵守每条笔记的 AI 策略，写入新笔记前都会先由你确认。",
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

        // Question & scope composer card
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
                            Text(
                                text = "正在检索并整理本机线索…",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
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
                            label = "开始本机分析",
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

        // Active run output card
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
