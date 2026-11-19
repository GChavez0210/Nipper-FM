package app.openradio.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.annotation.OptIn
import androidx.car.app.connection.CarConnection
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.Observer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import app.openradio.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Handles a user-created exact alarm and owns the local fallback sound. */
@OptIn(UnstableApi::class)
class ListeningRoutineService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val carConnection by lazy { CarConnection(this).type }
    private val carObserver = Observer<Int> { state ->
        if (state != CarConnection.CONNECTION_TYPE_NOT_CONNECTED) stopRoutine()
    }
    private var job: Job? = null
    private var ringtone: Ringtone? = null
    private var pendingStation: Pair<MediaController, String>? = null

    private fun stopRoutine() {
        pendingStation?.let { (controller, mediaId) ->
            if (controller.currentMediaItem?.mediaId == mediaId) controller.pause()
        }
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRoutine()
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_NOT_STICKY
        val routine = ListeningRoutines(this).list().firstOrNull { it.id == intent?.getStringExtra(ListeningRoutines.EXTRA_ROUTINE) }
        showNotification(routine?.stationName.orEmpty(), fallback = false)
        if (routine == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        carConnection.observeForever(carObserver)
        job = scope.launch {
            try {
                val disconnected = withTimeoutOrNull(4_000) {
                    while (carConnection.value == null) delay(50)
                    carConnection.value == CarConnection.CONNECTION_TYPE_NOT_CONNECTED
                } == true
                if (disconnected) playRoutine(routine)
            } finally {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun playRoutine(routine: ListeningRoutine) {
        val token = SessionToken(this, ComponentName(this, RadioService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        try {
            val controller = withTimeoutOrNull(5_000) { future.await() } ?: return
            if (controller.playWhenReady) return
            val command = SessionCommand(SessionKeys.CMD_PLAY_ROUTINE, Bundle.EMPTY)
            val args = Bundle().apply {
                putString(ListeningRoutines.EXTRA_ROUTINE, routine.id)
            }
            val result = withTimeoutOrNull(5_000) { controller.sendCustomCommand(command, args).await() }
            val mediaId = MediaIds.station(routine.stationId)
            val started = result?.resultCode == SessionResult.RESULT_SUCCESS
            if (started) pendingStation = controller to mediaId
            val playing = started && withTimeoutOrNull(18_000) {
                while (controller.currentMediaItem?.mediaId != mediaId ||
                    (!controller.playWhenReady && controller.playerError == null &&
                        !controller.sessionExtras.getBoolean(SessionKeys.EXTRA_WIFI_BLOCKED))
                ) delay(100)
                while (controller.currentMediaItem?.mediaId == mediaId &&
                    controller.playWhenReady && !controller.isPlaying && controller.playerError == null
                ) delay(100)
                controller.currentMediaItem?.mediaId == mediaId && controller.isPlaying
            } == true
            if (playing || !routine.wakeUp || carConnection.value != CarConnection.CONNECTION_TYPE_NOT_CONNECTED) return
            val currentMediaId = controller.currentMediaItem?.mediaId
            if (controller.isPlaying || (started && currentMediaId != mediaId)) return
            if (currentMediaId == mediaId && controller.playerError == null && !controller.playWhenReady &&
                !controller.sessionExtras.getBoolean(SessionKeys.EXTRA_WIFI_BLOCKED)
            ) return
            if (currentMediaId == mediaId && controller.playWhenReady) controller.pause()
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) return
            ringFallback(routine.stationName)
        } finally {
            pendingStation = null
            MediaController.releaseFuture(future)
        }
    }

    private suspend fun ringFallback(stationName: String) {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val sound = RingtoneManager.getRingtone(this, uri) ?: return
        ringtone = sound
        sound.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        if (Build.VERSION.SDK_INT >= 28) sound.isLooping = true
        showNotification(stationName, fallback = true)
        sound.play()
        delay(60_000)
    }

    private fun showNotification(stationName: String, fallback: Boolean) {
        val manager = checkNotNull(getSystemService<NotificationManager>())
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Scheduled listening", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, ListeningRoutineService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_radio)
            .setContentTitle(if (fallback) "Radio wake-up alarm" else "Scheduled listening")
            .setContentText(if (fallback) "Could not play $stationName" else stationName)
            .setOngoing(true)
            .addAction(0, "Stop", stop)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        carConnection.removeObserver(carObserver)
        ringtone?.stop()
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "listening_routines"
        const val ACTION_STOP = "app.openradio.action.STOP_LISTENING_ROUTINE"
        const val NOTIFICATION_ID = 8702
    }
}
