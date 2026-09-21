package com.igloo.blindpenguincoder.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlTest {

    @Test
    fun `defaults a missing scheme to http and computes the api base`() {
        assertAddress(
            raw = "igloo.local:8080",
            origin = "http://igloo.local:8080",
            apiBaseUrl = "http://igloo.local:8080/api",
            effectivePort = 8080,
        )
    }

    @Test
    fun `normalizes schemes hosts root slashes and default ports`() {
        assertAddress(" HTTP://IGLOO.Example:80/ ", "http://igloo.example", 80)
        assertAddress("HTTPS://IGLOO.Example:443", "https://igloo.example", 443)
        assertAddress("https://IGLOO.Example:8443/", "https://igloo.example:8443", 8443)
    }

    @Test
    fun `accepts documented api suffixes without duplicating the api path`() {
        assertAddress(
            "http://192.168.1.50:8080/api",
            "http://192.168.1.50:8080",
            8080,
        )
        assertAddress(
            "http://100.100.100.100:8080/api/",
            "http://100.100.100.100:8080",
            8080,
        )
        assertAddress("http://igloo.local:8080/api", "http://igloo.local:8080", 8080)
        assertAddress("https://example.com/api", "https://example.com", 443)
    }

    @Test
    fun `accepts hostnames ipv4 localhost and bracketed ipv6`() {
        assertAddress("shield.tail1234.ts.net:8080", "http://shield.tail1234.ts.net:8080", 8080)
        assertAddress("192.168.1.50", "http://192.168.1.50", 80)
        assertAddress("localhost:3001", "http://localhost:3001", 3001)
        assertAddress("http://[::1]:8080", "http://[::1]:8080", 8080)
        assertAddress("HTTPS://[2001:DB8::1]", "https://[2001:db8::1]", 443)
    }

    @Test
    fun `api base can be restored as a normalized address`() {
        val restored = ServerAddress.fromApiBaseUrl("http://IGLOO.local:80/api/")

        assertEquals("http://igloo.local", restored?.origin)
        assertEquals("http://igloo.local/api", restored?.apiBaseUrl)
    }

    @Test
    fun `rejects empty malformed and unsupported addresses`() {
        assertInvalid("")
        assertInvalid("   ")
        assertInvalid("http://")
        assertInvalid("http://:8080")
        assertInvalid("not a host name")
        assertInvalid("ftp://igloo.local")
        assertInvalid("file:///etc/passwd")
        assertInvalid("http://999.1.1.1")
        assertInvalid("http://::1")
    }

    @Test
    fun `rejects paths other than the documented api suffix`() {
        listOf(
            "http://igloo.local/API",
            "http://igloo.local/media",
            "http://igloo.local///",
            "http://igloo.local/%2F",
        ).forEach(::assertInvalid)
    }

    @Test
    fun `rejects credentials queries and fragments`() {
        assertInvalid("http://user@igloo.local")
        assertInvalid("http://user:secret@igloo.local")
        assertInvalid("http://igloo.local?mode=test")
        assertInvalid("http://igloo.local/#setup")
    }

    @Test
    fun `rejects invalid explicit ports`() {
        assertInvalid("http://igloo.local:0")
        assertInvalid("http://igloo.local:65536")
        assertInvalid("http://igloo.local:not-a-port")
        assertInvalid("http://igloo.local:")
    }

    private fun assertAddress(raw: String, origin: String, effectivePort: Int) {
        assertAddress(raw, origin, "$origin/api", effectivePort)
    }

    private fun assertAddress(
        raw: String,
        origin: String,
        apiBaseUrl: String,
        effectivePort: Int,
    ) {
        val address = (parseServerAddress(raw) as ServerAddressParseResult.Valid).address
        assertEquals(origin, address.origin)
        assertEquals(apiBaseUrl, address.apiBaseUrl)
        assertEquals(effectivePort, address.effectivePort)
    }

    private fun assertInvalid(raw: String) {
        assertTrue("Expected '$raw' to be invalid", parseServerAddress(raw) is ServerAddressParseResult.Invalid)
    }
}
