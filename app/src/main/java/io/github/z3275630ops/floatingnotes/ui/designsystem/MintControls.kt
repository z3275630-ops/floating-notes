package io.github.z3275630ops.floatingnotes.ui.designsystem

import android.animation.ValueAnimator
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal fun mintAnimatorsEnabled(): Boolean =
    runCatching { ValueAnimator.areAnimatorsEnabled() }.getOrDefault(true)

internal fun <T> mintSpringSpec(): AnimationSpec<T> =
    if (mintAnimatorsEnabled()) spring(dampingRatio = 0.85f, stiffness = 500f) else snap()

@Composable
internal fun rememberMintPressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.985f,
): Float {
    val pressed by interactionSource.collectIsPressedAsState()
    val target = if (pressed) pressedScale else 1f
    val scale by animateFloatAsState(
        targetValue = target,
        animationSpec = mintSpringSpec(),
        label = "mint-press-scale",
    )
    return if (mintAnimatorsEnabled()) scale else target
}

/**
 * Apple HIG Inset Grouped container: crisp borderless paper surface with zero outer border or shadow.
 */
@Composable
internal fun MintGroupedSection(
    dark: Boolean,
    modifier: Modifier = Modifier,
    shapeRadius: Dp = 16.dp,
    containerColor: Color = mintCardSurface(dark),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(shapeRadius))
            .background(containerColor),
        content = content,
    )
}

@Composable
internal fun MintGroupedDivider(
    dark: Boolean,
    startIndent: Dp = 16.dp,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = startIndent)
            .height(0.5.dp)
            .background(mintDividerColor(dark)),
    )
}

