package com.igloo.blindpenguincoder.images

import com.igloo.blindpenguincoder.core.network.isIglooServerUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageUrlResolverTest {

    private val apiBase = "http://igloo.test:8080/api"
    private val origin = "http://igloo.test:8080"

    @Test
    fun `a leading slash is stripped so the proxy gets a bare filename`() {
        assertEquals(
            "http://igloo.test:8080/api/tmdb/images/w500/abc.jpg",
            tmdbImageUrl(apiBase, TmdbImageSize.W500, "/abc.jpg"),
        )
    }

    @Test
    fun `a bare path is used as is`() {
        assertEquals(
            "http://igloo.test:8080/api/tmdb/images/w500/abc.jpg",
            tmdbImageUrl(apiBase, TmdbImageSize.W500, "abc.jpg"),
        )
    }

    @Test
    fun `null and blank paths resolve to no URL`() {
        assertNull(tmdbImageUrl(apiBase, TmdbImageSize.W500, null))
        assertNull(tmdbImageUrl(apiBase, TmdbImageSize.W500, ""))
        assertNull(tmdbImageUrl(apiBase, TmdbImageSize.W500, "/"))
    }

    @Test
    fun `each size maps to its route segment`() {
        assertEquals(
            listOf("w185", "w500", "w1280"),
            TmdbImageSize.entries.map { it.segment },
        )
        assertEquals(
            "http://igloo.test:8080/api/tmdb/images/w1280/abc.jpg",
            tmdbImageUrl(apiBase, TmdbImageSize.W1280, "abc.jpg"),
        )
    }

    /**
     * With the server's Download images setting on, a TMDB field holds the server's own copy,
     * stored at one size; it loads from the origin, where the bearer is attached.
     */
    @Test
    fun `a downloaded copy is loaded from the server as it is`() {
        val url = tmdbImageUrl(apiBase, TmdbImageSize.W500, "/api/static/movies/603/the_matrix_poster.jpg?v=1a2b3c4d")

        assertEquals("$origin/api/static/movies/603/the_matrix_poster.jpg?v=1a2b3c4d", url)
        assertTrue(isIglooServerUrl(url, origin))
    }

    @Test
    fun `youtube thumbnails go through the authenticated proxy`() {
        val url = youtubeThumbnailUrl(apiBase, "0xbkYZbdIVw")

        assertEquals("http://igloo.test:8080/api/youtube/thumbnails/0xbkYZbdIVw", url)
        assertTrue(isIglooServerUrl(url, origin))
    }

    @Test
    fun `a null or blank youtube key resolves to no URL`() {
        assertNull(youtubeThumbnailUrl(apiBase, null))
        assertNull(youtubeThumbnailUrl(apiBase, ""))
        assertNull(youtubeThumbnailUrl(apiBase, "   "))
    }

    @Test
    fun `uploaded avatar paths are resolved against the origin`() {
        assertEquals(
            "$origin/api/static/avatars/7-1735689600.jpg",
            serverImageUrl(origin, "/api/static/avatars/7-1735689600.jpg"),
        )
    }

    /**
     * The half that makes the other half work: `/api/static` is authenticated, so a resolved
     * avatar is only fetchable if the loader recognizes it as ours and attaches the bearer.
     */
    @Test
    fun `a resolved avatar is same-origin, so the bearer is attached`() {
        val url = serverImageUrl(origin, "/api/static/avatars/7.jpg")

        assertTrue(isIglooServerUrl(url, origin))
    }

    @Test
    fun `absolute avatar urls pass through untouched`() {
        assertEquals(
            "https://cdn.example.com/jose.png",
            serverImageUrl(origin, "https://cdn.example.com/jose.png"),
        )
        assertEquals(
            "http://cdn.example.com/jose.png",
            serverImageUrl(origin, "http://cdn.example.com/jose.png"),
        )
    }

    @Test
    fun `music covers are a provider url or the server's own copy`() {
        assertEquals(
            "https://i.scdn.co/image/ab67616d0000b273",
            serverImageUrl(origin, "https://i.scdn.co/image/ab67616d0000b273"),
        )
        assertEquals(
            "$origin/api/static/albums/1440746112/blackbird_cover.jpg?v=af7150b0",
            serverImageUrl(origin, "/api/static/albums/1440746112/blackbird_cover.jpg?v=af7150b0"),
        )
    }

    @Test
    fun `a trailing slash on the origin does not double up`() {
        assertEquals("$origin/api/static/a.jpg", serverImageUrl("$origin/", "/api/static/a.jpg"))
    }

    @Test
    fun `a missing avatar renders as initials`() {
        assertNull(serverImageUrl(origin, null))
        assertNull(serverImageUrl(origin, ""))
        assertNull(serverImageUrl(origin, "   "))
    }

    /** No defined resolution, so it must not be guessed at by pasting onto the origin. */
    @Test
    fun `avatar values that are neither absolute nor rooted are dropped`() {
        assertNull(serverImageUrl(origin, "avatars/7.jpg"))
        assertNull(serverImageUrl(origin, "data:image/png;base64,AAAA"))
    }
}
