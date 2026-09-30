package com.igloo.blindpenguincoder.feature.music

import com.igloo.blindpenguincoder.data.model.AlbumTrack
import com.igloo.blindpenguincoder.data.model.SqlNullInt64
import com.igloo.blindpenguincoder.data.repository.TestHttp
import com.igloo.blindpenguincoder.data.repository.albumArtistJson
import com.igloo.blindpenguincoder.data.repository.albumDetailsJson
import com.igloo.blindpenguincoder.data.repository.albumJson
import com.igloo.blindpenguincoder.data.repository.albumTrackJson
import com.igloo.blindpenguincoder.data.repository.jsonResponse
import com.igloo.blindpenguincoder.data.repository.trackGenreJson
import com.igloo.blindpenguincoder.feature.shared.DetailsState
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
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
class AlbumDetailsViewModelTest {

    // A view model outlives the test body — its scope is not runTest's child — so an unfinished
    // load would still be on Dispatchers.Main when the next class calls setMain. Closing cancels
    // it, which is also what Back does in production.
    private val viewModels = mutableListOf<AlbumDetailsViewModel>()

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

    private fun viewModel(http: TestHttp) =
        AlbumDetailsViewModel(http.musicRepository).also { viewModels += it }

    private fun TestScope.http(
        respond: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(albumDetailsJson()) },
    ) = TestHttp(UnconfinedTestDispatcher(testScheduler)) { request ->
        check(request.url.encodedPath.startsWith("/api/music/albums/details/")) {
            "Unrouted path: ${request.url.encodedPath}"
        }
        respond(request)
    }

    private fun AlbumDetailsViewModel.loaded(): AlbumDetailsUi =
        (uiState.value.details as DetailsState.Loaded).value

    private fun track(
        id: Long = 1,
        trackIndex: Long = 1,
        durationMs: Long = 125_000,
        disc: Long = 1,
        codec: String = "flac",
        bitRate: Long = 900_000,
        channelLayout: String = "stereo",
    ) = AlbumTrack(
        id = id,
        title = "Track $id",
        trackIndex = trackIndex,
        duration = durationMs,
        disc = disc,
        codec = codec,
        bitRate = bitRate,
        channelLayout = channelLayout,
        musicianId = SqlNullInt64(4, valid = true),
    )

    @Test
    fun `nothing loads until an album is opened`() = runTest {
        var requests = 0
        val viewModel = viewModel(http { requests++; jsonResponse(albumDetailsJson()) })

        assertNull(viewModel.uiState.value.openAlbumId)
        viewModel.refresh()
        viewModel.retry()

        assertEquals(0, requests)
    }

    @Test
    fun `open reads the album and maps the page`() = runTest {
        val paths = mutableListOf<String>()
        val http = http { request ->
            paths += request.url.encodedPath
            jsonResponse(
                albumDetailsJson(
                    album = albumJson(
                        id = 211,
                        title = "Glacier Sessions",
                        cover = "https://i.scdn.co/image/ab67.jpg",
                        musician = "Aurora Pines",
                        releaseDate = "2026-02-13",
                        year = 2026,
                        spotifyPopularity = 73.4,
                    ),
                    tracks = listOf(
                        albumTrackJson(id = 900, title = "Northern Drift", trackIndex = 1, durationMs = 214_000),
                        albumTrackJson(id = 901, title = "Cold Current", trackIndex = 2, durationMs = 198_000),
                    ),
                    artists = listOf(albumArtistJson(id = 4, name = "Aurora Pines")),
                    trackGenres = listOf(
                        trackGenreJson(trackId = 900, tag = "Ambient"),
                        trackGenreJson(trackId = 900, genreId = 13, tag = "Electronic"),
                    ),
                    albumGenres = listOf("Ambient", "Electronic"),
                    totalDurationMs = 412_000.0,
                ),
            )
        }

        val viewModel = viewModel(http)
        viewModel.open(211)
        testScheduler.advanceUntilIdle()
        val album = viewModel.loaded()

        assertEquals(listOf("/api/music/albums/details/211"), paths)
        assertEquals(211L, viewModel.uiState.value.openAlbumId)
        assertEquals(211L, album.id)
        assertEquals("Glacier Sessions", album.title)
        assertEquals("Aurora Pines", album.artistName)
        // Verbatim: an absolute Spotify URL, never routed through an image proxy.
        assertEquals("https://i.scdn.co/image/ab67.jpg", album.coverUrl)
        assertEquals("February 13, 2026", album.releaseDateText)
        assertEquals("2 tracks", album.trackCountText)
        // 412,000 ms — the millisecond wire unit must land as 6m 52s, not weeks.
        assertEquals("6m 52s", album.totalDurationText)
        assertEquals("Ambient · Electronic", album.genresLine)
        assertEquals(73, album.popularity)
        assertEquals(listOf("Aurora Pines"), album.artists.map { it.name })
        assertEquals(false, album.hasMultipleDiscs)

        val tracks = album.discs.single().tracks
        assertEquals(listOf("Northern Drift", "Cold Current"), tracks.map { it.title })
        assertEquals(listOf("3:34", "3:18"), tracks.map { it.durationText })
        assertEquals("Ambient, Electronic", tracks[0].subtitle)
        assertNull(tracks[1].subtitle)
        assertEquals(
            "Track 1. Northern Drift. Ambient, Electronic. 3 minutes and 34 seconds.",
            tracks[0].spokenInfo,
        )

        assertEquals(
            listOf(
                "Release date" to "February 13, 2026",
                "Total tracks" to "2",
                "Total duration" to "6m 52s",
                "Artist" to "Aurora Pines",
                "Genres" to "Ambient, Electronic",
                "Audio quality" to "FLAC · 900 kbps · stereo",
                "Spotify popularity" to "73 / 100",
            ),
            album.facts.map { it.label to it.value },
        )
        assertEquals(
            "Glacier Sessions by Aurora Pines. 2 tracks. " +
                "Total duration: 6 minutes and 52 seconds. Genres: Ambient, Electronic. " +
                "Spotify popularity 73 out of 100.",
            album.heroInfoDescription,
        )
        assertTrue(album.factsDescription.startsWith("Album details. Release date: February 13, 2026."))
        assertTrue(album.factsDescription.endsWith("Spotify popularity: 73 / 100."))
    }

    @Test
    fun `a bare album drops every optional field instead of showing blanks`() = runTest {
        val viewModel = viewModel(
            http {
                jsonResponse(
                    albumDetailsJson(
                        album = albumJson(
                            title = "",
                            cover = null,
                            musician = null,
                            releaseDate = null,
                            year = null,
                            totalTracks = null,
                            spotifyPopularity = null,
                        ),
                        tracks = listOf(
                            albumTrackJson(durationMs = 0, codec = "", bitRate = 0, channelLayout = ""),
                        ),
                        artists = emptyList(),
                        trackGenres = emptyList(),
                        albumGenres = emptyList(),
                        totalDurationMs = 0.0,
                    ),
                )
            },
        )
        viewModel.open(1)
        testScheduler.advanceUntilIdle()
        val album = viewModel.loaded()

        // An untagged rip must not render an empty title line or a nameless announcement.
        assertEquals("Untitled album", album.title)
        assertNull(album.artistName)
        assertNull(album.coverUrl)
        assertNull(album.releaseDateText)
        assertNull(album.genresLine)
        assertNull(album.popularity)
        assertEquals(emptyList<AlbumArtistUi>(), album.artists)
        val track = album.discs.single().tracks.single()
        // Web parity: a missing duration renders nothing, and the sentence skips it too.
        assertEquals("", track.durationText)
        assertEquals("Track 1. Yesterday.", track.spokenInfo)
        assertEquals(
            listOf("Total tracks" to "1", "Total duration" to "0m 0s"),
            album.facts.map { it.label to it.value },
        )
    }

    @Test
    fun `tracks group by disc in index order and disc zero is disc one`() = runTest {
        val viewModel = viewModel(
            http {
                jsonResponse(
                    albumDetailsJson(
                        tracks = listOf(
                            albumTrackJson(id = 3, title = "D2 T1", trackIndex = 1, disc = 2),
                            albumTrackJson(id = 2, title = "D1 T2", trackIndex = 2, disc = 0),
                            albumTrackJson(id = 1, title = "D1 T1", trackIndex = 1, disc = 1),
                        ),
                    ),
                )
            },
        )
        viewModel.open(1)
        testScheduler.advanceUntilIdle()
        val album = viewModel.loaded()

        assertTrue(album.hasMultipleDiscs)
        assertEquals(listOf(1L, 2L), album.discs.map { it.disc })
        // Disc 0 is an untagged single-disc rip's value; it files under disc 1 (web parity).
        assertEquals(listOf("D1 T1", "D1 T2"), album.discs[0].tracks.map { it.title })
        assertEquals(listOf("D2 T1"), album.discs[1].tracks.map { it.title })
        // The "Disc N" header is plain text a TV screen reader never reaches, so each disc's
        // first row folds it into its own sentence — and only the first row.
        assertTrue(album.discs[0].tracks[0].spokenInfo.startsWith("Disc 1. Track 1."))
        assertTrue(album.discs[0].tracks[1].spokenInfo.startsWith("Track 2."))
        assertTrue(album.discs[1].tracks[0].spokenInfo.startsWith("Disc 2. Track 1."))
        assertEquals(listOf("Discs" to "2"), album.facts.filter { it.label == "Discs" }.map { it.label to it.value })
    }

    @Test
    fun `audio quality is the dominant codec, peak bitrate, and uniform layout`() {
        // Dominant by count; a tie keeps the first codec encountered (web parity).
        assertEquals(
            "FLAC · 900 kbps · stereo",
            audioQualitySummary(
                listOf(
                    track(id = 1, codec = "flac", bitRate = 900_000),
                    track(id = 2, codec = "flac", bitRate = 320_000),
                    track(id = 3, codec = "mp3", bitRate = 128_000),
                ),
            ),
        )
        assertEquals(
            "MP3 · 320 kbps · stereo",
            audioQualitySummary(
                listOf(
                    track(id = 1, codec = "mp3", bitRate = 320_000),
                    track(id = 2, codec = "flac", bitRate = 128_000),
                ),
            ),
        )
        // A mixed layout drops the layout part; a zero bitrate drops the kbps part.
        assertEquals(
            "FLAC",
            audioQualitySummary(
                listOf(
                    track(id = 1, bitRate = 0, channelLayout = "stereo"),
                    track(id = 2, bitRate = 0, channelLayout = "5.1"),
                ),
            ),
        )
        // No codec anywhere: no summary at all rather than an empty chip.
        assertNull(audioQualitySummary(listOf(track(codec = ""))))
        assertNull(audioQualitySummary(emptyList()))
    }

    @Test
    fun `album and track durations format from milliseconds`() {
        assertEquals("1h 2m", formatAlbumDuration(3_735_000))
        assertEquals("42m 10s", formatAlbumDuration(2_530_000))
        assertEquals("0m 0s", formatAlbumDuration(0))
        assertEquals("3:34", formatTrackDuration(214_000))
        assertEquals("", formatTrackDuration(0))
        assertEquals("", formatTrackDuration(-1))
    }

    @Test
    fun `artist chips fall back to the album musician when the join is empty`() = runTest {
        val viewModel = viewModel(
            http {
                jsonResponse(
                    albumDetailsJson(
                        album = albumJson(musician = "Various Artists"),
                        artists = emptyList(),
                    ),
                )
            },
        )
        viewModel.open(1)
        testScheduler.advanceUntilIdle()

        val album = viewModel.loaded()
        assertEquals(listOf("Various Artists"), album.artists.map { it.name })
        assertEquals(
            "Help! by Various Artists. 1 track. Total duration: 2 minutes and 5 seconds. " +
                "Genres: Rock. Spotify popularity 73 out of 100.",
            album.heroInfoDescription,
        )
    }

    @Test
    fun `credited collaborators join the musician in spoken summaries`() = runTest {
        val viewModel = viewModel(
            http {
                jsonResponse(
                    albumDetailsJson(
                        album = albumJson(title = "Shared Record", musician = "Various Artists"),
                        artists = listOf(
                            albumArtistJson(id = 4, name = "Aurora Pines"),
                            albumArtistJson(id = 5, name = "North Harbor Choir"),
                        ),
                    ),
                )
            },
        )
        viewModel.open(1)
        testScheduler.advanceUntilIdle()
        val album = viewModel.loaded()

        assertEquals(listOf("Aurora Pines", "North Harbor Choir"), album.artists.map { it.name })
        assertEquals(
            "Aurora Pines, North Harbor Choir",
            album.facts.single { it.label == "Artist" }.value,
        )
        assertEquals(
            "Shared Record by Various Artists. Artists: Aurora Pines, North Harbor Choir. " +
                "1 track. Total duration: 2 minutes and 5 seconds. Genres: Rock. " +
                "Spotify popularity 73 out of 100.",
            album.heroInfoDescription,
        )
        assertTrue(
            album.factsDescription.contains("Artist: Aurora Pines, North Harbor Choir."),
        )
    }

    @Test
    fun `credited artists remain spoken when the album musician is absent`() = runTest {
        val viewModel = viewModel(
            http {
                jsonResponse(
                    albumDetailsJson(
                        album = albumJson(title = "Joint Record", musician = null),
                        artists = listOf(
                            albumArtistJson(id = 4, name = "Mara Vale"),
                            albumArtistJson(id = 5, name = "The Winter Quartet"),
                        ),
                    ),
                )
            },
        )
        viewModel.open(1)
        testScheduler.advanceUntilIdle()
        val album = viewModel.loaded()

        assertNull(album.artistName)
        assertEquals(
            "Mara Vale, The Winter Quartet",
            album.facts.single { it.label == "Artist" }.value,
        )
        assertEquals(
            "Joint Record. Artists: Mara Vale, The Winter Quartet. 1 track. " +
                "Total duration: 2 minutes and 5 seconds. Genres: Rock. " +
                "Spotify popularity 73 out of 100.",
            album.heroInfoDescription,
        )
    }

    @Test
    fun `a failed open shows the error and retry reloads`() = runTest {
        var fail = true
        val viewModel = viewModel(
            http {
                if (fail) {
                    jsonResponse(
                        """{"error":true,"message":"music scan in progress"}""",
                        HttpStatusCode.InternalServerError,
                    )
                } else {
                    jsonResponse(albumDetailsJson(album = albumJson(title = "Help!")))
                }
            },
        )

        viewModel.open(1)
        testScheduler.advanceUntilIdle()
        assertEquals(
            "music scan in progress",
            (viewModel.uiState.value.details as DetailsState.Error).message,
        )

        fail = false
        viewModel.retry()
        testScheduler.advanceUntilIdle()

        assertEquals("Help!", viewModel.loaded().title)
    }

    @Test
    fun `a failed background refresh keeps the page that is already on screen`() = runTest {
        var fail = false
        val viewModel = viewModel(
            http {
                if (fail) {
                    jsonResponse(
                        """{"error":true,"message":"music is unavailable"}""",
                        HttpStatusCode.InternalServerError,
                    )
                } else {
                    jsonResponse(albumDetailsJson(album = albumJson(title = "Help!")))
                }
            },
        )
        viewModel.open(1)
        testScheduler.advanceUntilIdle()

        fail = true
        viewModel.refresh()
        testScheduler.advanceUntilIdle()

        // A TV waking from standby must not swap a readable page for an error nobody asked for.
        assertEquals("Help!", viewModel.loaded().title)
    }

    @Test
    fun `closing forgets the album and a late response cannot reopen it`() = runTest {
        val viewModel = viewModel(http())
        viewModel.open(1)
        testScheduler.advanceUntilIdle()

        viewModel.close()

        assertNull(viewModel.uiState.value.openAlbumId)
        assertTrue(viewModel.uiState.value.details is DetailsState.Loading)
        // Idempotent: the host closes every detail view model on Back without asking which was up.
        viewModel.close()
        viewModel.refresh()
        testScheduler.advanceUntilIdle()
        assertNull(viewModel.uiState.value.openAlbumId)
    }
}
