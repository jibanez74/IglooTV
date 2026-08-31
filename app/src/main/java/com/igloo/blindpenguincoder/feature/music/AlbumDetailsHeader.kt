package com.igloo.blindpenguincoder.feature.music

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.overMedia
import com.igloo.blindpenguincoder.core.design.scaled
import com.igloo.blindpenguincoder.core.ui.IglooButton
import com.igloo.blindpenguincoder.core.ui.IglooButtonVariant
import com.igloo.blindpenguincoder.core.ui.IglooIcons
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.focusRing
import com.igloo.blindpenguincoder.core.ui.iglooSurface

/**
 * The album hero's content block (docs/design-system.md section 11.5.1): square cover left;
 * title, artist, metadata chips, genres, the Spotify popularity meter, and the action row
 * right. The section 3.2 over-media treatment is gated on [overMedia], exactly like the movie
 * hero: with no backdrop decoded behind it everything falls back to token colors.
 */
@Composable
internal fun AlbumDetailsHeader(
    album: AlbumDetailsUi,
    overMedia: Boolean,
    hasHeroActions: Boolean,
    spokenAccessibilityEnabled: Boolean,
    heroInfoRequester: FocusRequester,
    primaryRequester: FocusRequester,
    shuffleRequester: FocusRequester,
    downRequester: FocusRequester,
    onActionFocused: (FocusRequester) -> Unit,
    onPlayAlbum: () -> Unit,
    onShuffle: () -> Unit,
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
        HeaderCover(coverUrl = album.coverUrl)

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
        ) {
            AlbumHeroInfo(
                album = album,
                overMedia = overMedia,
                readingStop = spokenAccessibilityEnabled,
                requester = heroInfoRequester,
                downRequester = primaryRequester,
            )
            if (hasHeroActions) {
                AlbumActionRow(
                    overMedia = overMedia,
                    playRequester = primaryRequester,
                    shuffleRequester = shuffleRequester,
                    upRequester = actionUpRequester,
                    downRequester = downRequester,
                    onActionFocused = onActionFocused,
                    onPlayAlbum = onPlayAlbum,
                    onShuffle = onShuffle,
                    modifier = Modifier.padding(top = IglooTheme.spacing.sm),
                )
            }
        }
    }
}

/**
 * The hero's prose: title, artist, metadata chips, genres, and the popularity meter. TV
 * TalkBack follows input focus and never traverses plain text, so while a screen reader runs
 * this whole block is one reading stop — reachable by pressing up from the action row — that
 * speaks everything in a single announcement.
 */
@Composable
private fun AlbumHeroInfo(
    album: AlbumDetailsUi,
    overMedia: Boolean,
    readingStop: Boolean,
    requester: FocusRequester,
    downRequester: FocusRequester,
) {
    val colors = IglooTheme.colors
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .then(
                if (readingStop) {
                    Modifier
                        .testTag("album_hero_info")
                        // The About panel's focus treatment: a fill and ring, no scale, because
                        // this is a focus target only and carries no action to promise. Over the
                        // backdrop the fill is the section 3.2 black ground, not the token card.
                        .focusRing(
                            focused = focused,
                            radius = IglooTheme.radius.lg,
                            fill = when {
                                !focused -> Color.Transparent
                                overMedia -> Color.Black.copy(alpha = 0.45f)
                                else -> colors.card.copy(alpha = 0.72f)
                            },
                            scaleOnFocus = false,
                        )
                        .focusRequester(requester)
                        .focusProperties {
                            up = Cancel
                            left = Cancel
                            right = Cancel
                            down = downRequester
                        }
                        .onFocusChanged { focused = it.isFocused }
                        .focusable()
                        .clearAndSetSemantics {
                            contentDescription = album.heroInfoDescription
                        }
                } else {
                    Modifier
                },
            ),
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
    ) {
        IglooText(
            text = album.title,
            style = IglooTheme.typography.titleLarge.overMedia(overMedia),
            color = if (overMedia) Color.White else colors.foreground,
            maxLines = 2,
            modifier = Modifier.semantics { heading() },
        )
        if (album.artistName != null) {
            IglooText(
                text = album.artistName,
                style = IglooTheme.typography.bodyLarge.overMedia(overMedia),
                color = if (overMedia) Color.White.copy(alpha = 0.85f) else colors.mutedForeground,
                maxLines = 1,
            )
        }
        AlbumMetadataRow(album = album, overMedia = overMedia)
        if (album.genresLine != null) {
            IglooText(
                text = album.genresLine,
                style = IglooTheme.typography.label.overMedia(overMedia),
                color = if (overMedia) Color.White.copy(alpha = 0.75f) else colors.mutedForeground,
                maxLines = 1,
            )
        }
        if (album.popularity != null) {
            SpotifyPopularityMeter(score = album.popularity, overMedia = overMedia)
        }
    }
}

/** Decorative — the artwork repeats nothing the text does not say, so TalkBack skips it. */
@Composable
private fun HeaderCover(coverUrl: String?) {
    val colors = IglooTheme.colors
    var imageFailed by remember(coverUrl) { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .width(IglooTheme.layout.posterWidth)
            .aspectRatio(IglooTheme.layout.albumAspect)
            .iglooSurface(radius = IglooTheme.radius.lg, fill = colors.muted),
        contentAlignment = Alignment.Center,
    ) {
        if (coverUrl != null && !imageFailed) {
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onState = { state ->
                    if (state is AsyncImagePainter.State.Error) imageFailed = true
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Image(
                imageVector = IglooIcons.Music,
                contentDescription = null,
                colorFilter = ColorFilter.tint(colors.mutedForeground),
                modifier = Modifier.size(IglooTheme.icons.lg),
            )
        }
    }
}

/**
 * Release date, track count, and total duration — visually a row of chips, but one TalkBack
 * stop: three consecutive chip announcements would be noise, and the hero reading stop already
 * carries the full sentence for a spoken screen reader.
 */
@Composable
private fun AlbumMetadataRow(
    album: AlbumDetailsUi,
    overMedia: Boolean,
) {
    val parts = listOfNotNull(
        album.releaseDateText,
        album.trackCountText,
        album.totalDurationText,
    )
    Row(
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = parts.joinToString(", ")
        },
        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        parts.forEach { part ->
            AlbumDetailChip(text = part, overMedia = overMedia)
        }
    }
}

