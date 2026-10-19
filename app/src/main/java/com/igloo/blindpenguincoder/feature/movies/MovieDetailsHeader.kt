package com.igloo.blindpenguincoder.feature.movies

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusRequester.Companion.Cancel
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.overMedia
import com.igloo.blindpenguincoder.core.ui.IglooButton
import com.igloo.blindpenguincoder.core.ui.IglooButtonVariant
import com.igloo.blindpenguincoder.core.ui.IglooIconButton
import com.igloo.blindpenguincoder.core.ui.IglooIcons
import com.igloo.blindpenguincoder.core.ui.IglooNotice
import com.igloo.blindpenguincoder.core.ui.joinedLine
import com.igloo.blindpenguincoder.feature.shared.DetailsHeroInfo
import com.igloo.blindpenguincoder.feature.shared.HeroArtwork
import com.igloo.blindpenguincoder.feature.shared.HeroSingleActionRow
import com.igloo.blindpenguincoder.feature.shared.ProgressSyncRetry
import com.igloo.blindpenguincoder.feature.shared.ResumeProgress

/**
 * The hero's content block (docs/design-system.md section 11.4): poster left; title, tagline,
 * metadata chips, genres, actions, and the resume strip right. The section 3.2 over-media
 * treatment — white text with shadows, black chip grounds — is gated on [overMedia], exactly
 * like the home hero: with no backdrop behind it everything falls back to token colors.
 */
@Composable
internal fun MovieDetailsHeader(
    movie: MovieDetailsUi,
    overMedia: Boolean,
    actions: MovieDetailsActions,
    onPlay: () -> Unit,
    playReturnRequester: FocusRequester,
    onPlayTrailer: (() -> Unit)?,
    spokenAccessibilityEnabled: Boolean,
    heroInfoRequester: FocusRequester,
    primaryRequester: FocusRequester,
    heroTrailerReturnRequester: FocusRequester,
    watchedRequester: FocusRequester,
    likeRequester: FocusRequester,
    moreRequester: FocusRequester,
    downRequester: FocusRequester?,
    onActionFocused: (FocusRequester) -> Unit,
    onOpenMoreMenu: () -> Unit,
    onMoreAnchorPositioned: (Rect) -> Unit,
    mutationNotice: String?,
    progressSyncError: String?,
    onRetryProgressSync: () -> Unit,
    progressRetryRequester: FocusRequester,
    progressRetryUpRequester: FocusRequester?,
    progressRetryDownRequester: FocusRequester?,
    onProgressRetryFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Up from the action row reaches the hero's reading stop only while a screen reader runs;
    // otherwise the row keeps its pinned top edge and the stop is never composed as a target.
    val actionUpRequester = if (spokenAccessibilityEnabled) heroInfoRequester else Cancel

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.xl),
        verticalAlignment = Alignment.Bottom,
    ) {
        HeroArtwork(
            imageUrl = movie.posterUrl,
            aspect = IglooTheme.layout.posterAspect,
            radius = IglooTheme.radius.lg,
            fallbackIcon = IglooIcons.Movies,
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
        ) {
            DetailsHeroInfo(
                title = movie.title,
                tagline = movie.tagline,
                ratingBadge = movie.ratingBadge,
                chips = listOfNotNull(movie.certification) + movie.mediaBadges,
                plainLine = joinedLine(listOfNotNull(movie.runtimeText, movie.releaseDateText), " · "),
                metadataDescription = movie.metadataDescription,
                genresLine = movie.genresLine,
                readingStopDescription = movie.heroInfoDescription,
                overMedia = overMedia,
                readingStop = spokenAccessibilityEnabled,
                requester = heroInfoRequester,
                downRequester = primaryRequester,
            )
            val actionRowModifier = Modifier.padding(top = IglooTheme.spacing.sm)
            when (actions) {
                is MovieDetailsActions.Library -> LibraryActionRow(
                    movie = movie,
                    overMedia = overMedia,
                    actions = actions,
                    onPlay = onPlay,
                    playReturnRequester = playReturnRequester,
                    playRequester = primaryRequester,
                    watchedRequester = watchedRequester,
                    likeRequester = likeRequester,
                    moreRequester = moreRequester,
                    upRequester = actionUpRequester,
                    downRequester = downRequester,
                    onActionFocused = onActionFocused,
                    onOpenMoreMenu = onOpenMoreMenu,
                    onMoreAnchorPositioned = onMoreAnchorPositioned,
                    modifier = actionRowModifier,
                )

                // The in-theaters row is Play Trailer alone, composed only when TMDB lists a
                // trailer (section 11.4.2). "Play", not the poster default's "Open": the
                // announced action has to say what pressing does (section 12).
                is MovieDetailsActions.Theater -> if (onPlayTrailer != null) {
                    HeroSingleActionRow(
                        text = "Play Trailer",
                        semanticLabel = "Play Trailer",
                        onClick = onPlayTrailer,
                        tag = "details_play_trailer",
                        requester = primaryRequester,
                        returnRequester = heroTrailerReturnRequester,
                        upRequester = actionUpRequester,
                        downRequester = downRequester,
                        onFocused = { onActionFocused(primaryRequester) },
                        modifier = actionRowModifier,
                    )
                }
            }
            if (mutationNotice != null) {
                IglooNotice(
                    text = mutationNotice,
                    modifier = Modifier.testTag("details_mutation_notice"),
                )
            }
            if (progressSyncError != null) {
                ProgressSyncRetry(
                    message = progressSyncError,
                    onRetry = onRetryProgressSync,
                    retryModifier = Modifier
                        .focusRequester(progressRetryRequester)
                        .focusProperties {
                            up = progressRetryUpRequester ?: Cancel
                            down = progressRetryDownRequester ?: Cancel
                            left = Cancel
                            right = Cancel
                        }
                        .onFocusChanged { onProgressRetryFocusChanged(it.isFocused) }
                        .testTag("details_progress_retry"),
                    modifier = Modifier.testTag("details_progress_error"),
                )
            }
        }
    }
}

