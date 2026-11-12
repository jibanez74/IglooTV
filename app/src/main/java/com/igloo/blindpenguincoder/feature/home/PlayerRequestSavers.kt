package com.igloo.blindpenguincoder.feature.home

import com.igloo.blindpenguincoder.core.ui.jsonSaver
import com.igloo.blindpenguincoder.playback.model.MAX_QUEUE_TRACKS
import com.igloo.blindpenguincoder.playback.model.MusicPlayRequest
import com.igloo.blindpenguincoder.playback.model.VideoPlayRequest

/*
 * The shell's player overlays ride activity recreation through [jsonSaver]: a request that no
 * longer parses comes back as no overlay, since the screen underneath is restored anyway.
 */

/**
 * What the movie player overlay is playing (the screen itself restarts the engine and re-seeks
 * to its saved position).
 */
internal val VideoPlayRequestSaver = jsonSaver<VideoPlayRequest>()

/**
 * What the music player overlay is playing. The host keeps the request current as an endless
 * queue grows, so what is saved is the queue as it stands. A queue past [MAX_QUEUE_TRACKS] —
 * only a finite one can get there, an endless one stops refilling — is not saved at all: a
 * truncated queue would desynchronize the screen's saved position, and "come back with no
 * overlay" is the saver's honest degradation.
 */
internal val MusicPlayRequestSaver =
    jsonSaver<MusicPlayRequest> { it.tracks.size <= MAX_QUEUE_TRACKS }

internal val TrailerRequestSaver = jsonSaver<TrailerRequest>()
