package com.igloo.blindpenguincoder.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class LatestMovie(
    val id: Long,
    val title: String,
    @SerialName("poster_path") val posterPath: SqlNullString,
    val year: SqlNullInt64,
)

/** LatestMovie plus certification, used in library listings. */
@Serializable
data class MovieLibraryItem(
    val id: Long,
    val title: String,
    @SerialName("poster_path") val posterPath: SqlNullString,
    val year: SqlNullInt64,
    val certification: SqlNullString? = null,
)

/** Payload of `MoviesLibraryEnvelope.data`. */
@Serializable
data class MoviesLibraryData(
    val movies: List<MovieLibraryItem>,
    val total: Long,
    val page: Long,
    @SerialName("per_page") val perPage: Long,
    @SerialName("total_pages") val totalPages: Long,
    val sort: SortOrder,
)

/** Payload of `LatestMoviesEnvelope.data`. */
@Serializable
data class LatestMoviesData(
    val movies: List<LatestMovie>,
)

/** LatestMovie plus watch progress; items of `GET /movies/continue-watching`. */
@Serializable
data class ContinueWatchingMovie(
    val id: Long,
    val title: String,
    @SerialName("poster_path") val posterPath: SqlNullString,
    val year: SqlNullInt64,
    @SerialName("progress_sec") val progressSec: Double,
    @SerialName("duration_sec") val durationSec: Double,
)

/** Payload of `ContinueWatchingMoviesEnvelope.data`. */
@Serializable
data class ContinueWatchingMoviesData(
    val movies: List<ContinueWatchingMovie>,
)

/** Payload of `MoviesStatsEnvelope.data`. */
@Serializable
data class MoviesStatsData(
    @SerialName("total_movies") val totalMovies: Long,
)

/** Payload of `MovieLikeStatusEnvelope.data`. */
@Serializable
data class MovieLikeStatusData(
    @SerialName("is_liked") val isLiked: Boolean,
)

/** Payload of `MovieLikeToggleEnvelope.data`. */
@Serializable
data class MovieLikeToggleData(
    @SerialName("movie_id") val movieId: Long,
    @SerialName("is_liked") val isLiked: Boolean,
)

@Serializable
data class MovieGenreWithCount(
    @SerialName("genre_id") val genreId: Long,
    @SerialName("genre_tag") val genreTag: String,
    @SerialName("movie_count") val movieCount: Long,
)

/** Payload of `MovieGenresEnvelope.data`. */
@Serializable
data class MovieGenresData(
    val genres: List<MovieGenreWithCount>,
)

@Serializable
data class Movie(
    val id: Long,
    val title: String,
    @SerialName("file_path") val filePath: String,
    @SerialName("file_name") val fileName: String,
    val size: Long,
    val container: String,
    @SerialName("mime_type") val mimeType: String,
    val adult: Boolean,
    @SerialName("tmdb_id") val tmdbId: SqlNullInt64? = null,
    @SerialName("imdb_id") val imdbId: SqlNullString? = null,
    @SerialName("poster_path") val posterPath: SqlNullString? = null,
    @SerialName("backdrop_path") val backdropPath: SqlNullString? = null,
    val language: SqlNullString? = null,
    val year: SqlNullInt64? = null,
    @SerialName("release_date") val releaseDate: SqlNullString? = null,
    val overview: SqlNullString? = null,
    @SerialName("tag_line") val tagLine: SqlNullString? = null,
    val certification: SqlNullString? = null,
    @SerialName("critic_rating") val criticRating: SqlNullFloat64? = null,
    @SerialName("audience_rating") val audienceRating: SqlNullFloat64? = null,
    val revenue: SqlNullFloat64? = null,
    val budget: SqlNullFloat64? = null,
    @SerialName("run_time") val runTime: SqlNullInt64? = null,
    val duration: SqlNullFloat64? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

/** Payload of `MovieDetailsEnvelope.data`. The spec leaves list item shapes untyped. */
@Serializable
data class MovieDetailsData(
    val movie: Movie,
    val cast: List<JsonObject>,
    val crew: List<JsonObject>,
    val genres: List<JsonObject>,
    @SerialName("production_companies") val productionCompanies: List<JsonObject>,
    @SerialName("extra_videos") val extraVideos: List<JsonObject>,
)

/** Payload of `MovieTechnicalDetailsEnvelope.data`. Stream shapes are untyped in the spec. */
@Serializable
data class MovieTechnicalDetailsData(
    val movie: JsonObject,
    @SerialName("video_streams") val videoStreams: List<JsonObject>,
    @SerialName("audio_streams") val audioStreams: List<JsonObject>,
    val subtitles: List<JsonObject>,
    val chapters: List<JsonObject>,
)

@Serializable
data class UpdateMovieWatchProgressRequest(
    @SerialName("progress_sec") val progressSec: Double,
    @SerialName("duration_sec") val durationSec: Double,
)

@Serializable
data class SetMovieWatchedRequest(
    val watched: Boolean,
)

/** Payload of `MovieWatchProgressEnvelope.data`. */
@Serializable
data class MovieWatchProgress(
    @SerialName("progress_sec") val progressSec: Double?,
    @SerialName("duration_sec") val durationSec: Double?,
    val watched: Boolean,
    @SerialName("updated_at") val updatedAt: String?,
)

/** Payload of `MovieWatchProgressUpdateEnvelope.data`. */
@Serializable
data class MovieWatchProgressUpdateData(
    val watched: Boolean,
)

/** Payload of `ClearedEnvelope.data`. */
@Serializable
data class ClearedData(
    val cleared: Boolean,
)

/** Payload of `MovieWatchedEnvelope.data`. */
@Serializable
data class MovieWatchedData(
    @SerialName("movie_id") val movieId: Long,
    val watched: Boolean,
)

@Serializable
data class IdentifyMovieRequest(
    @SerialName("tmdb_id") val tmdbId: Int,
)

@Serializable
data class UpdateMovieMetadataRequest(
    val title: String? = null,
    val year: Long? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    val overview: String? = null,
    @SerialName("tag_line") val tagLine: String? = null,
    val certification: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val language: String? = null,
)

@Serializable
data class DeleteMovieRequest(
    @SerialName("delete_file") val deleteFile: Boolean = false,
)
