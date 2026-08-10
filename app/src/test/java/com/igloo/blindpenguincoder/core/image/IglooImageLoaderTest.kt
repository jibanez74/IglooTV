package com.igloo.blindpenguincoder.core.image

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bearer token may only ever accompany a request to the active Igloo server. */
class IglooImageLoaderTest {

    private val origin = "http://igloo.test:8080"

    @Test
    fun `same-origin image URLs are recognized`() {
        assertTrue(isIglooImageUrl("$origin/api/tmdb/images/w500/abc.jpg", origin))
    }

    @Test
    fun `foreign hosts never get the token`() {
        assertFalse(isIglooImageUrl("https://avatars.example.com/jose.png", origin))
        // A hostname that merely starts with the origin's text must not pass.
        assertFalse(isIglooImageUrl("http://igloo.test:8080.evil.com/x.jpg", origin))
    }

    @Test
    fun `missing url or unconfigured server attaches nothing`() {
        assertFalse(isIglooImageUrl(null, origin))
        assertFalse(isIglooImageUrl("$origin/api/x.jpg", null))
    }
}
