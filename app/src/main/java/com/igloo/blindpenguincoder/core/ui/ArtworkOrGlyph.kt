package com.igloo.blindpenguincoder.core.ui

import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.igloo.blindpenguincoder.core.design.IglooTheme

/**
 * Whether the artwork at [imageUrl] is on screen: it is until the decode fails. Hoisted through
 * [rememberArtworkState] by a card whose own treatment depends on it — text drawn over the art
 * needs the over-media colours only while there is art under it (section 3.2).
 */
@Stable
internal class ArtworkState(val imageUrl: String?) {
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
        AsyncImage(
            model = state.imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onState = { if (it is AsyncImagePainter.State.Error) state.failed = true },
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Image(
            imageVector = fallbackIcon,
            contentDescription = null,
            colorFilter = ColorFilter.tint(fallbackTint),
            modifier = Modifier.size(IglooTheme.icons.lg),
        )
    }
}
