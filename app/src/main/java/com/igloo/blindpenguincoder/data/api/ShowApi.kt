package com.igloo.blindpenguincoder.data.api

import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import com.igloo.blindpenguincoder.data.model.SortOrder
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse

class ShowApi(
    private val client: HttpClient,
    private val serverUrl: ServerUrlProvider,
) {
    /**
     * One page of the browsable show library, name-ordered server-side. The same paging
     * contract as the movie routes: [perPage] is clamped at [MAX_LIBRARY_PER_PAGE] and [sort] is
     * only a direction.
     */
    suspend fun showsLibrary(page: Long, perPage: Long, sort: SortOrder): HttpResponse =
        client.pagedList("${serverUrl.require().apiBaseUrl}/shows/library", page, perPage, sort)

    /** All show genres with per-genre counts, tag-ordered server-side. */
    suspend fun showGenres(): HttpResponse =
        client.get("${serverUrl.require().apiBaseUrl}/shows/genres")

    /** One page of one genre's shows; same paging contract as [showsLibrary]. */
    suspend fun genreShows(genreId: Long, page: Long, perPage: Long, sort: SortOrder): HttpResponse =
        client.pagedList(
            "${serverUrl.require().apiBaseUrl}/shows/genres/$genreId/shows",
            page,
            perPage,
            sort,
        )

    /** Library-wide counts; today just the total number of shows. */
    suspend fun showStats(): HttpResponse =
        client.get("${serverUrl.require().apiBaseUrl}/shows/stats")
}
