package com.igloo.blindpenguincoder.playback.model

import com.igloo.blindpenguincoder.data.model.PlaybackMode

/** The movie player's phases; the chrome renders exactly one of these at a time. */
enum class VideoPlayerPhase { AwaitingResume, Loading, Playing, Paused, Buffering, Ended, Error }

/**
 * One row of an in-player track menu. [id] is engine-opaque ("group:track" for ExoPlayer).
 * A row with [enabled] false is rendered inert — focusable and announced, but not activatable —
 * for a stream the current source cannot serve (an image-based subtitle under HLS).
 */
data class TrackOption(
    val id: String,
    val label: String,
    val selected: Boolean,
    val enabled: Boolean = true,
)

/**
 * The movie player's render-ready state, reduced purely from engine events so the whole machine
 * is testable without ExoPlayer. Two rules bind every transition, both inherited from the
 * trailer machine: an [VideoPlayerPhase.Error] is sticky — later events never downgrade the
 * first failure the user saw — and a known [durationSec] never shrinks back to zero, because a
 * seek bar that collapses mid-play reads as a crash. Unlike the trailer, a seek clamped to the
 * very end is allowed to land: Ended comes from the engine and exits with full progress, which
 * is exactly what finishing a movie means.
 */
data class VideoPlayerState(
    val phase: VideoPlayerPhase = VideoPlayerPhase.Loading,
    val ready: Boolean = false,
    val playWhenReady: Boolean = false,
    val currentTimeSec: Double = 0.0,
    val durationSec: Double = 0.0,
    val errorMessage: String? = null,
    val audioOptions: List<TrackOption> = emptyList(),
    val subtitleOptions: List<TrackOption> = emptyList(),
    val qualityOptions: List<TrackOption> = emptyList(),
    /** Engine narration for long waits ("Waiting for the server…"); null = plain buffering. */
    val statusMessage: String? = null,
    /**
     * Why the engine declined the last quality choice, shown inside the open Quality menu.
     * A refusal is not a playback failure: the current source keeps playing untouched.
     */
    val modeRefusalMessage: String? = null,
) {
    /** The resume decision was made; the engine is starting and the chrome shows loading. */
    fun onResumeChosen(): VideoPlayerState = when (phase) {
        VideoPlayerPhase.AwaitingResume -> copy(
            phase = VideoPlayerPhase.Loading,
            playWhenReady = true,
        )
        else -> this
    }

    /**
     * STATE_READY: the surface has media. Paused, not Playing — the play/pause truth is
     * [onIsPlayingChanged], which fires in the same batch when playback actually starts.
     */
    fun onReady(durationSec: Double): VideoPlayerState = when (phase) {
        VideoPlayerPhase.Error, VideoPlayerPhase.Ended -> this
        VideoPlayerPhase.Loading, VideoPlayerPhase.Buffering -> copy(
            ready = true,
            phase = VideoPlayerPhase.Paused,
            durationSec = keptDuration(durationSec),
            statusMessage = null,
        )
        else -> copy(ready = true, durationSec = keptDuration(durationSec), statusMessage = null)
    }

    fun onBuffering(): VideoPlayerState = when (phase) {
        VideoPlayerPhase.Error, VideoPlayerPhase.Ended, VideoPlayerPhase.AwaitingResume -> this
        else -> copy(phase = VideoPlayerPhase.Buffering)
    }

    /**
     * ExoPlayer reports isPlaying=false for pause, buffering, and ended alike; the specific
     * events carry those, so false only ever downgrades an actual Playing phase.
     */
    fun onIsPlayingChanged(playing: Boolean): VideoPlayerState = when {
        phase == VideoPlayerPhase.Error || phase == VideoPlayerPhase.Ended -> this
        playing -> copy(phase = VideoPlayerPhase.Playing)
        phase == VideoPlayerPhase.Playing -> copy(phase = VideoPlayerPhase.Paused)
        else -> this
    }

    fun onPlayWhenReadyChanged(playWhenReady: Boolean): VideoPlayerState = when (phase) {
        VideoPlayerPhase.Error, VideoPlayerPhase.Ended -> this
        else -> copy(playWhenReady = playWhenReady)
    }

    fun onEnded(): VideoPlayerState = when (phase) {
        VideoPlayerPhase.Error -> this
        else -> copy(
            phase = VideoPlayerPhase.Ended,
            playWhenReady = false,
            currentTimeSec = durationSec.takeIf { it > 0.0 } ?: currentTimeSec,
        )
    }

    /** The first failure wins; later messages never replace what the user already saw. */
    fun onError(message: String): VideoPlayerState = when (phase) {
        VideoPlayerPhase.Error -> this
        else -> copy(phase = VideoPlayerPhase.Error, errorMessage = message)
    }

    fun onTime(currentSec: Double, durationSec: Double): VideoPlayerState = when (phase) {
        VideoPlayerPhase.Error, VideoPlayerPhase.Ended -> this
        else -> copy(
            currentTimeSec = currentSec.coerceAtLeast(0.0),
            durationSec = keptDuration(durationSec),
        )
    }

    fun onTracksChanged(
        audio: List<TrackOption>,
        subtitles: List<TrackOption>,
    ): VideoPlayerState = copy(audioOptions = audio, subtitleOptions = subtitles)

    /** An accepted switch re-emits the ladder, which is also what clears a stale refusal. */
    fun onQualityOptionsChanged(options: List<TrackOption>): VideoPlayerState =
        copy(qualityOptions = options, modeRefusalMessage = null)

    fun onModeRefused(message: String): VideoPlayerState = copy(modeRefusalMessage = message)

    /** Closing the Quality menu retires its refusal; the next visit starts clean. */
    fun onModeRefusalDismissed(): VideoPlayerState = when (modeRefusalMessage) {
        null -> this
        else -> copy(modeRefusalMessage = null)
    }

    /** Narration only matters while the user is still waiting for media. */
    fun onStatusMessage(message: String?): VideoPlayerState = when (phase) {
        VideoPlayerPhase.Error, VideoPlayerPhase.Ended -> this
        else -> copy(statusMessage = message)
    }

    /** Where a relative seek lands, clamped into the playable range. */
    fun seekTarget(deltaSec: Double): Double = clampToPlayable(currentTimeSec + deltaSec)

    /** An optimistic seek: the bar moves under a held key without waiting for the next tick. */
    fun onSeekApplied(targetSec: Double): VideoPlayerState = when (phase) {
        VideoPlayerPhase.Error, VideoPlayerPhase.Ended -> this
        else -> copy(currentTimeSec = clampToPlayable(targetSec))
    }

    private fun clampToPlayable(seconds: Double): Double =
        clampSecondsToDuration(seconds, durationSec)

    private fun keptDuration(incoming: Double): Double =
        nonShrinkingDuration(incoming, durationSec)
}

