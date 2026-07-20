package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.core.config.ServerAddress
import com.igloo.blindpenguincoder.core.config.ServerAddressParseResult
import com.igloo.blindpenguincoder.core.config.parseServerAddress
import com.igloo.blindpenguincoder.core.network.FakeSessionCookieStore
import com.igloo.blindpenguincoder.core.network.PersistentCookiesStorage
import com.igloo.blindpenguincoder.core.network.ServerHealthProbe
import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import com.igloo.blindpenguincoder.core.network.createIglooHttpClient
import com.igloo.blindpenguincoder.core.network.createServerProbeHttpClient
import com.igloo.blindpenguincoder.data.api.AuthApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

const val TEST_SERVER = "http://igloo.test:8080/api"

class TestHttp(handler: MockRequestHandler) {
    val cookieStore = FakeSessionCookieStore()
    val cookiesStorage = PersistentCookiesStorage(cookieStore)
    val serverUrl = ServerUrlProvider().apply { set(testServerAddress()) }
    val client: HttpClient = createIglooHttpClient(
        cookiesStorage = cookiesStorage,
        engine = MockEngine(handler),
    )
    val api = AuthApi(client, serverUrl)
}

fun testServerAddress(origin: String = "http://igloo.test:8080"): ServerAddress =
    (parseServerAddress(origin) as ServerAddressParseResult.Valid).address

fun testServerHealthProbe(
    handler: MockRequestHandler,
    timeoutMillis: Long = 10_000,
): ServerHealthProbe = ServerHealthProbe(
    client = createServerProbeHttpClient(MockEngine(handler)),
    timeoutMillis = timeoutMillis,
)

fun MockRequestHandleScope.jsonResponse(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
    setCookie: String? = null,
): HttpResponseData {
    val headers = if (setCookie != null) {
        headersOf(
            HttpHeaders.ContentType to listOf("application/json"),
            HttpHeaders.SetCookie to listOf(setCookie),
        )
    } else {
        headersOf(HttpHeaders.ContentType, "application/json")
    }
    return respond(content = body, status = status, headers = headers)
}
