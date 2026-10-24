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
 * Payload of `ShowEpisodePlaybackEnvelope.data`, reduced to what the player needs to title an
 * episode and to offer the one that follows it. The catalog fields (ids, backdrop, season name,
 * overview, air date, still, runtime, votes) are left to `ignoreUnknownKeys` until a screen reads
 * them.
 */
@Serializable
data class ShowEpisodePlaybackData(
    val show: ShowEpisodePlaybackShow,
    val season: ShowEpisodePlaybackSeason,
    val episode: ShowEpisodePlaybackEpisode,
    /** Null after the show's last playable episode. */
    @SerialName("next_episode") val nextEpisode: ShowEpisodeUpNext?,
)

@Serializable
data class ShowEpisodePlaybackShow(
    val name: String,
    @SerialName("poster_path") val posterPath: SqlNullString,
)

@Serializable
data class ShowEpisodePlaybackSeason(
    @SerialName("season_number") val seasonNumber: Long,
)

@Serializable
data class ShowEpisodePlaybackEpisode(
    @SerialName("episode_number") val episodeNumber: Long,
    val name: String,
)

/**
 * The episode the player advances to when the current one ends: the next in listing order
 * (regular seasons by number, specials last) backed by a file other than the one that just
 * played, with the caller's progress on it so the hand-off can resume where it was left.
 */
@Serializable
data class ShowEpisodeUpNext(
    val id: Long,
    @SerialName("season_number") val seasonNumber: Long,
    @SerialName("episode_number") val episodeNumber: Long,
    val name: String,
    @SerialName("still_path") val stillPath: SqlNullString,
    @SerialName("progress_sec") val progressSec: SqlNullFloat64,
    @SerialName("duration_sec") val durationSec: SqlNullFloat64,
    val watched: Boolean,
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

/** One entry of `GET /shows/latest`, shared by Home's rail and newest-show hero. */
@Serializable
data class LatestShow(
    val id: Long,
    val name: String,
    @SerialName("poster_path") val posterPath: SqlNullString,
    @SerialName("premiere_year") val premiereYear: SqlNullInt64,
)

/** Payload of `LatestShowsEnvelope.data`. */
@Serializable
data class LatestShowsData(
    val shows: List<LatestShow>,
)

/**
 * Payload of `ShowDetailsEnvelope.data`: everything the show details screen and the Home hero's
 * show page read. `crew` is left to `ignoreUnknownKeys` — the page names only the creators.
 */
@Serializable
data class ShowDetailsData(
    val show: Show,
    val seasons: List<ShowSeasonSummary>,
    val cast: List<ShowCastCredit>,
    val creators: List<ShowPerson>,
    val genres: List<Genre>,
    val networks: List<ShowNetwork>,
    @SerialName("production_companies") val productionCompanies: List<ProductionCompany>,
    @SerialName("extra_videos") val extraVideos: List<ExtraVideo>,
)

/**
 * The stored show. The scraper writes TMDB's "no data" as a valid zero, so callers guard every
 * number against zero as well as null. `tmdb_id`, `origin_countries` and `vote_count` are left to
 * `ignoreUnknownKeys`.
 */
@Serializable
data class Show(
    val id: Long,
    val name: String,
    @SerialName("original_name") val originalName: SqlNullString,
    @SerialName("premiere_year") val premiereYear: SqlNullInt64,
    val overview: SqlNullString,
    val tagline: SqlNullString,
    val language: SqlNullString,
    @SerialName("first_air_date") val firstAirDate: SqlNullString,
    @SerialName("last_air_date") val lastAirDate: SqlNullString,
    val status: SqlNullString,
    val type: SqlNullString,
    @SerialName("poster_path") val posterPath: SqlNullString,
    @SerialName("backdrop_path") val backdropPath: SqlNullString,
    @SerialName("vote_average") val voteAverage: SqlNullFloat64,
    val certification: SqlNullString,
    /** What TMDB knows of, against which the library's own seasons are counted ("2 of 3"). */
    @SerialName("tmdb_season_count") val tmdbSeasonCount: SqlNullInt64,
    @SerialName("tmdb_episode_count") val tmdbEpisodeCount: SqlNullInt64,
)

/**
 * One season the library holds, in the server's order: by number, specials (season 0) last.
 * Specials do not count as a season. The season's own name, overview, air date and poster are
 * left to `ignoreUnknownKeys` — the page labels seasons itself, as the web client does.
 */
@Serializable
data class ShowSeasonSummary(
    @SerialName("season_number") val seasonNumber: Long,
    @SerialName("available_episode_count") val availableEpisodeCount: Long,
)

/**
 * One cast credit, in billing order. One artist can hold several roles, so neither
 * `artist_id` nor `credit_id` is read as the card's identity; the ids are left to
 * `ignoreUnknownKeys`.
 */
@Serializable
data class ShowCastCredit(
    val character: String,
    @SerialName("cast_order") val castOrder: Long,
    @SerialName("episode_count") val episodeCount: Long,
    @SerialName("artist_name") val artistName: String,
    @SerialName("artist_profile") val artistProfile: SqlNullString,
)

/** A show's creator; the Key Crew band names them and nothing more. */
@Serializable
data class ShowPerson(
    val name: String,
)

/** A network that aired the show; drawn as text, so the logo is left to `ignoreUnknownKeys`. */
@Serializable
data class ShowNetwork(
    val name: String,
)

/**
 * Payload of `ShowSeasonEpisodesEnvelope.data`. The `season` summary repeats what the details
 * route already sent, so it is left to `ignoreUnknownKeys`.
 */
@Serializable
data class ShowSeasonEpisodesData(
    val episodes: List<ShowEpisode>,
)

/**
 * One episode of a season, with the caller's progress. Progress is null until a save or a mark;
 * marking watched (or a save at 95% or more) sets [watched] and resets the position.
 * `vote_average` and `vote_count` are left to `ignoreUnknownKeys`.
 */
@Serializable
data class ShowEpisode(
    val id: Long,
    @SerialName("episode_number") val episodeNumber: Long,
    val name: String,
    val overview: SqlNullString,
    @SerialName("air_date") val airDate: SqlNullString,
    @SerialName("still_path") val stillPath: SqlNullString,
    /** Minutes, from TMDB. */
    @SerialName("tmdb_runtime") val tmdbRuntime: SqlNullInt64,
    @SerialName("progress_sec") val progressSec: SqlNullFloat64,
    @SerialName("duration_sec") val durationSec: SqlNullFloat64,
    val watched: Boolean,
)

/** Payload of `EpisodeWatchedEnvelope.data`: the server's answer to a watched write. */
@Serializable
data class EpisodeWatchedData(
    @SerialName("episode_id") val episodeId: Long,
    val watched: Boolean,
)
