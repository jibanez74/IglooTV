package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.data.model.QuickConnectStatus
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRepositoryTest {

    private fun repo(http: TestHttp) = AuthRepository(http.api, http.cookiesStorage)

    private suspend fun TestHttp.addSessionCookie() {
        cookiesStorage.addCookie(
            Url("$TEST_SERVER/auth/login"),
            Cookie(name = "session", value = "abc123", path = "/"),
        )
    }

    @Test
    fun `login success stores the session cookie`() = runTest {
        val http = TestHttp { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("$TEST_SERVER/auth/login", request.url.toString())
            jsonResponse(
                body = """{"error":false,"message":"Hello Jose, welcome to your media library!"}""",
                setCookie = "session=abc123; Path=/; HttpOnly; SameSite=Lax",
            )
        }

        val result = repo(http).login("jose@example.com", "hunter2")

        assertTrue(result is ApiResult.Success)
        assertEquals("session", http.cookieStore.stored?.name)
        assertEquals("abc123", http.cookieStore.stored?.value)
    }

    @Test
    fun `login with wrong password maps to Unauthorized`() = runTest {
        val http = TestHttp {
            jsonResponse(
                body = """{"error":true,"message":"invalid credentials"}""",
                status = HttpStatusCode.Unauthorized,
            )
        }

        val result = repo(http).login("jose@example.com", "wrong")

        assertEquals(AppError.Unauthorized, (result as ApiResult.Failure).error)
        assertNull(http.cookieStore.stored)
    }

    @Test
    fun `backend error message is preserved`() = runTest {
        val http = TestHttp {
            jsonResponse(
                body = """{"error":true,"message":"failed to parse email and password from request body"}""",
                status = HttpStatusCode.BadRequest,
            )
        }

        val result = repo(http).login("", "")

        val error = (result as ApiResult.Failure).error as AppError.Api
        assertEquals("failed to parse email and password from request body", error.message)
        assertEquals(400, error.status)
    }

    @Test
    fun `network failure maps to Network error`() = runTest {
        val http = TestHttp { throw IOException("connection refused") }

        val result = repo(http).login("jose@example.com", "hunter2")

        assertEquals(AppError.Network, (result as ApiResult.Failure).error)
    }

    @Test
    fun `device login decodes token response`() = runTest {
        val http = TestHttp { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("$TEST_SERVER/auth/device-login", request.url.toString())
            jsonResponse(
                body = """
                    {"error":false,"data":{"token":"igd_test","device":{
                        "id":9,"name":"Shield","platform":"android_tv","app_version":"0.1.0",
                        "created_at":"2026-07-01T00:00:00Z",
                        "last_used_at":"2026-07-01T00:01:00Z","is_current":true
                    }}}
                """.trimIndent(),
            )
        }

        val result = repo(http).deviceLogin(
            email = "jose@example.com",
            password = "hunter2",
            deviceName = "Shield",
            platform = "android_tv",
            appVersion = "0.1.0",
        )

        val data = (result as ApiResult.Success).value
        assertEquals("igd_test", data.token)
        assertEquals("Shield", data.device.name)
        assertTrue(data.device.isCurrent)
    }

    @Test
    fun `fetchCurrentUser decodes the user envelope`() = runTest {
        val http = TestHttp { request ->
            assertEquals("$TEST_SERVER/auth/user", request.url.toString())
            jsonResponse(
                body = """
                    {"error":false,"message":"user found","data":{"user":{
                        "id":1,"name":"Jose","email":"jose@example.com","is_admin":true,
                        "avatar":{"String":"","Valid":false},"created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z"
                    }}}
                """.trimIndent(),
            )
        }

        val result = repo(http).fetchCurrentUser()

        val user = (result as ApiResult.Success).value
        assertEquals("Jose", user.name)
        assertTrue(user.isAdmin)
    }

    @Test
    fun `expired session on fetchCurrentUser maps to Unauthorized`() = runTest {
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

        val initiated = repository.initiateQuickConnect("Shield", platform = "android_tv")
        val redeemed = repository.redeemQuickConnect("ABCD12", "device-secret")

        assertEquals("ABCD12", (initiated as ApiResult.Success).value.code)
        assertEquals(2, initiated.value.pollIntervalSeconds)
        assertEquals(QuickConnectStatus.Pending, (redeemed as ApiResult.Success).value.status)
        assertEquals(2, requestIndex)
    }

    @Test
    fun `approveQuickConnect uses the session-authenticated route`() = runTest {
        val http = TestHttp { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("$TEST_SERVER/quick-connect/approve", request.url.toString())
            jsonResponse(body = """{"error":false,"message":"approved"}""")
        }

        val result = repo(http).approveQuickConnect("ABCD12")

        assertTrue(result is ApiResult.Success)
    }

    @Test
    fun `devices are listed with a session cookie and no bearer token`() = runTest {
        val http = TestHttp { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("$TEST_SERVER/devices", request.url.toString())
            assertNull(request.headers[HttpHeaders.Authorization])
            assertTrue(request.headers[HttpHeaders.Cookie]?.contains("session=abc123") == true)
            jsonResponse(
                body = """
                    {"error":false,"data":{"devices":[{
                        "id":9,"name":"Shield","platform":"android_tv","app_version":null,
                        "created_at":"2026-07-01T00:00:00Z",
                        "last_used_at":"2026-07-01T00:01:00Z","is_current":true
                    }]}}
                """.trimIndent(),
            )
        }
        http.addSessionCookie()

        val result = repo(http).devices()

        val devices = (result as ApiResult.Success).value
        assertEquals(1, devices.size)
        assertEquals("Shield", devices.single().name)
    }

    @Test
    fun `rename and revoke use session cookies without bearer tokens`() = runTest {
        var requestIndex = 0
        val http = TestHttp { request ->
            requestIndex += 1
            assertNull(request.headers[HttpHeaders.Authorization])
            assertTrue(request.headers[HttpHeaders.Cookie]?.contains("session=abc123") == true)
            when (requestIndex) {
                1 -> {
                    assertEquals(HttpMethod.Patch, request.method)
                    assertEquals("$TEST_SERVER/devices/9", request.url.toString())
                    jsonResponse(body = """{"error":false,"message":"renamed"}""")
                }
                2 -> {
                    assertEquals(HttpMethod.Delete, request.method)
                    assertEquals("$TEST_SERVER/devices/9", request.url.toString())
                    jsonResponse(body = """{"error":false,"message":"revoked"}""")
                }
                else -> error("Unexpected request")
            }
        }
        http.addSessionCookie()
        val repository = repo(http)

        val renamed = repository.renameDevice(id = 9, name = "Living Room")
        val revoked = repository.revokeDevice(id = 9)

        assertTrue(renamed is ApiResult.Success)
        assertTrue(revoked is ApiResult.Success)
        assertEquals(2, requestIndex)
    }

    @Test
    fun `logout uses DELETE and clears the cookie even when the server fails`() = runTest {
        val http = TestHttp { request ->
            assertEquals(HttpMethod.Delete, request.method)
            assertEquals("$TEST_SERVER/auth/logout", request.url.toString())
            jsonResponse(
                body = """{"error":true,"message":"boom"}""",
                status = HttpStatusCode.InternalServerError,
            )
        }
        val repo = repo(http)
        http.addSessionCookie()

        repo.logout()

        assertNull(http.cookieStore.stored)
    }
}
