package com.igloo.blindpenguincoder.feature.music

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooIcons
import com.igloo.blindpenguincoder.core.ui.IglooPosterCard

/**
 * The one way a [MusicianCardUi] becomes a card (docs/design-system.md section 11.5): the
 * circular thumbnail with the name and counts centred beneath it, speaking the card's one
 * sentence. The Music pane's grid and a search result share it, so the two cannot drift.
 */
@Composable
fun MusicianCard(
    musician: MusicianCardUi,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    width: Dp = IglooTheme.layout.posterWidth,
) {
    IglooPosterCard(
        title = musician.name,
        subtitle = musician.countsLine,
        semanticLabel = musician.spoken,
        imageUrl = musician.thumbUrl,
        onClick = onClick,
        aspect = IglooTheme.layout.albumAspect,
        width = width,
        fallbackIcon = IglooIcons.Person,
        artworkRadius = IglooTheme.radius.pill,
        centerText = true,
        modifier = modifier,
    )
}
