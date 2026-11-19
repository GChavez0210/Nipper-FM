package app.openradio.domain

/** What the UI shows for the current stream. Always rendered with text/icon, never colour alone. */
enum class PlaybackStatus { None, Live, Buffering, Reconnecting, Paused, Unavailable }

object PlaybackStatusRules {
    /**
     * @param recovery state published by the service's reconnect controller
     * @param hasError the player is in an error state
     */
    fun derive(
        hasItem: Boolean,
        playWhenReady: Boolean,
        isReady: Boolean,
        isBuffering: Boolean,
        hasError: Boolean,
        recovery: RecoveryPhase,
    ): PlaybackStatus = when {
        !hasItem -> PlaybackStatus.None
        recovery == RecoveryPhase.Failed -> PlaybackStatus.Unavailable
        recovery == RecoveryPhase.Reconnecting -> PlaybackStatus.Reconnecting
        hasError && playWhenReady -> PlaybackStatus.Unavailable
        !playWhenReady -> PlaybackStatus.Paused
        isBuffering || !isReady -> PlaybackStatus.Buffering
        else -> PlaybackStatus.Live
    }
}

enum class RecoveryPhase { Idle, Reconnecting, Failed }
