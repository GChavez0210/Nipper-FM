package app.openradio.playback

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import androidx.core.content.getSystemService
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import app.openradio.RadioApp
import app.openradio.data.remote.TrackArtworkClient
import app.openradio.domain.Station
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.time.DayOfWeek
import org.junit.Assume.assumeTrue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class RadioSessionTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RadioApp
    private val token = SessionToken(app, ComponentName(app, RadioService::class.java))

    @Test
    fun browseRootExposesPresetsPlaylistsRecentsAndBrowse() = withBrowser { browser ->
        assertEquals(MediaIds.ROOT, browser.getLibraryRoot(null).await().value?.mediaId)
        val children = browser.getChildren(MediaIds.ROOT, 0, 20, null).await().value.orEmpty()
        assertEquals(listOf(MediaIds.PRESETS, MediaIds.PLAYLISTS, MediaIds.RECENTS, MediaIds.BROWSE), children.map { it.mediaId })
        assertFalse(browser.playWhenReady)
    }

    @Test
    fun recentRootExposesStationAndPlayResumesIt() = withBrowser { browser ->
        withServer { server ->
            val station = station(server)
            val audio = wav()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = audioResponse(audio)
            }
            app.container.stations.recordPlayed(station)
            val root = browser.getLibraryRoot(LibraryParams.Builder().setRecent(true).build()).await().value
            assertEquals(MediaIds.RECENT_ROOT, root?.mediaId)
            val recent = browser.getChildren(MediaIds.RECENT_ROOT, 0, 1, null).await().value.orEmpty()
            assertEquals(listOf(MediaIds.station(station.id)), recent.map { it.mediaId })
            assertFalse(browser.playWhenReady)
            browser.play()
            awaitPlaying(browser)
            assertEquals(MediaIds.station(station.id), browser.currentMediaItem?.mediaId)
        }
    }

    @Test
    fun icyTitleSplitsIntoArtistSongAndStreamCover() = withBrowser { browser ->
        withServer { server ->
            val station = station(server)
            val cover = "http://127.0.0.1:${server.port}/cover.jpg"
            val stream = icyStream(wav(), "StreamTitle='Test Artist - Test Song';StreamUrl='$cover';")
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) =
                    MockResponse().setHeader("Content-Type", "audio/wav").setHeader("icy-metaint", ICY_METAINT)
                        .setBody(Buffer().write(stream))
            }
            browser.setMediaItem(station.toMediaItem(playable = true))
            browser.prepare()
            browser.play()
            awaitPlaying(browser)
            withTimeout(15_000) {
                while (browser.mediaMetadata.artworkUri?.toString() != cover) delay(25)
            }
            assertEquals("Test Song", browser.mediaMetadata.title?.toString())
            assertEquals("Test Artist", browser.mediaMetadata.artist?.toString())
            // The queued station keeps its own identity and logo.
            assertEquals("Test station", browser.currentMediaItem?.mediaMetadata?.station?.toString())
            assertEquals(null, browser.currentMediaItem?.mediaMetadata?.artworkUri)
        }
    }

    @Test
    fun icySongChangeClearsOldArtistCoverAndHandlesBlankAndStationName() = withBrowser { browser ->
        withServer { server ->
            val logo = "http://127.0.0.1:${server.port}/logo.png"
            val cover1 = "http://127.0.0.1:${server.port}/cover1.jpg"
            val station = station(server).copy(faviconUrl = logo)
            // Each 16_000-byte ICY block is 1 second of 8 kHz 16-bit mono WAV.
            val stream = icyStreamBlocks(
                wav(seconds = 10),
                mapOf(
                    0 to "StreamTitle='Fabrizio De André – La città vecchia';StreamUrl='$cover1';",
                    1 to "StreamTitle='Fabrizio De André – La città vecchia';StreamUrl='$cover1';",
                    2 to "StreamTitle='Morning Show';",
                    4 to "StreamTitle='';",
                    6 to "StreamTitle='Test station';",
                ),
            )
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) =
                    MockResponse().setHeader("Content-Type", "audio/wav").setHeader("icy-metaint", ICY_METAINT)
                        .setBody(Buffer().write(stream))
            }
            browser.setMediaItem(station.toMediaItem(playable = true))
            browser.prepare()
            browser.play()
            awaitPlaying(browser)

            withTimeout(15_000) {
                while (browser.mediaMetadata.title?.toString() != "La città vecchia") delay(20)
            }
            assertEquals("Fabrizio De André", browser.mediaMetadata.artist?.toString())
            assertEquals(cover1, browser.mediaMetadata.artworkUri?.toString())

            // Song changes to a title-only show: old artist and cover must vanish immediately.
            withTimeout(15_000) {
                while (browser.mediaMetadata.title?.toString() != "Morning Show") delay(20)
            }
            assertNull(browser.mediaMetadata.artist)
            assertEquals(logo, browser.mediaMetadata.artworkUri?.toString())

            // Blank StreamTitle clears title and artist rather than leaving an empty string.
            withTimeout(15_000) {
                while (browser.mediaMetadata.title != null) delay(20)
            }
            assertNull(browser.mediaMetadata.artist)
            assertEquals(logo, browser.mediaMetadata.artworkUri?.toString())

            // Station logo was never overwritten by cover art in MediaItem or Room.
            assertEquals(logo, browser.currentMediaItem?.mediaMetadata?.artworkUri?.toString())
            assertEquals(logo, app.container.stations.localStation(station.id)?.faviconUrl)

            // Recently On Air recorded the two distinct tracks without duplicates.
            delay(200)
            val entries = app.container.stations.onAirHistory.first().filter { it.stationId == station.id }
            assertEquals(
                listOf("Morning Show", "Fabrizio De André - La città vecchia"),
                entries.map { it.trackTitle },
            )
        }
    }

    @Test
    fun hlsId3Tit2AndTpe1SplitAndClearStaleArtistOnTitleOnlyTrack() = withBrowser { browser ->
        withServer { server ->
            val logo = "http://127.0.0.1:${server.port}/logo.png"
            val playlist = buildString {
                appendLine("#EXTM3U")
                appendLine("#EXT-X-VERSION:3")
                appendLine("#EXT-X-TARGETDURATION:1")
                appendLine("#EXT-X-MEDIA-SEQUENCE:0")
                repeat(8) { index ->
                    appendLine("#EXTINF:1.0,")
                    appendLine("segment-$index.aac")
                }
                appendLine("#EXT-X-ENDLIST")
            }
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    if (path == "/id3.m3u8") {
                        return MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl").setBody(playlist)
                    }
                    val idx = Regex("segment-(\\d+)\\.aac").find(path)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val tag = when {
                        idx < 2 -> id3Tag(pts90k = idx * 90_000L, title = "Volare", artist = "Domenico Modugno")
                        else -> id3Tag(pts90k = idx * 90_000L, title = "News Bulletin", artist = null)
                    }
                    val seg = Buffer().write(tag)
                    repeat(43) { seg.write(ADTS_SILENT_FRAME) }
                    return MockResponse().setHeader("Content-Type", "audio/aac").setBody(seg)
                }
            }
            val station = station(server).copy(
                streamUrl = "http://127.0.0.1:${server.port}/id3.m3u8",
                faviconUrl = logo,
                isHls = true,
            )
            browser.setMediaItem(station.toMediaItem(playable = true))
            browser.prepare()
            browser.play()
            awaitPlaying(browser)

            withTimeout(15_000) {
                while (browser.mediaMetadata.title?.toString() != "Volare") delay(25)
            }
            assertEquals("Domenico Modugno", browser.mediaMetadata.artist?.toString())

            // Next segment has TIT2 only (no TPE1): old artist must NOT stick around.
            withTimeout(15_000) {
                while (browser.mediaMetadata.title?.toString() != "News Bulletin") delay(25)
            }
            assertNull(browser.mediaMetadata.artist)
        }
    }

    @Test
    fun artworkLookupToggleOffPriorityFallbackAndStationSwitch() = withBrowser { browser ->
        withServer { server ->
            val originalClient = app.container.artworkClient
            val originalPref = app.container.preferences.playbackPrefs.first().trackArtwork
            val lookupCount = java.util.concurrent.atomic.AtomicInteger(0)
            val failLookup = AtomicBoolean(false)
            val logo = "http://127.0.0.1:${server.port}/logo.png"
            val streamCover = "http://127.0.0.1:${server.port}/stream-cover.jpg"
            val itunesCover100 = "http://127.0.0.1:${server.port}/art/100x100bb.jpg"
            val itunesCover600 = "http://127.0.0.1:${server.port}/art/600x600bb.jpg"

            val stream1 = icyStreamBlocks(
                wav(seconds = 8),
                mapOf(
                    0 to "StreamTitle='Artist A - Song A';StreamUrl='$streamCover';",
                    2 to "StreamTitle='Artist B - Song B';",
                    5 to "StreamTitle='Artist C - Song C';",
                ),
            )
            val stream2 = wav(seconds = 4)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/itunes") -> {
                            lookupCount.incrementAndGet()
                            if (failLookup.get()) {
                                MockResponse().setResponseCode(503)
                            } else {
                                val term = request.requestUrl?.queryParameter("term").orEmpty()
                                val body = when {
                                    term.contains("Song B") ->
                                        """{"results":[{"artistName":"Artist B","trackName":"Song B","artworkUrl100":"$itunesCover100"}]}"""
                                    term.contains("Song C") ->
                                        """{"results":[{"artistName":"Artist C","trackName":"Song C","artworkUrl100":"$itunesCover100"}]}"""
                                    else -> """{"results":[]}"""
                                }
                                MockResponse().setBody(body)
                            }
                        }
                        path == "/stream1.wav" -> MockResponse()
                            .setHeader("Content-Type", "audio/wav")
                            .setHeader("icy-metaint", ICY_METAINT)
                            .setBody(Buffer().write(stream1))
                        else -> audioResponse(stream2)
                    }
                }
            }

            try {
                app.container.artworkClient = TrackArtworkClient(
                    app.container.httpClient,
                    "http://127.0.0.1:${server.port}/itunes",
                )
                app.container.preferences.setTrackArtwork(true)
                delay(150)

                val st1 = station(server).copy(
                    streamUrl = "http://127.0.0.1:${server.port}/stream1.wav",
                    faviconUrl = logo,
                )
                val st2 = station(server).copy(
                    name = "Second station",
                    streamUrl = "http://127.0.0.1:${server.port}/stream2.wav",
                    faviconUrl = logo,
                )

                browser.setMediaItem(st1.toMediaItem(playable = true))
                browser.prepare()
                browser.play()
                awaitPlaying(browser)

                // 1. StreamUrl cover takes priority over iTunes lookup (0 requests).
                withTimeout(15_000) {
                    while (browser.mediaMetadata.title?.toString() != "Song A") delay(20)
                }
                assertEquals(streamCover, browser.mediaMetadata.artworkUri?.toString())
                assertEquals(0, lookupCount.get())

                // 2. Song B has no StreamUrl: iTunes lookup fills cover.
                withTimeout(15_000) {
                    while (browser.mediaMetadata.artworkUri?.toString() != itunesCover600) delay(20)
                }
                assertEquals("Song B", browser.mediaMetadata.title?.toString())
                assertEquals("Artist B", browser.mediaMetadata.artist?.toString())
                assertEquals(1, lookupCount.get())

                // 3. Turning off "Look up album artwork" removes looked-up cover immediately and stops further requests.
                app.container.preferences.setTrackArtwork(false)
                withTimeout(10_000) {
                    while (browser.mediaMetadata.artworkUri?.toString() != logo) delay(20)
                }
                assertEquals("Song B", browser.mediaMetadata.title?.toString())

                // Song C arrives while artwork lookup is off: 0 additional requests.
                withTimeout(15_000) {
                    while (browser.mediaMetadata.title?.toString() != "Song C") delay(20)
                }
                delay(200)
                assertEquals(1, lookupCount.get())
                assertEquals(logo, browser.mediaMetadata.artworkUri?.toString())

                // 4. Re-enabling lookup while server returns 503 keeps station logo without crashing.
                failLookup.set(true)
                app.container.preferences.setTrackArtwork(true)
                withTimeout(10_000) {
                    while (lookupCount.get() < 2) delay(20)
                }
                delay(150)
                assertEquals(logo, browser.mediaMetadata.artworkUri?.toString())

                // 5. Switching stations clears song info immediately.
                browser.setMediaItem(st2.toMediaItem(playable = true))
                browser.prepare()
                browser.play()
                awaitPlaying(browser)
                withTimeout(10_000) {
                    while (browser.currentMediaItem?.mediaId != MediaIds.station(st2.id)) delay(20)
                }
                assertNull(browser.mediaMetadata.title)
                assertNull(browser.mediaMetadata.artist)
                assertEquals(logo, browser.mediaMetadata.artworkUri?.toString())
            } finally {
                app.container.artworkClient = originalClient
                app.container.preferences.setTrackArtwork(originalPref)
            }
        }
    }

    @Test
    fun stopAfterTrackHandlesNormalizedTitleAndAvoidsDuplicateHistory() = withBrowser { browser ->
        withServer { server ->
            val logo = "http://127.0.0.1:${server.port}/logo.png"
            val cover = "http://127.0.0.1:${server.port}/cover.jpg"
            // Block 0 & block 2 repeat the same song using an en-dash; block 6 moves to a new song.
            val icyBody = icyStreamBlocks(
                wav(seconds = 8),
                mapOf(
                    0 to "StreamTitle='Lucio Battisti – Il mio canto libero';StreamUrl='$cover';",
                    2 to "StreamTitle='Lucio Battisti – Il mio canto libero';StreamUrl='$cover';",
                    6 to "StreamTitle='Mina – Se telefonando';",
                ),
            )
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse()
                    .setHeader("Content-Type", "audio/wav")
                    .setHeader("icy-metaint", ICY_METAINT)
                    .setBody(Buffer().write(icyBody))
            }
            val station = station(server).copy(faviconUrl = logo)
            browser.setMediaItem(station.toMediaItem(playable = true))
            browser.prepare()
            browser.play()
            awaitPlaying(browser)

            withTimeout(15_000) {
                while (browser.mediaMetadata.title?.toString() != "Il mio canto libero") delay(20)
            }
            assertEquals("Lucio Battisti", browser.mediaMetadata.artist?.toString())

            // Arm "stop after this track". Repeated en-dash metadata at block 2 must NOT pause early.
            val res = browser.sendCustomCommand(
                SessionCommand(SessionKeys.CMD_SLEEP_END_OF_TRACK, Bundle.EMPTY),
                Bundle.EMPTY,
            ).await()
            assertEquals(SessionResult.RESULT_SUCCESS, res.resultCode)
            assertTrue(browser.sessionExtras.getBoolean(SessionKeys.EXTRA_SLEEP_END_OF_TRACK))

            // Wait past block 2 (~1s into 8s audio): must still be playing until block 6 (~3s).
            delay(1_400)
            assertTrue("Expected playback to continue through repeated metadata", browser.playWhenReady)

            // When block 6 ("Se telefonando") arrives, playback must pause automatically.
            withTimeout(15_000) {
                while (browser.playWhenReady) delay(25)
            }
            assertFalse(browser.sessionExtras.getBoolean(SessionKeys.EXTRA_SLEEP_END_OF_TRACK))

            // History must contain "Lucio Battisti - Il mio canto libero" only once (no duplicate).
            val history = app.container.stations.onAirHistory.first()
                .filter { it.stationId == station.id }
            assertEquals(1, history.count { it.trackTitle == "Lucio Battisti - Il mio canto libero" })
            assertTrue(history.any { it.trackTitle == "Mina - Se telefonando" })

            // Recents must keep the station's own logo, never the song cover.
            val recentStation = app.container.stations.recentsNow().firstOrNull { it.id == station.id }
            assertEquals(logo, recentStation?.faviconUrl)
        }
    }

    @Test
    fun retryAfterHttpFailureKeepsStationAndPlays() = withBrowser { browser ->
        withServer { server ->
            val station = station(server)
            val failed = AtomicBoolean(false)
            val audio = wav()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (failed.get()) audioResponse(audio) else MockResponse().setResponseCode(404)
            }
            browser.setMediaItem(station.toMediaItem(playable = true))
            browser.prepare()
            browser.play()
            withTimeout(15_000) {
                while (browser.playerError == null) delay(25)
            }
            failed.set(true)
            browser.prepare()
            browser.play()
            awaitPlaying(browser)
            assertEquals(MediaIds.station(station.id), browser.currentMediaItem?.mediaId)
        }
    }

    @Test
    fun seekableHlsSupportsPauseRewindAndGoLive() = withBrowser { browser ->
        withServer { server ->
            val playlist = buildString {
                appendLine("#EXTM3U")
                appendLine("#EXT-X-VERSION:3")
                appendLine("#EXT-X-TARGETDURATION:4")
                appendLine("#EXT-X-MEDIA-SEQUENCE:100")
                repeat(18) { index ->
                    appendLine("#EXTINF:4.0,")
                    appendLine("segment-$index.aac")
                }
            }
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) =
                    if (request.path == "/live.m3u8") {
                        MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl").setBody(playlist)
                    } else {
                        val segment = Buffer().apply { repeat(172) { write(ADTS_SILENT_FRAME) } }
                        MockResponse().setHeader("Content-Type", "audio/aac").setBody(segment)
                    }
            }
            val station = station(server).copy(streamUrl = "http://127.0.0.1:${server.port}/live.m3u8", isHls = true)
            browser.setMediaItem(station.toMediaItem(playable = true))
            browser.prepare()
            browser.play()
            awaitPlaying(browser)
            browser.pause()
            withTimeout(3_000) { while (browser.isPlaying) delay(25) }
            val livePosition = browser.currentPosition
            org.junit.Assert.assertTrue(livePosition >= 30_000)
            browser.seekTo(livePosition - 30_000)
            withTimeout(3_000) { while (browser.currentPosition > livePosition - 29_000) delay(25) }
            browser.play()
            awaitPlaying(browser)
            browser.seekToDefaultPosition()
            withTimeout(3_000) { while (browser.currentPosition < livePosition - 1_000) delay(25) }
        }
    }

    @Test
    fun scheduledStationStartsWithScreenOff() = withBrowser { browser ->
        val alarms = checkNotNull(app.getSystemService<AlarmManager>())
        val routines = ListeningRoutines(app)
        assumeTrue(routines.canSchedule())
        val power = checkNotNull(app.getSystemService<PowerManager>())
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val original = routines.list()
        val originallyInteractive = power.isInteractive
        withServer { server ->
            val station = station(server)
            val audio = wav()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = audioResponse(audio)
            }
            val routine = ListeningRoutine(UUID.randomUUID().toString(), station, 7, 0, DayOfWeek.entries.toSet(), false)
            val epoch = System.currentTimeMillis() + 5_000
            val operation = PendingIntent.getBroadcast(
                app,
                421,
                Intent(app, ListeningAlarmReceiver::class.java)
                    .setAction(ListeningRoutines.ACTION_ALARM)
                    .putExtra(ListeningRoutines.EXTRA_ROUTINE, routine.id)
                    .putExtra(ListeningRoutines.EXTRA_TIME, epoch),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            try {
                routines.save(listOf(routine))
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epoch, operation)
                automation.executeShellCommand("input keyevent KEYCODE_SLEEP").close()
                withTimeout(3_000) { while (power.isInteractive) delay(50) }
                awaitPlaying(browser)
                assertFalse(power.isInteractive)
                assertEquals(MediaIds.station(station.id), browser.currentMediaItem?.mediaId)
            } finally {
                alarms.cancel(operation)
                routines.save(original)
                if (originallyInteractive) automation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
            }
        }
    }

    private suspend fun withServer(test: suspend (MockWebServer) -> Unit) {
        val server = withContext(Dispatchers.IO) { MockWebServer().apply { start(java.net.InetAddress.getByName("127.0.0.1"), 0) } }
        try {
            test(server)
        } finally {
            withContext(Dispatchers.IO) { server.close() }
        }
    }

    private fun withBrowser(test: suspend (MediaBrowser) -> Unit) = runBlocking {
        withContext(Dispatchers.Main) {
            val browser = MediaBrowser.Builder(app, token).buildAsync().await()
            try {
                browser.pause()
                browser.clearMediaItems()
                test(browser)
            } finally {
                browser.pause()
                browser.clearMediaItems()
                browser.release()
            }
        }
    }

    private suspend fun awaitPlaying(browser: MediaBrowser) = withTimeout(20_000) {
        while (!browser.isPlaying) delay(25)
    }

    private fun station(server: MockWebServer) = Station(
        id = "custom:instrumented-${UUID.randomUUID()}",
        name = "Test station",
        streamUrl = "http://127.0.0.1:${server.port}/stream.wav",
        isCustom = true,
    )

    private fun audioResponse(audio: ByteArray) =
        MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(audio))

    /** Interleaves one ICY metadata block after the first [ICY_METAINT] bytes, then empty blocks. */
    private fun icyStream(audio: ByteArray, metadata: String): ByteArray =
        icyStreamBlocks(audio, mapOf(0 to metadata))

    /** Interleaves ICY metadata blocks at 0-based [ICY_METAINT] boundaries. */
    private fun icyStreamBlocks(audio: ByteArray, metadataByBlock: Map<Int, String>): ByteArray {
        val interval = ICY_METAINT.toInt()
        val out = java.io.ByteArrayOutputStream()
        var offset = 0
        var blockIndex = 0
        while (offset < audio.size) {
            val n = minOf(interval, audio.size - offset)
            out.write(audio, offset, n)
            offset += n
            if (n < interval) break
            val metadata = metadataByBlock[blockIndex++]
            if (metadata != null) {
                val meta = metadata.toByteArray(Charsets.UTF_8)
                val blocks = (meta.size + 15) / 16
                out.write(blocks)
                out.write(meta.copyOf(blocks * 16))
            } else {
                out.write(0)
            }
        }
        return out.toByteArray()
    }

    /** Builds an ID3v2.4 tag with HLS transport timestamp plus optional TIT2 and TPE1 frames. */
    private fun id3Tag(pts90k: Long, title: String?, artist: String?): ByteArray {
        val frames = java.io.ByteArrayOutputStream()
        val privPayload = java.io.ByteArrayOutputStream().apply {
            write("com.apple.streaming.transportStreamTimestamp".toByteArray(Charsets.US_ASCII))
            write(0)
            write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(pts90k).array())
        }.toByteArray()
        writeId3Frame(frames, "PRIV", privPayload)
        if (title != null) {
            writeId3Frame(frames, "TIT2", byteArrayOf(0x03) + title.toByteArray(Charsets.UTF_8))
        }
        if (artist != null) {
            writeId3Frame(frames, "TPE1", byteArrayOf(0x03) + artist.toByteArray(Charsets.UTF_8))
        }
        val body = frames.toByteArray()
        return java.io.ByteArrayOutputStream().apply {
            write("ID3".toByteArray(Charsets.US_ASCII))
            write(byteArrayOf(0x04, 0x00, 0x00))
            write(synchsafe(body.size))
            write(body)
        }.toByteArray()
    }

    private fun writeId3Frame(out: java.io.ByteArrayOutputStream, id: String, payload: ByteArray) {
        out.write(id.toByteArray(Charsets.US_ASCII))
        out.write(synchsafe(payload.size))
        out.write(byteArrayOf(0x00, 0x00))
        out.write(payload)
    }

    private fun synchsafe(value: Int): ByteArray = byteArrayOf(
        ((value shr 21) and 0x7F).toByte(),
        ((value shr 14) and 0x7F).toByte(),
        ((value shr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte(),
    )

    private companion object {
        const val ICY_METAINT = "16000"
        // One ADTS AAC-LC silent frame at 44.1 kHz (~23.22 ms).
        val ADTS_SILENT_FRAME = byteArrayOf(
            0xff.toByte(), 0xf1.toByte(), 0x50, 0x40, 0x01, 0x7f, 0xfc.toByte(), 0x01, 0x18, 0x20, 0x07,
        )
    }

    private fun wav(seconds: Int = 30): ByteArray {
        val bytes = 8_000 * 2 * seconds
        return ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + bytes).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(bytes).array()
    }
}
