# Kotlin TV Client Authentication

This guide describes how a Kotlin TV client should authenticate with the Igloo API.

TV clients should use device bearer tokens, not browser session cookies. Igloo issues device tokens through either Quick Connect pairing or direct device login. After a token is issued, send it on authenticated API requests with:

```http
Authorization: Bearer igd_...
```

Device tokens are opaque. Store and replay the exact string returned by the API. Do not parse it.

## Recommended Flow: Quick Connect

Quick Connect is the preferred TV sign-in flow because the user does not need to type an email and password on the TV.

1. The TV starts pairing with `POST /api/quick-connect/initiate`.
2. Igloo returns a short user-visible code and a device-held secret.
3. The TV displays only the code.
4. The user approves the code in the web app while authenticated with a browser session.
5. The TV polls `POST /api/quick-connect/redeem` until the response is approved or the code expires.
6. The TV stores the returned `token` securely and uses it as a bearer token.

The initiate request is public:

```json
{
  "device_name": "Living Room TV",
  "platform": "android_tv",
  "app_version": "1.0.0"
}
```

A successful initiate response is `201 Created`:

```json
{
  "error": false,
  "data": {
    "code": "ABCD23",
    "secret": "device-held-secret",
    "expires_in_seconds": 300,
    "poll_interval_seconds": 2
  }
}
```

Display `code` to the user. Keep `secret` hidden and only in memory while pairing. The secret is required when polling redeem:

```json
{
  "code": "ABCD23",
  "secret": "device-held-secret"
}
```

While the user has not approved the code, redeem returns `200 OK` with:

```json
{
  "error": false,
  "data": {
    "status": "pending"
  }
}
```

After approval, redeem returns the token exactly once:

```json
{
  "error": false,
  "data": {
    "status": "approved",
    "token": "igd_...",
    "device": {
      "id": 12,
      "name": "Living Room TV",
      "platform": "android_tv",
      "app_version": "1.0.0",
      "created_at": "2026-07-14 12:00:00",
      "last_used_at": "2026-07-14 12:00:00",
      "is_current": true
    }
  }
}
```

If redeem returns `404 Not Found`, the code is unknown, expired, already consumed, the secret does not match, or the server restarted and lost the in-memory pairing. Stop polling and start a new Quick Connect flow.

Respect `poll_interval_seconds`; do not poll faster. If initiate or redeem returns `429 Too Many Requests`, back off before retrying. If initiate returns `503 Service Unavailable`, the server is temporarily at its pending-code capacity; retry later.

## Direct Device Login Fallback

Use direct device login only when the TV client intentionally supports entering credentials on the TV.

Send `POST /api/auth/device-login`:

```json
{
  "email": "user@example.com",
  "password": "correct horse battery staple",
  "device_name": "Living Room TV",
  "platform": "android_tv",
  "app_version": "1.0.0"
}
```

A successful response is `200 OK` and has the same `data.token` and `data.device` shape as an approved Quick Connect redeem response.

Do not store the user's password. Exchange it for a device token, store the token, then discard the password from memory.

## Authenticated Requests

Most routes that accept browser `cookieAuth` also accept device bearer tokens. The OpenAPI document is the source of truth for route-specific requirements.

Use the token on normal API calls:

```http
GET /api/auth/user
Authorization: Bearer igd_...
```

`GET /api/auth/user` is a good startup check. A `200 OK` response confirms the token is valid and returns the current user. A `401 Unauthorized` response means the token is missing, invalid, revoked, or expired after inactivity; clear the stored token and ask the user to pair again.

To sign out this TV client, call:

```http
DELETE /api/auth/logout
Authorization: Bearer igd_...
```

With bearer authentication, logout revokes only the current device token. If the TV later sends the same token again, Igloo returns `401 Unauthorized`.

Do not use a device bearer token for these browser-session-only routes:

- `POST /api/quick-connect/lookup`
- `POST /api/quick-connect/approve`
- `GET /api/devices`
- `PATCH /api/devices/{id}`
- `DELETE /api/devices/{id}`

Those routes intentionally require a web session cookie so a device token cannot approve more devices or manage the user's device list.

## Ktor Client Example

These examples use Ktor Client with JSON content negotiation. Adapt the storage implementation to the Android TV app's architecture.

