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
 * `created_at`, left to `ignoreUnknownKeys` — the list only draws the card, and joining a room
 * reads its details route.
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

/** A user as other users see them. */
@Serializable
data class UserSummary(
    val id: Long,
    val name: String,
    // The same three shapes as AuthUser.avatar; resolve with serverImageUrl() before rendering.
    val avatar: String?,
)
