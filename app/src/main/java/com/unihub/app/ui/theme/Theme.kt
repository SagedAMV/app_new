package com.unihub.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.unihub.app.core.prefs.ThemeMode

private val LightColors = lightColorScheme(
    primary = Sage,
    onPrimary = SageOn,
    primaryContainer = SageContainer,
    onPrimaryContainer = SageOnContainer,
    secondary = Sand,
    onSecondary = Color.White,
    secondaryContainer = SandContainer,
    onSecondaryContainer = SandOnContainer,
    tertiary = Slate,
    onTertiary = Color.White,
    tertiaryContainer = SlateContainer,
    onTertiaryContainer = SlateOnContainer,
    error = ClayError,
    onError = Color.White,
    errorContainer = ClayErrorContainer,
    onErrorContainer = ClayOnErrorContainer,
    background = PaperBackground,
    onBackground = InkOnPaper,
    surface = PaperSurface,
    onSurface = InkOnPaper,
    surfaceVariant = PaperSurfaceVariant,
    onSurfaceVariant = InkMuted,
    outline = PaperOutline,
    outlineVariant = Color(0xFFD8DCD3),
    inverseSurface = Color(0xFF333B37),
    inverseOnSurface = Color(0xFFECF0EC),
    inversePrimary = MintSoft
)

private val DarkColors = darkColorScheme(
    primary = MintSoft,
    onPrimary = MintOnSoft,
    primaryContainer = MintContainerDark,
    onPrimaryContainer = SageContainer,
    secondary = SandSoft,
    onSecondary = SandOnSoft,
    secondaryContainer = SandContainerDark,
    onSecondaryContainer = SandContainer,
    tertiary = SlateSoft,
    onTertiary = SlateOnSoft,
    tertiaryContainer = SlateContainerDark,
    onTertiaryContainer = SlateContainer,
    error = ClayErrorDark,
    onError = ClayOnErrorContainer,
    errorContainer = ClayErrorContainerDark,
    onErrorContainer = ClayErrorContainer,
    background = NightBackground,
    onBackground = LightOnNight,
    surface = NightSurface,
    onSurface = LightOnNight,
    surfaceVariant = NightSurfaceVariant,
    onSurfaceVariant = LightMuted,
    outline = NightOutline,
    outlineVariant = Color(0xFF3A423E),
    inverseSurface = Color(0xFFE0E5E1),
    inverseOnSurface = Color(0xFF2A312D),
    inversePrimary = Sage
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/**
 * سمة التطبيق. [mode] ثلاثي الحالات (نظام/فاتح/داكن) بدل الثنائية القديمة،
 * و[useDynamicColor] اختيارية لمحبي ألوان Material You على أندرويد 12+.
 */
@Composable
fun UniHubTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    useDynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = when {
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
