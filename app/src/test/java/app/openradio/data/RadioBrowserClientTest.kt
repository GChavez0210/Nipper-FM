package app.openradio.data

import app.openradio.data.remote.RadioBrowserClient
import app.openradio.data.remote.StationDto
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RadioBrowserClientTest {
    private lateinit var a: MockWebServer
    private lateinit var b: MockWebServer

    @Before fun setUp() { a = MockWebServer().apply { start() }; b = MockWebServer().apply { start() } }
    @After fun tearDown() { a.shutdown(); b.shutdown() }

    private fun host(s: MockWebServer) = "${s.hostName}:${s.port}"

    private fun client(hosts: List<String>, discovery: String = "http://${host(a)}/nope") =
        RadioBrowserClient(
            OkHttpClient(), discoveryUrl = discovery, fallbackHosts = hosts, scheme = "http",
            userAgent = "TestAgent/1.0", shuffle = { it },
        )

    private val oneStation = """[{"stationuuid":"u1","name":"KEXP","url":"http://old","url_resolved":"https://res","countrycode":"US","country":"USA","state":"Washington","tags":"rock, indie","codec":"AAC","bitrate":128,"hls":0}]"""

    @Test fun sendsUserAgentAndDocumentedBrokenFilterAndTrendOrder() = runBlocking {
        a.enqueue(MockResponse().setResponseCode(404)) // discovery fails -> fallback hosts
        a.enqueue(MockResponse().setBody(oneStation))
        val result = client(listOf(host(a))).search("kexp", "US", "rock", 30, 30)

        a.takeRequest() // discovery
        val req = a.takeRequest()
        assertEquals("TestAgent/1.0", req.getHeader("User-Agent"))
        val url = req.requestUrl!!
        assertEquals("true", url.queryParameter("hidebroken"))
        assertEquals("clicktrend", url.queryParameter("order"))
        assertEquals("true", url.queryParameter("reverse"))
        assertEquals("kexp", url.queryParameter("name"))
        assertEquals("US", url.queryParameter("countrycode"))
        assertEquals("rock", url.queryParameter("tag"))
        assertEquals("30", url.queryParameter("offset"))
        assertEquals("u1", result.single().id)
    }

    @Test fun prefersUrlResolvedAndFallsBackToUrl() {
        val json = Json { ignoreUnknownKeys = true }
        val dto = json.decodeFromString<List<StationDto>>(oneStation).single()
        assertEquals("https://res", dto.toStation()!!.streamUrl)
        assertEquals("http://old", dto.copy(urlResolved = "").toStation()!!.streamUrl)
        assertNull(dto.copy(urlResolved = "", url = "").toStation())
        assertEquals(listOf("rock", "indie"), dto.toStation()!!.tags)
    }

    @Test fun failsOverToNextMirrorOnServerError() = runBlocking {
        a.enqueue(MockResponse().setResponseCode(404))
        a.enqueue(MockResponse().setResponseCode(503))
        b.enqueue(MockResponse().setBody(oneStation))
        val result = client(listOf(host(a), host(b))).search(null, null, null, 0, 10)
        assertEquals("u1", result.single().id)
    }

    @Test fun failsOverWhenMirrorIsUnreachable() = runBlocking {
        val dead = host(a)
        a.shutdown()
        b.enqueue(MockResponse().setResponseCode(404)) // discovery -> fallback list
        b.enqueue(MockResponse().setBody(oneStation))
        val result = client(listOf(dead, host(b)), discovery = "http://${host(b)}/x")
            .search(null, null, null, 0, 10)
        assertEquals("u1", result.single().id)
    }

    @Test fun doesNotFailOverOnClientError() = runBlocking {
        a.enqueue(MockResponse().setResponseCode(404)) // discovery
        a.enqueue(MockResponse().setResponseCode(400))
        var thrown = false
        try { client(listOf(host(a), host(b))).search(null, null, null, 0, 10) } catch (e: retrofit2.HttpException) { thrown = true }
        assertTrue(thrown)
        assertEquals(0, b.requestCount)
    }

    @Test fun usesDiscoveredServersWhenAvailable() = runBlocking {
        a.enqueue(MockResponse().setBody("""[{"name":"${host(b)}"}]"""))
        b.enqueue(MockResponse().setBody(oneStation))
        val result = client(listOf("unused.invalid")).search(null, null, null, 0, 10)
        assertEquals("u1", result.single().id)
        assertEquals(1, b.requestCount)
    }

    @Test fun lookupByUuid() = runBlocking {
        a.enqueue(MockResponse().setResponseCode(404))
        a.enqueue(MockResponse().setBody(oneStation))
        val station = client(listOf(host(a))).byUuid("u1")
        a.takeRequest()
        assertEquals("/json/stations/byuuid/u1", a.takeRequest().path)
        assertEquals("https://res", station!!.streamUrl)
    }
}
