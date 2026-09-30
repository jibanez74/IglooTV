package com.igloo.blindpenguincoder.core.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
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
 * Artwork cropped to fill its box, or [fallbackIcon] at `icons.lg` when there is none or it fails
 * to load; the caller's box centres the glyph and paints the ground both sit on. Decorative: the
 * text beside it says everything, so neither reaches TalkBack.
 */
@Composable
internal fun ArtworkOrGlyph(
    imageUrl: String?,
    fallbackIcon: ImageVector,
    fallbackTint: Color = IglooTheme.colors.mutedForeground,
) {
    var imageFailed by remember(imageUrl) { mutableStateOf(false) }
    if (imageUrl != null && !imageFailed) {
        AsyncImage(
            model = imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onState = { state -> if (state is AsyncImagePainter.State.Error) imageFailed = true },
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
