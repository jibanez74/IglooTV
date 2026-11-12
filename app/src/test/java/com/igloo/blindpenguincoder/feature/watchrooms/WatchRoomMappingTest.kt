package com.igloo.blindpenguincoder.feature.watchrooms

import com.igloo.blindpenguincoder.data.model.UserSummary
import com.igloo.blindpenguincoder.data.model.WatchRoomListItem
import com.igloo.blindpenguincoder.data.repository.testServerAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchRoomMappingTest {
    private val server = testServerAddress()
    private val maria = UserSummary(id = 1, name = "Maria", avatar = null)

    private fun room(
        moviePoster: String? = "/heat.jpg",
        owner: UserSummary = maria,
        members: List<UserSummary> = listOf(maria),
        isOwner: Boolean = false,
    ) = WatchRoomListItem(
        id = 7,
        movieTitle = "Heat",
        moviePoster = moviePoster,
        owner = owner,
        members = members,
        isOwner = isOwner,
    )

    private fun members(count: Int) =
        (1..count).map { UserSummary(id = it.toLong(), name = "User $it", avatar = null) }

    @Test
    fun `a TMDB poster path goes through the image proxy at the library's size`() {
        assertEquals(
            "http://igloo.test:8080/api/tmdb/images/w500/heat.jpg",
            room().toWatchRoomUi(server).posterUrl,
        )
    }

    @Test
    fun `the server's downloaded poster resolves against the origin and none stays null`() {
        assertEquals(
            "http://igloo.test:8080/api/static/posters/7.jpg",
            room(moviePoster = "/api/static/posters/7.jpg").toWatchRoomUi(server).posterUrl,
        )
        assertNull(room(moviePoster = null).toWatchRoomUi(server).posterUrl)
    }

    @Test
    fun `avatars resolve against the origin, pass through when absolute, and may be absent`() {
        val ui = room(
            members = listOf(
                UserSummary(1, "Maria", "/api/static/avatars/1.jpg"),
                UserSummary(2, "Jorge", "https://cdn.example/jorge.png"),
                UserSummary(3, "Ana", null),
            ),
        ).toWatchRoomUi(server)

        assertEquals(
            listOf("http://igloo.test:8080/api/static/avatars/1.jpg", "https://cdn.example/jorge.png", null),
            ui.members.map { it.avatarUrl },
        )
    }

    @Test
    fun `the host line marks the signed-in profile's own room`() {
        assertEquals("Hosted by Maria", room().toWatchRoomUi(server).hostLine)
        assertEquals("Hosted by Maria (you)", room(isOwner = true).toWatchRoomUi(server).hostLine)
    }

    @Test
    fun `the member line counts everyone and one member is singular`() {
        assertEquals("1 member", room().toWatchRoomUi(server).memberLine)
        assertEquals("6 members", room(members = members(6)).toWatchRoomUi(server).memberLine)
    }

    @Test
    fun `the card draws the first four members and counts the rest`() {
        val four = room(members = members(4)).toWatchRoomUi(server)
        assertEquals(listOf(1L, 2L, 3L, 4L), four.members.map { it.id })
        assertEquals(0, four.overflowCount)

        val six = room(members = members(6)).toWatchRoomUi(server)
        assertEquals(listOf(1L, 2L, 3L, 4L), six.members.map { it.id })
        assertEquals(2, six.overflowCount)
    }

    @Test
    fun `the spoken sentence carries the title, the host, the count and the names`() {
        assertEquals(
            "Heat, hosted by Maria, 2 members: Maria, Jorge",
            room(members = listOf(maria, UserSummary(2, "Jorge", null))).toWatchRoomUi(server).description,
        )
        assertEquals(
            "Heat, hosted by you, 6 members: User 1, User 2, User 3, User 4 and 2 more",
            room(members = members(6), isOwner = true).toWatchRoomUi(server).description,
        )
    }
}
