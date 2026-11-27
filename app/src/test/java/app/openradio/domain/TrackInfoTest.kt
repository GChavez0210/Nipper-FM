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

    @Test fun splitsEnDashAndNonAsciiItalianCharacters() {
        val enDash = TrackInfoParser.parse("Fabrizio De André – La città vecchia", null, "Radio Italia")!!
        assertEquals(TrackInfo("La città vecchia", "Fabrizio De André"), enDash)
        assertEquals("Fabrizio De André - La città vecchia", enDash.display)
        assertTrue(enDash.isSong)

        val accented = TrackInfoParser.parse("Niccolò Fabi - È non è", null, "Radio Italia")!!
        assertEquals(TrackInfo("È non è", "Niccolò Fabi"), accented)
    }

    @Test fun splitsOnFirstSeparatorOnly() {
        val t = TrackInfoParser.parse("A - B - C", null, null)!!
        assertEquals("A", t.artist)
        assertEquals("B - C", t.title)
        assertEquals("A - B - C", t.display)
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
        assertNull(TrackInfoParser.parse("", null, "Radio X"))
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

    @Test fun matcherRejectsSubstringFalsePositives() {
        // Real iTunes result for non-existent "Sting - Rain" is "Sting - After the Rain Has Fallen".
        assertNull(
            ArtworkMatcher.pick(
                TrackInfo("Rain", "Sting"),
                listOf(
                    ArtworkCandidate("Sting", "After the Rain Has Fallen", "u1"),
                    ArtworkCandidate("Casting Crowns", "Brain Damage", "u2"),
                ),
            ),
        )
        assertNull(
            ArtworkMatcher.pick(
                TrackInfo("Love", "Queen"),
                listOf(ArtworkCandidate("Queen", "Somebody to Love", "u")),
            ),
        )
        assertNull(
            ArtworkMatcher.pick(
                TrackInfo("Try", "Pink"),
                listOf(ArtworkCandidate("Pink Floyd", "Try", "u")),
            ),
        )
        assertNull(
            ArtworkMatcher.pick(
                TrackInfo("One", "Drake"),
                listOf(ArtworkCandidate("Nick Drake", "One", "u")),
            ),
        )
    }

    @Test fun matcherFoldsDiacriticsAndStripsEditions() {
        val accented = ArtworkCandidate("Fabrizio De André", "La Città Vecchia", "u1")
        assertEquals(accented, ArtworkMatcher.pick(TrackInfo("La citta vecchia", "Fabrizio De Andre"), listOf(accented)))

        val remaster = ArtworkCandidate("Eagles", "Hotel California - 2013 Remaster", "u2")
        assertEquals(remaster, ArtworkMatcher.pick(TrackInfo("Hotel California", "Eagles"), listOf(remaster)))

        val beatles = ArtworkCandidate("The Beatles", "Yesterday", "u3")
        assertEquals(beatles, ArtworkMatcher.pick(TrackInfo("Yesterday", "Beatles"), listOf(beatles)))
    }

    @Test fun matcherToleratesFeaturingAndPrefersExactArtist() {
        val track = TrackInfo("Song", "Main feat. Guest")
        val c = ArtworkCandidate("Main", "Song [feat. Guest]", "u")
        assertEquals(c, ArtworkMatcher.pick(track, listOf(c)))

        val collab = ArtworkCandidate("Queen & David Bowie", "Under Pressure", "u1")
        val exact = ArtworkCandidate("Queen", "Under Pressure", "u2")
        assertEquals(exact, ArtworkMatcher.pick(TrackInfo("Under Pressure", "Queen"), listOf(collab, exact)))
        assertEquals(collab, ArtworkMatcher.pick(TrackInfo("Under Pressure", "Queen"), listOf(collab)))
    }

    @Test fun upscalesItunesArtwork() {
        assertEquals(
            "https://is1.mzstatic.com/image/thumb/x/600x600bb.jpg",
            ArtworkMatcher.upscale("https://is1.mzstatic.com/image/thumb/x/100x100bb.jpg"),
        )
        assertTrue(ArtworkMatcher.upscale("https://example.com/cover.jpg") == "https://example.com/cover.jpg")
    }
}
