package com.igloo.blindpenguincoder.data.api

import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse

/** The five paged search routes, named by their path segment. */
enum class SearchRoute(val segment: String) {
    Movies("movies"),
    Shows("shows"),
    Albums("albums"),
    Musicians("musicians"),
    Tracks("tracks"),
}

class SearchApi(
    private val client: HttpClient,
    private val serverUrl: ServerUrlProvider,
) {
    /**
     * Every category at once: the top eight rows of each with its full match count, no paging.
     * The server tokenises [query] itself; a blank one answers with empty sections, not an error.
     */
    suspend fun searchAll(query: String): HttpResponse =
        client.get("${serverUrl.require().apiBaseUrl}/search") {
            parameter("q", query)
        }

    /** One page of one category; `per_page` is clamped to [MAX_LIBRARY_PER_PAGE] server-side. */
    suspend fun search(route: SearchRoute, query: String, page: Long, perPage: Long): HttpResponse =
        client.pagedList(
            "${serverUrl.require().apiBaseUrl}/search/${route.segment}",
            page,
            perPage,
            query = query,
        )
}
