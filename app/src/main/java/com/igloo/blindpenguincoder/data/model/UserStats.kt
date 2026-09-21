package com.igloo.blindpenguincoder.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RecordPlayEventRequest(
    @SerialName("track_id") val trackId: Long,
    @SerialName("duration_played") val durationPlayed: Long,
    val completed: Boolean,
)

/** Payload of `RecordedPlayEnvelope.data`. */
@Serializable
data class RecordedPlayData(
    val recorded: Boolean,
)

/** Payload of `UserListeningStatsEnvelope.data`. */
@Serializable
data class UserListeningStatsData(
    @SerialName("total_plays") val totalPlays: Long,
    @SerialName("total_time_listened") val totalTimeListened: Long,
    @SerialName("unique_tracks_played") val uniqueTracksPlayed: Long,
    @SerialName("liked_tracks_count") val likedTracksCount: Long,
)

@Serializable
data class RecentlyPlayedTrack(
    @SerialName("played_at") val playedAt: String,
    @SerialName("duration_played") val durationPlayed: Long,
    val id: Long,
    val title: String,
    val duration: Long,
    @SerialName("album_id") val albumId: SqlNullInt64,
    @SerialName("album_title") val albumTitle: SqlNullString,
    @SerialName("album_cover") val albumCover: SqlNullString,
    @SerialName("musician_id") val musicianId: SqlNullInt64,
    @SerialName("musician_name") val musicianName: SqlNullString,
)

/** Payload of `RecentlyPlayedEnvelope.data`. */
@Serializable
data class RecentlyPlayedData(
    val tracks: List<RecentlyPlayedTrack>,
    val limit: Long,
    val offset: Long,
)

@Serializable
data class TopTrack(
    @SerialName("play_count") val playCount: Long,
    @SerialName("total_time_played") val totalTimePlayed: Long,
    @SerialName("last_played_at") val lastPlayedAt: SqlNullString,
    val id: Long,
    val title: String,
    val duration: Long,
    @SerialName("album_id") val albumId: SqlNullInt64,
    @SerialName("album_title") val albumTitle: SqlNullString,
    @SerialName("album_cover") val albumCover: SqlNullString,
    @SerialName("musician_id") val musicianId: SqlNullInt64,
    @SerialName("musician_name") val musicianName: SqlNullString,
)

/** Payload of `TopTracksEnvelope.data`. */
@Serializable
data class TopTracksData(
    val tracks: List<TopTrack>,
    val limit: Long,
    val offset: Long,
)

@Serializable
data class TopMusician(
    val id: Long,
    val name: String,
    val thumb: SqlNullString,
    @SerialName("total_play_count") val totalPlayCount: SqlNullFloat64,
    @SerialName("total_time_listened") val totalTimeListened: SqlNullFloat64,
    @SerialName("unique_tracks_played") val uniqueTracksPlayed: Long,
)

/** Payload of `TopMusiciansEnvelope.data`. */
@Serializable
data class TopMusiciansData(
    val musicians: List<TopMusician>,
    val limit: Long,
    val offset: Long,
)

@Serializable
data class TopGenre(
    val id: Long,
    val tag: String,
    @SerialName("total_play_count") val totalPlayCount: SqlNullFloat64,
    @SerialName("total_time_listened") val totalTimeListened: SqlNullFloat64,
    @SerialName("unique_tracks_played") val uniqueTracksPlayed: Long,
)

/** Payload of `TopGenresEnvelope.data`; the only top list without an offset. */
@Serializable
data class TopGenresData(
    val genres: List<TopGenre>,
    val limit: Long,
)

@Serializable
data class TopAlbum(
    val id: Long,
    val title: String,
    val cover: SqlNullString,
    val musician: SqlNullString,
    val year: SqlNullInt64,
    @SerialName("total_play_count") val totalPlayCount: SqlNullFloat64,
    @SerialName("total_time_listened") val totalTimeListened: SqlNullFloat64,
    @SerialName("unique_tracks_played") val uniqueTracksPlayed: Long,
)

/** Payload of `TopAlbumsEnvelope.data`. */
@Serializable
data class TopAlbumsData(
    val albums: List<TopAlbum>,
    val limit: Long,
    val offset: Long,
)
