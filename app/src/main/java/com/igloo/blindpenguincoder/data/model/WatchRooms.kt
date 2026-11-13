package com.igloo.blindpenguincoder.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Payload of `WatchRoomsEnvelope.data`: the caller's rooms, newest first, never paged. */
@Serializable
data class WatchRoomsData(
    val rooms: List<WatchRoomListItem>,
)

/**
 * One room of `GET /watch-rooms`. The route also sends `movie_id`, `playback_mode` and
 * `created_at`, left to `ignoreUnknownKeys` — the list only draws the card, and opening a room
 * reads [WatchRoomDetail].
 */
@Serializable
data class WatchRoomListItem(
    val id: Long,
    @SerialName("movie_title") val movieTitle: String,
    // A TMDB image path, or the `/api/static/...` path of the server's downloaded copy; resolve
    // with tmdbImageUrl() before rendering.
    @SerialName("movie_poster") val moviePoster: String?,
    val owner: UserSummary,
    /** Everyone in the room, the owner included, in the order they joined. */
    val members: List<UserSummary>,
    @SerialName("is_owner") val isOwner: Boolean,
)

/** Payload of `GET /watch-rooms/{id}`. */
@Serializable
data class WatchRoomData(
    val room: WatchRoomDetail,
)

/**
 * One room as its own screen reads it: the list item's fields plus what the room fixed when it was
 * created. `created_at` is left to `ignoreUnknownKeys`.
 */
@Serializable
data class WatchRoomDetail(
    val id: Long,
    @SerialName("movie_id") val movieId: Long,
    @SerialName("movie_title") val movieTitle: String,
    // Same shapes as WatchRoomListItem.moviePoster.
    @SerialName("movie_poster") val moviePoster: String?,
    val owner: UserSummary,
    val members: List<UserSummary>,
    /** A `PlaybackMode` wire id; read it with `playbackModeOf`. */
    @SerialName("playback_mode") val playbackMode: String,
    @SerialName("is_owner") val isOwner: Boolean,
    /** Zero-based position in the movie's audio list, baked into the room's stream. */
    @SerialName("audio_track") val audioTrack: Long,
    /** Zero-based position in the movie's subtitle list, or null for none. */
    @SerialName("subtitle_track") val subtitleTrack: Long?,
)

/**
 * Body of `POST /watch-rooms`. The two tracks are positions in the movie's audio and subtitle
 * lists, not stream ids; a null [subtitleTrack] is left out (`explicitNulls = false`), which is
 * the server's "no subtitles".
 */
@Serializable
data class CreateWatchRoomRequest(
    @SerialName("movie_id") val movieId: Long,
    /** A `PlaybackMode` wire id. */
    val mode: String,
    @SerialName("audio_track") val audioTrack: Long,
    @SerialName("subtitle_track") val subtitleTrack: Long?,
    @SerialName("invited_user_ids") val invitedUserIds: List<Long>,
)

/** Payload of `POST /watch-rooms`: the new room, which the creator joins at once. */
@Serializable
data class CreateWatchRoomData(
    @SerialName("room_id") val roomId: Long,
)

/**
 * Payload of `GET /users`: everyone but the caller, by name — the people a room can invite. Each
 * row also carries an `email`, which the TV never shows.
 */
@Serializable
data class InviteUsersData(
    val users: List<UserSummary>,
)

/** A user as other users see them. */
@Serializable
data class UserSummary(
    val id: Long,
    val name: String,
    // The same three shapes as AuthUser.avatar; resolve with serverImageUrl() before rendering.
    val avatar: String?,
)
