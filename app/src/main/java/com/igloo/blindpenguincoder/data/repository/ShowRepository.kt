package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.map
import com.igloo.blindpenguincoder.data.api.ShowApi
import com.igloo.blindpenguincoder.data.model.ShowEpisodePlaybackData
import com.igloo.blindpenguincoder.data.model.ShowEpisodeTechnicalDetailsData
import com.igloo.blindpenguincoder.data.model.ShowGenreWithCount
import com.igloo.blindpenguincoder.data.model.ShowGenresData
import com.igloo.blindpenguincoder.data.model.ShowsLibraryData
import com.igloo.blindpenguincoder.data.model.ShowsStatsData
import com.igloo.blindpenguincoder.data.model.SortOrder
import com.igloo.blindpenguincoder.data.model.WatchProgress

class ShowRepository(
    private val api: ShowApi,
) {
    /** One page of the browsable show library; the envelope's paging counts are in the result. */
    suspend fun showsLibrary(
        page: Long,
        perPage: Long,
        sort: SortOrder,
    ): ApiResult<ShowsLibraryData> =
        envelopeData("shows list") { api.showsLibrary(page, perPage, sort) }

    suspend fun showGenres(): ApiResult<List<ShowGenreWithCount>> =
        envelopeData<ShowGenresData>("show genres") { api.showGenres() }.map { it.genres }

    /** One page of one genre's shows; same result shape as [showsLibrary]. */
    suspend fun genreShows(
        genreId: Long,
        page: Long,
        perPage: Long,
        sort: SortOrder,
    ): ApiResult<ShowsLibraryData> =
        envelopeData("shows list") { api.genreShows(genreId, page, perPage, sort) }

    suspend fun showStats(): ApiResult<ShowsStatsData> =
        envelopeData("show stats") { api.showStats() }

    suspend fun episodePlayback(id: Long): ApiResult<ShowEpisodePlaybackData> =
        envelopeData("episode") { api.episodePlayback(id) }

    suspend fun episodeTechnicalDetails(id: Long): ApiResult<ShowEpisodeTechnicalDetailsData> =
        envelopeData("technical details") { api.episodeTechnicalDetails(id) }

    suspend fun episodeWatchProgress(id: Long): ApiResult<WatchProgress> =
        envelopeData("watch progress") { api.episodeWatchProgress(id) }
}
