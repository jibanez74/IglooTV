package com.igloo.blindpenguincoder.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.OVER_MEDIA_CONTROL_FILL
import com.igloo.blindpenguincoder.core.design.OVER_MEDIA_SECONDARY
import com.igloo.blindpenguincoder.core.design.OVER_MEDIA_TRACK
import com.igloo.blindpenguincoder.core.design.overMedia
import com.igloo.blindpenguincoder.core.design.rememberOverlayReveal
import com.igloo.blindpenguincoder.core.design.scaled
import com.igloo.blindpenguincoder.core.ui.ArtworkOrGlyph
import com.igloo.blindpenguincoder.core.ui.IglooIcons
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.focusRing
import com.igloo.blindpenguincoder.core.ui.formatEpisodeCode
import com.igloo.blindpenguincoder.core.ui.spokenSeason
import com.igloo.blindpenguincoder.playback.model.UpNextEpisode

/**
 * The Up Next card (docs/design-system.md section 11.8): over the last seconds of an episode, what
 * plays next and how long until it does. One focus target on the over-media black ground, the
 * transport button's recipe at card size: the next episode's still, the "Up next" eyebrow, "S1 E2
 * · Title" and the [status] line — the countdown, "Loading next episode…" at a held end, or why
 * the next episode could not be prepared. Center plays it now; Back, handled by the screen,
 * dismisses it. [spoken] is the card's whole description: the title and the status as it stood
 * when the card appeared or last changed what it was waiting for, never the falling count — a
 * timer narrating every second is section 12 noise. TalkBack reads it with the focus the card
 * takes, and reads it again when the sentence changes under its cursor; no live region.
 */
@Composable
internal fun UpNextCard(
    next: UpNextEpisode,
    status: String,
    spoken: String,
    requester: FocusRequester,
    upRequester: FocusRequester,
    downRequester: FocusRequester,
    onPlayNow: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val reveal by rememberOverlayReveal("upNextReveal")
    Row(
        modifier = modifier
            .graphicsLayer { alpha = reveal }
            .width(IglooTheme.layout.dialogWidth)
            .focusRing(
                focused = focused,
                radius = IglooTheme.radius.lg,
                fill = OVER_MEDIA_CONTROL_FILL,
            )
            .onFocusChanged {
                focused = it.isFocused
                onFocusChanged(it.isFocused)
            }
            .focusRequester(requester)
            .focusProperties {
                up = upRequester
                down = downRequester
                left = FocusRequester.Cancel
                right = FocusRequester.Cancel
            }
            .clickable(interactionSource = null, indication = null, onClick = onPlayNow)
            .clearAndSetSemantics {
                contentDescription = spoken
                role = Role.Button
                onClick(label = upNextActionLabel(next.resumable)) {
                    onPlayNow()
                    true
                }
            }
            .padding(IglooTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(UP_NEXT_STILL_WIDTH.scaled())
                .aspectRatio(IglooTheme.layout.wideAspect)
                .clip(RoundedCornerShape(IglooTheme.radius.md))
                .background(OVER_MEDIA_TRACK),
            contentAlignment = Alignment.Center,
        ) {
            ArtworkOrGlyph(
                imageUrl = next.stillUrl,
                fallbackIcon = IglooIcons.TvShows,
                fallbackTint = OVER_MEDIA_SECONDARY,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.xs)) {
            IglooText(
                text = "UP NEXT",
                style = IglooTheme.typography.label.overMedia(true),
                color = OVER_MEDIA_SECONDARY,
            )
            // Two lines: episode names run long ("The One with the Sonogram at the End") and a
            // name cut to its first words is not one the viewer can place.
            IglooText(
                text = upNextTitle(next),
                style = IglooTheme.typography.titleMedium.overMedia(true),
                color = Color.White,
                maxLines = 2,
            )
            IglooText(
                text = status,
                // Tabular digits: the count must not shuffle the line as it falls.
                style = IglooTheme.typography.bodyMedium.overMedia(true).copy(fontFeatureSettings = "tnum"),
                color = OVER_MEDIA_SECONDARY,
                maxLines = 2,
                modifier = Modifier.testTag("movie_up_next_status"),
            )
        }
    }
}

/** "S1 E2 · Title", the card's one line for the episode. */
internal fun upNextTitle(next: UpNextEpisode): String =
    "${formatEpisodeCode(next.seasonNumber, next.episodeNumber)} · ${next.name}"

/** "season 1, episode 2, Title": the code read aloud is letters and digits, not an episode. */
internal fun upNextSpokenTitle(next: UpNextEpisode): String =
    "${spokenSeason(next.seasonNumber)}, episode ${next.episodeNumber}, ${next.name}"

/** The card's count: "Playing in 12s", or "Resuming in 12s" when the next episode is resumable. */
internal fun upNextCountdown(secondsLeft: Int, resumable: Boolean): String =
    "${upNextVerb(resumable)} in ${secondsLeft}s"

/** What Center does on the card, as TalkBack offers it. */
internal fun upNextActionLabel(resumable: Boolean): String =
    if (resumable) "Resume now" else "Play now"

/**
 * The card's spoken sentence while it counts: the count as it stood when the card appeared. No
 * closing period — TalkBack ends the read with ". Button" itself.
 */
internal fun upNextAnnouncement(next: UpNextEpisode, secondsLeft: Int): String {
    val unit = if (secondsLeft == 1) "second" else "seconds"
    return "${upNextSpokenPrefix(next)} ${upNextVerb(next.resumable)} in $secondsLeft $unit"
}

/** The card's spoken sentence with a status that is not a count: the held end, or a failure. */
internal fun upNextSpokenStatus(next: UpNextEpisode, status: String): String =
    "${upNextSpokenPrefix(next)} ${status.trimEnd('.')}"

private fun upNextSpokenPrefix(next: UpNextEpisode): String = "Up next: ${upNextSpokenTitle(next)}."

private fun upNextVerb(resumable: Boolean): String = if (resumable) "Resuming" else "Playing"

/** The card's status while the episode has ended and the next request is still being prepared. */
internal const val UP_NEXT_LOADING = "Loading next episode…"

/** The still's width on the card; its height follows the 16:9 aspect. */
private val UP_NEXT_STILL_WIDTH = 160.dp
