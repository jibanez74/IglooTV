package com.igloo.blindpenguincoder.feature.shared

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooIcons
import com.igloo.blindpenguincoder.core.ui.IglooPosterCard
import com.igloo.blindpenguincoder.core.ui.PosterCardProgress

/**
 * The one way a [PosterItem] becomes a card: title over the year, poster or [fallbackIcon]
 * (the TV glyph for a show). Home's rails and the library grids share it, so a listing cannot
 * drift from the others in what it shows or announces. [width] is [Dp.Unspecified] in a grid
 * cell, which is already sized (see `IglooPosterCard`).
 */
@Composable
fun PosterItemCard(
    item: PosterItem,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    aspect: Float = IglooTheme.layout.posterAspect,
    width: Dp = IglooTheme.layout.posterWidth,
    fallbackIcon: ImageVector = IglooIcons.Movies,
    progress: PosterCardProgress? = null,
) {
    IglooPosterCard(
        title = item.title,
        subtitle = item.year?.toString(),
        imageUrl = item.posterUrl,
        onClick = onClick,
        progress = progress,
        aspect = aspect,
        width = width,
        fallbackIcon = fallbackIcon,
        modifier = modifier,
    )
}
