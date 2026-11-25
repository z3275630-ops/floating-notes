package io.github.z3275630ops.floatingnotes.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.z3275630ops.floatingnotes.BuildConfig
import io.github.z3275630ops.floatingnotes.ui.designsystem.GlassBackdrop
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintBottomSheet
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGlassDialog
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGroupedDivider
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGroupedRow
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintGroupedSection
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintIcons
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintPillButton
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintSegmentedBar
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintSwitch
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintDividerColor
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintFontScale
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintSecondaryFill
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintTodoAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@Immutable
internal data class AlarmChecklistState(
    val notificationsGranted: Boolean = true,
    val exactAlarmGranted: Boolean = true,
    val fullScreenIntentGranted: Boolean = true,
    val batteryOptimizationIgnored: Boolean = false,
) {
    val readyCount: Int
        get() = listOf(
            notificationsGranted,
            exactAlarmGranted,
            fullScreenIntentGranted,
            batteryOptimizationIgnored,
        ).count { it }
}

@Immutable
internal data class SettingsCenterState(
    val preferences: AppPreferencesSnapshot = AppPreferencesSnapshot(),
    val alarmChecklist: AlarmChecklistState = AlarmChecklistState(),
    val attachmentStorageBytes: Long = 0L,
    val appVersionName: String = BuildConfig.VERSION_NAME,
)

