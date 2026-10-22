package com.igloo.blindpenguincoder.feature.shows

import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.data.model.PlaybackMode
import com.igloo.blindpenguincoder.data.repository.TestHttp
import com.igloo.blindpenguincoder.data.repository.audioStreamJson
import com.igloo.blindpenguincoder.data.repository.episodePlaybackJson
import com.igloo.blindpenguincoder.data.repository.episodeTechnicalDetailsJson
import com.igloo.blindpenguincoder.data.repository.jsonResponse
import com.igloo.blindpenguincoder.data.repository.showDetailsJson
import com.igloo.blindpenguincoder.data.repository.showEpisodeJson
import com.igloo.blindpenguincoder.data.repository.showSeasonEpisodesJson
import com.igloo.blindpenguincoder.data.repository.showSeasonSummaryJson
import com.igloo.blindpenguincoder.data.repository.watchProgressJson
import com.igloo.blindpenguincoder.feature.shared.DetailsState
import com.igloo.blindpenguincoder.playback.model.PlaybackMediaRef
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShowDetailsViewModelTest {

    // A view model outlives the test body — its scope is not runTest's child — so an unfinished
    // load would still be on Dispatchers.Main when the next class calls setMain. Closing cancels
    // it, which is also what Back does in production.
    private val viewModels = mutableListOf<ShowDetailsViewModel>()

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

    /**
     * A fake server for one show: [details] answers the details route, [season] each season route
     * by number, and [episode], [episodeTechnical] and [episodeProgress] an episode's three
     * playback reads by id. Every request path is recorded in [paths], and anything else fails.
     */
    private class Server {
        val paths = mutableListOf<String>()
        var details: suspend MockRequestHandleScope.() -> HttpResponseData = {
            jsonResponse(showDetailsJson())
        }
        var season: suspend MockRequestHandleScope.(Long) -> HttpResponseData = { number ->
            jsonResponse(seasonJson(number))
        }
        var episode: suspend MockRequestHandleScope.(Long) -> HttpResponseData = { id ->
            jsonResponse(episodePlaybackJson(episodeId = id))
        }
        var episodeTechnical: suspend MockRequestHandleScope.(Long) -> HttpResponseData = {
            jsonResponse(episodeTechnicalDetailsJson())
        }
        var episodeProgress: suspend MockRequestHandleScope.(Long) -> HttpResponseData = {
            jsonResponse(watchProgressJson(progressSec = 600.0, durationSec = 3300.0))
        }

        fun seasonRequests(number: Long): Int =
            paths.count { it == "/api/shows/40/seasons/$number/episodes" }

        fun episodeRequests(id: Long): List<String> =
            paths.filter { it.startsWith("/api/shows/episodes/$id") }
    }

    private fun TestScope.viewModel(
        server: Server,
        canPlayVideo: Boolean = true,
        canPlayAudio: Boolean = true,
    ): ShowDetailsViewModel {
        val http = TestHttp(UnconfinedTestDispatcher(testScheduler)) { request ->
            val path = request.url.encodedPath
            server.paths += path
            val season = Regex("/api/shows/40/seasons/(\\d+)/episodes").matchEntire(path)
            val episode = Regex("/api/shows/episodes/(\\d+)(/technical-details|/watch-progress)?")
                .matchEntire(path)
            when {
                path == "/api/shows/details/40" -> server.details(this)
                season != null -> server.season(this, season.groupValues[1].toLong())
                episode != null -> {
                    val id = episode.groupValues[1].toLong()
                    when (episode.groupValues[2]) {
                        "/technical-details" -> server.episodeTechnical(this, id)
                        "/watch-progress" -> server.episodeProgress(this, id)
                        else -> server.episode(this, id)
                    }
                }
                else -> error("Unrouted path: $path")
            }
        }
        return ShowDetailsViewModel(
            http.showRepository,
            http.serverUrl,
            canPlayVideoMime = { canPlayVideo },
            canPlayAudioMime = { _, _ -> canPlayAudio },
        ).also { viewModels += it }
    }

    /**
     * The next launch, or null when none arrives within a (virtual) second: the channel is
     * conflated, so this is also what proves an abandoned preparation never launched — had it
     * completed after the one that replaced it, its request would be the latest.
     */
    private suspend fun ShowDetailsViewModel.nextLaunchOrNull(): PlaybackMediaRef? =
        withTimeoutOrNull(1_000) { playRequests.first().media }

    private fun ShowDetailsViewModel.loaded(): ShowDetailsUi =
        (uiState.value.details as DetailsState.Loaded).value

    private fun ShowDetailsViewModel.episodeIds(): List<Long> =
        (uiState.value.episodes as IglooRailState.Loaded).items.map { it.id }

    @Test
    fun `nothing loads until a show is opened`() = runTest {
        val server = Server()
        val viewModel = viewModel(server)

        viewModel.refresh()
        viewModel.retry()
        viewModel.selectSeason(2)
        viewModel.retrySeason()
        viewModel.playEpisode(101)

        assertNull(viewModel.uiState.value.openShowId)
        assertTrue(server.paths.isEmpty())
    }

    @Test
    fun `open holds the page until the first season settles, then publishes both`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val server = Server().apply {
            season = { number -> gate.await(); jsonResponse(seasonJson(number)) }
        }
        val viewModel = viewModel(server)

        viewModel.open(40)
        // runCurrent, not advanceUntilIdle: idling would run the held request into its timeout.
        testScheduler.runCurrent()

        assertEquals(listOf("/api/shows/details/40", "/api/shows/40/seasons/1/episodes"), server.paths)
        assertEquals(DetailsState.Loading, viewModel.uiState.value.details)

        gate.complete(Unit)
        testScheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Severance", viewModel.loaded().title)
        assertEquals(1L, state.selectedSeason)
        assertEquals(listOf(101L, 102L), viewModel.episodeIds())
        assertEquals("Play S1 E1", state.playTarget?.label)
        assertEquals(101L, state.playTarget?.episodeId)
    }

    @Test
    fun `the first season in server order is the default, so specials last are never it`() = runTest {
        val server = Server().apply {
            details = {
                jsonResponse(
                    showDetailsJson(
                        seasons = listOf(
                            showSeasonSummaryJson(1),
                            showSeasonSummaryJson(2),
                            showSeasonSummaryJson(0),
                        ),
                    ),
                )
            }
        }
        val viewModel = viewModel(server)

        viewModel.open(40)
        testScheduler.advanceUntilIdle()

        assertEquals(1L, viewModel.uiState.value.selectedSeason)
        assertEquals(listOf(1L, 2L, 0L), viewModel.loaded().seasons.map { it.number })
    }

    @Test
    fun `a show with no seasons loads with no selection, an empty rail and no target`() = runTest {
        val server = Server().apply { details = { jsonResponse(showDetailsJson(seasons = emptyList())) } }
        val viewModel = viewModel(server)

        viewModel.open(40)
        testScheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.details is DetailsState.Loaded)
        assertNull(state.selectedSeason)
        assertEquals(IglooRailState.Loaded(emptyList<EpisodeUi>()), state.episodes)
        assertNull(state.playTarget)
        assertEquals(listOf("/api/shows/details/40"), server.paths)
    }

    @Test
    fun `a failed first season still opens the page, with the rail in error and no target`() = runTest {
        val server = Server().apply { season = { failure() } }
        val viewModel = viewModel(server)

        viewModel.open(40)
        testScheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.details is DetailsState.Loaded)
        assertEquals(1L, state.selectedSeason)
        assertTrue(state.episodes is IglooRailState.Error)
        assertNull(state.playTarget)
    }

    @Test
    fun `a failed open shows the error and Retry reloads the show and its season`() = runTest {
        var fail = true
        val server = Server().apply {
            details = { if (fail) failure() else jsonResponse(showDetailsJson()) }
        }
        val viewModel = viewModel(server)

        viewModel.open(40)
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.details is DetailsState.Error)

        fail = false
        viewModel.retry()
        testScheduler.advanceUntilIdle()

        assertEquals("Severance", viewModel.loaded().title)
        assertEquals(listOf(101L, 102L), viewModel.episodeIds())
    }

    @Test
    fun `selecting a season shows Loading, then its episodes, and retargets the hero`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val server = Server().apply {
            season = { number ->
                if (number == 2L) gate.await()
                jsonResponse(seasonJson(number, resumeSecond = number == 2L))
            }
        }
        val viewModel = viewModel(server)
        viewModel.open(40)
        testScheduler.advanceUntilIdle()

        viewModel.selectSeason(2)
        testScheduler.runCurrent()

        assertEquals(2L, viewModel.uiState.value.selectedSeason)
        assertEquals(IglooRailState.Loading, viewModel.uiState.value.episodes)
        assertNull(viewModel.uiState.value.playTarget)

        gate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(201L, 202L), viewModel.episodeIds())
        assertEquals("Resume S2 E2", viewModel.uiState.value.playTarget?.label)
        assertEquals(202L, viewModel.uiState.value.playTarget?.episodeId)
    }

    @Test
    fun `a loaded season is cached and not read again`() = runTest {
        val server = Server()
        val viewModel = viewModel(server)
        viewModel.open(40)
        testScheduler.advanceUntilIdle()

        viewModel.selectSeason(2)
        testScheduler.advanceUntilIdle()
        viewModel.selectSeason(1)
        viewModel.selectSeason(2)
        testScheduler.advanceUntilIdle()

        assertEquals(1, server.seasonRequests(1))
        assertEquals(1, server.seasonRequests(2))
        assertEquals(listOf(201L, 202L), viewModel.episodeIds())
    }

    @Test
    fun `a failed season is not cached, so picking it again retries it`() = runTest {
        var failSeasonTwo = true
        val server = Server().apply {
            season = { number ->
                if (number == 2L && failSeasonTwo) failure() else jsonResponse(seasonJson(number))
            }
        }
        val viewModel = viewModel(server)
        viewModel.open(40)
        testScheduler.advanceUntilIdle()

        viewModel.selectSeason(2)
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.episodes is IglooRailState.Error)

        failSeasonTwo = false
        viewModel.selectSeason(1)
        viewModel.selectSeason(2)
        testScheduler.advanceUntilIdle()

        assertEquals(2, server.seasonRequests(2))
        assertEquals(listOf(201L, 202L), viewModel.episodeIds())
    }

    @Test
    fun `retrySeason reads the selected season again and nothing else`() = runTest {
        var fail = true
        val server = Server().apply {
            season = { number -> if (fail) failure() else jsonResponse(seasonJson(number)) }
        }
        val viewModel = viewModel(server)
        viewModel.open(40)
        testScheduler.advanceUntilIdle()

        fail = false
        viewModel.retrySeason()
        testScheduler.advanceUntilIdle()

        assertEquals(1, server.paths.count { it == "/api/shows/details/40" })
        assertEquals(2, server.seasonRequests(1))
        assertEquals(listOf(101L, 102L), viewModel.episodeIds())
        assertEquals("Play S1 E1", viewModel.uiState.value.playTarget?.label)
    }

    @Test
    fun `a late answer for a season the user left fills the cache and leaves the rail alone`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val server = Server().apply {
            season = { number ->
                if (number == 2L) gate.await()
                jsonResponse(seasonJson(number))
            }
        }
        val viewModel = viewModel(server)
        viewModel.open(40)
        testScheduler.advanceUntilIdle()

        viewModel.selectSeason(2)
        viewModel.selectSeason(1)
        testScheduler.runCurrent()
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals(1L, viewModel.uiState.value.selectedSeason)
        assertEquals(listOf(101L, 102L), viewModel.episodeIds())

        viewModel.selectSeason(2)
        assertEquals(listOf(201L, 202L), viewModel.episodeIds())
        assertEquals(1, server.seasonRequests(2))
    }

    @Test
    fun `a failed background refresh keeps the page and the rail`() = runTest {
        var fail = false
        val server = Server().apply {
            details = { if (fail) failure() else jsonResponse(showDetailsJson()) }
            season = { number -> if (fail) failure() else jsonResponse(seasonJson(number)) }
        }
        val viewModel = viewModel(server)
        viewModel.open(40)
        testScheduler.advanceUntilIdle()

        fail = true
        viewModel.refresh()
        testScheduler.advanceUntilIdle()

        assertEquals("Severance", viewModel.loaded().title)
        assertEquals(listOf(101L, 102L), viewModel.episodeIds())
        assertEquals("Play S1 E1", viewModel.uiState.value.playTarget?.label)
    }

    @Test
    fun `a refresh that no longer lists the selected season falls back to the first`() = runTest {
        var seasons = listOf(showSeasonSummaryJson(1), showSeasonSummaryJson(2))
        val server = Server().apply { details = { jsonResponse(showDetailsJson(seasons = seasons)) } }
        val viewModel = viewModel(server)
        viewModel.open(40)
        testScheduler.advanceUntilIdle()
        viewModel.selectSeason(2)
        testScheduler.advanceUntilIdle()

        seasons = listOf(showSeasonSummaryJson(1))
        viewModel.refresh()
        testScheduler.advanceUntilIdle()

        assertEquals(1L, viewModel.uiState.value.selectedSeason)
        assertEquals(listOf(101L, 102L), viewModel.episodeIds())
    }

    @Test
    fun `a refresh rereads the selected season and drops the other cached ones`() = runTest {
        val server = Server()
        val viewModel = viewModel(server)
        viewModel.open(40)
        testScheduler.advanceUntilIdle()
        viewModel.selectSeason(2)
        testScheduler.advanceUntilIdle()

        viewModel.refresh()
        testScheduler.advanceUntilIdle()
        assertEquals(2, server.seasonRequests(2))

        viewModel.selectSeason(1)
        testScheduler.advanceUntilIdle()
        assertEquals(2, server.seasonRequests(1))
    }

    @Test
    fun `closing forgets the show and a late answer cannot reopen it`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val server = Server().apply {
            details = { gate.await(); jsonResponse(showDetailsJson()) }
        }
        val viewModel = viewModel(server)
        viewModel.open(40)
        testScheduler.runCurrent()

        viewModel.close()
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals(ShowDetailsUiState(), viewModel.uiState.value)
    }

    @Test
    fun `playing an episode prepares it and emits the request the host opens the player with`() =
        runTest {
            val server = Server()
            val viewModel = viewModel(server)
            viewModel.open(40)
            testScheduler.advanceUntilIdle()

            viewModel.playEpisode(102)
            val request = viewModel.playRequests.first()

            assertEquals(PlaybackMediaRef.Episode(102), request.media)
            assertEquals("Severance · S1 E3 · In Perpetuity", request.title)
            assertEquals(PlaybackMode.Direct, request.mode)
            assertEquals(600.0, request.resumeAtSec)
            assertEquals(
                listOf(
                    "/api/shows/episodes/102",
                    "/api/shows/episodes/102/technical-details",
                    "/api/shows/episodes/102/watch-progress",
                ),
                server.episodeRequests(102).sorted(),
            )
            assertNull(viewModel.uiState.value.playbackNotice)
        }

    @Test
    fun `a failed episode read surfaces a notice and launches nothing`() = runTest {
        val server = Server().apply { episodeTechnical = { failure() } }
        val viewModel = viewModel(server)
        viewModel.open(40)
        testScheduler.advanceUntilIdle()

        viewModel.playEpisode(101)
        val notice = viewModel.uiState.first { it.playbackNotice != null }.playbackNotice

        assertEquals("Couldn't prepare playback: boom", notice)
        assertNull(viewModel.nextLaunchOrNull())
    }

    @Test
    fun `presses on one episode coalesce and a press on another abandons the first`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val server = Server().apply {
            episode = { id ->
                if (id == 101L) gate.await()
                jsonResponse(episodePlaybackJson(episodeId = id))
            }
        }
        val viewModel = viewModel(server)
        viewModel.open(40)
        testScheduler.advanceUntilIdle()

        viewModel.playEpisode(101)
        viewModel.playEpisode(101)
        testScheduler.runCurrent()
        assertEquals(1, server.paths.count { it == "/api/shows/episodes/101" })

        viewModel.playEpisode(102)
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals(PlaybackMediaRef.Episode(102), viewModel.nextLaunchOrNull())
        assertNull(viewModel.uiState.value.playbackNotice)
    }

    @Test
    fun `closing abandons a preparation in flight, so a late answer opens nothing`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val server = Server().apply {
            episode = { id -> gate.await(); jsonResponse(episodePlaybackJson(episodeId = id)) }
        }
        val viewModel = viewModel(server)
        viewModel.open(40)
        testScheduler.advanceUntilIdle()

        viewModel.playEpisode(101)
        testScheduler.runCurrent()
        viewModel.close()
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertNull(viewModel.nextLaunchOrNull())
        assertEquals(ShowDetailsUiState(), viewModel.uiState.value)
    }

    @Test
    fun `a refresh clears the notice and abandons a preparation in flight`() = runTest {
        var fail = true
        val gate = CompletableDeferred<Unit>()
        val server = Server().apply {
            episodeTechnical = {
                if (fail) failure() else { gate.await(); jsonResponse(episodeTechnicalDetailsJson()) }
            }
        }
        val viewModel = viewModel(server)
        viewModel.open(40)
        testScheduler.advanceUntilIdle()

        viewModel.playEpisode(101)
        testScheduler.advanceUntilIdle()
        assertEquals("Couldn't prepare playback: boom", viewModel.uiState.value.playbackNotice)

        // The host's start effect, with the notice showing and a second press in flight.
        fail = false
        viewModel.playEpisode(102)
        testScheduler.runCurrent()
        viewModel.refresh()
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.playbackNotice)
        assertNull(viewModel.nextLaunchOrNull())
    }

    @Test
    fun `a refresh drops a launch the host has not taken yet`() = runTest {
        val server = Server()
        val viewModel = viewModel(server)
        viewModel.open(40)
        testScheduler.advanceUntilIdle()

        // Nobody collecting: the request waits in the channel, as it would across a recreation.
        viewModel.playEpisode(101)
        testScheduler.advanceUntilIdle()
        viewModel.refresh()
        testScheduler.advanceUntilIdle()

        assertNull(viewModel.nextLaunchOrNull())
    }

    /** The press named a concrete episode; browsing another season does not withdraw it. */
    @Test
    fun `a season switch leaves a preparation in flight alone`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val server = Server().apply {
            episode = { id -> gate.await(); jsonResponse(episodePlaybackJson(episodeId = id)) }
        }
        val viewModel = viewModel(server)
        viewModel.open(40)
        testScheduler.advanceUntilIdle()

        viewModel.playEpisode(101)
        testScheduler.runCurrent()
        viewModel.selectSeason(2)
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals(PlaybackMediaRef.Episode(101), viewModel.playRequests.first().media)
        assertEquals(2L, viewModel.uiState.value.selectedSeason)
    }

    /** The page has no Playback Settings, so the gate's refusal becomes a Remux launch, as on Home. */
    @Test
    fun `an undecodable default track falls back to remux instead of blocking`() = runTest {
        val server = Server().apply {
            episodeTechnical = {
                jsonResponse(
                    episodeTechnicalDetailsJson(
                        audioStreams = listOf(
                            audioStreamJson(ownerKey = "file_id", codec = "truehd", channels = 8),
                        ),
                    ),
                )
            }
        }
        val viewModel = viewModel(server, canPlayAudio = false)
        viewModel.open(40)
        testScheduler.advanceUntilIdle()

        viewModel.playEpisode(101)
        val request = viewModel.playRequests.first()

        assertEquals(PlaybackMode.Remux, request.mode)
        assertNull(viewModel.uiState.value.playbackNotice)
    }

    private companion object {
        /** Two episodes per season, ids `{season}01` and `{season}02`. */
        fun seasonJson(number: Long, resumeSecond: Boolean = false): String =
            showSeasonEpisodesJson(
                number,
                showEpisodeJson(id = number * 100 + 1, episodeNumber = 1, name = "First"),
                showEpisodeJson(
                    id = number * 100 + 2,
                    episodeNumber = 2,
                    name = "Second",
                    progressSec = if (resumeSecond) 600.0 else null,
                    durationSec = if (resumeSecond) 3300.0 else null,
                ),
            )

        fun MockRequestHandleScope.failure(): HttpResponseData =
            jsonResponse("""{"error":true,"message":"boom"}""", HttpStatusCode.InternalServerError)
    }
}
