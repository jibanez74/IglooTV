package com.igloo.blindpenguincoder.core.network

import com.igloo.blindpenguincoder.core.storage.StoredCookie
import io.ktor.http.Cookie
import io.ktor.http.Url
import io.ktor.util.date.GMTDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentCookiesStorageTest {

    private val serverUrl = Url("http://igloo.local:8080/api/auth/login")

    private fun sessionCookie(expires: GMTDate? = null) = Cookie(
        name = "session",
        value = "secret-token",
        path = "/",
        expires = expires,
        httpOnly = true,
    )

    @Test
    fun `persists cookie and returns it for the same host`() = runTest {
        val store = FakeSessionCookieStore()
        val storage = PersistentCookiesStorage(store)

        storage.addCookie(serverUrl, sessionCookie())

        val cookies = storage.get(Url("http://igloo.local:8080/api/auth/user"))
        assertEquals(1, cookies.size)
        assertEquals("session", cookies.first().name)
        assertEquals("secret-token", cookies.first().value)
        assertNotNull(store.stored)
        assertEquals("igloo.local", store.stored?.host)
    }

    @Test
    fun `does not return cookie for a different host`() = runTest {
        val store = FakeSessionCookieStore()
        val storage = PersistentCookiesStorage(store)

        storage.addCookie(serverUrl, sessionCookie())

        assertTrue(storage.get(Url("http://other.host:8080/api")).isEmpty())
    }

    @Test
    fun `loads previously persisted cookie from the store`() = runTest {
        val store = FakeSessionCookieStore(
            StoredCookie(
                name = "session",
                value = "persisted",
                host = "igloo.local",
                path = "/",
                expiresEpochMillis = null,
                secure = false,
                httpOnly = true,
            ),
        )
        val storage = PersistentCookiesStorage(store)

        val cookies = storage.get(serverUrl)
        assertEquals("persisted", cookies.single().value)
    }

    @Test
    fun `expired cookie is dropped and cleared from the store`() = runTest {
        val store = FakeSessionCookieStore()
        val storage = PersistentCookiesStorage(store)

        storage.addCookie(serverUrl, sessionCookie(expires = GMTDate(1L)))

        assertTrue(storage.get(serverUrl).isEmpty())
        assertNull(store.stored)
    }

    @Test
    fun `new cookie overwrites the previous one`() = runTest {
        val store = FakeSessionCookieStore()
        val storage = PersistentCookiesStorage(store)

        storage.addCookie(serverUrl, sessionCookie())
        storage.addCookie(serverUrl, Cookie(name = "session", value = "renewed", path = "/"))

        assertEquals("renewed", storage.get(serverUrl).single().value)
        assertEquals("renewed", store.stored?.value)
    }

    @Test
    fun `clear removes cookie from memory and store`() = runTest {
        val store = FakeSessionCookieStore()
        val storage = PersistentCookiesStorage(store)

        storage.addCookie(serverUrl, sessionCookie())
        storage.clear()

        assertTrue(storage.get(serverUrl).isEmpty())
        assertNull(store.stored)
    }

    @Test
    fun `stored cookie toString never exposes the value`() {
        val cookie = StoredCookie(
            name = "session",
            value = "super-secret",
            host = "igloo.local",
            path = "/",
            expiresEpochMillis = null,
            secure = false,
            httpOnly = true,
        )
        assertTrue("super-secret" !in cookie.toString())
    }
}
