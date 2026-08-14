package com.igloo.blindpenguincoder.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Payload of `TmdbStatusEnvelope.data` and `SpotifyStatusEnvelope.data`. */
@Serializable
data class ProviderStatusData(
    val available: Boolean,
)

@Serializable
data class TheaterMovie(
    val id: Int,
    val title: String,
    val overview: String,
    @SerialName("release_date") val releaseDate: String,
    @SerialName("poster_path") val posterPath: String,
    @SerialName("backdrop_path") val backdropPath: String,
    @SerialName("vote_average") val voteAverage: Double,
)

/** Payload of `TmdbTheaterMoviesEnvelope.data`. */
@Serializable
data class TheaterMoviesData(
    val movies: List<TheaterMovie>,
)

@Serializable
data class TmdbSearchResult(
    @SerialName("tmdb_id") val tmdbId: Int,
    val title: String,
    @SerialName("release_date") val releaseDate: String,
    val overview: String,
    @SerialName("poster_path") val posterPath: String,
    @SerialName("already_in_library") val alreadyInLibrary: Boolean,
    @SerialName("library_movie_id") val libraryMovieId: Long? = null,
)

/** Payload of `TmdbSearchResultsEnvelope.data`. */
@Serializable
data class TmdbSearchResultsData(
    val results: List<TmdbSearchResult>,
)

/** Payload of `TmdbMovieEnvelope.data`. The TMDB movie shape is untyped in the spec. */
@Serializable
data class TmdbMovieData(
    val movie: JsonObject,
)

@Serializable
data class TmdbSearchMoviesRequest(
    val title: String? = null,
    val year: Int? = null,
    @SerialName("tmdb_id") val tmdbId: Int? = null,
)

@Serializable
data class SpotifyAlbumSearchResult(
    @SerialName("spotify_id") val spotifyId: String,
    val title: String,
    @SerialName("artist_names") val artistNames: List<String>,
    @SerialName("release_date") val releaseDate: String,
    @SerialName("album_type") val albumType: String,
    @SerialName("total_tracks") val totalTracks: Int,
    @SerialName("cover_url") val coverUrl: String,
    @SerialName("spotify_url") val spotifyUrl: String,
    @SerialName("already_in_library") val alreadyInLibrary: Boolean,
    @SerialName("library_album_id") val libraryAlbumId: Long? = null,
)

/** Payload of `SpotifyAlbumSearchResultsEnvelope.data`. */
@Serializable
data class SpotifyAlbumSearchResultsData(
    val results: List<SpotifyAlbumSearchResult>,
)

@Serializable
data class SpotifyTrackSearchResult(
    @SerialName("spotify_id") val spotifyId: String,
    val title: String,
    @SerialName("artist_names") val artistNames: List<String>,
    @SerialName("album_name") val albumName: String,
    @SerialName("release_date") val releaseDate: String,
    @SerialName("duration_ms") val durationMs: Int,
    @SerialName("cover_url") val coverUrl: String,
    @SerialName("spotify_url") val spotifyUrl: String,
)

/** Payload of `SpotifyTrackSearchResultsEnvelope.data`. */
@Serializable
data class SpotifyTrackSearchResultsData(
    val results: List<SpotifyTrackSearchResult>,
)

@Serializable
data class SpotifySearchAlbumsRequest(
    val title: String,
)

@Serializable
data class SpotifySearchTracksRequest(
    val title: String,
)
