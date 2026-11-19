package app.openradio.ui.theme

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
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
    val specularMid: Color = Color.Transparent,
    val sheenTop: Color = Color.Transparent,
)

val LocalNeumorphicStyle = staticCompositionLocalOf {
    buildNeumorphicStyle(dark = true, style = DarkStyle.Obsidian)
}

fun buildNeumorphicStyle(dark: Boolean, style: DarkStyle): NeumorphicStyle = when {
    !dark -> NeumorphicStyle(
        isDark = false,
        isFlatHighContrast = false,
        lightShadow = Color(0xFFFFFFFF),
        darkShadow = Color(0x387A90B0),
        raisedTopLeft = Color(0xD9FFFFFF),
        raisedBottomRight = Color(0x8CFFFFFF),
        insetTopLeft = Color(0x78D9E4F2),
        insetBottomRight = Color(0xB8F5F9FF),
        bevelLight = Color(0xF2FFFFFF),
        bevelDark = Color(0x66FFFFFF),
        specularMid = Color(0x33B8CAE0),
        sheenTop = Color(0x55FFFFFF),
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
        specularMid = Color(0xFF3A3F4B),
        sheenTop = Color.Transparent,
    )
    else -> NeumorphicStyle(
        isDark = true,
        isFlatHighContrast = false,
        lightShadow = Color(0x33FFFFFF),
        darkShadow = Color(0xAA04060C),
        raisedTopLeft = Color(0x88262B3A),
        raisedBottomRight = Color(0x52171A24),
        insetTopLeft = Color(0x990A0C12),
        insetBottomRight = Color(0x66161924),
        bevelLight = Color(0x6BFFFFFF),
        bevelDark = Color(0x26FFFFFF),
        specularMid = Color(0x0DFFFFFF),
        sheenTop = Color(0x22FFFFFF),
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
 * Ambient multi-orb aurora backdrop that gives translucent glassmorphism panels
 * color, light refraction, and depth behind them. Disabled in High-Contrast Flat mode.
 */
@Composable
fun GlassBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val neu = LocalNeumorphicStyle.current
    val bg = MaterialTheme.colorScheme.background
    val accent = MaterialTheme.colorScheme.primary
    Box(modifier = modifier.fillMaxSize().background(bg)) {
        if (!neu.isFlatHighContrast) {
            val secondaryOrb = if (neu.isDark) Color(0xFF29B6F6) else Color(0xFF5C9DFF)
            val tertiaryOrb = if (neu.isDark) Color(0xFFEC407A) else Color(0xFFFF8A65)
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val r1 = maxOf(w, h) * 0.55f
                val r2 = maxOf(w, h) * 0.48f
                val r3 = maxOf(w, h) * 0.52f

                // Top-left primary accent aurora orb
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            accent.copy(alpha = if (neu.isDark) 0.30f else 0.20f),
                            accent.copy(alpha = if (neu.isDark) 0.10f else 0.06f),
                            Color.Transparent,
                        ),
                        center = Offset(w * 0.15f, h * 0.12f),
                        radius = r1,
                    ),
                    radius = r1,
                    center = Offset(w * 0.15f, h * 0.12f),
                )

                // Mid-right cool cyan/blue refraction orb
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            secondaryOrb.copy(alpha = if (neu.isDark) 0.20f else 0.15f),
                            secondaryOrb.copy(alpha = if (neu.isDark) 0.06f else 0.04f),
                            Color.Transparent,
                        ),
                        center = Offset(w * 0.88f, h * 0.44f),
                        radius = r2,
                    ),
                    radius = r2,
                    center = Offset(w * 0.88f, h * 0.44f),
                )

                // Bottom-left accent + warm orb behind floating MiniPlayer & nav dock
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            accent.copy(alpha = if (neu.isDark) 0.25f else 0.17f),
                            tertiaryOrb.copy(alpha = if (neu.isDark) 0.10f else 0.07f),
                            Color.Transparent,
                        ),
                        center = Offset(w * 0.28f, h * 0.88f),
                        radius = r3,
                    ),
                    radius = r3,
                    center = Offset(w * 0.28f, h * 0.88f),
                )
            }
        }
        content()
    }
}

/**
 * Translucent frosted-glass panel with soft downward drop shadow, top-left specular rim reflection,
 * and internal glass sheen (or crisp flat surface in High-Contrast Flat mode).
 */
