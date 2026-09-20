package com.igloo.blindpenguincoder.playback.model

/** The music player's phases; the chrome renders exactly one of these at a time. */
enum class MusicPlayerPhase { Loading, Playing, Paused, Buffering, Ended, Error }

/**
 * The music player's render-ready state, reduced purely from engine events so the whole machine
 * is testable without ExoPlayer — the movie machine minus its movie-only concerns (no resume
 * prompt, no track menus, no quality ladder) plus the one queue concept: [currentTrackIndex].
 * The movie's two binding rules carry over: an [MusicPlayerPhase.Error] is sticky — later
 * events never downgrade the first failure the user saw — and a known [durationSec] never
 * shrinks back to zero *within a track*; [onTrackChanged] is the one deliberate reset, because
 * the next track's timeline is genuinely a new one. [playWhenReady] starts true: the launching
 * press is itself the play intent, so there is no paused first frame to click through.
 */
data class MusicPlayerState(
    val phase: MusicPlayerPhase = MusicPlayerPhase.Loading,
    val ready: Boolean = false,
    val playWhenReady: Boolean = true,
    val currentTrackIndex: Int = 0,
    val currentTimeSec: Double = 0.0,
    val durationSec: Double = 0.0,
    val errorMessage: String? = null,
) {
    /**
     * STATE_READY: the stream has media. Paused, not Playing — the play/pause truth is
     * [onIsPlayingChanged], which fires in the same batch when playback actually starts.
     */
    fun onReady(durationSec: Double): MusicPlayerState = when (phase) {
        MusicPlayerPhase.Error, MusicPlayerPhase.Ended -> this
        MusicPlayerPhase.Loading, MusicPlayerPhase.Buffering -> copy(
            ready = true,
            phase = MusicPlayerPhase.Paused,
            durationSec = keptDuration(durationSec),
        )
        else -> copy(ready = true, durationSec = keptDuration(durationSec))
    }

    fun onBuffering(): MusicPlayerState = when (phase) {
        MusicPlayerPhase.Error, MusicPlayerPhase.Ended -> this
        else -> copy(phase = MusicPlayerPhase.Buffering)
    }

    /**
     * ExoPlayer reports isPlaying=false for pause, buffering, and ended alike; the specific
     * events carry those, so false only ever downgrades an actual Playing phase.
     */
    fun onIsPlayingChanged(playing: Boolean): MusicPlayerState = when {
        phase == MusicPlayerPhase.Error || phase == MusicPlayerPhase.Ended -> this
        playing -> copy(phase = MusicPlayerPhase.Playing)
        phase == MusicPlayerPhase.Playing -> copy(phase = MusicPlayerPhase.Paused)
        else -> this
    }

    fun onPlayWhenReadyChanged(playWhenReady: Boolean): MusicPlayerState = when (phase) {
        MusicPlayerPhase.Error, MusicPlayerPhase.Ended -> this
        else -> copy(playWhenReady = playWhenReady)
    }

    /**
     * The queue advanced (auto-advance, a skip, or a wrap to a chosen index): a new timeline,
     * so time and duration reset — the one licensed exception to duration-never-shrinks. The
     * engine's duration is often still unknown at the transition; it sends the wire duration
     * then, and the real one arrives with the next Ready or Time.
     *
     * A transition that names the index already current is not an advance: setting the
     * playlist is itself an item transition, so the queue's own start index arrives before a
     * frame plays. Resetting there would discard the restored playhead that a Retry and a
     * background return both resume from. Such a report only refreshes the duration.
     */
    fun onTrackChanged(index: Int, durationSec: Double): MusicPlayerState = when {
        phase == MusicPlayerPhase.Error || phase == MusicPlayerPhase.Ended -> this
        index.coerceAtLeast(0) == currentTrackIndex -> copy(durationSec = keptDuration(durationSec))
        else -> copy(
            currentTrackIndex = index.coerceAtLeast(0),
            currentTimeSec = 0.0,
            durationSec = durationSec.coerceAtLeast(0.0),
        )
    }

    /** The whole queue finished — the engine only reports Ended past the last queue item. */
    fun onEnded(): MusicPlayerState = when (phase) {
        MusicPlayerPhase.Error -> this
        else -> copy(
            phase = MusicPlayerPhase.Ended,
            playWhenReady = false,
            currentTimeSec = durationSec.takeIf { it > 0.0 } ?: currentTimeSec,
        )
    }

    /** The first failure wins; later messages never replace what the user already saw. */
    fun onError(message: String): MusicPlayerState = when (phase) {
        MusicPlayerPhase.Error -> this
        else -> copy(phase = MusicPlayerPhase.Error, errorMessage = message)
    }

    fun onTime(currentSec: Double, durationSec: Double): MusicPlayerState = when (phase) {
        MusicPlayerPhase.Error, MusicPlayerPhase.Ended -> this
        else -> copy(
            currentTimeSec = currentSec.coerceAtLeast(0.0),
            durationSec = keptDuration(durationSec),
        )
    }

    /** Where a relative seek lands, clamped into the current track's playable range. */
    fun seekTarget(deltaSec: Double): Double = clampToPlayable(currentTimeSec + deltaSec)

    /** An optimistic seek: the bar moves under a held key without waiting for the next tick. */
    fun onSeekApplied(targetSec: Double): MusicPlayerState = when (phase) {
        MusicPlayerPhase.Error, MusicPlayerPhase.Ended -> this
        else -> copy(currentTimeSec = clampToPlayable(targetSec))
    }

    private fun clampToPlayable(seconds: Double): Double =
        clampSecondsToDuration(seconds, durationSec)

    private fun keptDuration(incoming: Double): Double =
        nonShrinkingDuration(incoming, durationSec)
}

