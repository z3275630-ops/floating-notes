package io.github.z3275630ops.floatingnotes.feature.explore

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.z3275630ops.floatingnotes.data.discovery.EvidenceHit
import io.github.z3275630ops.floatingnotes.data.discovery.RelatedNoteMatch
import io.github.z3275630ops.floatingnotes.ui.designsystem.GlassBackdrop
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintActivityIndicator
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintBottomSheet
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGroupedDivider
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGroupedSection
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintIcons
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintPillButton
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintAiAccent
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintAnimatorsEnabled
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintCardSurface
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintSecondaryFill

@Composable
internal fun EditorEdgeEchoStrip(
    hit: EvidenceHit?,
    dark: Boolean,
    onPreview: (EvidenceHit) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val animEnabled = mintAnimatorsEnabled()
    val shiftPx = with(LocalDensity.current) { 8.dp.roundToPx() }

    AnimatedVisibility(
        visible = hit != null,
        enter = if (!animEnabled) fadeIn(snap()) else fadeIn(tween(220)) + slideInVertically(tween(220)) { shiftPx },
        exit = if (!animEnabled) fadeOut(snap()) else fadeOut(tween(180)),
        modifier = modifier.focusProperties { canFocus = false },
    ) {
        val item = hit ?: return@AnimatedVisibility
        val aiAccent = mintAiAccent(dark)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(aiAccent.bg)
                .focusProperties { canFocus = false }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = { onPreview(item) },
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("editor-edge-echo"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = MintIcons.Sparkles,
                contentDescription = null,
                tint = aiAccent.fg,
                modifier = Modifier.size(16.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "旧日回响 · ${item.noteTitle}",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = aiAccent.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.mergedExcerpt,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "查看",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = aiAccent.fg,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .focusProperties { canFocus = false }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onDismiss,
                    )
                    .testTag("editor-edge-echo-dismiss"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MintIcons.Close,
                    contentDescription = "收起回响",
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EdgeEchoPreviewSheet(
    hit: EvidenceHit,
    dark: Boolean,
    backdrop: GlassBackdrop,
    onDismiss: () -> Unit,
    onOpenTarget: (EvidenceHit) -> Unit,
    onMuteSession: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    MintBottomSheet(
        onDismissRequest = onDismiss,
        dark = dark,
        backdrop = backdrop,
        title = "历史回响",
        subtitle = "写作边缘关联 · ${formatShortDate(hit.note.updatedAt)}",
        leadingLabel = "关闭",
        trailingLabel = "跳往原笔记",
        onTrailingClick = { onOpenTarget(hit) },
        modifier = Modifier.testTag("editor-edge-echo-sheet"),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MintGroupedSection(dark = dark, containerColor = mintCardSurface(dark)) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = hit.noteTitle,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = scheme.onSurface,
                    )
                    if (hit.reasons.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MintPillButton(
                    label = "本次编辑不再提示",
                    primary = false,
                    dark = dark,
                    modifier = Modifier.weight(1f).testTag("editor-edge-echo-mute"),
                    onClick = onMuteSession,
                )
                MintPillButton(
                    label = "跳往原笔记",
                    primary = true,
                    dark = dark,
                    modifier = Modifier.weight(1f).testTag("editor-edge-echo-open"),
                    onClick = { onOpenTarget(hit) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EditorRelatedNotesSheet(
    noteId: String,
    state: EditorDiscoveryState,
    dark: Boolean,
    backdrop: GlassBackdrop,
    onDismiss: () -> Unit,
    onOpenRelatedNote: (RelatedNoteMatch) -> Unit,
    onStartWalkFromCurrent: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    MintBottomSheet(
        onDismissRequest = onDismiss,
        dark = dark,
        backdrop = backdrop,
        title = "相关笔记",
        subtitle = "基于引用、共同标签与主题相似度",
        leadingLabel = "关闭",
        trailingLabel = "从此漫步",
        onTrailingClick = { onStartWalkFromCurrent(noteId) },
        modifier = Modifier.testTag("editor-related-sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.loadingRelated) {
                MintGroupedSection(dark = dark) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MintActivityIndicator(color = scheme.primary)
                        Text(
                            text = "正在分析相关笔记…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            } else if (state.relatedNotes.isEmpty()) {
                MintGroupedSection(dark = dark) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "暂时还没有找到关联较强的旧笔记",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = scheme.onSurface,
                        )
                        Text(
                            text = "加上标签或在正文输入 @ 引用其他笔记后，这里会自动浮现相关线索。",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                state.relatedNotes.forEach { match ->
                    MintGroupedSection(dark = dark, containerColor = mintCardSurface(dark)) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    role = Role.Button,
                                    onClick = { onOpenRelatedNote(match) },
                                )
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = match.noteTitle,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = scheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.size(8.dp))
                                Text(
                                    text = formatShortDate(match.note.updatedAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                match.reasons.forEach { r ->
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(mintSecondaryFill(dark))
                                            .padding(horizontal = 8.dp, vertical = 3.dp),
                                    ) {
                                        Text(
                                            text = r,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = scheme.primary,
                                        )
                                    }
                                }
                            }
                            if (match.previewExcerpt.isNotBlank()) {
                                Text(
                                    text = match.previewExcerpt,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
