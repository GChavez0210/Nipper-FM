package app.openradio.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackInfoTest {
    @Test fun splitsIcyStreamTitle() {
        assertEquals(TrackInfo("Volare", "Domenico Modugno"), TrackInfoParser.parse("Domenico Modugno - Volare", null, "Radio X"))
    }

    @Test fun splitsOnFirstSeparatorOnly() {
        val t = TrackInfoParser.parse("Artist - Song - Remix", null, null)!!
        assertEquals("Artist", t.artist)
        assertEquals("Song - Remix", t.title)
        assertEquals("Artist - Song - Remix", t.display)
    }

    @Test fun keepsSeparateId3Fields() {
        val t = TrackInfoParser.parse("Song - Live", "Band", null)!!
        assertEquals(TrackInfo("Song - Live", "Band"), t)
    }

    @Test fun titleWithoutArtistIsNotASong() {
        val t = TrackInfoParser.parse("Morning Show", null, "Radio X")!!
        assertNull(t.artist)
        assertFalse(t.isSong)
        assertEquals("Morning Show", t.display)
    }

    @Test fun ignoresStationNameAndBlank() {
        assertNull(TrackInfoParser.parse("radio x", null, "Radio X"))
        assertNull(TrackInfoParser.parse("  ", null, "Radio X"))
        assertNull(TrackInfoParser.parse(null, "Artist", "Radio X"))
    }

    @Test fun leadingSeparatorIsNotAnArtist() {
        val t = TrackInfoParser.parse(" - Song", null, null)!!
        assertNull(t.artist)
        assertEquals("Song", t.title)
    }

    @Test fun matcherRequiresArtistAndTitle() {
        val track = TrackInfo("Volare (Remastered)", "Domenico Modugno")
        val wrong = ArtworkCandidate("Gipsy Kings", "Volare", "http://a/1/100x100bb.jpg")
        val right = ArtworkCandidate("Domenico Modugno", "Nel blu dipinto di blu (Volare)", "http://a/2/100x100bb.jpg")
        val exact = ArtworkCandidate("Domenico Modugno", "Volare", "http://a/3/100x100bb.jpg")
        assertNull(ArtworkMatcher.pick(track, listOf(wrong)))
        assertEquals(exact, ArtworkMatcher.pick(track, listOf(wrong, exact, right)))
        assertNull(ArtworkMatcher.pick(TrackInfo("Volare"), listOf(exact)))
    }

    @Test fun matcherToleratesFeaturing() {
        val track = TrackInfo("Song", "Main feat. Guest")
        val c = ArtworkCandidate("Main", "Song [feat. Guest]", "u")
        assertEquals(c, ArtworkMatcher.pick(track, listOf(c)))
    }

    @Test fun upscalesItunesArtwork() {
        assertEquals(
            "https://is1.mzstatic.com/image/thumb/x/600x600bb.jpg",
            ArtworkMatcher.upscale("https://is1.mzstatic.com/image/thumb/x/100x100bb.jpg"),
        )
        assertTrue(ArtworkMatcher.upscale("https://example.com/cover.jpg") == "https://example.com/cover.jpg")
    }
}