@Composable
fun Modifier.neuRaised(
    shape: CornerBasedShape = RoundedCornerShape(22.dp),
    elevation: Dp = 6.dp,
    accentGlow: Color? = null,
    borderWidth: Dp = 1.dp,
): Modifier {
    val neu = LocalNeumorphicStyle.current
    val density = LocalDensity.current
    val cornerPx = with(density) {
        shape.topStart.toPx(Size(200f, 200f), density)
    }
    if (neu.isFlatHighContrast) {
        val borderColor = accentGlow ?: neu.bevelLight
        return this
            .clip(shape)
            .background(neu.raisedTopLeft, shape)
            .border(borderWidth, borderColor, shape)
    }
    val offsetPx = with(density) { elevation.toPx() }
    val blurPx = (offsetPx * 2.2f).coerceAtLeast(1f)

    // Translucent frosted glass fill, tinted with accentGlow when active/selected
    val glassTopLeft = if (accentGlow != null) {
        accentGlow.copy(alpha = if (neu.isDark) 0.26f else 0.22f)
    } else {
        neu.raisedTopLeft
    }
    val glassBottomRight = if (accentGlow != null) {
        accentGlow.copy(alpha = if (neu.isDark) 0.10f else 0.10f)
    } else {
        neu.raisedBottomRight
    }

    // Specular rim: bright top-left highlight -> subtle mid -> faint bottom-right rim reflection
    val rimTopLeft = accentGlow?.copy(alpha = 0.90f) ?: neu.bevelLight
    val rimMid = accentGlow?.copy(alpha = 0.22f) ?: neu.specularMid
    val rimBottomRight = accentGlow?.copy(alpha = 0.50f) ?: neu.bevelDark

    return this
        .drawBehind {
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                val shadowColor = if (accentGlow != null) {
                    accentGlow.copy(alpha = if (neu.isDark) 0.35f else 0.25f)
                } else {
                    neu.darkShadow
                }
                val shadowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = shadowColor.toArgb()
                    maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
                }
                val r = cornerPx.coerceAtMost(size.minDimension / 2f)
                native.drawRoundRect(
                    offsetPx * 0.15f,
                    offsetPx * 0.55f,
                    size.width + offsetPx * 0.35f,
                    size.height + offsetPx * 0.95f,
                    r, r, shadowPaint,
                )
            }
        }
        .clip(shape)
        .then(
            if (accentGlow != null) {
                // Base frosted layer under the accent glass tint so text stays high-contrast
                Modifier.background(
                    brush = Brush.linearGradient(
                        colors = listOf(neu.raisedTopLeft, neu.raisedBottomRight),
                        start = Offset.Zero,
                        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                    ),
                    shape = shape,
                )
            } else {
                Modifier
            },
        )
        .background(
            brush = Brush.linearGradient(
                colors = listOf(glassTopLeft, glassBottomRight),
                start = Offset.Zero,
                end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
            ),
            shape = shape,
        )
        .drawWithContent {
            // Subtle top-left diagonal glass refraction sheen
            val r = cornerPx.coerceAtMost(size.minDimension / 2f)
            drawRoundRect(
                brush = Brush.linearGradient(
                    colors = listOf(neu.sheenTop, Color.Transparent),
                    start = Offset.Zero,
                    end = Offset(size.width * 0.55f, size.height * 0.65f),
                ),
                topLeft = Offset.Zero,
                size = size,
                cornerRadius = CornerRadius(r, r),
            )
            drawContent()
        }
        .border(
            width = borderWidth,
            brush = Brush.linearGradient(
                colors = listOf(rimTopLeft, rimMid, rimBottomRight),
                start = Offset.Zero,
                end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
            ),
            shape = shape,
        )
}

/**
 * Etched / recessed translucent glass well for search inputs, active filter capsules, and tracks.
 */
@Composable
fun Modifier.neuInset(
    shape: CornerBasedShape = RoundedCornerShape(24.dp),
    depth: Dp = 4.dp,
    accentBorder: Color? = null,
): Modifier {
    val neu = LocalNeumorphicStyle.current
    val density = LocalDensity.current
    val cornerPx = with(density) {
        shape.topStart.toPx(Size(200f, 200f), density)
    }
    if (neu.isFlatHighContrast) {
        return this
            .clip(shape)
            .background(neu.insetTopLeft, shape)
            .border(1.dp, accentBorder ?: neu.bevelLight, shape)
    }
    val topFill = if (accentBorder != null) {
        accentBorder.copy(alpha = if (neu.isDark) 0.22f else 0.16f)
    } else {
        neu.insetTopLeft
    }
    val bottomFill = if (accentBorder != null) {
        accentBorder.copy(alpha = if (neu.isDark) 0.10f else 0.08f)
    } else {
        neu.insetBottomRight
    }
    val topRim = accentBorder?.copy(alpha = 0.70f) ?: neu.bevelDark
    val bottomRim = accentBorder?.copy(alpha = 0.90f) ?: neu.bevelLight
    return this
        .clip(shape)
        .background(
            brush = Brush.linearGradient(
                colors = listOf(neu.insetTopLeft, neu.insetBottomRight),
                start = Offset.Zero,
                end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
            ),
            shape = shape,
        )
        .then(
            if (accentBorder != null) {
                Modifier.background(
                    brush = Brush.linearGradient(
                        colors = listOf(topFill, bottomFill),
                        start = Offset.Zero,
                        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                    ),
                    shape = shape,
                )
            } else {
                Modifier
            },
        )
        .drawWithContent {
            drawContent()
            val r = cornerPx.coerceAtMost(size.minDimension / 2f)
            // Subtle top inner shadow & bottom specular highlight inside the etched well
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = if (neu.isDark) 0.24f else 0.07f),
                        Color.Transparent,
                        Color.White.copy(alpha = if (neu.isDark) 0.07f else 0.35f),
                    ),
                ),
                topLeft = Offset.Zero,
                size = size,
                cornerRadius = CornerRadius(r, r),
            )
        }
        .border(
            width = if (accentBorder != null) 1.2.dp else 1.dp,
            brush = Brush.linearGradient(
                colors = listOf(topRim, neu.specularMid, bottomRim),
                start = Offset.Zero,
                end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
            ),
            shape = shape,
        )
}

private val base = Typography()
private val AppTypography = base.copy(
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    displaySmall = TextStyle(fontSize = 36.sp, lineHeight = 42.sp, fontWeight = FontWeight.ExtraBold),
)

@Composable
fun OpenRadioTheme(prefs: AppearancePrefs, content: @Composable () -> Unit) {
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
