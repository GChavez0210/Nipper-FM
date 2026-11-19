package app.openradio.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class PresetOrderingTest {
    private fun saved(id: String, pin: Int? = null) =
        SavedStation(Station(id, id, "http://x/$id"), isFavorite = true, pinnedOrder = pin, lastPlayedAt = null)

    @Test fun pinAppendsInOrder() {
        assertEquals(listOf("a", "b"), PresetOrdering.pin(listOf("a"), "b"))
    }

    @Test fun pinIgnoresDuplicates() {
        assertEquals(listOf("a", "b"), PresetOrdering.pin(listOf("a", "b"), "a"))
    }

    @Test fun pinStopsAtSix() {
        val six = listOf("1", "2", "3", "4", "5", "6")
        assertEquals(six, PresetOrdering.pin(six, "7"))
    }

    @Test fun unpinKeepsRemainingOrder() {
        assertEquals(listOf("a", "c"), PresetOrdering.unpin(listOf("a", "b", "c"), "b"))
    }

    @Test fun moveUpAndDown() {
        val pins = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), PresetOrdering.move(pins, 1, 0))
        assertEquals(listOf("b", "c", "a"), PresetOrdering.move(pins, 0, 2))
    }

    @Test fun moveClampsAndIgnoresBadIndexes() {
        val pins = listOf("a", "b", "c")
        assertEquals(listOf("b", "c", "a"), PresetOrdering.move(pins, 0, 99))
        assertEquals(pins, PresetOrdering.move(pins, 5, 0))
        assertEquals(pins, PresetOrdering.move(pins, 1, 1))
    }

    @Test fun retainFavoritesDropsRemovedStations() {
        assertEquals(listOf("a", "c"), PresetOrdering.retainFavorites(listOf("a", "b", "c"), setOf("a", "c", "z")))
    }

    @Test fun carShowsPinsInPinOrder() {
        val favs = listOf(saved("x"), saved("b", 1), saved("a", 0), saved("y"))
        assertEquals(listOf("a", "b"), PresetOrdering.carPresets(favs).map { it.id })
    }

    @Test fun carFallsBackToFavoritesWhenNothingPinned() {
        val favs = listOf(saved("x"), saved("y"))
        assertEquals(listOf("x", "y"), PresetOrdering.carPresets(favs).map { it.id })
    }

    @Test fun carCapsPinnedAtSix() {
        val favs = (0..7).map { saved("s$it", it) }
        assertEquals(6, PresetOrdering.carPresets(favs).size)
    }
}
