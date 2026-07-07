package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRepositoryTest {

    private fun repo(http: TestHttp) = AuthRepository(http.api, http.cookiesStorage)

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
        http.cookiesStorage.addCookie(
            io.ktor.http.Url("$TEST_SERVER/auth/login"),
            io.ktor.http.Cookie(name = "session", value = "abc123", path = "/"),
        )

        repo.logout()

        assertNull(http.cookieStore.stored)
    }
}
