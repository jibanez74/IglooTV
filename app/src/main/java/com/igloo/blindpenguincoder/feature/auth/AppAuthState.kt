package com.igloo.blindpenguincoder.feature.auth

import com.igloo.blindpenguincoder.core.config.ServerAddress
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.data.model.AuthUser

sealed interface AppAuthState {
    data object Loading : AppAuthState
    data class NeedsServer(val initialOrigin: String = "") : AppAuthState
    data class NeedsLogin(
        val serverAddress: ServerAddress,
        val restoreError: AppError? = null,
    ) : AppAuthState
    data class Authenticated(val user: AuthUser) : AppAuthState
}