@Composable
private fun LibraryActionRow(
    movie: MovieDetailsUi,
    overMedia: Boolean,
    actions: MovieDetailsActions.Library,
    onPlay: () -> Unit,
    playReturnRequester: FocusRequester,
    playRequester: FocusRequester,
    watchedRequester: FocusRequester,
    likeRequester: FocusRequester,
    moreRequester: FocusRequester,
    upRequester: FocusRequester,
    downRequester: FocusRequester?,
    onActionFocused: (FocusRequester) -> Unit,
    onOpenMoreMenu: () -> Unit,
    onMoreAnchorPositioned: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = IglooTheme.colors
    val watched = movie.watched == true
    val liked = movie.liked == true
    // Its POST toggles whatever the server holds, so without a known base state there is no
    // press to make — unlike Watched, whose PUT carries the value it wants (section 11.4.1).
    val likeEnabled = movie.liked != null
    // Every direction out of the row is pinned: the shell is still composed under this overlay,
    // so an unpinned edge lets a spatial search land on a card the user cannot see. Down is
    // hand-wired to the first section below rather than left to a beam heuristic.
    val rowFocus = Modifier.focusProperties {
        up = upRequester
        down = downRequester ?: Cancel
    }
    // `ring` and `primary` are the same value, so a resting Play carries several times more
    // glacier than the ring on whatever is actually focused — the eye lands on Play even when
    // the remote press will activate a sibling. Play steps back while a sibling holds focus so
    // the focused control is the strongest thing in the row.
    var rowHasFocus by remember { mutableStateOf(false) }
    var playFocused by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.onFocusChanged { rowHasFocus = it.hasFocus },
        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
    ) {
        // Play, its strip, and the reserved longest caption share one intrinsic-width column,
        // so progress reads as Play's and a late response cannot resize the row. The button and
        // strip fill that typography-derived width; a fixed value would drift under localisation.
        Column(modifier = Modifier.width(IntrinsicSize.Max)) {
            IglooButton(
                text = "Play",
                onClick = onPlay,
                icon = IglooIcons.Play,
                // The resume caption below is plain text a TV screen reader can never reach, so
                // Play carries the exact resume point and spoken remaining time as state while
                // its label stays the action.
                semanticLabel = if (movie.progress != null) "Play" else "Play ${movie.title}",
                stateDescription = movie.progress?.resumeStateDescription,
                actionLabel = "Play",
                recessed = rowHasFocus && !playFocused,
                modifier = Modifier
                    .testTag("details_play")
                    .focusRequester(playRequester)
                    // The node the movie player restores focus to on close — two requesters on
                    // one button, the same pairing the trailer's action row carries.
                    .focusRequester(playReturnRequester)
                    .fillMaxWidth()
                    .then(rowFocus)
                    .focusProperties { left = Cancel }
                    .onFocusChanged {
                        playFocused = it.isFocused
                        if (it.isFocused) onActionFocused(playRequester)
                    },
            )
            ResumeProgress(progress = movie.progress, overMedia = overMedia)
        }
        IglooButton(
            text = if (watched) "Watched" else "Watch",
            labelVariants = TOGGLE_WATCHED_LABELS,
            onClick = actions.onToggleWatched,
            variant = IglooButtonVariant.Ghost,
            icon = IglooIcons.Check,
            iconTint = if (watched) colors.primary else null,
            overMedia = overMedia,
            // The announced label is the visible one — "Watch" until watched — because a constant
            // "Watched" against a "Not watched" state read as a contradiction under TalkBack.
            // Null until the status request lands: the button has to look like something in the
            // meantime, but announcing "Not watched" for a movie that is watched states as fact
            // something the app does not know yet.
            stateDescription = movie.watched?.let { if (it) "Marked as watched" else "Not watched" },
            actionLabel = if (watched) "Remove from watched" else "Mark as watched",
            modifier = Modifier
                .testTag("details_watched")
                .focusRequester(watchedRequester)
                .then(rowFocus)
                // A disabled Like is not focusable, so while its status is unknown it leaves
                // the focus tree, and a rightward search from here would have to guess past the
                // hole. Right is hand-wired for the same reason down is: it skips straight to
                // More, the always-present control that now pins the row's right edge.
                .focusProperties { right = if (likeEnabled) likeRequester else moreRequester }
                .onFocusChanged { if (it.isFocused) onActionFocused(watchedRequester) },
        )
        IglooButton(
            text = if (liked) "Liked" else "Like",
            labelVariants = TOGGLE_LIKE_LABELS,
            onClick = actions.onToggleLike,
            variant = IglooButtonVariant.Ghost,
            icon = if (liked) IglooIcons.HeartFilled else IglooIcons.Heart,
            iconTint = if (liked) colors.primary else null,
            overMedia = overMedia,
            semanticLabel = "Like",
            stateDescription = movie.liked?.let { if (it) "Liked" else "Not liked" },
            actionLabel = if (liked) "Remove like" else "Like this movie",
            enabled = likeEnabled,
            modifier = Modifier
                .testTag("details_like")
                .focusRequester(likeRequester)
                .then(rowFocus)
                .focusProperties { right = moreRequester }
                .onFocusChanged { if (it.isFocused) onActionFocused(likeRequester) },
        )
        IglooIconButton(
            icon = IglooIcons.MoreVertical,
            semanticLabel = "More options",
            onClick = onOpenMoreMenu,
            overMedia = overMedia,
            modifier = Modifier
                .testTag("details_more")
                .focusRequester(moreRequester)
                .then(rowFocus)
                .focusProperties { right = Cancel }
                .onGloballyPositioned { onMoreAnchorPositioned(it.boundsInRoot()) }
                .onFocusChanged { if (it.isFocused) onActionFocused(moreRequester) },
        )
    }
}

// Both labels of each toggle, so the button reserves the wider one and the flip is a repaint
// rather than a relayout shoving the controls to its right (Like, and the More trigger).
private val TOGGLE_WATCHED_LABELS = listOf("Watch", "Watched")
private val TOGGLE_LIKE_LABELS = listOf("Like", "Liked")

