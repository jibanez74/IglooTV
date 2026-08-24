package com.igloo.blindpenguincoder.feature.movies

import com.igloo.blindpenguincoder.data.model.PlaybackMode
import com.igloo.blindpenguincoder.data.repository.TestHttp
import com.igloo.blindpenguincoder.data.repository.audioStreamJson
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

        // The fixture's default track is DTS-HD MA 5.1; every consultation (the gate's and the
        // settings dialog's, which shares its rule) asked about exactly that.
        assertTrue(asked.isNotEmpty())
        assertTrue(asked.all { it == ("audio/vnd.dts.hd" to 6) })
        val notice = requireNotNull(viewModel.uiState.value.mutationNotice)
        assertTrue(notice.contains("DTS-HD"))
        assertTrue(notice.contains("English · 5.1 surround"))
    }

    @Test
    fun `an HLS mode launches carrying the profile the user chose`() = runTest {
        val viewModel = viewModel(http())
        viewModel.open(1)
        viewModel.awaitTracksResolved()
        viewModel.selectPlaybackMode(PlaybackMode.P1080Mbps8)

        val request = viewModel.requestAndAwaitLaunch()

        assertEquals(PlaybackMode.P1080Mbps8, request.mode)
        assertNull(viewModel.uiState.value.mutationNotice)
    }

    /** An unplayable Direct pick is refused with guidance — never silently switched to Remux. */
    @Test
    fun `an undecodable direct track is refused, not substituted`() = runTest {
        val viewModel = viewModel(http()) { _, _ -> false }
        viewModel.open(1)
        viewModel.awaitTracksResolved()

        viewModel.requestPlayback()

        val notice = requireNotNull(viewModel.uiState.value.mutationNotice)
        assertTrue(notice.contains("Playback Settings"))
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
    fun `cached failed reads retry together and only fresh responses can launch`() = runTest {
        var technicalAttempts = 0
        var progressAttempts = 0
        val releaseTechnicalRetry = CompletableDeferred<Unit>()
        val releaseProgressRetry = CompletableDeferred<Unit>()
        val http = TestHttp(UnconfinedTestDispatcher(testScheduler)) { request ->
            val path = request.url.encodedPath
            when {
                path.startsWith("/api/movies/details/") -> jsonResponse(movieDetailsJson())
                path.endsWith("/technical-details") -> {
                    technicalAttempts += 1
                    when (technicalAttempts) {
                        1 -> jsonResponse(technicalDetailsJson())
                        2 -> jsonResponse(
                            """{"error":true,"message":"Probe failed"}""",
                            HttpStatusCode.InternalServerError,
                        )
                        else -> {
                            releaseTechnicalRetry.await()
                            jsonResponse(
                                technicalDetailsJson(
                                    audioStreams = listOf(
                                        audioStreamJson(id = 99, language = "spa"),
                                    ),
                                ),
                            )
                        }
                    }
                }
                path.endsWith("/watch-progress") -> {
                    progressAttempts += 1
                    when (progressAttempts) {
                        1 -> jsonResponse(
                            watchProgressJson(progressSec = 1800.0, durationSec = 7200.0),
                        )
                        2 -> jsonResponse(
                            """{"error":true,"message":"Progress failed"}""",
                            HttpStatusCode.InternalServerError,
                        )
                        else -> {
                            releaseProgressRetry.await()
                            jsonResponse(
                                watchProgressJson(progressSec = 3600.0, durationSec = 7200.0),
                            )
                        }
                    }
                }
                path.endsWith("/like-status") -> jsonResponse(likeStatusJson())
                else -> error("Unrouted path: $path")
            }
        }
        val viewModel = viewModel(http) { _, _ -> true }
        val launches = mutableListOf<com.igloo.blindpenguincoder.playback.model.MoviePlayRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.playRequests.collect { launches += it }
        }

        viewModel.open(1)
        advanceUntilIdle()
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(2, technicalAttempts)
        assertEquals(2, progressAttempts)
        viewModel.requestPlayback()
        runCurrent()

        assertTrue(launches.isEmpty())
        assertEquals(3, technicalAttempts)
        assertEquals(3, progressAttempts)

        releaseTechnicalRetry.complete(Unit)
        runCurrent()
        assertTrue(launches.isEmpty())

        releaseProgressRetry.complete(Unit)
        runCurrent()
        assertEquals(3, technicalAttempts)
        assertEquals(3, progressAttempts)
        assertEquals(1, launches.size)
        assertEquals(3600.0, launches.single().resumeAtSec)
        assertEquals("Spanish · 5.1 surround", launches.single().selectedAudioTrack?.label)
    }
}
