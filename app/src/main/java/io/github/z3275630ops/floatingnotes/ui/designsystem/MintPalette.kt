package io.github.z3275630ops.floatingnotes.ui.designsystem

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

internal fun mintColorScheme(dark: Boolean) = if (dark) darkColorScheme(
    primary = Color(0xFF95E5B9),
    onPrimary = Color(0xFF073822),
    primaryContainer = Color(0xFF155A3B),
    onPrimaryContainer = Color(0xFFD0FBE2),
    inversePrimary = Color(0xFF126543),
    secondary = Color(0xFFADCCB9),
    onSecondary = Color(0xFF203529),
    secondaryContainer = Color(0xFF2C4639),
    onSecondaryContainer = Color(0xFFD8F3E4),
    tertiary = Color(0xFFBCE095),
    onTertiary = Color(0xFF243613),
    tertiaryContainer = Color(0xFF354C24),
    onTertiaryContainer = Color(0xFFE0FAC4),
    background = Color(0xFF0D1914),
    onBackground = Color(0xFFE7F3EA),
    surface = Color(0xFF182720),
    onSurface = Color(0xFFE7F3EA),
    surfaceVariant = Color(0xFF33483D),
    onSurfaceVariant = Color(0xFFBED1C4),
    surfaceContainer = Color(0xFF1A2B22),
    surfaceContainerHigh = Color(0xFF22362B),
    surfaceContainerHighest = Color(0xFF2B4134),
    outline = Color(0xFF8CA598),
    outlineVariant = Color(0xFF435E50),
) else lightColorScheme(
    primary = Color(0xFF126543),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC4F3D9),
    onPrimaryContainer = Color(0xFF0C3825),
    inversePrimary = Color(0xFF95E5B9),
    secondary = Color(0xFF3D6758),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDAEFE5),
    onSecondaryContainer = Color(0xFF1C392E),
    tertiary = Color(0xFF4F652D),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE4F4C7),
    onTertiaryContainer = Color(0xFF2E4113),
    background = Color(0xFFF4F8F2),
    onBackground = Color(0xFF182B21),
    surface = Color(0xFFFAFDF9),
    onSurface = Color(0xFF182B21),
    surfaceVariant = Color(0xFFE0EDE3),
    onSurfaceVariant = Color(0xFF435B4D),
    surfaceContainer = Color(0xFFEBF4EB),
    surfaceContainerHigh = Color(0xFFE3EFE6),
    surfaceContainerHighest = Color(0xFFDAEBDF),
    outline = Color(0xFF687F70),
    outlineVariant = Color(0xFFB4C9BA),
)

internal fun mintAmbient(dark: Boolean) = Brush.linearGradient(
    if (dark) listOf(Color(0xFF1E4538), Color(0xFF0D1914), Color(0xFF142C24), Color(0xFF283920))
    else listOf(Color(0xFFBFEBD4), Color(0xFFF4F8F2), Color(0xFFE0F3EB), Color(0xFFE7F2C7)),
)

internal fun mintHighlight(dark: Boolean) = Brush.horizontalGradient(
    if (dark) listOf(Color(0xFF2B6B52), Color(0xFF375633))
    else listOf(Color(0xFFA4E7CD), Color(0xFFD8F4B9)),
)
