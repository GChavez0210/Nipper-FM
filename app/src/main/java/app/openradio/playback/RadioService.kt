package app.openradio.playback

import android.app.ActivityOptions
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.Observer
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.extractor.metadata.id3.ApicFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.core.net.toUri
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import app.openradio.MainActivity
import app.openradio.R
import app.openradio.RadioApp
import app.openradio.data.remote.RadioBrowserClient
import app.openradio.data.remote.TrackArtworkClient
import app.openradio.domain.TrackInfoParser
import app.openradio.domain.AlternateFeedSelector
import app.openradio.domain.BufferProfile
import app.openradio.domain.CarPlaylists
import app.openradio.domain.CarPrefs
import app.openradio.domain.CarQueue
import app.openradio.domain.ErrorLogEntry
import app.openradio.domain.EqPreset
import app.openradio.domain.FailureKind
import app.openradio.domain.PlaybackErrorClassifier
import app.openradio.domain.PlaybackPrefs
import app.openradio.domain.PlaybackStatus
import app.openradio.domain.PlaybackStatusRules
import app.openradio.domain.RecoveryPhase
import app.openradio.domain.ReconnectController
import app.openradio.domain.SleepTimer
import app.openradio.domain.Station
import app.openradio.domain.StreamFeed
import app.openradio.domain.StreamUrlPreference
import app.openradio.ShortcutPublisher
import app.openradio.tile.LastStationTileService
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import app.openradio.domain.CountryNames
import app.openradio.domain.GenreCatalog
import app.openradio.domain.VoiceMatch
import app.openradio.domain.VoiceRequestParser
import app.openradio.domain.VolumeTrim
import app.openradio.widget.RadioWidgetProvider
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch

/**
 * Owns the single ExoPlayer and the MediaLibrarySession used by the phone UI, the system media
 * controls, home-screen widget, and Android Auto. Reconnects, alternate-feed failover, Wi-Fi-only
 * gating, audio processing (EQ/Spatial/Loudness), the sleep timer, and recents bookkeeping live
 * here so they keep working with the screen locked and no UI present.
 */
