package app.openradio.data

import app.openradio.data.remote.TrackArtworkClient
import app.openradio.domain.TrackInfo
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackArtworkClientTest {
    private val server = MockWebServer().apply { start() }
    private val client = TrackArtworkClient(OkHttpClient(), server.url("/search").toString())

    @After fun tearDown() = server.shutdown()

    @Test fun returnsMatchingArtworkAndCaches() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"resultCount":2,"results":[
                {"artistName":"Someone Else","trackName":"Volare","artworkUrl100":"https://a/x/100x100bb.jpg"},
                {"artistName":"Domenico Modugno","trackName":"Volare","artworkUrl100":"https://a/y/100x100bb.jpg","collectionName":"z"}]}""",
            ),
        )
        val track = TrackInfo("Volare", "Domenico Modugno")
        assertEquals("https://a/y/600x600bb.jpg", client.artworkFor(track))
        assertEquals("https://a/y/600x600bb.jpg", client.artworkFor(track))
        assertEquals(1, server.requestCount)
        val path = server.takeRequest().requestUrl!!
        assertEquals("Domenico Modugno Volare", path.queryParameter("term"))
    }

    @Test fun skipsTitlesWithoutArtist() = runBlocking {
        assertNull(client.artworkFor(TrackInfo("Morning Show")))
        assertEquals(0, server.requestCount)
    }

    @Test fun errorsAreNotCached() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("""{"results":[]}"""))
        val track = TrackInfo("Song", "Artist")
        assertNull(client.artworkFor(track))
        assertNull(client.artworkFor(track))
        assertEquals(2, server.requestCount)
    }
}
