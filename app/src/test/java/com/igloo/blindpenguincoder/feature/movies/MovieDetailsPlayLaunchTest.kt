package com.igloo.blindpenguincoder.feature.movies

import com.igloo.blindpenguincoder.data.model.PlaybackMode
import com.igloo.blindpenguincoder.data.repository.TestHttp
import com.igloo.blindpenguincoder.data.repository.jsonResponse
import com.igloo.blindpenguincoder.data.repository.likeStatusJson
import com.igloo.blindpenguincoder.data.repository.movieDetailsJson
import com.igloo.blindpenguincoder.data.repository.technicalDetailsJson
import com.igloo.blindpenguincoder.data.repository.watchProgressJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import io.ktor.http.HttpStatusCode

/**
 * The Play press: [MovieDetailsViewModel.requestPlayback] assembling the request and running the
 * pre-flight gate. The mapping itself is covered in [MoviePlayRequestMappingTest]; here the
 * subject is the launch decision and where a refusal lands.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MovieDetailsPlayLaunchTest {

    private val viewModels = mutableListOf<MovieDetailsViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        viewModels.forEach { it.close() }
        viewModels.clear()
        Dispatchers.resetMain()
    }

    private fun TestScope.http(
        progressJson: String = watchProgressJson(),
    ) = TestHttp(UnconfinedTestDispatcher(testScheduler)) { request ->
        val path = request.url.encodedPath
        when {
            path.startsWith("/api/movies/details/") -> jsonResponse(movieDetailsJson())
            path.endsWith("/technical-details") -> jsonResponse(technicalDetailsJson())
            path.endsWith("/watch-progress") -> jsonResponse(progressJson)
            path.endsWith("/like-status") -> jsonResponse(likeStatusJson())
            else -> error("Unrouted path: $path")
        }
    }

    private fun viewModel(
        http: TestHttp,
        canPlayAudioMime: (String, Int?) -> Boolean = { _, _ -> true },
    ) = MovieDetailsViewModel(
        http.movieRepository,
        http.serverUrl,
        onWatchedStateCommitted = {},
        canPlayAudioMime = canPlayAudioMime,
    ).also { viewModels += it }

    private suspend fun MovieDetailsViewModel.awaitTracksResolved() {
        uiState.first {
            (it.details as? MovieDetailsState.Loaded)
                ?.movie?.playbackSettings?.selectedAudioId != null
        }
    }

    private suspend fun MovieDetailsViewModel.requestAndAwaitLaunch() =
        kotlinx.coroutines.coroutineScope {
            val launch = async { playRequests.first() }
            requestPlayback()
            launch.await()
        }

    @Test
    fun `nothing launches before the details load`() = runTest {
        val viewModel = viewModel(http())

        viewModel.requestPlayback()
        assertNull(viewModel.uiState.value.mutationNotice)
    }

    @Test
    fun `a playable movie launches with the resolved tracks and resume position`() = runTest {
        val viewModel = viewModel(http(watchProgressJson(progressSec = 1800.0, durationSec = 10200.0)))
        viewModel.open(1)
        viewModel.awaitTracksResolved()

        val request = viewModel.requestAndAwaitLaunch()

        assertEquals(1L, request.movieId)
        assertEquals("Heat", request.title)
        assertEquals("video/x-matroska", request.mimeType)
        assertEquals(PlaybackMode.Direct, request.mode)
        assertEquals(0, request.audioTypeIndex)
        assertNull(request.subtitleTypeIndex)
        assertEquals(1800.0, request.resumeAtSec)
        assertEquals(10200.0, request.durationSec)
        assertNull(viewModel.uiState.value.mutationNotice)
    }

    @Test
    fun `an undecodable audio track blocks the launch onto the details notice`() = runTest {
        val asked = mutableListOf<Pair<String, Int?>>()
        val viewModel = viewModel(http()) { mime, channels ->
            asked += mime to channels
            false
        }
        viewModel.open(1)
        viewModel.awaitTracksResolved()

        viewModel.requestPlayback()

        // The fixture's default track is DTS-HD MA 5.1; the gate asked about exactly that.
        assertEquals(listOf("audio/vnd.dts.hd" to 6), asked)
        val notice = requireNotNull(viewModel.uiState.value.mutationNotice)
        assertTrue(notice.contains("DTS-HD"))
        assertTrue(notice.contains("English · 5.1 surround"))
    }

    @Test
    fun `a non-direct mode blocks the launch without consulting the device`() = runTest {
        var consulted = false
        val viewModel = viewModel(http()) { _, _ ->
            consulted = true
            true
        }
        viewModel.open(1)
        viewModel.awaitTracksResolved()
        viewModel.selectPlaybackMode(PlaybackMode.P1080Mbps8)

        viewModel.requestPlayback()

        assertTrue(!consulted)
        val notice = requireNotNull(viewModel.uiState.value.mutationNotice)
        assertTrue(notice.contains("isn't available on this TV app yet"))
    }

    @Test
    fun `one pending Play intent launches once after required reads arrive in any order`() =
        runTest {
            val orders = listOf(
                listOf("details", "technical", "progress"),
                listOf("progress", "details", "technical"),
                listOf("technical", "progress", "details"),
            )
            for (order in orders) {
                val gates = mapOf(
                    "details" to CompletableDeferred<Unit>(),
                    "technical" to CompletableDeferred(),
                    "progress" to CompletableDeferred(),
                )
                val http = TestHttp(UnconfinedTestDispatcher(testScheduler)) { request ->
                    val path = request.url.encodedPath
                    when {
                        path.startsWith("/api/movies/details/") -> {
                            gates.getValue("details").await()
                            jsonResponse(movieDetailsJson())
                        }
                        path.endsWith("/technical-details") -> {
                            gates.getValue("technical").await()
                            jsonResponse(technicalDetailsJson())
                        }
                        path.endsWith("/watch-progress") -> {
                            gates.getValue("progress").await()
                            jsonResponse(watchProgressJson(progressSec = 900.0, durationSec = 7200.0))
                        }
                        path.endsWith("/like-status") -> jsonResponse(likeStatusJson())
                        else -> error("Unrouted path: $path")
                    }
                }
                val viewModel = viewModel(http)
                val launches = mutableListOf<com.igloo.blindpenguincoder.playback.model.MoviePlayRequest>()
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    viewModel.playRequests.collect { launches += it }
                }

                viewModel.open(1)
                viewModel.requestPlayback()
                viewModel.requestPlayback()
                order.forEachIndexed { index, read ->
                    gates.getValue(read).complete(Unit)
                    // Drain only currently runnable work. Advancing virtual time while another
                    // response is intentionally gated would fire the client's request timeout.
                    runCurrent()
                    if (index == order.lastIndex && launches.isEmpty()) {
                        error("No launch for $order; state=${viewModel.uiState.value}")
                    }
                    assertEquals(
                        "order=$order completed=$read",
                        if (index == order.lastIndex) 1 else 0,
                        launches.size,
                    )
                }
                assertEquals(900.0, launches.single().resumeAtSec)
            }
        }

    @Test
    fun `failed preparation retries only the failed read and cannot bypass the gate`() = runTest {
        var technicalAttempts = 0
        var capabilityChecks = 0
        val http = TestHttp(UnconfinedTestDispatcher(testScheduler)) { request ->
            val path = request.url.encodedPath
            when {
                path.startsWith("/api/movies/details/") -> jsonResponse(movieDetailsJson())
                path.endsWith("/technical-details") -> {
                    technicalAttempts += 1
                    if (technicalAttempts < 3) {
                        jsonResponse(
                            """{"error":true,"message":"Probe failed"}""",
                            HttpStatusCode.InternalServerError,
                        )
                    } else {
                        jsonResponse(technicalDetailsJson())
                    }
                }
                path.endsWith("/watch-progress") -> jsonResponse(
                    watchProgressJson(progressSec = 1800.0, durationSec = 7200.0),
                )
                path.endsWith("/like-status") -> jsonResponse(likeStatusJson())
                else -> error("Unrouted path: $path")
            }
        }
        val viewModel = viewModel(http) { _, _ ->
            capabilityChecks += 1
            true
        }
        val launches = mutableListOf<com.igloo.blindpenguincoder.playback.model.MoviePlayRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.playRequests.collect { launches += it }
        }

        viewModel.open(1)
        advanceUntilIdle()
        viewModel.requestPlayback()
        advanceUntilIdle()

        assertTrue(launches.isEmpty())
        assertEquals(0, capabilityChecks)
        assertEquals(2, technicalAttempts)
        assertTrue(viewModel.uiState.value.mutationNotice.orEmpty().contains("Press Play"))

        viewModel.requestPlayback()
        advanceUntilIdle()

        assertEquals(3, technicalAttempts)
        assertEquals(1, capabilityChecks)
        assertEquals(1800.0, launches.single().resumeAtSec)
    }
}