/** The existing polite live region prefers actionable wait detail over generic phase copy. */
internal fun videoPlayerAnnouncement(
    phase: VideoPlayerPhase,
    statusMessage: String?,
    title: String,
): String? = when (phase) {
    VideoPlayerPhase.Playing -> "Playing: $title"
    VideoPlayerPhase.Paused -> "Paused: $title"
    VideoPlayerPhase.Loading -> statusMessage ?: "Loading movie"
    VideoPlayerPhase.Buffering -> statusMessage ?: "Buffering"
    else -> null
}

/** What the engine reports upward; each maps 1:1 onto a [VideoPlayerState] transition. */
sealed interface VideoPlayerEvent {
    data class Ready(val durationSec: Double) : VideoPlayerEvent
    data object Buffering : VideoPlayerEvent
    data class IsPlayingChanged(val playing: Boolean) : VideoPlayerEvent
    data class PlayWhenReadyChanged(val playWhenReady: Boolean) : VideoPlayerEvent
    data object Ended : VideoPlayerEvent

    /** [unauthorized] rides along so the screen can distinguish a revoked session's message. */
    data class Error(val message: String, val unauthorized: Boolean = false) : VideoPlayerEvent
    data class Time(val currentSec: Double, val durationSec: Double) : VideoPlayerEvent
    data class TracksChanged(
        val audio: List<TrackOption>,
        val subtitles: List<TrackOption>,
    ) : VideoPlayerEvent
    /**
     * The quality ladder plus the accepted requested mode. During HLS preflight this is the
     * pending request so lifecycle reconstruction can preserve it; otherwise it is the last
     * successfully committed request. The selected row always reports the effective source.
     */
    data class QualityOptionsChanged(
        val options: List<TrackOption>,
        val requestedMode: PlaybackMode,
    ) : VideoPlayerEvent

    /** The engine declined a quality choice and kept playing; [message] says why. */
    data class ModeRefused(val message: String) : VideoPlayerEvent

    /** Narration for long engine waits; null clears it. */
    data class StatusMessage(val message: String?) : VideoPlayerEvent
}

fun VideoPlayerState.onEvent(event: VideoPlayerEvent): VideoPlayerState = when (event) {
    is VideoPlayerEvent.Ready -> onReady(event.durationSec)
    is VideoPlayerEvent.Buffering -> onBuffering()
    is VideoPlayerEvent.IsPlayingChanged -> onIsPlayingChanged(event.playing)
    is VideoPlayerEvent.PlayWhenReadyChanged -> onPlayWhenReadyChanged(event.playWhenReady)
    is VideoPlayerEvent.Ended -> onEnded()
    is VideoPlayerEvent.Error -> onError(event.message)
    is VideoPlayerEvent.Time -> onTime(event.currentSec, event.durationSec)
    is VideoPlayerEvent.TracksChanged -> onTracksChanged(event.audio, event.subtitles)
    is VideoPlayerEvent.QualityOptionsChanged -> onQualityOptionsChanged(event.options)
    is VideoPlayerEvent.ModeRefused -> onModeRefused(event.message)
    is VideoPlayerEvent.StatusMessage -> onStatusMessage(event.message)
}
