package com.igloo.blindpenguincoder.feature.shows

import com.igloo.blindpenguincoder.core.ui.RatingBadgeSpec
import com.igloo.blindpenguincoder.core.ui.countNoun
import com.igloo.blindpenguincoder.core.ui.formatCount
import com.igloo.blindpenguincoder.core.ui.formatEpisodeCode
import com.igloo.blindpenguincoder.core.ui.formatReleaseDate
import com.igloo.blindpenguincoder.core.ui.formatRemainingTime
import com.igloo.blindpenguincoder.core.ui.formatRuntime
import com.igloo.blindpenguincoder.core.ui.formatSpokenRemainingTime
import com.igloo.blindpenguincoder.core.ui.formatSpokenTime
import com.igloo.blindpenguincoder.core.ui.joinedLine
import com.igloo.blindpenguincoder.core.ui.progressFraction
import com.igloo.blindpenguincoder.core.ui.ratingBadgeSpec
import com.igloo.blindpenguincoder.core.ui.spoken
import com.igloo.blindpenguincoder.data.model.ShowDetailsData
import com.igloo.blindpenguincoder.data.model.ShowEpisode
import com.igloo.blindpenguincoder.data.model.ShowSeasonSummary
import com.igloo.blindpenguincoder.feature.movies.resumePositionSec
import com.igloo.blindpenguincoder.feature.shared.CastCredit
import com.igloo.blindpenguincoder.feature.shared.CrewEntry
import com.igloo.blindpenguincoder.feature.shared.FactUi
import com.igloo.blindpenguincoder.feature.shared.VideoSource
import com.igloo.blindpenguincoder.feature.shared.castMembers
import com.igloo.blindpenguincoder.feature.shared.resumeProgressUi
import com.igloo.blindpenguincoder.feature.shared.youTubeExtraVideos
import com.igloo.blindpenguincoder.images.TmdbImageSize
import com.igloo.blindpenguincoder.images.tmdbImageUrl
import com.igloo.blindpenguincoder.playback.model.languageDisplayName

/*
 * The wire-to-render rules of the show details page (docs/design-system.md section 11.4.3): what
 * each element shows and what TalkBack hears for it, composed here so the screen reads strings
 * and the two can never drift apart. Visual forms are terse ("10 Seasons", "23m", "S1 E3");
 * spoken forms spell out what a screen reader would stumble over ("10 seasons", "23 minutes",
 * "season 1, episode 3").
 */

