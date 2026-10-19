package com.igloo.blindpenguincoder.feature.shows

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusRequester.Companion.Cancel
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.scaled
import com.igloo.blindpenguincoder.core.ui.AnnouncedSection
import com.igloo.blindpenguincoder.core.ui.IglooIcons
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.core.ui.announcedSection
import com.igloo.blindpenguincoder.feature.shared.DetailsHeroInfo
import com.igloo.blindpenguincoder.feature.shared.HeroArtwork
import com.igloo.blindpenguincoder.feature.shared.HeroSingleActionRow
import com.igloo.blindpenguincoder.feature.shared.ResumeProgress

/**
 * The show hero's content block (docs/design-system.md section 11.4.3): poster left; title,
 * tagline, the metadata row (rating, certification and status chips, then seasons, episodes and
 * years), genres and the Play/Resume action right — the movie hero's shape, through the shared
 * pieces, with the section 3.2 over-media treatment gated on [overMedia].
 *
 * The action slot is composed whenever the show has seasons and keeps one footprint — a button
 * over the movie hero's resume strip — in every state, so the bottom-anchored hero never reflows
 * when a season switch retargets it: the selected season's [playTarget] as an inert Play/Resume
 * button (no playback yet — the `IglooButton` contract) with the strip showing while it resumes,
 * a muted stub while that season loads, and an empty, silent slot when it failed or has no
 * episodes. A show with no seasons has no slot at all.
 */
@Composable
internal fun ShowDetailsHeader(
    show: ShowDetailsUi,
    episodes: IglooRailState<EpisodeUi>,
    playTarget: PlayTargetUi?,
    overMedia: Boolean,
    spokenAccessibilityEnabled: Boolean,
    heroInfoRequester: FocusRequester,
    heroInfoDownRequester: FocusRequester?,
    actionRequester: FocusRequester,
    actionUpRequester: FocusRequester?,
    actionDownRequester: FocusRequester?,
    heroInfoSection: AnnouncedSection,
    actionSection: AnnouncedSection,
    onActionFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.xl),
        verticalAlignment = Alignment.Bottom,
    ) {
        HeroArtwork(
            imageUrl = show.posterUrl,
            aspect = IglooTheme.layout.posterAspect,
            radius = IglooTheme.radius.lg,
            fallbackIcon = IglooIcons.TvShows,
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
        ) {
            DetailsHeroInfo(
                title = show.title,
                tagline = show.tagline,
                ratingBadge = show.ratingBadge,
                chips = listOfNotNull(show.certification, show.status),
                plainLine = show.extentLine,
                metadataDescription = show.metadataDescription,
                genresLine = show.genresLine,
                readingStopDescription = show.heroInfoDescription,
                overMedia = overMedia,
                readingStop = spokenAccessibilityEnabled,
                requester = heroInfoRequester,
                downRequester = heroInfoDownRequester ?: Cancel,
                modifier = Modifier.announcedSection(heroInfoSection),
            )
            if (show.seasons.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .announcedSection(actionSection)
                        .onFocusChanged { onActionFocusChanged(it.hasFocus) }
                        .padding(top = IglooTheme.spacing.sm),
                ) {
                    if (playTarget != null) {
                        HeroSingleActionRow(
                            text = playTarget.label,
                            semanticLabel = playTarget.spokenLabel,
                            onClick = null,
                            tag = "details_play",
                            requester = actionRequester,
                            upRequester = actionUpRequester ?: Cancel,
                            downRequester = actionDownRequester,
                            stateDescription = playTarget.progress?.resumeStateDescription,
                            belowButton = {
                                ResumeProgress(progress = playTarget.progress, overMedia = overMedia)
                            },
                        )
                    } else {
                        // The same footprint, button and strip, so the season's target landing
                        // later does not move anything: a muted stub while the season loads, an
                        // empty slot when it failed or has nothing to play.
                        Column(modifier = Modifier.width(IntrinsicSize.Max)) {
                            Box(
                                modifier = Modifier
                                    .width(PLAY_STUB_WIDTH.scaled())
                                    .heightIn(min = IglooTheme.sizes.controlHeight)
                                    .then(
                                        if (episodes is IglooRailState.Loading) {
                                            Modifier
                                                .testTag("details_play_stub")
                                                .background(
                                                    IglooTheme.colors.muted,
                                                    RoundedCornerShape(IglooTheme.radius.lg),
                                                )
                                        } else {
                                            Modifier
                                        },
                                    ),
                            )
                            ResumeProgress(progress = null, overMedia = overMedia)
                        }
                    }
                }
            }
        }
    }
}

/** The Play/Resume button's approximate footprint, so the loading stand-ins match it. */
internal val PLAY_STUB_WIDTH = 160.dp
