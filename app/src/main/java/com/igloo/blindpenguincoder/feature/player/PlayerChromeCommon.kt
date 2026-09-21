package com.igloo.blindpenguincoder.feature.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.overMedia
import com.igloo.blindpenguincoder.core.design.scaled
import com.igloo.blindpenguincoder.core.ui.IglooButton
import com.igloo.blindpenguincoder.core.ui.IglooButtonVariant
import com.igloo.blindpenguincoder.core.ui.IglooIcons
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

/**
 * The chrome's top bar: the scrim that keeps white legible over media, the Back button, and the
 * media's title. Left/right/up are pinned — Back is the row's only control and the top of the
 * vertical route — while [downRequester] is the screen's, because what sits under Back differs
 * (the transport, a reading stop, or a progress-retry action).
 */
@Composable
internal fun PlayerTopBar(
    title: String,
    backRequester: FocusRequester,
    downRequester: FocusRequester,
    backTag: String,
    onBack: () -> Unit,
    onFocused: () -> Unit = {},
) {
    val layout = IglooTheme.layout
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .playerTopScrim()
            .padding(
                horizontal = layout.safeAreaHorizontal,
                vertical = layout.safeAreaVertical,
            ),
        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IglooButton(
            text = "Back",
            icon = IglooIcons.ArrowBack,
            onClick = onBack,
            variant = IglooButtonVariant.Ghost,
            semanticLabel = "Close player",
            restingFill = OVER_MEDIA_CONTROL_FILL,
            contentColor = Color.White,
            modifier = Modifier
                .focusRequester(backRequester)
                .onFocusChanged { if (it.isFocused) onFocused() }
                .focusProperties {
                    left = FocusRequester.Cancel
                    right = FocusRequester.Cancel
                    up = FocusRequester.Cancel
                    down = downRequester
                }
                .testTag(backTag),
        )
        IglooText(
            text = title,
            style = IglooTheme.typography.titleMedium.overMedia(true),
            color = Color.White,
            maxLines = 1,
        )
    }
}

/** The top bar's scrim: black at the edge, clear where the media shows through. */
internal fun Modifier.playerTopScrim(): Modifier = background(
    Brush.verticalGradient(
        0f to Color.Black.copy(alpha = SCRIM_STRENGTH),
        1f to Color.Black.copy(alpha = 0f),
    ),
)

/** The transport block's scrim: the mirror of [playerTopScrim]. */
internal fun Modifier.playerBottomScrim(): Modifier = background(
    Brush.verticalGradient(
        0f to Color.Black.copy(alpha = 0f),
        1f to Color.Black.copy(alpha = SCRIM_STRENGTH),
    ),
)

/**
 * One transport control's focus edges. Every control pins down to Cancel and up to [up]; only
 * the row's outer edges cancel sideways, so a control further along the row stays one press
 * away.
 */
internal fun Modifier.transportFocus(
    up: FocusRequester,
    isFirst: Boolean = false,
    isLast: Boolean = false,
    onFocused: () -> Unit = {},
): Modifier = this
    .onFocusChanged { if (it.isFocused) onFocused() }
    .focusProperties {
        if (isFirst) left = FocusRequester.Cancel
        if (isLast) right = FocusRequester.Cancel
        this.up = up
        down = FocusRequester.Cancel
    }

/**
 * The transport announcement for a TalkBack focus parked anywhere: play-state flips driven by
 * media keys are otherwise silent. Polite — it narrates, it never interrupts. A null [text]
 * composes nothing, which is how a phase with nothing to say stays quiet.
 */
@Composable
internal fun PoliteAnnouncement(text: String?) {
    if (text == null) return
    Box(
        modifier = Modifier
            .size(1.dp)
            .clearAndSetSemantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = text
            },
    )
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

/**
 * A terminal playback failure. The action is Retry, except for a revoked session: that cannot
 * be retried into working — the host is already revalidating — so Close is the only honest
 * action it has. [mediaNoun] names what failed in the fallback sentence and the Retry label.
 */
@Composable
internal fun PlayerFailureSurface(
    message: String?,
    unauthorized: Boolean,
    mediaNoun: String,
    actionRequester: FocusRequester,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    PlayerErrorSurface(
        message = message ?: "The $mediaNoun could not be played.",
        actionText = if (unauthorized) "Close" else "Retry",
        actionSemanticLabel = if (unauthorized) "Close player" else "Retry playing $mediaNoun",
        actionRequester = actionRequester,
        onAction = if (unauthorized) onClose else onRetry,
    )
}

/** The Activity behind a composition's context; every player's ON_STOP asks it about config changes. */
internal tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}

// The section 3.2 over-media literals, which deliberately do not track the theme: the chrome sits
// on video, not on a surface. The seek track keeps the progress-strip ground (0.40f) instead.
internal val OVER_MEDIA_CONTROL_FILL = Color.Black.copy(alpha = 0.45f)
internal val OVER_MEDIA_SECONDARY = Color.White.copy(alpha = 0.85f)
internal val OVER_MEDIA_TERTIARY = Color.White.copy(alpha = 0.75f)

internal const val SEEK_STEP_SEC = 10.0
internal const val CHROME_HIDE_MS = 4_000L
internal const val SCRIM_STRENGTH = 0.70f
