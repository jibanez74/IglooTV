package com.igloo.blindpenguincoder.data.api

import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import com.igloo.blindpenguincoder.data.model.LoginRequest
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
    suspend fun login(request: LoginRequest): HttpResponse =
        client.post("${serverUrl.require()}/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    suspend fun currentUser(): HttpResponse =
        client.get("${serverUrl.require()}/auth/user")

    suspend fun logout(): HttpResponse =
        client.delete("${serverUrl.require()}/auth/logout")

    /** Health check against a candidate base URL, before it is saved. */
    suspend fun health(baseUrl: String): HttpResponse =
        client.get("$baseUrl/health")
}
