package app.openradio.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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

    val appearance: Flow<AppearancePrefs> = context.dataStore.data.map { p ->
        AppearancePrefs(
            themeMode = p[themeKey].toEnum(ThemeMode.System),
            darkStyle = p[darkKey].toEnum(DarkStyle.Obsidian),
            accent = p[accentKey].toEnum(Accent.Amber),
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[themeKey] = mode.name }
    suspend fun setDarkStyle(style: DarkStyle) = context.dataStore.edit { it[darkKey] = style.name }
    suspend fun setAccent(accent: Accent) = context.dataStore.edit { it[accentKey] = accent.name }

    private inline fun <reified T : Enum<T>> String?.toEnum(default: T): T =
        enumValues<T>().firstOrNull { it.name == this } ?: default
}
