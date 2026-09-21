package com.igloo.blindpenguincoder.core.network

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BearerTokenProviderTest {

    @Test
    fun `starts with no credential`() = runTest {
        assertNull(BearerTokenProvider().current())
    }

    @Test
    fun `serves the credential it was given`() = runTest {
        val provider = BearerTokenProvider()

        provider.set(ActiveCredential(profileId = 7, token = "igd_abc"))

        assertEquals(ActiveCredential(7, "igd_abc"), provider.current())
    }

    @Test
    fun `a later credential replaces the earlier one`() = runTest {
        val provider = BearerTokenProvider()
        provider.set(ActiveCredential(profileId = 1, token = "igd_first"))

        provider.set(ActiveCredential(profileId = 2, token = "igd_second"))

        assertEquals(ActiveCredential(2, "igd_second"), provider.current())
    }

    @Test
    fun `clearing leaves nothing to attach`() = runTest {
        val provider = BearerTokenProvider()
        provider.set(ActiveCredential(profileId = 1, token = "igd_abc"))

        provider.set(null)

        assertNull(provider.current())
    }
}
