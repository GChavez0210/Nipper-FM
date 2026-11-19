package app.openradio.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.openradio.AppContainer
import app.openradio.data.remote.StationDirectory
import app.openradio.domain.Country
import app.openradio.domain.PresetOrdering
import app.openradio.domain.SavedStation
import app.openradio.domain.Station
import app.openradio.ui.explore.ExploreController
import app.openradio.ui.theme.Accent
import app.openradio.ui.theme.AppearancePrefs
import app.openradio.ui.theme.DarkStyle
import app.openradio.ui.theme.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class FilterOptions(
    val countries: List<Country> = emptyList(),
    val tags: List<String> = emptyList(),
    val loading: Boolean = false,
    val error: Boolean = false,
)

class AppViewModel(private val container: AppContainer, initialPrefs: AppearancePrefs) : ViewModel() {
    private val repo = container.stations
    private val directory: StationDirectory = container.directory
    val playback = container.playback

    val explore = ExploreController(viewModelScope, directory)

    val appearance: StateFlow<AppearancePrefs> =
        container.preferences.appearance.stateIn(viewModelScope, SharingStarted.Eagerly, initialPrefs)

    val favorites: StateFlow<List<SavedStation>> =
        repo.favorites.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val recents: StateFlow<List<SavedStation>> =
        repo.recents.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val favoriteIds: StateFlow<Set<String>> =
        repo.favoriteIds.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _filters = MutableStateFlow(FilterOptions())
    val filters = _filters.asStateFlow()

    private val _messages = MutableStateFlow<String?>(null)
    val message = _messages.asStateFlow()
    fun messageShown() { _messages.value = null }

    init {
        playback.connect()
        explore.reload()
    }

    fun loadFilters() {
        if (_filters.value.loading || (_filters.value.countries.isNotEmpty() && _filters.value.tags.isNotEmpty())) return
        _filters.value = _filters.value.copy(loading = true, error = false)
        viewModelScope.launch {
            runCatching { directory.countries() to directory.tags() }
                .onSuccess { (c, t) -> _filters.value = FilterOptions(c, t) }
                .onFailure { _filters.value = FilterOptions(loading = false, error = true) }
        }
    }

    fun play(station: Station) = playback.play(station)

    fun toggleFavorite(station: Station) {
        viewModelScope.launch { repo.toggleFavorite(station) }
    }

    fun togglePin(station: Station, pinned: Boolean) {
        viewModelScope.launch {
            if (pinned) repo.unpin(station.id)
            else if (!repo.pin(station.id)) _messages.value = "Car presets are full (${PresetOrdering.MAX_PINS}). Unpin one first."
        }
    }

    fun movePin(from: Int, to: Int) {
        viewModelScope.launch { repo.movePin(from, to) }
    }

    fun setThemeMode(m: ThemeMode) { viewModelScope.launch { container.preferences.setThemeMode(m) } }
    fun setDarkStyle(s: DarkStyle) { viewModelScope.launch { container.preferences.setDarkStyle(s) } }
    fun setAccent(a: Accent) { viewModelScope.launch { container.preferences.setAccent(a) } }
}
