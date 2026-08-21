package com.igloo.blindpenguincoder.playback.model

import com.igloo.blindpenguincoder.data.model.PlaybackMode

/**
 * The web's `STREAM_MODES` labels, except the first two say "Original quality" outright —
 * the user asked for the original-quality option to be unmistakable on a TV screen. Shared by
 * the Playback Settings dialog and the playback gate's error messages.
 */
internal fun playbackModeLabel(mode: PlaybackMode): String = when (mode) {
    PlaybackMode.Direct -> "Original quality — plays the file as-is"
    PlaybackMode.Remux -> "Original quality — audio adjusted"
    PlaybackMode.P2160Mbps16 -> "4K — highest quality"
    PlaybackMode.P1080Mbps8 -> "1080p — best quality"
    PlaybackMode.P1080Mbps6 -> "1080p — high quality"
    PlaybackMode.P1080Mbps4 -> "1080p — balanced"
    PlaybackMode.P720Mbps3 -> "720p — lower bandwidth"
}
