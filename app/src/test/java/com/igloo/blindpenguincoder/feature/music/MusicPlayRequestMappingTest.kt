package com.igloo.blindpenguincoder.feature.music

import com.igloo.blindpenguincoder.data.model.Album
import com.igloo.blindpenguincoder.data.model.AlbumDetailsData
import com.igloo.blindpenguincoder.data.model.AlbumTrack
import com.igloo.blindpenguincoder.data.model.SqlNullFloat64
import com.igloo.blindpenguincoder.data.model.SqlNullInt64
import com.igloo.blindpenguincoder.data.model.SqlNullString
import com.igloo.blindpenguincoder.playback.model.MusicQueueSource
import com.igloo.blindpenguincoder.playback.queue.shuffled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class MusicPlayRequestMappingTest {

    private fun album(
        title: String = "Test Album",
        musician: String? = "Test Artist",
        cover: String? = "https://i.scdn.co/image/abc",
    ) = Album(
        id = 7L,
        title = title,
        spotifyPopularity = SqlNullFloat64(0.0, false),
        musician = SqlNullString(musician.orEmpty(), musician != null),
        releaseDate = SqlNullString("", false),
        year = SqlNullInt64(0, false),
        cover = SqlNullString(cover.orEmpty(), cover != null),
    )

    private fun track(id: Long, title: String, index: Long, disc: Long, durationMs: Long) =
        AlbumTrack(
            id = id,
            title = title,
            trackIndex = index,
            duration = durationMs,
            disc = disc,
            codec = "flac",
            bitRate = 900_000,
            channelLayout = "stereo",
            musicianId = SqlNullInt64(4, valid = true),
        )

    private fun details(vararg tracks: AlbumTrack) = AlbumDetailsData(
        album = album(),
        tracks = tracks.toList(),
        artists = emptyList(),
        trackGenres = emptyList(),
        albumGenres = emptyList(),
        totalDuration = tracks.sumOf { it.duration }.toDouble(),
    )

    @Test
    fun `queue flattens discs in disc-then-track order`() {
        // Deliberately shuffled on the wire; the detail page's ordering rules apply.
        val request = toMusicPlayRequest(
            toAlbumDetailsUi(
                details(
                    track(id = 21, title = "D2 T1", index = 1, disc = 2, durationMs = 100_000),
                    track(id = 12, title = "D1 T2", index = 2, disc = 1, durationMs = 100_000),
                    track(id = 11, title = "D1 T1", index = 1, disc = 1, durationMs = 100_000),
                    track(id = 22, title = "D2 T2", index = 2, disc = 2, durationMs = 100_000),
                ),
            ),
        )
        assertEquals(listOf(11L, 12L, 21L, 22L), request.tracks.map { it.id })
        assertEquals(listOf("D1 T1", "D1 T2", "D2 T1", "D2 T2"), request.tracks.map { it.title })
    }

    @Test
    fun `durations arrive in seconds and a missing duration becomes zero`() {
        val request = toMusicPlayRequest(
            toAlbumDetailsUi(
                details(
                    track(id = 1, title = "Timed", index = 1, disc = 1, durationMs = 187_500),
                    track(id = 2, title = "Untimed", index = 2, disc = 1, durationMs = 0),
                ),
            ),
        )
        assertEquals(187.5, request.tracks[0].durationSec, 0.0)
        assertEquals(0.0, request.tracks[1].durationSec, 0.0)
    }

    @Test
    fun `album identity is the source and rides on every entry`() {
        val request = toMusicPlayRequest(
            toAlbumDetailsUi(
                details(
                    track(id = 1, title = "One", index = 1, disc = 1, durationMs = 1000),
                    track(id = 2, title = "Two", index = 2, disc = 1, durationMs = 1000),
                ),
            ),
        )
        assertEquals(MusicQueueSource.Album(albumId = 7L, title = "Test Album"), request.source)
        assertEquals(0, request.startIndex)
        request.tracks.forEach { entry ->
            assertEquals("Test Artist", entry.artistName)
            assertEquals("Test Album", entry.albumTitle)
            assertEquals("https://i.scdn.co/image/abc", entry.coverUrl)
        }
    }

    @Test
    fun `a row's play names its flat position as the start`() {
        val request = toMusicPlayRequest(
            toAlbumDetailsUi(
                details(
                    track(id = 11, title = "D1 T1", index = 1, disc = 1, durationMs = 1000),
                    track(id = 21, title = "D2 T1", index = 1, disc = 2, durationMs = 1000),
                ),
            ),
            startIndex = 1,
        )
        assertEquals(1, request.startIndex)
        assertEquals(21L, request.tracks[request.startIndex].id)
    }

    @Test
    fun `blank artist and cover become null`() {
        val data = AlbumDetailsData(
            album = album(musician = "  ", cover = null),
            tracks = listOf(track(id = 1, title = "One", index = 1, disc = 1, durationMs = 1000)),
            artists = emptyList(),
            trackGenres = emptyList(),
            albumGenres = emptyList(),
            totalDuration = 1000.0,
        )
        val request = toMusicPlayRequest(toAlbumDetailsUi(data))
        assertNull(request.tracks.single().artistName)
        assertNull(request.tracks.single().coverUrl)
    }

    @Test
    fun `shuffle is a permutation of the album that starts at the top and leaves the page alone`() {
        val album = toAlbumDetailsUi(
            details(
                track(id = 11, title = "One", index = 1, disc = 1, durationMs = 1000),
                track(id = 12, title = "Two", index = 2, disc = 1, durationMs = 1000),
                track(id = 13, title = "Three", index = 3, disc = 1, durationMs = 1000),
                track(id = 14, title = "Four", index = 4, disc = 1, durationMs = 1000),
            ),
        )
        val inOrder = toMusicPlayRequest(album)

        val shuffles = (1L..5L).map { seed -> inOrder.shuffled(Random(seed)) }

        shuffles.forEach { shuffled ->
            assertEquals(inOrder.source, shuffled.source)
            assertEquals(0, shuffled.startIndex)
            assertEquals(inOrder.tracks.toSet(), shuffled.tracks.toSet())
            assertEquals(4, shuffled.tracks.size)
        }
        // Any one seed may land on the identity; five in a row cannot.
        assertTrue(shuffles.any { it.tracks != inOrder.tracks })
        // The page's own order is untouched: the shuffle worked on a copy.
        assertEquals(listOf(11L, 12L, 13L, 14L), album.discs.single().tracks.map { it.id })
    }
}
