package app.openradio.ui

import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.VerticalDivider
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.openradio.domain.PlaybackStatus
import app.openradio.ui.components.PlayPauseButton
import app.openradio.ui.components.StationArt
import app.openradio.ui.explore.ExploreScreen
import app.openradio.ui.favorites.FavoritesScreen
import app.openradio.ui.nowplaying.NowPlayingScreen
import app.openradio.ui.settings.AboutScreen
import app.openradio.ui.settings.SettingsScreen
import app.openradio.ui.nowplaying.SleepTimerScreen
import androidx.compose.material3.Surface
import app.openradio.ui.theme.neuInset
import app.openradio.ui.theme.neuRaised
import kotlinx.coroutines.delay

private object Routes {
    const val HOME = "home"
    const val NOW_PLAYING = "now_playing"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    const val SLEEP_TIMER = "sleep_timer"
}

@Composable
fun AppRoot(vm: AppViewModel) {
    val nav = rememberNavController()
    val pendingLastStation by vm.pendingOpenLastStation.collectAsState()
    LaunchedEffect(pendingLastStation) {
        if (pendingLastStation) {
            vm.consumeOpenLastStationShortcut {
                nav.navigate(Routes.NOW_PLAYING) {
                    launchSingleTop = true
                }
            }
        }
    }
    val openSleepTimer by vm.pendingOpenSleepTimer.collectAsState()
    LaunchedEffect(openSleepTimer) {
        if (openSleepTimer) {
            nav.navigate(Routes.SLEEP_TIMER) { launchSingleTop = true }
            vm.sleepTimerOpened()
        }
    }
    Surface(Modifier.fillMaxSize().systemBarsPadding(), color = MaterialTheme.colorScheme.background) {
        NavHost(
            navController = nav, startDestination = Routes.HOME,
            enterTransition = { androidx.compose.animation.fadeIn() },
            exitTransition = { androidx.compose.animation.fadeOut() },
        ) {
            composable(Routes.HOME) {
                HomeScreen(vm, onOpenNowPlaying = { nav.navigate(Routes.NOW_PLAYING) }, onOpenSettings = { nav.navigate(Routes.SETTINGS) })
            }
            composable(
                Routes.NOW_PLAYING,
                enterTransition = { slideInVertically { it } },
                exitTransition = { androidx.compose.animation.fadeOut() },
                popExitTransition = { slideOutVertically { it } },
            ) { NowPlayingScreen(vm, onClose = { nav.popBackStack() }) }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    vm,
                    onBack = { nav.popBackStack() },
                    onOpenAbout = { nav.navigate(Routes.ABOUT) { launchSingleTop = true } },
                )
            }
            composable(Routes.ABOUT) { AboutScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.SLEEP_TIMER) { SleepTimerScreen(vm, onBack = { nav.popBackStack() }) }
        }
    }
}

@Composable
private fun HomeScreen(vm: AppViewModel, onOpenNowPlaying: () -> Unit, onOpenSettings: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val ui by vm.playback.state.collectAsState()
    val message by vm.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        val m = message ?: return@LaunchedEffect
        try {
            snackbar.showSnackbar(m)
        } finally {
            vm.messageShown()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val twoPane = maxWidth >= 600.dp
        val openPlayer = if (twoPane) ({}) else onOpenNowPlaying
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (!twoPane && ui.station != null) {
                        MiniPlayer(vm, onOpenNowPlaying)
                        Spacer(Modifier.height(10.dp))
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .neuRaised(shape = RoundedCornerShape(26.dp), elevation = 6.dp)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        NeuNavTab(
                            selected = tab == 0,
                            label = "Explore",
                            icon = if (tab == 0) Icons.Filled.Explore else Icons.Outlined.Explore,
                            onClick = { tab = 0 },
                            modifier = Modifier.weight(1f),
                        )
                        NeuNavTab(
                            selected = tab == 1,
                            label = "Favorites",
                            icon = if (tab == 1) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            onClick = { tab = 1 },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (tab == 0) {
                        ExploreScreen(vm, ui.station?.id, onOpenSettings, openPlayer)
                    } else {
                        FavoritesScreen(vm, ui.station?.id, openPlayer)
                    }
                }
                if (twoPane) {
                    VerticalDivider()
                    Box(Modifier.weight(0.9f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        if (ui.station == null) {
                            Text("Choose a station to start listening")
                        } else {
                            NowPlayingScreen(vm, onClose = null)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NeuNavTab(
    selected: Boolean,
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(18.dp)
    val fg = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .then(
                if (selected) {
                    Modifier.neuInset(shape = shape, depth = 4.dp, accentBorder = accent)
                } else {
                    Modifier.neuRaised(shape = shape, elevation = 3.dp)
                },
            )
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = fg,
        )
    }
}

@Composable
private fun MiniPlayer(vm: AppViewModel, onClick: () -> Unit) {
    val ui by vm.playback.state.collectAsState()
    val station = ui.station ?: return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ui.sleepEndsAtMs) {
        while (ui.sleepEndsAtMs > 0) { now = System.currentTimeMillis(); delay(1_000) }
    }
    val sleepText = if (ui.sleepAtEndOfTrack) {
        "Sleep after track"
    } else if (ui.sleepEndsAtMs > 0) {
        val total = ((ui.sleepEndsAtMs - now).coerceAtLeast(0) / 1000).toInt()
        "Sleep %d:%02d".format(total / 60, total % 60)
    } else {
        null
    }
    val statusText = if (ui.wifiOnlyBlocked) "Wi-Fi only" else when (ui.status) {
        PlaybackStatus.Live -> "Live"
        PlaybackStatus.Buffering -> "Buffering"
        PlaybackStatus.Reconnecting -> "Reconnecting"
        PlaybackStatus.Paused -> "Paused"
        PlaybackStatus.Unavailable -> "Unavailable"
        PlaybackStatus.None -> ""
    }
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .neuRaised(
                shape = RoundedCornerShape(22.dp),
                elevation = 2.dp,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StationArt(station, 46.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(station.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(station.state ?: station.country, statusText.takeIf { it.isNotEmpty() }).joinToString(" • ")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (sleepText != null) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Outlined.Bedtime, contentDescription = null, tint = accent, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(sleepText, style = MaterialTheme.typography.labelSmall, color = accent, maxLines = 1)
                }
            }
        }
        PlayPauseButton(
            isPlaying = ui.status == PlaybackStatus.Live || ui.status == PlaybackStatus.Buffering || ui.status == PlaybackStatus.Reconnecting,
            size = 46.dp,
            onClick = vm.playback::togglePlayPause,
        )
    }
}
