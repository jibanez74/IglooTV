package com.igloo.blindpenguincoder.playback.hls

import com.igloo.blindpenguincoder.data.model.PlaybackMode

/** Resolves server truth without letting an unknown future profile erase the user's request. */
internal fun effectivePlaybackMode(
    requestedMode: PlaybackMode,
    effectiveProfileId: String,
): PlaybackMode = PlaybackMode.entries
    .firstOrNull { it.hlsProfileId == effectiveProfileId }
    ?: requestedMode
