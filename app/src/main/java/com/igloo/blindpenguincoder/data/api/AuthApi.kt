package com.igloo.blindpenguincoder.data.api

import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import com.igloo.blindpenguincoder.core.network.withBearerOverride
import com.igloo.blindpenguincoder.core.network.withoutDeviceAuth
import com.igloo.blindpenguincoder.data.model.DeviceLoginRequest
import com.igloo.blindpenguincoder.data.model.QuickConnectInitiateRequest
import com.igloo.blindpenguincoder.data.model.QuickConnectRedeemRequest
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType

class AuthApi(
    private val client: HttpClient,
    private val serverUrl: ServerUrlProvider,
) {
    suspend fun deviceLogin(request: DeviceLoginRequest): HttpResponse =
        client.post("${serverUrl.require().apiBaseUrl}/auth/device-login") {
            withoutDeviceAuth()
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    suspend fun currentUser(): HttpResponse =
        client.get("${serverUrl.require().apiBaseUrl}/auth/user")

    /** [bearerOverride] revokes a token that a re-pairing has already replaced. */
    suspend fun logout(bearerOverride: String? = null): HttpResponse =
        client.delete("${serverUrl.require().apiBaseUrl}/auth/logout") {
            bearerOverride?.let { withBearerOverride(it) }
        }

    suspend fun initiateQuickConnect(request: QuickConnectInitiateRequest): HttpResponse =
        client.post("${serverUrl.require().apiBaseUrl}/quick-connect/initiate") {
            withoutDeviceAuth()
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    suspend fun redeemQuickConnect(request: QuickConnectRedeemRequest): HttpResponse =
        client.post("${serverUrl.require().apiBaseUrl}/quick-connect/redeem") {
            withoutDeviceAuth()
            contentType(ContentType.Application.Json)
            setBody(request)
        }
}
