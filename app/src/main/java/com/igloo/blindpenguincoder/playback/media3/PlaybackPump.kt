// ForwardingPlayer is part of Media3's unstable surface; like its siblings, this file keeps the
// instability below the engine seam.
@file:androidx.annotation.OptIn(UnstableApi::class)

package com.igloo.blindpenguincoder.playback.media3

import android.os.Handler
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * The position pump every engine runs while it holds media: ExoPlayer has no position callback,
 * so the seek bar and the progress cadence are driven from here. One cadence for every player,
 * because the movie's progress arithmetic is calibrated against it.
 *
 * [onTick] returns false to stop the pump — the engine is released or has gone terminal — and
 * true to keep it running; whether a tick *emits* anything is the engine's own business.
 */
internal class PlaybackTicker(
    private val handler: Handler,
    private val onTick: () -> Boolean,
) {
    private val runnable = object : Runnable {
        override fun run() {
            if (!onTick()) return
            handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    fun start() {
        handler.post(runnable)
    }

    fun stop() {
        handler.removeCallbacks(runnable)
    }

    companion object {
        const val TICK_INTERVAL_MS = 500L
    }
}

/**
 * The MediaSession's view of a player, with transport routed back through the engine so
 * [PlaybackIntent] stays authoritative — an Assistant "play" while the host is paused must not
 * restart audio behind a covered screen — and `playWhenReady` reports that intent, the same
 * section 11.8 rule the on-screen toggle renders. Everything else passes through: queue and
 * seek commands carry no transport intent.
 *
 * Open so an engine can add its own timeline arithmetic on top (the movie's absolute-second
 * rebase); the four transport members below are the part no engine should restate.
 */
internal open class IntentRoutingPlayer(
    player: Player,
    private val intent: PlaybackIntent,
    private val onPlay: () -> Unit,
    private val onPause: () -> Unit,
) : ForwardingPlayer(player) {

    final override fun getPlayWhenReady(): Boolean = intent.shouldPlay

    final override fun play() {
        onPlay()
    }

    final override fun pause() {
        onPause()
    }

    final override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) onPlay() else onPause()
    }
}
