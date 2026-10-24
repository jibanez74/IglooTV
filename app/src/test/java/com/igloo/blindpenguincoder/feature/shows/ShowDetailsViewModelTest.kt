package com.igloo.blindpenguincoder.feature.shows

import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.data.model.PlaybackMode
import com.igloo.blindpenguincoder.data.repository.TestHttp
import com.igloo.blindpenguincoder.data.repository.audioStreamJson
import com.igloo.blindpenguincoder.data.repository.episodePlaybackJson
import com.igloo.blindpenguincoder.data.repository.episodeTechnicalDetailsJson
import com.igloo.blindpenguincoder.data.repository.episodeWatchedJson
import com.igloo.blindpenguincoder.data.repository.jsonResponse
import com.igloo.blindpenguincoder.data.repository.showDetailsJson
import com.igloo.blindpenguincoder.data.repository.showEpisodeJson
import com.igloo.blindpenguincoder.data.repository.showSeasonEpisodesJson
import com.igloo.blindpenguincoder.data.repository.showSeasonSummaryJson
import com.igloo.blindpenguincoder.data.repository.upNextEpisodeJson
import com.igloo.blindpenguincoder.data.repository.watchProgressJson
import com.igloo.blindpenguincoder.feature.shared.DetailsState
import com.igloo.blindpenguincoder.playback.model.EpisodeContext
import com.igloo.blindpenguincoder.playback.model.PlaybackMediaRef
import com.igloo.blindpenguincoder.playback.model.UpNextEpisode
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.toByteArray
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
    private var committed = 0

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
        var episodeWatched: suspend MockRequestHandleScope.(Long) -> HttpResponseData = { id ->
            jsonResponse(episodeWatchedJson(episodeId = id, watched = true))
        }
        val watchedBodies = mutableListOf<String>()

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
            val episode = Regex(
                "/api/shows/episodes/(\\d+)(/technical-details|/watch-progress/watched|/watch-progress)?",
            ).matchEntire(path)
            when {
                path == "/api/shows/details/40" -> server.details(this)
                season != null -> server.season(this, season.groupValues[1].toLong())
                episode != null -> {
                    val id = episode.groupValues[1].toLong()
                    when (episode.groupValues[2]) {
                        "/technical-details" -> server.episodeTechnical(this, id)
                        "/watch-progress" -> server.episodeProgress(this, id)
                        "/watch-progress/watched" -> {
                            server.watchedBodies += String(request.body.toByteArray())
                            server.episodeWatched(this, id)
                        }
                        else -> server.episode(this, id)
                    }
                }
                else -> error("Unrouted path: $path")
            }
        }
        return ShowDetailsViewModel(
            http.showRepository,
            http.serverUrl,
            EpisodePlayPreparer(
                http.showRepository,
                http.serverUrl,
                canPlayVideoMime = { canPlayVideo },
                canPlayAudioMime = { _, _ -> canPlayAudio },
            ),
            onWatchedStateCommitted = { committed += 1 },
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

    private fun ShowDetailsViewModel.episode(id: Long): EpisodeUi =
        (uiState.value.episodes as IglooRailState.Loaded).items.single { it.id == id }

    @Test
    fun `nothing loads until a show is opened`() = runTest {
        val server = Server()
        val viewModel = viewModel(server)

        viewModel.refresh()
        viewModel.retry()
        viewModel.setEpisodeWatched(101, watched = true)
        viewModel.landOnEpisode(1, 101, EpisodeLaunchSite.Hero)
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
            // The page drew episode 102 without a bar, so the position the server has is asked about.
            assertEquals(true, request.promptForResume)
            assertEquals(EpisodeContext(seasonNumber = 1, upNext = null), request.episode)
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
    fun `a press on the episode the page shows as resumable skips the Resume prompt`() = runTest {
        val server = Server().apply { season = { jsonResponse(seasonJson(it, resumeSecond = true)) } }
        val viewModel = viewModel(server)
        viewModel.open(40)
        testScheduler.advanceUntilIdle()

        viewModel.playEpisode(102)
        val request = viewModel.playRequests.first()

        assertEquals(600.0, request.resumeAtSec)
        assertEquals(false, request.promptForResume)
    }

    @Test
    fun `the request carries the episode that follows, as the card will present it`() = runTest {
        val server = Server().apply {
            episode = { id ->
                jsonResponse(
                    episodePlaybackJson(
                        episodeId = id,
                        seasonNumber = 2,
                        nextEpisode = upNextEpisodeJson(
                            id = 903,
                            seasonNumber = 2,
                            episodeNumber = 4,
                            progressSec = 600.0,
                            durationSec = 3300.0,
                        ),
                    ),
                )
            }
        }
        val viewModel = viewModel(server)
        viewModel.open(40)
        testScheduler.advanceUntilIdle()

        viewModel.playEpisode(102)
        val request = viewModel.playRequests.first()

        assertEquals(
            EpisodeContext(
                seasonNumber = 2,
                upNext = UpNextEpisode(
                    id = 903,
                    seasonNumber = 2,
                    episodeNumber = 4,
                    name = "The You You Are",
                    stillUrl = "http://igloo.test:8080/api/tmdb/images/w500/next.jpg",
                    resumable = true,
                ),
            ),
            request.episode,
        )
    }

    @Test
    fun `a next episode already watched, or barely started, is not resumable`() = runTest {
        val server = Server()
        val viewModel = viewModel(server)
        viewModel.open(40)
        testScheduler.advanceUntilIdle()

        listOf(
            upNextEpisodeJson(progressSec = 600.0, durationSec = 3300.0, watched = true),
            upNextEpisodeJson(progressSec = 10.0, durationSec = 3300.0),
            upNextEpisodeJson(stillPath = null),
        ).forEachIndexed { index, next ->
            server.episode = { id -> jsonResponse(episodePlaybackJson(episodeId = id, nextEpisode = next)) }
            viewModel.playEpisode(101L + index)
            val upNext = requireNotNull(viewModel.playRequests.first().episode?.upNext)
            assertEquals(false, upNext.resumable)
            if (index == 2) assertNull(upNext.stillUrl)
        }
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

    // --- watched ---

    @Test
    fun `marking an episode watched flips its card and the hero at once, then rereads the season`() =
        runTest {
            val held = CompletableDeferred<Unit>()
            val server = Server().apply {
                episodeWatched = { id -> held.await(); jsonResponse(episodeWatchedJson(id, watched = true)) }
            }
            val viewModel = viewModel(server)
            viewModel.open(40)
            testScheduler.advanceUntilIdle()
            assertEquals("Play S1 E1", viewModel.uiState.value.playTarget?.label)
            val readsBefore = server.seasonRequests(1)

            viewModel.setEpisodeWatched(101, watched = true)

            // Optimistic: the check is on and the hero moved on before the server answered.
            assertEquals(listOf("""{"watched":true}"""), server.watchedBodies)
            assertTrue(viewModel.episode(101).watched)
            assertNull(viewModel.episode(101).progressFraction)
            assertEquals(102L, viewModel.uiState.value.playTarget?.episodeId)
            assertEquals(readsBefore, server.seasonRequests(1))
            assertEquals(0, committed)

            held.complete(Unit)
            testScheduler.advanceUntilIdle()

            assertEquals(readsBefore + 1, server.seasonRequests(1))
            assertEquals(1, committed)
            assertNull(viewModel.uiState.value.watchedNotice)
        }

    @Test
    fun `marking a watched episode unwatched clears the check and makes it the hero's target`() =
        runTest {
            // The fake server's list follows the write, as the real one's does.
            var firstWatched = true
            val server = Server().apply {
                season = { number ->
                    jsonResponse(
                        showSeasonEpisodesJson(
                            number,
                            showEpisodeJson(id = 101, episodeNumber = 1, name = "First", watched = firstWatched),
                            showEpisodeJson(id = 102, episodeNumber = 2, name = "Second"),
                        ),
                    )
                }
                episodeWatched = { id ->
                    firstWatched = false
                    jsonResponse(episodeWatchedJson(id, watched = false))
                }
            }
            val viewModel = viewModel(server)
            viewModel.open(40)
            testScheduler.advanceUntilIdle()
            assertEquals(102L, viewModel.uiState.value.playTarget?.episodeId)

            viewModel.setEpisodeWatched(101, watched = false)
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("""{"watched":false}"""), server.watchedBodies)
            assertTrue(!viewModel.episode(101).watched)
            assertEquals(101L, viewModel.uiState.value.playTarget?.episodeId)
            assertEquals(1, committed)
        }

    @Test
    fun `a failed watched write puts the card back and says why under the rail`() = runTest {
        val server = Server().apply { episodeWatched = { failure() } }
        val viewModel = viewModel(server)
        viewModel.open(40)
        testScheduler.advanceUntilIdle()
        val readsBefore = server.seasonRequests(1)

        viewModel.setEpisodeWatched(101, watched = true)
        testScheduler.advanceUntilIdle()

        assertTrue(!viewModel.episode(101).watched)
        assertEquals(101L, viewModel.uiState.value.playTarget?.episodeId)
        assertEquals("Couldn't update watched status: boom", viewModel.uiState.value.watchedNotice)
        assertEquals(readsBefore, server.seasonRequests(1))
        assertEquals(0, committed)

        // The next press clears the notice.
        server.episodeWatched = { id -> jsonResponse(episodeWatchedJson(id, watched = true)) }
        viewModel.setEpisodeWatched(102, watched = true)
        assertNull(viewModel.uiState.value.watchedNotice)
    }

    @Test
    fun `a press while a write is out wins, and its failure reverts to before the first press`() =
        runTest {
            val first = CompletableDeferred<Unit>()
            var calls = 0
            val server = Server().apply {
                episodeWatched = { id ->
                    if (++calls == 1) {
                        first.await()
                        jsonResponse(episodeWatchedJson(id, watched = true))
                    } else {
                        failure()
                    }
                }
            }
            val viewModel = viewModel(server)
            viewModel.open(40)
            testScheduler.advanceUntilIdle()
            val readsBefore = server.seasonRequests(1)

            viewModel.setEpisodeWatched(101, watched = true)
            viewModel.setEpisodeWatched(101, watched = false)
            testScheduler.advanceUntilIdle()
            // The second press failed: the row is as it was before the first press, not as the
            // first press left it.
            assertTrue(!viewModel.episode(101).watched)
            assertEquals(1, server.watchedBodies.count { it == """{"watched":false}""" })

            first.complete(Unit)
            testScheduler.advanceUntilIdle()

            // The first press's late success is nobody's: no re-read, no refresh.
            assertEquals(readsBefore, server.seasonRequests(1))
            assertEquals(0, committed)
        }

    @Test
    fun `a season read landing mid-write keeps the write's value`() = runTest {
        val held = CompletableDeferred<Unit>()
        val server = Server().apply {
            episodeWatched = { id -> held.await(); jsonResponse(episodeWatchedJson(id, watched = true)) }
        }
        val viewModel = viewModel(server)
        viewModel.open(40)
        testScheduler.advanceUntilIdle()

        viewModel.setEpisodeWatched(101, watched = true)
        // The player's save commit re-reads the page; the server still lists 101 unwatched.
        // (No scheduler advance while the write is held: virtual time would trip the client's
        // request timeout and fail the write instead of holding it.)
        viewModel.refresh()
        assertEquals(2, server.seasonRequests(1))

        assertTrue(viewModel.episode(101).watched)
        assertEquals(102L, viewModel.uiState.value.playTarget?.episodeId)

        held.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(1, committed)
    }

    @Test
    fun `a watched write outlives Back and still refreshes Continue Watching`() = runTest {
        val held = CompletableDeferred<Unit>()
        val server = Server().apply {
            episodeWatched = { id -> held.await(); jsonResponse(episodeWatchedJson(id, watched = true)) }
        }
        val viewModel = viewModel(server)
        viewModel.open(40)
        testScheduler.advanceUntilIdle()

        viewModel.setEpisodeWatched(101, watched = true)
        viewModel.close()
        held.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals(1, committed)
        assertEquals(ShowDetailsUiState(), viewModel.uiState.value)
    }

    // --- landing ---

    @Test
    fun `landing on an episode of the shown season records it without a read`() = runTest {
        val server = Server()
        val viewModel = viewModel(server)
        viewModel.open(40)
        testScheduler.advanceUntilIdle()
        val readsBefore = server.seasonRequests(1)

        viewModel.landOnEpisode(1, 102, EpisodeLaunchSite.EpisodesRail)

        assertEquals(
            EpisodeLanding(seasonNumber = 1, episodeId = 102, site = EpisodeLaunchSite.EpisodesRail, sequence = 1),
            viewModel.uiState.value.landing,
        )
        assertEquals(1L, viewModel.uiState.value.selectedSeason)
        assertEquals(readsBefore, server.seasonRequests(1))
    }

    @Test
    fun `landing on another cached season shows it with the landing`() = runTest {
        val server = Server()
        val viewModel = viewModel(server)
        viewModel.open(40)
        testScheduler.advanceUntilIdle()
        viewModel.selectSeason(2)
        viewModel.selectSeason(1)
        testScheduler.advanceUntilIdle()
        val readsBefore = server.seasonRequests(2)

        viewModel.landOnEpisode(2, 202, EpisodeLaunchSite.Hero)

        val state = viewModel.uiState.value
        assertEquals(2L, state.selectedSeason)
        assertEquals(listOf(201L, 202L), viewModel.episodeIds())
        assertEquals(EpisodeLaunchSite.Hero, state.landing?.site)
        assertEquals(202L, state.landing?.episodeId)
        assertEquals(readsBefore, server.seasonRequests(2))
    }

    @Test
    fun `landing on an uncached season shows Loading, then its episodes, with the landing kept`() =
        runTest {
            val held = CompletableDeferred<Unit>()
            val server = Server().apply {
                season = { number ->
                    if (number == 2L) held.await()
                    jsonResponse(seasonJson(number))
                }
            }
            val viewModel = viewModel(server)
            viewModel.open(40)
            testScheduler.advanceUntilIdle()

            viewModel.landOnEpisode(2, 202, EpisodeLaunchSite.EpisodesRail)

            var state = viewModel.uiState.value
            assertEquals(2L, state.selectedSeason)
            assertTrue(state.episodes is IglooRailState.Loading)
            assertNull(state.playTarget)
            assertEquals(202L, state.landing?.episodeId)

            held.complete(Unit)
            testScheduler.advanceUntilIdle()

            state = viewModel.uiState.value
            assertEquals(listOf(201L, 202L), viewModel.episodeIds())
            assertEquals(201L, state.playTarget?.episodeId)
            assertEquals(202L, state.landing?.episodeId)
        }

    @Test
    fun `a landing on a season the show does not list is ignored`() = runTest {
        val server = Server()
        val viewModel = viewModel(server)
        viewModel.open(40)
        testScheduler.advanceUntilIdle()
        val before = viewModel.uiState.value

        viewModel.landOnEpisode(9, 901, EpisodeLaunchSite.EpisodesRail)

        assertEquals(before, viewModel.uiState.value)
    }

    @Test
    fun `two landings on the same episode are told apart`() = runTest {
        val server = Server()
        val viewModel = viewModel(server)
        viewModel.open(40)
        testScheduler.advanceUntilIdle()

        viewModel.landOnEpisode(1, 102, EpisodeLaunchSite.EpisodesRail)
        val first = viewModel.uiState.value.landing
        viewModel.landOnEpisode(1, 102, EpisodeLaunchSite.EpisodesRail)
        val second = viewModel.uiState.value.landing

        assertEquals(1, first?.sequence)
        assertEquals(2, second?.sequence)
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
