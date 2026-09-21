package com.igloo.blindpenguincoder.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AdminCreateUserRequest(
    val name: String,
    val email: String,
    val password: String,
    @SerialName("is_admin") val isAdmin: Boolean,
)

@Serializable
data class AdminUpdateUserRequest(
    val name: String,
    val email: String,
    @SerialName("is_admin") val isAdmin: Boolean,
)

@Serializable
data class AdminResetUserPasswordRequest(
    val password: String,
)

/** Payload of `AdminUserEnvelope.data`; the spec's `AdminUser` is exactly [AuthUser]. */
@Serializable
data class AdminUserData(
    val user: AuthUser,
)

/** Payload of `AdminUsersEnvelope.data`. */
@Serializable
data class AdminUsersData(
    val users: List<AuthUser>,
)

/** User entry offered for watch-room invites. */
@Serializable
data class InviteUser(
    val id: Long,
    val name: String,
    val email: String,
    val avatar: String?,
)

/** Payload of `InviteUsersEnvelope.data`. */
@Serializable
data class InviteUsersData(
    val users: List<InviteUser>,
)
