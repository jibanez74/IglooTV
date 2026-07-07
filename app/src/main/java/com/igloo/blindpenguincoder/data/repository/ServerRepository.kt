package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.core.config.normalizeServerUrl
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.core.network.PersistentCookiesStorage
import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import com.igloo.blindpenguincoder.core.network.safeApiCall
import com.igloo.blindpenguincoder.core.storage.ServerSettingsStore
import com.igloo.blindpenguincoder.data.api.AuthApi
import io.ktor.http.Url

class ServerRepository(
    private val api: AuthApi,
    private val settings: ServerSettingsStore,
    private val serverUrl: ServerUrlProvider,
    private val cookiesStorage: PersistentCookiesStorage,
) {
    /**
     * Validates user-typed server input against `/health`, then persists it
     * as the active server. Returns the normalized base URL.
     */
    suspend fun connect(rawInput: String): ApiResult<String> {
        val normalized = normalizeServerUrl(rawInput)
            ?: return ApiResult.Failure(
                AppError.Validation("Enter a valid server URL, like http://192.168.1.5:8080"),
            )

        val health = safeApiCall<Unit>(
            request = { api.health(normalized) },
            decode = { },
        )
        if (health is ApiResult.Failure) return health

        val previous = serverUrl.current.value
        if (previous != null && Url(previous).host != Url(normalized).host) {
            cookiesStorage.clear()
        }

        settings.save(normalized)
        serverUrl.set(normalized)
        return ApiResult.Success(normalized)
    }
}
