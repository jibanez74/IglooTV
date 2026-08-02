package com.igloo.blindpenguincoder.feature.auth

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class QuickConnectApprovalTest {

    @Test
    fun `builds account approval URLs from HTTP HTTPS ports and IPv6 origins`() {
        val cases = mapOf(
            "http://igloo.local/api" to "http://igloo.local/settings/account",
            "https://example.com/api" to "https://example.com/settings/account",
            "http://192.168.1.50:8080/api/" to
                "http://192.168.1.50:8080/settings/account",
            "https://[2001:DB8::1]:8443/api" to
                "https://[2001:db8::1]:8443/settings/account",
        )

        cases.forEach { (serverUrl, expected) ->
            val actual = buildQuickConnectApprovalUrl(serverUrl)

            assertEquals(expected, actual)
            assertFalse(URI(actual).rawPath.startsWith("/api"))
        }
    }

    @Test
    fun `display wrapping does not change the approval URL`() {
        val url = "http://[2001:db8::1]:8080/settings/account"

        val displayed = wrapApprovalUrlForDisplay(url)

        assertEquals(url, displayed.replace("\u200B", ""))
    }
}
