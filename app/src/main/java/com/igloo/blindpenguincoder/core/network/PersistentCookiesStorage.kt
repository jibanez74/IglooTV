package com.igloo.blindpenguincoder.core.network

import com.igloo.blindpenguincoder.core.storage.SessionCookieStore
import com.igloo.blindpenguincoder.core.storage.StoredCookie
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.http.Cookie
import io.ktor.http.Url
import io.ktor.util.date.GMTDate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Persists the single Igloo session cookie across app restarts. The Ktor
 * HttpCookies plugin calls [get] on every request, so reads are served from
 * an in-memory cache after the first DataStore load.
 */
class PersistentCookiesStorage(private val store: SessionCookieStore) : CookiesStorage {

    private val mutex = Mutex()
    private var loaded = false
    private var cached: StoredCookie? = null

    override suspend fun get(requestUrl: Url): List<Cookie> = mutex.withLock {
        loadIfNeeded()
        val cookie = cached ?: return@withLock emptyList()
        if (!cookie.host.equals(requestUrl.host, ignoreCase = true)) return@withLock emptyList()
        val expires = cookie.expiresEpochMillis
        if (expires != null && expires <= System.currentTimeMillis()) {
            cached = null
            store.clear()
            return@withLock emptyList()
        }
        listOf(cookie.toKtorCookie())
    }

    override suspend fun addCookie(requestUrl: Url, cookie: Cookie) = mutex.withLock {
        loadIfNeeded()
        val stored = StoredCookie(
            name = cookie.name,
            value = cookie.value,
            host = requestUrl.host,
            path = cookie.path ?: "/",
            expiresEpochMillis = cookie.expires?.timestamp,
            secure = cookie.secure,
            httpOnly = cookie.httpOnly,
        )
        cached = stored
        store.write(stored)
    }

    override fun close() {}

    suspend fun clear() = mutex.withLock {
        loaded = true
        cached = null
        store.clear()
    }

    private suspend fun loadIfNeeded() {
        if (!loaded) {
            cached = store.read()
            loaded = true
        }
    }

    private fun StoredCookie.toKtorCookie() = Cookie(
        name = name,
        value = value,
        path = path,
        expires = expiresEpochMillis?.let { GMTDate(it) },
        secure = secure,
        httpOnly = httpOnly,
    )
}
