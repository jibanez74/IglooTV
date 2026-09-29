package com.igloo.blindpenguincoder.feature.music

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusRequester.Companion.Cancel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.OVER_MEDIA_CONTROL_FILL
import com.igloo.blindpenguincoder.core.design.OVER_MEDIA_SECONDARY
import com.igloo.blindpenguincoder.core.design.OVER_MEDIA_TERTIARY
import com.igloo.blindpenguincoder.core.design.OVER_MEDIA_TRACK
import com.igloo.blindpenguincoder.core.design.overMedia
import com.igloo.blindpenguincoder.core.design.scaled
import com.igloo.blindpenguincoder.core.ui.IglooIcons
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.ProgressTrack
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
    /** The player's return node when Play Album launched it; null when another control did. */
    playReturnRequester: FocusRequester?,
    shuffleRequester: FocusRequester,
    shuffleReturnRequester: FocusRequester?,
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
        MusicHeroArtwork(imageUrl = album.coverUrl, radius = IglooTheme.radius.lg, fallbackIcon = IglooIcons.Music)

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
                MusicHeroActionRow(
                    primaryText = "Play Album",
                    primarySemanticLabel = "Play Album",
                    primaryTag = "album_play",
                    shuffleSemanticLabel = "Shuffle album",
                    shuffleTag = "album_shuffle",
                    overMedia = overMedia,
                    primaryRequester = primaryRequester,
                    primaryReturnRequester = playReturnRequester,
                    shuffleRequester = shuffleRequester,
                    shuffleReturnRequester = shuffleReturnRequester,
                    upRequester = actionUpRequester,
                    downRequester = downRequester,
                    onActionFocused = onActionFocused,
                    onPrimary = onPlayAlbum,
                    onShuffle = onShuffle,
                    modifier = Modifier.padding(top = IglooTheme.spacing.sm),
                )
            }
        }
    }
}

/**
 * The hero's prose: title, artist, metadata chips, genres, and the popularity meter — one
 * reading stop under a screen reader ([MusicHeroReadingStop]).
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
    MusicHeroReadingStop(
        enabled = readingStop,
        tag = "album_hero_info",
        overMedia = overMedia,
        requester = requester,
        downRequester = downRequester,
        description = album.heroInfoDescription,
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
                color = if (overMedia) OVER_MEDIA_SECONDARY else colors.mutedForeground,
                maxLines = 1,
            )
        }
        MusicMetadataChips(
            parts = listOfNotNull(album.releaseDateText, album.trackCountText, album.totalDurationText),
            overMedia = overMedia,
        )
        if (album.genresLine != null) {
            IglooText(
                text = album.genresLine,
                style = IglooTheme.typography.label.overMedia(overMedia),
                color = if (overMedia) OVER_MEDIA_TERTIARY else colors.mutedForeground,
                maxLines = 1,
            )
        }
        if (album.popularity != null) {
            SpotifyPopularityMeter(score = album.popularity, overMedia = overMedia)
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
                fill = if (overMedia) OVER_MEDIA_CONTROL_FILL else colors.muted,
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
internal fun SpotifyPopularityMeter(
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
                color = if (overMedia) OVER_MEDIA_SECONDARY else colors.mutedForeground,
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
        ProgressTrack(
            fraction = score / 100f,
            ground = if (overMedia) OVER_MEDIA_TRACK else colors.muted,
            fill = SpotifyGreen,
            rounded = true,
        )
    }
}

/** The Spotify brand green — the meter's fill and glyph, and nothing else in the app. */
internal val SpotifyGreen = Color(0xFF1DB954)

/** Web parity: the meter hugs the info column but never spans the whole pane (`max-w-md`). */
private val POPULARITY_METER_MAX_WIDTH = 360.dp
