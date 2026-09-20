package com.igloo.blindpenguincoder.playback.queue

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.data.model.ShuffleTracksData
import com.igloo.blindpenguincoder.data.model.TracksData

/**
 * The two reads an endless queue refills from. `MusicRepository` is the production
 * implementation; the seam exists so the refill rules are testable on the JVM without HTTP.
 */
interface MusicQueueFetcher {
    suspend fun tracks(limit: Long, offset: Long): ApiResult<TracksData>
    suspend fun shuffleTracks(limit: Long, exclude: List<Long>): ApiResult<ShuffleTracksData>
}
