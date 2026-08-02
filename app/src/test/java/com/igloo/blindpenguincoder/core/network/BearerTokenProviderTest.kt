package com.igloo.blindpenguincoder.core.network

import com.igloo.blindpenguincoder.core.storage.FakeDeviceTokenStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BearerTokenProviderTest {

    @Test
    fun `serves the stored token`() = runTest {
        val provider = BearerTokenProvider(FakeDeviceTokenStore(stored = "igd_abc"))

        assertEquals("igd_abc", provider.token())
    }

    @Test
    fun `caches after the first read`() = runTest {
        val store = FakeDeviceTokenStore(stored = "igd_abc")
        val provider = BearerTokenProvider(store)
        provider.token()

        store.stored = "igd_changed-behind-the-cache"

        assertEquals("igd_abc", provider.token())
    }

    @Test
    fun `set updates the cache and the store`() = runTest {
        val store = FakeDeviceTokenStore()
        val provider = BearerTokenProvider(store)

        provider.set("igd_new")

        assertEquals("igd_new", provider.token())
        assertEquals("igd_new", store.stored)
    }

    @Test
    fun `clear wipes the cache and the store`() = runTest {
        val store = FakeDeviceTokenStore(stored = "igd_abc")
        val provider = BearerTokenProvider(store)
        provider.token()

        provider.clear()

        assertNull(provider.token())
        assertNull(store.stored)
    }
}
