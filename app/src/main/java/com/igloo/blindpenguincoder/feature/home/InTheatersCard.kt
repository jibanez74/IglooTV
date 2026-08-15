package com.igloo.blindpenguincoder.feature.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.overMedia
import com.igloo.blindpenguincoder.core.ui.IglooIcons
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.RatingBadge
import com.igloo.blindpenguincoder.core.ui.focusRing
import com.igloo.blindpenguincoder.core.ui.ratingBadgeSpec

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
 * Focusable but inert, like the albums rail's cards: there is no TMDB detail screen yet, and a
 * card that announces "Open …" and then does nothing is worse than one that announces none.
 */
@Composable
fun InTheatersCard(
    movie: HomeTheaterMovie,
    aspect: Float,
    modifier: Modifier = Modifier,
) {
    val colors = IglooTheme.colors
    var focused by remember { mutableStateOf(false) }
    var imageFailed by remember(movie.posterUrl) { mutableStateOf(false) }
    val overMedia = movie.posterUrl != null && !imageFailed
    val ratingBadge = movie.rating?.let { ratingBadgeSpec(it) }

    Box(
        modifier = modifier
            .width(IglooTheme.layout.posterWidth)
            .aspectRatio(aspect)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clearAndSetSemantics {
                contentDescription = listOfNotNull(
                    movie.title,
                    movie.year,
                    ratingBadge?.let { "rated ${it.label} out of 10" },
                ).joinToString(", ")
            }
            // focusRing owns the clip, so the muted fill doubles as the image backdrop and
            // nothing here may clip over it.
            .focusRing(
                focused = focused,
                radius = IglooTheme.radius.lg,
                fill = colors.muted,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (overMedia) {
            AsyncImage(
                model = movie.posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onState = { state ->
                    if (state is AsyncImagePainter.State.Error) imageFailed = true
                },
                modifier = Modifier.fillMaxSize(),
            )
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
        } else {
            Image(
                imageVector = IglooIcons.Movies,
                contentDescription = null,
                colorFilter = ColorFilter.tint(colors.mutedForeground),
                modifier = Modifier.size(IglooTheme.icons.lg),
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
                    color = if (overMedia) Color.White.copy(alpha = 0.85f) else colors.mutedForeground,
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

