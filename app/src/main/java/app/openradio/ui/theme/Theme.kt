package app.openradio.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun Long.c() = Color(this)

@Immutable
data class NeumorphicStyle(
    val isDark: Boolean,
    val isFlatHighContrast: Boolean,
    val lightShadow: Color,
    val darkShadow: Color,
    val raisedTopLeft: Color,
    val raisedBottomRight: Color,
    val insetTopLeft: Color,
    val insetBottomRight: Color,
    val bevelLight: Color,
    val bevelDark: Color,
)

val LocalNeumorphicStyle = staticCompositionLocalOf {
    NeumorphicStyle(
        isDark = true,
        isFlatHighContrast = false,
        lightShadow = Color(0xFF2D313D),
        darkShadow = Color(0xFF0B0C0F),
        raisedTopLeft = Color(0xFF252831),
        raisedBottomRight = Color(0xFF1C1E25),
        insetTopLeft = Color(0xFF131419),
        insetBottomRight = Color(0xFF22252E),
        bevelLight = Color(0xFF363B48),
        bevelDark = Color(0xFF121318),
    )
}

fun buildNeumorphicStyle(dark: Boolean, style: DarkStyle): NeumorphicStyle = when {
    !dark -> NeumorphicStyle(
        isDark = false,
        isFlatHighContrast = false,
        lightShadow = Color(0xFFFFFFFF),
        darkShadow = Color(0xFFC9D4E4),
        raisedTopLeft = Color(0xFFFAFBFD),
        raisedBottomRight = Color(0xFFEEF2F8),
        insetTopLeft = Color(0xFFE1E8F2),
        insetBottomRight = Color(0xFFF9FBFE),
        bevelLight = Color(0xFFFFFFFF),
        bevelDark = Color(0xFFD2DCEB),
    )
    style == DarkStyle.TrueBlack -> NeumorphicStyle(
        isDark = true,
        isFlatHighContrast = true,
        lightShadow = Color.Transparent,
        darkShadow = Color.Transparent,
        raisedTopLeft = Color(0xFF0E1014),
        raisedBottomRight = Color(0xFF0E1014),
        insetTopLeft = Color(0xFF060709),
        insetBottomRight = Color(0xFF060709),
        bevelLight = Color(0xFF3A3F4B),
        bevelDark = Color(0xFF3A3F4B),
    )
    else -> NeumorphicStyle(
        isDark = true,
        isFlatHighContrast = false,
        lightShadow = Color(0xFF2C303C),
        darkShadow = Color(0xFF0B0C10),
        raisedTopLeft = Color(0xFF252832),
        raisedBottomRight = Color(0xFF1C1E25),
        insetTopLeft = Color(0xFF121318),
        insetBottomRight = Color(0xFF21242C),
        bevelLight = Color(0xFF353A47),
        bevelDark = Color(0xFF121317),
    )
}

fun buildColorScheme(accent: Accent, dark: Boolean, style: DarkStyle): ColorScheme {
    val s = when {
        !dark -> Surfaces.PaperWhite
        style == DarkStyle.TrueBlack -> Surfaces.TrueBlack
        else -> Surfaces.Obsidian
    }
    val primary = if (dark) accent.dark else accent.light
    val onPrimary = ColorMath.onColor(primary)
    val container = ColorMath.blend(primary, s.surface, if (dark) 0.22 else 0.14)
    val errorColor = if (dark) 0xFFFF8A80 else 0xFFB3261E
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary.c(), onPrimary = onPrimary.c(),
        primaryContainer = container.c(), onPrimaryContainer = s.onSurface.c(),
        secondary = primary.c(), onSecondary = onPrimary.c(),
        secondaryContainer = container.c(), onSecondaryContainer = s.onSurface.c(),
        tertiary = primary.c(), onTertiary = onPrimary.c(),
        background = s.background.c(), onBackground = s.onSurface.c(),
        surface = s.surface.c(), onSurface = s.onSurface.c(),
        surfaceVariant = s.container.c(), onSurfaceVariant = s.onSurfaceVariant.c(),
        surfaceContainerLowest = s.background.c(), surfaceContainerLow = s.surface.c(),
        surfaceContainer = s.container.c(), surfaceContainerHigh = s.containerHigh.c(),
        surfaceContainerHighest = s.containerHigh.c(),
        outline = s.outline.c(), outlineVariant = s.outlineVariant.c(),
        error = errorColor.c(), onError = ColorMath.onColor(errorColor).c(),
    )
}

/**
 * Tactile raised soft-neumorphic surface (or crisp flat surface in High-Contrast Flat mode).
 */
@Composable
fun Modifier.neuRaised(
    shape: CornerBasedShape = RoundedCornerShape(22.dp),
    elevation: Dp = 6.dp,
    accentGlow: Color? = null,
    borderWidth: Dp = 1.dp,
): Modifier {
    val style = LocalNeumorphicStyle.current
    val color = if (elevation >= 4.dp) style.raisedTopLeft else style.raisedBottomRight
    val surface = clip(shape).background(color, shape)
    return when {
        style.isFlatHighContrast -> surface.border(borderWidth, accentGlow ?: style.bevelLight, shape)
        accentGlow != null -> surface.border(borderWidth, accentGlow.copy(alpha = 0.35f), shape)
        else -> surface
    }
}

/** Low-emphasis surface for text inputs, selection, and grouped controls. */
@Composable
fun Modifier.neuInset(
    shape: CornerBasedShape = RoundedCornerShape(24.dp),
    depth: Dp = 4.dp,
    accentBorder: Color? = null,
): Modifier {
    val style = LocalNeumorphicStyle.current
    val color = if (depth >= 4.dp) style.insetTopLeft else style.insetBottomRight
    val surface = clip(shape).background(color, shape)
    return when {
        style.isFlatHighContrast -> surface.border(1.dp, accentBorder ?: style.bevelLight, shape)
        accentBorder != null -> surface.border(1.dp, accentBorder.copy(alpha = 0.45f), shape)
        else -> surface
    }
}

private val base = Typography()
private val AppTypography = base.copy(
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    displaySmall = TextStyle(fontSize = 36.sp, lineHeight = 42.sp, fontWeight = FontWeight.ExtraBold),
)

@Composable
fun NipperFmTheme(prefs: AppearancePrefs, content: @Composable () -> Unit) {
    val dark = when (prefs.themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
    }
    val neuStyle = buildNeumorphicStyle(dark, prefs.darkStyle)
    CompositionLocalProvider(LocalNeumorphicStyle provides neuStyle) {
        MaterialTheme(
            colorScheme = buildColorScheme(prefs.accent, dark, prefs.darkStyle),
            typography = AppTypography,
            content = content,
        )
    }
}
