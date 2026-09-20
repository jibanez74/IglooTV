package com.igloo.blindpenguincoder.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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

/** A music playlist row (`Playlist`); the same columns as [MoviePlaylist]. */
@Serializable
data class Playlist(
    val id: Long,
    @SerialName("user_id") val userId: Long,
    val name: String,
    val description: SqlNullString,
    @SerialName("cover_image") val coverImage: SqlNullString,
    @SerialName("is_public") val isPublic: Boolean,
    @SerialName("movie_id") val movieId: SqlNullInt64,
    @SerialName("content_type") val contentType: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

/** [Playlist] plus list metadata (`PlaylistSummary`), used in playlist listings. */
@Serializable
data class PlaylistSummary(
    val id: Long,
    @SerialName("user_id") val userId: Long,
    val name: String,
    val description: SqlNullString,
    @SerialName("cover_image") val coverImage: SqlNullString,
    @SerialName("is_public") val isPublic: Boolean,
    @SerialName("movie_id") val movieId: SqlNullInt64,
    @SerialName("content_type") val contentType: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("track_count") val trackCount: Long,
    @SerialName("total_duration") val totalDuration: Long,
    @SerialName("is_owner") val isOwner: Boolean,
    @SerialName("can_edit") val canEdit: Boolean,
)

/** One entry of a playlist's track list (`PlaylistTrack`): the membership row joined to the track. */
@Serializable
data class PlaylistTrack(
    @SerialName("playlist_track_id") val playlistTrackId: Long,
    val position: Long,
    @SerialName("added_at") val addedAt: String,
    @SerialName("added_by") val addedBy: SqlNullInt64,
    val id: Long,
    val title: String,
    val duration: Long,
    val codec: String,
    @SerialName("bit_rate") val bitRate: Long,
    @SerialName("album_id") val albumId: SqlNullInt64,
    @SerialName("musician_id") val musicianId: SqlNullInt64,
    @SerialName("album_title") val albumTitle: SqlNullString,
    @SerialName("album_cover") val albumCover: SqlNullString,
    @SerialName("musician_name") val musicianName: SqlNullString,
)

/** Payload of `MusicPlaylistsEnvelope.data`. */
@Serializable
data class MusicPlaylistsData(
    val playlists: List<PlaylistSummary>,
)

/** Payload of `MusicPlaylistDetailEnvelope.data`. */
@Serializable
data class MusicPlaylistDetailData(
    val playlist: Playlist,
    @SerialName("track_count") val trackCount: Long,
    val duration: Double,
    @SerialName("is_owner") val isOwner: Boolean,
    @SerialName("can_edit") val canEdit: Boolean,
    val collaborators: List<PlaylistCollaborator>?,
)

/** Payload of `MusicPlaylistMutationEnvelope.data`. */
@Serializable
data class MusicPlaylistMutationData(
    val playlist: Playlist,
)

/** Payload of `PlaylistTracksEnvelope.data`. */
@Serializable
data class PlaylistTracksData(
    val tracks: List<PlaylistTrack>,
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
