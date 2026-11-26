package app.openradio.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarPlaylistsTest {
    private fun st(id: String) = Station(id, id, "http://x/$id")
    private fun fav(id: String, group: String? = null, pin: Int? = null) =
        SavedStation(st(id), isFavorite = true, pinnedOrder = pin, lastPlayedAt = null, groupName = group)

    private val favorites = listOf(fav("a", "Jazz"), fav("b", "News"), fav("c", "jazz"), fav("d"))
    private val presets = listOf(st("b"), st("d"))

    @Test fun wheelStartKeysRoundTrip() {
        listOf(WheelStart.LastStation, WheelStart.Presets, WheelStart.AllFavorites, WheelStart.Collection("Rock: 80s"))
            .forEach { assertEquals(it, WheelStart.fromKey(it.key)) }
        assertEquals(WheelStart.LastStation, WheelStart.fromKey(null))
        assertEquals(WheelStart.LastStation, WheelStart.fromKey("collection:"))
        assertEquals(WheelStart.LastStation, WheelStart.fromKey("bogus"))
    }

    @Test fun collectionsAreCaseInsensitive() {
        assertEquals(listOf("a", "c"), CarPlaylists.collection(favorites, "JAZZ").map { it.id })
        assertEquals(listOf("Jazz", "News"), CarPlaylists.collectionNames(favorites))
    }

    @Test fun collectionStartsAtTopWhenLastStationIsElsewhere() {
        val q = CarPlaylists.wheelQueue(WheelStart.Collection("Jazz"), listOf(st("b")), presets, favorites)!!
        assertEquals(listOf("a", "c"), q.stations.map { it.id })
        assertEquals(0, q.startIndex)
    }

    @Test fun collectionResumesAtLastStation() {
        val q = CarPlaylists.wheelQueue(WheelStart.Collection("Jazz"), listOf(st("c")), presets, favorites)!!
        assertEquals("c", q.current.id)
    }

    @Test fun emptyCollectionFallsBackToLastStation() {
        val q = CarPlaylists.wheelQueue(WheelStart.Collection("Gone"), listOf(st("d")), presets, favorites)!!
        assertEquals(listOf("b", "d"), q.stations.map { it.id })
        assertEquals("d", q.current.id)
    }

    @Test fun lastStationQueuesItsList() {
        val q = CarPlaylists.wheelQueue(WheelStart.LastStation, listOf(st("a"), st("z")), presets, favorites)!!
        assertEquals(listOf("a", "b", "c", "d"), q.stations.map { it.id })
        val alone = CarPlaylists.wheelQueue(WheelStart.LastStation, listOf(st("z")), presets, favorites)!!
        assertEquals(listOf("z"), alone.stations.map { it.id })
    }

    @Test fun nothingPlayedUsesPresetsOrNothing() {
        assertEquals(listOf("b", "d"), CarPlaylists.wheelQueue(WheelStart.LastStation, emptyList(), presets, favorites)!!.stations.map { it.id })
        assertNull(CarPlaylists.wheelQueue(WheelStart.AllFavorites, emptyList(), emptyList(), emptyList()))
    }
}
