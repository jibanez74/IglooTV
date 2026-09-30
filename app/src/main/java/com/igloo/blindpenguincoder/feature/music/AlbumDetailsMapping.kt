package com.igloo.blindpenguincoder.feature.music

import com.igloo.blindpenguincoder.core.ui.formatReleaseDate
import com.igloo.blindpenguincoder.core.ui.formatSpokenTime
import com.igloo.blindpenguincoder.core.ui.joinedLine
import com.igloo.blindpenguincoder.data.model.AlbumDetailsData
import com.igloo.blindpenguincoder.data.model.AlbumTrack
import com.igloo.blindpenguincoder.feature.shared.FactUi
import com.igloo.blindpenguincoder.feature.shared.TrackRowUi
import com.igloo.blindpenguincoder.playback.model.MusicPlayRequest
import com.igloo.blindpenguincoder.playback.model.MusicPlayTrack
import com.igloo.blindpenguincoder.playback.model.MusicQueueSource
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Wire-to-[AlbumDetailsUi] rules for the album detail screen (docs/design-system.md section
 * 11.5.1), kept as pure functions so every formatting and grouping decision is JVM-testable.
 * Web parity throughout: the reference implementation is the web client's
 * `routes/_auth/music/album.$id.tsx`.
 *
 * All wire durations on this endpoint are **milliseconds**; everything here divides by 1000
 * before reusing the seconds-based formatters.
 */

/** The one render model the album detail screen draws. */
data class AlbumDetailsUi(
    val id: Long,
    val title: String,
    val artistName: String?,
    /** Verbatim: the scanner stores an absolute Spotify URL or nothing; there is no proxy. */
    val coverUrl: String?,
    val releaseDateText: String?,
    val trackCountText: String,
    val totalDurationText: String,
    val genresLine: String?,
    /** Spotify popularity, rounded to 0..100; null when the scanner has none. */
    val popularity: Int?,
    val artists: List<AlbumArtistUi>,
    val discs: List<AlbumDiscUi>,
    val hasMultipleDiscs: Boolean,
    val facts: List<FactUi>,
    /** The facts panel's one cleared announcement, heading folded in (section 11.4.1 rule). */
    val factsDescription: String,
    /** The hero reading stop's one sentence (web `pageAnnouncement` parity, plus popularity). */
    val heroInfoDescription: String,
)

/** A credited artist; [id] is what the chip opens, null when the name has no musician row. */
data class AlbumArtistUi(
    val id: Long?,
    val name: String,
)

data class AlbumDiscUi(
    val disc: Long,
    val tracks: List<TrackRowUi>,
)

internal fun toAlbumDetailsUi(data: AlbumDetailsData): AlbumDetailsUi {
    val album = data.album
    val title = album.title.ifBlank { UNTITLED_ALBUM }
    val artistName = album.musician.orNullIfBlank()
    val releaseDateText = album.releaseDate.orNullIfBlank()?.let(::formatReleaseDate)
        ?: album.year.orNull()?.toString()
    val trackCountText = countLine(data.tracks.size.toLong(), "track")
    val totalDurationText = formatAlbumDuration(data.totalDuration.toLong())
    val popularity = spotifyPopularity(album.spotifyPopularity)
    val artists = data.artists.filter { it.name.isNotBlank() }.map { AlbumArtistUi(it.id, it.name) }
    // With no credited rows the album's own musician is the one name, and it has no id to open.
    val artistNames = artists.map { it.name }.ifEmpty { listOfNotNull(artistName) }
    val artistNamesLine = joinedLine(artistNames, ", ")
    val discs = discs(data.tracks, trackGenres = data.trackGenres.groupBy({ it.trackId }, { it.tag }))
    val hasMultipleDiscs = discs.size > 1
    val audioQuality = audioQualitySummary(data.tracks)
    val facts = buildList {
        // Web parity: the facts row wants the full date and shows nothing for a bare year;
        // the hero's date-or-year fallback is the year's one home.
        album.releaseDate.orNullIfBlank()?.let(::formatReleaseDate)
            ?.let { add(FactUi("Release date", it)) }
        add(FactUi("Total tracks", "${data.tracks.size}"))
        add(FactUi("Total duration", totalDurationText))
        artistNamesLine?.let { add(FactUi("Artist", it)) }
        joinedLine(data.albumGenres, ", ")?.let { add(FactUi("Genres", it)) }
        if (hasMultipleDiscs) add(FactUi("Discs", "${discs.size}"))
        audioQuality?.let { add(FactUi("Audio quality", it)) }
        popularity?.let { add(FactUi("Spotify popularity", "$it / 100")) }
    }
    return AlbumDetailsUi(
        id = album.id,
        title = title,
        artistName = artistName,
        coverUrl = album.cover.orNullIfBlank(),
        releaseDateText = releaseDateText,
        trackCountText = trackCountText,
        totalDurationText = totalDurationText,
        genresLine = joinedLine(data.albumGenres, " · "),
        popularity = popularity,
        artists = artists.ifEmpty { listOfNotNull(artistName?.let { AlbumArtistUi(id = null, name = it) }) },
        discs = discs,
        hasMultipleDiscs = hasMultipleDiscs,
        facts = facts,
        factsDescription = factsDescription("Album details", facts),
        heroInfoDescription = heroInfoDescription(
            title = title,
            artistName = artistName,
            artistNames = artistNames,
            trackCountText = trackCountText,
            totalDurationSpoken = formatSpokenTime(data.totalDuration / 1000.0),
            genres = data.albumGenres,
            popularity = popularity,
        ),
    )
}

