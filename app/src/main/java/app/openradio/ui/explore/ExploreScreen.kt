package app.openradio.ui.explore

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.openradio.domain.Country
import app.openradio.ui.AppViewModel
import app.openradio.ui.components.MessagePanel
import app.openradio.ui.components.SectionHeader
import app.openradio.ui.components.StationRow
import app.openradio.ui.theme.neuInset
import app.openradio.ui.theme.neuRaised

@Composable
fun ExploreScreen(vm: AppViewModel, currentStationId: String?, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val state by vm.explore.state.collectAsState()
    val favoriteIds by vm.favoriteIds.collectAsState()
    val filters by vm.filters.collectAsState()
    var dialog by remember { mutableStateOf<FilterDialog?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(listState) {
        snapshotFlow {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 5
        }.collect { nearEnd -> if (nearEnd) vm.explore.loadMore() }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val searchShape = RoundedCornerShape(26.dp)
            OutlinedTextField(
                value = state.query,
                onValueChange = vm.explore::setQuery,
                modifier = Modifier
                    .weight(1f)
                    .neuInset(
                        shape = searchShape,
                        depth = 4.dp,
                        accentBorder = if (state.query.isNotEmpty()) MaterialTheme.colorScheme.primary else null,
                    ),
                singleLine = true,
                placeholder = { Text("Search stations") },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        tint = if (state.query.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { vm.explore.setQuery("") }) {
                            Icon(Icons.Filled.Close, "Clear search")
                        }
                    }
                },
                shape = searchShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                ),
            )
            Spacer(Modifier.width(12.dp))
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .size(48.dp)
                    .neuRaised(shape = RoundedCornerShape(16.dp), elevation = 5.dp),
            ) {
                Icon(Icons.Filled.Settings, "Settings", tint = MaterialTheme.colorScheme.primary)
            }
        }
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            NeuFilterChip(
                selected = state.country != null,
                label = state.country?.name ?: "Country",
                onClick = { vm.loadFilters(); dialog = FilterDialog.Country },
            )
            NeuFilterChip(
                selected = state.tag != null,
                label = state.tag?.replaceFirstChar { it.uppercase() } ?: "Genre",
                onClick = { vm.loadFilters(); dialog = FilterDialog.Genre },
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.loading && state.items.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error != null -> MessagePanel(
                    "Can't reach the directory", "Check your connection. Your favorites and recents still work.",
                    "Retry", vm.explore::retry, Modifier.align(Alignment.Center),
                )
                state.isEmpty -> MessagePanel(
                    "No stations found", "Try a different name or clear a filter.",
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> LazyColumn(state = listState) {
                    item { SectionHeader(if (state.isTrending) "Trending" else "Results") }
                    items(state.items, key = { it.id }) { station ->
                        StationRow(
                            station = station,
                            isFavorite = station.id in favoriteIds,
                            isCurrent = station.id == currentStationId,
                            onClick = { vm.play(station) },
                            onToggleFavorite = { vm.toggleFavorite(station) },
                        )
                    }
                    if (state.loadingMore) item {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(28.dp))
                        }
                    }
                }
            }
        }
    }

    when (dialog) {
        FilterDialog.Country -> PickerDialog(
            title = "Country",
            options = filters.countries.map { it.name to it },
            loading = filters.loading, error = filters.error, onRetry = vm::loadFilters,
            onPick = { vm.explore.setCountry(it); dialog = null },
            onClear = { vm.explore.setCountry(null); dialog = null },
            onDismiss = { dialog = null },
            sublabel = { c: Country -> "${c.stationCount}" },
        )
        FilterDialog.Genre -> PickerDialog(
            title = "Genre",
            options = filters.tags.map { it.replaceFirstChar { c -> c.uppercase() } to it },
            loading = filters.loading, error = filters.error, onRetry = vm::loadFilters,
            onPick = { vm.explore.setTag(it); dialog = null },
            onClear = { vm.explore.setTag(null); dialog = null },
            onDismiss = { dialog = null },
            sublabel = { _: String -> null },
        )
        null -> Unit
    }
}

@Composable
private fun NeuFilterChip(selected: Boolean, label: String, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(16.dp)
    val fg = if (selected) accent else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .then(
                if (selected) {
                    Modifier.neuInset(shape = shape, depth = 3.dp, accentBorder = accent)
                } else {
                    Modifier.neuRaised(shape = shape, elevation = 4.dp)
                },
            )
            .clickable(role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = fg)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = fg,
        )
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Filled.ArrowDropDown, null, tint = fg)
    }
}

private enum class FilterDialog { Country, Genre }

@Composable
private fun <T> PickerDialog(
    title: String,
    options: List<Pair<String, T>>,
    loading: Boolean,
    error: Boolean,
    onRetry: () -> Unit,
    onPick: (T) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    sublabel: (T) -> String?,
) {
    var filter by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(filter, { filter = it }, singleLine = true, placeholder = { Text("Filter") }, modifier = Modifier.fillMaxWidth())
                when {
                    loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    error -> MessagePanel("Couldn't load the list", "", "Retry", onRetry)
                    else -> LazyColumn(Modifier.padding(top = 8.dp)) {
                        items(options.filter { it.first.contains(filter, ignoreCase = true) }, key = { it.first }) { (label, value) ->
                            Row(
                                Modifier.fillMaxWidth().androidxClickable { onPick(value) }.padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(label, style = MaterialTheme.typography.bodyLarge)
                                sublabel(value)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClear) { Text("Any") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun Modifier.androidxClickable(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(onClick = onClick))
