package com.igloo.blindpenguincoder.feature.movies

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusRequester.Companion.Cancel
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.overMedia
import com.igloo.blindpenguincoder.core.design.scaled
import com.igloo.blindpenguincoder.core.ui.IglooButton
import com.igloo.blindpenguincoder.core.ui.IglooButtonVariant
import com.igloo.blindpenguincoder.core.ui.IglooIcons
import com.igloo.blindpenguincoder.core.ui.IglooNotice
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.RatingBadge
import com.igloo.blindpenguincoder.core.ui.iglooSurface

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
    playRequester: FocusRequester,
    watchedRequester: FocusRequester,
    likeRequester: FocusRequester,
    downRequester: FocusRequester?,
    onActionFocused: (FocusRequester) -> Unit,
    onPlay: () -> Unit,
    onToggleWatched: () -> Unit,
    onToggleLike: () -> Unit,
    mutationNotice: String?,
    modifier: Modifier = Modifier,
) {
    val colors = IglooTheme.colors

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.xl),
        verticalAlignment = Alignment.Bottom,
    ) {
        HeaderPoster(posterUrl = movie.posterUrl)

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
        ) {
            IglooText(
                text = movie.title,
                style = IglooTheme.typography.titleLarge.overMedia(overMedia),
                color = if (overMedia) Color.White else colors.foreground,
                maxLines = 2,
                modifier = Modifier.semantics { heading() },
            )
            if (movie.tagline != null) {
                IglooText(
                    text = "“${movie.tagline}”",
                    style = IglooTheme.typography.bodyLarge
                        .copy(fontStyle = FontStyle.Italic)
                        .overMedia(overMedia),
                    color = if (overMedia) Color.White.copy(alpha = 0.85f) else colors.mutedForeground,
                    maxLines = 1,
                )
            }
            MetadataRow(movie = movie, overMedia = overMedia)
            if (movie.genresLine != null) {
                IglooText(
                    text = movie.genresLine,
                    style = IglooTheme.typography.label.overMedia(overMedia),
                    color = if (overMedia) Color.White.copy(alpha = 0.75f) else colors.mutedForeground,
                    maxLines = 1,
                )
            }
            ActionRow(
                movie = movie,
                overMedia = overMedia,
                playRequester = playRequester,
                watchedRequester = watchedRequester,
                likeRequester = likeRequester,
                downRequester = downRequester,
                onActionFocused = onActionFocused,
                onPlay = onPlay,
                onToggleWatched = onToggleWatched,
                onToggleLike = onToggleLike,
                modifier = Modifier.padding(top = IglooTheme.spacing.sm),
            )
            if (mutationNotice != null) {
                IglooNotice(
                    text = mutationNotice,
                    modifier = Modifier.testTag("details_mutation_notice"),
                )
            }
        }
    }
}

