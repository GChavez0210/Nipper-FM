package app.openradio.ui

import app.openradio.data.remote.StationDirectory
import app.openradio.domain.Country
import app.openradio.domain.Station
import app.openradio.ui.explore.ExploreController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private fun st(id: String) = Station(id, "Station $id", "http://x/$id")

private class FakeDirectory : StationDirectory {
    data class Call(val query: String?, val cc: String?, val tag: String?, val offset: Int)
    val calls = mutableListOf<Call>()
    var handler: suspend (Call) -> List<Station> = { emptyList() }
    override suspend fun search(query: String?, countryCode: String?, tag: String?, offset: Int, limit: Int): List<Station> {
        val c = Call(query, countryCode, tag, offset)
        calls += c
        return handler(c)
    }
    override suspend fun byUuid(uuid: String): Station? = null
    override suspend fun countries(): List<Country> = emptyList()
    override suspend fun tags(): List<String> = emptyList()
    override suspend fun registerClick(uuid: String) {}
}

@OptIn(ExperimentalCoroutinesApi::class)
class ExploreControllerTest {

    @Test fun debouncesTypingIntoOneRequest() = runTest {
        val dir = FakeDirectory().apply { handler = { listOf(st(it.query ?: "trend")) } }
        val c = ExploreController(backgroundScope, dir, debounceMs = 350, pageSize = 30)
        c.setQuery("j"); advanceTimeBy(100)
        c.setQuery("ja"); advanceTimeBy(100)
        c.setQuery("jaz"); advanceTimeBy(400)

        assertEquals(listOf("jaz"), dir.calls.map { it.query })
        assertEquals(listOf("jaz"), c.state.value.items.map { it.id })
    }

    @Test fun staleResponseNeverReplacesNewerResults() = runTest {
        val slow = CompletableDeferred<List<Station>>()
        val dir = FakeDirectory().apply {
            handler = { call ->
                if (call.query == "old") withContext(NonCancellable) { slow.await() } else listOf(st("new"))
            }
        }
        val c = ExploreController(backgroundScope, dir, debounceMs = 0)
        c.setQuery("old"); settle()
        c.setQuery("newer"); settle()
        assertEquals(listOf("new"), c.state.value.items.map { it.id })

        slow.complete(listOf(st("stale")))
        settle()
        assertEquals(listOf("new"), c.state.value.items.map { it.id })
        assertFalse(c.state.value.loading)
    }

    @Test fun trendingWhenNoQuery() = runTest {
        val dir = FakeDirectory()
        val c = ExploreController(backgroundScope, dir, debounceMs = 0)
        c.reload(); settle()
        assertTrue(c.state.value.isTrending)
        assertNull(dir.calls.single().query)
        c.setQuery("kexp"); settle()
        assertFalse(c.state.value.isTrending)
    }

    @Test fun filtersArePassedToDirectory() = runTest {
        val dir = FakeDirectory()
        val c = ExploreController(backgroundScope, dir, debounceMs = 0)
        c.setCountry(Country("France", "FR", 10)); settle()
        c.setTag("jazz"); settle()
        val last = dir.calls.last()
        assertEquals("FR", last.cc)
        assertEquals("jazz", last.tag)
    }

    @Test fun paginatesUntilShortPage() = runTest {
        val dir = FakeDirectory().apply {
            handler = { c -> if (c.offset == 0) (1..3).map { st("p$it") } else listOf(st("p4")) }
        }
        val c = ExploreController(backgroundScope, dir, debounceMs = 0, pageSize = 3)
        c.reload(); settle()
        assertFalse(c.state.value.endReached)
        c.loadMore(); settle()
        assertEquals(4, c.state.value.items.size)
        assertTrue(c.state.value.endReached)
        c.loadMore(); settle()
        assertEquals(2, dir.calls.size) // no request once the end is reached
    }

    @Test fun reloadWhilePagingDropsLateMorePage() = runTest {
        val gate = CompletableDeferred<List<Station>>()
        val dir = FakeDirectory().apply {
            handler = { c ->
                when {
                    c.query == "b" -> listOf(st("b1"))
                    c.offset > 0 -> withContext(NonCancellable) { gate.await() }
                    else -> (1..2).map { st("a$it") }
                }
            }
        }
        val c = ExploreController(backgroundScope, dir, debounceMs = 0, pageSize = 2)
        c.reload(); settle()
        c.loadMore(); settle()
        c.setQuery("b"); settle()
        gate.complete(listOf(st("late")))
        settle()
        assertEquals(listOf("b1"), c.state.value.items.map { it.id })
    }

    @Test fun errorThenRetry() = runTest {
        var fail = true
        val dir = FakeDirectory().apply { handler = { if (fail) throw IOException("down") else listOf(st("ok")) } }
        val c = ExploreController(backgroundScope, dir, debounceMs = 0)
        c.reload(); settle()
        assertNotNull(c.state.value.error)
        assertFalse(c.state.value.loading)

        fail = false
        c.retry(); settle()
        assertNull(c.state.value.error)
        assertEquals(listOf("ok"), c.state.value.items.map { it.id })
    }

    @Test fun emptyResultIsEmptyState() = runTest {
        val c = ExploreController(backgroundScope, FakeDirectory(), debounceMs = 0)
        c.setQuery("zzz"); settle()
        assertTrue(c.state.value.isEmpty)
    }
}

private suspend fun TestScope.settle() { advanceTimeBy(120_000); runCurrent() }