@Composable
internal fun SettingsCenterSheet(
    state: SettingsCenterState,
    dark: Boolean,
    backdrop: GlassBackdrop,
    onDismiss: () -> Unit,
    onSelectFontScaleTier: (FontScaleTier) -> Unit,
    onSelectNoteSortOrder: (NoteSortOrder) -> Unit,
    onToggleSlashShortcuts: (Boolean) -> Unit,
    onToggleEdgeEcho: (Boolean) -> Unit,
    onToggleOpenTasksFirst: (Boolean) -> Unit,
    onToggleStrongAlarm: (Boolean) -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    onOpenFullScreenIntentSettings: () -> Unit,
    onOpenBatteryOptimizationSettings: () -> Unit,
    onToggleWidgetPrivacyMask: (Boolean) -> Unit,
    onSelectTrashRetentionDays: (Int) -> Unit,
    onOpenBackupRestore: () -> Unit,
    onRestoreDiscoveryExclusions: () -> Unit,
    modifier: Modifier = Modifier,
    onRefreshAlarmHealth: () -> Unit = {},
    aiSummaryLabel: String = "仅本机离线规则",
    onOpenAiSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val windowInfo = LocalWindowInfo.current
    val currentRefreshAlarmHealth by rememberUpdatedState(onRefreshAlarmHealth)
    val scheme = MaterialTheme.colorScheme
    val scale = mintFontScale()
    val prefs = state.preferences
    var showLicensesDialog by remember { mutableStateOf(false) }
    var exclusionRestoredHint by remember { mutableStateOf(false) }

    LaunchedEffect(windowInfo.isWindowFocused) {
        if (windowInfo.isWindowFocused) {
            currentRefreshAlarmHealth()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                currentRefreshAlarmHealth()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val noticeText by produceState<String?>(initialValue = null, showLicensesDialog) {
        if (showLicensesDialog && value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    context.assets.open("third-party/NOTICE.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
                }.getOrElse {
                    "未能读取第三方开源许可声明（assets/third-party/NOTICE.txt）。"
                }
            }
        }
    }

    MintBottomSheet(
        onDismissRequest = onDismiss,
        dark = dark,
        backdrop = backdrop,
        title = "设置中心",
        subtitle = "显示、编辑、闹钟与数据管理",
        leadingLabel = "关闭",
        maxHeightFraction = 0.94f,
        modifier = modifier.testTag("settings-center-sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(top = 10.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // 1. 显示与阅读
            SettingsSectionBlock(title = "显示与阅读", scale = scale) {
                MintGroupedSection(dark = dark) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = SettingsIcons.TextSize,
                                contentDescription = null,
                                tint = scheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = "全局字号",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontSize = (16.5f * scale).sp,
                                    fontWeight = FontWeight.Medium,
                                ),
                                color = scheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${prefs.fontScaleTier.label} (${(prefs.fontScaleTier.multiplier * 100).toInt()}%)",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = (14f * scale).sp),
                                color = scheme.primary,
                            )
                        }
                        MintSegmentedBar(
                            options = FontScaleTier.entries.map { it.label },
                            selectedIndex = FontScaleTier.entries.indexOf(prefs.fontScaleTier).coerceAtLeast(0),
                            onSelect = { idx -> onSelectFontScaleTier(FontScaleTier.entries[idx]) },
                            dark = dark,
                            optionTags = FontScaleTier.entries.map { "settings-font-tier-${it.name}" },
                        )
                        val previewMultiplier = prefs.fontScaleTier.multiplier
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(mintSecondaryFill(dark))
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                                .testTag("settings-font-preview-card"),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "字号实时预览 · 浮记正文效果",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontSize = (16.5f * previewMultiplier).sp,
                                    lineHeight = (23f * previewMultiplier).sp,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = scheme.onSurface,
                            )
                            Text(
                                text = "把一闪而过的灵感留下来，夜间或长文阅读也能清晰舒适。",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = (15.5f * previewMultiplier).sp,
                                    lineHeight = (22.5f * previewMultiplier).sp,
                                ),
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                    MintGroupedDivider(dark = dark, startIndent = 48.dp)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = SettingsIcons.SortOrder,
                                contentDescription = null,
                                tint = scheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = "笔记排序规则",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontSize = (16.5f * scale).sp,
                                    fontWeight = FontWeight.Medium,
                                ),
                                color = scheme.onSurface,
                            )
                        }
                        MintSegmentedBar(
                            options = NoteSortOrder.entries.map { it.label },
                            selectedIndex = NoteSortOrder.entries.indexOf(prefs.noteSortOrder).coerceAtLeast(0),
                            onSelect = { idx -> onSelectNoteSortOrder(NoteSortOrder.entries[idx]) },
                            dark = dark,
                            optionTags = NoteSortOrder.entries.map { "settings-sort-${it.name}" },
                        )
                    }
                }
            }

            // 2. 编辑与快捷
            SettingsSectionBlock(title = "编辑与快捷", scale = scale) {
                MintGroupedSection(dark = dark) {
                    MintGroupedRow(
                        label = "启用 / 斜杠与前缀快捷排版",
                        subtitle = "输入 / 或 #、-、[ ] 等前缀快速切换标题、清单与引用",
                        leadingIcon = SettingsIcons.SlashCommand,
                        dark = dark,
                        trailingContent = {
                            MintSwitch(
                                checked = prefs.enableSlashShortcuts,
                                onCheckedChange = onToggleSlashShortcuts,
                                dark = dark,
                                modifier = Modifier.testTag("settings-slash-shortcuts-toggle"),
                            )
                        },
                        onClick = { onToggleSlashShortcuts(!prefs.enableSlashShortcuts) },
                    )
                    MintGroupedDivider(dark = dark, startIndent = 48.dp)
                    MintGroupedRow(
                        label = "写作边缘历史回响",
                        subtitle = "编辑时停顿片刻，在边缘轻提示相关旧笔记，不打断输入",
                        leadingIcon = MintIcons.Explore,
                        dark = dark,
                        trailingContent = {
                            MintSwitch(
                                checked = prefs.edgeEchoEnabled,
                                onCheckedChange = onToggleEdgeEcho,
                                dark = dark,
                                modifier = Modifier.testTag("settings-edge-echo-toggle"),
                            )
                        },
                        onClick = { onToggleEdgeEcho(!prefs.edgeEchoEnabled) },
                    )
                }
            }

            // 3. AI 模型与接口 (BYOK)
            SettingsSectionBlock(title = "AI 模型与接口 (BYOK)", scale = scale) {
                MintGroupedSection(dark = dark) {
                    MintGroupedRow(
                        label = "AI 模型与接口",
                        subtitle = "配置 DeepSeek / OpenAI / 硅基流动 / 本地 Ollama 等模型；默认仅本机离线",
                        leadingIcon = MintIcons.Sparkles,
                        trailingText = aiSummaryLabel,
                        showChevron = true,
                        dark = dark,
                        modifier = Modifier.testTag("settings-open-ai-config"),
                        onClick = onOpenAiSettings,
                    )
                }
            }

            // 4. 待办与强闹钟
            SettingsSectionBlock(title = "待办与强闹钟", scale = scale) {
                MintGroupedSection(dark = dark) {
                    MintGroupedRow(
                        label = "启动默认进入待办",
                        subtitle = "开启后每次冷启动应用将直接展示待办与提醒列表",
                        leadingIcon = MintIcons.Checklist,
                        dark = dark,
                        trailingContent = {
                            MintSwitch(
                                checked = prefs.openTasksFirst,
                                onCheckedChange = onToggleOpenTasksFirst,
                                dark = dark,
                                modifier = Modifier.testTag("settings-open-tasks-toggle"),
                            )
                        },
                        onClick = { onToggleOpenTasksFirst(!prefs.openTasksFirst) },
                    )
                    MintGroupedDivider(dark = dark, startIndent = 48.dp)
                    MintGroupedRow(
                        label = "启用强响铃闹钟",
                        subtitle = "使用系统闹钟通道触发锁屏响铃提醒，提升静音与息屏触达率",
                        leadingIcon = MintIcons.Bell,
                        dark = dark,
                        trailingContent = {
                            MintSwitch(
                                checked = prefs.strongAlarmEnabled,
                                onCheckedChange = onToggleStrongAlarm,
                                dark = dark,
                                modifier = Modifier.testTag("settings-strong-alarm-toggle"),
                            )
                        },
                        onClick = { onToggleStrongAlarm(!prefs.strongAlarmEnabled) },
                    )
                    MintGroupedDivider(dark = dark, startIndent = 48.dp)
                    AlarmChecklistCard(
                        checklist = state.alarmChecklist,
                        dark = dark,
                        scale = scale,
                        onRefresh = onRefreshAlarmHealth,
                        onOpenNotificationSettings = onOpenNotificationSettings,
                        onOpenExactAlarmSettings = onOpenExactAlarmSettings,
                        onOpenFullScreenIntentSettings = onOpenFullScreenIntentSettings,
                        onOpenBatteryOptimizationSettings = onOpenBatteryOptimizationSettings,
                    )
                }
            }

            // 4. 隐私与数据
            SettingsSectionBlock(title = "隐私与数据", scale = scale) {
                MintGroupedSection(dark = dark) {
                    MintGroupedRow(
                        label = "桌面小组件隐私遮罩",
                        subtitle = "默认开启：桌面回顾卡片隐藏敏感正文，点击才打开原文",
                        leadingIcon = MintIcons.Lock,
                        dark = dark,
                        trailingContent = {
                            MintSwitch(
                                checked = prefs.widgetPrivacyMask,
                                onCheckedChange = onToggleWidgetPrivacyMask,
                                dark = dark,
                                modifier = Modifier.testTag("settings-widget-privacy-toggle"),
                            )
                        },
                        onClick = { onToggleWidgetPrivacyMask(!prefs.widgetPrivacyMask) },
                    )
                    MintGroupedDivider(dark = dark, startIndent = 48.dp)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = MintIcons.Trash,
                                contentDescription = null,
                                tint = scheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = "废纸篓自动清理天数",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontSize = (16.5f * scale).sp,
                                    fontWeight = FontWeight.Medium,
                                ),
                                color = scheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${prefs.trashRetentionDays} 天",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = (14f * scale).sp),
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        val dayOptions = AppPreferences.VALID_TRASH_RETENTION_DAYS
                        MintSegmentedBar(
                            options = dayOptions.map { "$it 天" },
                            selectedIndex = dayOptions.indexOf(prefs.trashRetentionDays).coerceAtLeast(1),
                            onSelect = { idx -> onSelectTrashRetentionDays(dayOptions[idx]) },
                            dark = dark,
                            optionTags = dayOptions.map { "settings-trash-days-$it" },
                        )
                    }
                    MintGroupedDivider(dark = dark, startIndent = 48.dp)
                    MintGroupedRow(
                        label = "备份、恢复与导出",
                        subtitle = "生成完整 ZIP 备份、导出 Markdown 压缩包或恢复存档",
                        leadingIcon = MintIcons.Export,
                        showChevron = true,
                        dark = dark,
                        modifier = Modifier.testTag("settings-open-backup-restore"),
                        onClick = onOpenBackupRestore,
                    )
                    MintGroupedDivider(dark = dark, startIndent = 48.dp)
                    MintGroupedRow(
                        label = "恢复已排除的回顾与发现内容",
                        subtitle = if (exclusionRestoredHint) {
                            "已恢复全部此前隐藏的笔记与标签"
                        } else {
                            "重新允许此前在回顾或找一找中隐藏的笔记和标签出现"
                        },
                        leadingIcon = MintIcons.Restore,
                        showChevron = true,
                        dark = dark,
                        modifier = Modifier.testTag("settings-restore-exclusions"),
                        onClick = {
                            onRestoreDiscoveryExclusions()
                            exclusionRestoredHint = true
                        },
                    )
                }
            }

            // 5. 关于浮记
            SettingsSectionBlock(title = "关于浮记", scale = scale) {
                MintGroupedSection(dark = dark) {
                    MintGroupedRow(
                        label = "版本号",
                        leadingIcon = SettingsIcons.Info,
                        trailingText = state.appVersionName,
                        dark = dark,
                        modifier = Modifier.testTag("settings-about-version"),
                    )
                    MintGroupedDivider(dark = dark, startIndent = 48.dp)
                    MintGroupedRow(
                        label = "附件空间占用",
                        subtitle = "本地图片、音频与视频缓存总大小",
                        leadingIcon = MintIcons.Paperclip,
                        trailingText = formatStorageMb(state.attachmentStorageBytes),
                        dark = dark,
                        modifier = Modifier.testTag("settings-about-storage"),
                    )
                    MintGroupedDivider(dark = dark, startIndent = 48.dp)
                    MintGroupedRow(
                        label = "开源许可",
                        subtitle = "查看第三方组件与许可协议声明",
                        leadingIcon = MintIcons.Sparkles,
                        showChevron = true,
                        dark = dark,
                        modifier = Modifier.testTag("settings-open-licenses"),
                        onClick = { showLicensesDialog = true },
                    )
                }
            }
        }
    }

    if (showLicensesDialog) {
        MintGlassDialog(
            onDismissRequest = { showLicensesDialog = false },
            dark = dark,
            backdrop = backdrop,
            title = "开源许可声明",
            subtitle = "Floating Notes 第三方组件",
            modifier = Modifier.testTag("settings-licenses-dialog"),
            actions = {
                MintPillButton(
                    label = "关闭",
                    onClick = { showLicensesDialog = false },
                    dark = dark,
                    primary = true,
                )
            },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(mintSecondaryFill(dark))
                    .padding(14.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = noticeText ?: "正在读取开源许可…",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = (13.5f * scale).sp,
                        lineHeight = (20f * scale).sp,
                    ),
                    color = scheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun SettingsSectionBlock(
    title: String,
    scale: Float,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = (14f * scale).sp,
                lineHeight = (19f * scale).sp,
                fontWeight = FontWeight.Medium,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        content()
    }
}

@Composable
private fun AlarmChecklistCard(
    checklist: AlarmChecklistState,
    dark: Boolean,
    scale: Float,
    onRefresh: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    onOpenFullScreenIntentSettings: () -> Unit,
    onOpenBatteryOptimizationSettings: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val readyCount = checklist.readyCount
    val warnAccent = mintTodoAccent(dark)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 16.dp)
            .testTag("settings-alarm-checklist-card"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = SettingsIcons.ShieldCheck,
                    contentDescription = null,
                    tint = if (readyCount == 4) scheme.primary else warnAccent.fg,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = "待办闹钟防漏响自检",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = (16.5f * scale).sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .defaultMinSize(minHeight = 36.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(mintSecondaryFill(dark))
                        .testTag("settings-alarm-refresh-button")
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClick = onRefresh,
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = MintIcons.Refresh,
                        contentDescription = "刷新状态",
                        tint = scheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = "刷新状态",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = (12.5f * scale).sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = scheme.primary,
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (readyCount == 4) scheme.primaryContainer else warnAccent.bg)
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "$readyCount/4 已就绪",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = (12.5f * scale).sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = if (readyCount == 4) scheme.primary else warnAccent.fg,
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(mintSecondaryFill(dark))
                .border(0.6.dp, mintDividerColor(dark), RoundedCornerShape(12.dp)),
        ) {
            AlarmCheckItemRow(
                title = "通知权限",
                subtitle = "允许弹出待办提醒与横幅通知",
                granted = checklist.notificationsGranted,
                dark = dark,
                scale = scale,
                tag = "settings-alarm-check-notifications",
                onOpenSettings = onOpenNotificationSettings,
            )
            MintGroupedDivider(dark = dark, startIndent = 14.dp)
            AlarmCheckItemRow(
                title = "精确闹钟",
                subtitle = "确保到点准时唤醒，不被系统延迟合并",
                granted = checklist.exactAlarmGranted,
                dark = dark,
                scale = scale,
                tag = "settings-alarm-check-exact",
                onOpenSettings = onOpenExactAlarmSettings,
            )
            MintGroupedDivider(dark = dark, startIndent = 14.dp)
            AlarmCheckItemRow(
                title = "锁屏全屏通知",
                subtitle = "息屏或锁屏时可直接弹出响铃界面",
                granted = checklist.fullScreenIntentGranted,
                dark = dark,
                scale = scale,
                tag = "settings-alarm-check-fullscreen",
                onOpenSettings = onOpenFullScreenIntentSettings,
            )
            MintGroupedDivider(dark = dark, startIndent = 14.dp)
            AlarmCheckItemRow(
                title = "系统忽略电池优化",
                subtitle = "授权「允许始终后台运行」以防息屏漏响（独立于手机管家自启动，点此一键弹窗授权）",
                granted = checklist.batteryOptimizationIgnored,
                dark = dark,
                scale = scale,
                tag = "settings-alarm-check-battery",
                onOpenSettings = onOpenBatteryOptimizationSettings,
            )
        }
    }
}

