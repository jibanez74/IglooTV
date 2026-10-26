package com.igloo.blindpenguincoder.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One category of `GET /search`: the top rows the server chose and the category's full match
 * count, which is what a "See all" is worth. [T] is the same row the category's own paged route
 * returns, so one card mapping serves both.
 */
@Serializable
data class SearchSection<T>(
    val results: List<T>,
    val total: Long,
)

/** Payload of `SearchAllEnvelope.data`: every category at once, each with its own count. */
@Serializable
data class SearchAllData(
    val query: String,
    val movies: SearchSection<MovieLibraryItem>,
    val shows: SearchSection<ShowLibraryItem>,
    val albums: SearchSection<SimpleAlbum>,
    val musicians: SearchSection<SimpleMusician>,
    val tracks: SearchSection<TrackListItem>,
)

/**
 * One page of a category's search (`GET /search/{movies,shows,albums,musicians,tracks}`); the
 * same paging contract as the library lists, with the query echoed back.
 */
@Serializable
data class SearchPageData<T>(
    val query: String,
    val results: List<T>,
    val total: Long,
    val page: Long,
    @SerialName("per_page") val perPage: Long,
    @SerialName("total_pages") val totalPages: Long,
)
