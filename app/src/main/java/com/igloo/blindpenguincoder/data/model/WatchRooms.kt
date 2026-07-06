package com.igloo.blindpenguincoder.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WatchRoomMember(
    val id: Long,
    val name: String,
    val avatar: String?,
)

@Serializable
data class WatchRoomListItem(
    val id: Long,
    @SerialName("movie_id") val movieId: Long,
    @SerialName("movie_title") val movieTitle: String,
    @SerialName("movie_poster") val moviePoster: String?,
    val owner: WatchRoomMember,
    val members: List<WatchRoomMember>,
    @SerialName("playback_mode") val playbackMode: PlaybackMode,
    @SerialName("is_owner") val isOwner: Boolean,
    @SerialName("created_at") val createdAt: String,
)

/** WatchRoomListItem plus track selection. */
@Serializable
data class WatchRoomDetail(
    val id: Long,
    @SerialName("movie_id") val movieId: Long,
    @SerialName("movie_title") val movieTitle: String,
    @SerialName("movie_poster") val moviePoster: String?,
    val owner: WatchRoomMember,
    val members: List<WatchRoomMember>,
    @SerialName("playback_mode") val playbackMode: PlaybackMode,
    @SerialName("is_owner") val isOwner: Boolean,
    @SerialName("created_at") val createdAt: String,
    @SerialName("audio_track") val audioTrack: Long,
    @SerialName("subtitle_track") val subtitleTrack: Long?,
)

@Serializable
data class CreateWatchRoomRequest(
    @SerialName("movie_id") val movieId: Long,
    val mode: PlaybackMode,
    @SerialName("audio_track") val audioTrack: Long,
    @SerialName("subtitle_track") val subtitleTrack: Long?,
    @SerialName("invited_user_ids") val invitedUserIds: List<Long>,
)

/** Payload of `WatchRoomsEnvelope.data`. */
@Serializable
data class WatchRoomsData(
    val rooms: List<WatchRoomListItem>,
)

/** Payload of `CreateWatchRoomEnvelope.data`. */
@Serializable
data class CreateWatchRoomData(
    @SerialName("room_id") val roomId: Long,
)

/** Payload of `WatchRoomEnvelope.data`. */
@Serializable
data class WatchRoomData(
    val room: WatchRoomDetail,
)

/** Payload of `JoinWatchRoomEnvelope.data`. */
@Serializable
data class JoinWatchRoomData(
    @SerialName("room_id") val roomId: Long,
    val joined: Boolean,
)

/** Payload of `DeleteWatchRoomEnvelope.data`. */
@Serializable
data class DeleteWatchRoomData(
    val deleted: Boolean,
)

@Serializable
data class WatchRoomPlaybackState(
    val paused: Boolean,
    @SerialName("position_sec") val positionSec: Double,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
enum class WatchRoomEventType {
    @SerialName("room_snapshot") RoomSnapshot,
    @SerialName("playback_changed") PlaybackChanged,
    @SerialName("member_joined") MemberJoined,
    @SerialName("member_left") MemberLeft,
    @SerialName("room_deleted") RoomDeleted,
    @SerialName("pong") Pong,
}

/** Server-to-client event on the watch-room WebSocket. */
@Serializable
data class WatchRoomServerEvent(
    val type: WatchRoomEventType,
    @SerialName("room_id") val roomId: Long,
    val playback: WatchRoomPlaybackState? = null,
    val member: WatchRoomMember? = null,
    @SerialName("connected_user_ids") val connectedUserIds: List<Long>? = null,
)
