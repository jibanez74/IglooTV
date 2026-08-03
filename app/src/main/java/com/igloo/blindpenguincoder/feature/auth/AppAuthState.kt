package com.igloo.blindpenguincoder.feature.auth

import com.igloo.blindpenguincoder.core.config.ServerAddress
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.data.model.AuthUser
import com.igloo.blindpenguincoder.data.model.ProfileSummary

sealed interface AppAuthState {
    data object Loading : AppAuthState

    data class NeedsServer(val initialOrigin: String = "") : AppAuthState

    /** Who is watching? Rendered from stored profiles, so it needs no network. */
    data class ChooseProfile(
        val serverAddress: ServerAddress,
        val profiles: List<ProfileSummary>,
        val initialFocusUserId: Long?,
        /** Explains a profile that has just disappeared, e.g. a revoked session. */
        val notice: String? = null,
        val restoreError: AppError? = null,
    ) : AppAuthState

    data class NeedsPin(
        val serverAddress: ServerAddress,
        val profile: ProfileSummary,
    ) : AppAuthState

    data class NeedsLogin(
        val serverAddress: ServerAddress,
        val restoreError: AppError? = null,
        /** True when other profiles exist, so this is "add a user" rather than first setup. */
        val canCancel: Boolean = false,
    ) : AppAuthState

    data class Authenticated(val user: AuthUser) : AppAuthState
}
