package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.core.config.ServerAddress
import com.igloo.blindpenguincoder.core.config.ServerAddressParseResult
import com.igloo.blindpenguincoder.core.config.parseServerAddress
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.core.network.BearerTokenProvider
import com.igloo.blindpenguincoder.core.network.ServerHealthProbe
import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import com.igloo.blindpenguincoder.core.storage.ServerSettingsStore

class ServerRepository(
    private val probe: ServerHealthProbe,
    private val settings: ServerSettingsStore,
    private val serverUrl: ServerUrlProvider,
    private val tokenProvider: BearerTokenProvider,
) {
    suspend fun connect(rawInput: String): ApiResult<ServerAddress> {
        val candidate = when (val parsed = parseServerAddress(rawInput)) {
            is ServerAddressParseResult.Valid -> parsed.address
            is ServerAddressParseResult.Invalid -> {
                return ApiResult.Failure(AppError.Validation(parsed.message))
            }
        }

        val finalAddress = when (val result = probe.probe(candidate)) {
            is ApiResult.Success -> result.value
            is ApiResult.Failure -> return result
        }

        val previous = serverUrl.current.value
        if (previous == null || !previous.hasSameOrigin(finalAddress)) {
            tokenProvider.clear()
        }

        settings.save(finalAddress.apiBaseUrl)
        serverUrl.set(finalAddress)
        return ApiResult.Success(finalAddress)
    }
}
