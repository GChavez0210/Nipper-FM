package app.openradio.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.core.content.getSystemService
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import app.openradio.MainActivity
import app.openradio.R
import app.openradio.RadioApp
import app.openradio.data.remote.RadioBrowserClient
import app.openradio.domain.AlternateFeedSelector
import app.openradio.domain.BufferProfile
import app.openradio.domain.ErrorLogEntry
import app.openradio.domain.FailureKind
import app.openradio.domain.PlaybackErrorClassifier
import app.openradio.domain.PlaybackPrefs
import app.openradio.domain.PlaybackStatus
import app.openradio.domain.PlaybackStatusRules
import app.openradio.domain.RecoveryPhase
import app.openradio.domain.ReconnectController
import app.openradio.domain.SleepCycle
import app.openradio.domain.SleepTimer
import app.openradio.domain.Station
import app.openradio.domain.StreamFeed
import app.openradio.domain.StreamUrlPreference
import app.openradio.ShortcutPublisher
import app.openradio.tile.LastStationTileService
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import app.openradio.domain.VoiceMatch
import app.openradio.domain.VolumeTrim
import app.openradio.widget.RadioWidgetProvider
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
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
    private var session: MediaLibrarySession? = null
    private lateinit var reconnect: ReconnectController
    private lateinit var sleepTimer: SleepTimer
    private val audioFx = AudioFxController()

    private var currentStationId: String? = null
    private var recordedStationId: String? = null
    private var pausedAtElapsed = 0L
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var playbackPrefs: PlaybackPrefs = PlaybackPrefs()
    private var mobileDataOverrideOnce = false
    private var wifiOnlyBlocked = false

    private val alternateFeedsCache = HashMap<String, List<StreamFeed>>()
    private val triedUrlsForCurrent = HashSet<String>()

    private var favoriteIds: Set<String> = emptySet()
    /** Player volume = per-station trim (cuts only) × sleep fade-out. */
    private var trimDb = 0
    private var fadeVolume = 1f

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

        val activityIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val bitmapLoader = androidx.media3.session.CacheBitmapLoader(
            androidx.media3.datasource.DataSourceBitmapLoader.Builder(this)
                .setDataSourceFactory(dataSourceFactory)
                .build(),
        )
        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setId("radio")
            .setSessionActivity(activityIntent)
            .setBitmapLoader(bitmapLoader)
            .build()
        setMediaNotificationProvider(
            object : DefaultMediaNotificationProvider(this) {
                override fun getNotificationContentTitle(metadata: MediaMetadata): CharSequence? =
                    metadata.title ?: metadata.station ?: metadata.displayTitle

                override fun getNotificationContentText(metadata: MediaMetadata): CharSequence? =
                    if (metadata.title != null && metadata.title != metadata.station) {
                        metadata.station ?: metadata.subtitle
                    } else {
                        metadata.subtitle ?: metadata.artist
                    }
            }.also {
                it.setSmallIcon(R.drawable.ic_stat_radio)
            },
        )

        // Observe audio processing & network playback preferences.
        scope.launch {
            prefsRepo.playbackPrefs.collect { prefs ->
                playbackPrefs = prefs
                audioFx.applyPrefs(prefs)
                if (enforceWifiOnly()) return@collect
                if (!prefs.wifiOnly && wifiOnlyBlocked) {
                    wifiOnlyBlocked = false
                    publishExtras()
                }
            }
        }

        // Keep the car's Presets / Recents lists and the home-screen widget in sync.
        scope.launch {
            repo.favorites.drop(1).collect {
                session?.notifyChildrenChanged(MediaIds.PRESETS, repo.carPresetsNow().size, null)
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
            mobileDataOverrideOnce = false
            recordedStationId = null
            pausedAtElapsed = 0L
            triedUrlsForCurrent.clear()
            mediaItem?.localConfiguration?.uri?.toString()?.let { triedUrlsForCurrent += it }
            reconnect.onStationChanged(id)
            sleepTimer.onStationChanged()
            sessionBytes.set(0)
            val stationId = id?.let(MediaIds::stationId)
            scope.launch { setTrim(stationId?.let { repo.volumeTrim(it) } ?: 0, persist = null) }
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

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            val item = player.currentMediaItem ?: return
            val station = item.toStation() ?: return
            val title = mediaMetadata.title?.toString()?.trim().orEmpty()
            if (title.isNotEmpty() && !title.equals(station.name, ignoreCase = true)) {
                scope.launch { repo.recordOnAir(station, title) }
                sleepTimer.onTrackTitle(title)
            }
            updateWidget()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) {
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
        player.volume = (VolumeTrim.attenuationFactor(trimDb) * fadeVolume).coerceIn(0f, 1f)
    }

    /** Applies [db] to the live player; when [persist] is set, also stores it for that station. */
    private fun setTrim(db: Int, persist: Station?) {
        trimDb = VolumeTrim.clamp(db)
        audioFx.setBoostMillibels(VolumeTrim.boostMillibels(trimDb))
        applyVolume()
        publishExtras()
        if (persist != null) scope.launch { repo.setVolumeTrim(persist, trimDb) }
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
        val sleeping = sleepTimer.endsAtMs != null
        buttons += CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setCustomIconResId(if (sleeping) R.drawable.ic_sleep_on else R.drawable.ic_sleep_off)
            .setDisplayName(
                when {
                    sleepTimer.endOfTrack -> "Sleep after this track (tap for 15 min)"
                    sleeping -> "Sleep timer on (tap to extend or turn off)"
                    else -> "Sleep timer (15 min)"
                },
            )
            .setSessionCommand(SessionCommand(SessionKeys.CMD_SLEEP_CYCLE, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_FORWARD, CommandButton.SLOT_OVERFLOW)
            .build()
        s.setMediaButtonPreferences(buttons)
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
                .add(SessionCommand(SessionKeys.CMD_SLEEP_CYCLE, Bundle.EMPTY))
                .add(SessionCommand(SessionKeys.CMD_SLEEP_END_OF_TRACK, Bundle.EMPTY))
                .add(SessionCommand(SessionKeys.CMD_SET_VOLUME_TRIM, Bundle.EMPTY))
                .build()
            // Connecting (including from the car) never starts audio.
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
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
                SessionKeys.CMD_SLEEP_CYCLE -> {
                    val remaining = if (sleepTimer.endOfTrack) 0L
                    else (sleepTimer.endsAtMs ?: 0L) - System.currentTimeMillis()
                    val next = SleepCycle.next(remaining)
                    if (next > 0) sleepTimer.start(next) else sleepTimer.cancel()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
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
            val extras = Bundle().apply {
                putInt(
                    CONTENT_STYLE_PLAYABLE_HINT,
                    CONTENT_STYLE_LIST_ITEM,
                )
                putInt(
                    CONTENT_STYLE_BROWSABLE_HINT,
                    CONTENT_STYLE_LIST_ITEM,
                )
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
            val items: List<MediaItem> = when (parentId) {
                MediaIds.ROOT -> listOf(folder(MediaIds.PRESETS, "Presets"), folder(MediaIds.RECENTS, "Recents"))
                MediaIds.PRESETS -> repo.carPresetsNow().map { it.toMediaItem(playable = false) }
                MediaIds.RECENTS -> repo.recentsNow().map { it.toMediaItem(playable = false) }
                MediaIds.RECENT_ROOT -> repo.recentsNow().take(1).map { it.toMediaItem(playable = false) }
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
            val station = id?.let { repo.resolveForPlayback(it) }
            if (station == null) LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            else LibraryResult.ofItem(station.toMediaItem(playable = false), null)
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = scope.future {
            val count = searchStations(query).size
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
            val items = searchStations(query).map { it.toMediaItem(playable = false) }
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
            val station = preferKnownGoodUrl(resolved)
            reconnect.onStationChanged(MediaIds.station(station.id))

            // Starting from a preset, recent or favorite queues that whole list so next/previous
            // work on the lock screen, the notification and in the car.
            val presets = repo.carPresetsNow()
            val recents = repo.recentsNow()
            val favorites = repo.favoritesNow().map { it.station }
            val list = when {
                presets.any { it.id == station.id } -> presets
                recents.any { it.id == station.id } -> recents
                favorites.any { it.id == station.id } -> favorites
                else -> listOf(station)
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
            mediaItems.mapNotNull { resolveRequest(it)?.toMediaItem(playable = true) }.toMutableList()
        }

        /** Lets a host resume the last station; the host decides whether to actually play. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val last = repo.recentsNow().firstOrNull() ?: repo.carPresetsNow().firstOrNull()
                ?: throw UnsupportedOperationException("Nothing to resume")
            MediaSession.MediaItemsWithStartPosition(listOf(last.toMediaItem(playable = isForPlayback)), 0, C.TIME_UNSET)
        }
    }

    /**
     * Turns a controller's request into a station: by stable id (cached URL first, so it works
     * during a directory outage) or by spoken search query.
     */
    private suspend fun resolveRequest(item: MediaItem): Station? {
        MediaIds.stationId(item.mediaId)?.let { id ->
            // A full record with a URI comes from a fresh directory result: prefer and remember it.
            if (item.localConfiguration != null) {
                item.toStation()?.let { fresh ->
                    repo.rememberStreamUrl(fresh.id, fresh.streamUrl)
                    return fresh
                }
            }
            // Otherwise use the cached record, which keeps working during a directory outage.
            return repo.localStation(id) ?: repo.resolveForPlayback(id)
        }
        val query = item.requestMetadata.searchQuery
        if (query != null) {
            if (VoiceMatch.cleanQuery(query).isEmpty()) {
                return repo.carPresetsNow().firstOrNull() ?: repo.recentsNow().firstOrNull()
            }
            return searchStations(query).let { VoiceMatch.best(query, it) ?: it.firstOrNull() }
        }
        return null
    }

    /** Swaps in a URL that worked before when the station's usual URL failed recently. */
    private suspend fun preferKnownGoodUrl(station: Station): Station {
        val health = runCatching { repo.streamHealth(station.id) }.getOrDefault(emptyList())
        val url = StreamUrlPreference.choose(station.streamUrl, health, System.currentTimeMillis())
        if (url == station.streamUrl) return station
        logEvent("Known-good URL", "Starting with a URL that worked before; the usual one failed recently", station, url)
        return station.copy(streamUrl = url, isHls = url.contains(".m3u8", ignoreCase = true))
    }

    /** Favorites first (exact matches win), then the directory. */
    private suspend fun searchStations(rawQuery: String): List<Station> {
        val q = VoiceMatch.cleanQuery(rawQuery).ifEmpty { rawQuery }
        val saved = repo.searchFavorites(q)
        val exactSaved = VoiceMatch.best(rawQuery, repo.favoritesNow().map { it.station })
        val local = listOfNotNull(exactSaved) + saved
        val remote = if (exactSaved == null) repo.searchDirectory(q) else emptyList()
        return (local + remote).distinctBy { it.id }
    }

    private fun folder(id: String, title: String) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
                .build(),
        )
        .build()

    private fun paged(items: List<MediaItem>, page: Int, pageSize: Int): ImmutableList<MediaItem> {
        val size = if (pageSize <= 0) items.size else pageSize
        return ImmutableList.copyOf(items.drop(page * size).take(size))
    }

    companion object {
        private const val STALE_PAUSE_MS = 30_000L
        private const val USAGE_FLUSH_MS = 30_000L

        // Android Auto content-style hints (androidx.media.utils.MediaConstants values).
        private const val CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        private const val CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        private const val CONTENT_STYLE_LIST_ITEM = 1
    }
}
