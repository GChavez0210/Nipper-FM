package app.openradio.playback

import android.content.ComponentName
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import app.openradio.domain.FailureKind
import app.openradio.domain.PlaybackErrorClassifier
import app.openradio.domain.PlaybackErrorDetail
import app.openradio.domain.PlaybackStatus
import app.openradio.domain.PlaybackStatusRules
import app.openradio.domain.RecoveryPhase
import app.openradio.domain.Station
import app.openradio.domain.StreamFeed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SignalInfo(
    val codec: String? = null,
    /** kbps; [bitrateFromDirectory] marks values that come from the station listing, not the stream. */
    val bitrateKbps: Int? = null,
    val bitrateFromDirectory: Boolean = false,
    val bufferedMs: Long? = null,
    val route: String? = null,
)

data class LivePosition(val positionMs: Long, val windowMs: Long) {
    val behindLiveMs: Long get() = (windowMs - positionMs).coerceAtLeast(0)
    val atLiveEdge: Boolean get() = behindLiveMs < 3_000
}

data class PlayerUiState(
    val connected: Boolean = false,
    val station: Station? = null,
    val status: PlaybackStatus = PlaybackStatus.None,
    val trackTitle: String? = null,
    val isPlaying: Boolean = false,
    /** Non-null only when the stream exposes a seekable live window. */
    val seekableLive: Boolean = false,
    val sleepEndsAtMs: Long = 0L,
    /** Sleep is waiting for the current track to end ([sleepEndsAtMs] is then only the safety cap). */
    val sleepAtEndOfTrack: Boolean = false,
    val volumeTrimDb: Int = 0,
    /** Measured network bytes since the current station started (refreshed every ~30 s). */
    val sessionBytes: Long = 0L,
    val recoveryAttempt: Int = 0,
    val recoveryMax: Int = 0,
    val errorMessage: String? = null,
    val errorDetail: PlaybackErrorDetail? = null,
    val wifiOnlyBlocked: Boolean = false,
    val audioSessionId: Int = 0,
    val signal: SignalInfo = SignalInfo(),
)

@OptIn(UnstableApi::class)
/** Phone-side MediaController wrapper: one StateFlow that mirrors the service's session. */
class PlaybackConnection(private val context: Context) {
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var connecting = false
    private val queued = mutableListOf<(MediaController) -> Unit>()