/** The page, render-ready; the selected season's episodes and play target ride the UI state. */
internal fun ShowDetailsData.toUi(apiBaseUrl: String): ShowDetailsUi {
    // Zero-guarded: the scraper writes TMDB's "no data" as a valid 0.
    val ratingBadge = show.voteAverage.orNull()?.takeIf { it > 0 }?.let(::ratingBadgeSpec)
    val certification = show.certification.orNullIfBlank()
    val status = show.status.orNullIfBlank()
    val numbered = seasons.numberedSeasons()
    val seasonCount = numbered.size.toLong()
    val episodeCount = numbered.sumOf { it.availableEpisodeCount }
    val tmdbSeasons = show.tmdbSeasonCount.orNull()?.takeIf { it > seasonCount }
    val tmdbEpisodes = show.tmdbEpisodeCount.orNull()?.takeIf { it > episodeCount }
    val firstYear = show.firstAirDate.orNullIfBlank()?.take(4)?.takeIf { it.length == 4 }
        ?: show.premiereYear.orNull()?.takeIf { it > 0 }?.toString()
    val lastYear = show.lastAirDate.orNullIfBlank()?.take(4)?.takeIf { it.length == 4 }
    val years = when {
        firstYear == null -> null
        lastYear == null || lastYear == firstYear -> firstYear
        else -> "$firstYear–$lastYear"
    }
    val extentLine = joinedLine(
        listOfNotNull(
            seasonCount.takeIf { it > 0 }?.let { formatSeasonCount(it, tmdbSeasons) },
            episodeCount.takeIf { it > 0 }?.let { countLine(it, tmdbEpisodes, "Episode") },
            years,
        ),
        " · ",
    )
    val genresLine = joinedLine(genres.map { it.tag }, " · ")
    return ShowDetailsUi(
        id = show.id,
        title = show.name,
        tagline = show.tagline.orNullIfBlank(),
        backdropUrl = tmdbImageUrl(apiBaseUrl, TmdbImageSize.W1280, show.backdropPath.orNull()),
        posterUrl = tmdbImageUrl(apiBaseUrl, TmdbImageSize.W500, show.posterPath.orNull()),
        ratingBadge = ratingBadge,
        certification = certification,
        status = status,
        extentLine = extentLine,
        metadataDescription = showMetadataDescription(
            ratingBadge = ratingBadge,
            certification = certification,
            status = status,
            spokenSeasons = seasonCount.takeIf { it > 0 }
                ?.let { formatSpokenSeasonCount(it, tmdbSeasons) },
            spokenEpisodes = episodeCount.takeIf { it > 0 }
                ?.let { countLine(it, tmdbEpisodes, "episode") },
            spokenYears = years?.let { "aired " + it.replace("–", " to ") },
        ),
        genresLine = genresLine,
        overview = show.overview.orNullIfBlank(),
        seasons = seasons.map { it.toUi() },
        keyCrew = creators.map { CrewEntry(job = "Creator", name = it.name) }.distinct(),
        // Positional ids, assigned before the billing sort: one artist can hold several roles,
        // and a rail's keys must be unique.
        cast = castMembers(
            cast.mapIndexed { index, credit ->
                CastCredit(
                    id = index.toLong(),
                    name = credit.artistName,
                    character = credit.character,
                    profilePath = credit.artistProfile.orNull(),
                    order = credit.castOrder,
                    episodeCount = credit.episodeCount,
                )
            },
            apiBaseUrl,
        ),
        extraVideos = youTubeExtraVideos(
            extraVideos.map {
                VideoSource(
                    id = it.id,
                    title = it.title,
                    type = it.type,
                    site = it.site,
                    key = it.key,
                )
            },
            apiBaseUrl,
        ),
        about = aboutFacts(),
    )
}

/**
 * The metadata row's one TalkBack sentence: "Rated 8.4 out of 10, TV-14, Ended, 10 seasons,
 * 236 episodes, aired 1994 to 2004". Each part is dropped when absent.
 */
internal fun showMetadataDescription(
    ratingBadge: RatingBadgeSpec?,
    certification: String?,
    status: String?,
    spokenSeasons: String?,
    spokenEpisodes: String?,
    spokenYears: String?,
): String = listOfNotNull(
    ratingBadge?.spoken(),
    certification,
    status,
    spokenSeasons,
    spokenEpisodes,
    spokenYears,
).joinToString(", ")

/** The seasons a show counts: specials (season 0) are not one, the way every catalogue counts. */
internal fun List<ShowSeasonSummary>.numberedSeasons(): List<ShowSeasonSummary> =
    filter { it.seasonNumber > 0 }

/** "10 Seasons", or "2 of 3 Seasons" when TMDB knows of more than the library holds. */
internal fun formatSeasonCount(count: Long, ofTotal: Long? = null): String =
    countLine(count, ofTotal, "Season")

/** [formatSeasonCount] as TalkBack hears it: "10 seasons", "2 of 3 seasons". */
internal fun formatSpokenSeasonCount(count: Long, ofTotal: Long? = null): String =
    countLine(count, ofTotal, "season")

private fun countLine(count: Long, ofTotal: Long?, singular: String): String {
    val noun = countNoun(ofTotal ?: count, singular)
    return if (ofTotal != null) {
        "${formatCount(count)} of ${formatCount(ofTotal)} $noun"
    } else {
        "${formatCount(count)} $noun"
    }
}

/** How the web client names a season: "Specials" for season 0, "Season N" for the rest. */
internal fun seasonLabel(seasonNumber: Long): String =
    if (seasonNumber == 0L) "Specials" else "Season $seasonNumber"

private fun ShowSeasonSummary.toUi(): SeasonUi {
    val label = seasonLabel(seasonNumber)
    val count = formatCount(availableEpisodeCount)
    return SeasonUi(
        number = seasonNumber,
        label = label,
        chipText = "$label · $count",
        chipDescription = "$label, $count ${countNoun(availableEpisodeCount, "episode")}",
    )
}

