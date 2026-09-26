package com.igloo.blindpenguincoder.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One entry of a paged show listing. The route also sends `certification`, left to
 * `ignoreUnknownKeys` — the grid card renders name and premiere year only.
 */
@Serializable
data class ShowLibraryItem(
    val id: Long,
    val name: String,
    @SerialName("poster_path") val posterPath: SqlNullString,
    @SerialName("premiere_year") val premiereYear: SqlNullInt64,
)

/**
 * Payload of `ShowsLibraryEnvelope.data`. The envelope echoes `page`, `per_page`, and `sort`
 * too; the client tracks its own cursor, so those are left to `ignoreUnknownKeys`.
 */
@Serializable
data class ShowsLibraryData(
    val shows: List<ShowLibraryItem>,
    val total: Long,
    @SerialName("total_pages") val totalPages: Long,
)

@Serializable
data class ShowGenreWithCount(
    @SerialName("genre_id") val genreId: Long,
    @SerialName("genre_tag") val genreTag: String,
    @SerialName("show_count") val showCount: Long,
)

/** Payload of `ShowGenresEnvelope.data`. */
@Serializable
data class ShowGenresData(
    val genres: List<ShowGenreWithCount>,
)

/** Payload of `ShowsStatsEnvelope.data`. */
@Serializable
data class ShowsStatsData(
    @SerialName("total_shows") val totalShows: Long,
)
