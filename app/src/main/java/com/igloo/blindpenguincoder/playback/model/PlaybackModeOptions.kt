package com.igloo.blindpenguincoder.playback.model

import com.igloo.blindpenguincoder.data.model.PlaybackMode
import com.igloo.blindpenguincoder.data.model.transcodeHeight

/**
 * The playback modes worth offering for one movie, in the enum's descending-quality order.
 * Transcode profiles taller than the source are dropped — the server never upscales, so they
 * would only waste bitrate on identical pixels — with `720p_3mbps` kept as the floor when the
 * source is smaller than every profile (web parity). An unknown height offers everything.
 *
 * [includeDirect] exists for the in-player quality menu, which omits Direct when the selected
 * audio track provably cannot play; the pre-play dialog always lists it and lets the gate
 * explain the refusal, because offers may be filtered but a user's choice is never overridden.
 */
fun availablePlaybackModes(videoHeight: Int?, includeDirect: Boolean = true): List<PlaybackMode> {
    val transcodes = PlaybackMode.entries.filter { it.transcodeHeight != null }
    val fitting = when (videoHeight) {
        null -> transcodes
        else -> transcodes.filter { it.transcodeHeight!! <= videoHeight }
    }.ifEmpty { listOf(PlaybackMode.P720Mbps3) }
    return buildList {
        if (includeDirect) add(PlaybackMode.Direct)
        add(PlaybackMode.Remux)
        addAll(fitting)
    }
}
