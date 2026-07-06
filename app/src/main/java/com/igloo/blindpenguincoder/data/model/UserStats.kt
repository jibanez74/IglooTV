package com.igloo.blindpenguincoder.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

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

/** Payload of `TracksWithLimitOffsetEnvelope.data` (top tracks, recently played). */
@Serializable
data class TracksWithLimitOffsetData(
    val tracks: List<JsonObject>,
    val limit: Long,
    val offset: Long,
)

/** Payload of `TopMusiciansEnvelope.data`. Item shape is untyped in the spec. */
@Serializable
data class TopMusiciansData(
    val musicians: List<JsonObject>,
    val limit: Long,
    val offset: Long,
)

/** Payload of `TopGenresEnvelope.data`. Item shape is untyped in the spec. */
@Serializable
data class TopGenresData(
    val genres: List<JsonObject>,
    val limit: Long,
)

/** Payload of `TopAlbumsEnvelope.data`. Item shape is untyped in the spec. */
@Serializable
data class TopAlbumsData(
    val albums: List<JsonObject>,
    val limit: Long,
    val offset: Long,
)
