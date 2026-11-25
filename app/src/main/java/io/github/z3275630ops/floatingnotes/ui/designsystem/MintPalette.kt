package io.github.z3275630ops.floatingnotes.ui.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

@Immutable
internal data class SemanticAccent(
    val fg: Color,
    val bg: Color,
)

internal fun mintColorScheme(dark: Boolean) = if (dark) darkColorScheme(
    primary = Color(0xFF5FBF95),
    onPrimary = Color(0xFF0E241B),
    primaryContainer = Color(0xFF1C342A),
    onPrimaryContainer = Color(0xFFBDF2D8),
    inversePrimary = Color(0xFF1F6B4F),
    secondary = Color(0xFFAEAEB2),
    onSecondary = Color(0xFF1C1C1E),
    secondaryContainer = Color(0xFF2C2C2E),
    onSecondaryContainer = Color(0xFFF2F2F7),
    tertiary = Color(0xFF9B8FF0),
    onTertiary = Color(0xFF1D1A33),
    tertiaryContainer = Color(0xFF231F3A),
    onTertiaryContainer = Color(0xFFE5E1FF),
    background = Color(0xFF121214),
    onBackground = Color(0xFFF2F2F7),
    surface = Color(0xFF1C1C1E),
    onSurface = Color(0xFFF2F2F7),
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFFAEAEB2),
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF242426),
    surfaceContainerHighest = Color(0xFF3A3A3C),
    outline = Color(0xFF8E8E93),
    outlineVariant = Color(0xFF2C2C2E),
    error = Color(0xFFF06A60),
) else lightColorScheme(
    primary = Color(0xFF1F6B4F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4F0EA),
    onPrimaryContainer = Color(0xFF17553F),
    inversePrimary = Color(0xFF5FBF95),
    secondary = Color(0xFF6E6E73),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEEEEF0),
    onSecondaryContainer = Color(0xFF1C1C1E),
    tertiary = Color(0xFF6B5BD6),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFECE9FB),
    onTertiaryContainer = Color(0xFF2D2468),
    background = Color(0xFFF5F5F7),
    onBackground = Color(0xFF1C1C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFFEEEEF0),
    onSurfaceVariant = Color(0xFF6E6E73),
    surfaceContainer = Color(0xFFEEEEF0),
    surfaceContainerHigh = Color(0xFFEEEEF0),
    surfaceContainerHighest = Color(0xFFE5E5EA),
    outline = Color(0xFF8E8E93),
    outlineVariant = Color(0xFFE5E5EA),
    error = Color(0xFFC8372D),
)

@JvmOverloads
internal fun mintTypography(fontScaleMultiplier: Float = 1.0f): Typography {
    val scale = fontScaleMultiplier.coerceIn(0.85f, 1.5f)
    fun scaled(spValue: Float): TextUnit = (spValue * scale).sp
    return Typography(
        displayLarge = TextStyle(
            fontSize = scaled(34f),
            lineHeight = scaled(40f),
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.4).sp,
            fontFeatureSettings = "tnum",
        ),
        headlineLarge = TextStyle(
            fontSize = scaled(28f),
            lineHeight = scaled(34f),
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.4).sp,
            fontFeatureSettings = "tnum",
        ),
        headlineMedium = TextStyle(
            fontSize = scaled(24f),
            lineHeight = scaled(32f),
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.2).sp,
            fontFeatureSettings = "tnum",
        ),
        headlineSmall = TextStyle(
            fontSize = scaled(20f),
            lineHeight = scaled(26f),
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.2).sp,
            fontFeatureSettings = "tnum",
        ),
        titleLarge = TextStyle(
            fontSize = scaled(20f),
            lineHeight = scaled(27f),
            fontWeight = FontWeight.SemiBold,
            fontFeatureSettings = "tnum",
        ),
        titleMedium = TextStyle(
            fontSize = scaled(18f),
            lineHeight = scaled(25f),
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.1).sp,
            fontFeatureSettings = "tnum",
        ),
        titleSmall = TextStyle(
            fontSize = scaled(16.5f),
            lineHeight = scaled(23f),
            fontWeight = FontWeight.SemiBold,
            fontFeatureSettings = "tnum",
        ),
        bodyLarge = TextStyle(
            fontSize = scaled(17.5f),
            lineHeight = scaled(28f),
            fontWeight = FontWeight.Normal,
            fontFeatureSettings = "tnum",
        ),
        bodyMedium = TextStyle(
            fontSize = scaled(15.5f),
            lineHeight = scaled(22.5f),
            fontWeight = FontWeight.Normal,
            fontFeatureSettings = "tnum",
        ),
        bodySmall = TextStyle(
            fontSize = scaled(14f),
            lineHeight = scaled(20f),
            fontWeight = FontWeight.Normal,
            fontFeatureSettings = "tnum",
        ),
        labelLarge = TextStyle(
            fontSize = scaled(16f),
            lineHeight = scaled(22f),
            fontWeight = FontWeight.SemiBold,
            fontFeatureSettings = "tnum",
        ),
        labelMedium = TextStyle(
            fontSize = scaled(14f),
            lineHeight = scaled(19f),
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.2.sp,
            fontFeatureSettings = "tnum",
        ),
        labelSmall = TextStyle(
            fontSize = scaled(13f),
            lineHeight = scaled(18f),
            fontWeight = FontWeight.Normal,
            fontFeatureSettings = "tnum",
        ),
    )
}

