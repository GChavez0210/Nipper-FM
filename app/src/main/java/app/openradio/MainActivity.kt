package app.openradio

import android.Manifest
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
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.openradio.ui.AppRoot
import app.openradio.ui.AppViewModel
import app.openradio.ui.theme.OpenRadioTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
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

        setContent {
            val prefs by vm.appearance.collectAsState()
            val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            OpenRadioTheme(prefs) { AppRoot(vm) }
        }
    }
}
