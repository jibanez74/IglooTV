package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.network.PersistentCookiesStorage
import com.igloo.blindpenguincoder.core.network.safeApiCall
import com.igloo.blindpenguincoder.data.api.AuthApi
import com.igloo.blindpenguincoder.data.model.ApiEnvelope
import com.igloo.blindpenguincoder.data.model.AuthUser
import com.igloo.blindpenguincoder.data.model.AuthUserData
import com.igloo.blindpenguincoder.data.model.Device
import com.igloo.blindpenguincoder.data.model.DeviceLoginRequest
import com.igloo.blindpenguincoder.data.model.DeviceTokenData
import com.igloo.blindpenguincoder.data.model.DevicesListData
import com.igloo.blindpenguincoder.data.model.LoginRequest
import com.igloo.blindpenguincoder.data.model.QuickConnectApproveRequest
import com.igloo.blindpenguincoder.data.model.QuickConnectInitiateData
import com.igloo.blindpenguincoder.data.model.QuickConnectInitiateRequest
import com.igloo.blindpenguincoder.data.model.QuickConnectRedeemData
import com.igloo.blindpenguincoder.data.model.QuickConnectRedeemRequest
import com.igloo.blindpenguincoder.data.model.RenameDeviceRequest
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

    suspend fun deviceLogin(
        email: String,
        password: String,
        deviceName: String,
        platform: String? = null,
        appVersion: String? = null,
    ): ApiResult<DeviceTokenData> =
        safeApiCall(
            request = {
                api.deviceLogin(
                    DeviceLoginRequest(
                        email = email,
                        password = password,
                        deviceName = deviceName,
                        platform = platform,
                        appVersion = appVersion,
                    ),
                )
            },
            decode = { response ->
                response.body<ApiEnvelope<DeviceTokenData>>().data
                    ?: error("Missing device token in auth response")
            },
        )

    suspend fun fetchCurrentUser(): ApiResult<AuthUser> =
        safeApiCall(
            request = { api.currentUser() },
            decode = { response ->
                val envelope = response.body<ApiEnvelope<AuthUserData>>()
                envelope.data?.user ?: error("Missing user in auth response")
            },
        )

    suspend fun initiateQuickConnect(
        deviceName: String,
        platform: String? = null,
        appVersion: String? = null,
    ): ApiResult<QuickConnectInitiateData> =
        safeApiCall(
            request = {
                api.initiateQuickConnect(
                    QuickConnectInitiateRequest(
                        deviceName = deviceName,
                        platform = platform,
                        appVersion = appVersion,
                    ),
                )
            },
            decode = { response ->
                response.body<ApiEnvelope<QuickConnectInitiateData>>().data
                    ?: error("Missing quick-connect code in response")
            },
        )

    suspend fun redeemQuickConnect(code: String, secret: String): ApiResult<QuickConnectRedeemData> =
        safeApiCall(
            request = { api.redeemQuickConnect(QuickConnectRedeemRequest(code = code, secret = secret)) },
            decode = { response ->
                response.body<ApiEnvelope<QuickConnectRedeemData>>().data
                    ?: error("Missing quick-connect status in response")
            },
        )

    suspend fun approveQuickConnect(code: String): ApiResult<Unit> =
        safeApiCall(
            request = { api.approveQuickConnect(QuickConnectApproveRequest(code = code)) },
            decode = { },
        )

    suspend fun devices(): ApiResult<List<Device>> =
        safeApiCall(
            request = { api.devices() },
            decode = { response ->
                response.body<ApiEnvelope<DevicesListData>>().data?.devices
                    ?: error("Missing devices in response")
            },
        )

    suspend fun renameDevice(id: Long, name: String): ApiResult<Unit> =
        safeApiCall(
            request = { api.renameDevice(id, RenameDeviceRequest(name = name)) },
            decode = { },
        )

    suspend fun revokeDevice(id: Long): ApiResult<Unit> =
        safeApiCall(
            request = { api.revokeDevice(id) },
            decode = { },
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
