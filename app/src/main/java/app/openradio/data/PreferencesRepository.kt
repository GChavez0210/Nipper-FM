package app.openradio.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.openradio.domain.BufferProfile
import app.openradio.domain.EqPreset
import app.openradio.domain.PlaybackPrefs
import app.openradio.ui.theme.Accent
import app.openradio.ui.theme.AppearancePrefs
import app.openradio.ui.theme.DarkStyle
import app.openradio.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("appearance")

class PreferencesRepository(private val context: Context) {
    private val themeKey = stringPreferencesKey("theme_mode")
    private val darkKey = stringPreferencesKey("dark_style")
    private val accentKey = stringPreferencesKey("accent")
    private val wifiOnlyKey = booleanPreferencesKey("wifi_only")
    private val bufferProfileKey = stringPreferencesKey("buffer_profile")
    private val eqPresetKey = stringPreferencesKey("eq_preset")
    private val spatialAudioKey = booleanPreferencesKey("spatial_audio")
    private val loudnessNormKey = booleanPreferencesKey("loudness_norm")

    val appearance: Flow<AppearancePrefs> = context.dataStore.data.map { p ->
        AppearancePrefs(
            themeMode = p[themeKey].toEnum(ThemeMode.System),
            darkStyle = p[darkKey].toEnum(DarkStyle.Obsidian),
            accent = p[accentKey].toEnum(Accent.Amber),
        )
    }

    val playbackPrefs: Flow<PlaybackPrefs> = context.dataStore.data.map { p ->
        PlaybackPrefs(
            wifiOnly = p[wifiOnlyKey] ?: false,
            bufferProfile = p[bufferProfileKey].toEnum(BufferProfile.Standard),
            eqPreset = p[eqPresetKey].toEnum(EqPreset.Flat),
            spatialAudio = p[spatialAudioKey] ?: false,
            loudnessNorm = p[loudnessNormKey] ?: false,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[themeKey] = mode.name }
    suspend fun setDarkStyle(style: DarkStyle) = context.dataStore.edit { it[darkKey] = style.name }
    suspend fun setAccent(accent: Accent) = context.dataStore.edit { it[accentKey] = accent.name }

    suspend fun setWifiOnly(enabled: Boolean) = context.dataStore.edit { it[wifiOnlyKey] = enabled }
    suspend fun setBufferProfile(profile: BufferProfile) = context.dataStore.edit { it[bufferProfileKey] = profile.name }
    suspend fun setEqPreset(preset: EqPreset) = context.dataStore.edit { it[eqPresetKey] = preset.name }
    suspend fun setSpatialAudio(enabled: Boolean) = context.dataStore.edit { it[spatialAudioKey] = enabled }
    suspend fun setLoudnessNorm(enabled: Boolean) = context.dataStore.edit { it[loudnessNormKey] = enabled }

    private inline fun <reified T : Enum<T>> String?.toEnum(default: T): T =
        enumValues<T>().firstOrNull { it.name == this } ?: default
}
