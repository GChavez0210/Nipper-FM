package app.openradio.ui.favorites

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.openradio.domain.PresetOrdering
import app.openradio.ui.AppViewModel
import app.openradio.ui.components.MessagePanel
import app.openradio.ui.components.PinButton
import app.openradio.ui.components.SectionHeader
import app.openradio.ui.components.StationRow
import app.openradio.ui.theme.neuRaised

@Composable
fun FavoritesScreen(vm: AppViewModel, currentStationId: String?, modifier: Modifier = Modifier) {
    val favorites by vm.favorites.collectAsState()
    val recents by vm.recents.collectAsState()
    val pinned = favorites.filter { it.pinnedOrder != null }.sortedBy { it.pinnedOrder }
    val others = favorites.filter { it.pinnedOrder == null }

    if (favorites.isEmpty() && recents.isEmpty()) {
        MessagePanel(
            "No favorites yet", "Tap the heart on any station in Explore to keep it here, even when you're offline.",
            modifier = modifier.fillMaxSize().padding(top = 64.dp),
        )
        return
    }

    LazyColumn(modifier.fillMaxSize()) {
        if (favorites.isNotEmpty()) {
            item {
                SectionHeader("Car presets", trailing = "${pinned.size} of ${PresetOrdering.MAX_PINS} pinned")
                Text(
                    if (pinned.isEmpty()) "Pin up to six favorites for Android Auto. Until you pin some, the car shows all favorites."
                    else "These appear as Presets in Android Auto, in this order.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            itemsIndexed(pinned, key = { _, s -> "pin-" + s.station.id }) { index, saved ->
                val btnShape = RoundedCornerShape(14.dp)
                StationRow(
                    station = saved.station, isFavorite = true, isCurrent = saved.station.id == currentStationId,
                    onClick = { vm.play(saved.station) }, onToggleFavorite = { vm.toggleFavorite(saved.station) },
                    trailing = {
                        IconButton(
                            onClick = { vm.movePin(index, index - 1) },
                            enabled = index > 0,
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .size(38.dp)
                                .then(if (index > 0) Modifier.neuRaised(btnShape, 3.dp) else Modifier),
                        ) {
                            Icon(Icons.Filled.ArrowUpward, "Move ${saved.station.name} up")
                        }
                        IconButton(
                            onClick = { vm.movePin(index, index + 1) },
                            enabled = index < pinned.lastIndex,
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .size(38.dp)
                                .then(if (index < pinned.lastIndex) Modifier.neuRaised(btnShape, 3.dp) else Modifier),
                        ) {
                            Icon(Icons.Filled.ArrowDownward, "Move ${saved.station.name} down")
                        }
                        PinButton(true, saved.station.name, true) { vm.togglePin(saved.station, true) }
                    },
                )
            }
            item { SectionHeader("Favorites") }
            itemsIndexed(others, key = { _, s -> "fav-" + s.station.id }) { _, saved ->
                StationRow(
                    station = saved.station, isFavorite = true, isCurrent = saved.station.id == currentStationId,
                    onClick = { vm.play(saved.station) }, onToggleFavorite = { vm.toggleFavorite(saved.station) },
                    trailing = { PinButton(false, saved.station.name) { vm.togglePin(saved.station, false) } },
                )
            }
        }
        if (recents.isNotEmpty()) {
            item { SectionHeader("Recently played") }
            itemsIndexed(recents, key = { _, s -> "rec-" + s.station.id }) { _, saved ->
                StationRow(
                    station = saved.station, isFavorite = saved.isFavorite, isCurrent = saved.station.id == currentStationId,
                    onClick = { vm.play(saved.station) }, onToggleFavorite = { vm.toggleFavorite(saved.station) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
