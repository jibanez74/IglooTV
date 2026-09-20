package com.igloo.blindpenguincoder.core.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.scaled
import java.util.Locale
import kotlin.math.round

/** The critic-score tiers of section 3.2, strongest first. */
enum class RatingTier { Strong, Fair, Weak }

/** What the badge paints: its number and the tier that colors it. */
data class RatingBadgeSpec(val label: String, val tier: RatingTier)

/**
 * Scores carry more decimals than the badge shows one of, so the tier is read off the rounded
 * value: taking it off the raw score would paint 6.951 in the middle tier under a "7.0" label.
 * Both come from here so they cannot disagree.
 */
fun ratingBadgeSpec(rating: Double): RatingBadgeSpec {
    val rounded = round(rating * 10) / 10
    return RatingBadgeSpec(
        label = String.format(Locale.US, "%.1f", rounded),
        tier = when {
            rounded >= 7.0 -> RatingTier.Strong
            rounded >= 5.0 -> RatingTier.Fair
            else -> RatingTier.Weak
        },
    )
}

/**
 * Critic score tiers on the warm aurora accent (section 3.2), one badge per surface.
 *
 * [radius] defaults to the corner the poster-card badge has always used; a badge sitting in a
 * row of chips passes `pill` so the row reads as one set rather than two, and [verticalPadding]
 * matched to the chips' own so the set shares one height.
 */
@Composable
fun RatingBadge(
    spec: RatingBadgeSpec,
    modifier: Modifier = Modifier,
    radius: Dp = IglooTheme.radius.sm,
    verticalPadding: Dp = 2.dp.scaled(),
) {
    val colors = IglooTheme.colors
    val (background, foreground) = when (spec.tier) {
        RatingTier.Strong -> colors.aurora to colors.auroraForeground
        RatingTier.Fair -> colors.aurora.copy(alpha = 0.80f) to colors.auroraForeground
        // The web's muted tier tracks the theme; over media that is not allowed (section 3.2),
        // so the low tier is the equivalent literal instead. The badge paints its own ground,
        // so it reads the same on the no-poster fallback fill.
        RatingTier.Weak -> Color.Black.copy(alpha = 0.60f) to Color.White
    }
    Row(
        modifier = modifier
            .background(background, RoundedCornerShape(radius))
            .padding(horizontal = IglooTheme.spacing.xs, vertical = verticalPadding),
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
            text = spec.label,
            style = IglooTheme.typography.label,
            color = foreground,
            maxLines = 1,
        )
    }
}
