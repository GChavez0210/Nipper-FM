package app.openradio.ui.explore

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import app.openradio.domain.Country
import app.openradio.domain.GenreCatalog
import app.openradio.domain.MoodCollection
import app.openradio.ui.AppViewModel
import app.openradio.ui.components.CompactNeuSearchField
import app.openradio.ui.components.CustomStreamDialog
import app.openradio.ui.components.LastStationShortcutCard
import app.openradio.ui.components.MessagePanel
import app.openradio.ui.components.NeuDropdownSelector
import app.openradio.ui.components.NeuMenuOption
import app.openradio.ui.components.SectionHeader
import app.openradio.ui.components.StationRow
import app.openradio.ui.theme.neuRaised

private enum class DiscoverAction { SurpriseMe, CustomStream }

/** Shared with Android Auto's Genres folder and voice requests. */
private val genres = GenreCatalog.GENRES.map { it.label to it.tag }

@Composable
fun ExploreScreen(
    vm: AppViewModel,
    currentStationId: String?,
    onOpenSettings: () -> Unit,
    onOpenNowPlaying: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by vm.explore.state.collectAsState()
    val favoriteIds by vm.favoriteIds.collectAsState()
    val failedIds by vm.failedIds.collectAsState()
    val filters by vm.filters.collectAsState()
    val genreOptions = remember(filters.tags) {
        val tagRanks = filters.tags.withIndex().associate { (rank, tag) -> tag.lowercase() to rank }
        genres.sortedBy { (_, tag) -> tagRanks[tag] ?: Int.MAX_VALUE }
    }
    val lastStation by vm.lastStation.collectAsState()
    LaunchedEffect(Unit) {
        vm.loadFilters()
        vm.loadPopularCountries()
    }
    var dialog by remember { mutableStateOf<FilterDialog?>(null) }
    var showCustomStream by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val spoken = res.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (spoken.isNotEmpty()) {
                vm.explore.setQuery(spoken)
            }
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 5
        }.collect { nearEnd -> if (nearEnd) vm.explore.loadMore() }
    }

    Column(modifier.fillMaxSize()) {
        // Sleek single-line search bar + settings button
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactNeuSearchField(
                value = state.query,
                onValueChange = vm.explore::setQuery,
                placeholder = "Search station, city, or MHz",
                modifier = Modifier.weight(1f),
                trailingContent = {
                    IconButton(
                        onClick = {
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(
                                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                                )
                                putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a station, city, callsign, or genre")
                            }
                            runCatching { voiceLauncher.launch(intent) }
                                .onFailure { vm.showMessage("Voice search isn't available on this device.") }
                        },
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            Icons.Filled.Mic,
                            contentDescription = "Voice search",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
            )
            Spacer(Modifier.width(10.dp))
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .size(46.dp)
                    .neuRaised(shape = RoundedCornerShape(16.dp), elevation = 4.5.dp),
            ) {
                Icon(Icons.Filled.Settings, "Settings", tint = MaterialTheme.colorScheme.primary)
            }
        }

        // Minimalistic 3-dropdown selector bar (replaces 2 rows of 13 buttons/chips)
        val activeMood = MoodCollection.entries.firstOrNull {
            it.tag.equals(state.tag, ignoreCase = true)
        }
        val moodOrGenreLabel = when {
            activeMood != null -> activeMood.label
            state.tag != null -> genres.firstOrNull { it.second == state.tag }?.first ?: state.tag.orEmpty()
            else -> "Genres"
        }

        val moodOptions = remember(state.tag) {
            buildList {
                add(NeuMenuOption("All genres", ""))
                add(NeuMenuOption("Browse genres…", "dialog:genre", icon = Icons.Filled.Tune))
                MoodCollection.entries.forEach { m ->
                    add(
                        NeuMenuOption(
                            label = m.label,
                            value = "mood:${m.tag}",
                            icon = Icons.Filled.AutoAwesome,
                        ),
                    )
                }
            }
        }

        val popularCountryCodes by vm.popularCountryCodes.collectAsState()
        val countriesByCode = remember(filters.countries) { filters.countries.associateBy { it.code.uppercase() } }
        val countryOptions = buildList {
            add(NeuMenuOption("All countries", ""))
            popularCountryCodes.forEach { code ->
                countriesByCode[code]?.let { add(NeuMenuOption(it.flaggedName(), code)) }
            }
            add(NeuMenuOption("More countries…", "more", icon = Icons.Filled.Public))
        }

        val discoverOptions = listOf(
            NeuMenuOption("Surprise Me", DiscoverAction.SurpriseMe, icon = Icons.Filled.Shuffle),
            NeuMenuOption("Add stream…", DiscoverAction.CustomStream, icon = Icons.Filled.Add),
        )

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeuDropdownSelector(
                label = moodOrGenreLabel,
                active = state.tag != null,
                options = moodOptions,
                onSelect = { chosen ->
                    when {
                        chosen.isEmpty() -> vm.explore.setTag(null)
                        chosen == "dialog:genre" -> {
                            vm.loadFilters()
                            dialog = FilterDialog.Genre
                        }
                        chosen.startsWith("mood:") -> {
                            val tag = chosen.removePrefix("mood:")
                            vm.explore.setTag(tag)
                        }
                    }
                },
                modifier = Modifier.weight(1.15f),
            )

            NeuDropdownSelector(
                label = "Country: ${state.country?.flaggedName() ?: "All"}",
                active = state.country != null,
                options = countryOptions,
                onSelect = { code ->
                    when (code) {
                        "" -> vm.explore.setCountry(null)
                        "more" -> {
                            vm.loadFilters()
                            dialog = FilterDialog.Country
                        }
                        else -> countriesByCode[code]?.let(vm.explore::setCountry)
                    }
                },
                onMenuOpened = {
                    vm.loadFilters()
                    vm.loadPopularCountries()
                },
                modifier = Modifier.weight(2.1f),
                fillAnchor = true,
                labelOverflow = TextOverflow.Clip,
            )

            NeuDropdownSelector(
                label = "Tools",
                active = false,
                options = discoverOptions,
                leadingIcon = Icons.Filled.Tune,
                iconOnly = true,
                onSelect = { action ->
                    when (action) {
                        DiscoverAction.SurpriseMe -> vm.surpriseMe()
                        DiscoverAction.CustomStream -> showCustomStream = true
                    }
                },
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.loading && state.items.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error != null -> MessagePanel(
                    "Directory unavailable", "",
                    "Retry", vm.explore::retry, Modifier.align(Alignment.Center),
                )
                state.isEmpty -> MessagePanel(
                    "No stations found", "",
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> LazyColumn(state = listState) {
                    if (currentStationId == null && lastStation != null) {
                        item(key = "last-station-shortcut") {
                            val last = lastStation!!
                            LastStationShortcutCard(
                                station = last,
                                onOpenReady = {
                                    vm.openLastStationReady(last, onOpened = onOpenNowPlaying)
                                },
                                onPlayNow = { vm.play(last) },
                            )
                        }
                    }
                    item {
                        val headerTitle = when {
                            activeMood != null && state.query.isBlank() -> activeMood.label
                            state.isTrending -> "Trending"
                            else -> "Results"
                        }
                        SectionHeader(headerTitle)
                    }
                    items(state.items, key = { it.id }) { station ->
                        StationRow(
                            station = station,
                            isFavorite = station.id in favoriteIds,
                            isCurrent = station.id == currentStationId,
                            failedLastAttempt = station.id in failedIds,
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

    if (showCustomStream) {
        CustomStreamDialog(
            vm = vm,
            onDismiss = { showCustomStream = false },
        )
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
            flag = { countryFlag(it.code) },
        )
        FilterDialog.Genre -> PickerDialog(
            title = "Genre",
            options = genreOptions,
            loading = false, error = false, onRetry = {},
            onPick = { vm.explore.setTag(it); dialog = null },
            onClear = { vm.explore.setTag(null); dialog = null },
            onDismiss = { dialog = null },
            sublabel = { _: String -> null },
        )
        null -> Unit
    }
}

private enum class FilterDialog { Country, Genre }

private fun Country.flaggedName(): String =
    countryFlag(code)?.let { "$it $name" } ?: name

private fun countryFlag(code: String): String? {
    val letters = code.uppercase()
    if (letters.length != 2 || letters.any { it !in 'A'..'Z' }) return null
    return buildString {
        letters.forEach { appendCodePoint(0x1F1E6 + (it - 'A')) }
    }
}

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
    flag: (T) -> String? = { null },
) {
    var filter by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(filter, { filter = it }, singleLine = true, placeholder = { Text("Filter") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                when {
                    loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    error -> MessagePanel("Couldn't load the list", "", "Retry", onRetry)
                    else -> LazyColumn(Modifier.padding(top = 4.dp)) {
                        items(options.filter { it.first.contains(filter, ignoreCase = true) }, key = { it.first }) { (label, value) ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onPick(value) }.padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                flag(value)?.let { emoji ->
                                    Text(emoji, Modifier.clearAndSetSemantics {})
                                }
                                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
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
