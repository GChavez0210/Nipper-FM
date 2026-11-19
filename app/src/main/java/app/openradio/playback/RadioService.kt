package app.openradio.playback

import androidx.annotation.OptIn
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.SystemClock
import androidx.core.content.getSystemService
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.common.util.UnstableApi
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
import app.openradio.domain.ReconnectController
import app.openradio.domain.SleepTimer
import app.openradio.domain.Station
import app.openradio.domain.VoiceMatch
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
 * controls and Android Auto. Reconnects, the sleep timer and recents bookkeeping live here so they
 * keep working with the screen locked and no UI present.
 */
@OptIn(UnstableApi::class)
class RadioService : MediaLibraryService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container get() = (application as RadioApp).container
    private val repo get() = container.stations

    private lateinit var player: ExoPlayer
    private var session: MediaLibrarySession? = null
    private lateinit var reconnect: ReconnectController
    private lateinit var sleepTimer: SleepTimer

    private var currentStationId: String? = null
    private var recordedStationId: String? = null
    private var pausedAtElapsed = 0L
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(RadioBrowserClient.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
        val dataSourceFactory = DefaultDataSource.Factory(this, http)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(
                DefaultLoadControl.Builder().setBufferDurationsMs(10_000, 40_000, 2_500, 5_000).build(),
            )
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        reconnect = ReconnectController(
            scope = scope,
            fetchFreshUrl = { id -> MediaIds.stationId(id)?.let { repo.fetchFreshUrl(it) } },
            performRetry = ::retryPlayback,
            onStateChanged = { publishExtras() },
        )
        sleepTimer = SleepTimer(
            scope = scope,
            clock = System::currentTimeMillis,
            onExpired = { player.pause() },
            onChanged = { publishExtras() },
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

        // Keep the car's Presets / Recents lists in sync with edits made on the phone.
        scope.launch {
            repo.favorites.drop(1).collect {
                session?.notifyChildrenChanged(MediaIds.PRESETS, repo.carPresetsNow().size, null)
            }
        }
        scope.launch {
            repo.recents.drop(1).collect {
                session?.notifyChildrenChanged(MediaIds.RECENTS, repo.recentsNow().size, null)
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
        scope.cancel()
        session?.release()
        session = null
        player.release()
        super.onDestroy()
    }

    // ---- playback events --------------------------------------------------------------------

    private inner class PlayerEvents : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val id = mediaItem?.mediaId
            // retryPlayback() replaces the item in place; that is not a station change.
            if (id == currentStationId) return
            currentStationId = id
            recordedStationId = null
            pausedAtElapsed = 0L
            reconnect.onStationChanged(id)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) return
            reconnect.onPlaybackStarted()
            val item = player.currentMediaItem ?: return
            val station = item.toStation() ?: return
            if (recordedStationId == item.mediaId) return
            recordedStationId = item.mediaId
            // Recents are written only once audio has actually started.
            scope.launch {
                repo.recordPlayed(station)
                repo.registerClick(station.id)
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) {
                pausedAtElapsed = SystemClock.elapsedRealtime()
                reconnect.onUserPaused()
                return
            }
            reconnect.onUserResumed()
            when {
                player.playerError != null -> player.prepare()
                SystemClock.elapsedRealtime() - pausedAtElapsed > STALE_PAUSE_MS && pausedAtElapsed != 0L ->
                    jumpToLive()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val item = player.currentMediaItem ?: return
            if (!player.playWhenReady) return // user paused: nothing to recover
            reconnect.onPlaybackError(
                stationId = item.mediaId,
                kind = classifyPlaybackError(error.errorCode),
                currentUrl = item.localConfiguration?.uri?.toString(),
            )
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
            player.replaceMediaItem(player.currentMediaItemIndex, item.buildUpon().setUri(newUrl).build())
            MediaIds.stationId(item.mediaId)?.let { scope.launch { repo.rememberStreamUrl(it, newUrl) } }
        }
        player.seekToDefaultPosition()
        player.prepare()
    }

    private fun publishExtras() {
        val s = session ?: return
        val recovery = reconnect.state
        val extras = Bundle().apply {
            putString(SessionKeys.EXTRA_RECOVERY, recovery.phase.name)
            putInt(SessionKeys.EXTRA_ATTEMPT, recovery.attempt)
            putInt(SessionKeys.EXTRA_MAX_ATTEMPTS, recovery.maxAttempts)
            putString(SessionKeys.EXTRA_MESSAGE, recovery.message)
            putLong(SessionKeys.EXTRA_SLEEP_ENDS_AT, sleepTimer.endsAtMs ?: 0L)
        }
        s.setSessionExtras(extras)
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService<ConnectivityManager>() ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch { currentStationId?.let { reconnect.onNetworkAvailable(it) } }
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
            if (customCommand.customAction == SessionKeys.CMD_SLEEP) {
                val minutes = args.getInt(SessionKeys.ARG_MINUTES, 0)
                if (minutes > 0) sleepTimer.start(minutes) else sleepTimer.cancel()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
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
            return Futures.immediateFuture(LibraryResult.ofItem(folder(rootId, "Open Radio"), rootParams))
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
