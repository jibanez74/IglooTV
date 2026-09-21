package com.igloo.blindpenguincoder.playback.model

import com.igloo.blindpenguincoder.data.model.PlaybackMode

/**
 * Igloo's normative seven playback modes, in the enum's descending-quality order. Both the
 * pre-play settings dialog and the in-player quality menu expose this same complete contract.
 */
fun availablePlaybackModes(): List<PlaybackMode> = PlaybackMode.entries.toList()
