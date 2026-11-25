package io.github.z3275630ops.floatingnotes.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import io.github.z3275630ops.floatingnotes.ui.designsystem.MintSwitch
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintAiAccent
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintCardSurface
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintDestructiveColor
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintDividerColor
import io.github.z3275630ops.floatingnotes.ui.designsystem.mintSecondaryFill

internal data class AiProviderPresetOption(
    val id: String,
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val requiresApiKey: Boolean = true,
)

internal val defaultAiProviderPresets: List<AiProviderPresetOption> = listOf(
    AiProviderPresetOption(
        id = "deepseek",
        displayName = "DeepSeek",
        defaultBaseUrl = "https://api.deepseek.com/v1",
        defaultModel = "deepseek-chat",
        requiresApiKey = true,
    ),
    AiProviderPresetOption(
        id = "openai",
        displayName = "OpenAI",
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-4o-mini",
        requiresApiKey = true,
    ),
    AiProviderPresetOption(
        id = "siliconflow",
        displayName = "硅基流动",
        defaultBaseUrl = "https://api.siliconflow.cn/v1",
        defaultModel = "deepseek-ai/DeepSeek-V3",
        requiresApiKey = true,
    ),
    AiProviderPresetOption(
        id = "ollama",
        displayName = "Ollama(本地)",
        defaultBaseUrl = "http://127.0.0.1:11434/v1",
        defaultModel = "qwen2.5:7b",
        requiresApiKey = false,
    ),
    AiProviderPresetOption(
        id = "custom",
        displayName = "自定义",
        defaultBaseUrl = "",
        defaultModel = "",
        requiresApiKey = true,
    ),
)

