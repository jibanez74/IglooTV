package com.igloo.blindpenguincoder.feature.music

import com.igloo.blindpenguincoder.data.repository.TestHttp
import com.igloo.blindpenguincoder.data.repository.jsonResponse
import com.igloo.blindpenguincoder.data.repository.likedTrackIdsJson
import com.igloo.blindpenguincoder.data.repository.trackLikeToggleJson
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrackLikesViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Records the writes in order so FIFO per track can be asserted. */
    private class Routed {
        val toggled = mutableListOf<Long>()
        var seeds = 0
        lateinit var test: TestHttp
    }

    private val likePath = Regex("/api/music/tracks/(\\d+)/like")

    private fun TestScope.routed(
        seed: suspend MockRequestHandleScope.() -> HttpResponseData = { jsonResponse(likedTrackIdsJson(3, 5)) },
        toggle: suspend MockRequestHandleScope.(Long) -> HttpResponseData = { id ->
            jsonResponse(trackLikeToggleJson(trackId = id, isLiked = true))
        },
    ): Routed {
        val routed = Routed()
        routed.test = TestHttp(UnconfinedTestDispatcher(testScheduler)) { request: HttpRequestData ->
            val path = request.url.encodedPath
            val like = likePath.matchEntire(path)
            when {
                path.endsWith("/liked-ids") -> {
                    routed.seeds += 1
                    seed()
                }
                like != null -> {
                    val id = like.groupValues[1].toLong()
                    routed.toggled += id
                    toggle(id)
                }
                else -> error("unexpected request to $path")
            }
        }
        return routed
    }

    private fun seeded(routed: Routed) =
        TrackLikesViewModel(routed.test.musicRepository).also { it.refresh() }

    @Test
    fun `refresh seeds the liked set`() = runTest {
        val model = seeded(routed())

        assertEquals(setOf(3L, 5L), model.uiState.value.likedIds)
        assertEquals(true, model.uiState.value.isLiked(3))
        assertEquals(false, model.uiState.value.isLiked(4))
    }

    @Test
    fun `a toggle before the seed lands is ignored`() = runTest {
        val routed = routed()
        val model = TrackLikesViewModel(routed.test.musicRepository)

        model.toggle(3)

        assertNull(model.uiState.value.isLiked(3))
        assertEquals(emptyList<Long>(), routed.toggled)
    }

    @Test
    fun `a toggle flips optimistically, marks the track pending, and settles on the response`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val routed = routed(toggle = { id ->
            gate.await()
            jsonResponse(trackLikeToggleJson(trackId = id, isLiked = false))
        })
        val model = seeded(routed)

        model.toggle(3)

        assertEquals(false, model.uiState.value.isLiked(3))
        assertEquals(setOf(3L), model.uiState.value.pendingIds)

        gate.complete(Unit)

        assertEquals(false, model.uiState.value.isLiked(3))
        assertTrue(model.uiState.value.pendingIds.isEmpty())
        assertEquals(listOf(3L), routed.toggled)
    }

    /** Two presses are two toggles, written in order; the last response is the truth. */
    @Test
    fun `presses on one track are written FIFO and the last response wins`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var writes = 0
        val routed = routed(toggle = { id ->
            gate.await()
            writes += 1
            jsonResponse(trackLikeToggleJson(trackId = id, isLiked = writes % 2 == 0))
        })
        val model = seeded(routed)

        model.toggle(4)
        model.toggle(4)
        assertEquals(false, model.uiState.value.isLiked(4))

        gate.complete(Unit)

        assertEquals(listOf(4L, 4L), routed.toggled)
        assertEquals(true, model.uiState.value.isLiked(4))
        assertTrue(model.uiState.value.pendingIds.isEmpty())
    }

    @Test
    fun `a failed write reports a notice and re-seeds from the server`() = runTest {
        var seedCalls = 0
        val routed = routed(
            seed = {
                seedCalls += 1
                jsonResponse(likedTrackIdsJson(3, 5))
            },
            toggle = { jsonResponse("""{"error":true,"message":"nope"}""", HttpStatusCode.InternalServerError) },
        )
        val model = seeded(routed)

        model.toggle(3)

        assertTrue(model.uiState.value.notice != null)
        // The optimistic flip was rolled back by the re-read, and the track is settled.
        assertEquals(true, model.uiState.value.isLiked(3))
        assertTrue(model.uiState.value.pendingIds.isEmpty())
        assertEquals(2, seedCalls)
    }

    @Test
    fun `the next accepted press clears the notice`() = runTest {
        var fail = true
        val routed = routed(toggle = { id ->
            if (fail) {
                jsonResponse("""{"error":true,"message":"nope"}""", HttpStatusCode.InternalServerError)
            } else {
                jsonResponse(trackLikeToggleJson(trackId = id, isLiked = true))
            }
        })
        val model = seeded(routed)
        model.toggle(3)
        assertTrue(model.uiState.value.notice != null)

        fail = false
        model.toggle(4)

        assertNull(model.uiState.value.notice)
    }

    @Test
    fun `a failed re-seed keeps the last-known set`() = runTest {
        var seedCalls = 0
        val routed = routed(seed = {
            seedCalls += 1
            if (seedCalls == 1) {
                jsonResponse(likedTrackIdsJson(3, 5))
            } else {
                jsonResponse("""{"error":true,"message":"nope"}""", HttpStatusCode.InternalServerError)
            }
        })
        val model = seeded(routed)

        model.refresh()

        assertEquals(setOf(3L, 5L), model.uiState.value.likedIds)
    }

    /** A seed landing under a press must not paint over the value the press owns. */
    @Test
    fun `a seed in flight does not overwrite a pending press`() = runTest {
        val seedGate = CompletableDeferred<Unit>()
        val toggleGate = CompletableDeferred<Unit>()
        var seedCalls = 0
        val routed = routed(
            seed = {
                seedCalls += 1
                if (seedCalls > 1) seedGate.await()
                jsonResponse(likedTrackIdsJson(3, 5))
            },
            toggle = { id ->
                toggleGate.await()
                jsonResponse(trackLikeToggleJson(trackId = id, isLiked = false))
            },
        )
        val model = seeded(routed)

        model.refresh()
        model.toggle(3)
        assertEquals(false, model.uiState.value.isLiked(3))

        seedGate.complete(Unit)
        // The server still says 3 is liked; the pending press wins until it settles.
        assertEquals(false, model.uiState.value.isLiked(3))
        assertEquals(true, model.uiState.value.isLiked(5))

        toggleGate.complete(Unit)
        assertEquals(false, model.uiState.value.isLiked(3))
    }
}
