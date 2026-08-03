package com.igloo.blindpenguincoder.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AuthUser(
    val id: Long,
    val name: String,
    val email: String,
    @SerialName("is_admin") val isAdmin: Boolean,
    // The backend serializes this as a Go sql.NullString object, not a plain
    // string (docs/openapi.json is outdated here; see userResponseMap in ../Igloo).
    val avatar: SqlNullString? = null,
    @SerialName("has_pin") val hasPin: Boolean,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

/** Payload of `AuthUserEnvelope.data`. */
@Serializable
data class AuthUserData(
    val user: AuthUser,
)
