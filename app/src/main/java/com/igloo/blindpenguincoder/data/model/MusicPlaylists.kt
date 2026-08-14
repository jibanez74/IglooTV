package com.igloo.blindpenguincoder.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A collaborator as the list endpoints return it, joined against the user row. */
@Serializable
data class PlaylistCollaborator(
    val id: Long,
    @SerialName("playlist_id") val playlistId: Long,
    @SerialName("user_id") val userId: Long,
    @SerialName("can_edit") val canEdit: Boolean,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val username: String,
    val email: String,
)

/**
 * The row a collaborator mutation echoes back: the same record without the user join, so it
 * carries no `username` or `email`. A separate type because it has to be — reusing
 * [PlaylistCollaborator] here cannot decode the response at all.
 */
@Serializable
data class PlaylistCollaboratorMutation(
    val id: Long,
    @SerialName("playlist_id") val playlistId: Long,
    @SerialName("user_id") val userId: Long,
    @SerialName("can_edit") val canEdit: Boolean,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class CreatePlaylistRequest(
    val name: String,
    val description: String? = null,
    @SerialName("is_public") val isPublic: Boolean? = null,
)

@Serializable
data class UpdatePlaylistRequest(
    val name: String,
    val description: String? = null,
    @SerialName("cover_image") val coverImage: String? = null,
    @SerialName("is_public") val isPublic: Boolean? = null,
)

@Serializable
data class AddTracksRequest(
    @SerialName("track_ids") val trackIds: List<Long>,
)

/** Reorder uses the same body shape as adding tracks. */
typealias ReorderTracksRequest = AddTracksRequest

@Serializable
data class AddCollaboratorRequest(
    @SerialName("user_id") val userId: Long,
    @SerialName("can_edit") val canEdit: Boolean,
)

/** Payload of `MusicPlaylistsEnvelope.data`. Playlist summary shape is untyped in the spec. */
@Serializable
data class MusicPlaylistsData(
    val playlists: List<JsonObject>,
)

/** Payload of `MusicPlaylistDetailEnvelope.data`. Playlist shape is untyped in the spec. */
@Serializable
data class MusicPlaylistDetailData(
    val playlist: JsonObject,
    @SerialName("track_count") val trackCount: Long,
    val duration: Double,
    @SerialName("is_owner") val isOwner: Boolean,
    @SerialName("can_edit") val canEdit: Boolean,
    val collaborators: List<JsonElement>?,
)

/** Payload of `MusicPlaylistMutationEnvelope.data`. */
@Serializable
data class MusicPlaylistMutationData(
    val playlist: JsonObject,
)

/** Payload of `PlaylistTracksEnvelope.data`. Track shape is untyped in the spec. */
@Serializable
data class PlaylistTracksData(
    val tracks: List<JsonObject>,
    val total: Long,
    @SerialName("has_more") val hasMore: Boolean,
    @SerialName("next_offset") val nextOffset: Long,
)

/** Payload of `PlaylistCollaboratorsEnvelope.data`. */
@Serializable
data class PlaylistCollaboratorsData(
    val collaborators: List<PlaylistCollaborator>,
)

/** Payload of `PlaylistCollaboratorMutationEnvelope.data`. */
@Serializable
data class PlaylistCollaboratorMutationData(
    val collaborator: PlaylistCollaboratorMutation,
)
