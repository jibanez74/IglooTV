package com.igloo.blindpenguincoder.feature.music

import com.igloo.blindpenguincoder.core.ui.countNoun
import com.igloo.blindpenguincoder.core.ui.formatTimecode
import com.igloo.blindpenguincoder.core.ui.integerCountFormat

/**
 * The formatting rules every music mapping shares. Wire durations on the music routes are
 * **milliseconds**; everything here converts before reusing the seconds-based formatters.
 */

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

/** A wire duration as the play queue's seconds; 0.0 where the wire has no usable value. */
internal fun millisToSeconds(ms: Long): Double = if (ms > 0) ms / 1000.0 else 0.0

/** `"3 albums"`, `"1 track"`: a count and its noun, as every music surface spells one. */
internal fun countLine(count: Long, singular: String): String = "$count ${countNoun(count, singular)}"

/** Grouped integer text for the pane's totals: `1234` → `"1,234"`. */
internal fun formatCount(count: Long): String = integerCountFormat.format(count)

/** A line built from values: blanks dropped, null rather than an empty line. */
internal fun joinedLine(values: List<String>, separator: String): String? = values
    .filter { it.isNotBlank() }
    .takeIf { it.isNotEmpty() }
    ?.joinToString(separator)

/** The facts panel's one cleared announcement, heading folded in (section 11.4.1 rule). */
internal fun factsDescription(heading: String, facts: List<AlbumFactUi>): String =
    "$heading. " + facts.joinToString(". ") { "${it.label}: ${it.value}" } + "."
