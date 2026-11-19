package app.openradio.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import app.openradio.MainActivity
import app.openradio.R
import app.openradio.RadioApp
import app.openradio.domain.PlaybackStatus
import app.openradio.domain.Station
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Home-screen widget showing the current station, playback status/track, Play/Pause control,
 * and quick-tune buttons for up to 3 pinned car presets (or top favorites).
 */
class RadioWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        updateAllWidgets(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) = updateAllWidgets(context)

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val editor = widgetPreferences(context).edit()
        appWidgetIds.forEach { editor.remove(it.toString()) }
        editor.apply()
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val app = context.applicationContext as? RadioApp ?: return
        when (intent.action) {
            ACTION_TOGGLE_PLAY -> {
                val state = app.container.playback.state.value
                if (state.station != null) {
                    app.container.playback.togglePlayPause()
                } else {
                    CoroutineScope(Dispatchers.Main.immediate).launch {
                        val fallback = app.container.stations.recentsNow().firstOrNull()
                            ?: app.container.stations.carPresetsNow().firstOrNull()
                        if (fallback != null) {
                            app.container.playback.play(fallback)
                        }
                    }
                }
            }
            ACTION_PLAY_PRESET -> {
                val stationId = intent.getStringExtra(EXTRA_STATION_ID) ?: return
                val pending = goAsync()
                CoroutineScope(Dispatchers.Main.immediate).launch {
                    try {
                        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                        val saved = configuredPresets(context, widgetId)?.firstOrNull { it.id == stationId }
                        val stations = app.container.stations
                        val station = stations.localStation(stationId) ?: saved ?: stations.resolveForPlayback(stationId)
                        if (station != null) app.container.playback.play(station)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE_PLAY = "app.openradio.widget.ACTION_TOGGLE_PLAY"
        const val ACTION_PLAY_PRESET = "app.openradio.widget.ACTION_PLAY_PRESET"
        const val EXTRA_STATION_ID = "station_id"

        private fun widgetPreferences(context: Context) =
            context.getSharedPreferences("widget_presets", Context.MODE_PRIVATE)

        internal fun configuredPresets(context: Context, widgetId: Int): List<Station>? =
            widgetPreferences(context).getString(widgetId.toString(), null)?.let {
                runCatching { Json.decodeFromString<List<Station>>(it) }.getOrNull()
            }

        internal fun savePresets(context: Context, widgetId: Int, stations: List<Station>?) {
            val json = stations?.let { Json.encodeToString(it.take(3)) }
            widgetPreferences(context).edit().putString(widgetId.toString(), json).apply()
        }

        fun updateAllWidgets(
            context: Context,
            station: Station? = null,
            status: PlaybackStatus = PlaybackStatus.None,
            trackTitle: String? = null,
        ) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, RadioWidgetProvider::class.java))
            if (ids.isEmpty()) return

            val app = context.applicationContext as? RadioApp
            CoroutineScope(Dispatchers.Main.immediate).launch {
                val presets = runCatching {
                    app?.container?.stations?.carPresetsNow().orEmpty().take(3)
                }.getOrDefault(emptyList())
                val activeStation = station
                    ?: app?.container?.playback?.state?.value?.station
                    ?: runCatching { app?.container?.stations?.recentsNow()?.firstOrNull() }.getOrNull()
                val activeStatus = if (station != null) status else (app?.container?.playback?.state?.value?.status ?: PlaybackStatus.None)
                val activeTrack = trackTitle ?: app?.container?.playback?.state?.value?.trackTitle

                ids.forEach { id ->
                    val widgetPresets = configuredPresets(context, id)?.map {
                        app?.container?.stations?.localStation(it.id) ?: it
                    } ?: presets
                    val options = mgr.getAppWidgetOptions(id)
                    val expanded = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) >= 150
                    val layout = if (expanded) R.layout.widget_radio_expanded else R.layout.widget_radio
                    mgr.updateAppWidget(id, buildRemoteViews(context, id, activeStation, activeStatus, activeTrack, widgetPresets, layout))
                }
            }
        }

        private fun buildRemoteViews(
            context: Context,
            widgetId: Int,
            station: Station?,
            status: PlaybackStatus,
            trackTitle: String?,
            presets: List<Station>,
            layout: Int,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, layout)

            // Open app on root tap
            val openApp = PendingIntent.getActivity(
                context,
                100,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_root, openApp)

            val title = station?.name ?: "Nipper FM"
            val subtitle = when {
                !trackTitle.isNullOrBlank() -> trackTitle
                status == PlaybackStatus.Live -> "Live • ${station?.location?.ifEmpty { "On Air" } ?: "On Air"}"
                status == PlaybackStatus.Buffering -> "Buffering…"
                status == PlaybackStatus.Reconnecting -> "Reconnecting…"
                status == PlaybackStatus.Unavailable -> "Stream unavailable"
                station != null -> station.location.ifEmpty { "Ready to play" }
                else -> "Tap to tune in"
            }
            views.setTextViewText(R.id.widget_station_name, title)
            views.setTextViewText(R.id.widget_station_subtitle, subtitle)

            val isPlaying = status == PlaybackStatus.Live || status == PlaybackStatus.Buffering || status == PlaybackStatus.Reconnecting
            views.setImageViewResource(
                R.id.widget_play_pause,
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            )

            val toggleIntent = PendingIntent.getBroadcast(
                context,
                101,
                Intent(context, RadioWidgetProvider::class.java).setAction(ACTION_TOGGLE_PLAY),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_play_pause, toggleIntent)

            val presetIds = intArrayOf(R.id.widget_preset_1, R.id.widget_preset_2, R.id.widget_preset_3)
            presetIds.forEachIndexed { idx, viewId ->
                val p = presets.getOrNull(idx)
                if (p != null) {
                    views.setViewVisibility(viewId, View.VISIBLE)
                    views.setTextViewText(viewId, p.name)
                    val pi = PendingIntent.getBroadcast(
                        context,
                        200 + idx,
                        Intent(context, RadioWidgetProvider::class.java)
                            .setAction(ACTION_PLAY_PRESET)
                            .setData(Uri.Builder().scheme("nipperfm").authority("widget-preset")
                                .appendPath(widgetId.toString()).appendPath(p.id).build())
                            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                            .putExtra(EXTRA_STATION_ID, p.id),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                    views.setOnClickPendingIntent(viewId, pi)
                } else {
                    views.setViewVisibility(viewId, View.GONE)
                }
            }
            views.setViewVisibility(
                R.id.widget_presets_row,
                if (presets.isNotEmpty()) View.VISIBLE else View.GONE,
            )
            return views
        }
    }
}
