package app.openradio.tile

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.openradio.R
import app.openradio.RadioApp
import app.openradio.domain.PlaybackStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Quick Settings tile: shows the current (or last) station and toggles playback. With nothing
 * loaded it tunes to the most recent station, falling back to the first car preset.
 */
class LastStationTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: Job? = null
    private val container get() = (application as RadioApp).container

    override fun onStartListening() {
        super.onStartListening()
        container.playback.connect()
        listening?.cancel()
        listening = scope.launch {
            container.playback.state.collect { refresh() }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val playback = container.playback
        if (playback.state.value.station != null) {
            playback.togglePlayPause()
            return
        }
        scope.launch {
            val target = container.stations.recentsNow().firstOrNull()
                ?: container.stations.carPresetsNow().firstOrNull()
            if (target != null) playback.play(target)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun refresh() {
        val tile = qsTile ?: return
        val ui = container.playback.state.value
        val station = ui.station ?: container.stations.recentsNow().firstOrNull()
        val active = ui.station != null && ui.status in ACTIVE
        tile.state = when {
            active -> Tile.STATE_ACTIVE
            station != null -> Tile.STATE_INACTIVE
            else -> Tile.STATE_UNAVAILABLE
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_radio)
        tile.label = station?.name ?: getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                station == null -> getString(R.string.tile_no_station)
                ui.status == PlaybackStatus.Buffering || ui.status == PlaybackStatus.Reconnecting ->
                    getString(R.string.tile_connecting)
                active -> ui.trackTitle ?: getString(R.string.tile_playing)
                else -> getString(R.string.tile_tap_to_play)
            }
        }
        tile.contentDescription = tile.label
        tile.updateTile()
    }

    companion object {
        private val ACTIVE = setOf(PlaybackStatus.Live, PlaybackStatus.Buffering, PlaybackStatus.Reconnecting)

        /** Asks System UI to refresh the tile if the user has added it. */
        fun requestUpdate(context: Context) {
            runCatching {
                requestListeningState(context, ComponentName(context, LastStationTileService::class.java))
            }
        }
    }
}
