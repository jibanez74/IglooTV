package com.igloo.blindpenguincoder.core.image

import com.igloo.blindpenguincoder.core.network.isIglooServerUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IglooImageLoaderTest {

    private val origin = "http://igloo.test:8080"

    /**
     * An album cover the server downloaded reaches the UI as its `/api/static` path; resolved,
     * it is same-origin, so the bearer interceptor that runs next attaches the token.
     */
    @Test
    fun `a server path is loaded from the server's origin`() {
        val url = resolveServerPath("/api/static/albums/4aawyAB9/daylight_cover.jpg?v=1a2b3c4d", origin)

        assertEquals("$origin/api/static/albums/4aawyAB9/daylight_cover.jpg?v=1a2b3c4d", url)
        assertTrue(isIglooServerUrl(url, origin))
    }

    @Test
    fun `absolute urls and other data are left alone`() {
        assertNull(resolveServerPath("https://i.scdn.co/image/ab67616d0000b273", origin))
        assertNull(resolveServerPath(42, origin))
        assertNull(resolveServerPath(null, origin))
    }

    @Test
    fun `without a server there is nothing to resolve against`() {
        assertNull(resolveServerPath("/api/static/albums/a/b_cover.jpg", null))
    }
}
