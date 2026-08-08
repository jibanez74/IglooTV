package com.igloo.blindpenguincoder.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.ambientOffset

private data class BackdropAlphas(val primaryCore: Float, val auroraCore: Float)

/**
 * Documented in docs/design-system.md section 3.1. The same alpha does opposite things per theme:
 * dark `primary` lifts the canvas toward the text color, light `primary` is a navy that drops it.
 */
@Composable
private fun backdropAlphas(): BackdropAlphas =
    if (IglooTheme.colors.background.luminance() < 0.5f) {
        BackdropAlphas(primaryCore = 0.26f, auroraCore = 0.18f)
    } else {
        BackdropAlphas(primaryCore = 0.16f, auroraCore = 0.10f)
    }

/**
 * The full-bleed auth canvas: two drifting radial gradients over an opaque fill. [progress] is
 * read inside the draw lambda and the brushes are cached, so a moving backdrop invalidates the
 * draw phase only and allocates no shader per frame. Outer stops fade the color's own alpha —
 * `Color.Transparent` is transparent *black* and would leave a grey halo, most visible in light
 * theme.
 *
 * Pass `rememberAmbientProgress()` for the section 7.2 ambient loop, or a constant `0f` state for
 * a still backdrop on a screen too short-lived for drift to read.
 */
@Composable
fun Modifier.iglooAuroraBackdrop(progress: State<Float>): Modifier {
    val colors = IglooTheme.colors
    val alphas = backdropAlphas()
    return drawWithCache {
        val glacier = Brush.radialGradient(
            colorStops = arrayOf(
                0f to colors.primary.copy(alpha = alphas.primaryCore),
                0.55f to colors.primary.copy(alpha = alphas.primaryCore * 0.38f),
                1f to colors.primary.copy(alpha = 0f),
            ),
            center = Offset(size.width * 0.22f, size.height * 0.18f),
            radius = size.maxDimension * 0.95f,
        )
        val aurora = Brush.radialGradient(
            colorStops = arrayOf(
                0f to colors.aurora.copy(alpha = alphas.auroraCore),
                0.6f to colors.aurora.copy(alpha = alphas.auroraCore * 0.35f),
                1f to colors.aurora.copy(alpha = 0f),
            ),
            center = Offset(size.width * 0.86f, size.height * 0.88f),
            radius = size.maxDimension * 0.60f,
        )
        val driftX = size.width * 0.028f
        val driftY = size.height * 0.034f

        onDrawBehind {
            val t = progress.value
            drawRect(colors.background)
            translate(
                ambientOffset(t, 0f, driftX),
                ambientOffset(t, 0.25f, driftY),
            ) {
                drawRect(glacier)
            }
            translate(
                ambientOffset(t, 0.5f, -driftX * 0.7f),
                ambientOffset(t, 0.75f, driftY * 0.8f),
            ) {
                drawRect(aurora)
            }
        }
    }
}