/** Decorative — the artwork repeats nothing the text does not say, so TalkBack skips it. */
@Composable
private fun HeaderPoster(posterUrl: String?) {
    val colors = IglooTheme.colors
    var imageFailed by remember(posterUrl) { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .width(IglooTheme.layout.posterWidth)
            .aspectRatio(IglooTheme.layout.posterAspect)
            .iglooSurface(radius = IglooTheme.radius.lg, fill = colors.muted),
        contentAlignment = Alignment.Center,
    ) {
        if (posterUrl != null && !imageFailed) {
            AsyncImage(
                model = posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onState = { state ->
                    if (state is AsyncImagePainter.State.Error) imageFailed = true
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Image(
                imageVector = IglooIcons.Movies,
                contentDescription = null,
                colorFilter = ColorFilter.tint(colors.mutedForeground),
                modifier = Modifier.size(IglooTheme.icons.lg),
            )
        }
    }
}

/**
 * Rating badge, certification, media badges, runtime, and release date — visually a row of
 * chips, but one TalkBack stop: eight consecutive two-character announcements would be noise,
 * so the ViewModel composes the one sentence the row speaks.
 */
@Composable
private fun MetadataRow(
    movie: MovieDetailsUi,
    overMedia: Boolean,
) {
    val colors = IglooTheme.colors
    Row(
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = movie.metadataDescription
        },
        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (movie.ratingBadge != null) {
            RatingBadge(
                spec = movie.ratingBadge,
                radius = IglooTheme.radius.pill,
                verticalPadding = IglooTheme.spacing.xs,
            )
        }
        if (movie.certification != null) {
            DetailChip(text = movie.certification, overMedia = overMedia)
        }
        movie.mediaBadges.forEach { badge ->
            DetailChip(text = badge, overMedia = overMedia)
        }
        val plainLine = listOfNotNull(movie.runtimeText, movie.releaseDateText)
        if (plainLine.isNotEmpty()) {
            IglooText(
                text = plainLine.joinToString(" · "),
                style = IglooTheme.typography.label.overMedia(overMedia),
                color = if (overMedia) Color.White.copy(alpha = 0.85f) else colors.mutedForeground,
                maxLines = 1,
                modifier = Modifier.padding(start = IglooTheme.spacing.xs),
            )
        }
    }
}

/**
 * The pill ground is the section 3.2 over-media chip literal — black with a translucent white
 * hairline, deliberately theme-blind because a backdrop is behind it. The fallback is the token
 * pair the badge alphas of section 3.1 prescribe for chrome on a plain canvas.
 */
@Composable
private fun DetailChip(
    text: String,
    overMedia: Boolean,
) {
    val colors = IglooTheme.colors
    IglooText(
        text = text,
        style = IglooTheme.typography.label,
        color = if (overMedia) Color.White.copy(alpha = 0.90f) else colors.foreground,
        maxLines = 1,
        modifier = Modifier
            .iglooSurface(
                radius = IglooTheme.radius.pill,
                fill = if (overMedia) Color.Black.copy(alpha = 0.45f) else colors.muted,
                border = if (overMedia) Color.White.copy(alpha = 0.25f) else colors.border,
            )
            .padding(horizontal = 12.dp.scaled(), vertical = IglooTheme.spacing.xs),
    )
}

@Composable
private fun ActionRow(
    movie: MovieDetailsUi,
    overMedia: Boolean,
    playRequester: FocusRequester,
    watchedRequester: FocusRequester,
    likeRequester: FocusRequester,
    downRequester: FocusRequester?,
    onActionFocused: (FocusRequester) -> Unit,
    onPlay: () -> Unit,
    onToggleWatched: () -> Unit,
    onToggleLike: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = IglooTheme.colors
    // Section 3.2: a Ghost button's transparent ground and token text are licensed only on a
    // token canvas; over the backdrop the buttons carry the same black ground as the chips.
    val ghostFill = if (overMedia) Color.Black.copy(alpha = 0.45f) else null
    val ghostContent = if (overMedia) Color.White else null
    val watched = movie.watched == true
    val liked = movie.liked == true
    // Its POST toggles whatever the server holds, so without a known base state there is no
    // press to make — unlike Watched, whose PUT carries the value it wants (section 11.4.1).
    val likeEnabled = movie.liked != null
    // Every direction out of the row is pinned: the shell is still composed under this overlay,
    // so an unpinned edge lets a spatial search land on a card the user cannot see. Down is
    // hand-wired to the first section below rather than left to a beam heuristic.
    val rowFocus = Modifier.focusProperties {
        up = Cancel
        down = downRequester ?: Cancel
    }
    // `ring` and `primary` are the same value, so a resting Play carries several times more
    // glacier than the ring on whatever is actually focused — the eye lands on Play and the
    // press toggles watched. Play steps back while a sibling holds focus so the focused control
    // is the strongest thing in the row.
    var rowHasFocus by remember { mutableStateOf(false) }
    var playFocused by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.onFocusChanged { rowHasFocus = it.hasFocus },
        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
    ) {
        // Play and its resume strip share a column sized to Play, so the strip reads as Play's
        // progress rather than the whole row's. Width comes from the button's own intrinsic
        // width — a fixed value would drift the moment the label is localised.
        Column(modifier = Modifier.width(IntrinsicSize.Min)) {
            IglooButton(
                text = "Play",
                onClick = onPlay,
                icon = IglooIcons.Play,
                semanticLabel = "Play ${movie.title}",
                recessed = rowHasFocus && !playFocused,
                modifier = Modifier
                    .testTag("details_play")
                    .focusRequester(playRequester)
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
            onClick = onToggleWatched,
            variant = IglooButtonVariant.Ghost,
            icon = IglooIcons.Check,
            iconTint = if (watched) colors.primary else null,
            restingFill = ghostFill,
            contentColor = ghostContent,
            semanticLabel = "Watched",
            // Null until the status request lands: the button has to look like something in the
            // meantime, but announcing "Not watched" for a movie that is watched states as fact
            // something the app does not know yet.
            stateDescription = movie.watched?.let { if (it) "Marked as watched" else "Not watched" },
            actionLabel = if (watched) "Remove from watched" else "Mark as watched",
            modifier = Modifier
                .testTag("details_watched")
                .focusRequester(watchedRequester)
                .then(rowFocus)
                // A disabled Like is not focusable, so while its status is unknown it leaves the
                // focus tree and takes the row's right-edge Cancel with it. Right is hand-wired
                // here for the same reason down is: the row's edges stay pinned by controls that
                // are always present, not by one that comes and goes.
                .focusProperties { right = if (likeEnabled) likeRequester else Cancel }
                .onFocusChanged { if (it.isFocused) onActionFocused(watchedRequester) },
        )
        IglooButton(
            text = if (liked) "Liked" else "Like",
            labelVariants = TOGGLE_LIKE_LABELS,
            onClick = onToggleLike,
            variant = IglooButtonVariant.Ghost,
            icon = if (liked) IglooIcons.HeartFilled else IglooIcons.Heart,
            iconTint = if (liked) colors.primary else null,
            restingFill = ghostFill,
            contentColor = ghostContent,
            semanticLabel = "Like",
            stateDescription = movie.liked?.let { if (it) "Liked" else "Not liked" },
            actionLabel = if (liked) "Remove like" else "Like this movie",
            enabled = likeEnabled,
            modifier = Modifier
                .testTag("details_like")
                .focusRequester(likeRequester)
                .then(rowFocus)
                .focusProperties { right = Cancel }
                .onFocusChanged { if (it.isFocused) onActionFocused(likeRequester) },
        )
    }
}

// Both labels of each toggle, so the button reserves the wider one and the flip is a repaint
// rather than a relayout shoving the controls to its right (More, when its menu lands).
private val TOGGLE_WATCHED_LABELS = listOf("Watch", "Watched")
private val TOGGLE_LIKE_LABELS = listOf("Like", "Liked")

/**
 * The thin resume strip and its minutes-left caption. The strip repeats what the caption says,
 * so only the caption's text node speaks. It fills the column Play sizes, so it is exactly as
 * wide as the button it belongs to; the top gap clears Play's focus ring at its 1.05x scale.
 *
 * The slot is composed even with no [progress] — invisible and silent — because the progress
 * request lands after first paint and toggling Watched removes the strip: either would reflow
 * the bottom-anchored hero under the user's eye if the strip's height came and went. The
 * skeleton reserves the same slot so the loading→loaded swap does not move Play.
 */
@Composable
internal fun ResumeProgress(
    progress: ProgressUi?,
    overMedia: Boolean,
) {
    val colors = IglooTheme.colors
    val visible = progress != null
    Column(
        modifier = Modifier
            .padding(top = IglooTheme.spacing.sm)
            .alpha(if (visible) 1f else 0f)
            .then(if (visible) Modifier else Modifier.clearAndSetSemantics {}),
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.xs),
    ) {
        Box(
            modifier = Modifier
                .then(if (visible) Modifier.testTag("details_resume_track") else Modifier)
                .fillMaxWidth()
                .height(4.dp.scaled())
                .iglooSurface(
                    radius = IglooTheme.radius.pill,
                    // The PosterCardProgress track literal over media; muted on the fallback.
                    fill = if (overMedia) Color.Black.copy(alpha = 0.40f) else colors.muted,
                    border = Color.Transparent,
                    borderWidth = 0.dp,
                ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress?.fraction ?: 0f)
                    .fillMaxHeight()
                    .background(colors.primary),
            )
        }
        IglooText(
            text = progress?.minutesLeftLabel ?: "",
            style = IglooTheme.typography.label.overMedia(overMedia),
            color = if (overMedia) Color.White.copy(alpha = 0.85f) else colors.mutedForeground,
            maxLines = 1,
        )
    }
}
