package app.openradio.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.openradio.domain.Station
import app.openradio.ui.AppViewModel
import app.openradio.ui.components.PlayPauseButton
import app.openradio.ui.components.StationArt
import app.openradio.ui.components.WaveformArt
import app.openradio.ui.theme.Accent
import app.openradio.ui.theme.ColorMath
import app.openradio.ui.theme.DarkStyle
import app.openradio.ui.theme.ThemeMode
import app.openradio.ui.theme.neuInset
import app.openradio.ui.theme.neuRaised

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val prefs by vm.appearance.collectAsState()
    val accent = MaterialTheme.colorScheme.primary

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(44.dp)
                    .neuRaised(shape = RoundedCornerShape(14.dp), elevation = 4.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
            Spacer(Modifier.width(14.dp))
            Text("Appearance", style = MaterialTheme.typography.headlineMedium)
        }

        Section("Theme")
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .neuInset(shape = RoundedCornerShape(22.dp), depth = 4.dp)
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThemeMode.entries.forEach { mode ->
                val selected = prefs.themeMode == mode
                val shape = RoundedCornerShape(16.dp)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (selected) {
                                Modifier.neuRaised(shape = shape, elevation = 4.dp, accentGlow = accent)
                            } else {
                                Modifier
                            },
                        )
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = { vm.setThemeMode(mode) },
                        )
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        mode.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            "Light mode uses translucent frosted glass over a cool Paper White aurora backdrop.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 18.dp, top = 8.dp),
        )

        Section("Dark surface")
        Column(
            Modifier
                .selectableGroup()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DarkStyle.entries.forEach { style ->
                val selected = prefs.darkStyle == style
                val shape = RoundedCornerShape(20.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(
                            if (selected) {
                                Modifier.neuInset(shape = shape, depth = 4.dp, accentBorder = accent)
                            } else {
                                Modifier.neuRaised(shape = shape, elevation = 5.dp)
                            },
                        )
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = { vm.setDarkStyle(style) },
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            style.label,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            style.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Section("Accent color")
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .neuRaised(shape = RoundedCornerShape(24.dp), elevation = 5.dp)
                .padding(horizontal = 10.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Accent.entries.forEach { item ->
                Swatch(item, item == prefs.accent) { vm.setAccent(item) }
            }
        }

        Section("Preview")
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .neuRaised(
                    shape = RoundedCornerShape(26.dp),
                    elevation = 7.dp,
                    accentGlow = accent,
                )
                .padding(18.dp),
        ) {
            val demo = Station("preview", "KEXP 90.3", "", country = "United States", state = "Seattle")
            Row(verticalAlignment = Alignment.CenterVertically) {
                StationArt(demo, 56.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(demo.name, style = MaterialTheme.typography.titleMedium)
                    Text("Seattle", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                PlayPauseButton(isPlaying = false, size = 48.dp, onClick = {})
            }
            Spacer(Modifier.height(14.dp))
            WaveformArt(Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(start = 18.dp, top = 22.dp, bottom = 10.dp),
    )
}

@Composable
private fun Swatch(accent: Accent, selected: Boolean, onClick: () -> Unit) {
    val swatchArgb = if (MaterialTheme.colorScheme.background.luminanceIsDark()) accent.dark else accent.light
    val fill = Color(swatchArgb)
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(4.dp)
            .semantics { contentDescription = accent.label + if (selected) ", selected" else "" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .then(
                    if (selected) {
                        Modifier
                            .border(2.5.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            .padding(4.dp)
                    } else {
                        Modifier
                    },
                )
                .clip(CircleShape)
                .background(fill),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color(ColorMath.onColor(swatchArgb)),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            accent.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun Color.luminanceIsDark(): Boolean = (red * 0.2126f + green * 0.7152f + blue * 0.0722f) < 0.4f
