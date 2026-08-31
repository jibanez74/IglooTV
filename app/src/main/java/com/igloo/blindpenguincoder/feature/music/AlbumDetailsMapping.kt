package com.igloo.blindpenguincoder.feature.music

import com.igloo.blindpenguincoder.core.ui.formatReleaseDate
import com.igloo.blindpenguincoder.core.ui.formatSpokenTime
import com.igloo.blindpenguincoder.core.ui.formatTimecode
import com.igloo.blindpenguincoder.data.model.AlbumDetailsData
import com.igloo.blindpenguincoder.data.model.AlbumTrack
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Wire-to-[AlbumDetailsUi] rules for the album detail screen (docs/design-system.md section
 * 11.5.1), kept as pure functions so every formatting and grouping decision is JVM-testable.
 * Web parity throughout: the reference implementation is the web client's
 * `routes/_auth/music/album.$id.tsx`.
 *
 * All wire durations on this endpoint are **milliseconds** (unlike the tracks-list endpoint's
 * seconds); everything here divides by 1000 before reusing the seconds-based formatters.
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
    /** Display-only: there is no musician screen for these to open (section 11.5.1). */
    val artistNames: List<String>,
    val discs: List<AlbumDiscUi>,
    val hasMultipleDiscs: Boolean,
    val facts: List<AlbumFactUi>,
    /** The facts panel's one cleared announcement, heading folded in (section 11.4.1 rule). */
    val factsDescription: String,
    /** The hero reading stop's one sentence (web `pageAnnouncement` parity, plus popularity). */
    val heroInfoDescription: String,
)

data class AlbumDiscUi(
    val disc: Long,
    val tracks: List<AlbumTrackUi>,
)

data class AlbumTrackUi(
    val id: Long,
    val indexText: String,
    val title: String,
    val genresLine: String?,
    val durationText: String,
    /** The row's one spoken sentence; TV TalkBack reads a row as a single node (section 12). */
    val contentDescription: String,
)

/** One facts-panel row; absent values never become rows, so the panel renders what it holds. */
data class AlbumFactUi(
    val label: String,
    val value: String,
)

internal fun toAlbumDetailsUi(data: AlbumDetailsData): AlbumDetailsUi {
    val album = data.album
    // The contract requires a title but not a non-blank one (the home rail's rule).
    val title = album.title.ifBlank { "Untitled album" }
    val artistName = album.musician.orNullIfBlank()
    val releaseDateText = album.releaseDate.orNullIfBlank()?.let(::formatReleaseDate)
        ?: album.year.orNull()?.toString()
    val trackCountText = "${data.tracks.size} " + if (data.tracks.size == 1) "track" else "tracks"
    val totalDurationText = formatAlbumDuration(data.totalDuration.toLong())
    val popularity = album.spotifyPopularity.orNull()?.roundToInt()?.coerceIn(0, 100)
    val artistNames = data.artists.map { it.name }.filter { it.isNotBlank() }
        .ifEmpty { listOfNotNull(artistName) }
    val discs = discs(data.tracks, trackGenres = data.trackGenres.groupBy({ it.trackId }, { it.tag }))
    val audioQuality = audioQualitySummary(data.tracks)
    val facts = buildList {
        // Web parity: the facts row wants the full date and shows nothing for a bare year;
        // the hero's date-or-year fallback is the year's one home.
        album.releaseDate.orNullIfBlank()?.let(::formatReleaseDate)
            ?.let { add(AlbumFactUi("Release date", it)) }
        add(AlbumFactUi("Total tracks", "${data.tracks.size}"))
        add(AlbumFactUi("Total duration", totalDurationText))
        artistName?.let { add(AlbumFactUi("Artist", it)) }
        joinedLine(data.albumGenres, ", ")?.let { add(AlbumFactUi("Genres", it)) }
        if (discs.size > 1) add(AlbumFactUi("Discs", "${discs.size}"))
        audioQuality?.let { add(AlbumFactUi("Audio quality", it)) }
        popularity?.let { add(AlbumFactUi("Spotify popularity", "$it / 100")) }
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
        artistNames = artistNames,
        discs = discs,
        hasMultipleDiscs = discs.size > 1,
        facts = facts,
        factsDescription = "Album details. " +
            facts.joinToString(". ") { "${it.label}: ${it.value}" } + ".",
        heroInfoDescription = heroInfoDescription(
            title = title,
            artistName = artistName,
            trackCountText = trackCountText,
            totalDurationSpoken = formatSpokenTime(data.totalDuration / 1000.0),
            genres = data.albumGenres,
            popularity = popularity,
        ),
    )
}

/**
 * Tracks grouped and ordered by disc, then track index. A disc of 0 or below is disc 1 — the
 * web's `track.disc || 1` for an untagged rip.
 */
private fun discs(
    tracks: List<AlbumTrack>,
    trackGenres: Map<Long, List<String>>,
): List<AlbumDiscUi> {
    val hasMultipleDiscs = tracks.map { discNumber(it) }.distinct().size > 1
    return tracks
        .groupBy { discNumber(it) }
        .toSortedMap()
        .map { (disc, discTracks) ->
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

private fun toTrackUi(track: AlbumTrack, genres: List<String>, discSpoken: Long?): AlbumTrackUi {
    val genresLine = joinedLine(genres, ", ")
    val durationText = formatTrackDuration(track.duration)
    return AlbumTrackUi(
        id = track.id,
        indexText = "${track.trackIndex}",
        title = track.title,
        genresLine = genresLine,
        durationText = durationText,
        contentDescription = listOfNotNull(
            discSpoken?.let { "Disc $it" },
            "Track ${track.trackIndex}",
            track.title,
            genresLine,
            durationText.takeIf { it.isNotEmpty() }
                ?.let { formatSpokenTime(track.duration / 1000.0) },
        ).joinToString(". ") + ".",
    )
}

/** Web `formatDuration` parity: `"1h 2m"` past an hour, `"42m 10s"` under one. */
internal fun formatAlbumDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m ${seconds}s"
}

/** Web `formatTrackDuration` parity: `"3:34"`, empty for a missing or invalid duration. */
internal fun formatTrackDuration(ms: Long): String =
    if (ms > 0) formatTimecode(ms / 1000.0) else ""

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
    trackCountText: String,
    totalDurationSpoken: String,
    genres: List<String>,
    popularity: Int?,
): String = listOfNotNull(
    title + (artistName?.let { " by $it" } ?: ""),
    trackCountText,
    "Total duration: $totalDurationSpoken",
    joinedLine(genres, ", ")?.let { "Genres: $it" },
    popularity?.let { "Spotify popularity $it out of 100" },
).joinToString(". ") + "."

/** A line built from values: blanks dropped, null rather than an empty line. */
private fun joinedLine(values: List<String>, separator: String): String? = values
    .filter { it.isNotBlank() }
    .takeIf { it.isNotEmpty() }
    ?.joinToString(separator)