/**
 * A Play press mapped onto the player's queue: every disc's tracks flattened in the order the
 * page lists them (disc, then track index), starting at [startIndex] — 0 for Play Album, the
 * row's flat position for a row's own Play. The album's artist and cover ride on every entry.
 */
internal fun toMusicPlayRequest(album: AlbumDetailsUi, startIndex: Int = 0): MusicPlayRequest =
    MusicPlayRequest(
        source = MusicQueueSource.Album(albumId = album.id, title = album.title),
        startIndex = startIndex,
        tracks = album.discs.flatMap { disc ->
            disc.tracks.map { track ->
                MusicPlayTrack(
                    id = track.id,
                    title = track.title,
                    durationSec = track.durationSec,
                    artistName = album.artistName,
                    albumTitle = album.title,
                    coverUrl = album.coverUrl,
                )
            }
        },
    )

/**
 * Tracks grouped and ordered by disc, then track index. A disc of 0 or below is disc 1 — the
 * web's `track.disc || 1` for an untagged rip.
 */
private fun discs(
    tracks: List<AlbumTrack>,
    trackGenres: Map<Long, List<String>>,
): List<AlbumDiscUi> {
    val byDisc = tracks.groupBy { discNumber(it) }.toSortedMap()
    val hasMultipleDiscs = byDisc.size > 1
    return byDisc.map { (disc, discTracks) ->
        AlbumDiscUi(
            disc = disc,
            tracks = discTracks
                .sortedWith(compareBy({ it.trackIndex }, { it.id }))
                .mapIndexed { indexInDisc, track ->
                    toTrackUi(
                        track = track,
                        genres = trackGenres[track.id].orEmpty(),
                        // The header is plain text a TV screen reader never reaches, so the
                        // disc is folded into its first row's sentence (section 11.5.1).
                        discSpoken = disc.takeIf { hasMultipleDiscs && indexInDisc == 0 },
                    )
                },
        )
    }
}

private fun discNumber(track: AlbumTrack): Long = if (track.disc > 0) track.disc else 1

private fun toTrackUi(track: AlbumTrack, genres: List<String>, discSpoken: Long?): TrackRowUi =
    musicTrackRow(
        id = track.id,
        title = track.title,
        subtitle = joinedLine(genres, ", "),
        durationMs = track.duration,
        // The row already sits on its album; More can only go to the artist.
        albumId = null,
        musicianId = track.musicianId.orNull(),
        indexText = "${track.trackIndex}",
        spokenPrefix = listOfNotNull(discSpoken?.let { "Disc $it" }, "Track ${track.trackIndex}")
            .joinToString(". "),
    )

/**
 * The audio-quality summary, ported from the web page: the dominant codec by track count
 * (first past the post, in track order), the peak bitrate, and the channel layout only when
 * it is uniform across the album — `"FLAC · 900 kbps · stereo"`, each part dropped when
 * absent, null with no codec at all.
 */
internal fun audioQualitySummary(tracks: List<AlbumTrack>): String? {
    val codecCounts = LinkedHashMap<String, Int>()
    tracks.forEach { track ->
        if (track.codec.isNotBlank()) codecCounts.merge(track.codec, 1, Int::plus)
    }
    val dominantCodec = codecCounts.entries.fold(null as Map.Entry<String, Int>?) { best, entry ->
        if (best == null || entry.value > best.value) entry else best
    }?.key ?: return null
    val maxBitRate = tracks.maxOfOrNull { it.bitRate } ?: 0
    val uniformLayout = tracks.firstOrNull()?.channelLayout
        ?.takeIf { it.isNotBlank() }
        ?.takeIf { layout -> tracks.all { it.channelLayout == layout } }
    return listOfNotNull(
        dominantCodec.uppercase(Locale.US),
        maxBitRate.takeIf { it > 0 }?.let { "${(it / 1000.0).roundToInt()} kbps" },
        uniformLayout,
    ).joinToString(" · ")
}

private fun heroInfoDescription(
    title: String,
    artistName: String?,
    artistNames: List<String>,
    trackCountText: String,
    totalDurationSpoken: String,
    genres: List<String>,
    popularity: Int?,
): String {
    val creditedArtists = joinedLine(artistNames, ", ")
        ?.takeUnless {
            artistNames.size == 1 && artistName != null &&
                artistNames.single().equals(artistName, ignoreCase = true)
        }
        ?.let { "Artists: $it" }
    return listOfNotNull(
        title + (artistName?.let { " by $it" } ?: ""),
        creditedArtists,
        trackCountText,
        "Total duration: $totalDurationSpoken",
        joinedLine(genres, ", ")?.let { "Genres: $it" },
        popularity?.let { "Spotify popularity $it out of 100" },
    ).joinToString(". ") + "."
}
