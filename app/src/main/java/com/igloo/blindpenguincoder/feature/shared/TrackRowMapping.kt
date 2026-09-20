package com.igloo.blindpenguincoder.feature.shared

import com.igloo.blindpenguincoder.core.ui.formatSpokenTime

/**
 * The one render model every track row draws, whichever list it sits in: the Tracks tab, an
 * album's disc list, a musician's discography. [spokenInfo] is the row's whole sentence — the
 * Play control speaks it, the other two controls name only themselves — composed at mapping
 * time so a list header the eye reads can be folded into the first row under it.
 */
data class TrackRowUi(
    val id: Long,
    val title: String,
    /** The artist and album line on a library row; the genre line on an album row. */
    val subtitle: String?,
    /** The track number on an album row; null elsewhere. */
    val indexText: String?,
    /** `"3:34"`, or empty for a missing duration. */
    val durationText: String,
    /** Seconds for the play queue; 0.0 where the wire has no usable duration. */
    val durationSec: Double,
    /** Where More can go; null when the row already sits on that page or the wire has none. */
    val albumId: Long?,
    val musicianId: Long?,
    val spokenInfo: String,
)

/**
 * The letter a title files under, exactly as the server orders the track list: the first
 * character uppercased when it is A–Z, `#` for everything else. No trimming — the server does
 * none either, and a header that disagreed with the sort would appear out of order.
 */
fun letterBucket(title: String): String {
    val first = title.firstOrNull()?.uppercaseChar() ?: return "#"
    return if (first in 'A'..'Z') first.toString() else "#"
}

/** What the first row under a letter folds into its sentence, without the trailing period. */
fun spokenLetterHeader(letter: String): String =
    if (letter == "#") "Tracks starting with a number or symbol" else "Tracks starting with $letter"

/** `"The Beatles · Help!"`, each part dropped when absent, null when both are. */
fun trackSubtitle(artistName: String?, albumTitle: String?): String? =
    listOfNotNull(artistName?.takeIf { it.isNotBlank() }, albumTitle?.takeIf { it.isNotBlank() })
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" · ")

/**
 * The Play control's sentence: an optional folded header, the title, the subtitle, and the
 * duration spoken in words, each ending in a period so TalkBack pauses between them.
 */
fun trackSpokenInfo(
    prefix: String?,
    title: String,
    subtitle: String?,
    durationSec: Double,
): String = listOfNotNull(
    prefix,
    title,
    subtitle,
    durationSec.takeIf { it > 0 }?.let(::formatSpokenTime),
).joinToString(". ") + "."