internal data class AiSettingsUiState(
    val presetId: String = "deepseek",
    val enabled: Boolean = false,
    val baseUrl: String = "https://api.deepseek.com/v1",
    val model: String = "deepseek-chat",
    val apiKeyDraft: String = "",
    val hasSavedApiKey: Boolean = false,
    val maskedApiKey: String = "",
    val requiresApiKey: Boolean = true,
    val testingConnection: Boolean = false,
    val lastTestSuccess: Boolean? = null,
    val lastTestMessage: String? = null,
    val lastLatencyMs: Long? = null,
    val presets: List<AiProviderPresetOption> = defaultAiProviderPresets,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AiSettingsSheet(
    state: AiSettingsUiState,
    backdrop: GlassBackdrop,
    dark: Boolean,
    onDismiss: () -> Unit,
    onSelectPreset: (AiProviderPresetOption) -> Unit = {},
    onToggleEnabled: (Boolean) -> Unit = {},
    onBaseUrlChange: (String) -> Unit = {},
    onModelChange: (String) -> Unit = {},
    onApiKeyDraftChange: (String) -> Unit = {},
    onClearSavedApiKey: () -> Unit = {},
    onTestConnection: () -> Unit = {},
    onSave: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val aiAccent = mintAiAccent(dark)
    var revealApiKey by rememberSaveable { mutableStateOf(false) }
    val activePreset = remember(state.presetId, state.presets) {
        state.presets.firstOrNull { it.id == state.presetId }
    }

    MintBottomSheet(
        onDismissRequest = { if (!state.testingConnection) onDismiss() },
        dark = dark,
        backdrop = backdrop,
        title = "AI 模型与接口",
        subtitle = "BYOK 自定义大模型",
        leadingLabel = "取消",
        trailingLabel = "保存",
        onTrailingClick = onSave,
        trailingEnabled = !state.testingConnection,
        trailingTag = "ai-settings-save",
        maxHeightFraction = 0.92f,
        dismissOnClickOutside = !state.testingConnection,
        modifier = Modifier.testTag("ai-settings-sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 1. 主开关
            MintGroupedSection(dark = dark, containerColor = mintCardSurface(dark)) {
                MintGroupedRow(
                    label = "启用外部大模型进行深度分析",
                    subtitle = if (state.enabled) {
                        "已启用：可用于多步研究、多视角洞察与语音去口癖"
                    } else {
                        "关闭时全部保留在本机离线规则引擎运行"
                    },
                    leadingIcon = MintIcons.Sparkles,
                    iconTint = if (state.enabled) aiAccent.fg else scheme.onSurfaceVariant,
                    dark = dark,
                    trailingContent = {
                        MintSwitch(
                            checked = state.enabled,
                            onCheckedChange = onToggleEnabled,
                            dark = dark,
                            modifier = Modifier.testTag("ai-settings-enabled-switch"),
                        )
                    },
                    onClick = { onToggleEnabled(!state.enabled) },
                )
            }

            // 2. 预设供应商单选胶囊组
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "服务商预设",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    state.presets.forEach { preset ->
                        val selected = state.presetId == preset.id
                        MintChip(
                            label = preset.displayName,
                            selected = selected,
                            style = if (selected) MintChipStyle.Selected else MintChipStyle.Standard,
                            role = Role.RadioButton,
                            dark = dark,
                            modifier = Modifier.testTag("ai-preset-${preset.id}"),
                            onClick = { onSelectPreset(preset) },
                        )
                    }
                }
            }

            // 3. 接口参数表单（大圆角 + 清晰中文标签）
            MintGroupedSection(dark = dark, containerColor = mintCardSurface(dark)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AiInputBlock(
                        label = "API 接口地址 (Base URL)",
                        helper = activePreset?.defaultBaseUrl?.takeIf { it.isNotBlank() }?.let {
                            "推荐：$it"
                        } ?: "填写兼容 OpenAI Chat Completions 的接口根路径",
                        value = state.baseUrl,
                        onValueChange = onBaseUrlChange,
                        placeholder = "例如 https://api.deepseek.com/v1",
                        dark = dark,
                        tag = "ai-settings-base-url",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        trailingActionLabel = if (
                            activePreset != null &&
                            activePreset.defaultBaseUrl.isNotBlank() &&
                            state.baseUrl != activePreset.defaultBaseUrl
                        ) {
                            "恢复默认"
                        } else {
                            null
                        },
                        onTrailingActionClick = {
                            if (activePreset != null && activePreset.defaultBaseUrl.isNotBlank()) {
                                onBaseUrlChange(activePreset.defaultBaseUrl)
                            }
                        },
                    )

                    MintGroupedDivider(dark = dark, startIndent = 0.dp)

                    AiInputBlock(
                        label = "模型名称 (Model)",
                        helper = activePreset?.defaultModel?.takeIf { it.isNotBlank() }?.let {
                            "推荐：$it"
                        } ?: "填写服务商提供的完整模型 ID",
                        value = state.model,
                        onValueChange = onModelChange,
                        placeholder = "例如 deepseek-chat / gpt-4o-mini",
                        dark = dark,
                        tag = "ai-settings-model",
                    )

                    MintGroupedDivider(dark = dark, startIndent = 0.dp)

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        AiInputBlock(
                            label = if (state.requiresApiKey) "API Key（本地加密保存）" else "API Key（可选 · 本地服务可留空）",
                            helper = if (state.hasSavedApiKey && state.apiKeyDraft.isBlank()) {
                                "留空则继续沿用已保存密钥；填入新密钥将在保存后替换"
                            } else if (!state.requiresApiKey) {
                                "Ollama 等本地模型通常无需密钥"
                            } else {
                                "仅用于本机直连你填写的接口地址"
                            },
                            value = state.apiKeyDraft,
                            onValueChange = onApiKeyDraftChange,
                            placeholder = if (state.hasSavedApiKey) {
                                "已保存密钥，输入新 Key 可覆盖"
                            } else if (state.requiresApiKey) {
                                "sk-..."
                            } else {
                                "可留空"
                            },
                            dark = dark,
                            tag = "ai-settings-api-key",
                            visualTransformation = if (revealApiKey) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingActionLabel = if (revealApiKey) "隐藏" else "显示",
                            onTrailingActionClick = { revealApiKey = !revealApiKey },
                        )

                        AnimatedVisibility(visible = state.hasSavedApiKey) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(scheme.primaryContainer.copy(alpha = if (dark) 0.5f else 0.72f))
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = MintIcons.Check,
                                    contentDescription = null,
                                    tint = scheme.primary,
                                    modifier = Modifier.size(15.dp),
                                )
                                Text(
                                    text = "已安全加密保存 (${state.maskedApiKey.ifBlank { "****" }})",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = scheme.onPrimaryContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = "清除密钥",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = mintDestructiveColor(dark),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            role = Role.Button,
                                            onClick = onClearSavedApiKey,
                                        )
                                        .padding(horizontal = 6.dp, vertical = 3.dp)
                                        .testTag("ai-settings-clear-key"),
                                )
                            }
                        }
                    }
                }
            }

            // 4. 一键测试连通性 + 结果反馈卡
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (state.testingConnection) mintSecondaryFill(dark) else scheme.primary,
                            RoundedCornerShape(14.dp),
                        )
                        .clickable(
                            enabled = !state.testingConnection,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClick = onTestConnection,
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .testTag("ai-settings-test-button"),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.testingConnection) {
                        MintActivityIndicator(color = scheme.primary, sizeDp = 17.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "正在测试连通性…",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = scheme.onSurface,
                        )
                    } else {
                        Icon(
                            imageVector = MintIcons.Sparkles,
                            contentDescription = null,
                            tint = scheme.onPrimary,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "测试连通性",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = scheme.onPrimary,
                        )
                    }
                }

                state.lastTestSuccess?.let { ok ->
                    val tint = if (ok) scheme.primary else mintDestructiveColor(dark)
                    val bg = if (ok) {
                        scheme.primary.copy(alpha = if (dark) 0.16f else 0.10f)
                    } else {
                        mintDestructiveColor(dark).copy(alpha = if (dark) 0.16f else 0.10f)
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(bg)
                            .border(0.6.dp, tint.copy(alpha = 0.28f), RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                            .testTag("ai-settings-test-result"),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = if (ok) MintIcons.Check else MintIcons.Close,
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = if (ok) "连接正常" else "连接未通过",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = tint,
                                modifier = Modifier.weight(1f),
                            )
                            if (ok && state.lastLatencyMs != null) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(tint.copy(alpha = 0.14f))
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                ) {
                                    Text(
                                        text = "${state.lastLatencyMs} ms",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = tint,
                                    )
                                }
                            }
                        }
                        if (!state.lastTestMessage.isNullOrBlank()) {
                            Text(
                                text = state.lastTestMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurface,
                            )
                        }
                        if (!ok) {
                            Text(
                                text = "排查建议：核对 Base URL（通常以 /v1 结尾）、模型名称拼写、API Key 权限及当前网络/本地端口可达性。",
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // 5. 底部隐私说明卡
            MintGroupedSection(
                dark = dark,
                containerColor = aiAccent.bg.copy(alpha = if (dark) 0.72f else 0.9f),
                modifier = Modifier.testTag("ai-settings-privacy-card"),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = MintIcons.Lock,
                        contentDescription = null,
                        tint = aiAccent.fg,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(16.dp),
                    )
                    Text(
                        text = "API Key 仅通过手机系统 AndroidKeyStore 加密保存在本地，绝不随导出备份文件泄漏；标记为『不参与 AI』的笔记与附件永远不会发给任何外部模型。",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurface,
                    )
                }
            }

            MintPillButton(
                label = "保存配置",
                primary = true,
                icon = MintIcons.Check,
                enabled = !state.testingConnection,
                dark = dark,
                modifier = Modifier.fillMaxWidth(),
                onClick = onSave,
            )
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun AiInputBlock(
    label: String,
    helper: String?,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    dark: Boolean,
    tag: String,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    trailingActionLabel: String? = null,
    onTrailingActionClick: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    val borderColor = if (focused) scheme.primary.copy(alpha = 0.75f) else mintDividerColor(dark)

    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = scheme.onSurface,
            )
            if (!trailingActionLabel.isNullOrBlank() && onTrailingActionClick != null) {
                Text(
                    text = trailingActionLabel,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                    color = scheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClick = onTrailingActionClick,
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .clip(shape)
                .background(mintSecondaryFill(dark), shape)
                .border(if (focused) 1.2.dp else 0.6.dp, borderColor, shape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    visualTransformation = visualTransformation,
                    keyboardOptions = keyboardOptions,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
                    cursorBrush = SolidColor(scheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(tag)
                        .onFocusChanged { focused = it.isFocused },
                )
            }
        }
        if (!helper.isNullOrBlank()) {
            Text(
                text = helper,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}
