package com.igloo.blindpenguincoder.feature.movies

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.feature.shared.AboutSection
import com.igloo.blindpenguincoder.feature.shared.CastSection
import com.igloo.blindpenguincoder.feature.shared.ExtraVideoUi
import com.igloo.blindpenguincoder.feature.shared.ExtraVideosSection
import com.igloo.blindpenguincoder.feature.shared.FactUi
import com.igloo.blindpenguincoder.feature.shared.KeyCrewSection
import com.igloo.blindpenguincoder.feature.shared.OverviewSection

/**
 * Everything below the movie hero, in the movie page's order: overview, key crew, the cast and
 * extra-videos rails, and the fine-print about block — the shared details sections
 * (`feature/shared/DetailsSections.kt`) wired into one vertical chain. Sections with nothing to
 * show are skipped entirely rather than rendering empty shells.
 */
@Composable
internal fun MovieDetailsSections(
    movie: MovieDetailsUi,
    spokenAccessibilityEnabled: Boolean,
    overviewRequester: FocusRequester,
    keyCrewRequester: FocusRequester,
    castEntryRequester: FocusRequester,
    extrasEntryRequester: FocusRequester,
    extrasReturnRequester: FocusRequester,
    aboutRequester: FocusRequester,
    // Null on an in-theaters page whose hero has no trailer to play: there is no action row
    // above the first section to go back up to (section 11.4.2).
    upFromSections: FocusRequester?,
    onPlayExtra: (ExtraVideoUi) -> Unit,
    /**
     * The overscan inset. Held here rather than applied by the caller's container so the rails
     * can bleed past it while the prose sections stay inside it (section 8.3) — a container that
     * insets everything cannot let a card scroll off the panel's edge.
     */
    contentInset: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val hasCast = movie.cast.isNotEmpty()
    val hasExtras = movie.extraVideos.isNotEmpty()
    val hasAbout = !movie.about.isEmpty

    // The vertical chain, one entry per section actually on the page. With the reading stops out
    // (no screen reader) this reduces to exactly the old wiring: cast → extras → about.
    val chain = listOfNotNull(
        overviewRequester.takeIf { spokenAccessibilityEnabled },
        keyCrewRequester.takeIf { spokenAccessibilityEnabled && movie.keyCrew.isNotEmpty() },
        castEntryRequester.takeIf { hasCast },
        extrasEntryRequester.takeIf { hasExtras },
        aboutRequester.takeIf { hasAbout },
    )
    fun above(requester: FocusRequester): FocusRequester? =
        chain.getOrNull(chain.indexOf(requester) - 1) ?: upFromSections
    fun below(requester: FocusRequester): FocusRequester? =
        chain.getOrNull(chain.indexOf(requester) + 1)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.lg),
    ) {
        OverviewSection(
            overview = movie.overview,
            readingStop = spokenAccessibilityEnabled,
            requester = overviewRequester,
            upRequester = above(overviewRequester),
            downRequester = below(overviewRequester),
            modifier = Modifier.padding(contentInset),
        )
        if (movie.keyCrew.isNotEmpty()) {
            KeyCrewSection(
                crew = movie.keyCrew,
                readingStop = spokenAccessibilityEnabled,
                requester = keyCrewRequester,
                upRequester = above(keyCrewRequester),
                downRequester = below(keyCrewRequester),
                modifier = Modifier.padding(contentInset),
            )
        }
        if (hasCast) {
            CastSection(
                cast = movie.cast,
                contentInset = contentInset,
                entryRequester = castEntryRequester,
                upRequester = above(castEntryRequester),
                downRequester = below(castEntryRequester),
            )
        }
        if (hasExtras) {
            ExtraVideosSection(
                videos = movie.extraVideos,
                contentInset = contentInset,
                entryRequester = extrasEntryRequester,
                // The cast rail's entry requester rides its last-focused card, so up from the
                // extras lands where the user left the cast, not on its first card.
                upRequester = above(extrasEntryRequester),
                downRequester = below(extrasEntryRequester),
                returnRequester = extrasReturnRequester,
                onPlayExtra = onPlayExtra,
            )
        }
        if (hasAbout) {
            AboutSection(
                title = movie.title,
                facts = movie.about.facts(),
                modifier = Modifier.padding(contentInset),
                requester = aboutRequester,
                upRequester = above(aboutRequester),
            )
        }
    }
}

/** The movie's fine print, as facts rows; each row is dropped when the movie has no value. */
private fun AboutUi.facts(): List<FactUi> = listOfNotNull(
    production?.let { FactUi("Production", it) },
    status?.let { FactUi("Status", it) },
    language?.let { FactUi("Original language", it) },
    budget?.let { FactUi("Budget", it) },
    revenue?.let { FactUi("Revenue", it) },
)