```kotlin
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class IglooClient(
    private val baseUrl: String,
    private val tokenStore: DeviceTokenStore,
) {
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
            })
        }
    }

    private fun endpoint(path: String): String = baseUrl.trimEnd('/') + path

    suspend fun initiateQuickConnect(
        deviceName: String,
        appVersion: String,
    ): QuickConnectInitiateData {
        val response: ApiEnvelope<QuickConnectInitiateData> =
            client.post(endpoint("/api/quick-connect/initiate")) {
                contentType(ContentType.Application.Json)
                setBody(
                    QuickConnectInitiateRequest(
                        deviceName = deviceName,
                        platform = "android_tv",
                        appVersion = appVersion,
                    )
                )
            }.body()

        return response.requireData()
    }

    suspend fun redeemQuickConnect(code: String, secret: String): QuickConnectRedeemData {
        val response: ApiEnvelope<QuickConnectRedeemData> =
            client.post(endpoint("/api/quick-connect/redeem")) {
                contentType(ContentType.Application.Json)
                setBody(QuickConnectRedeemRequest(code = code, secret = secret))
            }.body()

        val data = response.requireData()
        if (data.status == "approved") {
            val token = data.token ?: error("approved response missing token")
            tokenStore.save(token)
        }
        return data
    }

    suspend fun deviceLogin(
        email: String,
        password: String,
        deviceName: String,
        appVersion: String,
    ): DeviceTokenData {
        val response: ApiEnvelope<DeviceTokenData> =
            client.post(endpoint("/api/auth/device-login")) {
                contentType(ContentType.Application.Json)
                setBody(
                    DeviceLoginRequest(
                        email = email,
                        password = password,
                        deviceName = deviceName,
                        platform = "android_tv",
                        appVersion = appVersion,
                    )
                )
            }.body()

        val data = response.requireData()
        tokenStore.save(data.token)
        return data
    }

    suspend fun currentUser(): AuthUser {
        val response: ApiEnvelope<AuthUserData> =
            client.get(endpoint("/api/auth/user")) {
                bearerToken()
            }.body()

        return response.requireData().user
    }

    suspend fun logoutDevice() {
        client.delete(endpoint("/api/auth/logout")) {
            bearerToken()
        }
        tokenStore.clear()
    }

    private fun HttpRequestBuilder.bearerToken() {
        header(HttpHeaders.Authorization, "Bearer ${tokenStore.requireToken()}")
    }
}

interface DeviceTokenStore {
    fun save(token: String)
    fun requireToken(): String
    fun clear()
}

private fun <T> ApiEnvelope<T>.requireData(): T {
    if (error) {
        error(message ?: "Igloo API returned an error")
    }
    return data ?: error("Igloo API response missing data")
}
```

DTOs:

```kotlin
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ApiEnvelope<T>(
    val error: Boolean,
    val message: String? = null,
    val data: T? = null,
)

@Serializable
data class QuickConnectInitiateRequest(
    @SerialName("device_name")
    val deviceName: String,
    val platform: String,
    @SerialName("app_version")
    val appVersion: String,
)

@Serializable
data class QuickConnectInitiateData(
    val code: String,
    val secret: String,
    @SerialName("expires_in_seconds")
    val expiresInSeconds: Int,
    @SerialName("poll_interval_seconds")
    val pollIntervalSeconds: Int,
)

@Serializable
data class QuickConnectRedeemRequest(
    val code: String,
    val secret: String,
)

@Serializable
data class QuickConnectRedeemData(
    val status: String,
    val token: String? = null,
    val device: Device? = null,
)

@Serializable
data class DeviceLoginRequest(
    val email: String,
    val password: String,
    @SerialName("device_name")
    val deviceName: String,
    val platform: String,
    @SerialName("app_version")
    val appVersion: String,
)

@Serializable
data class DeviceTokenData(
    val token: String,
    val device: Device,
)

@Serializable
data class Device(
    val id: Long,
    val name: String,
    val platform: String,
    @SerialName("app_version")
    val appVersion: String? = null,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("last_used_at")
    val lastUsedAt: String,
    @SerialName("is_current")
    val isCurrent: Boolean,
)

@Serializable
data class AuthUserData(
    val user: AuthUser,
)

@Serializable
data class AuthUser(
    val id: Long,
    val name: String,
    val email: String,
    @SerialName("is_admin")
    val isAdmin: Boolean,
    val avatar: String? = null,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String,
)
```

## Token Storage and Recovery

Store the device token in secure local app storage, such as Android Keystore-backed storage or an equivalent encrypted store. Do not write the token to logs, crash reports, analytics events, or screenshots.

Igloo has no device-token refresh endpoint. Tokens remain valid until they are revoked, logged out, deleted from the device list by a browser user, or unused for 90 days. On any authenticated request that returns `401 Unauthorized`, clear the stored token and start Quick Connect again.

Quick Connect pairings are held in server memory and expire quickly. The TV should keep the pairing screen resilient:

- Start a new pairing when the code expires.
- Start over on redeem `404`.
- Back off on `429`.
- Show a retry state on network failures.
- Never show the `secret` to the user.
