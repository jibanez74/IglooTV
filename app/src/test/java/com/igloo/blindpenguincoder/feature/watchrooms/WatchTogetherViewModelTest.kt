package com.igloo.blindpenguincoder.feature.watchrooms

import com.igloo.blindpenguincoder.MainDispatcherRule
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.data.model.PlaybackMode
import com.igloo.blindpenguincoder.data.repository.TestHttp
import com.igloo.blindpenguincoder.data.repository.createWatchRoomJson
import com.igloo.blindpenguincoder.data.repository.inviteUsersJson
import com.igloo.blindpenguincoder.data.repository.jsonResponse
import com.igloo.blindpenguincoder.feature.movies.WatchRoomPresets
import com.igloo.blindpenguincoder.playback.model.HLS_WAITING_FOR_CAPACITY_MESSAGE
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WatchTogetherViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val presets = WatchRoomPresets(
        mode = PlaybackMode.Remux,
        audioTrack = 1,
        subtitleTrack = null,
        settingsLine = "Original quality — audio adjusted · Spanish · Stereo · Subtitles off",
    )
    private val failures = mutableListOf<String>()
    private val failureMovies = mutableListOf<Long>()
    private val createBodies = mutableListOf<String>()

    /** The server's answers to a create, in order; the last repeats. */
    private var createAnswers = listOf<Pair<HttpStatusCode, String>>(
        HttpStatusCode.Created to createWatchRoomJson(),
    )

    private fun test(block: suspend TestScope.(WatchTogetherViewModel) -> Unit) = runTest {
        var creates = 0
        val http = TestHttp(UnconfinedTestDispatcher(testScheduler)) { request ->
            when (request.url.encodedPath) {
                "/api/users" -> jsonResponse(
                    inviteUsersJson(
                        """{"id":2,"name":"Maria","email":"m@example.com","avatar":null}""",
                        """{"id":3,"name":"Ana","email":"a@example.com","avatar":null}""",
                    ),
                )
                "/api/watch-rooms" -> {
                    createBodies += request.body.toByteArray().decodeToString()
                    val (status, body) = createAnswers[minOf(creates++, createAnswers.lastIndex)]
                    respond(
                        content = body,
                        status = status,
                        headers = headersOf(
                            HttpHeaders.ContentType to listOf("application/json"),
                            HttpHeaders.RetryAfter to listOf("5"),
                        ),
                    )
                }
                else -> error("unexpected request to ${request.url.encodedPath}")
            }
        }
        val viewModel = WatchTogetherViewModel(http.watchRoomRepository, http.serverUrl) { movieId, notice ->
            failureMovies += movieId
            failures += notice
        }
        viewModel.open(movieId = 42, title = "Heat", presets = presets)
        runCurrent()
        block(viewModel)
    }

    private suspend fun TestScope.created(viewModel: WatchTogetherViewModel, act: () -> Unit): List<CreatedRoom> {
        val rooms = mutableListOf<CreatedRoom>()
        val collector = backgroundScope.launch { viewModel.createdRooms.collect { rooms += it } }
        act()
        runCurrent()
        collector.cancel()
        return rooms
    }

    @Test
    fun `opening lists everyone the room can invite`() = test { viewModel ->
        val state = viewModel.uiState.value

        assertTrue(state.shown)
        assertEquals(listOf("Maria", "Ana"), (state.people as IglooRailState.Loaded).items.map { it.name })
        assertTrue(state.selected.isEmpty())
    }

    @Test
    fun `create with nobody chosen says so and sends nothing`() = test { viewModel ->
        viewModel.create()

        assertEquals(NOBODY_INVITED_HINT, viewModel.uiState.value.hint)
        assertTrue(createBodies.isEmpty())
        viewModel.toggle(2)
        assertNull(viewModel.uiState.value.hint)
    }

    @Test
    fun `create sends the page's settings and the chosen people, then hands the room on`() = test { viewModel ->
        viewModel.toggle(3)
        viewModel.toggle(2)

        val rooms = created(viewModel) { viewModel.create() }

        assertEquals(listOf(CreatedRoom(movieId = 42, roomId = 12)), rooms)
        val body = Json.parseToJsonElement(createBodies.single()).jsonObject
        assertEquals("remux", body.getValue("mode").jsonPrimitive.content)
        assertEquals(1L, body.getValue("audio_track").jsonPrimitive.long)
        assertFalse("off is left out", "subtitle_track" in body)
        // In the list's order, as the screen shows them.
        assertEquals(listOf(2L, 3L), body.getValue("invited_user_ids").jsonArray.map { it.jsonPrimitive.long })
        assertFalse(viewModel.uiState.value.shown)
    }

    @Test
    fun `a full server is waited out for as long as it asks`() = test { viewModel ->
        createAnswers = listOf(
            HttpStatusCode.ServiceUnavailable to """{"error":true,"message":"the server has no room"}""",
            HttpStatusCode.Created to createWatchRoomJson(),
        )
        viewModel.toggle(2)

        val rooms = created(viewModel) { viewModel.create() }
        assertTrue(rooms.isEmpty())
        assertEquals(HLS_WAITING_FOR_CAPACITY_MESSAGE, viewModel.uiState.value.pendingText)
        val later = created(viewModel) { advanceTimeBy(5_001) }

        assertEquals(listOf(CreatedRoom(42, 12)), later)
        assertEquals(2, createBodies.size)
    }

    @Test
    fun `a refusal closes the dialog and leaves its reason for the page`() = test { viewModel ->
        createAnswers = listOf(
            HttpStatusCode.BadRequest to
                """{"error":true,"message":"direct playback is only available for MP4 movies"}""",
        )
        viewModel.toggle(2)

        viewModel.create()
        runCurrent()

        assertEquals(
            listOf("Couldn't create the watch room: direct playback is only available for MP4 movies"),
            failures,
        )
        assertEquals("the reason goes to the page the room was made from", listOf(42L), failureMovies)
        assertFalse(viewModel.uiState.value.shown)
        assertFalse(viewModel.uiState.value.creating)
    }

    @Test
    fun `dismissing while creating stops showing the work, not the work`() = test { viewModel ->
        createAnswers = listOf(
            HttpStatusCode.ServiceUnavailable to """{"error":true,"message":"full"}""",
            HttpStatusCode.Created to createWatchRoomJson(),
        )
        viewModel.toggle(2)
        viewModel.create()
        runCurrent()

        viewModel.dismiss()
        assertFalse(viewModel.uiState.value.shown)
        assertTrue(viewModel.uiState.value.creating)
        viewModel.open(movieId = 42, title = "Heat", presets = presets)
        assertTrue("reopening shows the creation still under way", viewModel.uiState.value.creating)

        val rooms = created(viewModel) { advanceTimeBy(5_001) }
        assertEquals(listOf(CreatedRoom(42, 12)), rooms)
    }

    @Test
    fun `Watch Together on another movie while a room is being made says so and stays shut`() = test { viewModel ->
        createAnswers = listOf(
            HttpStatusCode.ServiceUnavailable to """{"error":true,"message":"busy"}""",
        )
        viewModel.toggle(2)
        viewModel.create()
        runCurrent()
        assertTrue(viewModel.uiState.value.creating)
        viewModel.dismiss()

        viewModel.open(movieId = 7, title = "Ronin", presets = presets)

        val state = viewModel.uiState.value
        assertFalse(state.shown)
        assertEquals(42L, state.movieId)
        assertEquals(listOf(7L), failureMovies)
        assertEquals(listOf(creationUnderWayNotice("Heat")), failures)

        // On its own movie the running creation comes back as it was.
        viewModel.open(movieId = 42, title = "Heat", presets = presets)
        assertTrue(viewModel.uiState.value.shown)
        assertTrue(viewModel.uiState.value.creating)

        // Let the server make room so the creation ends inside the test.
        createAnswers = listOf(HttpStatusCode.Created to createWatchRoomJson())
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.creating)
    }
}

