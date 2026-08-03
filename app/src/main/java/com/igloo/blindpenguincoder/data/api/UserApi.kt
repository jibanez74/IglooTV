package com.igloo.blindpenguincoder.data.api

import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import com.igloo.blindpenguincoder.data.model.VerifyUserPinRequest
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType

class UserApi(
    private val client: HttpClient,
    private val serverUrl: ServerUrlProvider,
) {
    /** Verifies the active profile's own PIN; the device token authenticates the call. */
    suspend fun verifyPin(request: VerifyUserPinRequest): HttpResponse =
        client.post("${serverUrl.require().apiBaseUrl}/user/pin/verify") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
}
