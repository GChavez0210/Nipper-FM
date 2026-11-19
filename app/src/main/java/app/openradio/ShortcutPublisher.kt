package app.openradio

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.openradio.domain.Station

/**
 * Keeps a dynamic "Play <station>" launcher/Assistant shortcut pointing at the most recently
 * played station. It reuses [MainActivity.ACTION_PLAY_LAST_STATION], which always plays the
 * newest recent, so the shortcut stays correct even if an update is missed.
 */
object ShortcutPublisher {
    private const val ID = "play_recent_dynamic"

    fun pinStation(context: Context, station: Station): Boolean = runCatching {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_PLAY_STATION)
            .setData(Uri.Builder().scheme("nipperfm").authority("station").appendPath(station.id).build())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val shortcut = ShortcutInfoCompat.Builder(context, "station:${station.id}")
            .setShortLabel(station.name.take(SHORT_LABEL_MAX).ifBlank { "Station" })
            .setLongLabel("Play ${station.name}".take(LONG_LABEL_MAX))
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(intent)
            .build()
        ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
    }.getOrDefault(false)

    fun reportPlayed(context: Context, station: Station) {
        runCatching {
            val intent = Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_PLAY_LAST_STATION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val shortcut = ShortcutInfoCompat.Builder(context, ID)
                .setShortLabel(station.name.take(SHORT_LABEL_MAX))
                .setLongLabel("Play ${station.name}".take(LONG_LABEL_MAX))
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(intent)
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        }
    }

    private const val SHORT_LABEL_MAX = 25
    private const val LONG_LABEL_MAX = 40
}
