package com.igloo.blindpenguincoder.feature.boot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooBrandMark
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.iglooAuroraBackdrop
import com.igloo.blindpenguincoder.core.ui.iglooEnterStagger

internal const val SPLASH_WORDMARK = "Igloo"
internal const val SPLASH_WORDMARK_SUFFIX = "TV"

/** One node, so TalkBack does not read the wordmark as two fragments before the app has a screen. */
internal const val SPLASH_LABEL = "Igloo TV. Starting."

/**
 * What the system splash draws: 120dp of the 960dp reference viewport, dead centre. Matching it
 * is the whole point — the tile must not resize or move when Compose takes over.
 *
 * Deliberately **not** `.scaled()`, and the one dimension in the app that is exempt from the
 * section 2 scale model: the system layer is a static drawable that knows nothing about `UiScale`,
 * so scaling this one would put a 105dp or 138dp tile against a fixed system tile and re-open the
 * seam this screen exists to close.
 */
private val MARK_SIZE = 120.dp

/**
 * The launch screen, drawn while `SessionManager.restore()` reads storage and held for
 * `IglooMotion.SPLASH_HOLD_MS` so the brand moment cannot flash. See docs/design-system.md
 * section 11.1.-1.
 */
@Composable
fun SplashScreen(modifier: Modifier = Modifier, announce: Boolean = true) {
    val colors = IglooTheme.colors

    // Still, not ambient: the drift covers a 24s cycle and this screen lives for under one, so an
    // infinite transition would request frames for movement nobody can see.
    val still = remember { mutableFloatStateOf(0f) }

    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    val markSize = MARK_SIZE

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .iglooAuroraBackdrop(still)
            // Silent while handing off: the screen underneath has already taken focus, and the
            // splash must not talk over it for the length of the fade.
            .clearAndSetSemantics { if (announce) contentDescription = SPLASH_LABEL },
    ) {
        // Centred on the screen, not on the lockup, and with no entrance: the system splash
        // already has this mark at exactly this size and place. Letting the wordmark push it
        // upward, or fading it in, would re-open the seam the system layer exists to close.
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            IglooBrandMark(size = markSize, textStyle = IglooTheme.typography.display)
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = maxHeight / 2 + markSize / 2 + IglooTheme.spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IglooText(
                text = SPLASH_WORDMARK,
                style = IglooTheme.typography.display,
                color = colors.foreground,
                modifier = Modifier.iglooEnterStagger(entered = entered, index = 0),
            )
            IglooText(
                text = SPLASH_WORDMARK_SUFFIX,
                style = IglooTheme.typography.titleMedium,
                color = colors.mutedForeground,
                modifier = Modifier.iglooEnterStagger(entered = entered, index = 1),
            )
        }
    }
}
