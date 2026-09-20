package com.igloo.blindpenguincoder.playback.queue

import com.igloo.blindpenguincoder.data.model.SqlNullInt64
import com.igloo.blindpenguincoder.data.model.SqlNullString
import com.igloo.blindpenguincoder.data.model.TrackListItem
import com.igloo.blindpenguincoder.playback.model.MusicPlayTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class MusicQueueMappingTest {

    private fun row(
        id: Long = 900,
        durationMs: Long = 125_000,
        musician: String? = "The Beatles",
        albumTitle: String? = "Help!",
        cover: String? = "https://i.scdn.co/image/help.jpg",
    ) = TrackListItem(
        id = id,
        title = "Yesterday",
        duration = durationMs,
        codec = "flac",
        bitRate = 900_000,
        albumId = SqlNullInt64(1, valid = albumTitle != null),
        albumTitle = SqlNullString(albumTitle.orEmpty(), valid = albumTitle != null),
        albumCover = SqlNullString(cover.orEmpty(), valid = cover != null),
        musicianId = SqlNullInt64(4, valid = musician != null),
        musicianName = SqlNullString(musician.orEmpty(), valid = musician != null),
    )

    @Test
    fun `a library row maps milliseconds to seconds and carries its own metadata`() {
        val track = row().toMusicPlayTrack()

        assertEquals(900L, track.id)
        assertEquals("Yesterday", track.title)
        assertEquals(125.0, track.durationSec, 0.0)
        assertEquals("The Beatles", track.artistName)
        assertEquals("Help!", track.albumTitle)
        assertEquals("https://i.scdn.co/image/help.jpg", track.coverUrl)
    }

    @Test
    fun `invalid wrappers and a missing duration become null and zero`() {
        val track = row(durationMs = 0, musician = null, albumTitle = null, cover = null)
            .toMusicPlayTrack()

        assertEquals(0.0, track.durationSec, 0.0)
        assertNull(track.artistName)
        assertNull(track.albumTitle)
        assertNull(track.coverUrl)
    }

    @Test
    fun `a blank wrapper is absent, the album page's rule`() {
        val track = row(musician = "  ", cover = "").toMusicPlayTrack()

        assertNull(track.artistName)
        assertNull(track.coverUrl)
    }

    @Test
    fun `a shuffled queue is a permutation on a copy with duplicates dropped`() {
        val original = (1L..6L).map { MusicPlayTrack(it, "T$it", 100.0) } +
            MusicPlayTrack(3, "T3 again", 100.0)
        val snapshot = original.toList()

        val shuffles = (1L..5L).map { seed -> original.shuffledQueue(Random(seed)) }

        shuffles.forEach { shuffled ->
            assertEquals((1L..6L).toSet(), shuffled.map { it.id }.toSet())
            assertEquals(6, shuffled.size)
        }
        // Any one seed may land on the identity; five in a row cannot.
        assertTrue(shuffles.any { it != original.take(6) })
        assertEquals(snapshot, original)
    }
}
