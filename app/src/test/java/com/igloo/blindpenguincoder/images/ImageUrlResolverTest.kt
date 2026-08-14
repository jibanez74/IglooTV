package com.igloo.blindpenguincoder.images

import com.igloo.blindpenguincoder.core.image.isIglooImageUrl
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
            listOf("w92", "w185", "w500", "w1280", "original"),
            TmdbImageSize.entries.map { it.segment },
        )
        assertEquals(
            "http://igloo.test:8080/api/tmdb/images/original/abc.jpg",
            tmdbImageUrl(apiBase, TmdbImageSize.Original, "abc.jpg"),
        )
    }

    @Test
    fun `uploaded avatar paths are resolved against the origin`() {
        assertEquals(
            "$origin/api/static/avatars/7-1735689600.jpg",
            avatarImageUrl(origin, "/api/static/avatars/7-1735689600.jpg"),
        )
    }

    /**
     * The half that makes the other half work: `/api/static` is authenticated, so a resolved
     * avatar is only fetchable if the loader recognizes it as ours and attaches the bearer.
     */
    @Test
    fun `a resolved avatar is same-origin, so the bearer is attached`() {
        val url = avatarImageUrl(origin, "/api/static/avatars/7.jpg")

        assertTrue(isIglooImageUrl(url, origin))
    }

    @Test
    fun `absolute avatar urls pass through untouched`() {
        assertEquals(
            "https://cdn.example.com/jose.png",
            avatarImageUrl(origin, "https://cdn.example.com/jose.png"),
        )
        assertEquals(
            "http://cdn.example.com/jose.png",
            avatarImageUrl(origin, "http://cdn.example.com/jose.png"),
        )
    }

    @Test
    fun `a trailing slash on the origin does not double up`() {
        assertEquals("$origin/api/static/a.jpg", avatarImageUrl("$origin/", "/api/static/a.jpg"))
    }

    @Test
    fun `a missing avatar renders as initials`() {
        assertNull(avatarImageUrl(origin, null))
        assertNull(avatarImageUrl(origin, ""))
        assertNull(avatarImageUrl(origin, "   "))
    }

    /** No defined resolution, so it must not be guessed at by pasting onto the origin. */
    @Test
    fun `avatar values that are neither absolute nor rooted are dropped`() {
        assertNull(avatarImageUrl(origin, "avatars/7.jpg"))
        assertNull(avatarImageUrl(origin, "data:image/png;base64,AAAA"))
    }
}
