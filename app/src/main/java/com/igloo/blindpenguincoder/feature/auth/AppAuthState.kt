package com.igloo.blindpenguincoder.feature.auth

import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.data.model.AuthUser

sealed interface AppAuthState {
    data object Loading : AppAuthState
    data object NeedsServer : AppAuthState
    data class NeedsLogin(val serverUrl: String, val restoreError: AppError? = null) : AppAuthState
    data class Authenticated(val user: AuthUser) : AppAuthState
}
