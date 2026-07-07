package com.igloo.blindpenguincoder.core.network

import com.igloo.blindpenguincoder.core.storage.SessionCookieStore
import com.igloo.blindpenguincoder.core.storage.StoredCookie

class FakeSessionCookieStore(var stored: StoredCookie? = null) : SessionCookieStore {
    override suspend fun read(): StoredCookie? = stored

    override suspend fun write(cookie: StoredCookie) {
        stored = cookie
    }

    override suspend fun clear() {
        stored = null
    }
}
