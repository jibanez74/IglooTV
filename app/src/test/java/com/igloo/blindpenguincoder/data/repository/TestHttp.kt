package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.core.config.DeviceIdentity
import com.igloo.blindpenguincoder.core.config.ServerAddress
import com.igloo.blindpenguincoder.core.config.ServerAddressParseResult
import com.igloo.blindpenguincoder.core.config.parseServerAddress
import com.igloo.blindpenguincoder.core.network.AuthEventBus
import com.igloo.blindpenguincoder.core.network.BearerTokenProvider
import com.igloo.blindpenguincoder.core.network.ServerHealthProbe
import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import com.igloo.blindpenguincoder.core.network.createIglooHttpClient
import com.igloo.blindpenguincoder.core.network.createServerProbeHttpClient
import com.igloo.blindpenguincoder.core.storage.FakeProfileStore
import com.igloo.blindpenguincoder.core.storage.ProfileVault
import com.igloo.blindpenguincoder.core.storage.StoredProfile
import com.igloo.blindpenguincoder.data.api.AuthApi
import com.igloo.blindpenguincoder.data.api.UserApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineDispatcher

const val TEST_SERVER = "http://igloo.test:8080/api"

val testDeviceIdentity = DeviceIdentity(
    name = "Shield",
    platform = "android_tv",
    appVersion = "0.1.0",
)

class TestHttp(
    engineDispatcher: CoroutineDispatcher? = null,
    handler: MockRequestHandler,
) {
    val profileStore = FakeProfileStore()
    val credentials = BearerTokenProvider()
    val authEvents = AuthEventBus()

    /** Advanced by tests that care about last-used ordering. */
    var clockMillis = 1_000L

    val profiles = ProfileRepository(profileStore, credentials, clock = { clockMillis })
    val serverUrl = ServerUrlProvider().apply { set(testServerAddress()) }
    val client: HttpClient = createIglooHttpClient(
        credentials = credentials,
        authEvents = authEvents,
        engine = MockEngine(
            MockEngineConfig().apply {
                engineDispatcher?.let { dispatcher = it }
                addHandler(handler)
            },
        ),
    )
    val api = AuthApi(client, serverUrl)
    val userApi = UserApi(client, serverUrl)
    val authRepository = AuthRepository(api, userApi, profiles, testDeviceIdentity)

    /** Puts profiles in the vault without going through a sign-in. */
    fun seedVault(
        vararg profiles: StoredProfile,
        activeUserId: Long? = null,
        pendingToken: String? = null,
    ) {
        profileStore.vault = ProfileVault(
            activeUserId = activeUserId,
            pendingToken = pendingToken,
            profiles = profiles.toList(),
        )
    }
}

fun testStoredProfile(
    userId: Long = 1,
    name: String = "Jose",
    token: String = "igd_$userId",
    hasPin: Boolean = false,
    lastUsedAtEpochMillis: Long = userId,
) = StoredProfile(
    userId = userId,
    token = token,
    name = name,
    avatarUrl = null,
    hasPin = hasPin,
    lastUsedAtEpochMillis = lastUsedAtEpochMillis,
)

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
): HttpResponseData = respond(
    content = body,
    status = status,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
)

/** `GET /auth/user` payload; `has_pin` is required by the contract. */
fun authUserJson(
    id: Long = 1,
    name: String = "Jose",
    hasPin: Boolean = false,
): String = """
    {"error":false,"message":"user found","data":{"user":{
        "id":$id,"name":"$name","email":"${name.lowercase()}@example.com","is_admin":false,
        "avatar":{"String":"","Valid":false},"has_pin":$hasPin,
        "created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z"
    }}}
""".trimIndent()
