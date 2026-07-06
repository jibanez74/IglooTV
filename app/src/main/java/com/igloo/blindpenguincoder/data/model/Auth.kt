package com.igloo.blindpenguincoder.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
)

@Serializable
data class AuthUser(
    val id: Long,
    val name: String,
    val email: String,
    @SerialName("is_admin") val isAdmin: Boolean,
    val avatar: String?,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

/** Payload of `AuthUserEnvelope.data`. */
@Serializable
data class AuthUserData(
    val user: AuthUser,
)

@Serializable
data class UpdateUserNameRequest(
    val name: String,
)

@Serializable
data class UpdateUserEmailRequest(
    val email: String,
)

@Serializable
data class UpdateUserPasswordRequest(
    @SerialName("current_password") val currentPassword: String,
    @SerialName("new_password") val newPassword: String,
)

@Serializable
data class UpdateUserAvatarRequest(
    val avatar: String,
)
