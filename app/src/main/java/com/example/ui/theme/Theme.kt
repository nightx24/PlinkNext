package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.example.data.model.AppThemeMode

@Immutable
data class PocketLinkSemanticColors(
    val isDark: Boolean,
    val amber: Color,
    val cyan: Color,
    val teal: Color,
    val violet: Color,
    val sky: Color,
    val emerald: Color,
    val cloudflare: Color,
    val cardBorder: Color,
)

val LocalPocketLinkDarkTheme = staticCompositionLocalOf { true }
val LocalPocketLinkColors = staticCompositionLocalOf {
    PocketLinkSemanticColors(
        isDark = true,
        amber = AmberAccent,
        cyan = CyanAccent,
        teal = TealAccent,
        violet = VioletAccent,
        sky = SkyAccent,
        emerald = EmeraldSuccess,
        cloudflare = CloudflareOrange,
        cardBorder = Color.White.copy(alpha = 0.07f),
    )
}

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF4760FA),
    onPrimary = Color.White,
    primaryContainer = IndigoContainerLight,
    onPrimaryContainer = Color(0xFF131F6B),
    secondary = CyanLightAccent,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD4F2F6),
    onSecondaryContainer = Color(0xFF00363D),
    tertiary = VioletLightAccent,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEDE8FF),
    onTertiaryContainer = Color(0xFF28127A),
    background = LightBackground,
    onBackground = Color(0xFF131726),
    surface = LightSurface,
    onSurface = Color(0xFF131726),
    surfaceVariant = LightSurfaceContainer,
    onSurfaceVariant = Color(0xFF525A74),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = LightSurfaceLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceHigh,
    surfaceContainerHighest = LightSurfaceHighest,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = Color(0xFFDC2626),
    onError = Color.White,
)

private val DarkColorScheme = darkColorScheme(
    primary = IndigoDarkPrimary,
    onPrimary = Color(0xFF0E153A),
    primaryContainer = IndigoContainerDark,
    onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = TealAccent,
    onSecondary = Color(0xFF002024),
    secondaryContainer = Color(0xFF12343A),
    onSecondaryContainer = Color(0xFF9AF0FA),
    tertiary = VioletAccent,
    onTertiary = Color.White,
    background = DarkBackground,
    onBackground = Color(0xFFF3F5FA),
    surface = DarkSurface,
    onSurface = Color(0xFFF3F5FA),
    surfaceVariant = DarkSurfaceContainer,
    onSurfaceVariant = Color(0xFFA0A7BA),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = DarkSurfaceLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceHigh,
    surfaceContainerHighest = DarkSurfaceHighest,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = CoralError,
    onError = Color(0xFF3E0005),
)

private val AmoledColorScheme = DarkColorScheme.copy(
    background = AmoledBackground,
    surface = AmoledBackground,
    surfaceContainerLowest = AmoledBackground,
    surfaceContainerLow = AmoledSurfaceLow,
    surfaceContainer = AmoledSurfaceContainer,
    surfaceContainerHigh = AmoledSurfaceHigh,
    surfaceContainerHighest = AmoledSurfaceHighest,
)

val PocketLinkShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun PocketLinkTheme(
    themeMode: AppThemeMode = AppThemeMode.DARK,
    amoled: Boolean = false,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val useDark = when (themeMode) {
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
        AppThemeMode.SYSTEM -> systemDark
    }
    val colorScheme = when {
        !useDark -> LightColorScheme
        amoled -> AmoledColorScheme
        else -> DarkColorScheme
    }
    val semanticColors = if (useDark) {
        PocketLinkSemanticColors(
            isDark = true,
            amber = AmberAccent,
            cyan = CyanAccent,
            teal = TealAccent,
            violet = VioletAccent,
            sky = SkyAccent,
            emerald = EmeraldSuccess,
            cloudflare = CloudflareOrange,
            cardBorder = Color.White.copy(alpha = 0.075f),
        )
    } else {
        PocketLinkSemanticColors(
            isDark = false,
            amber = AmberLightAccent,
            cyan = CyanLightAccent,
            teal = TealLightAccent,
            violet = VioletLightAccent,
            sky = SkyLightAccent,
            emerald = EmeraldLightSuccess,
            cloudflare = CloudflareLightOrange,
            cardBorder = Color(0xFF192348).copy(alpha = 0.09f),
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !useDark
                    isAppearanceLightNavigationBars = !useDark
                }
            }
        }
    }

    CompositionLocalProvider(
        LocalPocketLinkDarkTheme provides useDark,
        LocalPocketLinkColors provides semanticColors,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = PocketLinkTypography,
            shapes = PocketLinkShapes,
            content = content,
        )
    }
}
