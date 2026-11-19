package app.openradio.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.openradio.RadioApp
import app.openradio.domain.Station
import app.openradio.ui.theme.AppearancePrefs
import app.openradio.ui.theme.NipperFmTheme

class WidgetSetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(id)?.provider
        if (provider != ComponentName(this, RadioWidgetProvider::class.java)) {
            finish()
            return
        }
        val existing = RadioWidgetProvider.configuredPresets(this, id).orEmpty()
        val container = (application as RadioApp).container
        setContent {
            val appearance by container.preferences.appearance.collectAsState(initial = AppearancePrefs())
            val favorites by container.stations.favorites.collectAsState(initial = emptyList())
            val recents by container.stations.recents.collectAsState(initial = emptyList())
            var selected by rememberSaveable {
                mutableStateOf(ArrayList(existing.map { it.id }))
            }
            val candidates = ((favorites + recents).map { it.station } + existing).distinctBy { it.id }
            NipperFmTheme(appearance) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(20.dp)) {
                        Text("Widget presets", style = MaterialTheme.typography.headlineSmall)
                        Text("Choose up to three stations in the order they should appear.")
                        LazyColumn(Modifier.weight(1f)) {
                            items(candidates, key = { it.id }) { station ->
                                val position = selected.indexOf(station.id)
                                FilterChip(
                                    selected = position >= 0,
                                    enabled = position >= 0 || selected.size < 3,
                                    onClick = {
                                        selected = ArrayList(selected).apply {
                                            if (position >= 0) removeAt(position) else add(station.id)
                                        }
                                    },
                                    label = { Text(if (position >= 0) "${position + 1}. ${station.name}" else station.name) },
                                )
                            }
                        }
                        TextButton(onClick = { save(id, null) }) { Text("Use car presets") }
                        Button(onClick = {
                            save(id, selected.mapNotNull { chosen -> candidates.firstOrNull { it.id == chosen } })
                        }) { Text("Save presets") }
                    }
                }
            }
        }
    }

    private fun save(id: Int, selected: List<Station>?) {
        RadioWidgetProvider.savePresets(this, id, selected)
        RadioWidgetProvider.updateAllWidgets(this)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        finish()
    }
}
