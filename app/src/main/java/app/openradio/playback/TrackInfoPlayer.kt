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

    /**
     * What the service learned about the current station's track. Keyed on the underlying player's
     * [mediaId], [rawTitle], and [rawArtist] so stale enrichment is ignored the instant the stream
     * moves to a new song or station. A null [track] explicitly clears blank or station-name titles
     * and any previous song's artist or embedded artwork.
     */
    data class Enrichment(
        val mediaId: String?,
        val rawTitle: String,
        val rawArtist: String,
        val track: TrackInfo?,
        val artworkUri: Uri? = null,
        val clearEmbeddedArtwork: Boolean = false,
    )

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
        val rawTitle = base.title?.toString()?.trim().orEmpty()
        val rawArtist = base.artist?.toString()?.trim().orEmpty()
        // A stale enrichment (the stream moved on to the next song or station) is ignored until updated.
        if (p.currentMediaItem?.mediaId != e.mediaId || rawTitle != e.rawTitle || rawArtist != e.rawArtist) {
            return state
        }
        val enriched = enrich(base, e)
        val tracks = if (p.isCommandAvailable(Player.COMMAND_GET_TRACKS)) p.currentTracks else Tracks.EMPTY
        return state.buildUpon().setPlaylist(p.currentTimeline, tracks, enriched).build()
    }

    private fun enrich(base: MediaMetadata, e: Enrichment): MediaMetadata {
        val builder = base.buildUpon()
            .setTitle(e.track?.title)
            .setArtist(e.track?.artist)
        if (e.track == null || e.clearEmbeddedArtwork) {
            builder.setArtworkData(null, null)
        }
        // Artwork embedded in the current track (ID3 APIC) wins over a looked-up cover.
        if (e.track != null && e.artworkUri != null && (e.clearEmbeddedArtwork || base.artworkData == null)) {
            builder.setArtworkUri(e.artworkUri)
        }
        return builder.build()
    }
}
