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

/**
 * Payload of `ShowEpisodePlaybackEnvelope.data`: what the player needs to title an episode.
 * `next_episode` is left to `ignoreUnknownKeys` until the player advances between episodes.
 */
@Serializable
data class ShowEpisodePlaybackData(
    val show: ShowEpisodePlaybackShow,
    val season: ShowEpisodePlaybackSeason,
    val episode: ShowEpisodeSummary,
)

@Serializable
data class ShowEpisodePlaybackShow(
    val id: Long,
    val name: String,
    @SerialName("poster_path") val posterPath: SqlNullString,
    @SerialName("backdrop_path") val backdropPath: SqlNullString,
)

@Serializable
data class ShowEpisodePlaybackSeason(
    @SerialName("season_number") val seasonNumber: Long,
    val name: String,
)

/** One episode's catalog metadata (TMDB fields only). */
@Serializable
data class ShowEpisodeSummary(
    val id: Long,
    @SerialName("episode_number") val episodeNumber: Long,
    val name: String,
    val overview: SqlNullString,
    @SerialName("air_date") val airDate: SqlNullString,
    @SerialName("still_path") val stillPath: SqlNullString,
    @SerialName("tmdb_runtime") val tmdbRuntime: SqlNullInt64,
    @SerialName("vote_average") val voteAverage: SqlNullFloat64,
    @SerialName("vote_count") val voteCount: SqlNullInt64,
)

/** Payload of `ShowEpisodeTechnicalDetailsEnvelope.data`; the stream rows are the movie ones. */
@Serializable
data class ShowEpisodeTechnicalDetailsData(
    val file: ShowTechnicalFile,
    @SerialName("video_streams") val videoStreams: List<VideoStream>,
    @SerialName("audio_streams") val audioStreams: List<AudioStream>,
    val subtitles: List<Subtitle>,
    val chapters: List<Chapter>,
)

/**
 * The playback-relevant subset of the episode file. Unlike a movie, an episode carries no
 * runtime of its own for the player to fall back on, so the probed duration is read too;
 * `file_name`, `size` and `container` are left to `ignoreUnknownKeys`.
 */
@Serializable
data class ShowTechnicalFile(
    @SerialName("mime_type") val mimeType: String,
    val duration: SqlNullFloat64,
)