@Composable
private fun AlarmCheckItemRow(
    title: String,
    subtitle: String,
    granted: Boolean,
    dark: Boolean,
    scale: Float,
    tag: String,
    onOpenSettings: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val warnAccent = mintTodoAccent(dark)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = (15.5f * scale).sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = scheme.onSurface,
                )
                Text(
                    text = if (granted) "已开启" else "待开启",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = (12.5f * scale).sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = if (granted) scheme.primary else warnAccent.fg,
                )
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = (13.5f * scale).sp,
                    lineHeight = (19f * scale).sp,
                ),
                color = scheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .defaultMinSize(minHeight = 44.dp)
                .clip(RoundedCornerShape(10.dp))
                .testTag(tag)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onOpenSettings,
                )
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (granted) "查看" else "去设置",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = (14f * scale).sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = scheme.primary,
            )
        }
    }
}

internal fun formatStorageMb(bytes: Long): String {
    val mb = bytes.coerceAtLeast(0L) / (1024.0 * 1024.0)
    return String.format(Locale.US, "%.2f MB", mb)
}

private object SettingsIcons {
    val TextSize: ImageVector by lazy {
        strokeIcon("SettingsTextSize") {
            moveTo(4f, 18.5f); lineTo(8.5f, 6.5f); lineTo(13f, 18.5f)
            moveTo(5.6f, 14.8f); horizontalLineTo(11.4f)
            moveTo(15f, 18.5f); verticalLineTo(10.5f)
            moveTo(15f, 13.5f); curveTo(15f, 11.6f, 16.4f, 10.5f, 18f, 10.5f)
            curveTo(19.6f, 10.5f, 20.5f, 11.6f, 20.5f, 13.5f); verticalLineTo(18.5f)
        }
    }

