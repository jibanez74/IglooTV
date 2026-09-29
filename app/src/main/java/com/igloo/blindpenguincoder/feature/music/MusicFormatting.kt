package com.igloo.blindpenguincoder.feature.music

import com.igloo.blindpenguincoder.core.ui.countNoun
import com.igloo.blindpenguincoder.core.ui.formatTimecode
import com.igloo.blindpenguincoder.core.ui.hms
import com.igloo.blindpenguincoder.data.model.SqlNullFloat64
import com.igloo.blindpenguincoder.feature.shared.FactUi
import com.igloo.blindpenguincoder.feature.shared.TrackRowUi
import com.igloo.blindpenguincoder.feature.shared.trackSpokenInfo
import com.igloo.blindpenguincoder.playback.model.millisToSeconds
import kotlin.math.roundToInt

/**
 * The formatting rules every music mapping shares. Wire durations on the music routes are
 * **milliseconds**; everything here converts before reusing the seconds-based formatters.
 */

/** Web `formatDuration` parity: `"1h 2m"` past an hour, `"42m 10s"` under one. */
internal fun formatAlbumDuration(ms: Long): String {
    val (hours, minutes, seconds) = hms(ms / 1000.0)
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m ${seconds}s"
}

/** Web `formatTrackDuration` parity: `"3:34"`, empty for a missing or invalid duration. */
internal fun formatTrackDuration(ms: Long): String =
    if (ms > 0) formatTimecode(ms / 1000.0) else ""

/**
 * What a nameless row shows: the contract requires a title or name but not a non-blank one, and
 * an untagged rip must not render an empty line or a nameless announcement.
 */
internal const val UNTITLED_ALBUM = "Untitled album"
internal const val UNKNOWN_ARTIST = "Unknown artist"

/** Spotify's popularity score rounded into 0..100; null when the scanner has none. */
internal fun spotifyPopularity(score: SqlNullFloat64): Int? =
    score.orNull()?.roundToInt()?.coerceIn(0, 100)

/**
 * A track row from any music route: [durationMs] is the wire's milliseconds, and the Play
 * control's sentence reads the same title, subtitle and duration the row shows, after
 * [spokenPrefix] when there is one.
 */
internal fun musicTrackRow(
    id: Long,
    title: String,
    subtitle: String?,
    durationMs: Long,
    albumId: Long?,
    musicianId: Long?,
    indexText: String? = null,
    spokenPrefix: String? = null,
): TrackRowUi {
    val durationSec = millisToSeconds(durationMs)
    return TrackRowUi(
        id = id,
        title = title,
        subtitle = subtitle,
        indexText = indexText,
        durationText = formatTrackDuration(durationMs),
        durationSec = durationSec,
        albumId = albumId,
        musicianId = musicianId,
        spokenInfo = trackSpokenInfo(spokenPrefix, title, subtitle, durationSec),
    )
}

/** `"3 albums"`, `"1 track"`: a count and its noun, as every music surface spells one. */
internal fun countLine(count: Long, singular: String): String = "$count ${countNoun(count, singular)}"

/** The facts panel's one cleared announcement, heading folded in (section 11.4.1 rule). */
internal fun factsDescription(heading: String, facts: List<FactUi>): String =
    "$heading. " + facts.joinToString(". ") { "${it.label}: ${it.value}" } + "."
