package io.github.z3275630ops.floatingnotes.feature.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.z3275630ops.floatingnotes.data.backup.BackupUiState
import io.github.z3275630ops.floatingnotes.data.backup.RestoreMode
import io.github.z3275630ops.floatingnotes.data.backup.RestoreOutcome
import io.github.z3275630ops.floatingnotes.data.backup.RestorePreflight
import io.github.z3275630ops.floatingnotes.ui.designsystem.GlassBackdrop
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintActivityIndicator
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintAlertDialog
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintBottomSheet
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGroupedDivider
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGroupedRow
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGroupedSection
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintIcons
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintPillButton
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintSelectionRow
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintSwitch
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintDestructiveColor
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintFontScale
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintSecondaryFill
import java.time.LocalDate

@Composable
internal fun BackupRestoreSheet(
    state: BackupUiState,
    todoStartupEnabled: Boolean = false,
    onToggleTodoStartup: ((Boolean) -> Unit)? = null,
    backdrop: GlassBackdrop,
    dark: Boolean,
    onDismiss: () -> Unit,
    onExportFullBackup: (Uri) -> Unit,
    onExportMarkdownBundle: (Uri) -> Unit,
    onPreflightBackup: (Uri) -> Unit,
    onSetRestoreMode: (RestoreMode) -> Unit,
    onSetAutoBackupBeforeRestore: (Boolean) -> Unit,
    onConfirmRestore: () -> Unit,
    onDismissPreflight: () -> Unit,
    onClearOutcome: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val scale = mintFontScale()
    val today = remember { LocalDate.now().toString() }
    var showMarkdownConfirm by remember { mutableStateOf(false) }

    val fullBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> if (uri != null) onExportFullBackup(uri) }

    val markdownLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> if (uri != null) onExportMarkdownBundle(uri) }

    val restorePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) onPreflightBackup(uri) }

    MintBottomSheet(
        onDismissRequest = { if (!state.busy) onDismiss() },
        dark = dark,
        backdrop = backdrop,
        title = "备份、恢复与导出",
        subtitle = "本机 ZIP 与 Markdown",
        leadingLabel = "关闭",
        dismissOnClickOutside = !state.busy,
        maxHeightFraction = 0.92f,
        modifier = Modifier.testTag("backup-restore-sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (state.busy) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(mintSecondaryFill(dark))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        MintActivityIndicator(color = scheme.primary)
                        Text(
                            text = state.busyLabel ?: "正在处理本地备份…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurface,
                        )
                    }
                }
            }

            if (state.lastRestoreOutcome !is RestoreOutcome.Failure) {
                state.errorMessage?.let { err ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(mintDestructiveColor(dark).copy(alpha = 0.12f))
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                            .testTag("backup-error-banner"),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = mintDestructiveColor(dark),
                            )
                            Text(
                                text = "现有笔记库与本地附件未作任何修改。",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            state.lastExportOutcome?.let { export ->
                val tint = if (export.ok) scheme.primary else mintDestructiveColor(dark)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(tint.copy(alpha = if (dark) 0.16f else 0.10f))
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                        .testTag("backup-export-outcome"),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            text = export.message,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = tint,
                        )
                        export.warnings.forEach { warn ->
                            Text(
                                text = "• $warn",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            state.lastRestoreOutcome?.let { outcome ->
                when (outcome) {
                    is RestoreOutcome.Success -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(scheme.primary.copy(alpha = if (dark) 0.18f else 0.10f))
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                                .testTag("backup-restore-outcome"),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(
                                    text = "恢复完成：导入 ${outcome.importedNotes} 条笔记，跳过或副本重命名 ${outcome.skippedOrRenamedNotes} 条。",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = scheme.primary,
                                )
                                outcome.pausedRemindersNote?.let { note ->
                                    Text(
                                        text = note,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = scheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    is RestoreOutcome.Failure -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(mintDestructiveColor(dark).copy(alpha = 0.12f))
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                                .testTag("backup-restore-outcome"),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(
                                    text = outcome.message,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = mintDestructiveColor(dark),
                                )
                                if (outcome.existingLibraryIntact) {
                                    Text(
                                        text = "现有笔记库与附件完整保留，未受任何损坏。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = scheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "备份与恢复",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = (14.5f * scale).sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                MintGroupedSection(dark = dark) {
                    MintGroupedRow(
                        label = "生成完整备份 (ZIP)",
                        subtitle = "含笔记、历史、组织、搜索、提醒与附件校验",
                        leadingIcon = MintIcons.Export,
                        showChevron = true,
                        enabled = !state.busy,
                        dark = dark,
                        modifier = Modifier.testTag("backup-export-zip"),
                        onClick = {
                            onClearOutcome()
                            fullBackupLauncher.launch("floating-notes-backup-$today.zip")
                        },
                    )
                    MintGroupedDivider(dark = dark, startIndent = 48.dp)
                    MintGroupedRow(
                        label = "导出 Markdown 压缩包",
                        subtitle = "导出通用可读 .md 与附件文件，适用于迁移或阅读归档",
                        leadingIcon = MintIcons.FileDown,
                        showChevron = true,
                        enabled = !state.busy,
                        dark = dark,
                        modifier = Modifier.testTag("backup-export-markdown"),
                        onClick = {
                            onClearOutcome()
                            showMarkdownConfirm = true
                        },
                    )
                    MintGroupedDivider(dark = dark, startIndent = 48.dp)
                    MintGroupedRow(
                        label = "从备份 ZIP 恢复或合并",
                        subtitle = "先预检版本号、校验和与冲突数量，确认后再执行写入",
                        leadingIcon = MintIcons.Restore,
                        showChevron = true,
                        enabled = !state.busy,
                        dark = dark,
                        modifier = Modifier.testTag("backup-import-zip"),
                        onClick = {
                            onClearOutcome()
                            restorePickerLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                        },
                    )
                }
            }

            if (onToggleTodoStartup != null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "快捷偏好",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontSize = (14.5f * scale).sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    MintGroupedSection(dark = dark) {
                        MintGroupedRow(
                            label = "启动默认进入待办",
                            subtitle = "开启后每次冷启动应用将直接展示待办与提醒列表",
                            leadingIcon = MintIcons.Checklist,
                            dark = dark,
                            trailingContent = {
                                MintSwitch(
                                    checked = todoStartupEnabled,
                                    onCheckedChange = onToggleTodoStartup,
                                    dark = dark,
                                    modifier = Modifier.testTag("backup-startup-todo-toggle"),
                                )
                            },
                            onClick = { onToggleTodoStartup(!todoStartupEnabled) },
                        )
                    }
                }
            }
        }
    }

    if (showMarkdownConfirm) {
        val warningText = state.markdownExportWarnings.joinToString("\n• ", prefix = "• ")
        MintAlertDialog(
            onDismissRequest = { showMarkdownConfirm = false },
            dark = dark,
            backdrop = backdrop,
            title = "导出 Markdown 说明",
            subtitle = warningText,
            confirmLabel = "选择保存位置",
            onConfirmAction = {
                showMarkdownConfirm = false
                markdownLauncher.launch("floating-notes-markdown-$today.zip")
            },
        )
    }

    state.preflight?.let { preflight ->
        RestorePreflightSheet(
            preflight = preflight,
            mode = state.preflightMode,
            autoBackup = state.autoBackupBeforeRestore,
            busy = state.busy,
            backdrop = backdrop,
            dark = dark,
            onSelectMode = onSetRestoreMode,
            onToggleAutoBackup = onSetAutoBackupBeforeRestore,
            onConfirm = onConfirmRestore,
            onDismiss = onDismissPreflight,
        )
    }
}

@Composable
private fun RestorePreflightSheet(
    preflight: RestorePreflight,
    mode: RestoreMode,
    autoBackup: Boolean,
    busy: Boolean,
    backdrop: GlassBackdrop,
    dark: Boolean,
    onSelectMode: (RestoreMode) -> Unit,
    onToggleAutoBackup: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val scale = mintFontScale()
    MintBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        dark = dark,
        backdrop = backdrop,
        title = "恢复预检摘要",
        subtitle = "备份时间：${preflight.exportedAt} · Schema v${preflight.schemaVersion}",
        leadingLabel = "取消",
        trailingLabel = if (busy) "恢复中…" else "开始恢复",
        onTrailingClick = onConfirm,
        trailingEnabled = !busy,
        trailingTag = "backup-preflight-confirm",
        dismissOnClickOutside = !busy,
        maxHeightFraction = 0.92f,
        modifier = Modifier.testTag("backup-preflight-sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MintGroupedSection(dark = dark) {
                MintGroupedRow(
                    label = "SHA-256 完整性校验",
                    trailingText = if (preflight.verifiedSha256) "已通过" else "未校验",
                    dark = dark,
                )
                MintGroupedDivider(dark = dark)
                MintGroupedRow(
                    label = "包含内容",
                    subtitle = "${preflight.totalNotes} 笔记（含 ${preflight.trashedNotes} 在废纸篓）· ${preflight.versionCount} 历史版本 · ${preflight.attachmentCount} 附件",
                    dark = dark,
                )
                MintGroupedDivider(dark = dark)
                MintGroupedRow(
                    label = "组织与提醒",
                    subtitle = "${preflight.notebookCount} 笔记本 · ${preflight.tagCount} 标签 · ${preflight.savedSearchCount} 智能文件夹 · ${preflight.reminderCount} 提醒",
                    dark = dark,
                )
                if (preflight.conflictNoteCount > 0) {
                    MintGroupedDivider(dark = dark)
                    MintGroupedRow(
                        label = "检测到 ${preflight.conflictNoteCount} 条同 ID 冲突笔记",
                        subtitle = "合并模式下将自动保留副本，不会静默覆盖当前内容。",
                        dark = dark,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "恢复策略",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = (14.5f * scale).sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                MintGroupedSection(dark = dark) {
                    MintSelectionRow(
                        label = "合并导入（冲突保留副本）",
                        subtitle = "保留本机现有笔记，冲突笔记生成独立副本并恢复缺失条目",
                        selected = mode == RestoreMode.MERGE_KEEP_CONFLICTS,
                        dark = dark,
                        semanticsRole = Role.RadioButton,
                        onClick = { onSelectMode(RestoreMode.MERGE_KEEP_CONFLICTS) },
                    )
                    MintGroupedDivider(dark = dark)
                    MintSelectionRow(
                        label = "完整恢复（覆盖当前库）",
                        subtitle = "将本机笔记、标签与待办还原为备份包快照状态",
                        selected = mode == RestoreMode.REPLACE_ALL,
                        dark = dark,
                        semanticsRole = Role.RadioButton,
                        onClick = { onSelectMode(RestoreMode.REPLACE_ALL) },
                    )
                }
            }

            MintGroupedSection(dark = dark) {
                MintGroupedRow(
                    label = "恢复前自动备份当前库",
                    subtitle = "防止意外替换或中断导致数据丢失",
                    dark = dark,
                    trailingContent = {
                        MintSwitch(
                            checked = autoBackup,
                            onCheckedChange = onToggleAutoBackup,
                            dark = dark,
                            modifier = Modifier.testTag("backup-preflight-autobackup"),
                        )
                    },
                    onClick = { onToggleAutoBackup(!autoBackup) },
                )
            }

            MintPillButton(
                label = if (busy) "正在应用恢复…" else "确认并执行恢复",
                icon = MintIcons.Restore,
                primary = true,
                enabled = !busy,
                dark = dark,
                modifier = Modifier.fillMaxWidth(),
                onClick = onConfirm,
            )
        }
    }
}