    val SortOrder: ImageVector by lazy {
        strokeIcon("SettingsSortOrder") {
            moveTo(7.5f, 5f); verticalLineTo(19f)
            moveTo(4.5f, 8f); lineTo(7.5f, 5f); lineTo(10.5f, 8f)
            moveTo(16.5f, 19f); verticalLineTo(5f)
            moveTo(13.5f, 16f); lineTo(16.5f, 19f); lineTo(19.5f, 16f)
        }
    }

    val SlashCommand: ImageVector by lazy {
        strokeIcon("SettingsSlashCommand") {
            moveTo(14.5f, 5f); lineTo(9.5f, 19f)
            moveTo(6.5f, 8.5f); lineTo(3.5f, 12f); lineTo(6.5f, 15.5f)
            moveTo(17.5f, 8.5f); lineTo(20.5f, 12f); lineTo(17.5f, 15.5f)
        }
    }

    val ShieldCheck: ImageVector by lazy {
        strokeIcon("SettingsShieldCheck") {
            moveTo(12f, 3.8f)
            lineTo(19f, 6.5f)
            verticalLineTo(11.8f)
            curveTo(19f, 16.2f, 15.9f, 19.2f, 12f, 20.4f)
            curveTo(8.1f, 19.2f, 5f, 16.2f, 5f, 11.8f)
            verticalLineTo(6.5f)
            close()
            moveTo(9.2f, 12.2f); lineTo(11.2f, 14.2f); lineTo(15.2f, 9.8f)
        }
    }

    val Info: ImageVector by lazy {
        strokeIcon("SettingsInfo") {
            moveTo(12f, 3.8f)
            curveTo(16.5f, 3.8f, 20.2f, 7.5f, 20.2f, 12f)
            curveTo(20.2f, 16.5f, 16.5f, 20.2f, 12f, 20.2f)
            curveTo(7.5f, 20.2f, 3.8f, 16.5f, 3.8f, 12f)
            curveTo(3.8f, 7.5f, 7.5f, 3.8f, 12f, 3.8f)
            close()
            moveTo(12f, 11f); verticalLineTo(16.2f)
            moveTo(12f, 7.8f); verticalLineTo(8.2f)
        }
    }

    private fun strokeIcon(
        name: String,
        strokeWidth: Float = 1.75f,
        block: PathBuilder.() -> Unit,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = strokeWidth,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }.build()
}
