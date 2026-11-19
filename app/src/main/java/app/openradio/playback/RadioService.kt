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
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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
import app.openradio.domain.VoiceMatch
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

    override fun onCreate() {
        super.onCreate()

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(RadioBrowserClient.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
        val dataSourceFactory = DefaultDataSource.Factory(this, http)
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
                player.volume = 1f
                player.pause()
            },
            onChanged = { publishExtras() },
            onFadeVolume = { vol -> player.volume = vol.coerceIn(0.05f, 1f) },
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
        registerNetworkCallback()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    /** Swiping the app away keeps audio running; only an idle service shuts down. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
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
            val feeds = alternateFeedsCache.getOrPut(station.id) {
                runCatching { repo.alternateFeedsFor(station) }.getOrDefault(emptyList())
            }
            val failover = AlternateFeedSelector.nextFailoverFeed(currentUrl, feeds, triedUrlsForCurrent)
            if (failover != null) {
                triedUrlsForCurrent += failover.streamUrl
                return failover.streamUrl
            }
        }
        return repo.fetchFreshUrl(stationId)
    }

    private fun onRecoveryStateChanged(recovery: app.openradio.domain.RecoveryState) {
        if (recovery.phase == RecoveryPhase.Failed) {
            val station = player.currentMediaItem?.toStation()
            val kind = reconnect.lastKind ?: FailureKind.Other
            val online = isDeviceOnline()
            if (station != null && PlaybackErrorClassifier.shouldFlagStationFailed(kind, online)) {
                scope.launch { repo.markFailedAttempt(station, failed = true) }
            }
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
            updateWidget()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) {
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
            // Recents are written and any failedLastAttempt flag is cleared once audio actually starts.
            scope.launch {
                repo.recordPlayed(station)
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
            val kind = if (!isDeviceOnline()) FailureKind.Network else classifyPlaybackError(error.errorCode)
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
        }
        s.setSessionExtras(extras)
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

            val station = resolveRequest(requested)
                ?: throw IllegalArgumentException("Station not found: ${requested.mediaId}")
            reconnect.onStationChanged(MediaIds.station(station.id))

            // Starting from a preset or recent queues the whole list so next/previous work in the car.
            val presets = repo.carPresetsNow()
            val recents = repo.recentsNow()
            val list = when {
                presets.any { it.id == station.id } -> presets
                recents.any { it.id == station.id } -> recents
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

        // Android Auto content-style hints (androidx.media.utils.MediaConstants values).
        private const val CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        private const val CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        private const val CONTENT_STYLE_LIST_ITEM = 1
    }
}
