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

/**
 * Payload of `MovieDetailsEnvelope.data`. The spec leaves the five list item shapes untyped
 * (`additionalProperties: true`), so these models were pinned against live server responses;
 * `ignoreUnknownKeys` absorbs any fields the backend grows later.
 */
@Serializable
data class MovieDetailsData(
    val movie: Movie,
    val cast: List<MovieCastMember>,
    val crew: List<MovieCrewMember>,
    val genres: List<MovieGenre>,
    @SerialName("production_companies") val productionCompanies: List<MovieProductionCompany>,
    @SerialName("extra_videos") val extraVideos: List<MovieExtraVideo>,
)

@Serializable
data class MovieCastMember(
    val id: Long,
    @SerialName("movie_id") val movieId: Long,
    @SerialName("artist_id") val artistId: Long,
    val character: String,
    @SerialName("cast_order") val castOrder: Long,
    @SerialName("artist_name") val artistName: String,
    @SerialName("artist_profile") val artistProfile: SqlNullString? = null,
)

@Serializable
data class MovieCrewMember(
    val id: Long,
    @SerialName("movie_id") val movieId: Long,
    @SerialName("artist_id") val artistId: Long,
    val job: String,
    val department: String,
    @SerialName("artist_name") val artistName: String,
    @SerialName("artist_profile") val artistProfile: SqlNullString? = null,
)

@Serializable
data class MovieGenre(
    val id: Long,
    val tag: String,
)

@Serializable
data class MovieProductionCompany(
    val id: Long,
    val name: String,
    @SerialName("tmdb_id") val tmdbId: Long,
    val logo: SqlNullString? = null,
    val country: SqlNullString? = null,
)

@Serializable
data class MovieExtraVideo(
    val id: Long,
    val title: String,
    @SerialName("external_id") val externalId: SqlNullString? = null,
    val key: String,
    val type: String,
    val site: String,
    val official: Boolean,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

/**
 * Payload of `MovieTechnicalDetailsEnvelope.data`. The stream and chapter schemas are typed in
 * the spec (`VideoStream`, `AudioStream`, `Subtitle`, `Chapter`); only `movie` is left untyped.
 */
@Serializable
data class MovieTechnicalDetailsData(
    val movie: JsonObject,
    @SerialName("video_streams") val videoStreams: List<VideoStream>,
    @SerialName("audio_streams") val audioStreams: List<AudioStream>,
    val subtitles: List<Subtitle>,
    val chapters: List<Chapter>,
)

@Serializable
data class VideoStream(
    val id: Long,
    @SerialName("movie_id") val movieId: Long,
    @SerialName("stream_index") val streamIndex: Long,
    val codec: String,
    @SerialName("codec_profile") val codecProfile: SqlNullString? = null,
    @SerialName("codec_level") val codecLevel: SqlNullInt64? = null,
    @SerialName("bit_rate") val bitRate: Long,
    val width: Long,
    val height: Long,
    @SerialName("coded_width") val codedWidth: SqlNullInt64? = null,
    @SerialName("coded_height") val codedHeight: SqlNullInt64? = null,
    @SerialName("aspect_ratio") val aspectRatio: SqlNullString? = null,
    @SerialName("frame_rate") val frameRate: Double,
    @SerialName("avg_frame_rate") val avgFrameRate: SqlNullString? = null,
    @SerialName("bit_depth") val bitDepth: SqlNullInt64? = null,
    @SerialName("pixel_format") val pixelFormat: SqlNullString? = null,
    @SerialName("color_range") val colorRange: SqlNullString? = null,
    @SerialName("color_space") val colorSpace: SqlNullString? = null,
    @SerialName("color_primaries") val colorPrimaries: SqlNullString? = null,
    @SerialName("color_transfer") val colorTransfer: SqlNullString? = null,
    @SerialName("field_order") val fieldOrder: SqlNullString? = null,
    val rotation: SqlNullInt64? = null,
    val language: SqlNullString? = null,
    val title: SqlNullString? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class AudioStream(
    val id: Long,
    @SerialName("movie_id") val movieId: Long,
    @SerialName("stream_index") val streamIndex: Long,
    val codec: String,
    @SerialName("codec_profile") val codecProfile: SqlNullString? = null,
    @SerialName("bit_rate") val bitRate: Long,
    @SerialName("sample_rate") val sampleRate: SqlNullInt64? = null,
    val channels: Long,
    @SerialName("channel_layout") val channelLayout: SqlNullString? = null,
    val language: SqlNullString? = null,
    val title: SqlNullString? = null,
    @SerialName("is_default") val isDefault: Boolean,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class Subtitle(
    val id: Long,
    @SerialName("movie_id") val movieId: Long,
    @SerialName("stream_index") val streamIndex: Long,
    val codec: String,
    val language: SqlNullString? = null,
    val title: SqlNullString? = null,
    @SerialName("is_forced") val isForced: Boolean,
    @SerialName("is_default") val isDefault: Boolean,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

/**
 * `movie_id` is deliberately not modelled: the spec declares it a `SqlNullInt64` object but the
 * server sends a plain number, and decoding the whole technical-details payload fails on that
 * mismatch. Nothing needs it — the caller already knows which movie it asked about — so the
 * field is left to `ignoreUnknownKeys`, which also makes this tolerant of either shape.
 */
@Serializable
data class Chapter(
    val id: Long,
    val title: String,
    /** Seconds from the start of the movie. */
    @SerialName("start_time") val startTime: Long,
    val thumb: SqlNullString? = null,
)

@Serializable
data class UpdateMovieWatchProgressRequest(
    @SerialName("progress_sec") val progressSec: Double,
    @SerialName("duration_sec") val durationSec: Double,
    // The backend rejects out-of-order saves: the session id is a UUID minted once per
    // playback, and the sequence counts up from 1 across that session's saves.
    @SerialName("save_session_id") val saveSessionId: String,
    @SerialName("save_sequence") val saveSequence: Long,
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
