package com.igloo.blindpenguincoder.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TheaterMovie(
    val id: Int,
    val title: String,
    @SerialName("release_date") val releaseDate: String,
    @SerialName("poster_path") val posterPath: String,
    @SerialName("vote_average") val voteAverage: Double,
)

/** Payload of `TmdbTheaterMoviesEnvelope.data`. */
@Serializable
data class TheaterMoviesData(
    val movies: List<TheaterMovie>,
)

/**
 * One TMDB movie as `GET /tmdb/movies/{id}` returns it — the record behind the in-theaters
 * detail screen (docs/design-system.md section 11.4.2). Not library content: nothing here is
 * stored server-side, so these are TMDB's own shapes rather than the `SqlNull*` wrappers the
 * library models carry.
 *
 * Only the fields that screen renders are modelled; `ignoreUnknownKeys` drops the rest of a
 * large payload. The lists are genuinely nullable — the server marshals the record whole, so a
 * slice TMDB sent nothing for arrives as `null`, which is how the spec types them too. The
 * scalars are non-null with defaults: the server's own fields are plain Go values, so missing
 * artwork arrives as `""` rather than `null`, and `tmdbImageUrl` already reads a blank path as
 * no image.
 */
@Serializable
data class TmdbMovie(
    val id: Int,
    val title: String,
    val overview: String = "",
    @SerialName("release_date") val releaseDate: String = "",
    @SerialName("poster_path") val posterPath: String = "",
    @SerialName("backdrop_path") val backdropPath: String = "",
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
    val name: String,
)

@Serializable
data class TmdbProductionCompany(
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
    @SerialName("profile_path") val profilePath: String = "",
    /** TMDB's billing order; the rail shows the top of it. */
    val order: Int = 0,
)

@Serializable
data class TmdbCrewMember(
    val name: String,
    val job: String = "",
    val department: String = "",
)

@Serializable
data class TmdbVideos(
    val results: List<TmdbVideo>? = null,
)

/**
 * TMDB has no numeric id for a video, so none is modelled: the extras rail keys off the payload's
 * own order instead (`TheaterMovieDetailsViewModel.videoSources`).
 */
@Serializable
data class TmdbVideo(
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
