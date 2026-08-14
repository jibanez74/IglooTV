package com.igloo.blindpenguincoder.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AuthUser(
    val id: Long,
    val name: String,
    val email: String,
    @SerialName("is_admin") val isAdmin: Boolean,
    // Either a backend-relative `/api/static/avatars/...` path or an absolute URL; resolve
    // with avatarImageUrl() before rendering.
    val avatar: String? = null,
    @SerialName("has_pin") val hasPin: Boolean,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

/** Payload of `AuthUserEnvelope.data`. */
@Serializable
data class AuthUserData(
    val user: AuthUser,
)
