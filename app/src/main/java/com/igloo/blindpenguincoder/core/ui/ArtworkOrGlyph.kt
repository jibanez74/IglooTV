package com.igloo.blindpenguincoder.core.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.igloo.blindpenguincoder.core.design.IglooTheme

/**
 * Whether the artwork at [imageUrl] is on screen: it is until the decode fails, and [loaded] says
 * the decode has landed. Hoisted through [rememberArtworkState] by a surface whose own treatment
 * depends on it — text drawn over the art needs the over-media colours only while there is art
 * under it (section 3.2), and a URL alone would paint them over the bare fill while it loads.
 */
@Stable
internal class ArtworkState(val imageUrl: String?) {
    var loaded by mutableStateOf(false)
    var failed by mutableStateOf(false)
    val showsArtwork: Boolean get() = imageUrl != null && !failed
}

@Composable
internal fun rememberArtworkState(imageUrl: String?): ArtworkState =
    remember(imageUrl) { ArtworkState(imageUrl) }

/**
 * Artwork cropped to fill its box, or [fallbackIcon] at `icons.lg` when there is none or it fails
 * to load; the caller's box centres the glyph and paints the ground both sit on. Decorative: the
 * text beside it says everything, so neither reaches TalkBack.
 */
@Composable
internal fun ArtworkOrGlyph(
    imageUrl: String?,
    fallbackIcon: ImageVector,
    fallbackTint: Color = IglooTheme.colors.mutedForeground,
    state: ArtworkState = rememberArtworkState(imageUrl),
) {
    if (state.showsArtwork) {
        ArtworkImage(state, Modifier.fillMaxSize())
    } else {
        IglooGlyph(icon = fallbackIcon, tint = fallbackTint, size = IglooTheme.icons.lg)
    }
}

/** [state]'s artwork cropped to fill its box, reporting the decode back into [state]. Decorative. */
@Composable
internal fun ArtworkImage(state: ArtworkState, modifier: Modifier = Modifier) {
    AsyncImage(
        model = state.imageUrl,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        onState = {
            when (it) {
                is AsyncImagePainter.State.Success -> state.loaded = true
                is AsyncImagePainter.State.Error -> state.failed = true
                else -> Unit
            }
        },
        modifier = modifier,
    )
}
