package com.igloo.blindpenguincoder.core.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.transformations
import coil3.size.Scale
import coil3.size.Size
import coil3.transform.Transformation
import com.igloo.blindpenguincoder.core.design.IglooMotion
import com.igloo.blindpenguincoder.core.design.iglooTween

/**
 * A hero's picture: the title's backdrop, or — when it has none, or it fails to load — its
 * poster as a soft wash filling the same box, so the band still reads as that title's media
 * (docs/design-system.md section 3.2). With neither, nothing is drawn and the caller's
 * missing-media tile shows. [backdrop] and [poster] report their decodes as [ArtworkImage] does.
 */
@Composable
internal fun BackdropOrBlurredPoster(
    backdrop: ArtworkState,
    poster: ArtworkState,
    modifier: Modifier = Modifier,
) {
    when {
        backdrop.showsArtwork -> ArtworkImage(backdrop, modifier)
        poster.showsArtwork -> BlurredArtwork(poster, modifier)
    }
}

/**
 * The alpha a hero's [BackdropOrBlurredPoster] fades in on: 0 until [backdrop], or [poster]
 * standing in for it, has decoded, then 1 over a page's motion.
 */
@Composable
internal fun animatePictureAlpha(backdrop: ArtworkState, poster: ArtworkState, label: String): State<Float> {
    val loaded = if (backdrop.showsArtwork) backdrop.loaded else poster.showsArtwork && poster.loaded
    return animateFloatAsState(
        targetValue = if (loaded) 1f else 0f,
        animationSpec = iglooTween(IglooMotion.PAGE_MS),
        label = label,
    )
}

/**
 * [state]'s image blurred past recognising and cropped to fill its box. The web does this with a
 * CSS blur; here `Modifier.blur` needs API 31 and the Shield and the Fire TV stick are API 30. So
 * the image is decoded a few dozen pixels wide, box-blurred on that handful of pixels, and
 * stretched to the box: the stretch is most of the softness, and the blur takes the blocks out
 * of it. Decorative.
 */
@Composable
private fun BlurredArtwork(state: ArtworkState, modifier: Modifier = Modifier) {
    val context = LocalPlatformContext.current
    val request = remember(context, state.imageUrl) {
        ImageRequest.Builder(context)
            .data(state.imageUrl)
            .size(BLUR_DECODE_PX)
            // Fit, or the image's crop scale would decode a poster's longer side past the size.
            .scale(Scale.FIT)
            // The blur reads and writes pixels, which a hardware bitmap does not allow.
            .allowHardware(false)
            .transformations(BoxBlurTransformation)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        onState = state::onPainterState,
        modifier = modifier,
    )
}

private object BoxBlurTransformation : Transformation() {
    override val cacheKey = "igloo-box-blur-$BLUR_RADIUS_PX-x$BLUR_PASSES"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val width = input.width
        val height = input.height
        val pixels = IntArray(width * height)
        input.getPixels(pixels, 0, width, 0, 0, width, height)
        boxBlur(pixels, width, height, BLUR_RADIUS_PX, BLUR_PASSES)
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}

/**
 * Blurs [pixels], an ARGB image [width] by [height], in place: [passes] rounds of a box average
 * [radius] pixels to each side, across then down. Three passes are close to a Gaussian. Edges
 * repeat their outermost pixel, so the picture does not darken toward its border.
 */
internal fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int, passes: Int) {
    if (radius <= 0 || width <= 0 || height <= 0) return
    val line = IntArray(maxOf(width, height))
    repeat(passes) {
        for (y in 0 until height) blurLine(pixels, line, start = y * width, step = 1, length = width, radius)
        for (x in 0 until width) blurLine(pixels, line, start = x, step = width, length = height, radius)
    }
}

/** One row or column of [boxBlur]: a sliding window over [length] pixels [step] apart. */
private fun blurLine(pixels: IntArray, line: IntArray, start: Int, step: Int, length: Int, radius: Int) {
    for (i in 0 until length) line[i] = pixels[start + i * step]
    val window = radius * 2 + 1
    var alpha = 0
    var red = 0
    var green = 0
    var blue = 0
    for (offset in -radius..radius) {
        val pixel = line[offset.coerceIn(0, length - 1)]
        alpha += pixel ushr 24
        red += (pixel shr 16) and 0xFF
        green += (pixel shr 8) and 0xFF
        blue += pixel and 0xFF
    }
    for (i in 0 until length) {
        pixels[start + i * step] =
            ((alpha / window) shl 24) or ((red / window) shl 16) or ((green / window) shl 8) or (blue / window)
        val leaving = line[(i - radius).coerceIn(0, length - 1)]
        val entering = line[(i + radius + 1).coerceIn(0, length - 1)]
        alpha += (entering ushr 24) - (leaving ushr 24)
        red += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
        green += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
        blue += (entering and 0xFF) - (leaving and 0xFF)
    }
}

/** The stand-in's longer side as decoded: detail is the one thing a wash must not have. */
private const val BLUR_DECODE_PX = 96

private const val BLUR_RADIUS_PX = 3
private const val BLUR_PASSES = 3