    fun connect() {
        if (controller != null || connecting) return
        connecting = true
        val token = SessionToken(context, ComponentName(context, RadioService::class.java))
        val future = MediaController.Builder(context, token)
            .setListener(object : MediaController.Listener {
                override fun onExtrasChanged(controller: MediaController, extras: Bundle) = refresh()
            })
            .buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull()
            connecting = false
            if (c != null) {
                controller = c
                c.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) = refresh()
                })
                refresh()
                queued.forEach { it(c) }
                queued.clear()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else {
            queued += block
            connect()
        }
    }

    fun play(station: Station) = withController {
        it.setMediaItem(station.toMediaItem(playable = true))
        it.prepare()
        it.play()
    }

    /** Loads [station] into the player ready to start without auto-playing (for Last Station shortcut). */
    fun prepareReady(station: Station) = withController {
        if (it.currentMediaItem?.mediaId != MediaIds.station(station.id)) {
            it.playWhenReady = false
            it.setMediaItem(station.toMediaItem(playable = true))
            it.prepare()
        }
    }

    fun retryCurrent() = withController { c ->
        c.prepare()
        c.play()
    }

    fun allowMobileDataOnce() = withController { c ->
        c.sendCustomCommand(
            SessionCommand(SessionKeys.CMD_MOBILE_OVERRIDE, Bundle.EMPTY),
            Bundle.EMPTY,
        )
    }

    fun selectStreamFeed(feed: StreamFeed) = withController { c ->
        c.sendCustomCommand(
            SessionCommand(SessionKeys.CMD_SELECT_FEED, Bundle.EMPTY),
            bundleOf(
                SessionKeys.ARG_FEED_URL to feed.streamUrl,
                SessionKeys.ARG_FEED_CODEC to feed.codec,
                SessionKeys.ARG_FEED_BITRATE to feed.bitrateKbps,
                SessionKeys.ARG_FEED_HLS to feed.isHls,
            ),
        )
    }

    fun togglePlayPause() = withController { c ->
        val s = _state.value
        when {
            s.wifiOnlyBlocked -> allowMobileDataOnce()
            s.status == PlaybackStatus.Unavailable || s.status == PlaybackStatus.Reconnecting && !c.playWhenReady -> {
                c.prepare(); c.play()
            }
            c.playWhenReady -> c.pause()
            else -> { c.prepare(); c.play() }
        }
    }

    private fun liveWindowMs(c: MediaController): Long {
        val tl = c.currentTimeline
        if (tl.isEmpty) return 0L
        val w = tl.getWindow(c.currentMediaItemIndex, androidx.media3.common.Timeline.Window())
        return seekableLiveWindowMs(w.durationMs, w.defaultPositionMs)
    }

    fun seekBack(ms: Long = 15_000) = withController { c ->
        val win = liveWindowMs(c)
        val base = if (win > 0L) c.currentPosition.coerceAtMost(win) else c.currentPosition
        c.seekTo((base - ms).coerceAtLeast(0))
    }

    fun seekForward(ms: Long = 30_000) = withController { c ->
        val win = liveWindowMs(c).takeIf { it > 0L } ?: c.duration
        val target = c.currentPosition + ms
        c.seekTo(if (win != C.TIME_UNSET && win > 0L) target.coerceAtMost(win) else target)
    }

    fun seekToFraction(fraction: Float) = withController { c ->
        val win = liveWindowMs(c).takeIf { it > 0L } ?: c.duration
        if (win != C.TIME_UNSET && win > 0L) c.seekTo((win * fraction.coerceIn(0f, 1f)).toLong())
    }

    fun jumpToLive() = withController { it.seekToDefaultPosition() }

    fun setSleepTimer(minutes: Int?) = withController { c ->
        c.sendCustomCommand(
            SessionCommand(SessionKeys.CMD_SLEEP, Bundle.EMPTY),
            bundleOf(SessionKeys.ARG_MINUTES to (minutes ?: 0)),
        )
    }

    fun addSleepMinutes(extraMinutes: Int = 15) = withController { c ->
        c.sendCustomCommand(
            SessionCommand(SessionKeys.CMD_SLEEP_ADD, Bundle.EMPTY),
            bundleOf(SessionKeys.ARG_MINUTES to extraMinutes),
        )
    }

    /** Stops playback when the stream's current track ends (needs track metadata). */
    fun sleepAtEndOfTrack() = withController { c ->
        c.sendCustomCommand(SessionCommand(SessionKeys.CMD_SLEEP_END_OF_TRACK, Bundle.EMPTY), Bundle.EMPTY)
    }

    /** Sets and remembers the current station's volume trim in whole dB. */
    fun setVolumeTrim(db: Int) = withController { c ->
        c.sendCustomCommand(
            SessionCommand(SessionKeys.CMD_SET_VOLUME_TRIM, Bundle.EMPTY),
            bundleOf(SessionKeys.ARG_TRIM_DB to db),
        )
    }

    fun livePosition(): LivePosition? {
        val c = controller ?: return null
        val win = liveWindowMs(c)
        if (!_state.value.seekableLive || win <= 0L) return null
        return LivePosition(c.currentPosition.coerceIn(0, win), win)
    }

    private fun refresh() {
        val c = controller ?: return
        val item = c.currentMediaItem
        val station = item?.toStation()
        val extras = c.sessionExtras
        val recovery = runCatching {
            RecoveryPhase.valueOf(extras.getString(SessionKeys.EXTRA_RECOVERY) ?: RecoveryPhase.Idle.name)
        }.getOrDefault(RecoveryPhase.Idle)
        val wifiBlocked = extras.getBoolean(SessionKeys.EXTRA_WIFI_BLOCKED, false)
        val deviceOnline = extras.getBoolean(SessionKeys.EXTRA_DEVICE_ONLINE, true)
        val failureKind = extras.getString(SessionKeys.EXTRA_FAILURE_KIND)?.let {
            runCatching { FailureKind.valueOf(it) }.getOrNull()
        }
        val audioSessionId = extras.getInt(SessionKeys.EXTRA_AUDIO_SESSION_ID, 0)

        val status = if (wifiBlocked && item != null) {
            PlaybackStatus.Unavailable
        } else {
            PlaybackStatusRules.derive(
                hasItem = item != null,
                playWhenReady = c.playWhenReady,
                isReady = c.playbackState == Player.STATE_READY,
                isBuffering = c.playbackState == Player.STATE_BUFFERING,
                hasError = c.playerError != null,
                recovery = recovery,
            )
        }

        val streamTitle = c.mediaMetadata.title?.toString()
        val track = streamTitle?.takeIf { it.isNotBlank() && it != station?.name }

        val win = liveWindowMs(c)
        val seekable = item != null && c.isCurrentMediaItemLive && c.isCurrentMediaItemSeekable &&
            c.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) &&
            win > 0L

        val signal = readSignal(c, station)
        val detail = if (status == PlaybackStatus.Unavailable || wifiBlocked) {
            PlaybackErrorClassifier.describe(
                failureKind = failureKind,
                deviceHasNetwork = deviceOnline,
                wifiOnlyBlocked = wifiBlocked,
                bitrateKbps = signal.bitrateKbps ?: station?.bitrate ?: 0,
            )
        } else {
            null
        }

        _state.value = PlayerUiState(
            connected = true,
            station = station,
            status = status,
            trackTitle = track,
            isPlaying = c.isPlaying,
            seekableLive = seekable,
            sleepEndsAtMs = extras.getLong(SessionKeys.EXTRA_SLEEP_ENDS_AT, 0L),
            sleepAtEndOfTrack = extras.getBoolean(SessionKeys.EXTRA_SLEEP_END_OF_TRACK, false),
            volumeTrimDb = extras.getInt(SessionKeys.EXTRA_VOLUME_TRIM_DB, 0),
            sessionBytes = extras.getLong(SessionKeys.EXTRA_SESSION_BYTES, 0L),
            recoveryAttempt = extras.getInt(SessionKeys.EXTRA_ATTEMPT, 0),
            recoveryMax = extras.getInt(SessionKeys.EXTRA_MAX_ATTEMPTS, 0),
            errorMessage = detail?.message ?: extras.getString(SessionKeys.EXTRA_MESSAGE)
                ?: if (status == PlaybackStatus.Unavailable) "This station can't be played right now." else null,
            errorDetail = detail,
            wifiOnlyBlocked = wifiBlocked,
            audioSessionId = audioSessionId,
            signal = signal,
        )
    }

    /** Re-reads signal details on demand (buffer level changes continuously). */
    fun signalSnapshot(): SignalInfo {
        val c = controller ?: return SignalInfo()
        return readSignal(c, c.currentMediaItem?.toStation())
    }

    private fun readSignal(c: MediaController, station: Station?): SignalInfo {
        val format = c.currentTracks.groups
            .firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
            ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) } }
        val codec = format?.sampleMimeType?.let(::codecLabel) ?: station?.codec?.uppercase()?.takeIf { it != "UNKNOWN" }
        val streamKbps = format?.let { f -> listOf(f.averageBitrate, f.bitrate).firstOrNull { it > 0 } }?.div(1000)
        val dirKbps = station?.bitrate?.takeIf { it > 0 }?.let { if (it > 2000) it / 1000 else it }
        return SignalInfo(
            codec = codec,
            bitrateKbps = streamKbps ?: dirKbps,
            bitrateFromDirectory = streamKbps == null && dirKbps != null,
            bufferedMs = c.takeIf { it.playbackState != Player.STATE_IDLE }?.totalBufferedDuration,
            route = describeRoute(context),
        )
    }

    companion object {

        /**
         * Returns the seekable DVR window behind the live edge ([defaultPositionMs]),
         * excluding the HLS live holdback buffer between [defaultPositionMs] and [durationMs].
         */
        fun seekableLiveWindowMs(durationMs: Long, defaultPositionMs: Long): Long {
            if (durationMs == C.TIME_UNSET || durationMs <= 0L) return 0L
            return if (defaultPositionMs in 1..durationMs) defaultPositionMs else 0L
        }

        fun codecLabel(mime: String): String = when {
            mime.contains("mpeg", true) && !mime.contains("mp4", true) -> "MP3"
            mime.contains("mp4a", true) || mime.contains("aac", true) -> "AAC"
            mime.contains("opus", true) -> "Opus"
            mime.contains("vorbis", true) -> "Vorbis"
            mime.contains("flac", true) -> "FLAC"
            else -> mime.substringAfter('/').uppercase()
        }

        /** Best-effort label of where audio is going. Returns null when it cannot be determined. */
        fun describeRoute(context: Context): String? {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
            val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            fun has(vararg types: Int) = outputs.any { it.type in types }
            return when {
                has(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) -> "Bluetooth"
                has(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET) -> "USB audio"
                has(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET) -> "Headphones"
                Build.VERSION.SDK_INT >= 31 && has(AudioDeviceInfo.TYPE_BLE_HEADSET) -> "Bluetooth LE"
                has(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) -> "Phone speaker"
                else -> null
            }
        }
    }
}
