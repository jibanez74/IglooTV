package com.igloo.blindpenguincoder.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.AttributeKey
import kotlinx.serialization.json.Json

val IglooJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/** Marks a credential-issuing request, which must never carry a stale token. */
private val NoDeviceAuthAttribute = AttributeKey<Unit>("IglooNoDeviceAuth")

/** Sends a specific token instead of the active one, to revoke a superseded session. */
private val BearerOverrideAttribute = AttributeKey<String>("IglooBearerOverride")

/**
 * Marks a request whose own 401 is not a lost session: it is discarding the very credential it
 * carries, so "already gone" is the outcome it asked for.
 */
val ExpectedUnauthorizedAttribute = AttributeKey<Unit>("IglooExpectedUnauthorized")

private val AttachedCredentialAttribute = AttributeKey<ActiveCredential>("IglooAttachedCredential")

fun HttpRequestBuilder.withoutDeviceAuth() {
    attributes.put(NoDeviceAuthAttribute, Unit)
}

fun HttpRequestBuilder.withBearerOverride(token: String) {
    attributes.put(BearerOverrideAttribute, token)
}

fun HttpRequestBuilder.expectingUnauthorized() {
    attributes.put(ExpectedUnauthorizedAttribute, Unit)
}

/**
 * Attaches the active bearer token to every request that did not opt out, and reports
 * a rejected credential to [events] so any endpoint's 401 — not just the auth ones —
 * can return the user to sign-in.
 */
fun deviceTokenAuth(credentials: DeviceCredentialSource, events: AuthEventBus) =
    createClientPlugin("DeviceTokenAuth") {
        onRequest { request, _ ->
            if (request.attributes.contains(NoDeviceAuthAttribute)) return@onRequest
            val credential = request.attributes.getOrNull(BearerOverrideAttribute)
                ?.let { ActiveCredential(profileId = null, token = it) }
                ?: credentials.current()
                ?: return@onRequest
            request.attributes.put(AttachedCredentialAttribute, credential)
            request.headers.append(HttpHeaders.Authorization, "Bearer ${credential.token}")
        }
        onResponse { response ->
            if (response.status != HttpStatusCode.Unauthorized) return@onResponse
            val attributes = response.call.request.attributes
            // A request that set out to discard its own credential asked for "already gone", so
            // its 401 is the outcome, not a lost session. Suppressed here at the source rather
            // than filtered downstream: the event bus keeps one slot and drops the oldest, so a
            // spurious signal would evict a genuine 401 from a concurrent request.
            if (attributes.contains(ExpectedUnauthorizedAttribute)) return@onResponse
            val attached = attributes.getOrNull(AttachedCredentialAttribute) ?: return@onResponse
            events.signalUnauthorized(attached.profileId)
        }
    }

fun createIglooHttpClient(
    credentials: DeviceCredentialSource,
    authEvents: AuthEventBus,
    engine: HttpClientEngine = OkHttp.create(),
): HttpClient = HttpClient(engine) {
    expectSuccess = false
    install(ContentNegotiation) {
        json(IglooJson)
    }
    install(deviceTokenAuth(credentials, authEvents))
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
