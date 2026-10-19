package com.igloo.blindpenguincoder.feature.shows

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusRequester.Companion.Cancel
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.AnnouncedSection
import com.igloo.blindpenguincoder.core.ui.IglooIcons
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.core.ui.SectionHeading
import com.igloo.blindpenguincoder.core.ui.announcedSection
import com.igloo.blindpenguincoder.core.ui.insetHorizontally
import com.igloo.blindpenguincoder.feature.shared.ChipSpec
import com.igloo.blindpenguincoder.feature.shared.DetailsRailSection
import com.igloo.blindpenguincoder.feature.shared.SelectableChipRow

/**
 * The show page's seasons (docs/design-system.md section 11.4.3), two sections of the page's
 * vertical chain: a "Seasons" heading over a scrolling row of season chips — composed only for a
 * show with two or more seasons, since one chip would be a stop with nothing to choose — and
 * one rail of the selected season's episodes, headed with the season's name.
 *
 * Chips select on press, the Genres picker's contract: moving across them changes nothing, and
 * the press is where the page hands focus down to the season's first episode (the caller owns
 * that hand-off, because it travels through the page's section move). The selected chip carries
 * [chipsRequester], so up from the rail lands back on it.
 *
 * The rail is keyed by season, so a switch builds it fresh: its focus memory starts over and the
 * hand-off lands on the first episode, and its entry, loading, error and empty anchors all belong
 * to the season it shows. It speaks its heading once, on entry ("Season 2. Episode 1, …"),
 * because unlike the cast rail its name changes with the chips above it.
 */
@Composable
internal fun ShowSeasonsSection(
    seasons: List<SeasonUi>,
    selectedSeason: Long?,
    episodes: IglooRailState<EpisodeUi>,
    contentInset: PaddingValues,
    screenReader: Boolean,
    chipsRequester: FocusRequester,
    chipsUpRequester: FocusRequester?,
    chipsDownRequester: FocusRequester?,
    episodesRequester: FocusRequester,
    episodesUpRequester: FocusRequester?,
    episodesDownRequester: FocusRequester?,
    chipsSection: AnnouncedSection,
    episodesSection: AnnouncedSection,
    onEpisodesFocusChanged: (Boolean) -> Unit,
    onPressSeason: (Long) -> Unit,
    onRetrySeason: () -> Unit,
) {
    if (seasons.size >= 2) {
        val chips = remember(seasons) {
            seasons.map { season ->
                ChipSpec(
                    key = season.number,
                    text = season.chipText,
                    semanticLabel = season.chipDescription,
                    actionLabel = "Show ${season.label}",
                    testTag = "show_season_${season.number}",
                )
            }
        }
        Column(
            modifier = Modifier.announcedSection(chipsSection),
            verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
        ) {
            SectionHeading("Seasons", Modifier.insetHorizontally(contentInset))
            SelectableChipRow(
                chips = chips,
                selectedKey = selectedSeason,
                anchorRequester = chipsRequester,
                upRequester = chipsUpRequester ?: Cancel,
                downRequester = chipsDownRequester ?: Cancel,
                // The overlay owns the whole screen; there is no spine to exit to on the left.
                leftRequester = Cancel,
                contentInset = contentInset,
                rowTag = "show_season_row",
                onPress = onPressSeason,
            )
        }
    }

    val season = seasons.firstOrNull { it.number == selectedSeason } ?: return
    key(season.number) {
        DetailsRailSection(
            title = season.label,
            state = episodes,
            itemKey = { it.id },
            contentInset = contentInset,
            entryRequester = episodesRequester,
            upRequester = episodesUpRequester,
            downRequester = episodesDownRequester,
            modifier = Modifier
                .announcedSection(episodesSection)
                .onFocusChanged { onEpisodesFocusChanged(it.hasFocus) },
            cardAspect = IglooTheme.layout.wideAspect,
            cardWidth = IglooTheme.layout.wideCardWidth,
            screenReader = screenReader,
            announceSectionTitle = true,
            loadingLabel = "Loading ${season.label}",
            emptyIcon = IglooIcons.TvShows,
            emptyText = "No episodes of ${season.label} are in this library.",
            onRetry = onRetrySeason,
        ) { episode, itemModifier, _ ->
            EpisodeCard(
                episode = episode,
                // Episode playback is not on the page yet: the card is a landing site.
                onClick = null,
                modifier = itemModifier.testTag("episode_card_${episode.id}"),
            )
        }
    }
}
