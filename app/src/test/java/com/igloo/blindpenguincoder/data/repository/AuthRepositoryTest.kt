package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.data.model.QuickConnectStatus
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRepositoryTest {

    private fun repo(http: TestHttp) = AuthRepository(http.api, http.tokenProvider, testDeviceIdentity)

    private val deviceTokenJson = """
        {"error":false,"data":{"token":"igd_test","device":{
            "id":9,"name":"Shield","platform":"android_tv","app_version":"0.1.0",
            "created_at":"2026-07-01T00:00:00Z",
            "last_used_at":"2026-07-01T00:01:00Z","is_current":true
        }}}
    """.trimIndent()

    @Test
    fun `device login decodes and stores the token`() = runTest {
        val http = TestHttp { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("$TEST_SERVER/auth/device-login", request.url.toString())
            jsonResponse(deviceTokenJson)
        }

        val result = repo(http).deviceLogin("jose@example.com", "hunter2")

        val data = (result as ApiResult.Success).value
        assertEquals("igd_test", data.token)
        assertEquals("Shield", data.device.name)
        assertTrue(data.device.isCurrent)
        assertEquals("igd_test", http.tokenStore.stored)
    }

    @Test
    fun `device login sends the injected device identity`() = runTest {
        var body: String? = null
        val http = TestHttp { request ->
            body = String(request.body.toByteArray())
            jsonResponse(deviceTokenJson)
        }

        repo(http).deviceLogin("jose@example.com", "hunter2")

        val sent = body!!
        assertTrue(sent.contains(""""device_name":"Shield""""))
        assertTrue(sent.contains(""""platform":"android_tv""""))
        assertTrue(sent.contains(""""app_version":"0.1.0""""))
    }

    @Test
    fun `device login with wrong password maps to Unauthorized and stores no token`() = runTest {
        val http = TestHttp {
            jsonResponse(
                body = """{"error":true,"message":"invalid credentials"}""",
                status = HttpStatusCode.Unauthorized,
            )
        }

        val result = repo(http).deviceLogin("jose@example.com", "wrong")

        assertEquals(AppError.Unauthorized, (result as ApiResult.Failure).error)
        assertNull(http.tokenStore.stored)
    }

    @Test
    fun `backend error message is preserved`() = runTest {
        val http = TestHttp {
            jsonResponse(
                body = """{"error":true,"message":"failed to parse email and password from request body"}""",
                status = HttpStatusCode.BadRequest,
            )
        }

        val result = repo(http).deviceLogin("", "")

        val error = (result as ApiResult.Failure).error as AppError.Api
        assertEquals("failed to parse email and password from request body", error.message)
        assertEquals(400, error.status)
    }

    @Test
    fun `network failure maps to Network error`() = runTest {
        val http = TestHttp { throw IOException("connection refused") }

        val result = repo(http).deviceLogin("jose@example.com", "hunter2")

        assertEquals(AppError.Network, (result as ApiResult.Failure).error)
    }

    @Test
    fun `authenticated requests carry the bearer token`() = runTest {
        val http = TestHttp { request ->
            assertEquals("$TEST_SERVER/auth/user", request.url.toString())
            assertEquals("Bearer igd_test", request.headers[HttpHeaders.Authorization])
            jsonResponse(
                body = """
                    {"error":false,"message":"user found","data":{"user":{
                        "id":1,"name":"Jose","email":"jose@example.com","is_admin":true,
                        "avatar":{"String":"","Valid":false},"created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z"
                    }}}
                """.trimIndent(),
            )
        }
        http.tokenStore.stored = "igd_test"

        val result = repo(http).fetchCurrentUser()

        val user = (result as ApiResult.Success).value
        assertEquals("Jose", user.name)
        assertTrue(user.isAdmin)
    }

    @Test
    fun `credential endpoints never carry a stored token`() = runTest {
        var requestIndex = 0
        val http = TestHttp { request ->
            requestIndex += 1
            assertNull(request.headers[HttpHeaders.Authorization])
            when {
                request.url.encodedPath.endsWith("/auth/device-login") -> jsonResponse(deviceTokenJson)
                request.url.encodedPath.endsWith("/quick-connect/initiate") -> jsonResponse(
                    body = """
                        {"error":false,"data":{
                            "code":"ABCD12","secret":"device-secret",
                            "expires_in_seconds":300,"poll_interval_seconds":2
                        }}
                    """.trimIndent(),
                    status = HttpStatusCode.Created,
                )
                request.url.encodedPath.endsWith("/quick-connect/redeem") ->
                    jsonResponse(body = """{"error":false,"data":{"status":"pending"}}""")
                else -> error("Unexpected request")
            }
        }
        http.tokenStore.stored = "igd_stale"
        val repository = repo(http)

        repository.deviceLogin("jose@example.com", "hunter2")
        repository.initiateQuickConnect()
        repository.redeemQuickConnect("ABCD12", "device-secret")

        assertEquals(3, requestIndex)
    }

    @Test
    fun `expired token on fetchCurrentUser maps to Unauthorized`() = runTest {
        val http = TestHttp {
            jsonResponse(
                body = """{"error":true,"message":"session expired"}""",
                status = HttpStatusCode.Unauthorized,
            )
        }

        val result = repo(http).fetchCurrentUser()

        assertEquals(AppError.Unauthorized, (result as ApiResult.Failure).error)
    }

    @Test
    fun `quick connect initiate and redeem use documented routes`() = runTest {
        var requestIndex = 0
        val http = TestHttp { request ->
            requestIndex += 1
            when (requestIndex) {
                1 -> {
                    assertEquals(HttpMethod.Post, request.method)
                    assertEquals("$TEST_SERVER/quick-connect/initiate", request.url.toString())
                    jsonResponse(
                        body = """
                            {"error":false,"data":{
                                "code":"ABCD12","secret":"device-secret",
                                "expires_in_seconds":600,"poll_interval_seconds":2
                            }}
                        """.trimIndent(),
                        status = HttpStatusCode.Created,
                    )
                }
                2 -> {
                    assertEquals(HttpMethod.Post, request.method)
                    assertEquals("$TEST_SERVER/quick-connect/redeem", request.url.toString())
                    jsonResponse(body = """{"error":false,"data":{"status":"pending"}}""")
                }
                else -> error("Unexpected request")
            }
        }
        val repository = repo(http)

        val initiated = repository.initiateQuickConnect()
        val redeemed = repository.redeemQuickConnect("ABCD12", "device-secret")

        assertEquals("ABCD12", (initiated as ApiResult.Success).value.code)
        assertEquals(2, initiated.value.pollIntervalSeconds)
        assertEquals(QuickConnectStatus.Pending, (redeemed as ApiResult.Success).value.status)
        assertNull(http.tokenStore.stored)
        assertEquals(2, requestIndex)
    }

    @Test
    fun `approved redeem stores the token`() = runTest {
        val http = TestHttp {
            jsonResponse(
                body = """
                    {"error":false,"data":{"status":"approved","token":"igd_paired","device":{
                        "id":9,"name":"Shield","platform":"android_tv","app_version":"0.1.0",
                        "created_at":"2026-07-01T00:00:00Z",
                        "last_used_at":"2026-07-01T00:01:00Z","is_current":true
                    }}}
                """.trimIndent(),
            )
        }

        val result = repo(http).redeemQuickConnect("ABCD12", "device-secret")

        assertEquals(QuickConnectStatus.Approved, (result as ApiResult.Success).value.status)
        assertEquals("igd_paired", http.tokenStore.stored)
    }

    @Test
    fun `logout uses DELETE with the bearer token and clears it even when the server fails`() = runTest {
        val http = TestHttp { request ->
            assertEquals(HttpMethod.Delete, request.method)
            assertEquals("$TEST_SERVER/auth/logout", request.url.toString())
            assertEquals("Bearer igd_test", request.headers[HttpHeaders.Authorization])
            jsonResponse(
                body = """{"error":true,"message":"boom"}""",
                status = HttpStatusCode.InternalServerError,
            )
        }
        http.tokenStore.stored = "igd_test"

        repo(http).logout()

        assertNull(http.tokenStore.stored)
    }
}
