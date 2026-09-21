package com.igloo.blindpenguincoder.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import com.igloo.blindpenguincoder.core.design.IglooTheme

/**
 * The dim behind an overlay: the rail over the content pane (section 8.1) and the confirmation
 * dialog (section 9.3). **Only one renders at a time** — two of these composite to 0.84, an alpha
 * section 3.1 does not authorize, and dark enough that the control the user just left stops being
 * legible.
 *
 * Paint only: no clickable, focusable, or semantics modifiers, so it can never intercept the
 * d-pad and TalkBack does not know it exists. [alpha] is animatable, and a scrim at 0f draws
 * nothing at all.
 */
@Composable
fun IglooScrim(
    modifier: Modifier = Modifier,
    alpha: Float = SCRIM_ALPHA,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable () -> Unit = {},
) {
    val scrim = IglooTheme.colors.background
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                if (alpha > 0f) drawRect(scrim.copy(alpha = alpha))
            },
        contentAlignment = contentAlignment,
    ) {
        content()
    }
}

/** The 0.60 step from design-system.md section 3.1. */
const val SCRIM_ALPHA = 0.60f
