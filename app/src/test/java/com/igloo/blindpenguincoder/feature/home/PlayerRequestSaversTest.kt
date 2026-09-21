package com.igloo.blindpenguincoder.feature.home

import androidx.compose.runtime.saveable.SaverScope
import com.igloo.blindpenguincoder.data.model.PlaybackMode
import com.igloo.blindpenguincoder.playback.model.MoviePlayRequest
import com.igloo.blindpenguincoder.playback.model.MusicPlayRequest
import com.igloo.blindpenguincoder.playback.model.MAX_QUEUE_TRACKS
import com.igloo.blindpenguincoder.playback.model.MusicPlayTrack
import com.igloo.blindpenguincoder.playback.model.MusicQueueSource
import com.igloo.blindpenguincoder.playback.model.PlayableAudioTrack
import com.igloo.blindpenguincoder.playback.model.PlaybackChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two overlay savers. Restore runs inside saved-state restoration, over a bundle a possibly
 * older build wrote, so a round trip is not the only contract that matters: a slot it cannot
 * parse must come back as "no overlay", never as a throw that crashes the relaunch.
 */
class PlayerRequestSaversTest {

    // Every value is saveable; the savers never consult the scope.
    private val scope = SaverScope { true }

    private val album = MusicPlayRequest(
        source = MusicQueueSource.Album(albumId = 11, title = "Help!"),
        startIndex = 1,
        tracks = listOf(
            MusicPlayTrack(901, "Yesterday", 125.0, "The Beatles", "Help!", "https://i.scdn.co/image/abc"),
            MusicPlayTrack(902, "Ticket to Ride", 190.0, "The Beatles", "Help!", "https://i.scdn.co/image/abc"),
        ),
    )

    private val movie = MoviePlayRequest(
        movieId = 7,
        title = "Arrival",
        posterUrl = null,
        mimeType = "video/x-matroska",
        mode = PlaybackMode.Direct,
        audioTypeIndex = 1,
        subtitleTypeIndex = null,
        audioTracks = listOf(PlayableAudioTrack(label = "English", codec = "eac3", channels = 6)),
        subtitleTracks = emptyList(),
        resumeAtSec = 812.5,
        durationSec = 6960.0,
        chapters = listOf(PlaybackChapter(title = "Opening", startTimeSec = 0.0)),
    )

    private fun <T> roundTrip(saver: androidx.compose.runtime.saveable.Saver<T?, List<String>>, value: T?): T? =
        with(saver) { scope.save(value) }.let { saved -> saver.restore(saved ?: emptyList()) }

    @Test
    fun `an album survives a round trip whole`() {
        assertEquals(album, roundTrip(MusicPlayRequestSaver, album))
    }

    @Test
    fun `a blank artist and cover restore as null, not as empty text`() {
        val bare = album.copy(
            tracks = album.tracks.map { it.copy(artistName = null, albumTitle = null, coverUrl = null) },
        )
        assertEquals(bare, roundTrip(MusicPlayRequestSaver, bare))
    }

    /** Every source survives, including the in-order cursor an endless queue resumes from. */
    @Test
    fun `every queue source survives a round trip`() {
        val sources = listOf(
            MusicQueueSource.Musician(musicianId = 4, title = "The Beatles"),
            MusicQueueSource.TrackList,
            MusicQueueSource.LibraryInOrder(nextOffset = 150, total = 1234),
            MusicQueueSource.LibraryShuffle,
        )
        sources.forEach { source ->
            val request = album.copy(source = source)
            assertEquals(request, roundTrip(MusicPlayRequestSaver, request))
        }
    }

    @Test
    fun `a queue past the ceiling saves as no overlay rather than a truncated queue`() {
        val long = album.copy(
            tracks = (1..MAX_QUEUE_TRACKS + 1).map { MusicPlayTrack(it.toLong(), "T$it", 100.0) },
        )
        assertNull(roundTrip(MusicPlayRequestSaver, long))

        val atCeiling = album.copy(
            tracks = (1..MAX_QUEUE_TRACKS).map { MusicPlayTrack(it.toLong(), "T$it", 100.0) },
        )
        assertEquals(atCeiling, roundTrip(MusicPlayRequestSaver, atCeiling))
    }

    @Test
    fun `an unknown queue source restores as no overlay instead of throwing`() {
        val saved = with(MusicPlayRequestSaver) { scope.save(album) }!!.toMutableList()
        saved[0] = """{"type":"fromANewerBuild"}"""
        assertNull(MusicPlayRequestSaver.restore(saved))
    }

    @Test
    fun `an empty queue survives, so the player can report it rather than guess`() {
        val empty = album.copy(tracks = emptyList())
        assertEquals(empty, roundTrip(MusicPlayRequestSaver, empty))
    }

    @Test
    fun `no album saves and restores as no overlay`() {
        assertNull(roundTrip(MusicPlayRequestSaver, null))
    }

    @Test
    fun `a movie survives a round trip whole`() {
        assertEquals(movie, roundTrip(MoviePlayRequestSaver, movie))
    }

    @Test
    fun `no movie saves and restores as no overlay`() {
        assertNull(roundTrip(MoviePlayRequestSaver, null))
    }

    @Test
    fun `an unparseable queue restores as no overlay instead of throwing`() {
        val corrupt = listOf("""{"type":"tracks"}""", "0", "{not json")
        assertNull(MusicPlayRequestSaver.restore(corrupt))
    }

    @Test
    fun `an unknown playback mode restores as no overlay instead of throwing`() {
        val saved = with(MoviePlayRequestSaver) { scope.save(movie) }!!.toMutableList()
        saved[4] = "SomeModeFromANewerBuild"
        assertNull(MoviePlayRequestSaver.restore(saved))
    }

    @Test
    fun `a truncated bundle restores as no overlay instead of throwing`() {
        assertNull(MusicPlayRequestSaver.restore(listOf("""{"type":"tracks"}""", "0")))
        assertNull(MoviePlayRequestSaver.restore(listOf("7", "Arrival")))
    }
}
