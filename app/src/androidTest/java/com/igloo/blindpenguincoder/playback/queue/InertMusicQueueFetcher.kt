package com.igloo.blindpenguincoder.playback.queue

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.data.repository.MusicQueueFetcher
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.data.model.ShuffleTracksData
import com.igloo.blindpenguincoder.data.model.TracksData

/**
 * The fetcher the shell suites host: a finite queue never asks it anything, and an endless one
 * that does gets a failure, which the controller reports as a notice and otherwise ignores.
 */
object InertMusicQueueFetcher : MusicQueueFetcher {
    override suspend fun tracks(limit: Long, offset: Long): ApiResult<TracksData> =
        ApiResult.Failure(AppError.Unexpected("inert fetcher"))

    override suspend fun shuffleTracks(limit: Long, exclude: List<Long>): ApiResult<ShuffleTracksData> =
        ApiResult.Failure(AppError.Unexpected("inert fetcher"))
}
