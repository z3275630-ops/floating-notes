package io.github.z3275630ops.floatingnotes.ui.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun GlassAction(
    label: String,
    dark: Boolean,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val target = if (enabled && pressed) .96f else 1f
    val scale by animateFloatAsState(
        targetValue = target,
        animationSpec = mintSpringSpec(),
        label = "glass-action-press",
    )
    val activeScale = if (mintAnimatorsEnabled()) scale else target
    val fill: Brush = if (enabled) {
        mintHighlight(dark)
    } else {
        SolidColor(if (dark) Color(0xFF22352B) else Color(0xFFDDE8E1))
    }
    val edge: Brush = if (enabled) {
        Brush.linearGradient(listOf(
            Color.White.copy(alpha = if (dark) .38f else .90f),
            Color.White.copy(alpha = if (dark) .08f else .30f),
        ))
    } else {
        SolidColor(if (dark) Color.White.copy(alpha = 0.12f) else Color(0xFFB6C9BE).copy(alpha = 0.85f))
    }
    val shadowTint = if (dark) Color(0x55040A07) else Color(0x28126543)
    val boxModifier = modifier
        .graphicsLayer { scaleX = activeScale; scaleY = activeScale }
        .let { base ->
            if (enabled) base.shadow(10.dp, shape, ambientColor = shadowTint, spotColor = shadowTint)
            else base
        }
        .clip(shape)
        .background(fill)
        .border(1.dp, edge, shape)
        .clickable(
            enabled = enabled,
            interactionSource = source,
            indication = null,
            role = Role.Button,
            onClick = onClick,
        )
        .heightIn(min = 48.dp)
        .padding(horizontal = 19.dp)
    Box(
        boxModifier,
        contentAlignment = Alignment.Center,
    ) {
        val textColor = if (enabled) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            if (dark) Color(0xFF7E9487) else Color(0xFF6E8377)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.size(17.dp),
                )
            }
            Text(
                text = label,
                color = textColor,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            )
        }
    }
}
