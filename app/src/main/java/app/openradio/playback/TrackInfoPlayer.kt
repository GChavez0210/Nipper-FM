package app.openradio.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import app.openradio.domain.TrackInfo

/**
 * The player the media session exposes. It forwards everything to ExoPlayer but rewrites the
 * current metadata so the phone, notification, lock screen, and Android Auto show the artist and
 * song as separate fields, plus cover art when one was found.
 *
 * Only the player-level metadata changes. Each media item keeps the station's own metadata, so
 * the station logo and name still identify the station everywhere else.
 */
@OptIn(UnstableApi::class)
class TrackInfoPlayer(player: Player) : ForwardingSimpleBasePlayer(player) {

    /** What the service learned about the track whose raw in-stream title is [rawTitle]. */
    data class Enrichment(val rawTitle: String, val track: TrackInfo, val artworkUri: Uri? = null)

    var enrichment: Enrichment? = null
        private set

    fun updateEnrichment(value: Enrichment?) {
        if (value == enrichment) return
        enrichment = value
        invalidateState()
    }

    override fun getState(): State {
        val state = super.getState()
        val e = enrichment ?: return state
        val p = player
        if (!p.isCommandAvailable(Player.COMMAND_GET_TIMELINE) || !p.isCommandAvailable(Player.COMMAND_GET_METADATA)) {
            return state
        }
        val base = p.mediaMetadata
        // A stale enrichment (the stream moved on to the next song) is ignored until the service updates it.
        if (base.title?.toString()?.trim() != e.rawTitle) return state
        val enriched = enrich(base, e)
        val tracks = if (p.isCommandAvailable(Player.COMMAND_GET_TRACKS)) p.currentTracks else Tracks.EMPTY
        return state.buildUpon().setPlaylist(p.currentTimeline, tracks, enriched).build()
    }

    private fun enrich(base: MediaMetadata, e: Enrichment): MediaMetadata = base.buildUpon()
        .setTitle(e.track.title)
        .setArtist(e.track.artist ?: base.artist)
        .apply {
            // Artwork embedded in the stream (ID3 APIC) wins over a looked-up cover.
            if (e.artworkUri != null && base.artworkData == null) setArtworkUri(e.artworkUri)
        }
        .build()
}
