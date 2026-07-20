package com.igloo.blindpenguincoder.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

val IglooJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

fun createIglooHttpClient(
    cookiesStorage: CookiesStorage,
    engine: HttpClientEngine = OkHttp.create(),
): HttpClient = HttpClient(engine) {
    expectSuccess = false
    install(ContentNegotiation) {
        json(IglooJson)
    }
    install(HttpCookies) {
        storage = cookiesStorage
    }
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 30_000
        socketTimeoutMillis = 30_000
    }
}

fun createServerProbeHttpClient(
    engine: HttpClientEngine = OkHttp.create {
        config {
            followRedirects(false)
            followSslRedirects(false)
        }
    },
): HttpClient = HttpClient(engine) {
    expectSuccess = false
    followRedirects = false
    install(HttpTimeout) {
        connectTimeoutMillis = SERVER_PROBE_TIMEOUT_MILLIS
        requestTimeoutMillis = SERVER_PROBE_TIMEOUT_MILLIS
        socketTimeoutMillis = SERVER_PROBE_TIMEOUT_MILLIS
    }
}

const val SERVER_PROBE_TIMEOUT_MILLIS = 10_000L
