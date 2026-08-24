package com.igloo.blindpenguincoder.feature.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.overMedia
import com.igloo.blindpenguincoder.core.design.scaled
import com.igloo.blindpenguincoder.core.ui.IglooInlineError
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.focusRing
import com.igloo.blindpenguincoder.core.ui.formatSpokenTime
import com.igloo.blindpenguincoder.core.ui.formatTimecode
import com.igloo.blindpenguincoder.core.ui.pinnedToScreen
import com.igloo.blindpenguincoder.core.ui.progressFraction

/**
 * The pieces every full-screen player shares (section 11.8): the over-media transport button,
 * the presentation-only seek strip, the error surface, and the global key map. The trailer and
 * movie players each own their chrome layout; what they must not do is drift apart on these.
 */

/**
 * An icon-only transport control on the over-media black ground (section 3.2); one cleared
 * TalkBack node whose label is also its action.
 */
@Composable
internal fun TransportButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(IglooTheme.sizes.controlHeight)
            .focusRing(
                focused = focused,
                radius = IglooTheme.radius.pill,
                fill = OVER_MEDIA_CONTROL_FILL,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                onClick(label = label) {
                    onClick()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            imageVector = icon,
            contentDescription = null,
            colorFilter = ColorFilter.tint(Color.White),
            modifier = Modifier.size(IglooTheme.icons.md),
        )
    }
}

/**
 * The progress strip and timecodes: presentation plus one cleared, non-focusable summary node.
 * Seeking is done with Left/Right on the transport, so the bar itself carries no action, and it
 * has no live region — a timer narrating every tick is section 12 noise.
 */
@Composable
internal fun PlayerSeekBar(
    currentTimeSec: Double,
    durationSec: Double,
    seekTrackTag: String,
) {
    val colors = IglooTheme.colors
    val fraction = progressFraction(currentTimeSec, durationSec)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription =
                    "${formatSpokenTime(currentTimeSec)} of ${formatSpokenTime(durationSec)}"
            },
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
    ) {
        // The resume strip's recipe: 4dp track, over-media literal ground, primary fill.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp.scaled())
                .background(Color.Black.copy(alpha = 0.40f))
                .testTag(seekTrackTag),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(colors.primary),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IglooText(
                text = formatTimecode(currentTimeSec),
                style = IglooTheme.typography.label.overMedia(true),
                color = Color.White,
            )
            IglooText(
                text = formatTimecode(durationSec),
                style = IglooTheme.typography.label.overMedia(true),
                color = OVER_MEDIA_TERTIARY,
            )
        }
    }
}

/** The details screen's error recipe: one pinned action, Assertive, Back handled by the host. */
@Composable
internal fun PlayerErrorSurface(
    message: String,
    actionText: String,
    actionSemanticLabel: String,
    actionRequester: FocusRequester,
    onAction: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(IglooTheme.layout.safeAreaHorizontal),
        contentAlignment = Alignment.Center,
    ) {
        IglooInlineError(
            message = message,
            actionText = actionText,
            actionSemanticLabel = actionSemanticLabel,
            onAction = onAction,
            actionModifier = Modifier
                .focusRequester(actionRequester)
                .pinnedToScreen(),
            modifier = Modifier.width(IglooTheme.layout.dialogWidth),
        )
    }
}

/**
 * The global key map (section 11.8). While the chrome is hidden every handled key is swallowed
 * — the invisible focused control must not activate — and reveals the chrome; media transport
 * keys act regardless of chrome state. Everything else falls through to the focused control.
 */
internal fun handlePlayerKey(
    event: KeyEvent,
    chromeVisible: Boolean,
    controlsDisabled: Boolean,
    showChrome: () -> Unit,
    play: () -> Unit,
    pause: () -> Unit,
    togglePlayPause: () -> Unit,
    seekBy: (Double) -> Unit,
    focusPlayPause: () -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (controlsDisabled) {
        // Swallowed without acting: a transport key that fell through here would reach the
        // active MediaSession and drive playback underneath the modal or error surface.
        // D-pad and Enter still fall through to the modal's focused control.
        return when (event.key) {
            Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause,
            Key.MediaRewind, Key.MediaSkipBackward,
            Key.MediaFastForward, Key.MediaSkipForward,
            -> true

            else -> false
        }
    }

    when (event.key) {
        Key.MediaPlayPause -> {
            togglePlayPause()
            showChrome()
            return true
        }

        Key.MediaPlay -> {
            play()
            showChrome()
            return true
        }

        Key.MediaPause -> {
            pause()
            showChrome()
            return true
        }

        Key.MediaRewind, Key.MediaSkipBackward -> {
            seekBy(-SEEK_STEP_SEC)
            showChrome()
            return true
        }

        Key.MediaFastForward, Key.MediaSkipForward -> {
            seekBy(SEEK_STEP_SEC)
            showChrome()
            return true
        }

        else -> Unit
    }

    if (!chromeVisible) {
        return when (event.key) {
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                togglePlayPause()
                showChrome()
                true
            }

            Key.DirectionLeft -> {
                seekBy(-SEEK_STEP_SEC)
                showChrome()
                true
            }

            Key.DirectionRight -> {
                seekBy(SEEK_STEP_SEC)
                showChrome()
                true
            }

            Key.DirectionUp, Key.DirectionDown -> {
                showChrome()
                focusPlayPause()
                true
            }

            else -> false
        }
    }

    // Chrome visible: the key falls through to the focused control, but still counts as
    // interaction so the auto-hide clock restarts.
    showChrome()
    return false
}

// The section 3.2 over-media literals, which deliberately do not track the theme: the chrome sits
// on video, not on a surface. The seek track keeps the progress-strip ground (0.40f) instead.
internal val OVER_MEDIA_CONTROL_FILL = Color.Black.copy(alpha = 0.45f)
internal val OVER_MEDIA_SECONDARY = Color.White.copy(alpha = 0.85f)
internal val OVER_MEDIA_TERTIARY = Color.White.copy(alpha = 0.75f)

internal const val SEEK_STEP_SEC = 10.0
internal const val CHROME_HIDE_MS = 4_000L
internal const val SCRIM_STRENGTH = 0.70f
