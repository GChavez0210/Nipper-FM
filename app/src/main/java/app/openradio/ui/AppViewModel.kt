package app.openradio.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.openradio.AppContainer
import app.openradio.data.remote.StationDirectory
import app.openradio.domain.BackupCodec
import app.openradio.domain.BackupPayload
import app.openradio.domain.BackupPreviewSummary
import app.openradio.domain.BufferProfile
import app.openradio.domain.CarPrefs
import app.openradio.domain.WheelStart
import app.openradio.domain.Country
import app.openradio.domain.CustomStreamBuilder
import app.openradio.domain.EqPreset
import app.openradio.domain.ErrorLogFormat
import app.openradio.domain.MonthlyUsage
import app.openradio.domain.FavoriteGroupDefaults
import app.openradio.domain.OnAirEntry
import app.openradio.domain.PlaybackPrefs
import app.openradio.domain.PresetOrdering
import app.openradio.domain.SavedStation
import app.openradio.domain.Station
import app.openradio.domain.StreamFeed
import app.openradio.domain.SurprisePicker
import app.openradio.ui.explore.ExploreController
import app.openradio.ui.theme.Accent
import app.openradio.ui.theme.AppearancePrefs
import app.openradio.ui.theme.DarkStyle
import app.openradio.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

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

    val playbackPrefs: StateFlow<PlaybackPrefs> =
        container.preferences.playbackPrefs.stateIn(viewModelScope, SharingStarted.Eagerly, PlaybackPrefs())

    val favorites: StateFlow<List<SavedStation>> =
        repo.favorites.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val recents: StateFlow<List<SavedStation>> =
        repo.recents.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val favoriteIds: StateFlow<Set<String>> =
        repo.favoriteIds.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val failedIds: StateFlow<Set<String>> =
        repo.failedIds.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val favoriteGroups: StateFlow<List<String>> =
        repo.favoriteGroups.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            FavoriteGroupDefaults.STARTER_GROUPS,
        )
    val onAirHistory: StateFlow<List<OnAirEntry>> =
        repo.onAirHistory.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Most recently played station for the Last Station shortcut. */
    val lastStation: StateFlow<Station?> =
        recents.map { it.firstOrNull()?.station }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _selectedGroup = MutableStateFlow<String?>(null)
    val selectedGroup: StateFlow<String?> = _selectedGroup.asStateFlow()

    private val _alternateFeeds = MutableStateFlow<List<StreamFeed>>(emptyList())
    val alternateFeeds: StateFlow<List<StreamFeed>> = _alternateFeeds.asStateFlow()

    private val _filters = MutableStateFlow(FilterOptions())
    val filters = _filters.asStateFlow()

    private val _messages = MutableStateFlow<String?>(null)
    val message = _messages.asStateFlow()
    fun messageShown() { _messages.value = null }
    fun showMessage(msg: String) { _messages.value = msg }

    init {
        playback.connect()
        explore.reload()
    }

    private var countryDetectionStarted = false

    /**
     * Detects the user's current country once per launch and pre-selects it in Explore.
     * Call after the location permission prompt resolves; falls back to the mobile network country.
     */
    fun detectCountryOnce() {
        if (countryDetectionStarted) return
        countryDetectionStarted = true
        viewModelScope.launch {
            val code = runCatching { container.countryLocator.detectCountryCode() }.getOrNull() ?: return@launch
            val known = _filters.value.countries.firstOrNull { it.code.equals(code, ignoreCase = true) }
            val name = Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.getDefault())
                .ifBlank { code }
            explore.applyDetectedCountry(known ?: Country(name, code, 0))
        }
    }

    private val _popularCountryCodes = MutableStateFlow<List<String>>(emptyList())
    val popularCountryCodes = _popularCountryCodes.asStateFlow()
    private var loadingPopularCountries = false

    fun loadPopularCountries() {
        if (loadingPopularCountries || _popularCountryCodes.value.isNotEmpty()) return
        loadingPopularCountries = true
        viewModelScope.launch {
            try {
                _popularCountryCodes.value = directory.popularCountryCodes()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // The full country picker still works if the popularity request fails.
            } finally {
                loadingPopularCountries = false
            }
        }
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

    fun loadAlternateFeeds(station: Station) {
        // Clear any feeds left over from a previously viewed station so the sheet never shows stale mirrors.
        _alternateFeeds.value = emptyList()
        viewModelScope.launch {
            _alternateFeeds.value = repo.alternateFeedsFor(station)
        }
    }

    fun selectStreamFeed(feed: StreamFeed) {
        playback.selectStreamFeed(feed)
        _messages.value = "Switched feed: ${feed.qualityBadge}"
    }

    private val _pendingOpenSleepTimer = MutableStateFlow(false)
    val pendingOpenSleepTimer = _pendingOpenSleepTimer.asStateFlow()

    fun requestOpenSleepTimer() {
        _pendingOpenSleepTimer.value = true
    }

    fun sleepTimerOpened() {
        _pendingOpenSleepTimer.value = false
    }

    private val _pendingOpenLastStation = MutableStateFlow(false)
    val pendingOpenLastStation: StateFlow<Boolean> = _pendingOpenLastStation.asStateFlow()

    fun requestOpenLastStationShortcut() {
        _pendingOpenLastStation.value = true
    }

    fun consumeOpenLastStationShortcut(onNavigateToNowPlaying: () -> Unit) {
        if (!_pendingOpenLastStation.value) return
        _pendingOpenLastStation.value = false
        viewModelScope.launch {
            val target = lastStation.value ?: repo.recentsNow().firstOrNull()
            if (target != null) {
                playback.prepareReady(target)
                onNavigateToNowPlaying()
            } else {
                _messages.value = "No recently played station yet."
            }
        }
    }

    fun play(station: Station) = playback.play(station)

    fun playStationShortcut(stationId: String) {
        viewModelScope.launch {
            val station = repo.resolveForPlayback(stationId)
            if (station != null) play(station) else _messages.value = "Station unavailable."
        }
    }

    val resumeHeadset get() = container.preferences.resumeHeadset

    fun setResumeHeadset(enabled: Boolean) {
        viewModelScope.launch { container.preferences.setResumeHeadset(enabled) }
    }

    fun stationEq(stationId: String) = container.preferences.stationEq(stationId)

    fun setStationEq(stationId: String, preset: EqPreset?) {
        viewModelScope.launch { container.preferences.setStationEq(stationId, preset) }
    }

    /** Starts the most recent station (or first preset) right away, e.g. from Assistant. */
    fun playLastStationNow() {
        viewModelScope.launch {
            val target = repo.recentsNow().firstOrNull() ?: repo.carPresetsNow().firstOrNull()
            if (target != null) play(target) else _messages.value = "No recently played station yet."
        }
    }

    /** Opens [station] (or [lastStation]) in the player prepared and ready to start on tap. */
    fun openLastStationReady(station: Station? = lastStation.value, onOpened: () -> Unit = {}) {
        val target = station ?: return
        playback.prepareReady(target)
        onOpened()
    }

    /** Tunes to a surprise station within the current filters, favoring unplayed stations. */
    fun surpriseMe() {
        val exp = explore.state.value
        val candidates = (exp.items + favorites.value.map { it.station }).distinctBy { it.id }
        val recentIds = recents.value.map { it.station.id }
        val currentId = playback.state.value.station?.id
        val chosen = SurprisePicker.pick(candidates, recentIds, currentId)
        if (chosen != null) {
            play(chosen)
            _messages.value = "Surprise Me: ${chosen.name}"
        } else {
            _messages.value = "No stations loaded to pick from yet."
        }
    }

    fun toggleFavorite(station: Station) {
        viewModelScope.launch { repo.toggleFavorite(station) }
    }

    fun selectGroup(groupName: String?) {
        _selectedGroup.value = groupName
    }

    fun createGroup(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            repo.createGroup(clean)
            _selectedGroup.value = clean
            _messages.value = "Created collection \"$clean\""
        }
    }

    fun deleteGroup(name: String) {
        viewModelScope.launch {
            repo.deleteGroup(name)
            if (_selectedGroup.value == name) _selectedGroup.value = null
            _messages.value = "Removed collection \"$name\""
        }
    }

    fun assignStationGroup(station: Station, groupName: String?) {
        viewModelScope.launch {
            repo.setFavorite(station, favorite = true, groupName = groupName)
            if (groupName == null) {
                repo.setStationGroup(station.id, null)
                _messages.value = "Moved ${station.name} to All Favorites"
            } else {
                _messages.value = "Added ${station.name} to $groupName"
            }
        }
    }

    fun previewCustomStream(name: String, url: String): Boolean {
        val preview = CustomStreamBuilder.build(name.ifBlank { "Custom Stream Preview" }, url)
        if (preview == null) {
            _messages.value = "Enter a valid http:// or https:// stream URL."
            return false
        }
        play(preview)
        return true
    }

    fun saveCustomStream(name: String, url: String, groupName: String?, onSaved: (Station) -> Unit = {}) {
        viewModelScope.launch {
            val saved = repo.saveCustomStream(name, url, groupName)
            if (saved == null) {
                _messages.value = "Enter both a station name and a valid http(s):// stream URL."
            } else {
                _messages.value = "Saved custom stream \"${saved.name}\""
                onSaved(saved)
            }
        }
    }

    fun toggleOnAirBookmark(id: Long) {
        viewModelScope.launch { repo.toggleOnAirBookmark(id) }
    }

    fun bookmarkCurrentTrack() {
        val ui = playback.state.value
        val station = ui.station ?: return
        val title = ui.trackTitle ?: return
        viewModelScope.launch {
            if (repo.bookmarkTrack(station, title)) {
                _messages.value = "Bookmarked \"$title\""
            }
        }
    }

    /** Measured streaming data per month, newest first. */
    val dataUsage: StateFlow<List<MonthlyUsage>> =
        container.usage.months.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun resetDataUsage() {
        viewModelScope.launch {
            container.usage.reset()
            _messages.value = "Data usage counters reset."
        }
    }

    /** Readable playback-error log for export; [appVersion] and [device] go in the header. */
    suspend fun buildErrorLogText(appVersion: String, device: String): String =
        ErrorLogFormat.export(container.errorLog.entries(), appVersion, device)

    suspend fun errorLogCount(): Int = container.errorLog.entries().size

    fun clearErrorLog() {
        viewModelScope.launch {
            container.errorLog.clear()
            _messages.value = "Playback error log cleared."
        }
    }

    suspend fun buildBackupJson(): String {
        val payload = repo.buildBackupPayload(appearance.value)
        return BackupCodec.encode(payload)
    }

    fun parseBackup(rawJson: String): Pair<BackupPayload, BackupPreviewSummary>? =
        runCatching {
            val payload = BackupCodec.decode(rawJson)
            payload to BackupCodec.summarize(payload)
        }.getOrNull()

    fun applyBackup(payload: BackupPayload, replace: Boolean) {
        viewModelScope.launch {
            repo.restoreBackup(payload, replace)
            val prefs = payload.toAppearancePrefs()
            container.preferences.setThemeMode(prefs.themeMode)
            container.preferences.setDarkStyle(prefs.darkStyle)
            container.preferences.setAccent(prefs.accent)
            _messages.value = if (replace) "Backup restored (replaced setup)." else "Backup merged with your current setup."
        }
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

    fun setWifiOnly(enabled: Boolean) { viewModelScope.launch { container.preferences.setWifiOnly(enabled) } }
    fun setBufferProfile(profile: BufferProfile) { viewModelScope.launch { container.preferences.setBufferProfile(profile) } }
    fun setEqPreset(preset: EqPreset) { viewModelScope.launch { container.preferences.setEqPreset(preset) } }
    fun setSpatialAudio(enabled: Boolean) { viewModelScope.launch { container.preferences.setSpatialAudio(enabled) } }
    fun setLoudnessNorm(enabled: Boolean) { viewModelScope.launch { container.preferences.setLoudnessNorm(enabled) } }
    fun setTrackArtwork(enabled: Boolean) { viewModelScope.launch { container.preferences.setTrackArtwork(enabled) } }

    val carPrefs: StateFlow<CarPrefs> =
        container.preferences.carPrefs.stateIn(viewModelScope, SharingStarted.Eagerly, CarPrefs())
    fun setCarTiles(enabled: Boolean) { viewModelScope.launch { container.preferences.setCarTiles(enabled) } }
    fun setWheelStart(start: WheelStart) { viewModelScope.launch { container.preferences.setWheelStart(start) } }
}