/** The about panel's rows in the web client's order, each only when the show has a value. */
private fun ShowDetailsData.aboutFacts(): List<FactUi> = listOfNotNull(
    show.originalName.orNullIfBlank()?.takeIf { it != show.name }
        ?.let { FactUi("Original name", it) },
    joinedLine(networks.map { it.name }, ", ")?.let {
        FactUi(if (networks.size == 1) "Network" else "Networks", it)
    },
    joinedLine(productionCompanies.map { it.name }, ", ")?.let { FactUi("Production", it) },
    show.status.orNullIfBlank()?.let { FactUi("Status", it) },
    show.type.orNullIfBlank()?.let { FactUi("Type", it) },
    languageDisplayName(show.language.orNullIfBlank())?.let { FactUi("Original language", it) },
    show.firstAirDate.orNullIfBlank()?.let(::formatReleaseDate)?.let { FactUi("First aired", it) },
    show.lastAirDate.orNullIfBlank()?.let(::formatReleaseDate)?.let { FactUi("Last aired", it) },
)

/**
 * One episode card: "1. Pilot" over "23m · September 22, 1994 · 11m left", the overview, the
 * still, a progress bar while resumable and a check once watched — and the one sentence the
 * card speaks, which carries everything the drawing does.
 */
internal fun ShowEpisode.toUi(apiBaseUrl: String): EpisodeUi {
    val runtimeMinutes = tmdbRuntime.orNull()?.takeIf { it > 0 }
    val airDate = airDate.orNullIfBlank()?.let(::formatReleaseDate)
    val resume = resumeSpan()
    val overview = overview.orNullIfBlank()
    return EpisodeUi(
        id = id,
        title = "$episodeNumber. $name",
        stillUrl = tmdbImageUrl(apiBaseUrl, TmdbImageSize.W500, stillPath.orNull()),
        metaLine = joinedLine(
            listOfNotNull(
                runtimeMinutes?.let(::formatRuntime),
                airDate,
                resume?.let { formatRemainingTime(it.progressSec, it.durationSec) },
            ),
            " · ",
        ),
        overview = overview,
        watched = watched,
        progressFraction = resume?.let { progressFraction(it.progressSec, it.durationSec) },
        description = listOfNotNull(
            "Episode $episodeNumber, $name",
            joinedLine(
                listOfNotNull(
                    runtimeMinutes?.let { formatSpokenTime(it * 60.0) },
                    airDate?.let { "aired $it" },
                ),
                ", ",
            ),
            if (watched) {
                "Watched"
            } else {
                resume?.let { formatSpokenRemainingTime(it.progressSec, it.durationSec) }
            },
            overview,
        ).joinToString(". ") { it.trimEnd('.', ' ') },
    )
}

/** The saved position and the duration it is measured against. */
private class ResumeSpan(val progressSec: Double, val durationSec: Double)

/** A saved position worth resuming from (web parity), or null; a watched episode never has one. */
private fun ShowEpisode.resumeSpan(): ResumeSpan? {
    if (watched) return null
    val progress = progressSec.orNull() ?: return null
    val duration = durationSec.orNull() ?: return null
    return ResumeSpan(progress, duration).takeIf { resumePositionSec(progress, duration) != null }
}

/**
 * The hero's action for the selected season (web parity): resume the first episode with a
 * position worth resuming, else play the first unwatched one, else the first. Null for a season
 * with no episodes. "Resume S1 E3" on screen; TalkBack hears "Resume season 1, episode 3, The One
 * with the Thumb" — "S1 E3" read aloud is letters and digits, not an episode.
 */
internal fun seasonPlayTarget(seasonNumber: Long, episodes: List<ShowEpisode>): PlayTargetUi? {
    val resumable = episodes.firstOrNull { it.resumeSpan() != null }
    val target = resumable
        ?: episodes.firstOrNull { !it.watched }
        ?: episodes.firstOrNull()
        ?: return null
    val verb = if (resumable != null) "Resume" else "Play"
    val spokenSeason = if (seasonNumber == 0L) "Specials" else "season $seasonNumber"
    return PlayTargetUi(
        label = "$verb ${formatEpisodeCode(seasonNumber, target.episodeNumber)}",
        spokenLabel = "$verb $spokenSeason, episode ${target.episodeNumber}, ${target.name}",
        progress = target.resumeSpan()?.let { resumeProgressUi(it.progressSec, it.durationSec) },
    )
}
