package com.igloo.blindpenguincoder.feature.music

import com.igloo.blindpenguincoder.core.ui.formatSpokenTime
import com.igloo.blindpenguincoder.data.model.MusicianDetailsData
import com.igloo.blindpenguincoder.data.model.MusicianTrack
import com.igloo.blindpenguincoder.feature.shared.TrackRowUi
import com.igloo.blindpenguincoder.feature.shared.trackSpokenInfo
import com.igloo.blindpenguincoder.playback.model.MusicPlayRequest
import com.igloo.blindpenguincoder.playback.model.MusicPlayTrack
import com.igloo.blindpenguincoder.playback.model.MusicQueueSource
import com.igloo.blindpenguincoder.playback.queue.shuffledQueue
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Wire-to-render rules for the musician detail screen (docs/design-system.md section 11.5.2),
 * pure so every formatting decision is JVM-testable. Web parity: the reference is the web
 * client's `routes/_auth/music/musician.$id.tsx`. Durations on this endpoint are milliseconds.
 */
data class MusicianDetailsUi(
    val id: Long,
    val name: String,
    /** Verbatim: an absolute URL or nothing; there is no music image proxy. */
    val thumbUrl: String?,
    val summary: String?,
    val albumCountText: String,
    val trackCountText: String,
    val totalDurationText: String,
    val genresLine: String?,
    /** Spotify popularity, rounded to 0..100; null when the scanner has none. */
    val popularity: Int?,
    val followersText: String?,
    val albums: List<AlbumCardUi>,
    /** Every track across the discography; each row carries its album for More to open. */
    val tracks: List<TrackRowUi>,
    val facts: List<AlbumFactUi>,
    val factsDescription: String,
    val heroInfoDescription: String,
)

internal fun toMusicianDetailsUi(data: MusicianDetailsData): MusicianDetailsUi {
    val musician = data.musician
    val name = musician.name.ifBlank { "Unknown artist" }
    val albumCountText = "${data.albums.size} " + if (data.albums.size == 1) "album" else "albums"
    val trackCountText = "${data.tracks.size} " + if (data.tracks.size == 1) "track" else "tracks"
    val totalDurationText = formatAlbumDuration(data.totalDuration.toLong())
    val popularity = musician.spotifyPopularity.orNull()?.roundToInt()?.coerceIn(0, 100)
    val followers = musician.spotifyFollowers.orNull()?.takeIf { it > 0 }
    val followersText = followers?.let { "${integerFormat.format(it)} Spotify followers" }
    val summary = musician.summary.orNullIfBlank()
    val genresLine = data.genres.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    val facts = buildList {
        add(AlbumFactUi("Albums", "${data.albums.size}"))
        add(AlbumFactUi("Tracks", "${data.tracks.size}"))
        add(AlbumFactUi("Total duration", totalDurationText))
        data.genres.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
            ?.let { add(AlbumFactUi("Genres", it.joinToString(", "))) }
        popularity?.let { add(AlbumFactUi("Spotify popularity", "$it / 100")) }
        followersText?.let { add(AlbumFactUi("Spotify followers", integerFormat.format(followers))) }
        summary?.let { add(AlbumFactUi("About", it)) }
    }
    return MusicianDetailsUi(
        id = musician.id,
        name = name,
        thumbUrl = musician.thumb.orNullIfBlank(),
        summary = summary,
        albumCountText = albumCountText,
        trackCountText = trackCountText,
        totalDurationText = totalDurationText,
        genresLine = genresLine,
        popularity = popularity,
        followersText = followersText,
        albums = data.albums.map {
            AlbumCardUi(
                id = it.id,
                title = it.title.ifBlank { "Untitled album" },
                musician = it.year.orNull()?.toString(),
                coverUrl = it.cover.orNullIfBlank(),
            )
        },
        tracks = data.tracks.map { it.toRowUi() },
        facts = facts,
        factsDescription = "Artist details. " + facts.joinToString(". ") { "${it.label}: ${it.value}" } + ".",
        heroInfoDescription = listOfNotNull(
            name,
            "$albumCountText, $trackCountText",
            "Total duration: ${formatSpokenTime(data.totalDuration / 1000.0)}",
            data.genres.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { "Genres: ${it.joinToString(", ")}" },
            popularity?.let { "Spotify popularity $it out of 100" },
        ).joinToString(". ") + ".",
    )
}

/** A musician's track: its album is the subtitle and More's one destination. */
private fun MusicianTrack.toRowUi(): TrackRowUi {
    val durationSec = if (duration > 0) duration / 1000.0 else 0.0
    val album = albumTitle.orNullIfBlank()
    return TrackRowUi(
        id = id,
        title = title,
        subtitle = album,
        indexText = null,
        durationText = formatTrackDuration(duration),
        durationSec = durationSec,
        albumId = albumId.orNull(),
        // The musician is the page; there is nowhere else for More to go.
        musicianId = null,
        spokenInfo = trackSpokenInfo(null, title, album, durationSec),
    )
}

/** Play all, or a row's Play with [startIndex] at that row: the discography in page order. */
internal fun toMusicPlayRequest(musician: MusicianDetailsUi, startIndex: Int = 0): MusicPlayRequest =
    MusicPlayRequest(
        source = MusicQueueSource.Musician(musicianId = musician.id, title = musician.name),
        startIndex = startIndex,
        tracks = musician.tracks.map { track ->
            MusicPlayTrack(
                id = track.id,
                title = track.title,
                durationSec = track.durationSec,
                artistName = musician.name,
                albumTitle = track.subtitle,
                coverUrl = musician.albums.firstOrNull { it.id == track.albumId }?.coverUrl,
            )
        },
    )

/** The Shuffle press: the same queue in a fresh random order (docs/music-shuffle.md). */
internal fun toShuffledMusicPlayRequest(
    musician: MusicianDetailsUi,
    random: Random = Random.Default,
): MusicPlayRequest = toMusicPlayRequest(musician).let { it.copy(tracks = it.tracks.shuffledQueue(random)) }

private val integerFormat: NumberFormat = NumberFormat.getIntegerInstance(Locale.US)
