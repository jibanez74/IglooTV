package com.igloo.blindpenguincoder.feature.shows

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.CustomAccessibilityAction
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.OVER_MEDIA_CONTROL_FILL
import com.igloo.blindpenguincoder.core.design.OVER_MEDIA_TRACK
import com.igloo.blindpenguincoder.core.ui.ArtworkOrGlyph
import com.igloo.blindpenguincoder.core.ui.IglooIcons
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.ProgressTrack
import com.igloo.blindpenguincoder.core.ui.cardWidth
import com.igloo.blindpenguincoder.core.ui.focusRing
import com.igloo.blindpenguincoder.core.ui.iglooSurface
import com.igloo.blindpenguincoder.core.ui.mediaCardInteraction
import com.igloo.blindpenguincoder.core.ui.mediaSectionDescription
import com.igloo.blindpenguincoder.core.ui.rememberArtworkState
import com.igloo.blindpenguincoder.feature.shared.FadeClampedText

/**
 * One episode in the show page's season rail (docs/design-system.md section 11.4.3): the 16:9
 * still at `wideCardWidth` carrying the focus treatment, a progress bar on its bottom edge while
 * the episode has a position worth resuming and a check once it is watched; below it the title
 * ("1. Pilot"), one ellipsized metadata line, and the overview clamped to three lines with the
 * overview's fade.
 *
 * Focus, press and announcement are the poster card's (`mediaCardInteraction`): the whole card
 * is one focus target and one node speaking [EpisodeUi.description], which carries everything
 * drawn, and [onClick] plays the episode — the action TalkBack offers is "Play 1. Pilot". A held
 * Center opens the card's menu through [onOpenMenu] with the card's bounds to anchor it; TalkBack
 * gets that menu's one choice as a custom action on the same node, "Mark watched" or "Mark
 * unwatched", through [onToggleWatched].
 *
 * The check's pill takes the section 3.2 black ground only over a decoded still; over the glyph
 * fallback it sits on the token pair instead.
 */
@Composable
internal fun EpisodeCard(
    episode: EpisodeUi,
    onClick: () -> Unit,
    onOpenMenu: (anchorBounds: Rect) -> Unit,
    onToggleWatched: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = IglooTheme.colors
    var focused by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val still = rememberArtworkState(episode.stillUrl)

    Column(
        modifier = modifier
            .cardWidth(IglooTheme.layout.wideCardWidth)
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .mediaCardInteraction(
                onFocusChanged = { focused = it },
                onClick = onClick,
                description = mediaSectionDescription(episode.description),
                actionLabel = "Play ${episode.title}",
                onLongClick = { bounds?.let(onOpenMenu) },
                customActions = listOf(
                    CustomAccessibilityAction(episode.watchedToggleLabel) {
                        onToggleWatched()
                        true
                    },
                ),
            ),
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(IglooTheme.layout.wideAspect)
                // focusRing owns the clip, so the muted fill doubles as the image backdrop and
                // nothing here may clip over it.
                .focusRing(focused = focused, radius = IglooTheme.radius.lg, fill = colors.muted),
            contentAlignment = Alignment.Center,
        ) {
            ArtworkOrGlyph(
                imageUrl = episode.stillUrl,
                fallbackIcon = IglooIcons.TvShows,
                state = still,
            )
            if (episode.progressFraction != null) {
                ProgressTrack(
                    fraction = episode.progressFraction,
                    ground = OVER_MEDIA_TRACK,
                    fill = colors.primary,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            if (episode.watched) {
                WatchedCheck(
                    overMedia = still.showsArtwork,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(IglooTheme.spacing.sm),
                )
            }
        }
        // Two lines reserved, not just allowed: a title that wraps must not push its meta line
        // and overview below the neighbours' (Friends S10 E1 beside E2 at 4K, 2026-10-19). The
        // rail's rows stay aligned and a short title leaves its second line empty.
        IglooText(
            text = episode.title,
            style = IglooTheme.typography.bodyLarge,
            color = colors.foreground,
            maxLines = EPISODE_TITLE_LINES,
            minLines = EPISODE_TITLE_LINES,
        )
        if (episode.metaLine != null) {
            IglooText(
                text = episode.metaLine,
                style = IglooTheme.typography.label,
                color = colors.mutedForeground,
                maxLines = 1,
            )
        }
        if (episode.overview != null) {
            // foreground, not mutedForeground: prose the user came to read has to clear the
            // section 12 body contrast target.
            FadeClampedText(
                text = episode.overview,
                style = IglooTheme.typography.bodyMedium,
                color = colors.foreground,
                maxLines = EPISODE_OVERVIEW_MAX_LINES,
            )
        }
    }
}

/** The watched mark: decorative, because the card's sentence already says "Watched". */
@Composable
private fun WatchedCheck(overMedia: Boolean, modifier: Modifier = Modifier) {
    val colors = IglooTheme.colors
    Box(
        modifier = modifier.iglooSurface(
            radius = IglooTheme.radius.pill,
            fill = if (overMedia) OVER_MEDIA_CONTROL_FILL else colors.muted,
            border = if (overMedia) Color.White.copy(alpha = 0.25f) else colors.border,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            imageVector = IglooIcons.Check,
            contentDescription = null,
            colorFilter = ColorFilter.tint(if (overMedia) Color.White else colors.foreground),
            modifier = Modifier
                .padding(IglooTheme.spacing.xs)
                .size(IglooTheme.icons.md),
        )
    }
}

/** The home hero's card clamp (section 4.1): a rail card must not grow with its synopsis. */
private const val EPISODE_OVERVIEW_MAX_LINES = 3

/** Title lines every episode card reserves, so the rail's meta and overview rows line up. */
private const val EPISODE_TITLE_LINES = 2
