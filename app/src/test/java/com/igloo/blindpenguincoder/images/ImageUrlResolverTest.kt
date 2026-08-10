package com.igloo.blindpenguincoder.images

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageUrlResolverTest {

    private val apiBase = "http://igloo.test:8080/api"

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
}
