package com.igloo.blindpenguincoder.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LatestMovie(
    val id: Long,
    val title: String,
    @SerialName("poster_path") val posterPath: SqlNullString,
    val year: SqlNullInt64,
)

/**
 * One entry of a paged library listing. The route also sends `certification`, left to
 * `ignoreUnknownKeys` — the grid card renders title and year only.
 */
@Serializable
data class MovieLibraryItem(
    val id: Long,
    val title: String,
    @SerialName("poster_path") val posterPath: SqlNullString,
    val year: SqlNullInt64,
)

/**
 * Payload of `MoviesLibraryEnvelope.data`. The envelope echoes `page`, `per_page`, and `sort`
 * too; the client tracks its own cursor, so those are left to `ignoreUnknownKeys`.
 */
@Serializable
data class MoviesLibraryData(
    val movies: List<MovieLibraryItem>,
    val total: Long,
    @SerialName("total_pages") val totalPages: Long,
)

/** Payload of `LatestMoviesEnvelope.data`. */
@Serializable
data class LatestMoviesData(
    val movies: List<LatestMovie>,
)

/**
 * One entry of `GET /continue-watching`, a movie or a TV episode told apart by [kind]. The spec
 * models it as a discriminated `oneOf`; it is decoded flat here because a polymorphic decoder
 * would need an experimental serializer for no gain. On an episode [id] is the episode's,
 * [title], [posterPath] and [year] describe the show, and the three episode-only keys read here
 * are required by the contract; they are nullable only because the movie variant omits them.
 * `show_id` is left to `ignoreUnknownKeys`.
 */
@Serializable
data class ContinueWatchingItem(
    val kind: String,
    val id: Long,
    val title: String,
    @SerialName("poster_path") val posterPath: SqlNullString,
    val year: SqlNullInt64,
    @SerialName("progress_sec") val progressSec: Double,
    @SerialName("duration_sec") val durationSec: Double,
    @SerialName("season_number") val seasonNumber: Long? = null,
    @SerialName("episode_number") val episodeNumber: Long? = null,
    @SerialName("episode_name") val episodeName: String? = null,
) {
    val isMovie: Boolean get() = kind == KIND_MOVIE
    val isEpisode: Boolean get() = kind == KIND_EPISODE

    companion object {
        const val KIND_MOVIE = "movie"
        const val KIND_EPISODE = "episode"
    }
}

/** Payload of `ContinueWatchingEnvelope.data`. */
@Serializable
data class ContinueWatchingData(
    val items: List<ContinueWatchingItem>,
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
)

/** Payload of `MovieDetailsEnvelope.data`. */
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
    val character: String,
    @SerialName("cast_order") val castOrder: Long,
    @SerialName("artist_name") val artistName: String,
    @SerialName("artist_profile") val artistProfile: SqlNullString? = null,
)

@Serializable
data class MovieCrewMember(
    val id: Long,
    val job: String,
    val department: String,
    @SerialName("artist_name") val artistName: String,
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
)

@Serializable
data class MovieExtraVideo(
    val id: Long,
    val title: String,
    val key: String,
    val type: String,
    val site: String,
)

/** Payload of `MovieTechnicalDetailsEnvelope.data`. */
@Serializable
data class MovieTechnicalDetailsData(
    val movie: MovieTechnicalFile,
    @SerialName("video_streams") val videoStreams: List<VideoStream>,
    @SerialName("audio_streams") val audioStreams: List<AudioStream>,
    val subtitles: List<Subtitle>,
    val chapters: List<Chapter>,
)

/**
 * The playback-relevant subset of the movie file (`MovieTechnicalFile`). Only the container media
 * type is read — the direct-play `MediaItem` hint; `file_name`, `size`, `container`, `run_time`
 * and `duration` are left to `ignoreUnknownKeys` because nothing renders them.
 */
@Serializable
data class MovieTechnicalFile(
    @SerialName("mime_type") val mimeType: String,
)

/**
 * The stream, subtitle and chapter rows are shared by the movie and episode technical-details
 * routes, which differ only in the owner key (`movie_id` or `file_id`); the caller already knows
 * what it asked about, so that key is left to `ignoreUnknownKeys` on all four.
 */
@Serializable
data class VideoStream(
    val id: Long,
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
)

@Serializable
data class AudioStream(
    val id: Long,
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
)

@Serializable
data class Subtitle(
    val id: Long,
    @SerialName("stream_index") val streamIndex: Long,
    val codec: String,
    val language: SqlNullString? = null,
    val title: SqlNullString? = null,
    @SerialName("is_forced") val isForced: Boolean,
    @SerialName("is_default") val isDefault: Boolean,
)

@Serializable
data class Chapter(
    val id: Long,
    val title: String,
    /** Seconds from the start of the file. */
    @SerialName("start_time") val startTime: Long,
    val thumb: SqlNullString? = null,
)

@Serializable
data class UpdateWatchProgressRequest(
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

/** Payload of `WatchProgressEnvelope.data`; movies and TV episodes share the shape. */
@Serializable
data class WatchProgress(
    @SerialName("progress_sec") val progressSec: Double?,
    @SerialName("duration_sec") val durationSec: Double?,
    val watched: Boolean,
    @SerialName("updated_at") val updatedAt: String?,
)

/** Payload of `WatchProgressUpdateEnvelope.data`. */
@Serializable
data class WatchProgressUpdateData(
    val watched: Boolean,
)

/** Payload of `MovieWatchedEnvelope.data`. */
@Serializable
data class MovieWatchedData(
    @SerialName("movie_id") val movieId: Long,
    val watched: Boolean,
)

