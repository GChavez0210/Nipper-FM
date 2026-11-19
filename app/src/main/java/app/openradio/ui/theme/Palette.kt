package app.openradio.ui.theme

import kotlin.math.pow

enum class ThemeMode(val label: String) { System("System"), Dark("Dark"), Light("Light") }

enum class DarkStyle(val label: String, val subtitle: String) {
    Obsidian("Frosted Glass", "Translucent glass panels over an ambient aurora glow"),
    TrueBlack("High-Contrast Flat", "Pure black flat surfaces with crisp borders"),
}

/** Six preset accents. Each has a variant tuned for dark and for light (Paper White) surfaces. */
enum class Accent(val label: String, val dark: Long, val light: Long) {
    Cyan("Cyan", 0xFF29D3F5, 0xFF006275),
    Amber("Amber", 0xFFFFAE2B, 0xFF8A4F00),
    Violet("Violet", 0xFFA480FF, 0xFF5A32B5),
    Emerald("Emerald", 0xFF22CC8D, 0xFF006647),
    Crimson("Crimson", 0xFFFF6396, 0xFFA80E3B),
    Coral("Coral", 0xFFFF806B, 0xFFA13022),
}

data class AppearancePrefs(
    val themeMode: ThemeMode = ThemeMode.System,
    val darkStyle: DarkStyle = DarkStyle.Obsidian,
    val accent: Accent = Accent.Violet,
)

/** Surface colours as ARGB longs so contrast can be unit-tested without Android classes. */
data class SurfaceTokens(
    val background: Long,
    val surface: Long,
    val container: Long,
    val containerHigh: Long,
    val onSurface: Long,
    val onSurfaceVariant: Long,
    val outline: Long,
    val outlineVariant: Long,
)

object Surfaces {
    /** Soft neumorphic dark charcoal-slate palette (reference dark aesthetic). */
    val Obsidian = SurfaceTokens(
        background = 0xFF17181D,
        surface = 0xFF1C1E24,
        container = 0xFF21242B,
        containerHigh = 0xFF272A33,
        onSurface = 0xFFF4F6FA,
        onSurfaceVariant = 0xFFC0C5D2,
        outline = 0xFF8C92A3,
        outlineVariant = 0xFF323642,
    )

    /** Optional high-contrast flat dark mode (pure black, flat cards with crisp outlines). */
    val TrueBlack = SurfaceTokens(
        background = 0xFF000000,
        surface = 0xFF060709,
        container = 0xFF0E1014,
        containerHigh = 0xFF17191F,
        onSurface = 0xFFFFFFFF,
        onSurfaceVariant = 0xFFC8CDD8,
        outline = 0xFF949AA7,
        outlineVariant = 0xFF3A3F4B,
    )

    /** Clean, cool Paper White neumorphic palette (replaces yellowish parchment). */
    val PaperWhite = SurfaceTokens(
        background = 0xFFF2F5F9,
        surface = 0xFFF7F9FC,
        container = 0xFFECF0F6,
        containerHigh = 0xFFE3E9F2,
        onSurface = 0xFF141923,
        onSurfaceVariant = 0xFF434C5E,
        outline = 0xFF647084,
        outlineVariant = 0xFFD2DCE9,
    )

    /** Alias kept for compatibility with existing callers/tests. */
    val Parchment: SurfaceTokens get() = PaperWhite
}

object ColorMath {
    private fun channel(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    fun luminance(argb: Long): Double {
        val r = ((argb shr 16) and 0xFF).toInt()
        val g = ((argb shr 8) and 0xFF).toInt()
        val b = (argb and 0xFF).toInt()
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    }

    fun contrast(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** Black or white, whichever reads better on [background]. */
    fun onColor(background: Long): Long =
        if (contrast(0xFF000000, background) >= contrast(0xFFFFFFFF, background)) 0xFF000000 else 0xFFFFFFFF

    fun blend(top: Long, bottom: Long, topAlpha: Double): Long {
        fun mix(shift: Int): Long {
            val t = (top shr shift) and 0xFF
            val b = (bottom shr shift) and 0xFF
            return (t * topAlpha + b * (1 - topAlpha)).toLong().coerceIn(0, 255)
        }
        return 0xFF000000 or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }
}
