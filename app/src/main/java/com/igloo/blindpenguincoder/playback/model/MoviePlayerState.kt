package com.igloo.blindpenguincoder.playback.model

/** The movie player's phases; the chrome renders exactly one of these at a time. */
enum class MoviePlayerPhase { AwaitingResume, Loading, Playing, Paused, Buffering, Ended, Error }

/** One row of an in-player track menu. [id] is engine-opaque ("group:track" for ExoPlayer). */
data class TrackOption(
    val id: String,
    val label: String,
    val selected: Boolean,
)

/**
 * The movie player's render-ready state, reduced purely from engine events so the whole machine
 * is testable without ExoPlayer. Two rules bind every transition, both inherited from the
 * trailer machine: an [MoviePlayerPhase.Error] is sticky — later events never downgrade the
 * first failure the user saw — and a known [durationSec] never shrinks back to zero, because a
 * seek bar that collapses mid-play reads as a crash. Unlike the trailer, a seek clamped to the
 * very end is allowed to land: Ended comes from the engine and exits with full progress, which
 * is exactly what finishing a movie means.
 */
data class MoviePlayerState(
    val phase: MoviePlayerPhase = MoviePlayerPhase.Loading,
    val ready: Boolean = false,
    val playWhenReady: Boolean = false,
    val currentTimeSec: Double = 0.0,
    val durationSec: Double = 0.0,
    val errorMessage: String? = null,
    val audioOptions: List<TrackOption> = emptyList(),
    val subtitleOptions: List<TrackOption> = emptyList(),
) {
    /** The resume decision was made; the engine is starting and the chrome shows loading. */
    fun onResumeChosen(): MoviePlayerState = when (phase) {
        MoviePlayerPhase.AwaitingResume -> copy(
            phase = MoviePlayerPhase.Loading,
            playWhenReady = true,
        )
        else -> this
    }

    /**
     * STATE_READY: the surface has media. Paused, not Playing — the play/pause truth is
     * [onIsPlayingChanged], which fires in the same batch when playback actually starts.
     */
    fun onReady(durationSec: Double): MoviePlayerState = when (phase) {
        MoviePlayerPhase.Error, MoviePlayerPhase.Ended -> this
        MoviePlayerPhase.Loading, MoviePlayerPhase.Buffering -> copy(
            ready = true,
            phase = MoviePlayerPhase.Paused,
            durationSec = keptDuration(durationSec),
        )
        else -> copy(ready = true, durationSec = keptDuration(durationSec))
    }

    fun onBuffering(): MoviePlayerState = when (phase) {
        MoviePlayerPhase.Error, MoviePlayerPhase.Ended, MoviePlayerPhase.AwaitingResume -> this
        else -> copy(phase = MoviePlayerPhase.Buffering)
    }

    /**
     * ExoPlayer reports isPlaying=false for pause, buffering, and ended alike; the specific
     * events carry those, so false only ever downgrades an actual Playing phase.
     */
    fun onIsPlayingChanged(playing: Boolean): MoviePlayerState = when {
        phase == MoviePlayerPhase.Error || phase == MoviePlayerPhase.Ended -> this
        playing -> copy(phase = MoviePlayerPhase.Playing)
        phase == MoviePlayerPhase.Playing -> copy(phase = MoviePlayerPhase.Paused)
        else -> this
    }

    fun onPlayWhenReadyChanged(playWhenReady: Boolean): MoviePlayerState = when (phase) {
        MoviePlayerPhase.Error, MoviePlayerPhase.Ended -> this
        else -> copy(playWhenReady = playWhenReady)
    }

    fun onEnded(): MoviePlayerState = when (phase) {
        MoviePlayerPhase.Error -> this
        else -> copy(
            phase = MoviePlayerPhase.Ended,
            playWhenReady = false,
            currentTimeSec = durationSec.takeIf { it > 0.0 } ?: currentTimeSec,
        )
    }

    /** The first failure wins; later messages never replace what the user already saw. */
    fun onError(message: String): MoviePlayerState = when (phase) {
        MoviePlayerPhase.Error -> this
        else -> copy(phase = MoviePlayerPhase.Error, errorMessage = message)
    }

    fun onTime(currentSec: Double, durationSec: Double): MoviePlayerState = when (phase) {
        MoviePlayerPhase.Error, MoviePlayerPhase.Ended -> this
        else -> copy(
            currentTimeSec = currentSec.coerceAtLeast(0.0),
            durationSec = keptDuration(durationSec),
        )
    }

    fun onTracksChanged(
        audio: List<TrackOption>,
        subtitles: List<TrackOption>,
    ): MoviePlayerState = copy(audioOptions = audio, subtitleOptions = subtitles)

    /** Where a relative seek lands, clamped into the playable range. */
    fun seekTarget(deltaSec: Double): Double = clampToPlayable(currentTimeSec + deltaSec)

    /** An optimistic seek: the bar moves under a held key without waiting for the next tick. */
    fun onSeekApplied(targetSec: Double): MoviePlayerState = when (phase) {
        MoviePlayerPhase.Error, MoviePlayerPhase.Ended -> this
        else -> copy(currentTimeSec = clampToPlayable(targetSec))
    }

    private fun clampToPlayable(seconds: Double): Double = when {
        durationSec > 0.0 -> seconds.coerceIn(0.0, durationSec)
        else -> seconds.coerceAtLeast(0.0)
    }

    private fun keptDuration(incoming: Double): Double =
        if (incoming > 0.0) incoming else durationSec
}

/** What the engine reports upward; each maps 1:1 onto a [MoviePlayerState] transition. */
sealed interface MoviePlayerEvent {
    data class Ready(val durationSec: Double) : MoviePlayerEvent
    data object Buffering : MoviePlayerEvent
    data class IsPlayingChanged(val playing: Boolean) : MoviePlayerEvent
    data class PlayWhenReadyChanged(val playWhenReady: Boolean) : MoviePlayerEvent
    data object Ended : MoviePlayerEvent

    /** [unauthorized] rides along so the screen can distinguish a revoked session's message. */
    data class Error(val message: String, val unauthorized: Boolean = false) : MoviePlayerEvent
    data class Time(val currentSec: Double, val durationSec: Double) : MoviePlayerEvent
    data class TracksChanged(
        val audio: List<TrackOption>,
        val subtitles: List<TrackOption>,
    ) : MoviePlayerEvent
}

fun MoviePlayerState.onEvent(event: MoviePlayerEvent): MoviePlayerState = when (event) {
    is MoviePlayerEvent.Ready -> onReady(event.durationSec)
    is MoviePlayerEvent.Buffering -> onBuffering()
    is MoviePlayerEvent.IsPlayingChanged -> onIsPlayingChanged(event.playing)
    is MoviePlayerEvent.PlayWhenReadyChanged -> onPlayWhenReadyChanged(event.playWhenReady)
    is MoviePlayerEvent.Ended -> onEnded()
    is MoviePlayerEvent.Error -> onError(event.message)
    is MoviePlayerEvent.Time -> onTime(event.currentSec, event.durationSec)
    is MoviePlayerEvent.TracksChanged -> onTracksChanged(event.audio, event.subtitles)
}
