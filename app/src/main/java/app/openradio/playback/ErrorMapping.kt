package app.openradio.playback

import androidx.media3.common.PlaybackException
import app.openradio.domain.FailureKind

fun classifyPlaybackError(errorCode: Int): FailureKind = when (errorCode) {
    PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> FailureKind.BehindLive
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_TIMEOUT,
    -> FailureKind.Network
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
    PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
    -> FailureKind.StaleUrl
    else -> FailureKind.Other
}
