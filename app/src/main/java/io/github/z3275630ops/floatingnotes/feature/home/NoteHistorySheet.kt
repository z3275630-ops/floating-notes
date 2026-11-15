package io.github.z3275630ops.floatingnotes.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.z3275630ops.floatingnotes.data.backup.DiffLineKind
import io.github.z3275630ops.floatingnotes.data.backup.HistoryUiState
import io.github.z3275630ops.floatingnotes.data.backup.NoteRevisionSummary
import io.github.z3275630ops.floatingnotes.data.backup.VersionDiffPreview
import io.github.z3275630ops.floatingnotes.ui.designsystem.GlassBackdrop
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintActivityIndicator
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintBottomSheet
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGroupedDivider
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGroupedRow
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGroupedSection
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintIcons
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintPillButton
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintDestructiveColor
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintSecondaryFill
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun NoteHistorySheet(
    state: HistoryUiState,
    backdrop: GlassBackdrop,
    dark: Boolean,
    onDismiss: () -> Unit,
    onSelectRevision: (Long) -> Unit,
    onRestoreRevision: (noteId: String, targetRevision: Long) -> Unit,
) {
    val noteId = state.noteId ?: return
    val scheme = MaterialTheme.colorScheme
    val selectedSummary = state.revisions.firstOrNull { it.revision == state.selectedRevision }
    val canRestore = selectedSummary != null &&
        !selectedSummary.isCurrent &&
        state.diffPreview != null &&
        !state.restoring &&
        !state.loading

    MintBottomSheet(
        onDismissRequest = onDismiss,
        dark = dark,
        backdrop = backdrop,
        title = "版本历史",
        subtitle = "当前 v${state.currentRevision} · 创建新修订",
        leadingLabel = "关闭",
        trailingLabel = if (state.restoring) "恢复中…" else if (canRestore) "恢复此版" else null,
        onTrailingClick = if (canRestore) {
            { onRestoreRevision(noteId, selectedSummary!!.revision) }
        } else null,
        trailingEnabled = canRestore,
        trailingTag = "history-header-restore",
        modifier = Modifier.testTag("note-history-sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.statusMessage?.let { status ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(scheme.primary.copy(alpha = if (dark) 0.18f else 0.10f))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = status,
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
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = mintDestructiveColor(dark),
                    )
                }
            }

            if (state.loading && state.revisions.isEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MintActivityIndicator(color = scheme.primary)
                    Text("正在读取历史版本…", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                }
            } else if (state.revisions.isEmpty()) {
                MintGroupedSection(dark = dark) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "暂无历史版本",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = scheme.onSurface,
                        )
                        Text(
                            text = "每次保存或从历史恢复后，都会在此保留一份带附件引用的快照。",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Text(
                    text = "版本时间轴（最近 ${state.revisions.size} 版）",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                MintGroupedSection(dark = dark) {
                    state.revisions.forEachIndexed { index, summary ->
                        if (index > 0) MintGroupedDivider(dark = dark, startIndent = 48.dp)
                        RevisionTimelineRow(
                            summary = summary,
                            selected = summary.revision == state.selectedRevision,
                            dark = dark,
                            onClick = { onSelectRevision(summary.revision) },
                        )
                    }
                }

                state.diffPreview?.let { diff ->
                    DiffPreviewSection(
                        diff = diff,
                        dark = dark,
                        isCurrent = selectedSummary?.isCurrent == true,
                        restoring = state.restoring,
                        onRestore = { onRestoreRevision(noteId, diff.targetRevision) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RevisionTimelineRow(
    summary: NoteRevisionSummary,
    selected: Boolean,
    dark: Boolean,
    onClick: () -> Unit,
) {
    val originText = historyOriginLabel(summary.origin)
    val timeText = formatHistoryTime(summary.createdAt)
    val metaLine = buildString {
        append(timeText)
        append(" · ")
        append(originText)
        if (summary.attachmentCount > 0) {
            append(" · ${summary.attachmentCount} 个附件")
        }
        if (summary.snippet.isNotBlank()) {
            append("\n")
            append(summary.snippet)
        }
    }
    MintGroupedRow(
        label = summary.title?.takeIf(String::isNotBlank) ?: "版本 v${summary.revision}",
        subtitle = metaLine,
        leadingIcon = MintIcons.History,
        trailingText = if (summary.isCurrent) "当前 · v${summary.revision}" else "v${summary.revision}",
        selected = selected,
        dark = dark,
        modifier = Modifier.testTag("history-revision-${summary.revision}"),
        onClick = onClick,
    )
}

@Composable
private fun DiffPreviewSection(
    diff: VersionDiffPreview,
    dark: Boolean,
    isCurrent: Boolean,
    restoring: Boolean,
    onRestore: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = if (isCurrent) {
                "当前版本（v${diff.targetRevision}）内容快照"
            } else {
                "与当前版本差异对比（v${diff.targetRevision} → v${diff.currentRevision}）"
            },
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        if (diff.missingAttachments.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(mintDestructiveColor(dark).copy(alpha = 0.11f))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "${diff.missingAttachments.size} 个历史附件已不在本机保留期，恢复将保留文字与说明，无法找回缺失原文件：",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        color = mintDestructiveColor(dark),
                    )
                    diff.missingAttachments.forEach { missing ->
                        Text(
                            text = "• ${missing.label}",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        } else if (diff.totalAttachmentCount > 0) {
            Text(
                text = "所含 ${diff.totalAttachmentCount} 个附件均仍在本机保留期内，可完整恢复。",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.primary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        MintGroupedSection(
            dark = dark,
            modifier = Modifier.testTag("history-diff-preview"),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (diff.titleChanged) {
                    Text(
                        text = "- 标题：${diff.currentTitle ?: "无标题"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = mintDestructiveColor(dark),
                    )
                    Text(
                        text = "+ 标题：${diff.targetTitle ?: "无标题"}",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = scheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                if (diff.lines.isEmpty()) {
                    Text(
                        text = "正文内容与当前版本一致。",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                } else {
                    diff.lines.forEach { line ->
                        val (prefix, bg, fg) = when (line.kind) {
                            DiffLineKind.ADDED -> Triple(
                                "+",
                                scheme.primary.copy(alpha = if (dark) 0.16f else 0.10f),
                                scheme.primary,
                            )
                            DiffLineKind.REMOVED -> Triple(
                                "-",
                                mintDestructiveColor(dark).copy(alpha = 0.11f),
                                mintDestructiveColor(dark),
                            )
                            DiffLineKind.UNCHANGED -> Triple(
                                " ",
                                Color.Transparent,
                                scheme.onSurface.copy(alpha = 0.85f),
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(bg)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = prefix,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                color = fg,
                            )
                            Text(
                                text = line.text.ifEmpty { "（空行）" },
                                style = MaterialTheme.typography.bodySmall,
                                color = fg,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }

        if (!isCurrent) {
            MintPillButton(
                label = if (restoring) "正在恢复…" else "恢复为新版本 (v${diff.currentRevision + 1})",
                icon = MintIcons.Restore,
                primary = true,
                enabled = !restoring,
                dark = dark,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("history-restore-button"),
                onClick = onRestore,
            )
        }
    }
}

private fun historyOriginLabel(origin: String): String = when (origin) {
    "restore" -> "历史恢复"
    "share" -> "跨应用收集"
    "ai" -> "AI 整理前"
    "import" -> "导入"
    else -> "本地编辑"
}

private fun formatHistoryTime(iso: String): String = runCatching {
    val zdt = Instant.parse(iso).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    val time = DateTimeFormatter.ofPattern("HH:mm").format(zdt)
    when (zdt.toLocalDate()) {
        today -> "今天 $time"
        today.minusDays(1) -> "昨天 $time"
        else -> if (zdt.year == today.year) {
            "${zdt.monthValue}月${zdt.dayOfMonth}日 $time"
        } else {
            "${zdt.year}/${zdt.monthValue}/${zdt.dayOfMonth} $time"
        }
    }
}.getOrElse { iso }
