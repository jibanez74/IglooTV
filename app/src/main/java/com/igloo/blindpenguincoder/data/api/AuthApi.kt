package com.igloo.blindpenguincoder.data.api

import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import com.igloo.blindpenguincoder.data.model.DeviceLoginRequest
import com.igloo.blindpenguincoder.data.model.LoginRequest
import com.igloo.blindpenguincoder.data.model.QuickConnectApproveRequest
import com.igloo.blindpenguincoder.data.model.QuickConnectInitiateRequest
import com.igloo.blindpenguincoder.data.model.QuickConnectRedeemRequest
import com.igloo.blindpenguincoder.data.model.RenameDeviceRequest
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType

class AuthApi(
    private val client: HttpClient,
    private val serverUrl: ServerUrlProvider,
) {
    suspend fun login(request: LoginRequest): HttpResponse =
        client.post("${serverUrl.require()}/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    suspend fun deviceLogin(request: DeviceLoginRequest): HttpResponse =
        client.post("${serverUrl.require()}/auth/device-login") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    suspend fun currentUser(): HttpResponse =
        client.get("${serverUrl.require()}/auth/user")

    suspend fun logout(): HttpResponse =
        client.delete("${serverUrl.require()}/auth/logout")

    suspend fun initiateQuickConnect(request: QuickConnectInitiateRequest): HttpResponse =
        client.post("${serverUrl.require()}/quick-connect/initiate") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    suspend fun redeemQuickConnect(request: QuickConnectRedeemRequest): HttpResponse =
        client.post("${serverUrl.require()}/quick-connect/redeem") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    suspend fun approveQuickConnect(request: QuickConnectApproveRequest): HttpResponse =
        client.post("${serverUrl.require()}/quick-connect/approve") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    suspend fun devices(bearerToken: String? = null): HttpResponse =
        client.get("${serverUrl.require()}/devices") {
            bearerToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }

    suspend fun renameDevice(
        id: Long,
        request: RenameDeviceRequest,
        bearerToken: String? = null,
    ): HttpResponse =
        client.patch("${serverUrl.require()}/devices/$id") {
            bearerToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    suspend fun revokeDevice(id: Long, bearerToken: String? = null): HttpResponse =
        client.delete("${serverUrl.require()}/devices/$id") {
            bearerToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }

    /** Health check against a candidate base URL, before it is saved. */
    suspend fun health(baseUrl: String): HttpResponse =
        client.get("$baseUrl/health")
}
