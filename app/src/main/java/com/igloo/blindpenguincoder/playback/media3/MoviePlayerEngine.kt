package com.igloo.blindpenguincoder.playback.media3

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.igloo.blindpenguincoder.playback.model.MoviePlayerEvent
import kotlinx.coroutines.flow.SharedFlow

/**
 * The movie player's engine boundary, cut exactly at ExoPlayer so everything above it —
 * reducer, chrome, focus, Back — runs against a fake in tests without touching a decoder, and
 * so another engine (VLC was named as a possible future) can slot in behind the same seam.
 * All members are main-thread only; [events] replays what a late collector missed.
 *
 * Positions and durations cross this seam in absolute movie seconds in every mode. An HLS
 * session's media may begin mid-movie; the engine owns that offset so the reducer, seek bar,
 * chapters, resume, and progress saves never learn HLS exists.
 */
interface MoviePlayerEngine {
    val events: SharedFlow<MoviePlayerEvent>

    /** Engine-authoritative type ordinal; null keeps the container's default audio. */
    val currentAudioTypeIndex: Int?

    /**
     * The user's chosen subtitle type ordinal — engine-authoritative, null means subtitles are
     * off. Product policy: the choice may name an image-based stream the current HLS source
     * cannot serve; then nothing renders (never a substituted track), but the choice is kept,
     * persisted, and restored when a source that can serve it returns.
     */
    val currentSubtitleTypeIndex: Int?

    /** Draws the engine-owned video and subtitle surfaces behind Igloo's Compose chrome. */
    @Composable
    fun VideoSurface(modifier: Modifier = Modifier)

    /**
     * The resume decision was made: prepare the stream from [startPositionSec], or from the
     * beginning when null, and honor [initialPlayWhenReady]. Called exactly once per engine.
     *
     * [rewindOnResume] marks [startPositionSec] as a genuine resume point — a position carried
     * in from the backend rather than one this screen visit just watched past. Only then may a
     * mode rewind before it; a replacement engine rebuilt at its own playhead must not, or
     * repeated background trips walk the movie backwards.
     */
    fun startPlayback(
        startPositionSec: Double?,
        initialPlayWhenReady: Boolean,
        rewindOnResume: Boolean,
    )

    fun play()
    fun pause()

    fun seekTo(seconds: Double)

    /** [com.igloo.blindpenguincoder.playback.model.TrackOption.id] from the audio menu. */
    fun selectAudioTrack(optionId: String)

    /** A subtitle menu id, or null for "None". */
    fun selectSubtitleTrack(optionId: String?)

    /**
     * A quality-menu id — a [com.igloo.blindpenguincoder.data.model.PlaybackMode] name. The
     * engine swaps its source in place (a new HLS session, or back to the direct stream) and
     * resumes from the current position.
     */
    fun selectPlaybackMode(optionId: String)

    /** Host lifecycle paused: silence playback and clear pending autoplay. */
    fun onHostPaused()

    /** Host lifecycle returned; playback stays paused for the user to resume. */
    fun onHostResumed()

    /** Idempotently tear down every player/session resource; the engine is unusable afterwards. */
    fun release()
}
