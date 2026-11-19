package app.openradio.ui.favorites

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import app.openradio.domain.FavoriteSort
import app.openradio.domain.FavoritesQuery
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.openradio.domain.PresetOrdering
import app.openradio.domain.SavedStation
import app.openradio.ui.AppViewModel
import app.openradio.ui.components.CustomStreamDialog
import app.openradio.ui.components.GroupAssignDialog
import app.openradio.ui.components.LastStationShortcutCard
import app.openradio.ui.components.MessagePanel
import app.openradio.ui.components.NeuDropdownSelector
import app.openradio.ui.components.NeuMenuOption
import app.openradio.ui.components.PinButton
import app.openradio.ui.components.SectionHeader
import app.openradio.ui.components.StationRow
import app.openradio.ui.theme.neuInset
import app.openradio.ui.theme.neuRaised

@Composable
fun FavoritesScreen(
    vm: AppViewModel,
    currentStationId: String?,
    onOpenNowPlaying: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val favorites by vm.favorites.collectAsState()
    val recents by vm.recents.collectAsState()
    val failedIds by vm.failedIds.collectAsState()
    val groups by vm.favoriteGroups.collectAsState()
    val selectedGroup by vm.selectedGroup.collectAsState()
    val lastStation by vm.lastStation.collectAsState()

    var editingStationGroup by remember { mutableStateOf<SavedStation?>(null) }
    var manageCarPresets by remember { mutableStateOf(false) }
    var showNewGroupDialog by remember { mutableStateOf(false) }
    var showCustomStreamDialog by remember { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(FavoriteSort.RecentlyAdded) }

    val pinned = favorites.filter { it.pinnedOrder != null }.sortedBy { it.pinnedOrder }
    val filteredFavorites = remember(favorites, selectedGroup, manageCarPresets, searchQuery, sort) {
        val inScope = favorites.filter { saved ->
            (!manageCarPresets || saved.pinnedOrder == null) &&
                (selectedGroup == null || saved.groupName.equals(selectedGroup, ignoreCase = true))
        }
        FavoritesQuery.apply(inScope, searchQuery, sort)
    }

    if (favorites.isEmpty() && recents.isEmpty()) {
        Column(modifier.fillMaxSize()) {
            MessagePanel(
                "No favorites yet",
                "Tap a heart to save a station.",
                "Add stream",
                { showCustomStreamDialog = true },
                modifier = Modifier.padding(top = 48.dp),
            )
        }
        if (showCustomStreamDialog) {
            CustomStreamDialog(vm = vm, initialGroup = selectedGroup, onDismiss = { showCustomStreamDialog = false })
        }
        return
    }

    LazyColumn(modifier.fillMaxSize()) {
        if (currentStationId == null && lastStation != null) {
            item(key = "fav-last-station") {
                val last = lastStation!!
                LastStationShortcutCard(
                    station = last,
                    onOpenReady = { vm.openLastStationReady(last, onOpened = onOpenNowPlaying) },
                    onPlayNow = { vm.play(last) },
                )
            }
        }

        if (favorites.isNotEmpty()) {
            item {
                TextButton(
                    onClick = { manageCarPresets = !manageCarPresets },
                    modifier = Modifier.padding(start = 12.dp),
                ) {
                    Text(if (manageCarPresets) "Done with car presets" else "Manage car presets")
                }
            }
            if (manageCarPresets) item {
                SectionHeader("Car presets", trailing = "${pinned.size} of ${PresetOrdering.MAX_PINS}")
            }
            if (manageCarPresets) itemsIndexed(pinned, key = { _, s -> "pin-" + s.station.id }) { index, saved ->
                val btnShape = RoundedCornerShape(14.dp)
                StationRow(
                    station = saved.station,
                    isFavorite = true,
                    isCurrent = saved.station.id == currentStationId,
                    failedLastAttempt = saved.failedLastAttempt || saved.station.id in failedIds,
                    onClick = { vm.play(saved.station) },
                    onToggleFavorite = { vm.toggleFavorite(saved.station) },
                    trailing = {
                        GroupTagButton(saved.groupName) { editingStationGroup = saved }
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

            item {
                val groupMenuOptions = remember(groups, favorites, selectedGroup) {
                    buildList {
                        add(
                            NeuMenuOption(
                                label = "All Favorites (${favorites.size})",
                                value = "all",
                                icon = Icons.Outlined.Folder,
                            ),
                        )
                        groups.forEach { g ->
                            val count = favorites.count { it.groupName.equals(g, ignoreCase = true) }
                            add(
                                NeuMenuOption(
                                    label = if (count > 0) "$g ($count)" else g,
                                    value = "group:$g",
                                    icon = Icons.Outlined.Folder,
                                ),
                            )
                        }
                        add(
                            NeuMenuOption(
                                label = "New collection…",
                                value = "action:new_group",
                                icon = Icons.Filled.Add,
                            ),
                        )
                        add(
                            NeuMenuOption(
                                label = "Add stream…",
                                value = "action:custom_url",
                                icon = Icons.Filled.Link,
                            ),
                        )
                        if (selectedGroup != null) {
                            add(
                                NeuMenuOption(
                                    label = "Delete collection \"$selectedGroup\"",
                                    value = "action:delete_group",
                                    icon = Icons.Filled.DeleteOutline,
                                ),
                            )
                        }
                    }
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        selectedGroup ?: "Favorites",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    NeuDropdownSelector(
                        label = selectedGroup ?: "Collections",
                        active = selectedGroup != null,
                        options = groupMenuOptions,
                        leadingIcon = Icons.Outlined.Folder,
                        onSelect = { action ->
                            when {
                                action == "all" -> vm.selectGroup(null)
                                action.startsWith("group:") -> vm.selectGroup(action.removePrefix("group:"))
                                action == "action:new_group" -> showNewGroupDialog = true
                                action == "action:custom_url" -> showCustomStreamDialog = true
                                action == "action:delete_group" && selectedGroup != null -> vm.deleteGroup(selectedGroup!!)
                            }
                        },
                    )
                }
            }

            item(key = "fav-search") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search favorites") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Clear search")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f),
                    )
                    NeuDropdownSelector(
                        label = sort.label,
                        active = sort != FavoriteSort.RecentlyAdded,
                        leadingIcon = Icons.AutoMirrored.Filled.Sort,
                        options = FavoriteSort.entries.map { NeuMenuOption(it.label, it) },
                        onSelect = { sort = it },
                    )
                }
            }

            if (filteredFavorites.isEmpty() && searchQuery.isNotBlank()) {
                item {
                    MessagePanel(
                        "No matches",
                        "No favorites match \"${searchQuery.trim()}\".",
                    )
                }
            } else if (filteredFavorites.isEmpty() && selectedGroup != null) {
                item {
                    MessagePanel(
                        "No stations in $selectedGroup",
                        "Use the folder icon to add stations.",
                    )
                }
            } else {
                itemsIndexed(filteredFavorites, key = { _, s -> "fav-" + s.station.id }) { _, saved ->
                    val isPinned = saved.pinnedOrder != null
                    StationRow(
                        station = saved.station,
                        isFavorite = true,
                        isCurrent = saved.station.id == currentStationId,
                        failedLastAttempt = saved.failedLastAttempt || saved.station.id in failedIds,
                        onClick = { vm.play(saved.station) },
                        onToggleFavorite = { vm.toggleFavorite(saved.station) },
                        trailing = {
                            GroupTagButton(saved.groupName) { editingStationGroup = saved }
                            if (manageCarPresets) {
                                PinButton(isPinned, saved.station.name) { vm.togglePin(saved.station, isPinned) }
                            }
                        },
                    )
                }
            }
        }

        if (recents.isNotEmpty()) {
            item { SectionHeader("Recently played") }
            itemsIndexed(recents, key = { _, s -> "rec-" + s.station.id }) { _, saved ->
                StationRow(
                    station = saved.station,
                    isFavorite = saved.isFavorite,
                    isCurrent = saved.station.id == currentStationId,
                    failedLastAttempt = saved.failedLastAttempt || saved.station.id in failedIds,
                    onClick = { vm.play(saved.station) },
                    onToggleFavorite = { vm.toggleFavorite(saved.station) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    editingStationGroup?.let { saved ->
        GroupAssignDialog(
            station = saved.station,
            groups = groups,
            currentGroup = saved.groupName,
            onAssign = { g -> vm.assignStationGroup(saved.station, g) },
            onCreateGroup = vm::createGroup,
            onDismiss = { editingStationGroup = null },
        )
    }

    if (showNewGroupDialog) {
        NewGroupDialog(
            onCreate = {
                vm.createGroup(it)
                showNewGroupDialog = false
            },
            onDismiss = { showNewGroupDialog = false },
        )
    }

    if (showCustomStreamDialog) {
        CustomStreamDialog(
            vm = vm,
            initialGroup = selectedGroup,
            onDismiss = { showCustomStreamDialog = false },
        )
    }
}

@Composable
private fun GroupTagButton(groupName: String?, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .padding(end = 6.dp)
            .then(
                if (groupName != null) {
                    Modifier.neuInset(shape = shape, depth = 2.dp, accentBorder = accent.copy(alpha = 0.6f))
                } else {
                    Modifier.neuRaised(shape = shape, elevation = 2.5.dp)
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Folder,
            contentDescription = if (groupName != null) "Collection: $groupName" else "Add to collection",
            tint = if (groupName != null) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        if (groupName != null) {
            Spacer(Modifier.width(4.dp))
            Text(
                groupName,
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun NewGroupDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New collection") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Collection name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = { onCreate(name) },
                enabled = name.isNotBlank(),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
