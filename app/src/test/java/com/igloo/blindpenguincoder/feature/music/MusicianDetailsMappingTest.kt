package com.igloo.blindpenguincoder.feature.music

import com.igloo.blindpenguincoder.data.model.Musician
import com.igloo.blindpenguincoder.data.model.MusicianAlbum
import com.igloo.blindpenguincoder.data.model.MusicianDetailsData
import com.igloo.blindpenguincoder.data.model.MusicianTrack
import com.igloo.blindpenguincoder.data.model.SqlNullFloat64
import com.igloo.blindpenguincoder.data.model.SqlNullInt64
import com.igloo.blindpenguincoder.data.model.SqlNullString
import com.igloo.blindpenguincoder.playback.model.MusicQueueSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MusicianDetailsMappingTest {

    private fun musician(
        name: String = "The Beatles",
        summary: String? = "Liverpool, 1960.",
        popularity: Double? = 88.4,
        followers: Long? = 25_000_000,
        thumb: String? = "https://i.scdn.co/image/beatles.jpg",
    ) = Musician(
        id = 4,
        name = name,
        summary = SqlNullString(summary.orEmpty(), valid = summary != null),
        spotifyPopularity = SqlNullFloat64(popularity ?: 0.0, valid = popularity != null),
        spotifyFollowers = SqlNullInt64(followers ?: 0, valid = followers != null),
        thumb = SqlNullString(thumb.orEmpty(), valid = thumb != null),
    )

    private fun album(id: Long, title: String, year: Long?, cover: String? = null) = MusicianAlbum(
        id = id,
        title = title,
        cover = SqlNullString(cover.orEmpty(), valid = cover != null),
        year = SqlNullInt64(year ?: 0, valid = year != null),
    )

    private fun track(id: Long, title: String, durationMs: Long, albumId: Long?, albumTitle: String?) = MusicianTrack(
        id = id,
        title = title,
        duration = durationMs,
        albumId = SqlNullInt64(albumId ?: 0, valid = albumId != null),
        albumTitle = SqlNullString(albumTitle.orEmpty(), valid = albumTitle != null),
    )

    private fun data(
        musician: Musician = musician(),
        albums: List<MusicianAlbum> = listOf(album(11, "Help!", 1965, "https://i.scdn.co/image/help.jpg")),
        tracks: List<MusicianTrack> = listOf(track(951, "Yesterday", 125_000, 11, "Help!")),
        genres: List<String> = listOf("Rock", "Pop"),
    ) = MusicianDetailsData(
        musician = musician,
        albums = albums,
        tracks = tracks,
        genres = genres,
        totalDuration = tracks.sumOf { it.duration }.toDouble(),
    )

    @Test
    fun `the hero carries counts, duration, genres, popularity and followers`() {
        val ui = toMusicianDetailsUi(data())

        assertEquals("The Beatles", ui.name)
        assertEquals("1 album", ui.albumCountText)
        assertEquals("1 track", ui.trackCountText)
        assertEquals("2m 5s", ui.totalDurationText)
        assertEquals("Rock · Pop", ui.genresLine)
        assertEquals(88, ui.popularity)
        assertEquals(
            "The Beatles. 1 album, 1 track. Total duration: 2 minutes and 5 seconds. " +
                "Genres: Rock, Pop. Spotify popularity 88 out of 100.",
            ui.heroInfoDescription,
        )
        assertEquals(
            listOf("Albums", "Tracks", "Total duration", "Genres", "Spotify popularity", "Spotify followers", "About"),
            ui.facts.map { it.label },
        )
        assertTrue(ui.factsDescription.startsWith("Artist details. Albums: 1. Tracks: 1."))
    }

    @Test
    fun `a bare musician drops every optional field`() {
        val ui = toMusicianDetailsUi(
            data(
                musician = musician(name = "", summary = null, popularity = null, followers = null, thumb = null),
                albums = emptyList(),
                tracks = emptyList(),
                genres = emptyList(),
            ),
        )

        assertEquals("Unknown artist", ui.name)
        assertNull(ui.thumbUrl)
        assertNull(ui.genresLine)
        assertNull(ui.popularity)
        assertEquals("0 albums", ui.albumCountText)
        assertEquals(listOf("Albums", "Tracks", "Total duration"), ui.facts.map { it.label })
        assertEquals("Unknown artist. 0 albums, 0 tracks. Total duration: 0 seconds.", ui.heroInfoDescription)
    }

    @Test
    fun `rows carry their album for More and speak the album as the subtitle`() {
        val ui = toMusicianDetailsUi(
            data(tracks = listOf(track(951, "Yesterday", 125_000, 11, "Help!"), track(952, "Demo", 0, null, null))),
        )

        val (first, second) = ui.tracks
        assertEquals("Help!", first.subtitle)
        assertEquals(11L, first.albumId)
        assertNull(first.musicianId)
        assertEquals("Yesterday. Help!. 2 minutes and 5 seconds.", first.spokenInfo)
        assertNull(second.subtitle)
        assertNull(second.albumId)
        assertEquals("", second.durationText)
        assertEquals("Demo.", second.spokenInfo)
    }

    @Test
    fun `discography cards show the year under the title`() {
        val ui = toMusicianDetailsUi(data(albums = listOf(album(11, "Help!", 1965), album(12, "", null))))

        assertEquals(listOf("Help!", "Untitled album"), ui.albums.map { it.title })
        assertEquals(listOf("1965", null), ui.albums.map { it.subtitle })
    }

    @Test
    fun `the play request is the musician queue with the album's cover on each entry`() {
        val ui = toMusicianDetailsUi(data())

        val request = toMusicPlayRequest(ui, startIndex = 0)

        assertEquals(MusicQueueSource.Musician(musicianId = 4, title = "The Beatles"), request.source)
        val entry = request.tracks.single()
        assertEquals("The Beatles", entry.artistName)
        assertEquals("Help!", entry.albumTitle)
        assertEquals("https://i.scdn.co/image/help.jpg", entry.coverUrl)
    }

    @Test
    fun `shuffle is a permutation on a copy`() {
        val ui = toMusicianDetailsUi(
            data(tracks = (1L..6L).map { track(it, "T$it", 1000, 11, "Help!") }),
        )
        val inOrder = toMusicPlayRequest(ui)

        val shuffles = (1L..5L).map { toShuffledMusicPlayRequest(ui, Random(it)) }

        shuffles.forEach { assertEquals(inOrder.tracks.toSet(), it.tracks.toSet()) }
        assertTrue(shuffles.any { it.tracks != inOrder.tracks })
        assertEquals((1L..6L).toList(), ui.tracks.map { it.id })
    }
}
