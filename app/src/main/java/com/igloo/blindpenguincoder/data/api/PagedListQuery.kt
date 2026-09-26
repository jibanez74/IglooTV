package com.igloo.blindpenguincoder.data.api

import com.igloo.blindpenguincoder.data.model.SortOrder
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse

/**
 * The largest `per_page` a page-numbered movie or show list route serves. The backend clamps a
 * larger value to this rather than rejecting it (its default is 24), so the client asks for the
 * maximum and gets exactly it.
 */
const val MAX_LIBRARY_PER_PAGE = 48L

/** Every page-numbered library list speaks the same query dialect; spell it once. */
internal suspend fun HttpClient.pagedList(
    url: String,
    page: Long,
    perPage: Long,
    sort: SortOrder,
): HttpResponse =
    get(url) {
        parameter("page", page)
        parameter("per_page", perPage)
        parameter("sort", sort.wireName)
    }