/**
 * The pill ground is the section 3.2 over-media chip literal — black with a translucent white
 * hairline, deliberately theme-blind because a backdrop is behind it. The fallback is the token
 * pair the badge alphas of section 3.1 prescribe for chrome on a plain canvas.
 */
@Composable
internal fun AlbumDetailChip(
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

/**
 * The web page's Spotify popularity meter: the glyph, the label, the bold score, and a thin
 * fill bar. The brand green is the one deliberate brand color in the app — the meter reports a
 * Spotify-owned number, and painting it in the theme's primary would claim it as ours (section
 * 11.5.1). Not a focus target: the score is folded into the hero reading stop's sentence, so
 * the meter itself is silent.
 */
@Composable
private fun SpotifyPopularityMeter(
    score: Int,
    overMedia: Boolean,
) {
    val colors = IglooTheme.colors
    Column(
        modifier = Modifier
            .widthIn(max = POPULARITY_METER_MAX_WIDTH.scaled())
            .fillMaxWidth()
            .testTag("album_popularity_meter")
            .clearAndSetSemantics { },
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.xs),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                imageVector = IglooIcons.Spotify,
                contentDescription = null,
                colorFilter = ColorFilter.tint(SpotifyGreen),
                modifier = Modifier.size(IglooTheme.icons.md),
            )
            IglooText(
                text = "Spotify popularity",
                style = IglooTheme.typography.label.overMedia(overMedia),
                color = if (overMedia) Color.White.copy(alpha = 0.85f) else colors.mutedForeground,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            IglooText(
                text = "$score",
                style = IglooTheme.typography.label,
                color = SpotifyGreen,
                maxLines = 1,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp.scaled())
                .iglooSurface(
                    radius = IglooTheme.radius.pill,
                    // The resume strip's track literal over media; muted on the fallback.
                    fill = if (overMedia) Color.Black.copy(alpha = 0.40f) else colors.muted,
                    border = Color.Transparent,
                    borderWidth = 0.dp,
                ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(score / 100f)
                    .fillMaxHeight()
                    .background(SpotifyGreen),
            )
        }
    }
}

/**
 * Play Album and Shuffle. Both are host-owned stubs until playback lands (the More-menu item
 * precedent) with honest labels — no state or progress is claimed. Every direction out of the
 * row is pinned: the shell is still composed under this overlay, so an unpinned edge lets a
 * spatial search land on a card the user cannot see. Down is hand-wired to the first track row.
 */
@Composable
private fun AlbumActionRow(
    overMedia: Boolean,
    playRequester: FocusRequester,
    shuffleRequester: FocusRequester,
    upRequester: FocusRequester,
    downRequester: FocusRequester,
    onActionFocused: (FocusRequester) -> Unit,
    onPlayAlbum: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Section 3.2: a Ghost button's transparent ground and token text are licensed only on a
    // token canvas; over the backdrop the button carries the same black ground as the chips.
    val ghostFill = if (overMedia) Color.Black.copy(alpha = 0.45f) else null
    val ghostContent = if (overMedia) Color.White else null
    val rowFocus = Modifier.focusProperties {
        up = upRequester
        down = downRequester
    }
    // Play Album steps back while a sibling holds focus, so the focused control is the
    // strongest thing in the row (the movie action row's recess rule).
    var rowHasFocus by remember { mutableStateOf(false) }
    var playFocused by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.onFocusChanged { rowHasFocus = it.hasFocus },
        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
    ) {
        IglooButton(
            text = "Play Album",
            onClick = onPlayAlbum,
            icon = IglooIcons.Play,
            semanticLabel = "Play Album",
            recessed = rowHasFocus && !playFocused,
            modifier = Modifier
                .testTag("album_play")
                .focusRequester(playRequester)
                .then(rowFocus)
                .focusProperties {
                    left = Cancel
                    right = shuffleRequester
                }
                .onFocusChanged {
                    playFocused = it.isFocused
                    if (it.isFocused) onActionFocused(playRequester)
                },
        )
        IglooButton(
            text = "Shuffle",
            onClick = onShuffle,
            variant = IglooButtonVariant.Ghost,
            icon = IglooIcons.Shuffle,
            restingFill = ghostFill,
            contentColor = ghostContent,
            semanticLabel = "Shuffle album",
            modifier = Modifier
                .testTag("album_shuffle")
                .focusRequester(shuffleRequester)
                .then(rowFocus)
                .focusProperties { right = Cancel }
                .onFocusChanged { if (it.isFocused) onActionFocused(shuffleRequester) },
        )
    }
}

/** The Spotify brand green — the meter's fill and glyph, and nothing else in the app. */
internal val SpotifyGreen = Color(0xFF1DB954)

/** Web parity: the meter hugs the info column but never spans the whole pane (`max-w-md`). */
private val POPULARITY_METER_MAX_WIDTH = 360.dp
