package com.igloo.blindpenguincoder.core.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.igloo.blindpenguincoder.core.design.IglooMotion
import com.igloo.blindpenguincoder.core.design.iglooTween
import com.igloo.blindpenguincoder.core.design.scaled

private val RISE = 12.dp

/**
 * The section-enter recipe from docs/design-system.md section 7.2: fade and rise, staggered by
 * [index], starting once [entered] flips. Draw phase only — `Modifier.offset` would relayout and
 * drag the focus rect with it, and `AnimatedVisibility` would add a node, both of which that
 * section forbids. `iglooTween` collapses the whole stagger to one frame under reduced motion.
 *
 * Give the element that already holds focus index 0, so its ring never spends the stagger
 * invisible.
 */
@Composable
fun Modifier.iglooEnterStagger(entered: Boolean, index: Int): Modifier {
    val progress by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = iglooTween(
            durationMillis = IglooMotion.PAGE_MS,
            delayMillis = index * IglooMotion.STAGGER_MS,
        ),
        label = "iglooEnter$index",
    )
    val rise = with(LocalDensity.current) { RISE.scaled().toPx() }
    return graphicsLayer {
        alpha = progress
        translationY = (1f - progress) * rise
    }
}
