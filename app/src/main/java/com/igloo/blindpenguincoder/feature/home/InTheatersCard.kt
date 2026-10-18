package com.igloo.blindpenguincoder.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.OVER_MEDIA_SECONDARY
import com.igloo.blindpenguincoder.core.design.overMedia
import com.igloo.blindpenguincoder.core.ui.ArtworkOrGlyph
import com.igloo.blindpenguincoder.core.ui.IglooIcons
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.RatingBadge
import com.igloo.blindpenguincoder.core.ui.cardWidth
import com.igloo.blindpenguincoder.core.ui.focusRing
import com.igloo.blindpenguincoder.core.ui.mediaCardInteraction
import com.igloo.blindpenguincoder.core.ui.mediaSectionDescription
import com.igloo.blindpenguincoder.core.ui.ratingBadgeSpec
import com.igloo.blindpenguincoder.core.ui.rememberArtworkState
import com.igloo.blindpenguincoder.core.ui.spoken

/**
 * The In Theaters rail's card (section 11.3.2): unlike [com.igloo.blindpenguincoder.core.ui.IglooPosterCard],
 * the title and year sit on the poster over a bottom scrim, with a critic-rating badge in the
 * top-right corner — TMDB content the library does not hold, so it looks deliberately different.
 *
 * Section 3.2 licenses those literals only where a poster is actually behind them: the scrim and
 * low-tier badge are black literals, and aurora/auroraForeground are pinned to the same values in
 * both themes (section 3.1), so nothing shifts with the theme. With no poster to load — or one
 * that failed — the card drops the scrim and paints its text in theme tokens instead.
 *
 * Focus, press and announcement are the poster card's (`mediaCardInteraction`), so a null
 * [onClick] renders the same inert landing site.
 */
@Composable
fun InTheatersCard(
    movie: HomeTheaterMovie,
    aspect: Float,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = IglooTheme.colors
    var focused by remember { mutableStateOf(false) }
    val art = rememberArtworkState(movie.posterUrl)
    val overMedia = art.showsArtwork
    val ratingBadge = movie.rating?.let { ratingBadgeSpec(it) }
    val spokenDescription = mediaSectionDescription(
        listOfNotNull(movie.title, movie.year, ratingBadge?.spoken()).joinToString(", "),
    )

    Box(
        modifier = modifier
            .cardWidth(IglooTheme.layout.posterWidth)
            .aspectRatio(aspect)
            .mediaCardInteraction(
                onFocusChanged = { focused = it },
                onClick = onClick,
                description = spokenDescription,
                actionLabel = "Open ${movie.title}",
            )
            // focusRing owns the clip, so the muted fill doubles as the image backdrop and
            // nothing here may clip over it.
            .focusRing(
                focused = focused,
                radius = IglooTheme.radius.lg,
                fill = colors.muted,
            ),
        contentAlignment = Alignment.Center,
    ) {
        ArtworkOrGlyph(imageUrl = movie.posterUrl, fallbackIcon = IglooIcons.Movies, state = art)
        if (overMedia) {
            // Scrim under the text, lower third of the poster.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.38f)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.5f to Color.Black.copy(alpha = 0.50f),
                            1f to Color.Black.copy(alpha = 0.90f),
                        ),
                    ),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(IglooTheme.spacing.sm),
        ) {
            IglooText(
                text = movie.title,
                style = IglooTheme.typography.bodyMedium.overMedia(overMedia),
                color = if (overMedia) Color.White else colors.cardForeground,
                maxLines = 2,
            )
            if (movie.year != null) {
                IglooText(
                    text = movie.year,
                    style = IglooTheme.typography.label.overMedia(overMedia),
                    color = if (overMedia) OVER_MEDIA_SECONDARY else colors.mutedForeground,
                    maxLines = 1,
                )
            }
        }

        if (ratingBadge != null) {
            RatingBadge(
                spec = ratingBadge,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(IglooTheme.spacing.sm),
            )
        }
    }
}
