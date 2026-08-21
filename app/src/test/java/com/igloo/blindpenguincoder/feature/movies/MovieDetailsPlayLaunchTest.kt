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
import kotlinx.coroutines.flow.first
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

/**
 * The Play press: [MovieDetailsViewModel.buildPlayLaunch] assembling the request and running the
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

    @Test
    fun `nothing launches before the details load`() = runTest {
        val viewModel = viewModel(http())

        assertNull(viewModel.buildPlayLaunch())
        assertNull(viewModel.uiState.value.mutationNotice)
    }

    @Test
    fun `a playable movie launches with the resolved tracks and resume position`() = runTest {
        val viewModel = viewModel(http(watchProgressJson(progressSec = 1800.0, durationSec = 10200.0)))
        viewModel.open(1)
        viewModel.awaitTracksResolved()

        val request = requireNotNull(viewModel.buildPlayLaunch())

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

        assertNull(viewModel.buildPlayLaunch())

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

        assertNull(viewModel.buildPlayLaunch())

        assertTrue(!consulted)
        val notice = requireNotNull(viewModel.uiState.value.mutationNotice)
        assertTrue(notice.contains("isn't available on this TV app yet"))
    }
}