/**
 * The polite live region's one sentence. The title rides in it so an auto-advance — same
 * Playing phase, new track — still produces a new announcement.
 */
internal fun musicPlayerAnnouncement(
    phase: MusicPlayerPhase,
    trackTitle: String,
): String? = when (phase) {
    MusicPlayerPhase.Playing -> "Playing: $trackTitle"
    MusicPlayerPhase.Paused -> "Paused: $trackTitle"
    MusicPlayerPhase.Loading -> "Loading"
    MusicPlayerPhase.Buffering -> "Buffering"
    else -> null
}

/** What the engine reports upward; each maps 1:1 onto a [MusicPlayerState] transition. */
sealed interface MusicPlayerEvent {
    data class Ready(val durationSec: Double) : MusicPlayerEvent
    data object Buffering : MusicPlayerEvent
    data class IsPlayingChanged(val playing: Boolean) : MusicPlayerEvent
    data class PlayWhenReadyChanged(val playWhenReady: Boolean) : MusicPlayerEvent
    data class TrackChanged(val index: Int, val durationSec: Double) : MusicPlayerEvent
    data object Ended : MusicPlayerEvent

    /** [unauthorized] rides along so the screen can distinguish a revoked session's message. */
    data class Error(val message: String, val unauthorized: Boolean = false) : MusicPlayerEvent
    data class Time(val currentSec: Double, val durationSec: Double) : MusicPlayerEvent
}

fun MusicPlayerState.onEvent(event: MusicPlayerEvent): MusicPlayerState = when (event) {
    is MusicPlayerEvent.Ready -> onReady(event.durationSec)
    is MusicPlayerEvent.Buffering -> onBuffering()
    is MusicPlayerEvent.IsPlayingChanged -> onIsPlayingChanged(event.playing)
    is MusicPlayerEvent.PlayWhenReadyChanged -> onPlayWhenReadyChanged(event.playWhenReady)
    is MusicPlayerEvent.TrackChanged -> onTrackChanged(event.index, event.durationSec)
    is MusicPlayerEvent.Ended -> onEnded()
    is MusicPlayerEvent.Error -> onError(event.message)
    is MusicPlayerEvent.Time -> onTime(event.currentSec, event.durationSec)
}