@OptIn(UnstableApi::class)
class RadioService : MediaLibraryService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container get() = (application as RadioApp).container
    private val repo get() = container.stations
    private val prefsRepo get() = container.preferences

    private lateinit var player: ExoPlayer
    /** What the session exposes: [player] with artist, song, and cover art split out of stream metadata. */
    private lateinit var sessionPlayer: TrackInfoPlayer
    private var session: MediaLibrarySession? = null
    private val artworkClient get() = container.artworkClient
    private var artworkJob: Job? = null

    /**
     * Most recent ICY or ID3 track packet seen for the current station. Keeping the exact packet
     * prevents ExoPlayer's cumulative ID3 metadata merging from leaking an older track's artist
     * or embedded cover onto a newer title-only track.
     */
    private data class TrackPacket(
        val mediaId: String?,
        val title: String?,
        val artist: String?,
        val streamArtUrl: String?,
        val hasEmbeddedArt: Boolean,
    )

    private var latestTrackPacket: TrackPacket? = null
    private var trackPacketUpdated = false
    private lateinit var reconnect: ReconnectController
    private lateinit var sleepTimer: SleepTimer
    private val audioFx = AudioFxController()

    private var currentStationId: String? = null
    private var recordedStationId: String? = null
    private var pausedAtElapsed = 0L
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var playbackPrefs: PlaybackPrefs = PlaybackPrefs()
    private var carPrefs: CarPrefs = CarPrefs()
    private var stationEq: EqPreset? = null
    private var stationSettingsJob: Job? = null

    private fun applyAudioPrefs() {
        audioFx.applyPrefs(playbackPrefs.copy(eqPreset = stationEq ?: playbackPrefs.eqPreset))
    }

    private val headsetDeviceTypes = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_HEARING_AID,
    )
    private var headsetResumeEnabled = false
    private var resumeAfterDisconnect = false
    private var headsetResumeJob: Job? = null
    private val carConnection by lazy { CarConnection(this).type }
    private val carObserver = Observer<Int> { if (it != CarConnection.CONNECTION_TYPE_NOT_CONNECTED) cancelHeadsetResume() }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (!resumeAfterDisconnect) return
            val device = addedDevices.firstOrNull { it.isSink && it.type in headsetDeviceTypes } ?: return
            headsetResumeJob?.cancel()
            headsetResumeJob = scope.launch {
                // Give projection detection time to settle after a Bluetooth or USB handoff.
                delay(3_000)
                val devices = getSystemService<AudioManager>()?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                if (!resumeAfterDisconnect || !headsetResumeEnabled ||
                    carConnection.value != CarConnection.CONNECTION_TYPE_NOT_CONNECTED ||
                    session?.connectedControllers.orEmpty().any { it.packageName == "com.google.android.projection.gearhead" } ||
                    SystemClock.elapsedRealtime() - pausedAtElapsed > 10 * 60_000L ||
                    devices?.none { it.id == device.id } != false ||
                    player.currentMediaItem == null || player.playWhenReady || shouldBlockForWifiOnly()
                ) return@launch
                resumeAfterDisconnect = false
                player.prepare()
                player.play()
            }
        }
    }

    private fun cancelHeadsetResume() {
        resumeAfterDisconnect = false
        headsetResumeJob?.cancel()
    }

    private var mobileDataOverrideOnce = false
    private var wifiOnlyBlocked = false

    private val alternateFeedsCache = HashMap<String, List<StreamFeed>>()
    private val triedUrlsForCurrent = HashSet<String>()

    private var favoriteIds: Set<String> = emptySet()

    /** User's country for the car's Countries folder; looked up once per service lifetime. */
    private var detectedCountry: String? = null
    private var countryLookedUp = false
    /** Player volume = per-station trim (cuts only) × sleep fade-out. */
    private var trimDb = 0
    private var fadeVolume = 1f
    private var wakeVolume = 1f
    private var wakeStationId: String? = null
    private var wakeRamp: Job? = null

    private fun clearWakeRamp() {
        wakeStationId = null
        wakeRamp?.cancel()
        wakeRamp = null
        wakeVolume = 1f
        applyVolume()
    }

    /** Network bytes not yet written to the monthly totals, and bytes for the current station. */
    private val pendingBytes = AtomicLong()
    private val sessionBytes = AtomicLong()
    private val byteCounter = object : TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            if (!isNetwork || bytesTransferred <= 0) return
            pendingBytes.addAndGet(bytesTransferred.toLong())
            sessionBytes.addAndGet(bytesTransferred.toLong())
        }
    }

    private val sleepTimerIntent by lazy {
        PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_SLEEP_TIMER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    override fun onCreate() {
        super.onCreate()

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(RadioBrowserClient.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
        val dataSourceFactory = DefaultDataSource.Factory(this, http).setTransferListener(byteCounter)
        // Allocate max buffer pool large enough for Cellular Saver (75 s) while starting at Standard
        val loadControl = DefaultLoadControl.Builder()
            .setBackBuffer(30_000, /* retainBackBufferFromKeyframe = */ false)
            .setBufferDurationsMs(
                BufferProfile.Cellular.minBufferMs,
                BufferProfile.Cellular.maxBufferMs,
                BufferProfile.Standard.playbackStartMs,
                BufferProfile.Standard.rebufferMs,
            )
            .build()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        reconnect = ReconnectController(
            scope = scope,
            fetchFreshUrl = { mediaId -> resolveNextRetryUrl(mediaId) },
            performRetry = ::retryPlayback,
            onStateChanged = ::onRecoveryStateChanged,
        )
        sleepTimer = SleepTimer(
            scope = scope,
            clock = System::currentTimeMillis,
            onExpired = {
                cancelHeadsetResume()
                fadeVolume = 1f
                applyVolume()
                player.pause()
            },
            onChanged = {
                publishExtras()
                updateMediaButtons()
            },
            onFadeVolume = { vol ->
                fadeVolume = vol.coerceIn(0.05f, 1f)
                applyVolume()
            },
        )

        player.addListener(PlayerEvents())
        sessionPlayer = TrackInfoPlayer(player)

        val activityIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val bitmapLoader = androidx.media3.session.CacheBitmapLoader(
            androidx.media3.datasource.DataSourceBitmapLoader.Builder(this)
                .setDataSourceFactory(dataSourceFactory)
                .build(),
        )
        session = MediaLibrarySession.Builder(this, sessionPlayer, LibraryCallback())
            .setId("radio")
            .setSessionActivity(activityIntent)
            .setBitmapLoader(bitmapLoader)
            .build()
        setMediaNotificationProvider(
            object : DefaultMediaNotificationProvider(this) {
                override fun addNotificationActions(
                    mediaSession: MediaSession,
                    mediaButtons: ImmutableList<CommandButton>,
                    builder: NotificationCompat.Builder,
                    actionFactory: MediaNotification.ActionFactory,
                ): IntArray {
                    val actions = object : MediaNotification.ActionFactory by actionFactory {
                        override fun createNotificationDismissalIntent(mediaSession: MediaSession): PendingIntent =
                            actionFactory.createNotificationDismissalIntent(mediaSession)

                        override fun createCustomActionFromCustomCommandButton(
                            mediaSession: MediaSession,
                            customCommandButton: CommandButton,
                        ): NotificationCompat.Action {
                            if (customCommandButton.sessionCommand?.customAction != SessionKeys.CMD_OPEN_SLEEP_TIMER) {
                                return actionFactory.createCustomActionFromCustomCommandButton(mediaSession, customCommandButton)
                            }
                            return NotificationCompat.Action(
                                IconCompat.createWithResource(this@RadioService, sleepTimerIcon()),
                                sleepTimerLabel(),
                                sleepTimerIntent,
                            )
                        }
                    }
                    return super.addNotificationActions(mediaSession, mediaButtons, builder, actions)
                }

                override fun getNotificationContentTitle(metadata: MediaMetadata): CharSequence? =
                    metadata.title?.takeIf { it.isNotBlank() } ?: metadata.station ?: metadata.displayTitle

                override fun getNotificationContentText(metadata: MediaMetadata): CharSequence? {
                    val title = metadata.title?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    val station = metadata.station?.toString()?.trim()
                    return if (title != null && !title.equals(station, ignoreCase = true)) {
                        listOfNotNull(metadata.artist?.takeIf { it.isNotBlank() }, metadata.station ?: metadata.subtitle).joinToString(" · ")
                    } else {
                        metadata.subtitle ?: metadata.artist?.takeIf { it.isNotBlank() }
                    }
                }
            }.also {
                it.setSmallIcon(R.drawable.ic_stat_radio)
            },
        )

        // Observe audio processing & network playback preferences.
        scope.launch {
            prefsRepo.playbackPrefs.collect { prefs ->
                val artworkToggled = prefs.trackArtwork != playbackPrefs.trackArtwork
                playbackPrefs = prefs
                if (artworkToggled) {
                    sessionPlayer.updateEnrichment(null)
                    enrichTrack()
                }
                applyAudioPrefs()
                if (enforceWifiOnly()) return@collect
                if (!prefs.wifiOnly && wifiOnlyBlocked) {
                    wifiOnlyBlocked = false
                    publishExtras()
                }
            }
        }

        scope.launch {
            prefsRepo.carPrefs.collect { prefs ->
                val layoutChanged = prefs.tiles != carPrefs.tiles
                carPrefs = prefs
                if (layoutChanged) notifyCarPlaylistsChanged(root = true)
            }
        }

        // Keep the car's Presets / Playlists / Recents lists and the home-screen widget in sync.
        scope.launch {
            repo.favorites.drop(1).collect {
                session?.notifyChildrenChanged(MediaIds.PRESETS, repo.carPresetsNow().size, null)
                notifyCarPlaylistsChanged(root = false)
                updateWidget()
            }
        }
        scope.launch {
            repo.recents.drop(1).collect {
                session?.notifyChildrenChanged(MediaIds.RECENTS, repo.recentsNow().size, null)
                updateWidget()
            }
        }
        // Heart state for the notification / lock-screen / car favorite button.
        scope.launch {
            repo.favoriteIds.collect {
                favoriteIds = it
                updateMediaButtons()
            }
        }
        registerNetworkCallback()
        carConnection.observeForever(carObserver)
        getSystemService<AudioManager>()?.registerAudioDeviceCallback(audioDeviceCallback, Handler(Looper.getMainLooper()))
        scope.launch {
            prefsRepo.resumeHeadset.collect { enabled ->
                headsetResumeEnabled = enabled
                if (!enabled) cancelHeadsetResume()
            }
        }
        updateMediaButtons()

        // Persist measured data usage and refresh the session counter every 30 s.
        scope.launch {
            while (true) {
                delay(USAGE_FLUSH_MS)
                flushUsage()
                if (player.isPlaying) publishExtras()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    /** Swiping the app away keeps audio running; only an idle service shuts down. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        // The service scope is about to be cancelled; write the remaining bytes on their own.
        flushUsage(CoroutineScope(Dispatchers.IO))
        networkCallback?.let { getSystemService<ConnectivityManager>()?.unregisterNetworkCallback(it) }
        getSystemService<AudioManager>()?.unregisterAudioDeviceCallback(audioDeviceCallback)
        carConnection.removeObserver(carObserver)
        audioFx.release()
        scope.cancel()
        session?.release()
        session = null
        player.release()
        super.onDestroy()
    }

    // ---- network & failover helpers ---------------------------------------------------------

    private fun isDeviceOnline(): Boolean {
        val cm = getSystemService<ConnectivityManager>() ?: return true
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun isOnUnmeteredWifi(): Boolean {
        val cm = getSystemService<ConnectivityManager>() ?: return true
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Checks if Wi-Fi-only mode should pause playback on a metered cellular network. */
    private fun shouldBlockForWifiOnly(): Boolean =
        playbackPrefs.wifiOnly && !mobileDataOverrideOnce && isDeviceOnline() && !isOnUnmeteredWifi()

    private fun enforceWifiOnly(): Boolean {
        if (!player.playWhenReady || !shouldBlockForWifiOnly()) return false
        wifiOnlyBlocked = true
        player.pause()
        publishExtras()
        updateWidget()
        return true
    }

    /**
     * Resolves a fresh URL or next verified alternate feed when a retry cycle requests a URL refresh.
     */
    private suspend fun resolveNextRetryUrl(mediaId: String): String? {
        val stationId = MediaIds.stationId(mediaId) ?: return null
        val currentUrl = player.currentMediaItem?.localConfiguration?.uri?.toString()
        if (currentUrl != null) triedUrlsForCurrent += currentUrl

        val station = player.currentMediaItem?.toStation() ?: repo.localStation(stationId)
        if (station != null) {
            val ranked = alternateFeedsCache.getOrPut(station.id) {
                runCatching { repo.alternateFeedsFor(station) }.getOrDefault(emptyList())
            }
            // Feeds that worked before go first; ones that just failed go last.
            val health = runCatching { repo.streamHealth(station.id) }.getOrDefault(emptyList())
            val feeds = StreamUrlPreference.orderFeeds(ranked, health, System.currentTimeMillis())
            val failover = AlternateFeedSelector.nextFailoverFeed(currentUrl, feeds, triedUrlsForCurrent)
            if (failover != null) {
                triedUrlsForCurrent += failover.streamUrl
                logEvent("Failover", "Switching to alternate feed: ${failover.label} (${failover.qualityBadge})", station, failover.streamUrl)
                return failover.streamUrl
            }
        }
        val fresh = repo.fetchFreshUrl(stationId)
        logEvent(
            "Retry",
            if (fresh != null && fresh != currentUrl) "Using refreshed directory URL" else "Retrying the same URL",
            station,
            fresh ?: currentUrl,
        )
        return fresh
    }

    private fun onRecoveryStateChanged(recovery: app.openradio.domain.RecoveryState) {
        if (recovery.phase == RecoveryPhase.Failed) {
            val station = player.currentMediaItem?.toStation()
            val kind = reconnect.lastKind ?: FailureKind.Other
            val online = isDeviceOnline()
            if (station != null && PlaybackErrorClassifier.shouldFlagStationFailed(kind, online)) {
                scope.launch { repo.markFailedAttempt(station, failed = true) }
            }
            logEvent(
                "Recovery failed",
                "kind=${kind.name} online=$online attempts=${recovery.attempt}/${recovery.maxAttempts}" +
                    (recovery.message?.let { " · $it" } ?: ""),
                station,
            )
        }
        publishExtras()
        updateWidget()
    }

    // ---- playback events --------------------------------------------------------------------

    private inner class PlayerEvents : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            audioFx.bindSession(audioSessionId)
            publishExtras()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val id = mediaItem?.mediaId
            // retryPlayback() replaces the item in place; that is not a station change.
            if (id == currentStationId) return
            currentStationId = id
            latestTrackPacket = null
            trackPacketUpdated = false
            artworkJob?.cancel()
            sessionPlayer.updateEnrichment(null)
            if (id != wakeStationId) clearWakeRamp()
            cancelHeadsetResume()
            mobileDataOverrideOnce = false
            recordedStationId = null
            pausedAtElapsed = 0L
            triedUrlsForCurrent.clear()
            mediaItem?.localConfiguration?.uri?.toString()?.let { triedUrlsForCurrent += it }
            reconnect.onStationChanged(id)
            sleepTimer.onStationChanged()
            sessionBytes.set(0)
            val stationId = id?.let(MediaIds::stationId)
            stationSettingsJob?.cancel()
            stationEq = null
            applyAudioPrefs()
            setTrim(0, persist = null)
            stationSettingsJob = stationId?.let {
                scope.launch {
                    setTrim(repo.volumeTrim(it), persist = null)
                    prefsRepo.stationEq(it).collect { preset ->
                        stationEq = preset
                        applyAudioPrefs()
                    }
                }
            }
            updateMediaButtons()
            updateWidget()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) {
                flushUsage()
                updateWidget()
                return
            }
            if (enforceWifiOnly()) return
            wifiOnlyBlocked = false
            if (wakeStationId != null && wakeStationId == player.currentMediaItem?.mediaId && wakeRamp == null) {
                wakeRamp = scope.launch {
                    for (step in 1..40) {
                        delay(750)
                        wakeVolume = 0.05f + 0.95f * step / 40
                        applyVolume()
                    }
                    clearWakeRamp()
                }
            }
            reconnect.onPlaybackStarted()
            if (player.audioSessionId > 0) {
                audioFx.bindSession(player.audioSessionId)
            }
            val item = player.currentMediaItem ?: return
            val station = item.toStation() ?: return
            updateWidget()
            if (recordedStationId == item.mediaId) return
            recordedStationId = item.mediaId
            ShortcutPublisher.reportPlayed(this@RadioService, station)
            // Recents are written and any failedLastAttempt flag is cleared once audio actually starts.
            scope.launch {
                repo.recordPlayed(station)
                repo.recordUrlSuccess(station.id, station.streamUrl)
                repo.registerClick(station.id)
                // Warm alternate feeds in the background for seamless failover if needed later.
                if (station.id !in alternateFeedsCache) {
                    alternateFeedsCache[station.id] = runCatching { repo.alternateFeedsFor(station) }.getOrDefault(emptyList())
                }
            }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_MEDIA_METADATA_CHANGED) || trackPacketUpdated) {
                trackPacketUpdated = false
                onTrackMetadataUpdated()
            }
        }

        override fun onMetadata(metadata: Metadata) {
            val mediaId = player.currentMediaItem?.mediaId ?: return
            var icy: IcyInfo? = null
            var id3Title: String? = null
            var id3Artist: String? = null
            var hasId3TrackFrame = false
            var hasApic = false
            for (i in 0 until metadata.length()) {
                when (val entry = metadata.get(i)) {
                    is IcyInfo -> icy = entry
                    is TextInformationFrame -> when (entry.id) {
                        "TIT2", "TT2" -> {
                            hasId3TrackFrame = true
                            id3Title = entry.values.firstOrNull()
                        }
                        "TPE1", "TP1" -> {
                            hasId3TrackFrame = true
                            id3Artist = entry.values.firstOrNull()
                        }
                    }
                    is ApicFrame -> hasApic = true
                }
            }
            if (icy != null) {
                val url = icy.url?.trim()?.takeIf(::looksLikeImageUrl)
                latestTrackPacket = TrackPacket(
                    mediaId = mediaId,
                    title = icy.title,
                    artist = null,
                    streamArtUrl = url,
                    hasEmbeddedArt = false,
                )
                trackPacketUpdated = true
                return
            }
            if (hasId3TrackFrame || hasApic) {
                val prev = latestTrackPacket?.takeIf { it.mediaId == mediaId }
                val effectiveTitle = if (id3Title != null) id3Title else prev?.title
                // A new TIT2 starts a new track, so an absent TPE1/APIC clears any previous artist/cover.
                val effectiveArtist = if (id3Title != null) id3Artist else (id3Artist ?: prev?.artist)
                val effectiveApic = if (id3Title != null) hasApic else (hasApic || prev?.hasEmbeddedArt == true)
                latestTrackPacket = TrackPacket(
                    mediaId = mediaId,
                    title = effectiveTitle,
                    artist = effectiveArtist,
                    streamArtUrl = null,
                    hasEmbeddedArt = effectiveApic,
                )
                trackPacketUpdated = true
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            cancelHeadsetResume()
            if (!playWhenReady) {
                if (wakeStationId != null) clearWakeRamp()
                resumeAfterDisconnect = headsetResumeEnabled &&
                    reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY &&
                    carConnection.value == CarConnection.CONNECTION_TYPE_NOT_CONNECTED
                pausedAtElapsed = SystemClock.elapsedRealtime()
                reconnect.onUserPaused()
                updateWidget()
                return
            }
            if (enforceWifiOnly()) return
            wifiOnlyBlocked = false
            reconnect.onUserResumed()
            publishExtras()
            when {
                player.playerError != null -> player.prepare()
                SystemClock.elapsedRealtime() - pausedAtElapsed > STALE_PAUSE_MS && pausedAtElapsed != 0L ->
                    jumpToLive()
            }
            updateWidget()
        }

        override fun onPlayerError(error: PlaybackException) {
            val item = player.currentMediaItem ?: return
            if (!player.playWhenReady) return // user paused: nothing to recover
            val online = isDeviceOnline()
            val kind = if (!online) FailureKind.Network else classifyPlaybackError(error.errorCode)
            logEvent(
                "Playback error",
                "${error.errorCodeName} kind=${kind.name} online=$online" + (error.message?.let { " · $it" } ?: ""),
                item.toStation(),
            )
            val failedUrl = item.localConfiguration?.uri?.toString()
            val failedStation = MediaIds.stationId(item.mediaId)
            // Offline errors say nothing about the URL itself.
            if (online && failedUrl != null && failedStation != null) {
                scope.launch { repo.recordUrlFailure(failedStation, failedUrl) }
            }
            reconnect.onPlaybackError(
                stationId = item.mediaId,
                kind = kind,
                currentUrl = item.localConfiguration?.uri?.toString(),
            )
            publishExtras()
            updateWidget()
        }
    }

    private fun currentTrack(): app.openradio.domain.TrackInfo? {
        val item = player.currentMediaItem ?: return null
        val station = item.toStation() ?: return null
        val packet = latestTrackPacket?.takeIf { it.mediaId == item.mediaId }
        val titleField = if (packet != null) packet.title else player.mediaMetadata.title?.toString()
        val artistField = if (packet != null) packet.artist else player.mediaMetadata.artist?.toString()
        return TrackInfoParser.parse(titleField, artistField, station.name)
    }

    private fun onTrackMetadataUpdated() {
        val item = player.currentMediaItem ?: return
        val station = item.toStation() ?: return
        val track = currentTrack()
        if (track != null) {
            scope.launch { repo.recordOnAir(station, track.display) }
            sleepTimer.onTrackTitle(track.display)
        }
        enrichTrack()
        updateWidget()
    }

    /**
     * Splits the current stream title into artist and song for the session, and adds cover art:
     * a `StreamUrl` image sent by the station, else (when enabled) an iTunes Search API match.
     */
    private fun enrichTrack() {
        val item = player.currentMediaItem
        val mediaId = item?.mediaId
        val station = item?.toStation()
        val meta = player.mediaMetadata
        val rawTitle = meta.title?.toString()?.trim().orEmpty()
        val rawArtist = meta.artist?.toString()?.trim().orEmpty()
        if (station == null) {
            artworkJob?.cancel()
            sessionPlayer.updateEnrichment(null)
            return
        }
        val packet = latestTrackPacket?.takeIf { it.mediaId == mediaId }
        val track = currentTrack()
        val clearEmbedded = packet != null && !packet.hasEmbeddedArt
        if (track == null) {
            artworkJob?.cancel()
            // Explicitly clear blank / station-name titles and any stale artist or APIC cover.
            val hasStaleMetadata = meta.title != null || meta.artist != null || meta.artworkData != null
            sessionPlayer.updateEnrichment(
                if (hasStaleMetadata) {
                    TrackInfoPlayer.Enrichment(
                        mediaId = mediaId,
                        rawTitle = rawTitle,
                        rawArtist = rawArtist,
                        track = null,
                        artworkUri = null,
                        clearEmbeddedArtwork = true,
                    )
                } else {
                    null
                },
            )
            return
        }
        val streamArt = packet?.streamArtUrl?.toUri()
        val current = sessionPlayer.enrichment
        if (current != null &&
            current.mediaId == mediaId &&
            current.rawTitle == rawTitle &&
            current.rawArtist == rawArtist &&
            current.track == track &&
            current.clearEmbeddedArtwork == clearEmbedded &&
            (streamArt == null || current.artworkUri == streamArt)
        ) return
        artworkJob?.cancel()
        sessionPlayer.updateEnrichment(
            TrackInfoPlayer.Enrichment(
                mediaId = mediaId,
                rawTitle = rawTitle,
                rawArtist = rawArtist,
                track = track,
                artworkUri = streamArt,
                clearEmbeddedArtwork = clearEmbedded,
            ),
        )
        val hasCurrentEmbeddedArt = meta.artworkData != null && !clearEmbedded
        if (streamArt != null || !playbackPrefs.trackArtwork || !track.isSong || hasCurrentEmbeddedArt) return
        artworkJob = scope.launch {
            // Confirm persisted preference on cold start before making any network request.
            if (!prefsRepo.playbackPrefs.first().trackArtwork) return@launch
            val url = artworkClient.artworkFor(track) ?: return@launch
            val nowMeta = player.mediaMetadata
            if (!playbackPrefs.trackArtwork ||
                player.currentMediaItem?.mediaId != mediaId ||
                nowMeta.title?.toString()?.trim().orEmpty() != rawTitle ||
                nowMeta.artist?.toString()?.trim().orEmpty() != rawArtist ||
                currentTrack() != track
            ) return@launch
            sessionPlayer.updateEnrichment(
                TrackInfoPlayer.Enrichment(
                    mediaId = mediaId,
                    rawTitle = rawTitle,
                    rawArtist = rawArtist,
                    track = track,
                    artworkUri = url.toUri(),
                    clearEmbeddedArtwork = clearEmbedded,
                ),
            )
        }
    }

    private suspend fun notifyCarPlaylistsChanged(root: Boolean) {
        val s = session ?: return
        val favorites = repo.favoritesNow()
        if (root) s.notifyChildrenChanged(MediaIds.ROOT, 4, null)
        val names = CarPlaylists.collectionNames(favorites)
        s.notifyChildrenChanged(MediaIds.PLAYLISTS, if (favorites.isEmpty()) 0 else names.size + 1, null)
        s.notifyChildrenChanged(MediaIds.ALL_FAVORITES, favorites.size, null)
        names.forEach { s.notifyChildrenChanged(MediaIds.playlist(it), CarPlaylists.collection(favorites, it).size, null) }
    }

    private fun looksLikeImageUrl(url: String): Boolean =
        url.startsWith("http", ignoreCase = true) &&
            Regex("\\.(jpe?g|png|webp)(\\?.*)?$", RegexOption.IGNORE_CASE).containsMatchIn(url)

    /** After a long pause the buffered audio is stale; rejoin the live edge. */
    private fun jumpToLive() {
        if (player.isCurrentMediaItemSeekable) {
            player.seekToDefaultPosition()
        } else {
            player.stop()
            player.prepare()
        }
    }

    private fun retryPlayback(stationId: String, newUrl: String?) {
        val item = player.currentMediaItem ?: return
        if (item.mediaId != stationId || MediaIds.stationId(item.mediaId) == null) return
        if (!player.playWhenReady) return
        if (newUrl != null) {
            val builder = item.buildUpon().setUri(newUrl)
            if (newUrl.contains(".m3u8", ignoreCase = true)) {
                builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            }
            player.replaceMediaItem(player.currentMediaItemIndex, builder.build())
            MediaIds.stationId(item.mediaId)?.let { scope.launch { repo.rememberStreamUrl(it, newUrl) } }
        }
        player.seekToDefaultPosition()
        player.prepare()
    }

    private fun publishExtras() {
        val s = session ?: return
        val recovery = reconnect.state
        val online = isDeviceOnline()
        val extras = Bundle().apply {
            putString(SessionKeys.EXTRA_RECOVERY, recovery.phase.name)
            putInt(SessionKeys.EXTRA_ATTEMPT, recovery.attempt)
            putInt(SessionKeys.EXTRA_MAX_ATTEMPTS, recovery.maxAttempts)
            putString(SessionKeys.EXTRA_MESSAGE, recovery.message)
            putLong(SessionKeys.EXTRA_SLEEP_ENDS_AT, sleepTimer.endsAtMs ?: 0L)
            reconnect.lastKind?.let { putString(SessionKeys.EXTRA_FAILURE_KIND, it.name) }
            putBoolean(SessionKeys.EXTRA_DEVICE_ONLINE, online)
            putBoolean(SessionKeys.EXTRA_WIFI_BLOCKED, wifiOnlyBlocked)
            putInt(SessionKeys.EXTRA_AUDIO_SESSION_ID, player.audioSessionId)
            putBoolean(SessionKeys.EXTRA_SLEEP_END_OF_TRACK, sleepTimer.endOfTrack)
            putInt(SessionKeys.EXTRA_VOLUME_TRIM_DB, trimDb)
            putLong(SessionKeys.EXTRA_SESSION_BYTES, sessionBytes.get())
        }
        s.setSessionExtras(extras)
    }

    // ---- volume trim, media buttons & error log ---------------------------------------------

    /** Adds bytes streamed since the last flush to this month's mobile or Wi-Fi total. */
    private fun flushUsage(target: CoroutineScope = scope) {
        val bytes = pendingBytes.getAndSet(0)
        if (bytes <= 0) return
        val wifi = isOnUnmeteredWifi()
        val usage = container.usage
        target.launch {
            runCatching { usage.add(mobileBytes = if (wifi) 0 else bytes, wifiBytes = if (wifi) bytes else 0) }
        }
    }

    private fun applyVolume() {
        player.volume = (VolumeTrim.attenuationFactor(trimDb) * fadeVolume * wakeVolume).coerceIn(0f, 1f)
    }

    /** Applies [db] to the live player; when [persist] is set, also stores it for that station. */
    private fun setTrim(db: Int, persist: Station?) {
        trimDb = VolumeTrim.clamp(db)
        audioFx.setBoostMillibels(VolumeTrim.boostMillibels(trimDb))
        applyVolume()
        publishExtras()
        if (persist != null) {
            val savedTrim = trimDb
            scope.launch { repo.setVolumeTrim(persist, savedTrim) }
        }
    }

    /**
     * Favorite and sleep buttons for the media notification, the lock screen and Android Auto.
     * They take the back/forward slots when there is no previous/next station, otherwise they
     * fall back to the overflow area.
     */
    private fun updateMediaButtons() {
        val s = session ?: return
        val buttons = mutableListOf<CommandButton>()
        val station = player.currentMediaItem?.toStation()
        if (station != null) {
            val fav = station.id in favoriteIds
            buttons += CommandButton.Builder(if (fav) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
                .setDisplayName(if (fav) "Remove from favorites" else "Add to favorites")
                .setSessionCommand(SessionCommand(SessionKeys.CMD_TOGGLE_FAVORITE, Bundle.EMPTY))
                .setSlots(CommandButton.SLOT_BACK, CommandButton.SLOT_OVERFLOW)
                .build()
        }
        buttons += CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setCustomIconResId(sleepTimerIcon())
            .setDisplayName(sleepTimerLabel())
            .setSessionCommand(SessionCommand(SessionKeys.CMD_OPEN_SLEEP_TIMER, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_FORWARD, CommandButton.SLOT_OVERFLOW)
            .build()
        s.setMediaButtonPreferences(buttons)
    }

    private fun sleepTimerIcon() =
        if (sleepTimer.endsAtMs != null) R.drawable.ic_sleep_on else R.drawable.ic_sleep_off

    private fun sleepTimerLabel() = when {
        sleepTimer.endOfTrack -> "Sleep after this track. Open sleep timer setup"
        sleepTimer.endsAtMs != null -> "Sleep timer on. Open setup"
        else -> "Sleep timer off. Open setup"
    }

    private fun logEvent(
        event: String,
        detail: String?,
        station: Station? = player.currentMediaItem?.toStation(),
        url: String? = player.currentMediaItem?.localConfiguration?.uri?.toString(),
    ) {
        val entry = ErrorLogEntry(System.currentTimeMillis(), event, station?.name, station?.id, detail, url)
        scope.launch { runCatching { container.errorLog.record(entry) } }
    }

    private fun updateWidget() {
        val item = player.currentMediaItem
        val station = item?.toStation()
        val status = PlaybackStatusRules.derive(
            hasItem = item != null,
            playWhenReady = player.playWhenReady,
            isReady = player.playbackState == Player.STATE_READY,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            hasError = player.playerError != null || wifiOnlyBlocked,
            recovery = reconnect.state.phase,
        )
        val rawTitle = player.mediaMetadata.title?.toString()
        val track = rawTitle?.takeIf { it.isNotBlank() && it != station?.name }
        RadioWidgetProvider.updateAllWidgets(this, station, status, track)
        LastStationTileService.requestUpdate(this)
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService<ConnectivityManager>() ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch {
                    publishExtras()
                    currentStationId?.let { reconnect.onNetworkAvailable(it) }
                }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                scope.launch { enforceWifiOnly() }
            }

            override fun onLost(network: Network) {
                scope.launch { publishExtras() }
            }
        }
        networkCallback = callback
        cm.registerDefaultNetworkCallback(callback)
    }

    // ---- media library / Android Auto -------------------------------------------------------

    private fun canOpenSleepTimer(session: MediaSession, controller: MediaSession.ControllerInfo) =
        session.isMediaNotificationController(controller) ||
            (controller.isTrusted && controller.packageName == "com.android.systemui")

    private fun openSleepTimer(): Int {
        // MediaSession callbacks may run while Android disallows background activity launches.
        // A notification tap can launch the activity directly in that case.
        val notifications = getSystemService<NotificationManager>()
        if (notifications?.areNotificationsEnabled() == true) {
            notifications.createNotificationChannel(
                NotificationChannel(SLEEP_SETUP_CHANNEL, "Sleep timer setup", NotificationManager.IMPORTANCE_LOW),
            )
            notifications.notify(
                SLEEP_SETUP_NOTIFICATION_ID,
                NotificationCompat.Builder(this, SLEEP_SETUP_CHANNEL)
                    .setSmallIcon(sleepTimerIcon())
                    .setContentTitle("Open sleep timer setup")
                    .setContentIntent(sleepTimerIntent)
                    .setAutoCancel(true)
                    .setTimeoutAfter(30_000L)
                    .build(),
            )
        }
        return try {
            val options = if (Build.VERSION.SDK_INT >= 34) ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle() else null
            sleepTimerIntent.send(this, 0, null, null, null, null, options)
            SessionResult.RESULT_SUCCESS
        } catch (_: PendingIntent.CanceledException) {
            SessionError.ERROR_INVALID_STATE
        }
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(SessionKeys.CMD_SLEEP, Bundle.EMPTY))
                .add(SessionCommand(SessionKeys.CMD_SLEEP_ADD, Bundle.EMPTY))
                .add(SessionCommand(SessionKeys.CMD_MOBILE_OVERRIDE, Bundle.EMPTY))
                .add(SessionCommand(SessionKeys.CMD_SELECT_FEED, Bundle.EMPTY))
                .add(SessionCommand(SessionKeys.CMD_TOGGLE_FAVORITE, Bundle.EMPTY))
                .add(SessionCommand(SessionKeys.CMD_SLEEP_END_OF_TRACK, Bundle.EMPTY))
                .add(SessionCommand(SessionKeys.CMD_SET_VOLUME_TRIM, Bundle.EMPTY))
                .build()
            val available = commands.buildUpon().apply {
                if (controller.uid == applicationInfo.uid) {
                    add(SessionCommand(SessionKeys.CMD_PLAY_ROUTINE, Bundle.EMPTY))
                }
                if (canOpenSleepTimer(session, controller)) {
                    add(SessionCommand(SessionKeys.CMD_OPEN_SLEEP_TIMER, Bundle.EMPTY))
                }
            }.build()
            // Connecting (including from the car) never starts audio.
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(available)
                .build()
        }

        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            publishExtras()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                SessionKeys.CMD_PLAY_ROUTINE -> {
                    if (controller.uid != applicationInfo.uid) {
                        return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                    }
                    return scope.future {
                        val id = args.getString(ListeningRoutines.EXTRA_ROUTINE).orEmpty()
                        val routine = ListeningRoutines(this@RadioService).list().firstOrNull { it.id == id }
                            ?: return@future SessionResult(SessionError.ERROR_BAD_VALUE)
                        val station = preferKnownGoodUrl(repo.localStation(routine.station.id) ?: routine.station)
                        if (player.playWhenReady || carConnection.value != CarConnection.CONNECTION_TYPE_NOT_CONNECTED ||
                            this@RadioService.session?.connectedControllers.orEmpty().any { it.packageName == "com.google.android.projection.gearhead" }
                        ) return@future SessionResult(SessionError.ERROR_INVALID_STATE)
                        clearWakeRamp()
                        if (routine.wakeUp) {
                            wakeStationId = MediaIds.station(station.id)
                            wakeVolume = 0.05f
                            applyVolume()
                        }
                        player.setMediaItem(station.toMediaItem(playable = true))
                        player.prepare()
                        player.play()
                        SessionResult(SessionResult.RESULT_SUCCESS)
                    }
                }
                SessionKeys.CMD_SLEEP -> {
                    val minutes = args.getInt(SessionKeys.ARG_MINUTES, 0)
                    if (minutes > 0) sleepTimer.start(minutes) else sleepTimer.cancel()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                SessionKeys.CMD_SLEEP_ADD -> {
                    val extra = args.getInt(SessionKeys.ARG_MINUTES, 15).coerceAtLeast(1)
                    sleepTimer.addMinutes(extra)
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                SessionKeys.CMD_TOGGLE_FAVORITE -> {
                    val station = player.currentMediaItem?.toStation()
                        ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    // Flip the heart immediately; the favorites flow confirms it shortly after.
                    favoriteIds = if (station.id in favoriteIds) favoriteIds - station.id else favoriteIds + station.id
                    updateMediaButtons()
                    scope.launch { repo.toggleFavorite(station) }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                SessionKeys.CMD_OPEN_SLEEP_TIMER -> {
                    val result = if (canOpenSleepTimer(session, controller)) openSleepTimer()
                    else SessionError.ERROR_PERMISSION_DENIED
                    return Futures.immediateFuture(SessionResult(result))
                }
                SessionKeys.CMD_SLEEP_END_OF_TRACK -> {
                    val station = player.currentMediaItem?.toStation()
                    val title = player.mediaMetadata.title?.toString()?.trim()
                        ?.takeIf { it.isNotEmpty() && !it.equals(station?.name, ignoreCase = true) }
                        ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    sleepTimer.stopAfterTrack(title)
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                SessionKeys.CMD_SET_VOLUME_TRIM -> {
                    val station = player.currentMediaItem?.toStation()
                        ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    setTrim(args.getInt(SessionKeys.ARG_TRIM_DB, 0), persist = station)
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                SessionKeys.CMD_MOBILE_OVERRIDE -> {
                    mobileDataOverrideOnce = true
                    wifiOnlyBlocked = false
                    publishExtras()
                    if (player.currentMediaItem != null) {
                        player.prepare()
                        player.play()
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                SessionKeys.CMD_SELECT_FEED -> {
                    val url = args.getString(SessionKeys.ARG_FEED_URL)
                    val item = player.currentMediaItem
                    val station = item?.toStation()
                    if (!url.isNullOrBlank() && item != null && station != null) {
                        val updatedStation = station.copy(
                            streamUrl = url,
                            codec = args.getString(SessionKeys.ARG_FEED_CODEC) ?: station.codec,
                            bitrate = args.getInt(SessionKeys.ARG_FEED_BITRATE, station.bitrate),
                            isHls = args.getBoolean(SessionKeys.ARG_FEED_HLS, station.isHls),
                        )
                        player.replaceMediaItem(
                            player.currentMediaItemIndex,
                            updatedStation.toMediaItem(playable = true),
                        )
                        scope.launch { repo.rememberStreamUrl(station.id, url) }
                        player.prepare()
                        player.play()
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }
            return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootId = if (params?.isRecent == true) MediaIds.RECENT_ROOT else MediaIds.ROOT
            // Rows by default; Presets and playlists ask for tiles on their own folder items.
            val extras = Bundle().apply {
                putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST_ITEM)
                putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_LIST_ITEM)
            }
            val rootParams = LibraryParams.Builder().setExtras(extras).build()
            return Futures.immediateFuture(LibraryResult.ofItem(folder(rootId, "Nipper FM"), rootParams))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val country = MediaIds.countryCode(parentId)
            val tag = MediaIds.genreTag(parentId)
            val playlist = MediaIds.playlistName(parentId)
            val tiles = carPrefs.tiles
            val items: List<MediaItem> = when {
                // Android Auto shows at most four root folders as tabs.
                parentId == MediaIds.ROOT -> listOf(
                    folder(MediaIds.PRESETS, "Presets", tiles),
                    folder(MediaIds.PLAYLISTS, "Playlists"),
                    folder(MediaIds.RECENTS, "Recents"),
                    folder(MediaIds.BROWSE, "Browse"),
                )
                parentId == MediaIds.PRESETS -> repo.carPresetsNow().map { it.toMediaItem(playable = false) }
                parentId == MediaIds.PLAYLISTS -> {
                    val favorites = repo.favoritesNow()
                    if (favorites.isEmpty()) emptyList()
                    else listOf(folder(MediaIds.ALL_FAVORITES, "All favorites", tiles)) +
                        CarPlaylists.collectionNames(favorites).map { folder(MediaIds.playlist(it), it, tiles) }
                }
                parentId == MediaIds.ALL_FAVORITES || playlist != null ->
                    playlistStations(parentId).orEmpty().map { it.toMediaItem(playable = false, sourceFolder = parentId) }
                parentId == MediaIds.BROWSE -> listOf(
                    folder(MediaIds.COUNTRIES, "Countries"),
                    folder(MediaIds.GENRES, "Genres & moods"),
                )
                parentId == MediaIds.RECENTS -> repo.recentsNow().map { it.toMediaItem(playable = false) }
                parentId == MediaIds.RECENT_ROOT -> repo.recentsNow().take(1).map { it.toMediaItem(playable = false) }
                parentId == MediaIds.COUNTRIES -> carCountryCodes().map {
                    folder(MediaIds.country(it), CountryNames.displayName(it))
                }
                parentId == MediaIds.GENRES -> (GenreCatalog.MOODS + GenreCatalog.GENRES)
                    .distinctBy { it.tag }
                    .map { folder(MediaIds.genre(it.tag), it.label) }
                country != null || tag != null -> {
                    val stations = try {
                        categoryStations(tag, country)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Nothing cached yet and the directory is unreachable.
                        return@future LibraryResult.ofError(SessionError.ERROR_IO)
                    }
                    stations.map { it.toMediaItem(playable = false) }
                }
                else -> return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            }
            LibraryResult.ofItemList(paged(items, page, pageSize), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            val id = MediaIds.stationId(mediaId)
            val station = id?.let { repo.localStation(it) ?: container.carCache.findStation(it) ?: repo.resolveForPlayback(it) }
            if (station == null) LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            else LibraryResult.ofItem(station.toMediaItem(playable = false), null)
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = scope.future {
            val count = voiceCandidates(query).size
            session.notifySearchResultChanged(browser, query, count, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val items = voiceCandidates(query).map { it.toMediaItem(playable = false) }
            LibraryResult.ofItemList(paged(items, page, pageSize), params)
        }

        /** Phone, notification and car all land here; the service resolves the playable URL. */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val requested = mediaItems.getOrNull(startIndex.coerceAtLeast(0))
                ?: throw IllegalArgumentException("No media item")
            // Cancel recovery for the previous station before anything else can retry it.
            MediaIds.stationId(requested.mediaId)?.let { reconnect.onStationChanged(MediaIds.station(it)) }

            val resolved = resolveRequest(requested)
                ?: throw IllegalArgumentException("Station not found: ${requested.mediaId}")
            val station = preferKnownGoodUrl(resolved.station)
            reconnect.onStationChanged(MediaIds.station(station.id))

            // Queue the list the station came from so next/previous work on the lock screen,
            // the notification and in the car: a voice genre/country request, presets, recents,
            // favorites, or a car Countries/Genres folder.
            val presets = repo.carPresetsNow()
            val recents = repo.recentsNow()
            val favorites = repo.favoritesNow().map { it.station }
            val voiceQueue = resolved.queue?.takeIf { q -> q.any { it.id == station.id } }
            // A tap inside a car playlist queues that playlist.
            val browsedPlaylist = MediaIds.sourceFolder(requested.mediaId)
                ?.let { playlistStations(it) }
                ?.takeIf { q -> q.any { it.id == station.id } }
            val list = when {
                voiceQueue != null -> voiceQueue
                browsedPlaylist != null -> browsedPlaylist
                presets.any { it.id == station.id } -> presets
                recents.any { it.id == station.id } -> recents
                favorites.any { it.id == station.id } -> favorites
                else -> container.carCache.listContaining(station.id) ?: listOf(station)
            }
            val index = list.indexOfFirst { it.id == station.id }.coerceAtLeast(0)
            val items = list.mapIndexed { i, s ->
                // The chosen station keeps the URL we just resolved; others use the cached URL.
                (if (i == index) station else s).toMediaItem(playable = true)
            }
            MediaSession.MediaItemsWithStartPosition(items, index, C.TIME_UNSET)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future {
            mediaItems.mapNotNull { resolveRequest(it)?.station?.toMediaItem(playable = true) }.toMutableList()
        }

        /** Lets a host resume the last station; the host decides whether to actually play. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val queue = wheelQueue() ?: throw UnsupportedOperationException("Nothing to resume")
            val items = queue.stations.map { it.toMediaItem(playable = isForPlayback) }
            MediaSession.MediaItemsWithStartPosition(items, queue.startIndex, C.TIME_UNSET)
        }
    }

    /** A resolved request; [queue] is set when the request named a list (genre, country, presets). */
    private data class Resolution(val station: Station, val queue: List<Station>? = null)

    /**
     * Turns a controller's request into a station: by stable id (cached URL first, so it works
     * during a directory outage) or by spoken search query.
     */
    private suspend fun resolveRequest(item: MediaItem): Resolution? {
        MediaIds.stationId(item.mediaId)?.let { id ->
            // A full record with a URI comes from a fresh directory result: prefer and remember it.
            if (item.localConfiguration != null) {
                item.toStation()?.let { fresh ->
                    repo.rememberStreamUrl(fresh.id, fresh.streamUrl)
                    return Resolution(fresh)
                }
            }
            // Otherwise use a cached record, which keeps working during a directory outage.
            val station = repo.localStation(id) ?: container.carCache.findStation(id) ?: repo.resolveForPlayback(id)
            return station?.let { Resolution(it) }
        }
        val query = item.requestMetadata.searchQuery ?: return null
        return resolveVoice(query)
    }

    /**
     * Spoken requests, in order: a bare "play" resumes presets or recents; a favorite named like
     * the whole request; a genre, mood or country ("some jazz", "radio from Mexico"); otherwise a
     * station search, matched fuzzily.
     */
    private suspend fun resolveVoice(query: String): Resolution? {
        if (VoiceMatch.cleanQuery(query).isEmpty()) {
            return wheelQueue()?.let { Resolution(it.current, it.stations) }
        }
        // "Made in Germany Radio" or "Jazz" saved as favorites should not turn into a category.
        val favorites = repo.favoritesNow().map { it.station }
        VoiceMatch.bestScored(query, favorites)
            ?.takeIf { it.score >= VoiceMatch.STRONG_SCORE }
            ?.let { return Resolution(it.station, favorites) }

        val intent = VoiceRequestParser.parse(query)
        if (intent.isCategory) {
            val list = voiceCategory(intent.tag, intent.countryCode)
            list.firstOrNull()?.let { return Resolution(it, list) }
        }
        val stationQuery = intent.stationQuery ?: query
        val results = searchStations(stationQuery, intent.countryCode)
        val pick = VoiceMatch.best(stationQuery, results) ?: results.firstOrNull() ?: return null
        return Resolution(pick)
    }

    /** Stations in a car playlist folder (All favorites or a Collection), or null for other folders. */
    private suspend fun playlistStations(folderId: String): List<Station>? {
        val name = MediaIds.playlistName(folderId)
        return when {
            folderId == MediaIds.ALL_FAVORITES -> repo.favoritesNow().map { it.station }
            name != null -> CarPlaylists.collection(repo.favoritesNow(), name)
            else -> null
        }
    }

    /** The queue a bare Play starts, per the steering-wheel setting in Settings → Android Auto. */
    private suspend fun wheelQueue(): CarQueue? = CarPlaylists.wheelQueue(
        start = carPrefs.wheelStart,
        recents = repo.recentsNow(),
        presets = repo.carPresetsNow(),
        favorites = repo.favoritesNow(),
    )

    /** Search results for the car's search screen: category lists or matching stations. */
    private suspend fun voiceCandidates(query: String): List<Station> {
        val intent = VoiceRequestParser.parse(query)
        return if (intent.isCategory) {
            voiceCategory(intent.tag, intent.countryCode)
        } else {
            searchStations(intent.stationQuery ?: query, intent.countryCode)
        }
    }

    /** Favorites in the category first (the user's taste), then the directory's popular stations. */
    private suspend fun voiceCategory(tag: String?, countryCode: String?): List<Station> {
        val normTag = tag?.let(VoiceMatch::normalize)
        val saved = repo.favoritesNow().map { it.station }.filter { s ->
            (normTag == null || s.tags.any { VoiceMatch.normalize(it) == normTag }) &&
                (countryCode == null || s.countryCode.equals(countryCode, ignoreCase = true))
        }
        val remote = runCatching { categoryStations(tag, countryCode) }.getOrDefault(emptyList())
        return (saved + remote).distinctBy { it.id }
    }

    /** Cached directory list for a genre tag and/or country (see [app.openradio.data.CarBrowseCache]). */
    private suspend fun categoryStations(tag: String?, countryCode: String?): List<Station> =
        container.carCache.stations("cat:${tag.orEmpty()}:${countryCode.orEmpty()}") {
            repo.directoryCategory(tag, countryCode, CAR_LIST_SIZE)
        }

    /** The user's country, countries of their favorites, then the directory's most-played. */
    private suspend fun carCountryCodes(): List<String> {
        if (!countryLookedUp) {
            countryLookedUp = true
            detectedCountry = runCatching { container.countryLocator.detectCountryCode() }.getOrNull()
        }
        val fromFavorites = repo.favoritesNow()
            .mapNotNull { it.station.countryCode?.uppercase()?.takeIf { c -> c.length == 2 } }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(3).map { it.key }
        val popular = runCatching {
            container.carCache.codes("popular_countries") { repo.popularCountryCodes() }
        }.getOrDefault(emptyList())
        return (listOfNotNull(detectedCountry) + fromFavorites + popular)
            .map { it.uppercase() }
            .distinct()
            .take(MAX_CAR_COUNTRIES)
    }

    /** Swaps in a URL that worked before when the station's usual URL failed recently. */
    private suspend fun preferKnownGoodUrl(station: Station): Station {
        val health = runCatching { repo.streamHealth(station.id) }.getOrDefault(emptyList())
        val url = StreamUrlPreference.choose(station.streamUrl, health, System.currentTimeMillis())
        if (url == station.streamUrl) return station
        logEvent("Known-good URL", "Starting with a URL that worked before; the usual one failed recently", station, url)
        return station.copy(streamUrl = url, isHls = url.contains(".m3u8", ignoreCase = true))
    }

    /**
     * Favorites first (best matches first, misspellings tolerated), then the directory. When the
     * directory finds nothing for a misheard name, its longest word is searched and ranked fuzzily.
     */
    private suspend fun searchStations(rawQuery: String, countryCode: String? = null): List<Station> {
        val q = VoiceMatch.cleanQuery(rawQuery).ifEmpty { rawQuery }
        val favorites = repo.favoritesNow().map { it.station }
        val saved = favorites
            .map { it to VoiceMatch.score(q, it.name) }
            .filter { it.second >= VoiceMatch.MIN_SCORE }
            .sortedByDescending { it.second }
            .map { it.first }
        val strongSaved = saved.firstOrNull()?.takeIf { VoiceMatch.score(q, it.name) >= VoiceMatch.STRONG_SCORE }
        val remote = if (strongSaved != null) {
            emptyList()
        } else {
            repo.searchDirectory(q, countryCode, limit = 15).ifEmpty {
                val keyword = q.split(' ').filter { it.length >= 4 }.maxByOrNull { it.length }?.takeIf { it != q }
                keyword?.let { repo.searchDirectory(it, countryCode, limit = 30) }.orEmpty()
                    .filter { VoiceMatch.score(q, it.name) >= VoiceMatch.MIN_SCORE }
                    .sortedByDescending { VoiceMatch.score(q, it.name) }
            }
        }
        return (saved + remote).distinctBy { it.id }
    }

    /**
     * A browsable folder. [playableTiles] shows its stations as artwork tiles and
     * [browsableTiles] its subfolders; otherwise the root's row style applies.
     */
    private fun folder(id: String, title: String, playableTiles: Boolean = false, browsableTiles: Boolean = false) =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
                    .apply {
                        if (playableTiles || browsableTiles) {
                            setExtras(
                                Bundle().apply {
                                    if (playableTiles) putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_GRID_ITEM)
                                    if (browsableTiles) putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_GRID_ITEM)
                                },
                            )
                        }
                    }
                    .build(),
            )
            .build()

    private fun paged(items: List<MediaItem>, page: Int, pageSize: Int): ImmutableList<MediaItem> {
        val size = if (pageSize <= 0) items.size else pageSize
        return ImmutableList.copyOf(items.drop(page * size).take(size))
    }

    companion object {
        internal const val SLEEP_SETUP_NOTIFICATION_ID = 8703
        private const val SLEEP_SETUP_CHANNEL = "sleep_timer_setup"
        private const val STALE_PAUSE_MS = 30_000L
        private const val USAGE_FLUSH_MS = 30_000L
        private const val CAR_LIST_SIZE = 40
        private const val MAX_CAR_COUNTRIES = 12

        // Android Auto content-style hints (androidx.media.utils.MediaConstants values).
        private const val CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        private const val CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        private const val CONTENT_STYLE_LIST_ITEM = 1
        private const val CONTENT_STYLE_GRID_ITEM = 2
    }
}
