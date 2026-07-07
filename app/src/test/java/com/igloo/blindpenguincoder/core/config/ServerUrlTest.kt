package com.igloo.blindpenguincoder.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerUrlTest {

    @Test
    fun `bare host gets http scheme and api path`() {
        assertEquals("http://192.168.1.5/api", normalizeServerUrl("192.168.1.5"))
    }

    @Test
    fun `bare host with port gets api path`() {
        assertEquals("http://10.0.2.2:8080/api", normalizeServerUrl("10.0.2.2:8080"))
    }

    @Test
    fun `explicit path is kept as-is`() {
        assertEquals("http://10.0.2.2:8080/api", normalizeServerUrl("http://10.0.2.2:8080/api"))
        assertEquals(
            "https://igloo.example.com/media",
            normalizeServerUrl("https://igloo.example.com/media"),
        )
    }

    @Test
    fun `trailing slashes are stripped`() {
        assertEquals("http://10.0.2.2:8080/api", normalizeServerUrl("http://10.0.2.2:8080/api/"))
        assertEquals("http://10.0.2.2:8080/api", normalizeServerUrl("http://10.0.2.2:8080/api///"))
    }

    @Test
    fun `root slash counts as empty path`() {
        assertEquals("http://myserver:8080/api", normalizeServerUrl("http://myserver:8080/"))
    }

    @Test
    fun `https is preserved`() {
        assertEquals("https://igloo.example.com/api", normalizeServerUrl("https://igloo.example.com"))
    }

    @Test
    fun `scheme and host are lowercased`() {
        assertEquals("https://igloo.example.com/api", normalizeServerUrl("HTTPS://Igloo.Example.COM"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("http://10.0.2.2:8080/api", normalizeServerUrl("  10.0.2.2:8080  "))
    }

    @Test
    fun `tailscale hostname works`() {
        assertEquals("http://shield.tail1234.ts.net:8080/api", normalizeServerUrl("shield.tail1234.ts.net:8080"))
    }

    @Test
    fun `rejects empty and blank input`() {
        assertNull(normalizeServerUrl(""))
        assertNull(normalizeServerUrl("   "))
    }

    @Test
    fun `rejects non-http schemes`() {
        assertNull(normalizeServerUrl("ftp://example.com"))
        assertNull(normalizeServerUrl("file:///etc/passwd"))
    }

    @Test
    fun `rejects garbage input`() {
        assertNull(normalizeServerUrl("http://"))
        assertNull(normalizeServerUrl("not a url at all"))
        assertNull(normalizeServerUrl("http://:8080"))
    }
}
