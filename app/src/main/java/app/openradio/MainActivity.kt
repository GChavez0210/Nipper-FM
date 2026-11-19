package app.openradio

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.getSystemService
import androidx.lifecycle.ViewModelProvider
import app.openradio.playback.RadioService
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.openradio.ui.AppRoot
import app.openradio.ui.AppViewModel
import app.openradio.ui.theme.NipperFmTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    private var viewModel: AppViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as RadioApp).container
        // One quick read so the first frame already uses the saved theme (no flash).
        val initial = runBlocking { container.preferences.appearance.first() }
        val vm = ViewModelProvider(
            this,
            viewModelFactory { initializer { AppViewModel(container, initial) } },
        )[AppViewModel::class.java]
        viewModel = vm
        handleShortcutIntent(intent, vm)

        setContent {
            val prefs by vm.appearance.collectAsState()
            // Location (coarse) pre-selects the user's country in Explore; detection runs once the
            // prompt resolves and falls back to the mobile network country if it is denied.
            val askPermissions = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { vm.detectCountryOnce() }
            LaunchedEffect(Unit) {
                val wanted = buildList {
                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                }
                askPermissions.launch(wanted.toTypedArray())
            }
            NipperFmTheme(prefs) { AppRoot(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel?.let { handleShortcutIntent(intent, it) }
    }

    private fun handleShortcutIntent(intent: Intent?, vm: AppViewModel) {
        when (intent?.action) {
            ACTION_LAST_STATION -> vm.requestOpenLastStationShortcut()
            ACTION_OPEN_SLEEP_TIMER -> {
                getSystemService<NotificationManager>()?.cancel(RadioService.SLEEP_SETUP_NOTIFICATION_ID)
                vm.requestOpenSleepTimer()
                setIntent(Intent(this, MainActivity::class.java))
            }
            ACTION_PLAY_STATION -> {
                val uri = intent.data
                if (uri?.scheme == "nipperfm" && uri.authority == "station") {
                    uri.pathSegments.singleOrNull()?.let(vm::playStationShortcut)
                }
                setIntent(Intent(this, MainActivity::class.java))
            }
            ACTION_PLAY_LAST_STATION -> {
                vm.playLastStationNow()
                // Don't replay on rotation / process recreation.
                setIntent(Intent(this, MainActivity::class.java))
            }
        }
    }

    companion object {
        const val ACTION_OPEN_SLEEP_TIMER = "app.openradio.action.OPEN_SLEEP_TIMER"
        const val ACTION_PLAY_STATION = "app.openradio.action.PLAY_STATION"
        const val ACTION_LAST_STATION = "app.openradio.action.LAST_STATION"
        /** Plays the most recent station immediately (Assistant, dynamic shortcut). */
        const val ACTION_PLAY_LAST_STATION = "app.openradio.action.PLAY_LAST_STATION"
    }
}
