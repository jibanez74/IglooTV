package com.igloo.blindpenguincoder.playback.hls

import com.igloo.blindpenguincoder.data.model.PlaybackMode
import com.igloo.blindpenguincoder.data.model.playbackModeOf

/** Resolves server truth without letting an unknown future profile erase the user's request. */
internal fun effectivePlaybackMode(
    requestedMode: PlaybackMode,
    effectiveProfileId: String,
): PlaybackMode = playbackModeOf(effectiveProfileId) ?: requestedMode
