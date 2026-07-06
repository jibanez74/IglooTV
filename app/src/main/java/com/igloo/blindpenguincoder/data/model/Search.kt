package com.igloo.blindpenguincoder.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** One media-type section in a combined search result. Result shapes vary by section. */
@Serializable
data class SearchSection(
    val results: List<JsonObject>,
    val total: Long,
)

/** Payload of `SearchAllEnvelope.data`. */
@Serializable
data class SearchAllData(
    val query: String,
    val movies: SearchSection,
    val albums: SearchSection,
    val musicians: SearchSection,
    val tracks: SearchSection,
)

/** Payload of `PaginatedSearchEnvelope.data` (movies, albums, musicians, tracks searches). */
@Serializable
data class PaginatedSearchData(
    val query: String,
    val results: List<JsonObject>,
    val total: Long,
    val page: Long,
    @SerialName("per_page") val perPage: Long,
    @SerialName("total_pages") val totalPages: Long,
)
