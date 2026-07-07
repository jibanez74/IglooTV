package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.network.PersistentCookiesStorage
import com.igloo.blindpenguincoder.core.network.safeApiCall
import com.igloo.blindpenguincoder.data.api.AuthApi
import com.igloo.blindpenguincoder.data.model.ApiEnvelope
import com.igloo.blindpenguincoder.data.model.AuthUser
import com.igloo.blindpenguincoder.data.model.AuthUserData
import com.igloo.blindpenguincoder.data.model.LoginRequest
import io.ktor.client.call.body

class AuthRepository(
    private val api: AuthApi,
    private val cookiesStorage: PersistentCookiesStorage,
) {
    suspend fun login(email: String, password: String): ApiResult<Unit> =
        safeApiCall(
            request = { api.login(LoginRequest(email = email, password = password)) },
            decode = { },
        )

    suspend fun fetchCurrentUser(): ApiResult<AuthUser> =
        safeApiCall(
            request = { api.currentUser() },
            decode = { response ->
                val envelope = response.body<ApiEnvelope<AuthUserData>>()
                envelope.data?.user ?: error("Missing user in auth response")
            },
        )

    suspend fun logout(): ApiResult<Unit> {
        val result = safeApiCall<Unit>(
            request = { api.logout() },
            decode = { },
        )
        cookiesStorage.clear()
        return result
    }

    suspend fun clearSession() {
        cookiesStorage.clear()
    }
}
