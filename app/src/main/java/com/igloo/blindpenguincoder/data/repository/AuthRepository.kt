package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.core.config.DeviceIdentity
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.map
import com.igloo.blindpenguincoder.core.network.safeApiCall
import com.igloo.blindpenguincoder.data.api.AuthApi
import com.igloo.blindpenguincoder.data.api.UserApi
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

class AuthRepository(
    private val api: AuthApi,
    private val userApi: UserApi,
    private val profiles: ProfileRepository,
    private val deviceIdentity: DeviceIdentity,
) {
    /** A minted token is held pending until [ProfileRepository.commitSignIn] learns its owner. */
    suspend fun deviceLogin(email: String, password: String): ApiResult<Unit> =
        envelopeData<DeviceTokenData>("device token") {
            api.deviceLogin(
                DeviceLoginRequest(
                    email = email,
                    password = password,
                    deviceName = deviceIdentity.name,
                    platform = deviceIdentity.platform,
                    appVersion = deviceIdentity.appVersion,
                ),
            )
        }.map { profiles.setPending(it.token) }

    suspend fun fetchCurrentUser(): ApiResult<AuthUser> =
        envelopeData<AuthUserData>("current user") { api.currentUser() }.map { it.user }

    suspend fun initiateQuickConnect(): ApiResult<QuickConnectInitiateData> =
        envelopeData("quick-connect initiate") {
            api.initiateQuickConnect(
                QuickConnectInitiateRequest(
                    deviceName = deviceIdentity.name,
                    platform = deviceIdentity.platform,
                    appVersion = deviceIdentity.appVersion,
                ),
            )
        }

    suspend fun redeemQuickConnect(code: String, secret: String): ApiResult<QuickConnectRedeemData> {
        val result = envelopeData<QuickConnectRedeemData>("quick-connect redeem") {
            api.redeemQuickConnect(QuickConnectRedeemRequest(code = code, secret = secret))
        }
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
    suspend fun verifyPin(pin: String): ApiResult<Boolean> =
        envelopeData<UserPinVerifyData>("PIN verification") {
            userApi.verifyPin(VerifyUserPinRequest(pin))
        }.map { it.valid }
}
