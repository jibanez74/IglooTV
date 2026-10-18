package com.igloo.blindpenguincoder.feature.music

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooIcons
import com.igloo.blindpenguincoder.core.ui.IglooPosterCard

/**
 * The one way an [AlbumCardUi] becomes a card: title over its subtitle, square cover or the
 * music glyph. Home's albums rail, the Albums tab and a musician's discography share it.
 * [width] is [Dp.Unspecified] in a grid cell, which is already sized (see `IglooPosterCard`).
 */
@Composable
fun AlbumCard(
    album: AlbumCardUi,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    aspect: Float = IglooTheme.layout.albumAspect,
    width: Dp = IglooTheme.layout.posterWidth,
) {
    IglooPosterCard(
        title = album.title,
        subtitle = album.subtitle,
        imageUrl = album.coverUrl,
        onClick = onClick,
        aspect = aspect,
        width = width,
        fallbackIcon = IglooIcons.Music,
        modifier = modifier,
    )
}
