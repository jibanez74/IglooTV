package com.igloo.blindpenguincoder.data.api

import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import com.igloo.blindpenguincoder.core.network.expectingUnauthorized
import com.igloo.blindpenguincoder.core.network.withBearerOverride
import com.igloo.blindpenguincoder.core.network.withoutDeviceAuth
import com.igloo.blindpenguincoder.data.model.DeviceLoginRequest
import com.igloo.blindpenguincoder.data.model.QuickConnectInitiateRequest
import com.igloo.blindpenguincoder.data.model.QuickConnectRedeemRequest
import com.igloo.blindpenguincoder.data.model.VerifyUserPinRequest
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse

class AuthApi(
    private val client: HttpClient,
    private val serverUrl: ServerUrlProvider,
) {
    suspend fun deviceLogin(request: DeviceLoginRequest): HttpResponse =
        client.post(serverUrl.api("/auth/device-login")) {
            withoutDeviceAuth()
            jsonBody(request)
        }

    suspend fun currentUser(): HttpResponse =
        client.get(serverUrl.api("/auth/user"))

    /**
     * Revokes the device token this request carries — the active one, or [bearerOverride] for a
     * token a re-pairing has already replaced.
     *
     * Always marked as expecting a 401: every logout is discarding the credential it sends, so
     * being told that token is already gone is success, not a session to react to.
     */
    suspend fun logout(bearerOverride: String? = null): HttpResponse =
        client.delete(serverUrl.api("/auth/logout")) {
            expectingUnauthorized()
            bearerOverride?.let { withBearerOverride(it) }
        }

    suspend fun initiateQuickConnect(request: QuickConnectInitiateRequest): HttpResponse =
        client.post(serverUrl.api("/quick-connect/initiate")) {
            withoutDeviceAuth()
            jsonBody(request)
        }

    suspend fun redeemQuickConnect(request: QuickConnectRedeemRequest): HttpResponse =
        client.post(serverUrl.api("/quick-connect/redeem")) {
            withoutDeviceAuth()
            jsonBody(request)
        }

    /** Verifies the active profile's own PIN; the device token authenticates the call. */
    suspend fun verifyPin(request: VerifyUserPinRequest): HttpResponse =
        client.post(serverUrl.api("/user/pin/verify")) {
            jsonBody(request)
        }
}
