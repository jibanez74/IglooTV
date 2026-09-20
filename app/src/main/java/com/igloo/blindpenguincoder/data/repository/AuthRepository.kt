package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.core.config.DeviceIdentity
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.network.safeApiCall
import com.igloo.blindpenguincoder.data.api.AuthApi
import com.igloo.blindpenguincoder.data.api.UserApi
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
import com.igloo.blindpenguincoder.data.model.UserPinVerifyData
import com.igloo.blindpenguincoder.data.model.VerifyUserPinRequest
import io.ktor.client.call.body

class AuthRepository(
    private val api: AuthApi,
    private val userApi: UserApi,
    private val profiles: ProfileRepository,
    private val deviceIdentity: DeviceIdentity,
) {
    /** A minted token is held pending until [ProfileRepository.commitSignIn] learns its owner. */
    suspend fun deviceLogin(email: String, password: String): ApiResult<Unit> {
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
        return when (result) {
            is ApiResult.Success -> {
                profiles.setPending(result.value.token)
                ApiResult.Success(Unit)
            }
            is ApiResult.Failure -> result
        }
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
            result.value.token?.let { profiles.setPending(it) }
        }
        return result
    }

    /**
     * Revokes a device token server-side. [bearerOverride] targets a token a re-pairing
     * already replaced; without it, the active profile's token is revoked.
     */
    suspend fun logout(bearerOverride: String? = null): ApiResult<Unit> = safeApiCall(
        request = { api.logout(bearerOverride) },
        decode = { },
    )

    /** A wrong PIN is a successful call returning false; only a dead token fails. */
    suspend fun verifyPin(pin: String): ApiResult<Boolean> = safeApiCall(
        request = { userApi.verifyPin(VerifyUserPinRequest(pin)) },
        decode = { response ->
            response.body<ApiEnvelope<UserPinVerifyData>>().data?.valid
                ?: error("Missing PIN verification result in response")
        },
    )
}
