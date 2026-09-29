package com.igloo.blindpenguincoder.feature.music

import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.data.model.SimpleAlbum
import com.igloo.blindpenguincoder.data.model.SimpleMusician
import com.igloo.blindpenguincoder.data.model.SqlNullInt64
import com.igloo.blindpenguincoder.data.model.SqlNullString
import com.igloo.blindpenguincoder.data.model.TrackListItem
import com.igloo.blindpenguincoder.feature.shared.PagedState
import com.igloo.blindpenguincoder.playback.model.MusicQueueSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicMappingTest {

    private fun track(id: Long, title: String, musician: String? = "The Beatles", album: String? = "Help!") =
        TrackListItem(
            id = id,
            title = title,
            duration = 125_000,
            albumId = SqlNullInt64(11, valid = album != null),
            albumTitle = SqlNullString(album.orEmpty(), valid = album != null),
            albumCover = SqlNullString("https://i.scdn.co/image/help.jpg", valid = album != null),
            musicianId = SqlNullInt64(4, valid = musician != null),
            musicianName = SqlNullString(musician.orEmpty(), valid = musician != null),
        )

    @Test
    fun `entries put a letter header before each bucket and fold it into that bucket's first row only`() {
        val entries = tracksEntries(
            listOf(track(1, "1999"), track(2, "Abbey Road"), track(3, "All You Need"), track(4, "Blackbird")),
        )

        assertEquals(
            listOf("#", "1999", "A", "Abbey Road", "All You Need", "B", "Blackbird"),
            entries.map { if (it is TracksEntry.Letter) it.letter else (it as TracksEntry.Track).track.title },
        )
        val rows = entries.filterIsInstance<TracksEntry.Track>().map { it.track }
        assertEquals(
            "Tracks starting with a number or symbol. 1999. The Beatles · Help!. 2 minutes and 5 seconds.",
            rows[0].spokenInfo,
        )
        assertEquals(
            "Tracks starting with A. Abbey Road. The Beatles · Help!. 2 minutes and 5 seconds.",
            rows[1].spokenInfo,
        )
        assertEquals("All You Need. The Beatles · Help!. 2 minutes and 5 seconds.", rows[2].spokenInfo)
        assertEquals("The Beatles · Help!", rows[1].subtitle)
        assertEquals("2:05", rows[1].durationText)
        assertEquals(11L, rows[1].albumId)
        assertEquals(4L, rows[1].musicianId)
        assertNull(rows[1].indexText)
    }

    @Test
    fun `a row without joins has no subtitle and nowhere for More to go`() {
        val row = (tracksEntries(listOf(track(1, "Solo", musician = null, album = null)))[1] as TracksEntry.Track).track

        assertNull(row.subtitle)
        assertNull(row.albumId)
        assertNull(row.musicianId)
        assertEquals("Tracks starting with S. Solo. 2 minutes and 5 seconds.", row.spokenInfo)
    }

    @Test
    fun `a musician card counts its albums and tracks and speaks them as one sentence`() {
        val card = SimpleMusician(
            id = 4,
            name = "The Beatles",
            thumb = SqlNullString("https://i.scdn.co/image/beatles.jpg", valid = true),
            albumCount = 1,
            trackCount = 40,
        ).toCardUi()

        assertEquals("1 album · 40 tracks", card.countsLine)
        assertEquals("The Beatles. 1 album, 40 tracks.", card.spoken)
        assertEquals("https://i.scdn.co/image/beatles.jpg", card.thumbUrl)
    }

    @Test
    fun `an album card drops blank musician and cover`() {
        val card = SimpleAlbum(
            id = 1,
            title = "",
            cover = SqlNullString("", valid = true),
            musician = SqlNullString(" ", valid = true),
        ).toCardUi()

        assertEquals("Untitled album", card.title)
        assertNull(card.subtitle)
        assertNull(card.coverUrl)
    }

    @Test
    fun `a row's play queues the loaded list from that row`() {
        val loaded = listOf(track(1, "A"), track(2, "B"), track(3, "C"))

        val request = trackListPlayRequest(loaded, trackId = 2)!!

        assertEquals(MusicQueueSource.TrackList, request.source)
        assertEquals(1, request.startIndex)
        assertEquals(listOf(1L, 2L, 3L), request.tracks.map { it.id })
        assertNull(trackListPlayRequest(loaded, trackId = 99))
    }

    @Test
    fun `play all starts at the top and continues from the loaded count`() {
        val loaded = listOf(track(1, "A"), track(2, "B"))

        val request = playAllRequest(loaded, total = 120)!!

        assertEquals(MusicQueueSource.LibraryInOrder(nextOffset = 2, total = 120), request.source)
        assertEquals(0, request.startIndex)
        assertNull(playAllRequest(emptyList(), total = 0))
    }

    @Test
    fun `shuffle all is the server's batch with duplicates dropped, or nothing for an empty batch`() {
        val request = shuffleAllRequest(listOf(track(5, "E"), track(5, "E"), track(6, "F")))!!

        assertEquals(MusicQueueSource.LibraryShuffle, request.source)
        assertEquals(listOf(5L, 6L), request.tracks.map { it.id })
        assertNull(shuffleAllRequest(emptyList()))
    }

    @Test
    fun `the selected loaded count is rows only, so the Tracks tab does not count its letter headers`() {
        val entries = tracksEntries(listOf(track(1, "Abbey Road"), track(2, "Blackbird"), track(3, "Because")))
        val tracks = PagedState(IglooRailState.Loaded(entries), total = 3)

        assertEquals(5, entries.size)
        assertEquals(3, MusicUiState(tab = MusicTab.Tracks, tracks = tracks).selectedLoadedCount)
        assertNull(MusicUiState(tab = MusicTab.Albums, tracks = tracks).selectedLoadedCount)
    }
}
