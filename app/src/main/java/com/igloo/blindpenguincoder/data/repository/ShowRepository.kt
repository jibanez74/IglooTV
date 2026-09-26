package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.network.safeApiCall
import com.igloo.blindpenguincoder.data.api.ShowApi
import com.igloo.blindpenguincoder.data.model.ApiEnvelope
import com.igloo.blindpenguincoder.data.model.ShowGenreWithCount
import com.igloo.blindpenguincoder.data.model.ShowGenresData
import com.igloo.blindpenguincoder.data.model.ShowsLibraryData
import com.igloo.blindpenguincoder.data.model.ShowsStatsData
import com.igloo.blindpenguincoder.data.model.SortOrder
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse

class ShowRepository(
    private val api: ShowApi,
) {
    /** One page of the browsable show library. The envelope's paging counts are part of the result. */
    suspend fun showsLibrary(
        page: Long,
        perPage: Long,
        sort: SortOrder,
    ): ApiResult<ShowsLibraryData> = showsListPage { api.showsLibrary(page, perPage, sort) }

    suspend fun showGenres(): ApiResult<List<ShowGenreWithCount>> = safeApiCall(
        request = { api.showGenres() },
        decode = { response ->
            response.body<ApiEnvelope<ShowGenresData>>().data?.genres
                ?: error("Missing genres in show genres response")
        },
    )

    /** One page of one genre's shows; same result shape as [showsLibrary]. */
    suspend fun genreShows(
        genreId: Long,
        page: Long,
        perPage: Long,
        sort: SortOrder,
    ): ApiResult<ShowsLibraryData> = showsListPage { api.genreShows(genreId, page, perPage, sort) }

    /** The library and genre lists both answer with the same paged envelope. */
    private suspend fun showsListPage(
        request: suspend () -> HttpResponse,
    ): ApiResult<ShowsLibraryData> = safeApiCall(
        request = request,
        decode = { response ->
            response.body<ApiEnvelope<ShowsLibraryData>>().data
                ?: error("Missing data in shows list response")
        },
    )

    suspend fun showStats(): ApiResult<ShowsStatsData> = safeApiCall(
        request = { api.showStats() },
        decode = { response ->
            response.body<ApiEnvelope<ShowsStatsData>>().data
                ?: error("Missing data in show stats response")
        },
    )
}
