package com.igloo.blindpenguincoder.feature.movies

import com.igloo.blindpenguincoder.core.ui.RatingBadgeSpec
import com.igloo.blindpenguincoder.core.ui.formatSpokenTime
import com.igloo.blindpenguincoder.images.youtubeThumbnailUrl
import java.text.NumberFormat
import java.util.Locale

/**
 * The wire-to-[MovieDetailsUi] rules both detail view models share: the library movie
 * ([MovieDetailsViewModel]) and the TMDB in-theaters movie ([TheaterMovieDetailsViewModel]) draw
 * from different endpoints but render the same screen, so every rule about what that screen shows
 * — how crew is chosen, how extras are sorted and labelled, what the metadata row speaks — lives
 * here once instead of being reimplemented per source.
 *
 * The two sources are bridged by the small intermediates below rather than by a common wire
 * model: their payloads have nothing else in common, and a shared model would exist only to be
 * mapped into twice.
 */

/** A crew credit as [keyCrew] reads it, from either source's crew list. */
internal data class CrewCredit(
    val job: String,
    val department: String,
    val name: String,
)

/** A video as [youTubeExtraVideos] reads it, from either source's video list. */
internal data class VideoSource(
    val id: Long,
    val title: String,
    val type: String,
    val site: String,
    val key: String,
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
 * YouTube extras only — the backend has no thumbnail proxy for other sites (web parity) —
 * re-sorted trailers first, then special features, then the rest, with a case-insensitive title
 * tie-break. The library API's `ORDER BY type, title` is alphabetical, which puts trailers last;
 * the rail wants them leading, and TMDB's order carries no meaning of its own either.
 */
internal fun youTubeExtraVideos(
    videos: List<VideoSource>,
    apiBaseUrl: String,
): List<ExtraVideoUi> = videos
    .filter { normalizedVideoValue(it.site) == "youtube" }
    .sortedWith(
        compareBy<VideoSource> { extraVideoSortRank(it.type) }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
    )
    .map {
        ExtraVideoUi(
            id = it.id,
            title = it.title,
            typeLabel = extraVideoTypeLabel(it.type),
            thumbnailUrl = youtubeThumbnailUrl(apiBaseUrl, it.key),
            key = it.key,
        )
    }

/** Both sources spell their values differently around case and separators; this levels them. */
internal fun normalizedVideoValue(value: String): String =
    value.trim().lowercase(Locale.US).replace('-', '_')

/** Trailers, then special features, then the rest; unknown types sort last (web parity). */
internal fun extraVideoSortRank(type: String): Int = when (normalizedVideoValue(type)) {
    "trailer" -> 0
    "special_feature" -> 1
    "other" -> 2
    else -> 3
}

/**
 * The library DB constrains `type` to the three known values. Anything else is TMDB's own
 * free-form type ("Featurette", "Behind the Scenes"), which arrives already titled, so only the
 * snake_case values need splitting and nothing is lower-cased on the way through.
 */
internal fun extraVideoTypeLabel(type: String): String = when (normalizedVideoValue(type)) {
    "trailer" -> "Trailer"
    "special_feature" -> "Special feature"
    "other" -> "Other"
    else -> type.trim()
        .split('_')
        .filter { it.isNotBlank() }
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase(Locale.US) } }
}

/**
 * The metadata row's single TalkBack sentence (section 11.4.1): the row renders as chips, but
 * eight consecutive two-character announcements would be noise, so the view model composes what
 * it speaks. [mediaBadges] is empty for a movie the library does not hold — there are no probed
 * streams to derive badges from.
 */
internal fun metadataDescription(
    ratingBadge: RatingBadgeSpec?,
    certification: String?,
    mediaBadges: List<String>,
    runtimeMinutes: Long?,
    releaseDateText: String?,
): String = listOfNotNull(
    ratingBadge?.let { "Rated ${it.label} out of 10" },
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

/** Ten cast members, three writing credits: the same caps the web client's sections use. */
internal const val CAST_LIMIT = 10
private const val WRITER_LIMIT = 3
