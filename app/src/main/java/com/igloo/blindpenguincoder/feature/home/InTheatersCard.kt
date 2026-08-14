package com.igloo.blindpenguincoder.feature.home

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.scaled
import com.igloo.blindpenguincoder.core.ui.IglooIcons
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.focusRing
import java.util.Locale

/**
 * The In Theaters rail's card (section 11.3.2): unlike [com.igloo.blindpenguincoder.core.ui.IglooPosterCard],
 * the title and year sit on the poster over a bottom scrim, with a critic-rating badge in the
 * top-right corner — TMDB content the library does not hold, so it looks deliberately different.
 *
 * Every color painted over the poster is a literal or a theme-invariant token (section 3.2):
 * the scrim and low-tier badge are black literals, and aurora/auroraForeground are pinned to
 * the same values in both themes (section 3.1), so nothing here shifts with the theme.
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
    val ratingLabel = movie.rating?.let { String.format(Locale.US, "%.1f", it) }

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
                    ratingLabel?.let { "rated $it out of 10" },
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
        if (movie.posterUrl != null && !imageFailed) {
            AsyncImage(
                model = movie.posterUrl,
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

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(IglooTheme.spacing.sm),
        ) {
            IglooText(
                text = movie.title,
                style = IglooTheme.typography.bodyMedium.overMedia(),
                color = Color.White,
                maxLines = 2,
            )
            if (movie.year != null) {
                IglooText(
                    text = movie.year,
                    style = IglooTheme.typography.label.overMedia(),
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                )
            }
        }

        if (movie.rating != null && ratingLabel != null) {
            RatingBadge(
                rating = movie.rating,
                label = ratingLabel,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(IglooTheme.spacing.sm),
            )
        }
    }
}

/** Critic score tiers on the warm aurora accent (section 3.2), one badge per card. */
@Composable
private fun RatingBadge(
    rating: Double,
    label: String,
    modifier: Modifier = Modifier,
) {
    val colors = IglooTheme.colors
    val (background, foreground) = when {
        rating >= 7.0 -> colors.aurora to colors.auroraForeground
        rating >= 5.0 -> colors.aurora.copy(alpha = 0.80f) to colors.auroraForeground
        // The web's muted tier tracks the theme; over media that is not allowed (section 3.2),
        // so the low tier is the equivalent literal instead.
        else -> Color.Black.copy(alpha = 0.60f) to Color.White
    }
    Row(
        modifier = modifier
            .background(background, RoundedCornerShape(IglooTheme.radius.sm))
            .padding(horizontal = IglooTheme.spacing.xs, vertical = 2.dp.scaled()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp.scaled()),
    ) {
        Image(
            imageVector = IglooIcons.Star,
            contentDescription = null,
            colorFilter = ColorFilter.tint(foreground),
            modifier = Modifier.size(10.dp.scaled()),
        )
        IglooText(
            text = label,
            style = IglooTheme.typography.label,
            color = foreground,
            maxLines = 1,
        )
    }
}

/** Section 3.2's "text over media carries a shadow" — same treatment as the hero's. */
private fun TextStyle.overMedia(): TextStyle = copy(
    shadow = Shadow(
        color = Color.Black.copy(alpha = 0.60f),
        offset = Offset(0f, 2f),
        blurRadius = 8f,
    ),
)
