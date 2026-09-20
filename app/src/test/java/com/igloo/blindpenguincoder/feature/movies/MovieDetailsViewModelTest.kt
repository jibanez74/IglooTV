package com.igloo.blindpenguincoder.feature.movies

import com.igloo.blindpenguincoder.core.ui.RatingTier
import com.igloo.blindpenguincoder.data.model.PlaybackMode
import com.igloo.blindpenguincoder.data.repository.TestHttp
import com.igloo.blindpenguincoder.data.repository.audioStreamJson
import com.igloo.blindpenguincoder.data.repository.castMemberJson
import com.igloo.blindpenguincoder.data.repository.crewMemberJson
import com.igloo.blindpenguincoder.data.repository.extraVideoJson
import com.igloo.blindpenguincoder.data.repository.jsonResponse
import com.igloo.blindpenguincoder.data.repository.likeStatusJson
import com.igloo.blindpenguincoder.data.repository.likeToggleJson
import com.igloo.blindpenguincoder.data.repository.movieDetailsJson
import com.igloo.blindpenguincoder.data.repository.movieGenreJson
import com.igloo.blindpenguincoder.data.repository.productionCompanyJson
import com.igloo.blindpenguincoder.data.repository.subtitleJson
import com.igloo.blindpenguincoder.data.repository.TEST_SERVER
import com.igloo.blindpenguincoder.data.repository.technicalDetailsJson
import com.igloo.blindpenguincoder.data.repository.videoStreamJson
import com.igloo.blindpenguincoder.data.repository.watchProgressJson
import com.igloo.blindpenguincoder.data.repository.watchedUpdateJson
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MovieDetailsViewModelTest {

    // A view model outlives the test body — its scope is not runTest's child — so an unfinished
    // load would still be on Dispatchers.Main when the next class calls setMain. Closing cancels
    // every load, which is also what Back does in production.
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

    private fun viewModel(
        http: TestHttp,
        onWatchedStateCommitted: () -> Unit = {},
        onLikeStateCommitted: () -> Unit = {},
    ) =
        MovieDetailsViewModel(
            http.movieRepository,
            http.serverUrl,
            onWatchedStateCommitted,
            onLikeStateCommitted,
            canPlayAudioMime = { _, _ -> true },
        )
            .also { viewModels += it }

    /**
     * `open` fires four requests in parallel; handlers route by path. The mock engine runs on
     * the caller's scheduler so the responses and the view model share one clock — a real
     * thread hop can resume after the test has finished and fail whichever test is running by
     * then (see TestHttp's note on the health probe).
     */
    private fun TestScope.routedHttp(
        details: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(populatedDetailsJson()) },
        technical: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(technicalDetailsJson()) },
        progress: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(watchProgressJson()) },
        likeStatus: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(likeStatusJson()) },
        likeToggle: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(likeToggleJson()) },
        setWatched: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(watchedUpdateJson()) },
    ) = TestHttp(UnconfinedTestDispatcher(testScheduler)) { request ->
        val path = request.url.encodedPath
        when {
            path.startsWith("/api/movies/details/") -> details(request)
            path.endsWith("/technical-details") -> technical(request)
            path.endsWith("/watch-progress/watched") -> setWatched(request)
            path.endsWith("/watch-progress") -> progress(request)
            path.endsWith("/like-status") -> likeStatus(request)
            path.endsWith("/like") -> likeToggle(request)
            else -> error("Unrouted path: $path")
        }
    }

    private suspend fun MovieDetailsViewModel.awaitLoaded(): MovieDetailsUi =
        (uiState.first { it.details is MovieDetailsState.Loaded }.details as MovieDetailsState.Loaded)
            .movie

    private suspend fun MovieDetailsViewModel.awaitError(): MovieDetailsState.Error =
        uiState.first { it.details is MovieDetailsState.Error }.details as MovieDetailsState.Error

    @Test
    fun `nothing loads until a movie is opened`() = runTest {
        var requests = 0
        val http = routedHttp(details = { requests++; jsonResponse(populatedDetailsJson()) })

        val viewModel = viewModel(http)

        assertEquals(0, requests)
        assertNull(viewModel.uiState.value.openMovieId)
        viewModel.refresh()
        viewModel.retry()
        assertEquals(0, requests)
    }

    @Test
    fun `open fires all four requests and maps the movie`() = runTest {
        val paths = mutableListOf<String>()
        val http = TestHttp(UnconfinedTestDispatcher(testScheduler)) { request ->
            val path = request.url.encodedPath
            paths += path
            when {
                path.startsWith("/api/movies/details/") -> jsonResponse(populatedDetailsJson(id = 5))
                path.endsWith("/technical-details") -> jsonResponse(technicalDetailsJson())
                path.endsWith("/watch-progress") -> jsonResponse(
                    watchProgressJson(progressSec = 1800.0, durationSec = 10200.0),
                )
                path.endsWith("/like-status") -> jsonResponse(likeStatusJson(isLiked = true))
                else -> error("Unrouted path: $path")
            }
        }

        val viewModel = viewModel(http)
        viewModel.open(5)
        testScheduler.advanceUntilIdle()
        // The four responses land in any order; the final publication composes every fragment.
        val movie = (viewModel.uiState.value.details as MovieDetailsState.Loaded).movie

        assertEquals(
            setOf(
                "/api/movies/details/5",
                "/api/movies/5/technical-details",
                "/api/movies/5/watch-progress",
                "/api/movies/5/like-status",
            ),
            paths.toSet(),
        )
        assertEquals(5L, viewModel.uiState.value.openMovieId)
        assertEquals("Heat", movie.title)
        assertEquals("A Los Angeles crime saga.", movie.tagline)
        assertEquals(
            "http://igloo.test:8080/api/tmdb/images/w1280/heat-backdrop.jpg",
            movie.backdropUrl,
        )
        assertEquals("http://igloo.test:8080/api/tmdb/images/w500/heat.jpg", movie.posterUrl)
        assertEquals("8.2", movie.ratingBadge?.label)
        assertEquals(RatingTier.Strong, movie.ratingBadge?.tier)
        assertEquals("R", movie.certification)
        assertEquals("2h 50m", movie.runtimeText)
        assertEquals("December 15, 1995", movie.releaseDateText)
        assertEquals("Crime · Drama", movie.genresLine)
        assertEquals(listOf("4K", "HDR10", "5.1", "CC"), movie.mediaBadges)
        assertEquals(
            listOf(CrewEntry("Director", "Michael Mann"), CrewEntry("Writer", "Michael Mann")),
            movie.keyCrew,
        )
        assertEquals(listOf("Al Pacino", "Robert De Niro"), movie.cast.map { it.name })
        assertEquals("Vincent Hanna", movie.cast.first().character)
        assertEquals(
            "http://igloo.test:8080/api/tmdb/images/w185/pacino.jpg",
            movie.cast.first().photoUrl,
        )
        assertEquals("Regency Enterprises, Forward Pass", movie.about.production)
        assertEquals("English", movie.about.language)
        assertEquals("$60,000,000", movie.about.budget)
        assertEquals("$187,436,818", movie.about.revenue)
        assertEquals(0.176f, requireNotNull(movie.progress).fraction, 0.001f)
        assertEquals("2h 20m left", movie.progress.remainingTimeLabel)
        assertEquals(
            "Resume from 30 minutes and 0 seconds; 2 hours and 20 minutes remaining",
            movie.progress.resumeStateDescription,
        )
        assertEquals(false, movie.watched)
        assertEquals(true, movie.liked)
        assertEquals(
            "Rated 8.2 out of 10, R, 4K, HDR10, 5.1 surround sound, subtitles available, " +
                "2 hours and 50 minutes, released December 15, 1995",
            movie.metadataDescription,
        )
        assertEquals(
            "Heat. A Los Angeles crime saga. " +
                "Rated 8.2 out of 10, R, 4K, HDR10, 5.1 surround sound, subtitles available, " +
                "2 hours and 50 minutes, released December 15, 1995. Crime, Drama",
            movie.heroInfoDescription,
        )
    }

    @Test
    fun `a details failure is a full-screen error and retry recovers`() = runTest {
        var fail = true
        val http = routedHttp(
            details = {
                if (fail) {
                    jsonResponse("""{"error":true,"message":"boom"}""", HttpStatusCode.InternalServerError)
                } else {
                    jsonResponse(populatedDetailsJson())
                }
            },
        )

        val viewModel = viewModel(http)
        viewModel.open(1)
        viewModel.awaitError()

        fail = false
        viewModel.retry()

        assertEquals("Heat", viewModel.awaitLoaded().title)
    }

    @Test
    fun `secondary failures degrade instead of failing the screen`() = runTest {
        val http = routedHttp(
            technical = { jsonResponse("""{"error":true,"message":"x"}""", HttpStatusCode.InternalServerError) },
            progress = { jsonResponse("""{"error":true,"message":"x"}""", HttpStatusCode.InternalServerError) },
            likeStatus = { jsonResponse("""{"error":true,"message":"x"}""", HttpStatusCode.InternalServerError) },
        )

        val viewModel = viewModel(http)
        viewModel.open(1)
        val movie = viewModel.awaitLoaded()

        assertEquals("Heat", movie.title)
        assertTrue(movie.mediaBadges.isEmpty())
        assertNull(movie.progress)
        assertNull(movie.watched)
        assertNull(movie.liked)
    }

    @Test
    fun `badge tiers follow the web thresholds`() = runTest {
        suspend fun badges(video: String, audio: String, subtitles: List<String>): List<String> {
            val http = routedHttp(
                technical = {
                    jsonResponse(
                        technicalDetailsJson(
                            videoStreams = listOf(video),
                            audioStreams = listOf(audio),
                            subtitles = subtitles,
                        ),
                    )
                },
            )
            val viewModel = viewModel(http)
            viewModel.open(1)
            return viewModel.uiState
                .first {
                    val details = it.details
                    details is MovieDetailsState.Loaded && details.movie.mediaBadges.isNotEmpty()
                }
                .let { (it.details as MovieDetailsState.Loaded).movie.mediaBadges }
        }

        // Scope 4K by width; height alone under the bar.
        assertEquals(
            listOf("4K", "HDR10", "5.1", "CC"),
            badges(
                videoStreamJson(width = 3840, height = 1600, colorTransfer = "smpte2084"),
                audioStreamJson(channels = 6, channelLayout = "5.1(side)"),
                listOf(subtitleJson()),
            ),
        )
        // 1080p HLG, 7.1 layout, no subs.
        assertEquals(
            listOf("HD", "HLG", "7.1"),
            badges(
                videoStreamJson(width = 1920, height = 1080, colorTransfer = "arib-std-b67"),
                audioStreamJson(channels = 8, channelLayout = "7.1"),
                emptyList(),
            ),
        )
        // SD SDR: only the surround word for six channels without a named layout.
        assertEquals(
            listOf("Surround"),
            badges(
                videoStreamJson(width = 720, height = 576, colorTransfer = null),
                audioStreamJson(channels = 6, channelLayout = null),
                emptyList(),
            ),
        )
        // Stereo stays badge-free.
        assertEquals(
            listOf("HD"),
            badges(
                videoStreamJson(width = 1920, height = 1080, colorTransfer = null),
                audioStreamJson(channels = 2, channelLayout = "stereo"),
                emptyList(),
            ),
        )
    }

    /**
     * The tier checks use `>=`, and either dimension alone clears a tier — a scope master is
     * wide without being tall, an academy-ratio one tall without being wide. Pinned at the
     * exact cutoffs because an off-by-one there misfiles every borderline library.
     */
    @Test
    fun `resolution badge tiers are inclusive at the web cutoffs on either dimension`() = runTest {
        suspend fun badgesFor(width: Long, height: Long): List<String> {
            val http = routedHttp(
                technical = {
                    jsonResponse(
                        technicalDetailsJson(
                            videoStreams = listOf(
                                videoStreamJson(width = width, height = height, colorTransfer = null),
                            ),
                            audioStreams = emptyList(),
                            // A subtitle keeps the list non-empty so "no resolution badge" is
                            // distinguishable from "technical details not landed yet".
                            subtitles = listOf(subtitleJson()),
                        ),
                    )
                },
            )
            val viewModel = viewModel(http)
            viewModel.open(1)
            return viewModel.uiState
                .first {
                    val details = it.details
                    details is MovieDetailsState.Loaded && details.movie.mediaBadges.isNotEmpty()
                }
                .let { (it.details as MovieDetailsState.Loaded).movie.mediaBadges }
        }

        assertEquals(listOf("4K", "CC"), badgesFor(width = 3200, height = 100))
        assertEquals(listOf("4K", "CC"), badgesFor(width = 100, height = 2100))
        assertEquals(listOf("HD", "CC"), badgesFor(width = 1800, height = 100))
        assertEquals(listOf("HD", "CC"), badgesFor(width = 100, height = 1000))
        assertEquals(listOf("CC"), badgesFor(width = 1799, height = 999))
    }

    @Test
    fun `the progress strip needs thirty seconds and a position under 98 percent`() = runTest {
        suspend fun progressFor(progressSec: Double?, durationSec: Double?): ProgressUi? {
            val http = routedHttp(
                progress = {
                    jsonResponse(watchProgressJson(progressSec = progressSec, durationSec = durationSec))
                },
            )
            val viewModel = viewModel(http)
            viewModel.open(1)
            return viewModel.uiState
                .first {
                    val details = it.details
                    details is MovieDetailsState.Loaded && details.movie.watched != null
                }
                .let { (it.details as MovieDetailsState.Loaded).movie.progress }
        }

        assertNull(progressFor(29.0, 7200.0))
        assertEquals("2h left", progressFor(30.0, 7200.0)?.remainingTimeLabel)
        // The very first resumable position has nothing but seconds to say.
        assertEquals(
            "Resume from 30 seconds; 2 hours remaining",
            progressFor(30.0, 7200.0)?.resumeStateDescription,
        )
        val exact = requireNotNull(progressFor(3797.9, 7200.0))
        assertEquals(3797.9f / 7200f, exact.fraction, 0.0001f)
        assertEquals("57m left", exact.remainingTimeLabel)
        assertEquals(
            "Resume from 1 hour, 3 minutes, and 17 seconds; 57 minutes remaining",
            exact.resumeStateDescription,
        )
        assertNull(progressFor(7100.0, 7200.0))
        assertNull(progressFor(null, null))
    }

    @Test
    fun `cast is capped at ten in cast order`() = runTest {
        val http = routedHttp(
            details = {
                jsonResponse(
                    movieDetailsJson(
                        cast = (12 downTo 1).map {
                            castMemberJson(
                                id = it.toLong(),
                                artistId = it.toLong(),
                                castOrder = it.toLong(),
                                artistName = "Actor $it",
                            )
                        },
                    ),
                )
            },
        )

        val viewModel = viewModel(http)
        viewModel.open(1)
        val movie = viewModel.awaitLoaded()

        assertEquals((1..10).map { "Actor $it" }, movie.cast.map { it.name })
    }

    @Test
    fun `extra videos are youtube only, trailers first, titles tie-broken case-insensitively`() = runTest {
        val http = routedHttp(
            details = {
                jsonResponse(
                    movieDetailsJson(
                        // The server's `ORDER BY type, title` — alphabetical, trailers last —
                        // plus a vimeo entry the proxy has no thumbnails for.
                        extraVideos = listOf(
                            extraVideoJson(id = 1, title = "Bloopers", type = "other"),
                            extraVideoJson(id = 2, title = "Making Of", type = "special_feature"),
                            extraVideoJson(id = 3, title = "On Vimeo", type = "trailer", site = "vimeo"),
                            extraVideoJson(id = 4, title = "official teaser", type = "trailer"),
                            extraVideoJson(id = 5, title = "Official Trailer", type = "trailer"),
                        ),
                    ),
                )
            },
        )

        val viewModel = viewModel(http)
        viewModel.open(1)
        val movie = viewModel.awaitLoaded()

        assertEquals(listOf(4L, 5L, 2L, 1L), movie.extraVideos.map { it.id })
    }

    /** The scraper's casing is not trusted: `normalizedVideoValue` trims and lowercases first. */
    @Test
    fun `the youtube site filter survives casing and whitespace but still drops other sites`() = runTest {
        val http = routedHttp(
            details = {
                jsonResponse(
                    movieDetailsJson(
                        extraVideos = listOf(
                            extraVideoJson(id = 1, title = "Teaser", site = " YouTube "),
                            extraVideoJson(id = 2, title = "Elsewhere", site = "dailymotion"),
                        ),
                    ),
                )
            },
        )

        val viewModel = viewModel(http)
        viewModel.open(1)
        val movie = viewModel.awaitLoaded()

        assertEquals(listOf(1L), movie.extraVideos.map { it.id })
    }

    @Test
    fun `extra video types map to labels with a title-cased fallback`() = runTest {
        val http = routedHttp(
            details = {
                jsonResponse(
                    movieDetailsJson(
                        extraVideos = listOf(
                            extraVideoJson(id = 1, title = "A", type = "trailer"),
                            extraVideoJson(id = 2, title = "B", type = "special_feature"),
                            extraVideoJson(id = 3, title = "C", type = "other"),
                            extraVideoJson(id = 4, title = "D", type = "behind_the_scenes"),
                        ),
                    ),
                )
            },
        )

        val viewModel = viewModel(http)
        viewModel.open(1)
        val movie = viewModel.awaitLoaded()

        assertEquals(
            listOf("Trailer", "Special feature", "Other", "Behind The Scenes"),
            movie.extraVideos.map { it.typeLabel },
        )
    }

    @Test
    fun `extra videos keep their youtube key and thumbnails go through the proxy`() = runTest {
        val http = routedHttp(
            details = {
                jsonResponse(
                    movieDetailsJson(extraVideos = listOf(extraVideoJson(key = "0xbkYZbdIVw"))),
                )
            },
        )

        val viewModel = viewModel(http)
        viewModel.open(1)
        val movie = viewModel.awaitLoaded()

        val extra = movie.extraVideos.single()
        assertEquals("$TEST_SERVER/youtube/thumbnails/0xbkYZbdIVw", extra.thumbnailUrl)
        assertEquals("0xbkYZbdIVw", extra.key)
    }

    @Test
    fun `key crew is directors then up to three writing credits`() = runTest {
        val http = routedHttp(
            details = {
                jsonResponse(
                    movieDetailsJson(
                        crew = listOf(
                            crewMemberJson(id = 1, job = "Novel", department = "Writing", artistName = "W1"),
                            crewMemberJson(id = 2, job = "Director", department = "Directing", artistName = "D"),
                            crewMemberJson(id = 3, job = "Screenplay", department = "Writing", artistName = "W2"),
                            crewMemberJson(id = 4, job = "Screenplay", department = "Writing", artistName = "W3"),
                            crewMemberJson(id = 5, job = "Story", department = "Writing", artistName = "W4"),
                            crewMemberJson(id = 6, job = "Producer", department = "Production", artistName = "P"),
                        ),
                    ),
                )
            },
        )

        val viewModel = viewModel(http)
        viewModel.open(1)
        val movie = viewModel.awaitLoaded()

        assertEquals(
            listOf(
                CrewEntry("Director", "D"),
                CrewEntry("Novel", "W1"),
                CrewEntry("Screenplay", "W2"),
                CrewEntry("Screenplay", "W3"),
            ),
            movie.keyCrew,
        )
    }

    @Test
    fun `scraper zeros never render as data`() = runTest {
        val http = routedHttp(
            details = {
                jsonResponse(
                    movieDetailsJson(
                        criticRating = 0.0,
                        runTimeMinutes = 0,
                        budget = 0.0,
                        revenue = 0.0,
                        tagLine = null,
                        releaseDate = null,
                    ),
                )
            },
        )

        val viewModel = viewModel(http)
        viewModel.open(1)
        val movie = viewModel.awaitLoaded()

        assertNull(movie.ratingBadge)
        assertNull(movie.runtimeText)
        assertNull(movie.about.budget)
        assertNull(movie.about.revenue)
        assertNull(movie.tagline)
        assertNull(movie.releaseDateText)
    }

    /**
     * Every branch of the spoken form: TalkBack reading "1 hours 0 minutes" is exactly the kind
     * of regression the visual chip ("2h 50m", covered elsewhere) would never show.
     */
    @Test
    fun `the spoken runtime uses singular and plural hours and drops empty parts`() = runTest {
        suspend fun spoken(runtimeMinutes: Long): String {
            // Everything else in the metadata row is stripped so the description is exactly
            // the runtime phrase.
            val http = routedHttp(
                details = {
                    jsonResponse(
                        movieDetailsJson(
                            criticRating = null,
                            certification = null,
                            releaseDate = null,
                            runTimeMinutes = runtimeMinutes,
                        ),
                    )
                },
                technical = {
                    jsonResponse(
                        technicalDetailsJson(
                            videoStreams = emptyList(),
                            audioStreams = emptyList(),
                            subtitles = emptyList(),
                        ),
                    )
                },
            )
            val viewModel = viewModel(http)
            viewModel.open(1)
            return viewModel.awaitLoaded().metadataDescription
        }

        assertEquals("45 minutes", spoken(45))
        assertEquals("1 hour", spoken(60))
        assertEquals("1 hour and 30 minutes", spoken(90))
        assertEquals("2 hours", spoken(120))
    }

    @Test
    fun `about is empty only when every field is absent`() = runTest {
        suspend fun aboutFor(language: String?): AboutUi {
            val http = routedHttp(
                details = {
                    // Companies default empty; zero budget and revenue are scraper "no data".
                    jsonResponse(movieDetailsJson(language = language, budget = 0.0, revenue = 0.0))
                },
            )
            val viewModel = viewModel(http)
            viewModel.open(1)
            return viewModel.awaitLoaded().about
        }

        // isEmpty drives whether the About section exists at all, so both answers matter.
        assertTrue(aboutFor(language = null).isEmpty)
        assertFalse(aboutFor(language = "en").isEmpty)
    }

    /** Waits past the details publish for the one carrying resolved playback audio. */
    private suspend fun MovieDetailsViewModel.awaitPlaybackSettings(): PlaybackSettingsUi =
        uiState.first {
            (it.details as? MovieDetailsState.Loaded)?.movie?.playbackSettings?.selectedAudioId != null
        }.let { (it.details as MovieDetailsState.Loaded).movie.playbackSettings!! }

    @Test
    fun `playback settings default to direct, the default audio track, and subtitles off`() =
        runTest {
            val http = routedHttp(
                technical = {
                    jsonResponse(
                        technicalDetailsJson(
                            audioStreams = listOf(
                                audioStreamJson(id = 10, isDefault = true),
                                audioStreamJson(id = 11, isDefault = false, language = "spa"),
                            ),
                            subtitles = listOf(subtitleJson(id = 20)),
                        ),
                    )
                },
            )
            val viewModel = viewModel(http)
            viewModel.open(1)
            val settings = viewModel.awaitPlaybackSettings()

            assertEquals(PlaybackMode.Direct, settings.selectedMode)
            assertEquals(10L, settings.selectedAudioId)
            assertNull(settings.selectedSubtitleId)
            assertEquals(listOf("English · 5.1 surround", "Spanish · 5.1 surround"), settings.audioTracks.map { it.label })
        }

    @Test
    fun `playback selections republish with the explanation applied`() = runTest {
        val http = routedHttp(
            technical = {
                jsonResponse(
                    technicalDetailsJson(
                        audioStreams = listOf(
                            audioStreamJson(id = 10, isDefault = true),
                            audioStreamJson(id = 11, isDefault = false, language = "spa"),
                        ),
                        subtitles = listOf(subtitleJson(id = 20)),
                    ),
                )
            },
        )
        val viewModel = viewModel(http)
        viewModel.open(1)
        viewModel.awaitPlaybackSettings()

        // A non-first track under Direct stays Direct: ExoPlayer selects any embedded track
        // itself, so no remux upgrade happens on the TV.
        viewModel.selectAudioTrack(11)
        var settings = viewModel.awaitPlaybackSettings()
        assertEquals(PlaybackMode.Direct, settings.selectedMode)
        assertEquals(11L, settings.selectedAudioId)
        assertTrue(settings.explanation.contains("You'll hear: Spanish"))

        viewModel.selectPlaybackMode(PlaybackMode.P1080Mbps8)
        viewModel.selectSubtitle(20)
        settings = viewModel.awaitPlaybackSettings()
        assertEquals(PlaybackMode.P1080Mbps8, settings.selectedMode)
        assertEquals(20L, settings.selectedSubtitleId)
        assertTrue(settings.explanation.contains("Subtitles: English."))
    }

    @Test
    fun `a mode picked before technical details arrive survives their arrival`() = runTest {
        val releaseTechnical = CompletableDeferred<Unit>()
        val http = routedHttp(
            technical = {
                releaseTechnical.await()
                jsonResponse(technicalDetailsJson())
            },
        )
        val viewModel = viewModel(http)
        viewModel.open(1)
        viewModel.awaitLoaded()

        viewModel.selectPlaybackMode(PlaybackMode.P720Mbps3)
        val pending = requireNotNull(viewModel.awaitLoaded().playbackSettings)
        assertEquals(PlaybackMode.P720Mbps3, pending.selectedMode)
        // Tracks unknown: the audio section holds its inert stand-in.
        assertNull(pending.selectedAudioId)
        assertFalse(pending.audioTracks.single().enabled)

        releaseTechnical.complete(Unit)
        val settings = viewModel.awaitPlaybackSettings()
        assertEquals(PlaybackMode.P720Mbps3, settings.selectedMode)
        assertEquals(1L, settings.selectedAudioId)
    }

    @Test
    fun `closing the overlay resets playback selections`() = runTest {
        val http = routedHttp()
        val viewModel = viewModel(http)
        viewModel.open(1)
        viewModel.awaitPlaybackSettings()
        viewModel.selectPlaybackMode(PlaybackMode.P720Mbps3)
        assertEquals(
            PlaybackMode.P720Mbps3,
            viewModel.awaitPlaybackSettings().selectedMode,
        )

        viewModel.close()
        viewModel.open(1)

        assertEquals(PlaybackMode.Direct, viewModel.awaitPlaybackSettings().selectedMode)
    }

    @Test
    fun `usd amounts round to whole dollars`() = runTest {
        val http = routedHttp(
            details = { jsonResponse(movieDetailsJson(budget = 1234567.89, revenue = 99.4)) },
        )

        val viewModel = viewModel(http)
        viewModel.open(1)
        val movie = viewModel.awaitLoaded()

        assertEquals("$1,234,568", movie.about.budget)
        assertEquals("$99", movie.about.revenue)
    }

    @Test
    fun `toggle watched is optimistic and adopts the server's answer`() = runTest {
        val http = routedHttp(
            progress = { jsonResponse(watchProgressJson(progressSec = 1800.0, durationSec = 7200.0)) },
            setWatched = { jsonResponse(watchedUpdateJson(watched = true)) },
        )

        val viewModel = viewModel(http)
        viewModel.open(1)
        viewModel.uiState.first {
            (it.details as? MovieDetailsState.Loaded)?.movie?.watched == false
        }

        viewModel.toggleWatched()
        val movie = viewModel.uiState.first {
            (it.details as? MovieDetailsState.Loaded)?.movie?.watched == true
        }.let { (it.details as MovieDetailsState.Loaded).movie }

        assertEquals(true, movie.watched)
        // Marking watched hides the strip even though a position is still stored.
        assertNull(movie.progress)
    }

    @Test
    fun `a failed watched toggle flips the button back`() = runTest {
        val http = routedHttp(
            setWatched = {
                jsonResponse("""{"error":true,"message":"x"}""", HttpStatusCode.InternalServerError)
            },
        )

        val viewModel = viewModel(http)
        viewModel.open(1)
        viewModel.uiState.first {
            (it.details as? MovieDetailsState.Loaded)?.movie?.watched == false
        }

        viewModel.toggleWatched()

        // Optimistically true first; the failure response flips it back.
        viewModel.uiState.first {
            (it.details as? MovieDetailsState.Loaded)?.movie?.watched == false
        }
    }

    @Test
    fun `a failed like toggle flips the button back`() = runTest {
        val http = routedHttp(
            likeStatus = { jsonResponse(likeStatusJson(isLiked = false)) },
            likeToggle = {
                jsonResponse("""{"error":true,"message":"x"}""", HttpStatusCode.InternalServerError)
            },
        )

        val viewModel = viewModel(http)
        viewModel.open(1)
        viewModel.uiState.first {
            (it.details as? MovieDetailsState.Loaded)?.movie?.liked == false
        }

        viewModel.toggleLike()

        // Optimistically true first; the failure response flips it back.
        viewModel.uiState.first {
            (it.details as? MovieDetailsState.Loaded)?.movie?.liked == false
        }
    }

    @Test
    fun `like presses before status resolution send no mutation`() = runTest {
        val releaseStatus = CompletableDeferred<Unit>()
        var likeWrites = 0
        val http = routedHttp(
            likeStatus = {
                releaseStatus.await()
                jsonResponse(likeStatusJson(isLiked = false))
            },
            likeToggle = {
                likeWrites += 1
                jsonResponse(likeToggleJson(isLiked = true))
            },
        )

        val viewModel = viewModel(http)
        viewModel.open(1)
        assertNull(viewModel.awaitLoaded().liked)

        viewModel.toggleLike()
        testScheduler.runCurrent()
        assertEquals(0, likeWrites)

        releaseStatus.complete(Unit)
        viewModel.uiState.first {
            (it.details as? MovieDetailsState.Loaded)?.movie?.liked == false
        }
        viewModel.toggleLike()

        assertEquals(1, likeWrites)
    }

    @Test
    fun `a committed like toggle notifies the like listener and not the watched listener`() = runTest {
        var watchedCommits = 0
        var likeCommits = 0
        val http = routedHttp(
            likeStatus = { jsonResponse(likeStatusJson(isLiked = false)) },
            likeToggle = { jsonResponse(likeToggleJson(isLiked = true)) },
        )
        val viewModel = viewModel(
            http,
            onWatchedStateCommitted = { watchedCommits += 1 },
            onLikeStateCommitted = { likeCommits += 1 },
        )
        viewModel.open(1)
        viewModel.uiState.first {
            (it.details as? MovieDetailsState.Loaded)?.movie?.liked == false
        }

        viewModel.toggleLike()

        assertEquals(1, likeCommits)
        assertEquals(0, watchedCommits)
    }

    @Test
    fun `a committed watched toggle notifies the watched listener and not the like listener`() =
        runTest {
            var watchedCommits = 0
            var likeCommits = 0
            val http = routedHttp(
                progress = { jsonResponse(watchProgressJson(watched = false)) },
                setWatched = { jsonResponse(watchedUpdateJson(watched = true)) },
            )
            val viewModel = viewModel(
                http,
                onWatchedStateCommitted = { watchedCommits += 1 },
                onLikeStateCommitted = { likeCommits += 1 },
            )
            viewModel.open(1)
            viewModel.uiState.first {
                (it.details as? MovieDetailsState.Loaded)?.movie?.watched == false
            }

            viewModel.toggleWatched()

            assertEquals(1, watchedCommits)
            assertEquals(0, likeCommits)
        }

    @Test
    fun `rapid watched presses are written once each in order without older completion repaint`() =
        runTest {
            val firstReached = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val secondReached = CompletableDeferred<Unit>()
            val targets = mutableListOf<Boolean>()
            val http = routedHttp(
                progress = { jsonResponse(watchProgressJson(watched = false)) },
                setWatched = { request ->
                    val target = String(request.body.toByteArray()).contains("\"watched\":true")
                    targets += target
                    if (targets.size == 1) {
                        firstReached.complete(Unit)
                        releaseFirst.await()
                    } else {
                        secondReached.complete(Unit)
                    }
                    jsonResponse(watchedUpdateJson(watched = target))
                },
            )

            val viewModel = viewModel(http)
            viewModel.open(1)
            viewModel.uiState.first {
                (it.details as? MovieDetailsState.Loaded)?.movie?.watched == false
            }

            viewModel.toggleWatched()
            firstReached.await()
            viewModel.toggleWatched()

            // The second press is already visible, but its request waits behind the first.
            assertEquals(false, viewModel.awaitLoaded().watched)
            assertEquals(listOf(true), targets)

            releaseFirst.complete(Unit)
            withTimeout(WRITE_TIMEOUT_MS) { secondReached.await() }
            testScheduler.advanceUntilIdle()

            assertEquals(listOf(true, false), targets)
            assertEquals(false, viewModel.awaitLoaded().watched)
        }

    @Test
    fun `a watched press mid-read keeps the resume position that read carried`() = runTest {
        val progressReached = CompletableDeferred<Unit>()
        val releaseProgress = CompletableDeferred<Unit>()
        val http = routedHttp(
            progress = {
                progressReached.complete(Unit)
                releaseProgress.await()
                jsonResponse(watchProgressJson(progressSec = 1800.0, durationSec = 10200.0))
            },
            setWatched = { request ->
                val target = String(request.body.toByteArray()).contains("\"watched\":true")
                jsonResponse(watchedUpdateJson(watched = target))
            },
        )

        val viewModel = viewModel(http)
        viewModel.open(1)
        progressReached.await()

        // The press bumps the epoch while the read is in flight, so the `watched` flag it answers
        // with is stale — but the position in the same payload is nobody's to stomp.
        viewModel.toggleWatched()
        releaseProgress.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(true, viewModel.awaitLoaded().watched)

        // Watched hides the strip, so unwatching is what proves the position survived at all.
        viewModel.toggleWatched()
        testScheduler.advanceUntilIdle()

        val movie = viewModel.awaitLoaded()
        assertEquals(false, movie.watched)
        val progress = requireNotNull(movie.progress)
        assertEquals("2h 20m left", progress.remainingTimeLabel)
        assertEquals(
            "Resume from 30 minutes and 0 seconds; 2 hours and 20 minutes remaining",
            progress.resumeStateDescription,
        )
    }

    @Test
    fun `a settled movie's toggle state is dropped once the screen moves on`() = runTest {
        val holdReopenAfterOpen = CompletableDeferred<Unit>()
        val holdReopenAfterClose = CompletableDeferred<Unit>()
        var likeReads = 0
        val http = routedHttp(
            details = { request ->
                val id = request.url.encodedPath.substringAfterLast('/').toLong()
                jsonResponse(populatedDetailsJson(id))
            },
            // Only the two reopens are held, so "liked is still null" means the entry was
            // dropped rather than that the read simply had not landed yet.
            likeStatus = {
                likeReads += 1
                when (likeReads) {
                    3 -> holdReopenAfterOpen.await()
                    4 -> holdReopenAfterClose.await()
                }
                jsonResponse(likeStatusJson(isLiked = true))
            },
        )

        val viewModel = viewModel(http)
        viewModel.open(1)
        viewModel.uiState.first { (it.details as? MovieDetailsState.Loaded)?.movie?.liked == true }

        // Opening another movie retires the entry of the one left behind...
        viewModel.open(2)
        viewModel.open(1)
        assertNull(viewModel.awaitLoaded().liked)
        holdReopenAfterOpen.complete(Unit)
        viewModel.uiState.first { (it.details as? MovieDetailsState.Loaded)?.movie?.liked == true }

        // ...and so does closing the overlay.
        viewModel.close()
        viewModel.open(1)
        assertNull(viewModel.awaitLoaded().liked)

        holdReopenAfterClose.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(true, viewModel.awaitLoaded().liked)
    }

    /**
     * The other half of the pruning rule: `open(keep = movieId)` spares the movie being opened.
     * A write still pending at Back settles after close and stays in the map (nothing prunes it
     * until the next open), so reopening that movie paints the toggle it committed instead of
     * flickering through unknown while the status read is out.
     */
    @Test
    fun `a write settling after Back is kept for reopening the same movie`() = runTest {
        val writeReached = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val holdReopenProgress = CompletableDeferred<Unit>()
        var progressReads = 0
        val http = routedHttp(
            progress = {
                progressReads += 1
                // The reopen's read is held so "watched is already true" can only come from the
                // kept mutation state, not from the read landing first.
                if (progressReads == 2) holdReopenProgress.await()
                jsonResponse(watchProgressJson(watched = progressReads == 2))
            },
            setWatched = {
                writeReached.complete(Unit)
                releaseWrite.await()
                jsonResponse(watchedUpdateJson(watched = true))
            },
        )

        val viewModel = viewModel(http)
        viewModel.open(1)
        viewModel.uiState.first {
            (it.details as? MovieDetailsState.Loaded)?.movie?.watched == false
        }
        viewModel.toggleWatched()
        writeReached.await()
        viewModel.close()
        releaseWrite.complete(Unit)
        testScheduler.advanceUntilIdle()

        viewModel.open(1)
        assertEquals(true, viewModel.awaitLoaded().watched)

        holdReopenProgress.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(true, viewModel.awaitLoaded().watched)
    }

    @Test
    fun `rapid like presses are posted once each in strict order`() = runTest {
        val firstReached = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondReached = CompletableDeferred<Unit>()
        var writes = 0
        val http = routedHttp(
            likeStatus = { jsonResponse(likeStatusJson(isLiked = false)) },
            likeToggle = {
                writes += 1
                if (writes == 1) {
                    firstReached.complete(Unit)
                    releaseFirst.await()
                    jsonResponse(likeToggleJson(isLiked = true))
                } else {
                    secondReached.complete(Unit)
                    jsonResponse(likeToggleJson(isLiked = false))
                }
            },
        )

        val viewModel = viewModel(http)
        viewModel.open(1)
        viewModel.uiState.first {
            (it.details as? MovieDetailsState.Loaded)?.movie?.liked == false
        }

        viewModel.toggleLike()
        firstReached.await()
        viewModel.toggleLike()
        assertEquals(false, viewModel.awaitLoaded().liked)
        assertEquals(1, writes)

        releaseFirst.complete(Unit)
        withTimeout(WRITE_TIMEOUT_MS) { secondReached.await() }
        testScheduler.advanceUntilIdle()

        assertEquals(2, writes)
        assertEquals(false, viewModel.awaitLoaded().liked)
    }

    @Test
    fun `movie A write survives opening B and does not invalidate B status`() = runTest {
        val writeReached = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val writeAnswered = CompletableDeferred<Unit>()
        val bStatusReached = CompletableDeferred<Unit>()
        val releaseBStatus = CompletableDeferred<Unit>()
        val http = routedHttp(
            details = { request ->
                val id = request.url.encodedPath.substringAfterLast('/').toLong()
                jsonResponse(populatedDetailsJson(id))
            },
            progress = { request ->
                val id = request.url.encodedPath.substringAfter("/api/movies/").substringBefore('/')
                if (id == "2") {
                    bStatusReached.complete(Unit)
                    releaseBStatus.await()
                }
                jsonResponse(watchProgressJson(watched = false))
            },
            setWatched = {
                writeReached.complete(Unit)
                releaseWrite.await()
                writeAnswered.complete(Unit)
                jsonResponse(watchedUpdateJson(watched = true))
            },
        )

        val viewModel = viewModel(http)
        viewModel.open(1)
        viewModel.uiState.first {
            (it.details as? MovieDetailsState.Loaded)?.movie?.watched == false
        }
        viewModel.toggleWatched()
        writeReached.await()

        viewModel.open(2)
        bStatusReached.await()
        releaseWrite.complete(Unit)
        withTimeout(WRITE_TIMEOUT_MS) { writeAnswered.await() }
        releaseBStatus.complete(Unit)

        val movieB = viewModel.uiState.first {
            it.openMovieId == 2L &&
                (it.details as? MovieDetailsState.Loaded)?.movie?.watched == false
        }.let { (it.details as MovieDetailsState.Loaded).movie }
        assertEquals(2L, movieB.id)
        assertEquals(false, movieB.watched)
    }

    @Test
    fun `late mutation failure survives Back and a new movie clears its notice`() = runTest {
        val writeReached = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val http = routedHttp(
            details = { request ->
                val id = request.url.encodedPath.substringAfterLast('/').toLong()
                jsonResponse(populatedDetailsJson(id))
            },
            setWatched = {
                writeReached.complete(Unit)
                releaseWrite.await()
                jsonResponse(
                    """{"error":true,"message":"backend refused it"}""",
                    HttpStatusCode.InternalServerError,
                )
            },
        )

        val viewModel = viewModel(http)
        viewModel.open(1)
        viewModel.uiState.first {
            (it.details as? MovieDetailsState.Loaded)?.movie?.watched == false
        }
        viewModel.toggleWatched()
        writeReached.await()
        viewModel.close()
        releaseWrite.complete(Unit)

        val notice = viewModel.uiState.first { it.mutationNotice != null }.mutationNotice
        assertNull(viewModel.uiState.value.openMovieId)
        assertTrue(requireNotNull(notice).contains("watched status"))
        assertTrue(notice.contains("backend refused it"))

        viewModel.open(2)
        assertNull(viewModel.uiState.value.mutationNotice)
    }

    @Test
    fun `failed like reconciles preserves backend text and remains retryable`() = runTest {
        var statusReads = 0
        var writes = 0
        val http = routedHttp(
            likeStatus = {
                statusReads += 1
                jsonResponse(likeStatusJson(isLiked = false))
            },
            likeToggle = {
                writes += 1
                if (writes == 1) {
                    jsonResponse(
                        """{"error":true,"message":"likes are locked"}""",
                        HttpStatusCode.InternalServerError,
                    )
                } else {
                    jsonResponse(likeToggleJson(isLiked = true))
                }
            },
        )

        val viewModel = viewModel(http)
        viewModel.open(1)
        viewModel.uiState.first {
            (it.details as? MovieDetailsState.Loaded)?.movie?.liked == false
        }
        viewModel.toggleLike()

        val failed = viewModel.uiState.first { it.mutationNotice != null }
        assertEquals(2, statusReads)
        assertEquals(false, (failed.details as MovieDetailsState.Loaded).movie.liked)
        assertTrue(requireNotNull(failed.mutationNotice).contains("likes are locked"))

        viewModel.toggleLike()
        testScheduler.advanceUntilIdle()

        assertEquals(2, writes)
        assertEquals(true, viewModel.awaitLoaded().liked)
        assertNull(viewModel.uiState.value.mutationNotice)
    }

    @Test
    fun `watched callback fires once after a successful queue drain only`() = runTest {
        val firstReached = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var watchedWrites = 0
        var likeWrites = 0
        var commits = 0
        val http = routedHttp(
            details = { request ->
                val id = request.url.encodedPath.substringAfterLast('/').toLong()
                jsonResponse(populatedDetailsJson(id))
            },
            likeStatus = { jsonResponse(likeStatusJson(isLiked = false)) },
            setWatched = watched@{ request ->
                watchedWrites += 1
                if (watchedWrites == 1) {
                    firstReached.complete(Unit)
                    releaseFirst.await()
                }
                if (watchedWrites == 3) {
                    return@watched jsonResponse(
                        """{"error":true,"message":"no"}""",
                        HttpStatusCode.InternalServerError,
                    )
                }
                val target = String(request.body.toByteArray()).contains("\"watched\":true")
                jsonResponse(watchedUpdateJson(watched = target))
            },
            likeToggle = {
                likeWrites += 1
                jsonResponse(likeToggleJson(isLiked = true))
            },
        )

        val viewModel = viewModel(http, onWatchedStateCommitted = { commits += 1 })
        viewModel.open(1)
        viewModel.uiState.first {
            val movie = (it.details as? MovieDetailsState.Loaded)?.movie
            movie?.watched == false && movie.liked == false
        }
        viewModel.toggleWatched()
        firstReached.await()
        viewModel.toggleWatched()
        releaseFirst.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals(2, watchedWrites)
        assertEquals(1, commits)

        viewModel.toggleLike()
        testScheduler.advanceUntilIdle()
        assertEquals(1, likeWrites)
        assertEquals(1, commits)

        viewModel.open(2)
        viewModel.uiState.first {
            (it.details as? MovieDetailsState.Loaded)?.movie?.watched == false
        }
        viewModel.toggleWatched()
        testScheduler.advanceUntilIdle()
        assertEquals(3, watchedWrites)
        assertEquals(1, commits)
    }

    @Test
    fun `failed background refreshes retain every renderable secondary value`() = runTest {
        var detailReads = 0
        var technicalReads = 0
        var progressReads = 0
        var likeReads = 0
        val http = routedHttp(
            details = {
                detailReads += 1
                if (detailReads == 2) {
                    jsonResponse("""{"error":true,"message":"x"}""", HttpStatusCode.InternalServerError)
                } else {
                    jsonResponse(populatedDetailsJson())
                }
            },
            technical = {
                technicalReads += 1
                if (technicalReads == 2) {
                    jsonResponse("""{"error":true,"message":"x"}""", HttpStatusCode.InternalServerError)
                } else {
                    jsonResponse(
                        technicalDetailsJson(
                            audioStreams = listOf(
                                audioStreamJson(id = 10, language = "eng", isDefault = true),
                                audioStreamJson(id = 11, language = "spa", isDefault = false),
                            ),
                            subtitles = listOf(subtitleJson(id = 20, language = "eng")),
                        ),
                    )
                }
            },
            progress = {
                progressReads += 1
                if (progressReads == 2) {
                    jsonResponse("""{"error":true,"message":"x"}""", HttpStatusCode.InternalServerError)
                } else {
                    jsonResponse(
                        watchProgressJson(
                            progressSec = 1800.0,
                            durationSec = 10200.0,
                            watched = false,
                        ),
                    )
                }
            },
            likeStatus = {
                likeReads += 1
                if (likeReads == 2) {
                    jsonResponse("""{"error":true,"message":"x"}""", HttpStatusCode.InternalServerError)
                } else {
                    jsonResponse(likeStatusJson(isLiked = true))
                }
            },
        )

        val viewModel = viewModel(http)
        viewModel.open(1)
        testScheduler.advanceUntilIdle()
        viewModel.selectPlaybackMode(PlaybackMode.P1080Mbps8)
        viewModel.selectAudioTrack(11)
        viewModel.selectSubtitle(20)
        val before = viewModel.awaitLoaded()

        viewModel.refresh()
        // Await every second response. Merely awaiting Loaded would return the existing page
        // before any refresh request had settled and would not exercise the failed transitions.
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(2, 2, 2, 2), listOf(detailReads, technicalReads, progressReads, likeReads))
        val after = viewModel.awaitLoaded()

        assertEquals(before.mediaBadges, after.mediaBadges)
        assertEquals(before.metadataDescription, after.metadataDescription)
        assertEquals(before.progress, after.progress)
        assertEquals(false, after.watched)
        assertEquals(true, after.liked)
        val settings = requireNotNull(after.playbackSettings)
        assertEquals(PlaybackMode.P1080Mbps8, settings.selectedMode)
        assertEquals(11L, settings.selectedAudioId)
        assertEquals(20L, settings.selectedSubtitleId)
    }

    /**
     * Leaving the screen must not take the user's change with it: the toggle is a write they
     * asked for, and Back arriving a frame later is normal remote behaviour. The request is
     * held open across the close so cancellation has a window to happen in — without that, the
     * unconfined dispatcher delivers it before Back is even pressed and the test proves nothing.
     */
    @Test
    fun `closing right after a toggle still delivers the write`() = runTest {
        val reachedServer = CompletableDeferred<Unit>()
        val releaseServer = CompletableDeferred<Unit>()
        val answered = CompletableDeferred<Unit>()
        val http = routedHttp(
            setWatched = {
                reachedServer.complete(Unit)
                releaseServer.await()
                answered.complete(Unit)
                jsonResponse(watchedUpdateJson(watched = true))
            },
        )

        val viewModel = viewModel(http)
        viewModel.open(1)
        viewModel.uiState.first {
            (it.details as? MovieDetailsState.Loaded)?.movie?.watched == false
        }

        viewModel.toggleWatched()
        reachedServer.await()
        viewModel.close()
        releaseServer.complete(Unit)

        // Cancelling the job would cancel the handler's continuation, and this would never land.
        withTimeout(WRITE_TIMEOUT_MS) { answered.await() }
        assertNull(viewModel.uiState.value.openMovieId)
    }

    /**
     * The dangerous read is not the one a toggle cancels — it is the one issued *after* the
     * write starts (a resume calls `refresh`) and answered by the server from before that write
     * commits. Finishing later does not make it newer, so it must not set the flag.
     */
    @Test
    fun `a status read issued mid-write does not undo the write`() = runTest {
        val writeReached = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val staleReadRequested = CompletableDeferred<Unit>()
        val releaseStaleRead = CompletableDeferred<Unit>()
        val staleReadAnswered = CompletableDeferred<Unit>()
        var progressReads = 0
        val http = routedHttp(
            progress = {
                progressReads += 1
                if (progressReads > 1) {
                    staleReadRequested.complete(Unit)
                    releaseStaleRead.await()
                    staleReadAnswered.complete(Unit)
                }
                // Both reads carry the server's pre-flip truth.
                jsonResponse(watchProgressJson(watched = false))
            },
            setWatched = {
                writeReached.complete(Unit)
                releaseWrite.await()
                jsonResponse(watchedUpdateJson(watched = true))
            },
        )

        val viewModel = viewModel(http)
        viewModel.open(1)
        viewModel.uiState.first {
            (it.details as? MovieDetailsState.Loaded)?.movie?.watched == false
        }

        viewModel.toggleWatched()
        writeReached.await()
        // The TV resumes mid-write and re-reads everything.
        viewModel.refresh()
        staleReadRequested.await()
        releaseWrite.complete(Unit)
        releaseStaleRead.complete(Unit)
        withTimeout(WRITE_TIMEOUT_MS) { staleReadAnswered.await() }
        testScheduler.advanceUntilIdle()

        assertEquals(true, viewModel.awaitLoaded().watched)
    }

    @Test
    fun `close clears the overlay state`() = runTest {
        val http = routedHttp()

        val viewModel = viewModel(http)
        viewModel.open(1)
        viewModel.awaitLoaded()

        viewModel.close()

        assertNull(viewModel.uiState.value.openMovieId)
        assertEquals(MovieDetailsState.Loading, viewModel.uiState.value.details)
    }

    private companion object {
        const val WRITE_TIMEOUT_MS = 5_000L

        fun populatedDetailsJson(id: Long = 1) = movieDetailsJson(
            id = id,
            cast = listOf(
                // Wire order is not cast order: the mapper must sort.
                castMemberJson(
                    id = 2,
                    artistId = 2,
                    castOrder = 1,
                    artistName = "Robert De Niro",
                    character = "Neil McCauley",
                    artistProfile = "/deniro.jpg",
                ),
                castMemberJson(
                    id = 1,
                    artistId = 1,
                    castOrder = 0,
                    artistName = "Al Pacino",
                    character = "Vincent Hanna",
                    artistProfile = "/pacino.jpg",
                ),
            ),
            crew = listOf(
                crewMemberJson(id = 1, job = "Director", department = "Directing", artistName = "Michael Mann"),
                crewMemberJson(id = 2, job = "Writer", department = "Writing", artistName = "Michael Mann"),
            ),
            genres = listOf(movieGenreJson(id = 1, tag = "Crime"), movieGenreJson(id = 2, tag = "Drama")),
            productionCompanies = listOf(
                productionCompanyJson(id = 1, name = "Regency Enterprises"),
                productionCompanyJson(id = 2, name = "Forward Pass"),
            ),
        )
    }
}
