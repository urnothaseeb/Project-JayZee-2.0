package com.haseeb.jayzee

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Warm paper background, ink-navy for reminders, amber for tasks, green for done.
// Change the look of the whole app by editing these two palettes.
private val LightColors = lightColorScheme(
    primary = Color(0xFF1F3A5F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE6F2),
    onPrimaryContainer = Color(0xFF0F2238),
    secondary = Color(0xFFB86A1B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFBE9D2),
    onSecondaryContainer = Color(0xFF4A2A05),
    tertiary = Color(0xFF2E7D5B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD5EFE2),
    onTertiaryContainer = Color(0xFF0C3524),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFADAD7),
    onErrorContainer = Color(0xFF5C1B17),
    background = Color(0xFFF6F4EF),
    onBackground = Color(0xFF1B1C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1C1E),
    surfaceVariant = Color(0xFFECE9E2),
    onSurfaceVariant = Color(0xFF5C6068),
    outline = Color(0xFFA9A59C),
    outlineVariant = Color(0xFFE2DED6),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBFAF6),
    surfaceContainer = Color(0xFFF6F4EF),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFECE9E2),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9FC0E8),
    onPrimary = Color(0xFF0B2440),
    primaryContainer = Color(0xFF27425F),
    onPrimaryContainer = Color(0xFFD6E6F8),
    secondary = Color(0xFFF0B36B),
    onSecondary = Color(0xFF3F2503),
    secondaryContainer = Color(0xFF5A3A10),
    onSecondaryContainer = Color(0xFFFFE2BD),
    tertiary = Color(0xFF7FD1A9),
    onTertiary = Color(0xFF05301F),
    tertiaryContainer = Color(0xFF1D4A37),
    onTertiaryContainer = Color(0xFFBFEBD5),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF5C1B17),
    onErrorContainer = Color(0xFFFADAD7),
    background = Color(0xFF101418),
    onBackground = Color(0xFFE6E8EB),
    surface = Color(0xFF1A2027),
    onSurface = Color(0xFFE6E8EB),
    surfaceVariant = Color(0xFF232A32),
    onSurfaceVariant = Color(0xFFA3A9B2),
    outline = Color(0xFF5A636E),
    outlineVariant = Color(0xFF2A313A),
    surfaceContainerLowest = Color(0xFF0C1013),
    surfaceContainerLow = Color(0xFF14191E),
    surfaceContainer = Color(0xFF151A20),
    surfaceContainerHigh = Color(0xFF20272F),
    surfaceContainerHighest = Color(0xFF262E37),
)

@Composable
fun JayZeeTheme(content: @Composable () -> Unit) {
    val base = Typography()
    val type = base.copy(
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = type,
        content = content,
    )
}
