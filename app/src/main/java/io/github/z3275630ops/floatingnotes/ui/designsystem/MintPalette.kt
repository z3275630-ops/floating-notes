package io.github.z3275630ops.floatingnotes.ui.designsystem

import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
    secondary = Color(0xFFA39E93),
    onSecondary = Color(0xFF1C1C1A),
    secondaryContainer = Color(0xFF26251F),
    onSecondaryContainer = Color(0xFFEDEBE6),
    tertiary = Color(0xFF9B8FF0),
    onTertiary = Color(0xFF1D1A33),
    tertiaryContainer = Color(0xFF231F3A),
    onTertiaryContainer = Color(0xFFE5E1FF),
    background = Color(0xFF121211),
    onBackground = Color(0xFFEDEBE6),
    surface = Color(0xFF1C1C1A),
    onSurface = Color(0xFFEDEBE6),
    surfaceVariant = Color(0xFF26251F),
    onSurfaceVariant = Color(0xFFA39E93),
    surfaceContainer = Color(0xFF1C1C1A),
    surfaceContainerHigh = Color(0xFF26251F),
    surfaceContainerHighest = Color(0xFF302E28),
    outline = Color(0xFF6E6A61),
    outlineVariant = Color(0xFF2E2D27),
    error = Color(0xFFF06A60),
) else lightColorScheme(
    primary = Color(0xFF1F6B4F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4F0EA),
    onPrimaryContainer = Color(0xFF17553F),
    inversePrimary = Color(0xFF5FBF95),
    secondary = Color(0xFF6B675F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEFEDE7),
    onSecondaryContainer = Color(0xFF1C1B19),
    tertiary = Color(0xFF6B5BD6),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFECE9FB),
    onTertiaryContainer = Color(0xFF2D2468),
    background = Color(0xFFF6F4EF),
    onBackground = Color(0xFF1C1B19),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1B19),
    surfaceVariant = Color(0xFFEFEDE7),
    onSurfaceVariant = Color(0xFF6B675F),
    surfaceContainer = Color(0xFFEFEDE7),
    surfaceContainerHigh = Color(0xFFEFEDE7),
    surfaceContainerHighest = Color(0xFFE6E3DB),
    outline = Color(0xFFA19C92),
    outlineVariant = Color(0xFFE7E4DC),
    error = Color(0xFFC8372D),
)

internal fun mintTypography(): Typography = Typography(
    displayLarge = TextStyle(
        fontSize = 28.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.4).sp,
        fontFeatureSettings = "tnum",
    ),
    headlineLarge = TextStyle(
        fontSize = 36.sp,
        lineHeight = 40.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp,
        fontFeatureSettings = "tnum",
    ),
    headlineMedium = TextStyle(
        fontSize = 24.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.2).sp,
        fontFeatureSettings = "tnum",
    ),
    headlineSmall = TextStyle(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.2).sp,
        fontFeatureSettings = "tnum",
    ),
    titleLarge = TextStyle(
        fontSize = 18.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum",
    ),
    titleMedium = TextStyle(
        fontSize = 17.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.1).sp,
        fontFeatureSettings = "tnum",
    ),
    titleSmall = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum",
    ),
    bodyLarge = TextStyle(
        fontSize = 17.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Normal,
        fontFeatureSettings = "tnum",
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
        fontFeatureSettings = "tnum",
    ),
    bodySmall = TextStyle(
        fontSize = 13.sp,
        lineHeight = 19.sp,
        fontWeight = FontWeight.Normal,
        fontFeatureSettings = "tnum",
    ),
    labelLarge = TextStyle(
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum",
    ),
    labelMedium = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.2.sp,
        fontFeatureSettings = "tnum",
    ),
    labelSmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Normal,
        fontFeatureSettings = "tnum",
    ),
)

internal fun Modifier.mintAmbient(dark: Boolean): Modifier = drawBehind {
    drawRect(if (dark) Color(0xFF121211) else Color(0xFFF6F4EF))
}

internal fun mintHighlight(dark: Boolean) = Brush.horizontalGradient(
    if (dark) listOf(Color(0xFF3E9E75), Color(0xFF2D855F))
    else listOf(Color(0xFF1F6B4F), Color(0xFF17553F)),
)

internal fun mintCanvasColor(dark: Boolean): Color =
    if (dark) Color(0xFF121211) else Color(0xFFF6F4EF)

internal fun mintCardSurface(dark: Boolean): Color =
    if (dark) Color(0xFF1C1C1A) else Color(0xFFFFFFFF)

internal fun mintElevatedSurface(dark: Boolean): Color =
    if (dark) Color(0xFF232220) else Color(0xFFFFFFFF)

internal fun mintSecondaryFill(dark: Boolean): Color =
    if (dark) Color(0xFF26251F) else Color(0xFFEFEDE7)

internal fun mintPressedFill(dark: Boolean): Color =
    if (dark) Color(0xFF302E28) else Color(0xFFE6E3DB)

internal fun mintDividerColor(dark: Boolean): Color =
    if (dark) Color(0xFF2E2D27) else Color(0xFFE7E4DC)

internal fun mintStrongDividerColor(dark: Boolean): Color =
    if (dark) Color(0xFF3A3831) else Color(0xFFD9D5CB)

internal fun mintTertiaryTextColor(dark: Boolean): Color =
    if (dark) Color(0xFF6E6A61) else Color(0xFFA19C92)

internal fun mintDisabledTextColor(dark: Boolean): Color =
    if (dark) Color(0xFF57534B) else Color(0xFFC4BFB5)

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
        Color(0xFF26251F),
        Color(0xFF1B382B),
        Color(0xFF25533E),
        Color(0xFF3B7F60),
        Color(0xFF5FBF95),
    )
} else {
    listOf(
        Color(0xFFEFEDE7),
        Color(0xFFCFE5D8),
        Color(0xFF9CCBB2),
        Color(0xFF5FA585),
        Color(0xFF1F6B4F),
    )
}
