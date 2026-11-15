package io.github.z3275630ops.floatingnotes.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.z3275630ops.floatingnotes.data.backup.TrashPurgeImpact
import io.github.z3275630ops.floatingnotes.data.local.NoteEntity
import io.github.z3275630ops.floatingnotes.ui.designsystem.GlassBackdrop
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintAlertDialog
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGroupedDivider
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGroupedSection
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintIcons
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintPillButton
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintAmbient
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintDestructiveColor
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintSecondaryFill
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun TrashView(
    trashedNotes: List<NoteEntity>,
    purgeImpact: TrashPurgeImpact?,
    dark: Boolean,
    backdrop: GlassBackdrop,
    onOpenDrawer: () -> Unit,
    onRestore: (NoteEntity) -> Unit,
    onPreparePurgeNote: (NoteEntity) -> Unit,
    onPrepareEmptyTrash: () -> Unit,
    onConfirmPurgeNote: (NoteEntity) -> Unit,
    onConfirmEmptyTrash: () -> Unit,
    onClearPurgeImpact: () -> Unit,
    onOpenBackupExport: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var purgeTarget by remember { mutableStateOf<NoteEntity?>(null) }
    var emptyAllPending by remember { mutableStateOf(false) }

    fun dismissPurgeDialog() {
        purgeTarget = null
        emptyAllPending = false
        onClearPurgeImpact()
    }

    val opaqueCanvas = if (dark) Color(0xFF0D1813) else Color(0xFFF3FAF5)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(opaqueCanvas),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(opaqueCanvas)
                .mintAmbient(dark)
                .clearAndSetSemantics { }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                )
                .navigationBarsPadding()
                .testTag("trash-screen"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 44.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .clip(RoundedCornerShape(50))
                            .testTag("open-notes-drawer")
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Button,
                                onClick = onOpenDrawer,
                            )
                            .padding(end = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = MintIcons.Menu,
                            contentDescription = "打开笔记导航",
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "文件夹",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    if (trashedNotes.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                purgeTarget = null
                                emptyAllPending = true
                                onPrepareEmptyTrash()
                            },
                            modifier = Modifier.testTag("trash-empty-button"),
                        ) {
                            Text(
                                text = "清空废纸篓",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = mintDestructiveColor(dark),
                            )
                        }
                    }
                }
                Text(
                    text = "最近删除",
                    style = MaterialTheme.typography.displayLarge,
                    color = scheme.onSurface,
                )
                Text(
                    text = if (trashedNotes.isEmpty()) "废纸篓为空" else "${trashedNotes.size} 条已删除笔记",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        }

        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(mintSecondaryFill(dark))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .testTag("trash-retention-banner"),
            ) {
                Text(
                    text = "废纸篓中的笔记及其历史版本与附件均保留在本地（默认保留 30 天内可随时恢复）。永久清理不可撤回，清理前可先导出备份。",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }

        if (trashedNotes.isEmpty()) {
            item {
                MintGroupedSection(dark = dark) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "没有已删除的笔记",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = scheme.onSurface,
                        )
                        Text(
                            text = "在笔记菜单中选择「移到废纸篓」，可暂存到这里并在需要时恢复。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            item {
                MintGroupedSection(dark = dark) {
                    trashedNotes.forEachIndexed { index, note ->
                        if (index > 0) MintGroupedDivider(dark = dark)
                        TrashedNoteRow(
                            note = note,
                            dark = dark,
                            onRestore = { onRestore(note) },
                            onPurge = {
                                emptyAllPending = false
                                purgeTarget = note
                                onPreparePurgeNote(note)
                            },
                        )
                    }
                }
            }
        }
    }
    }

    if (emptyAllPending || purgeTarget != null) {
        val target = purgeTarget
        val impactText = purgeImpact?.let { impact ->
            "将永久清除 ${impact.noteCount} 条笔记、${impact.versionCount} 个历史版本，并清理 ${impact.orphanedAttachmentCount} 个不再被引用的附件，无法恢复。"
        } ?: "正在统计永久清理影响…"
        MintAlertDialog(
            onDismissRequest = ::dismissPurgeDialog,
            dark = dark,
            backdrop = backdrop,
            title = if (emptyAllPending) "清空废纸篓？" else "彻底删除这条笔记？",
            subtitle = impactText,
            confirmLabel = "彻底删除",
            destructive = true,
            modifier = Modifier.testTag("trash-purge-confirm-dialog"),
            onConfirmAction = {
                if (emptyAllPending) onConfirmEmptyTrash()
                else if (target != null) onConfirmPurgeNote(target)
                dismissPurgeDialog()
            },
        ) {
            MintPillButton(
                label = "先导出备份",
                icon = MintIcons.Export,
                dark = dark,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("trash-purge-export-shortcut"),
                onClick = {
                    dismissPurgeDialog()
                    onOpenBackupExport()
                },
            )
        }
    }
}

@Composable
private fun TrashedNoteRow(
    note: NoteEntity,
    dark: Boolean,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val title = note.title?.takeIf(String::isNotBlank)
        ?: note.searchText.lineSequence().firstOrNull { it.isNotBlank() }
        ?: "无标题笔记"
    val deletedWhen = note.deletedAt?.let(::formatTrashTimestamp).orEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {}
            .testTag("trashed-note"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = buildString {
                if (deletedWhen.isNotEmpty()) append("删除于 $deletedWhen")
                val body = note.searchText.trim()
                if (body.isNotEmpty() && body != title) {
                    if (isNotEmpty()) append("  ·  ")
                    append(body)
                }
            }
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onRestore,
                modifier = Modifier.testTag("trash-restore-${note.id}"),
            ) {
                Text("恢复", color = scheme.primary, fontWeight = FontWeight.SemiBold)
            }
            TextButton(
                onClick = onPurge,
                modifier = Modifier.testTag("trash-purge-${note.id}"),
            ) {
                Text("彻底删除", color = mintDestructiveColor(dark), fontWeight = FontWeight.Medium)
            }
        }
    }
}

private fun formatTrashTimestamp(iso: String): String = runCatching {
    val zdt = Instant.parse(iso).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    val time = DateTimeFormatter.ofPattern("HH:mm").format(zdt)
    when (zdt.toLocalDate()) {
        today -> "今天 $time"
        today.minusDays(1) -> "昨天 $time"
        else -> "${zdt.monthValue}月${zdt.dayOfMonth}日"
    }
}.getOrElse { iso }
