package app.openradio.ui

import app.openradio.ui.theme.Accent
import app.openradio.ui.theme.ColorMath
import app.openradio.ui.theme.DarkStyle
import app.openradio.ui.theme.Surfaces
import app.openradio.ui.theme.buildNeumorphicStyle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    private val themes = mapOf(
        "obsidian" to (Surfaces.Obsidian to true),
        "trueblack" to (Surfaces.TrueBlack to true),
        "paperwhite" to (Surfaces.PaperWhite to false),
    )

    @Test fun textIsReadableOnEverySurface() {
        for ((name, pair) in themes) {
            val s = pair.first
            for (bg in listOf(s.background, s.surface, s.container, s.containerHigh)) {
                assertTrue("$name onSurface", ColorMath.contrast(s.onSurface, bg) >= 7.0)
                assertTrue("$name onSurfaceVariant", ColorMath.contrast(s.onSurfaceVariant, bg) >= 4.5)
            }
            assertTrue("$name outline", ColorMath.contrast(s.outline, s.background) >= 3.0)
        }
    }

    @Test fun everyAccentMeetsContrastInBothThemes() {
        for (accent in Accent.entries) {
            for ((name, pair) in themes) {
                val (s, dark) = pair
                val primary = if (dark) accent.dark else accent.light
                // Accent is used for text, icons and controls on the surfaces.
                for (bg in listOf(s.background, s.surface, s.container, s.containerHigh)) {
                    assertTrue("${accent.label}/$name vs bg ${"%.2f".format(ColorMath.contrast(primary, bg))}", ColorMath.contrast(primary, bg) >= 4.5)
                }
                val on = ColorMath.onColor(primary)
                assertTrue("${accent.label}/$name onPrimary", ColorMath.contrast(on, primary) >= 4.5)
            }
        }
    }

    @Test fun paperWhiteIsCoolNeutralAndHighContrastDarkIsFlat() {
        val pw = Surfaces.PaperWhite.background
        val r = (pw shr 16) and 0xFF
        val b = pw and 0xFF
        // Paper white must not have a warm/yellowish cast (blue channel >= red channel).
        assertTrue("paperWhite should not be yellowish", b >= r)

        assertFalse(buildNeumorphicStyle(dark = false, style = DarkStyle.Obsidian).isFlatHighContrast)
        assertFalse(buildNeumorphicStyle(dark = true, style = DarkStyle.Obsidian).isFlatHighContrast)
        assertTrue(buildNeumorphicStyle(dark = true, style = DarkStyle.TrueBlack).isFlatHighContrast)

        // Verify Graphite accent specifically is high-contrast on both dark and light PaperWhite themes.
        assertTrue(ColorMath.contrast(Accent.OffWhite.dark, Surfaces.Obsidian.containerHigh) >= 7.0)
        assertTrue(ColorMath.contrast(Accent.OffWhite.light, Surfaces.PaperWhite.surface) >= 7.0)
        assertTrue(ColorMath.contrast(Accent.OffWhite.light, Surfaces.PaperWhite.containerHigh) >= 7.0)
    }
}
