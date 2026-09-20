package com.igloo.blindpenguincoder.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The movie section of a combined search (`MovieSearchSection`). */
@Serializable
data class MovieSearchSection(
    val results: List<MovieLibraryItem>,
    val total: Long,
)

/** The album section of a combined search (`AlbumSearchSection`). */
@Serializable
data class AlbumSearchSection(
    val results: List<SimpleAlbum>,
    val total: Long,
)

/** The musician section of a combined search (`MusicianSearchSection`). */
@Serializable
data class MusicianSearchSection(
    val results: List<SimpleMusician>,
    val total: Long,
)

/** The track section of a combined search (`TrackSearchSection`). */
@Serializable
data class TrackSearchSection(
    val results: List<TrackListItem>,
    val total: Long,
)

/**
 * Payload of `SearchAllEnvelope.data`. The spec's `shows` section is left to `ignoreUnknownKeys`
 * until the client has TV show models.
 */
@Serializable
data class SearchAllData(
    val query: String,
    val movies: MovieSearchSection,
    val albums: AlbumSearchSection,
    val musicians: MusicianSearchSection,
    val tracks: TrackSearchSection,
)

/** Payload of `SearchMoviesEnvelope.data`. */
@Serializable
data class MovieSearchData(
    val query: String,
    val results: List<MovieLibraryItem>,
    val total: Long,
    val page: Long,
    @SerialName("per_page") val perPage: Long,
    @SerialName("total_pages") val totalPages: Long,
)

/** Payload of `SearchAlbumsEnvelope.data`. */
@Serializable
data class AlbumSearchData(
    val query: String,
    val results: List<SimpleAlbum>,
    val total: Long,
    val page: Long,
    @SerialName("per_page") val perPage: Long,
    @SerialName("total_pages") val totalPages: Long,
)

/** Payload of `SearchMusiciansEnvelope.data`. */
@Serializable
data class MusicianSearchData(
    val query: String,
    val results: List<SimpleMusician>,
    val total: Long,
    val page: Long,
    @SerialName("per_page") val perPage: Long,
    @SerialName("total_pages") val totalPages: Long,
)

/** Payload of `SearchTracksEnvelope.data`. */
@Serializable
data class TrackSearchData(
    val query: String,
    val results: List<TrackListItem>,
    val total: Long,
    val page: Long,
    @SerialName("per_page") val perPage: Long,
    @SerialName("total_pages") val totalPages: Long,
)
