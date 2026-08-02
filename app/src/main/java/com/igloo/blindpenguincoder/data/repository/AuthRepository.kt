package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.core.config.DeviceIdentity
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.network.BearerTokenProvider
import com.igloo.blindpenguincoder.core.network.safeApiCall
import com.igloo.blindpenguincoder.data.api.AuthApi
import com.igloo.blindpenguincoder.data.model.ApiEnvelope
import com.igloo.blindpenguincoder.data.model.AuthUser
import com.igloo.blindpenguincoder.data.model.AuthUserData
import com.igloo.blindpenguincoder.data.model.DeviceLoginRequest
import com.igloo.blindpenguincoder.data.model.DeviceTokenData
import com.igloo.blindpenguincoder.data.model.QuickConnectInitiateData
import com.igloo.blindpenguincoder.data.model.QuickConnectInitiateRequest
import com.igloo.blindpenguincoder.data.model.QuickConnectRedeemData
import com.igloo.blindpenguincoder.data.model.QuickConnectRedeemRequest
import com.igloo.blindpenguincoder.data.model.QuickConnectStatus
import io.ktor.client.call.body

class AuthRepository(
    private val api: AuthApi,
    private val tokens: BearerTokenProvider,
    private val deviceIdentity: DeviceIdentity,
) {
    suspend fun hasToken(): Boolean = tokens.token() != null

    suspend fun deviceLogin(email: String, password: String): ApiResult<DeviceTokenData> {
        val result = safeApiCall(
            request = {
                api.deviceLogin(
                    DeviceLoginRequest(
                        email = email,
                        password = password,
                        deviceName = deviceIdentity.name,
                        platform = deviceIdentity.platform,
                        appVersion = deviceIdentity.appVersion,
                    ),
                )
            },
            decode = { response ->
                response.body<ApiEnvelope<DeviceTokenData>>().data
                    ?: error("Missing device token in auth response")
            },
        )
        if (result is ApiResult.Success) {
            tokens.set(result.value.token)
        }
        return result
    }

    suspend fun fetchCurrentUser(): ApiResult<AuthUser> =
        safeApiCall(
            request = { api.currentUser() },
            decode = { response ->
                val envelope = response.body<ApiEnvelope<AuthUserData>>()
                envelope.data?.user ?: error("Missing user in auth response")
            },
        )

    suspend fun initiateQuickConnect(): ApiResult<QuickConnectInitiateData> =
        safeApiCall(
            request = {
                api.initiateQuickConnect(
                    QuickConnectInitiateRequest(
                        deviceName = deviceIdentity.name,
                        platform = deviceIdentity.platform,
                        appVersion = deviceIdentity.appVersion,
                    ),
                )
            },
            decode = { response ->
                response.body<ApiEnvelope<QuickConnectInitiateData>>().data
                    ?: error("Missing quick-connect code in response")
            },
        )

    suspend fun redeemQuickConnect(code: String, secret: String): ApiResult<QuickConnectRedeemData> {
        val result = safeApiCall(
            request = { api.redeemQuickConnect(QuickConnectRedeemRequest(code = code, secret = secret)) },
            decode = { response ->
                response.body<ApiEnvelope<QuickConnectRedeemData>>().data
                    ?: error("Missing quick-connect status in response")
            },
        )
        if (result is ApiResult.Success && result.value.status == QuickConnectStatus.Approved) {
            result.value.token?.let { tokens.set(it) }
        }
        return result
    }

    /** Revokes this device's token server-side; the local token is dropped regardless. */
    suspend fun logout(): ApiResult<Unit> {
        val result = safeApiCall<Unit>(
            request = { api.logout() },
            decode = { },
        )
        tokens.clear()
        return result
    }

    suspend fun clearSession() {
        tokens.clear()
    }
}