@Composable
internal fun MintGroupedRow(
    label: String,
    dark: Boolean,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    iconTint: Color? = null,
    trailingText: String? = null,
    showChevron: Boolean = false,
    selected: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val titleColor = when {
        !enabled -> scheme.onSurfaceVariant.copy(alpha = 0.45f)
        destructive -> mintDestructiveColor(dark)
        else -> scheme.onSurface
    }
    val rowBg = if (selected) scheme.primary.copy(alpha = if (dark) 0.14f else 0.08f) else Color.Transparent
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 50.dp)
            .background(rowBg)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        enabled = enabled,
                        interactionSource = interactionSource,
                        indication = null,
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = iconTint ?: when {
                    destructive -> mintDestructiveColor(dark)
                    selected -> scheme.primary
                    else -> scheme.primary
                },
                modifier = Modifier.size(19.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = titleColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!trailingText.isNullOrBlank()) {
            Text(
                text = trailingText,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailingContent?.invoke(this)
        if (selected) {
            Icon(
                imageVector = MintIcons.Check,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(17.dp),
            )
        } else if (showChevron) {
            Icon(
                imageVector = MintIcons.ChevronRight,
                contentDescription = null,
                tint = scheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

/**
 * Apple HIG Bottom Sheet hosted inside a Compose Dialog so [isDialog] semantics remain intact.
 * Uses top grabber + 3-zone iOS navigation header (Cancel | Title | Primary Done/Save).
 */
@Composable
internal fun MintBottomSheet(
    onDismissRequest: () -> Unit,
    dark: Boolean,
    backdrop: GlassBackdrop = rememberGlassBackdrop(),
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String? = null,
    leadingLabel: String? = "取消",
    onLeadingClick: () -> Unit = onDismissRequest,
    trailingLabel: String? = null,
    onTrailingClick: (() -> Unit)? = null,
    trailingEnabled: Boolean = true,
    trailingTag: String? = null,
    trailingDestructive: Boolean = false,
    maxWidth: Dp = 600.dp,
    maxHeightFraction: Float = 0.88f,
    dismissOnClickOutside: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * maxHeightFraction).dp
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = dismissOnClickOutside,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = if (dark) 0.44f else 0.24f))
                    .then(
                        if (dismissOnClickOutside) {
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onDismissRequest,
                            )
                        } else Modifier
                    )
                    .clearAndSetSemantics { },
            )
            GlassSurface(
                backdrop = backdrop,
                tier = GlassTier.Reduced,
                dark = dark,
                shapeRadius = 28.dp,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                elevatedModal = true,
                modifier = modifier
                    .align(Alignment.BottomCenter)
                    .widthIn(max = maxWidth)
                    .fillMaxWidth()
                    .heightIn(max = maxSheetHeight),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp)
                        .padding(top = 8.dp, bottom = 18.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(bottom = 6.dp)
                            .size(width = 36.dp, height = 5.dp)
                            .clip(RoundedCornerShape(50))
                            .background(scheme.onSurfaceVariant.copy(alpha = 0.28f)),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (!leadingLabel.isNullOrBlank()) {
                                TextButton(
                                    onClick = onLeadingClick,
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                                ) {
                                    Text(
                                        text = leadingLabel,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = scheme.primary,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                        Column(
                            modifier = Modifier.weight(1.8f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = (-0.2).sp,
                                ),
                                color = scheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                            if (!subtitle.isNullOrBlank()) {
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            if (!trailingLabel.isNullOrBlank() && onTrailingClick != null) {
                                TextButton(
                                    onClick = onTrailingClick,
                                    enabled = trailingEnabled,
                                    modifier = Modifier.then(
                                        if (trailingTag != null) Modifier.testTag(trailingTag) else Modifier
                                    ),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                                ) {
                                    Text(
                                        text = trailingLabel,
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                        color = when {
                                            !trailingEnabled -> scheme.onSurfaceVariant.copy(alpha = 0.4f)
                                            trailingDestructive -> mintDestructiveColor(dark)
                                            else -> scheme.primary
                                        },
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    content()
                }
            }
        }
    }
}

/**
 * Compact Apple-style centered Alert dialog for destructive confirmations & quick prompts.
 */
@Composable
internal fun MintAlertDialog(
    onDismissRequest: () -> Unit,
    dark: Boolean,
    title: String,
    confirmLabel: String,
    onConfirmAction: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: GlassBackdrop = rememberGlassBackdrop(),
    subtitle: String? = null,
    dismissLabel: String? = "取消",
    onDismissAction: () -> Unit = onDismissRequest,
    confirmEnabled: Boolean = true,
    destructive: Boolean = false,
    dismissOnClickOutside: Boolean = true,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = dismissOnClickOutside,
        ),
    ) {
        GlassSurface(
            backdrop = backdrop,
            tier = GlassTier.Reduced,
            dark = dark,
            shapeRadius = 22.dp,
            elevatedModal = true,
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .widthIn(max = 318.dp)
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = scheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    if (content != null) {
                        Spacer(Modifier.height(6.dp))
                        content()
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(mintDividerColor(dark)),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!dismissLabel.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    role = Role.Button,
                                    onClick = onDismissAction,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = dismissLabel,
                                style = MaterialTheme.typography.bodyLarge,
                                color = scheme.primary,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .width(0.5.dp)
                                .height(48.dp)
                                .background(mintDividerColor(dark)),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .clickable(
                                enabled = confirmEnabled,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Button,
                                onClick = onConfirmAction,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = confirmLabel,
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = when {
                                !confirmEnabled -> scheme.onSurfaceVariant.copy(alpha = 0.4f)
                                destructive -> mintDestructiveColor(dark)
                                else -> scheme.primary
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Unified frosted Mint Glass modal dialog kept for legacy/external callers.
 */
@Composable
internal fun MintGlassDialog(
    onDismissRequest: () -> Unit,
    dark: Boolean,
    backdrop: GlassBackdrop = rememberGlassBackdrop(),
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    maxWidth: Dp = 440.dp,
    dismissOnClickOutside: Boolean = true,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = dismissOnClickOutside,
        ),
    ) {
        GlassSurface(
            backdrop = backdrop,
            tier = GlassTier.Reduced,
            dark = dark,
            shapeRadius = 28.dp,
            elevatedModal = true,
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 18.dp)
                .widthIn(max = maxWidth)
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 20.dp),
            ) {
                if (!title.isNullOrBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = (-0.2).sp,
                                ),
                                color = scheme.onSurface,
                            )
                            if (!subtitle.isNullOrBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
                content()
                if (actions != null) {
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions,
                    )
                }
            }
        }
    }
}

@Composable
internal fun MintTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    dark: Boolean,
    modifier: Modifier = Modifier,
    tag: String? = null,
    singleLine: Boolean = true,
    leadingIcon: ImageVector? = null,
    clearDescription: String = "清空输入",
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val scheme = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val borderColor by animateColorAsState(
        targetValue = if (focused) scheme.primary.copy(alpha = 0.75f) else Color.Transparent,
        animationSpec = mintSpringSpec(),
        label = "mint-field-border",
    )
    val container = mintSecondaryFill(dark)
    val shape = RoundedCornerShape(12.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(shape)
            .background(container, shape)
            .then(if (focused) Modifier.border(1.2.dp, borderColor, shape) else Modifier)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = if (focused) scheme.primary else scheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .clipToBounds()
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.78f),
                    maxLines = if (singleLine) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = singleLine,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (tag != null) Modifier.testTag(tag) else Modifier)
                    .onFocusChanged { focused = it.isFocused },
            )
        }
        if (value.isNotEmpty()) {
            MintIconButton(
                onClick = { onValueChange("") },
                contentDescription = clearDescription,
                dark = dark,
                icon = MintIcons.Close,
                iconSize = 16.dp,
                containerColor = Color.Transparent,
            )
        } else {
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
internal fun MintCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    dark: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val scale = if (enabled && onCheckedChange != null) rememberMintPressScale(interactionSource, 0.94f) else 1f
    val fill by animateColorAsState(
        targetValue = when {
            checked -> scheme.primary
            dark -> Color(0xFF1F3229)
            else -> Color(0xFFFFFFFF).copy(alpha = 0.85f)
        },
        animationSpec = mintSpringSpec(),
        label = "mint-checkbox-fill",
    )
    val borderColor by animateColorAsState(
        targetValue = if (checked) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.45f),
        animationSpec = mintSpringSpec(),
        label = "mint-checkbox-border",
    )

    Box(
        modifier = modifier
            .then(
                if (onCheckedChange != null) {
                    Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .toggleable(
                            value = checked,
                            enabled = enabled,
                            interactionSource = interactionSource,
                            indication = null,
                            role = Role.Checkbox,
                            onValueChange = onCheckedChange,
                        )
                } else {
                    Modifier
                }
            )
            .then(
                if (!contentDescription.isNullOrBlank()) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                }
            )
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.5f
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(fill, RoundedCornerShape(7.dp))
                .border(1.5.dp, borderColor, RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    imageVector = MintIcons.Check,
                    contentDescription = null,
                    tint = scheme.onPrimary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
internal fun MintSelectionRow(
    label: String,
    selected: Boolean,
    dark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    trailingBadge: String? = null,
    multiSelect: Boolean = false,
    semanticsRole: Role = if (multiSelect) Role.Checkbox else Role.RadioButton,
) {
    val scheme = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val fillColor by animateColorAsState(
        targetValue = if (selected) scheme.primary.copy(alpha = if (dark) 0.14f else 0.08f) else Color.Transparent,
        animationSpec = mintSpringSpec(),
        label = "mint-row-fill",
    )

    val rowModifier = modifier
        .fillMaxWidth()
        .defaultMinSize(minHeight = 48.dp)
        .background(fillColor)
        .then(
            if (semanticsRole == Role.Checkbox) {
                Modifier.toggleable(
                    value = selected,
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Checkbox,
                    onValueChange = { onClick() },
                )
            } else {
                Modifier.selectable(
                    selected = selected,
                    interactionSource = interactionSource,
                    indication = null,
                    role = semanticsRole,
                    onClick = onClick,
                )
            }
        )
        .padding(horizontal = 16.dp, vertical = 11.dp)

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = if (selected) scheme.primary else scheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = if (selected) scheme.onSurface else scheme.onSurface.copy(alpha = 0.92f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!trailingBadge.isNullOrBlank()) {
            Text(
                text = trailingBadge,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Icon(
                imageVector = MintIcons.Check,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(18.dp),
            )
        } else {
            Spacer(Modifier.size(18.dp))
        }
    }
}

internal enum class MintChipStyle {
    Standard,
    Selected,
    ActiveFilter,
    SmartFolder,
}

@Composable
internal fun MintChip(
    label: String,
    onClick: () -> Unit,
    dark: Boolean,
    modifier: Modifier = Modifier,
    style: MintChipStyle = MintChipStyle.Standard,
    selected: Boolean = style == MintChipStyle.Selected || style == MintChipStyle.ActiveFilter,
    leadingIcon: ImageVector? = null,
    trailingCount: Int? = null,
    removable: Boolean = false,
    maxLines: Int = 1,
    role: Role = Role.Button,
) {
    val scheme = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val scale = rememberMintPressScale(interactionSource, 0.97f)
    val shape = RoundedCornerShape(50)

    val containerColor = when (style) {
        MintChipStyle.ActiveFilter, MintChipStyle.Selected ->
            if (dark) Color(0xFF23523E) else Color(0xFFD7F2E4)
        MintChipStyle.SmartFolder ->
            if (dark) Color(0xFF1D3027) else Color(0xFFFBFDF9)
        MintChipStyle.Standard ->
            if (dark) Color(0xFF192921) else Color(0xFFF3F8F4)
    }
    val contentColor = when (style) {
        MintChipStyle.ActiveFilter, MintChipStyle.Selected ->
            if (dark) Color(0xFFAAF0CB) else Color(0xFF0E5A3A)
        MintChipStyle.SmartFolder -> scheme.onSurface
        MintChipStyle.Standard -> scheme.onSurfaceVariant
    }
    val borderColor = when (style) {
        MintChipStyle.ActiveFilter, MintChipStyle.Selected ->
            scheme.primary.copy(alpha = if (dark) 0.7f else 0.48f)
        MintChipStyle.SmartFolder ->
            scheme.outline.copy(alpha = if (dark) 0.48f else 0.35f)
        MintChipStyle.Standard ->
            scheme.outline.copy(alpha = if (dark) 0.32f else 0.24f)
    }

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(
                if (role == Role.Tab || role == Role.RadioButton) {
                    Modifier.selectable(
                        selected = selected,
                        interactionSource = interactionSource,
                        indication = null,
                        role = role,
                        onClick = onClick,
                    )
                } else {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        role = role,
                        onClick = onClick,
                    )
                }
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = 36.dp)
                .clip(shape)
                .background(containerColor, shape)
                .then(
                    if (selected) Modifier.border(0.6.dp, borderColor, shape)
                    else Modifier
                )
                .padding(
                    start = if (leadingIcon != null) 10.dp else 12.dp,
                    end = if (removable || trailingCount != null) 9.dp else 12.dp,
                    top = 6.dp,
                    bottom = 6.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = if (style == MintChipStyle.SmartFolder) scheme.primary else contentColor,
                    modifier = Modifier.size(15.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = if (selected || style == MintChipStyle.SmartFolder) FontWeight.SemiBold else FontWeight.Medium,
                ),
                color = contentColor,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
            if (trailingCount != null) {
                Box(
                    modifier = Modifier
                        .clip(shape)
                        .background(contentColor.copy(alpha = 0.12f))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = trailingCount.toString(),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = contentColor,
                    )
                }
            }
            if (removable) {
                Icon(
                    imageVector = MintIcons.Close,
                    contentDescription = null,
                    tint = contentColor.copy(alpha = 0.85f),
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

@Composable
internal fun MintSegmentedBar(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    dark: Boolean,
    modifier: Modifier = Modifier,
    badges: List<Int?> = emptyList(),
    optionTags: List<String> = emptyList(),
) {
    if (options.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    val trackColor = if (dark) Color(0xFF172720) else Color(0xFFE5EFE8)
    val thumbColor = if (dark) Color(0xFF285845) else Color(0xFFFFFFFF)

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 44.dp)
            .clip(RoundedCornerShape(50))
            .background(trackColor, RoundedCornerShape(50))
            .padding(3.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(36.dp)) {
            val count = options.size
            val segmentWidth = maxWidth / count
            val safeIndex = selectedIndex.coerceIn(0, count - 1)
            val indicatorOffset by animateDpAsState(
                targetValue = segmentWidth * safeIndex,
                animationSpec = mintSpringSpec(),
                label = "mint-segment-offset",
            )
            val activeOffset = if (mintAnimatorsEnabled()) indicatorOffset else segmentWidth * safeIndex

            Box(
                modifier = Modifier
                    .offset(x = activeOffset)
                    .width(segmentWidth)
                    .fillMaxHeight()
                    .then(
                        if (!dark) Modifier.shadow(2.dp, RoundedCornerShape(50), ambientColor = Color(0x14000000), spotColor = Color(0x14000000))
                        else Modifier
                    )
                    .clip(RoundedCornerShape(50))
                    .background(thumbColor, RoundedCornerShape(50)),
            )
            Row(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                options.forEachIndexed { index, label ->
                    val selected = index == safeIndex
                    val interactionSource = remember { MutableInteractionSource() }
                    val badge = badges.getOrNull(index)
                    val optionTag = optionTags.getOrNull(index)
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .then(if (!optionTag.isNullOrBlank()) Modifier.testTag(optionTag) else Modifier)
                            .clip(RoundedCornerShape(50))
                            .selectable(
                                selected = selected,
                                interactionSource = interactionSource,
                                indication = null,
                                role = Role.Tab,
                                onClick = { onSelect(index) },
                            )
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            ),
                            color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (badge != null && badge > 0) {
                            Spacer(Modifier.width(5.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        if (selected) scheme.primary.copy(alpha = 0.16f)
                                        else scheme.onSurfaceVariant.copy(alpha = 0.12f),
                                    )
                                    .padding(horizontal = 6.dp, vertical = 1.dp),
                            ) {
                                Text(
                                    text = badge.toString(),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (selected) scheme.primary else scheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun MintIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    dark: Boolean,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    iconSize: Dp = 20.dp,
    tint: Color? = null,
    containerColor: Color? = Color.Transparent,
    borderColor: Color? = null,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val scale = rememberMintPressScale(interactionSource, 0.94f)
    val fill = containerColor ?: Color.Transparent
    val resolvedTint = (tint ?: scheme.onSurface).copy(alpha = if (enabled) 1f else 0.38f)

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .then(if (fill != Color.Transparent) Modifier.background(fill, CircleShape) else Modifier)
            .then(
                if (borderColor != null) Modifier.border(0.8.dp, borderColor, CircleShape)
                else Modifier
            )
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = resolvedTint,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
internal fun MintPillButton(
    label: String,
    onClick: () -> Unit,
    dark: Boolean,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val scale = if (enabled) rememberMintPressScale(interactionSource, 0.97f) else 1f
    val shape = RoundedCornerShape(50)

    val containerBrush = when {
        !enabled && primary -> SolidColor(if (dark) Color(0xFF22352B) else Color(0xFFDDE8E1))
        !enabled -> SolidColor(if (dark) Color(0xFF192821) else Color(0xFFEBF2EE))
        primary -> Brush.horizontalGradient(
            if (dark) listOf(Color(0xFF2B6B52), Color(0xFF375633))
            else listOf(Color(0xFF126543), Color(0xFF1F7A53)),
        )
        destructive -> SolidColor(scheme.error.copy(alpha = if (dark) 0.24f else 0.12f))
        else -> SolidColor(if (dark) Color(0xFF1D3027) else Color(0xFFF1F7F3))
    }
    val labelColor = when {
        !enabled -> if (dark) Color(0xFF7E9487) else Color(0xFF6E8377)
        primary -> Color.White
        destructive -> scheme.error
        else -> scheme.onSurface
    }

    val border = when {
        !enabled && primary -> if (dark) Color.White.copy(alpha = 0.12f) else Color(0xFFB6C9BE)
        !enabled -> scheme.outline.copy(alpha = if (dark) 0.24f else 0.22f)
        primary -> Color.White.copy(alpha = if (dark) 0.22f else 0.35f)
        destructive -> scheme.error.copy(alpha = 0.35f)
        else -> scheme.outline.copy(alpha = if (dark) 0.42f else 0.30f)
    }

    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(containerBrush, shape)
            .then(
                if (!primary && !enabled) Modifier.border(0.6.dp, border, shape)
                else if (destructive) Modifier.border(0.6.dp, border, shape)
                else Modifier
            )
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 15.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = labelColor,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
