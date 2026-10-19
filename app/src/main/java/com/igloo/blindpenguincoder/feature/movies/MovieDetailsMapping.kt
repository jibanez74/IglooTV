package com.igloo.blindpenguincoder.feature.movies

import com.igloo.blindpenguincoder.core.ui.RatingBadgeSpec
import com.igloo.blindpenguincoder.core.ui.formatSpokenTime
import com.igloo.blindpenguincoder.core.ui.spoken
import com.igloo.blindpenguincoder.feature.shared.CrewEntry
import java.text.NumberFormat
import java.util.Locale

/**
 * The wire-to-[MovieDetailsUi] rules both movie detail view models share: the library movie
 * ([MovieDetailsViewModel]) and the TMDB in-theaters movie ([TheaterMovieDetailsViewModel]) draw
 * from different endpoints but render the same screen, so every rule about what that screen shows
 * — how crew is chosen, what the metadata row speaks — lives here once instead of being
 * reimplemented per source. The cast and extras rules every details page shares live in
 * `feature/shared/DetailsMapping.kt`.
 *
 * The two sources are bridged by small intermediates rather than by a common wire model: their
 * payloads have nothing else in common, and a shared model would exist only to be mapped into
 * twice.
 */

/** A crew credit as [keyCrew] reads it, from either source's crew list. */
internal data class CrewCredit(
    val job: String,
    val department: String,
    val name: String,
)

/** Director(s) first, then up to three writing credits under their actual jobs (web parity). */
internal fun keyCrew(crew: List<CrewCredit>): List<CrewEntry> {
    val directors = crew
        .filter { it.job == "Director" }
        .map { CrewEntry(job = it.job, name = it.name) }
    val writers = crew
        .filter { it.department == "Writing" }
        .map { CrewEntry(job = it.job, name = it.name) }
        .distinct()
        .take(WRITER_LIMIT)
    return (directors + writers).distinct()
}

/**
 * The metadata row's single TalkBack sentence (section 11.4.1): the row renders as chips, but
 * eight consecutive two-character announcements would be noise, so what it speaks is composed
 * here, once for both sources. [mediaBadges] is empty for a movie the library does not hold —
 * there are no probed streams to derive badges from.
 */
internal fun metadataDescription(
    ratingBadge: RatingBadgeSpec?,
    certification: String?,
    mediaBadges: List<String>,
    runtimeMinutes: Long?,
    releaseDateText: String?,
): String = listOfNotNull(
    ratingBadge?.spoken(),
    certification,
    *mediaBadges.map { spokenBadge(it) }.toTypedArray(),
    runtimeMinutes?.let { formatSpokenTime(it * 60.0) },
    releaseDateText?.let { "released $it" },
).joinToString(", ")

/** The visual chip is terse; TalkBack gets the words the abbreviation stands for. */
private fun spokenBadge(badge: String): String = when (badge) {
    "CC" -> "subtitles available"
    "5.1", "7.1" -> "$badge surround sound"
    "Surround" -> "surround sound"
    else -> badge
}

internal fun formatUsd(amount: Double): String =
    NumberFormat.getCurrencyInstance(Locale.US)
        .apply { maximumFractionDigits = 0 }
        .format(amount)

/** Three writing credits: the same cap the web client's Key Crew band uses. */
private const val WRITER_LIMIT = 3