@Composable
internal fun mintFontScale(): Float {
    val typo = MaterialTheme.typography
    val bodyMed = typo.bodyMedium.fontSize
    if (!bodyMed.isSp || bodyMed.value <= 0f) return 1f
    if (bodyMed.value == 14f && typo.titleSmall.fontSize.value == 14f && typo.labelSmall.fontSize.value == 11f) {
        return 1f
    }
    return (bodyMed.value / 15.5f).coerceIn(0.85f, 1.5f)
}

internal fun Modifier.mintAmbient(dark: Boolean): Modifier = drawBehind {
    drawRect(if (dark) Color(0xFF121214) else Color(0xFFF5F5F7))
}

internal fun mintHighlight(dark: Boolean) = Brush.horizontalGradient(
    if (dark) listOf(Color(0xFF3E9E75), Color(0xFF2D855F))
    else listOf(Color(0xFF1F6B4F), Color(0xFF17553F)),
)

internal fun mintCanvasColor(dark: Boolean): Color =
    if (dark) Color(0xFF121214) else Color(0xFFF5F5F7)

internal fun mintCardSurface(dark: Boolean): Color =
    if (dark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)

internal fun mintElevatedSurface(dark: Boolean): Color =
    if (dark) Color(0xFF242426) else Color(0xFFFFFFFF)

internal fun mintSecondaryFill(dark: Boolean): Color =
    if (dark) Color(0xFF2C2C2E) else Color(0xFFEEEEF0)

internal fun mintPressedFill(dark: Boolean): Color =
    if (dark) Color(0xFF3A3A3C) else Color(0xFFE5E5EA)

internal fun mintDividerColor(dark: Boolean): Color =
    if (dark) Color(0xFF2C2C2E) else Color(0xFFE5E5EA)

internal fun mintStrongDividerColor(dark: Boolean): Color =
    if (dark) Color(0xFF3A3A3C) else Color(0xFFD1D1D6)

internal fun mintTertiaryTextColor(dark: Boolean): Color =
    if (dark) Color(0xFF8E8E93) else Color(0xFF8E8E93)

internal fun mintDisabledTextColor(dark: Boolean): Color =
    if (dark) Color(0xFF636366) else Color(0xFFC7C7CC)

internal fun mintDestructiveColor(dark: Boolean): Color =
    if (dark) Color(0xFFF06A60) else Color(0xFFC8372D)

internal fun mintTodoAccent(dark: Boolean): SemanticAccent =
    if (dark) SemanticAccent(Color(0xFFF2B44D), Color(0xFF332614))
    else SemanticAccent(Color(0xFFD98A1C), Color(0xFFFBF0DC))

internal fun mintReviewAccent(dark: Boolean): SemanticAccent =
    if (dark) SemanticAccent(Color(0xFFE5896B), Color(0xFF331F18))
    else SemanticAccent(Color(0xFFC2603F), Color(0xFFF7E6DE))

internal fun mintLinkAccent(dark: Boolean): SemanticAccent =
    if (dark) SemanticAccent(Color(0xFF74A3DB), Color(0xFF1A2736))
    else SemanticAccent(Color(0xFF3E6FA8), Color(0xFFE3ECF6))

internal fun mintAiAccent(dark: Boolean): SemanticAccent =
    if (dark) SemanticAccent(Color(0xFF9B8FF0), Color(0xFF231F3A))
    else SemanticAccent(Color(0xFF6B5BD6), Color(0xFFECE9FB))

internal fun mintHeatmapPalette(dark: Boolean): List<Color> = if (dark) {
    listOf(
        Color(0xFF2C2C2E),
        Color(0xFF1B382B),
        Color(0xFF25533E),
        Color(0xFF3B7F60),
        Color(0xFF5FBF95),
    )
} else {
    listOf(
        Color(0xFFEEEEF0),
        Color(0xFFCFE5D8),
        Color(0xFF9CCBB2),
        Color(0xFF5FA585),
        Color(0xFF1F6B4F),
    )
}
