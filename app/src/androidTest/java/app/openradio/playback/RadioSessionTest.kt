package app.openradio.playback

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.PowerManager
import androidx.core.content.getSystemService
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import app.openradio.RadioApp
import app.openradio.domain.Station
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.time.DayOfWeek
import org.junit.Assume.assumeTrue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import org.junit.Test

@OptIn(UnstableApi::class)
class RadioSessionTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RadioApp
    private val token = SessionToken(app, ComponentName(app, RadioService::class.java))

    @Test
    fun browseRootExposesPresetsRecentsCountriesAndGenres() = withBrowser { browser ->
        assertEquals(MediaIds.ROOT, browser.getLibraryRoot(null).await().value?.mediaId)
        val children = browser.getChildren(MediaIds.ROOT, 0, 20, null).await().value.orEmpty()
        assertEquals(listOf(MediaIds.PRESETS, MediaIds.RECENTS, MediaIds.COUNTRIES, MediaIds.GENRES), children.map { it.mediaId })
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
            // 172 ADTS AAC-LC silent frames at 44.1 kHz make one four-second segment.
            val frame = byteArrayOf(0xff.toByte(), 0xf1.toByte(), 0x50, 0x40, 0x01, 0x7f, 0xfc.toByte(), 0x01, 0x18, 0x20, 0x07)
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
                        val segment = Buffer().apply { repeat(172) { write(frame) } }
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

    private fun wav(): ByteArray {
        val bytes = 8_000 * 2 * 30
        return ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + bytes).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(bytes).array()
    }
}
