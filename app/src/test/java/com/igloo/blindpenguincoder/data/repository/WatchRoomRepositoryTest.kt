package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.core.error.ApiResult
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchRoomRepositoryTest {

    @Test
    fun `watch rooms hits the contract path with the bearer token and no query`() = runTest {
        var request: HttpRequestData? = null
        val http = TestHttp {
            request = it
            jsonResponse(watchRoomsJson())
        }
        http.profiles.setPending("igd_test")

        val result = http.watchRoomRepository.watchRooms()

        val captured = requireNotNull(request)
        assertEquals("/api/watch-rooms", captured.url.encodedPath)
        assertTrue(captured.url.parameters.isEmpty())
        assertEquals("Bearer igd_test", captured.headers[HttpHeaders.Authorization])
        assertEquals(emptyList<Any>(), (result as ApiResult.Success).value)
    }

    @Test
    fun `a room decodes its movie, its owner and its members in order`() = runTest {
        val maria = userSummaryJson(id = 1, name = "Maria", avatar = "/api/static/avatars/1.jpg")
        val jorge = userSummaryJson(id = 2, name = "Jorge", avatar = null)
        val http = TestHttp {
            jsonResponse(
                watchRoomsJson(
                    watchRoomJson(id = 7, movieTitle = "Heat", owner = maria, members = listOf(maria, jorge)),
                    // A movie the scanner never matched has no poster.
                    watchRoomJson(id = 8, movieTitle = "Unmatched", moviePoster = null, isOwner = false),
                ),
            )
        }

        val rooms = (http.watchRoomRepository.watchRooms() as ApiResult.Success).value

        assertEquals(listOf(7L, 8L), rooms.map { it.id })
        val first = rooms.first()
        assertEquals("Heat", first.movieTitle)
        assertEquals("/heat.jpg", first.moviePoster)
        assertEquals("Maria", first.owner.name)
        assertEquals(listOf("Maria", "Jorge"), first.members.map { it.name })
        assertEquals("/api/static/avatars/1.jpg", first.members.first().avatar)
        assertNull(first.members.last().avatar)
        assertTrue(first.isOwner)
        assertNull(rooms.last().moviePoster)
        assertFalse(rooms.last().isOwner)
    }
}
