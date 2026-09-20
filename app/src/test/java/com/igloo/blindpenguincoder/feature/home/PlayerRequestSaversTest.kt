package com.igloo.blindpenguincoder.feature.home

import androidx.compose.runtime.saveable.SaverScope
import com.igloo.blindpenguincoder.data.model.PlaybackMode
import com.igloo.blindpenguincoder.playback.model.MoviePlayRequest
import com.igloo.blindpenguincoder.playback.model.MusicPlayRequest
import com.igloo.blindpenguincoder.playback.model.MusicPlayTrack
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
        albumId = 11,
        albumTitle = "Help!",
        artistName = "The Beatles",
        coverUrl = "https://i.scdn.co/image/abc",
        tracks = listOf(
            MusicPlayTrack(id = 901, title = "Yesterday", durationSec = 125.0),
            MusicPlayTrack(id = 902, title = "Ticket to Ride", durationSec = 190.0),
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
        val bare = album.copy(artistName = null, coverUrl = null)
        assertEquals(bare, roundTrip(MusicPlayRequestSaver, bare))
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
        val corrupt = listOf("11", "Help!", "", "", "{not json")
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
        assertNull(MusicPlayRequestSaver.restore(listOf("11", "Help!")))
        assertNull(MoviePlayRequestSaver.restore(listOf("7", "Arrival")))
    }
}
