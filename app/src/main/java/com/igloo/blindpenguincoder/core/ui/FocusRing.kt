package com.igloo.blindpenguincoder.core.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp
import com.igloo.blindpenguincoder.core.design.IglooMotion
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.iglooTween

/**
 * The one focus treatment (docs/design-system.md section 6.1) as a single node: glow, scale,
 * fill, and ring.
 *
 * It owns the fill because it owns the clip, and it owns the clip because the three properties
 * cannot be ordered independently: a shadow drawn inside a clip is discarded (the platform
 * projects a child's shadow into the parent render node, which is the clip), and a border drawn
 * outside one is painted over by the fill. So callers pass their fill in and do not clip.
 *
 * Focus is legible on any fill — including glacier-on-glacier, where `ring` and `primary` are the
 * same value — because the focused fill contracts to leave a hairline gap, and the gap shows the
 * real surface behind the control rather than a guessed colour. Ring against that gap is 8.68:1.
 * The glow does not carry the signal; see section 6.1 for why it cannot.
 */
@Composable
fun Modifier.focusRing(
    focused: Boolean,
    radius: Dp,
    fill: Color = Color.Transparent,
    hasError: Boolean = false,
    scaleOnFocus: Boolean = true,
): Modifier {
    val colors = IglooTheme.colors
    val focus = IglooTheme.focus

    // Two drivers, split exactly as section 7's table assigns them: colour lands 50ms ahead of
    // the lift. The properties that must stay locked to each other — scale and glow, both
    // geometric — share one driver so they cannot drift apart.
    val transform = animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = iglooTween(IglooMotion.STANDARD_MS),
        label = "focusTransform",
    )
    val tint = animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = iglooTween(IglooMotion.MICRO_MS),
        label = "focusTint",
    )

    val peakScale = if (scaleOnFocus) focus.scale else 1f
    val restColor = if (hasError) colors.destructive else colors.border
    // The layer's shape is the shadow's outline; without it the glow would square off the
    // corners of a rounded control. Hoisted so the draw-phase block allocates nothing.
    val shape = remember(radius) { RoundedCornerShape(radius) }

    return this
        .graphicsLayer {
            // Read inside the block: graphicsLayer records the snapshot reads and invalidates
            // draw only, so an animating focus costs no recomposition. Reading with `by` at the
            // modifier level would recompose every focusable's subtree at 60fps.
            val t = transform.value
            scaleX = 1f + (peakScale - 1f) * t
            scaleY = scaleX
            // Shadow colours go in at full alpha on purpose — the platform multiplies them by
            // the theme's spot (0.19) and ambient (0.039) shadow alphas before rasterising, so
            // pre-multiplying an alpha of our own here lands the glow at 1.06:1 against the
            // canvas, i.e. nothing. Ambient must be set too; left at its black default it
            // darkens the canvas and cancels the spot's blue.
            shadowElevation = focus.glowElevation.toPx() * t
            ambientShadowColor = colors.ring
            spotShadowColor = colors.ring
            this.shape = shape
            // Keep this outer layer unclipped so the scale and glow can extend past the bounds;
            // descendant masking happens concentrically in the draw phase below.
        }
        .drawWithCache {
            val ringWidth = focus.ringWidth.toPx()
            val restWidth = focus.restWidth.toPx()
            // A rounded rect whose corners exceed half its shortest side is not a valid outline;
            // clamping here is what lets radius.pill (999dp) resolve to the circle it stands for.
            val outerRadius = minOf(radius.toPx(), size.minDimension / 2f)
            val gap = ringWidth + restWidth
            val contentClip = Path()

            onDrawWithContent content@{
                val t = tint.value
                // At rest the fill reaches the edge exactly as it always has; on focus it
                // contracts to expose the surface behind the control.
                val contentInset = gap * t
                drawInsetRoundRect(fill, outerRadius, contentInset)

                if (contentInset > 0f) {
                    val contentRadius = (outerRadius - contentInset).coerceAtLeast(0f)
                    contentClip.reset()
                    contentClip.addRoundRect(
                        RoundRect(
                            left = contentInset,
                            top = contentInset,
                            right = size.width - contentInset,
                            bottom = size.height - contentInset,
                            cornerRadius = CornerRadius(contentRadius, contentRadius),
                        ),
                    )
                    clipPath(contentClip) { this@content.drawContent() }
                } else {
                    drawContent()
                }

                // Strokes are last so opaque edge-to-edge content cannot cover the focus ring or
                // the resting border. The contracted content leaves the real separator visible.
                if (t > 0f) {
                    drawInsetRoundRect(
                        color = colors.ring,
                        outerRadius = outerRadius,
                        inset = ringWidth / 2f,
                        stroke = Stroke(ringWidth),
                        alpha = t,
                    )
                }
                if (t < 1f) {
                    drawInsetRoundRect(
                        color = restColor,
                        outerRadius = outerRadius,
                        inset = restWidth / 2f,
                        stroke = Stroke(restWidth),
                        alpha = 1f - t,
                    )
                }
            }
        }
}

/** Draws [color] as a rounded rect inset from the bounds, keeping the corner concentric. */
private fun DrawScope.drawInsetRoundRect(
    color: Color,
    outerRadius: Float,
    inset: Float,
    stroke: Stroke? = null,
    alpha: Float = 1f,
) {
    if (color == Color.Transparent || alpha <= 0f) return
    val radius = (outerRadius - inset).coerceAtLeast(0f)
    drawRoundRect(
        color = color,
        topLeft = Offset(inset, inset),
        size = Size(size.width - inset * 2f, size.height - inset * 2f),
        cornerRadius = CornerRadius(radius, radius),
        alpha = alpha,
        style = stroke ?: Fill,
    )
}
