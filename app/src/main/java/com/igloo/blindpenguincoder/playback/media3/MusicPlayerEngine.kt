package com.igloo.blindpenguincoder.playback.media3

import com.igloo.blindpenguincoder.playback.model.MusicPlayTrack
import com.igloo.blindpenguincoder.playback.model.MusicPlayerEvent
import kotlinx.coroutines.flow.SharedFlow

/**
 * The music player's engine boundary, the movie seam's audio sibling: cut exactly at ExoPlayer
 * so the reducer, chrome, focus, and Back all run against a fake in tests without touching a
 * decoder. There is no surface member — the screen draws the album art itself — so the seam is
 * pure Kotlin. All members are main-thread only; [events] replays what a late collector missed.
 *
 * The engine owns the whole queue as one ExoPlayer playlist, so auto-advance and skip
 * semantics live below this seam; the screen only learns "the queue moved" through
 * [MusicPlayerEvent.TrackChanged]. Positions and durations cross in plain track seconds.
 */
interface MusicPlayerEngine {
    val events: SharedFlow<MusicPlayerEvent>

    /** Prepare the queue at [startTrackIndex]/[startPositionSec]. Called once per engine. */
    fun startPlayback(
        startTrackIndex: Int,
        startPositionSec: Double,
        initialPlayWhenReady: Boolean,
    )

    /**
     * An endless queue's refill: [tracks] join the end of the playlist and never move what is
     * already there, so every index the screen holds stays valid. Ignored after a terminal
     * failure — the replacement engine is seeded with the grown queue instead.
     */
    fun appendTracks(tracks: List<MusicPlayTrack>)

    fun play()
    fun pause()

    /** Seek within the current track. */
    fun seekTo(seconds: Double)

    fun skipToNext()

    /** Standard previous semantics: restart the current track, or step back near its start. */
    fun skipToPrevious()

    /** Host lifecycle paused: silence playback and clear pending autoplay. */
    fun onHostPaused()

    /** Host lifecycle returned; playback stays paused for the user to resume. */
    fun onHostResumed()

    /** Idempotently tear down every player/session resource; the engine is unusable afterwards. */
    fun release()
}
