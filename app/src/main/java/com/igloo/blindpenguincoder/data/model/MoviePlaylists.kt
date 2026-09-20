package com.igloo.blindpenguincoder.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class MoviePlaylist(
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

/** MoviePlaylist plus list metadata, used in playlist listings. */
@Serializable
data class MoviePlaylistSummary(
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
    @SerialName("movie_count") val movieCount: Long? = null,
    @SerialName("is_owner") val isOwner: Boolean? = null,
    @SerialName("can_edit") val canEdit: Boolean? = null,
)

@Serializable
data class CreateMoviePlaylistRequest(
    val name: String,
    val description: String? = null,
    @SerialName("is_public") val isPublic: Boolean? = null,
    @SerialName("movie_id") val movieId: Long? = null,
)

@Serializable
data class UpdateMoviePlaylistRequest(
    val name: String,
    val description: String? = null,
    @SerialName("cover_image") val coverImage: String? = null,
    @SerialName("is_public") val isPublic: Boolean? = null,
    @SerialName("movie_id") val movieId: Long? = null,
)

@Serializable
data class AddMoviesRequest(
    @SerialName("movie_ids") val movieIds: List<Long>,
)

/** Payload of `MoviePlaylistsEnvelope.data`. */
@Serializable
data class MoviePlaylistsData(
    val playlists: List<MoviePlaylistSummary>,
)

/** Payload of `MoviePlaylistDetailEnvelope.data`. Collaborator items are untyped in the spec. */
@Serializable
data class MoviePlaylistDetailData(
    val playlist: MoviePlaylist,
    @SerialName("movie_count") val movieCount: Long,
    @SerialName("is_owner") val isOwner: Boolean,
    @SerialName("can_edit") val canEdit: Boolean,
    val collaborators: List<JsonElement>?,
)

/** Payload of `MoviePlaylistMutationEnvelope.data`. */
@Serializable
data class MoviePlaylistMutationData(
    val playlist: MoviePlaylist,
)

/** Payload of `BulkAddEnvelope.data`. */
@Serializable
data class BulkAddData(
    val added: Long,
    val skipped: Long,
)
