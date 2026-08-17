package com.igloo.blindpenguincoder.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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

/**
 * One TMDB movie as `GET /tmdb/movies/{id}` returns it — the record behind the in-theaters
 * detail screen (docs/design-system.md section 11.4.2). Not library content: nothing here is
 * stored server-side, so these are TMDB's own shapes rather than the `SqlNull*` wrappers the
 * library models carry.
 *
 * Only the fields that screen renders are modelled; `ignoreUnknownKeys` drops the rest of a
 * large payload. Every list arrives as `null` when TMDB has nothing to send (the live response
 * sends `genre_ids: null`), and the spec's non-nullable strings do come back null in practice
 * for missing artwork, so both are declared nullable with defaults.
 */
@Serializable
data class TmdbMovie(
    val id: Int,
    val title: String,
    val overview: String = "",
    @SerialName("release_date") val releaseDate: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    /** Minutes. TMDB sends 0 for an unknown runtime. */
    val runtime: Long = 0,
    val status: String = "",
    val tagline: String = "",
    val budget: Long = 0,
    val revenue: Long = 0,
    @SerialName("original_language") val originalLanguage: String = "",
    @SerialName("production_companies") val productionCompanies: List<TmdbProductionCompany>? = null,
    val genres: List<TmdbGenre>? = null,
    val credits: TmdbCredits = TmdbCredits(),
    val videos: TmdbVideos = TmdbVideos(),
    @SerialName("release_dates") val releaseDates: TmdbReleaseDates = TmdbReleaseDates(),
)

@Serializable
data class TmdbGenre(
    val id: Int,
    val name: String,
)

@Serializable
data class TmdbProductionCompany(
    val id: Int,
    val name: String,
)

@Serializable
data class TmdbCredits(
    val cast: List<TmdbCastMember>? = null,
    val crew: List<TmdbCrewMember>? = null,
)

@Serializable
data class TmdbCastMember(
    val id: Int,
    val name: String,
    val character: String = "",
    @SerialName("profile_path") val profilePath: String? = null,
    /** TMDB's billing order; the rail shows the top of it. */
    val order: Int = 0,
)

@Serializable
data class TmdbCrewMember(
    val id: Int,
    val name: String,
    val job: String = "",
    val department: String = "",
)

@Serializable
data class TmdbVideos(
    val results: List<TmdbVideo>? = null,
)

@Serializable
data class TmdbVideo(
    val id: String,
    val key: String,
    val name: String,
    val site: String,
    val type: String,
)

/** Certifications per country; see the US-first rule in `TheaterMovieDetailsViewModel`. */
@Serializable
data class TmdbReleaseDates(
    val results: List<TmdbCountryReleaseDates>? = null,
)

@Serializable
data class TmdbCountryReleaseDates(
    @SerialName("iso_3166_1") val country: String,
    @SerialName("release_dates") val releaseDates: List<TmdbCertification>? = null,
)

@Serializable
data class TmdbCertification(
    val certification: String = "",
)

/** Payload of `TmdbMovieEnvelope.data`. */
@Serializable
data class TmdbMovieData(
    val movie: TmdbMovie,
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
