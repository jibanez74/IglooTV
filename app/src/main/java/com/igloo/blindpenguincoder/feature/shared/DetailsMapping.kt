package com.igloo.blindpenguincoder.feature.shared

import com.igloo.blindpenguincoder.core.ui.countNoun
import com.igloo.blindpenguincoder.core.ui.formatCount
import com.igloo.blindpenguincoder.core.ui.formatRemainingTime
import com.igloo.blindpenguincoder.core.ui.formatSpokenRemainingTime
import com.igloo.blindpenguincoder.core.ui.formatSpokenTimeThroughSeconds
import com.igloo.blindpenguincoder.core.ui.progressFraction
import com.igloo.blindpenguincoder.images.TmdbImageSize
import com.igloo.blindpenguincoder.images.tmdbImageUrl
import com.igloo.blindpenguincoder.images.youtubeThumbnailUrl
import java.util.Locale

/*
 * The render models and wire-to-render rules every video details page shares — the library and
 * in-theaters movie pages and the show page all draw a Key Crew band, a cast rail, an extras rail
 * and a hero reading stop, and each rule about what those show lives here once. Each source
 * bridges its own payload through the small intermediates below rather than a common wire model:
 * the payloads have nothing else in common.
 */

/** One credit line in the Key Crew band: `"Director"` over `"Michael Mann"`. */
data class CrewEntry(val job: String, val name: String)

/**
 * A cast member ready for the rail card; [photoUrl] is the w185 profile through the proxy and
 * [episodeCountText] the show page's "228 episodes" line, absent for a movie.
 */
data class CastMemberUi(
    val id: Long,
    val name: String,
    val character: String?,
    val photoUrl: String?,
    val episodeCountText: String? = null,
)

/**
 * An extra video ready for the rail card; [thumbnailUrl] is the authenticated YouTube proxy and
 * [key] is the YouTube video id the trailer player plays.
 */
data class ExtraVideoUi(
    val id: Long,
    val title: String,
    val typeLabel: String,
    val thumbnailUrl: String?,
    val key: String,
)

/**
 * The thin strip under a hero's Play — a movie's, or a show's Resume — present only while a
 * resume position is worth showing. [resumeStateDescription] is what the button carries as
 * state, because the caption under it is plain text a TV screen reader never reaches.
 */
data class ProgressUi(
    val fraction: Float,
    val remainingTimeLabel: String,
    val resumeStateDescription: String,
)

/** The strip for a saved position: "45m left" on screen, the exact point and remainder spoken. */
internal fun resumeProgressUi(progressSec: Double, durationSec: Double): ProgressUi = ProgressUi(
    fraction = progressFraction(progressSec, durationSec),
    remainingTimeLabel = formatRemainingTime(progressSec, durationSec),
    resumeStateDescription = "Resume from ${formatSpokenTimeThroughSeconds(progressSec)}; " +
        formatSpokenRemainingTime(progressSec, durationSec),
)

/** A cast credit as [castMembers] reads it, from any source's cast list. */
internal data class CastCredit(
    val id: Long,
    val name: String,
    val character: String,
    val profilePath: String?,
    /** The billing order; the rail shows the top of it. */
    val order: Long,
    /** How many of a show's episodes the credit spans; a movie has no such count. */
    val episodeCount: Long? = null,
)

/** A video as [youTubeExtraVideos] reads it, from any source's video list. */
internal data class VideoSource(
    val id: Long,
    val title: String,
    val type: String,
    val site: String,
    val key: String,
)

/** The top [CAST_LIMIT] of the billing, a blank character left out. */
internal fun castMembers(cast: List<CastCredit>, apiBaseUrl: String): List<CastMemberUi> = cast
    .sortedBy { it.order }
    .take(CAST_LIMIT)
    .map {
        CastMemberUi(
            id = it.id,
            name = it.name,
            character = it.character.takeIf { character -> character.isNotBlank() },
            photoUrl = tmdbImageUrl(apiBaseUrl, TmdbImageSize.W185, it.profilePath),
            episodeCountText = it.episodeCount?.takeIf { count -> count > 0 }
                ?.let { count -> "${formatCount(count)} ${countNoun(count, "episode")}" },
        )
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

/** The sources spell their values differently around case and separators; this levels them. */
internal fun normalizedVideoValue(value: String): String =
    value.trim().lowercase(Locale.US).replace('-', '_')

/** Trailers, then special features, then the rest; unknown types sort last (web parity). */
private fun extraVideoSortRank(type: String): Int = when (normalizedVideoValue(type)) {
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
private fun extraVideoTypeLabel(type: String): String = when (normalizedVideoValue(type)) {
    "trailer" -> "Trailer"
    "special_feature" -> "Special feature"
    "other" -> "Other"
    else -> type.trim()
        .split('_')
        .filter { it.isNotBlank() }
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase(Locale.US) } }
}

/**
 * Everything a details hero's text column says, as the one sentence its screen-reader reading
 * stop announces (TV TalkBack never traverses plain text). The genre separators become commas —
 * "·" is a pause the eye takes and a symbol a screen reader stumbles over — and each part sheds
 * its own trailing period so a tagline that ends in one doesn't double up in the join.
 */
internal fun heroInfoDescription(
    title: String,
    tagline: String?,
    metadataDescription: String,
    genresLine: String?,
): String = listOfNotNull(
    title,
    tagline,
    metadataDescription.takeIf { it.isNotBlank() },
    genresLine?.replace(" · ", ", "),
).joinToString(". ") { it.trimEnd('.', ' ') }

/** Ten cast members: the same cap the web client's cast section uses. */
private const val CAST_LIMIT = 10
