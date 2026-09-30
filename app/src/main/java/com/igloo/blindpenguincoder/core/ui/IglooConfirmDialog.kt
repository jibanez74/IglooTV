package com.igloo.blindpenguincoder.core.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.rememberOverlayReveal

/**
 * The confirmation modal — see docs/design-system.md section 9.3, which is authoritative for
 * everything below and explains the choices this file only implements.
 *
 * It is an **in-tree overlay, not `androidx.compose.ui.window.Dialog`**, deliberately. A dialog
 * window re-provides `LocalDensity` from its own view, which would silently drop the font-scale
 * clamp of section 12.1; it dims with the platform's own theme-blind black instead of our scrim;
 * its Back goes to a window callback no instrumented key-press can reach; and it adds a second
 * Compose root. Host it as the last child of the screen it covers, so it draws above everything
 * and nothing clips the confirm button's focus glow.
 *
 * The caller restores focus in [onDismiss] — see section 9.3 for why an effect cannot.
 */
@Composable
fun IglooConfirmDialog(
    title: String,
    body: String,
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    confirmVariant: IglooButtonVariant = IglooButtonVariant.Primary,
    pending: Boolean = false,
    pendingText: String = confirmText,
) {
    val colors = IglooTheme.colors
    val dismissFocus = remember { FocusRequester() }
    val confirmFocus = remember { FocusRequester() }
    val pendingFocus = remember { FocusRequester() }

    // Back cancels. The host must gate its own handlers while this is composed (section 9.3):
    // winning by registration order is not deliberate Back behavior. Live even while pending —
    // the request cannot be recalled, and the work belongs to a ViewModel, so leaving does not
    // abandon it. A remote dead for the length of a network timeout is the worse trade.
    BackHandler(onBack = onDismiss)

    // The focus requests below do not wait on the reveal.
    val reveal by rememberOverlayReveal("dialogReveal")

    IglooScrim(
        modifier = modifier.graphicsLayer { alpha = reveal },
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints {
            val cardWidth = minOf(IglooTheme.layout.dialogWidth, maxWidth)
            Column(
                modifier = Modifier
                    .width(cardWidth)
                    .iglooSurface(radius = IglooTheme.radius.xl, fill = colors.card)
                    .padding(IglooTheme.spacing.xl)
                    .semantics {
                        paneTitle = title
                        isTraversalGroup = true
                    },
                verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.lg),
            ) {
                IglooText(
                    text = title,
                    style = IglooTheme.typography.titleMedium,
                    color = colors.cardForeground,
                    modifier = Modifier.semantics { heading() },
                )
                // cardForeground, not mutedForeground: at 5.08:1 on card the muted token is under
                // the section 12 body target, and this is prose the user must read to decide.
                IglooText(
                    text = body,
                    style = IglooTheme.typography.bodyMedium,
                    color = colors.cardForeground,
                )

                if (pending) {
                    PendingRow(text = pendingText, focusRequester = pendingFocus)
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
                    ) {
                        // Dismiss left and focused first: the destructive action is never the
                        // default. Every direction that would leave the card is pinned to
                        // FocusRequester.Cancel, so focus search terminates here instead of
                        // falling through to whatever sits behind the scrim.
                        IglooButton(
                            text = dismissText,
                            onClick = onDismiss,
                            variant = IglooButtonVariant.Ghost,
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(dismissFocus)
                                .focusProperties {
                                    left = FocusRequester.Cancel
                                    up = FocusRequester.Cancel
                                    down = FocusRequester.Cancel
                                    right = confirmFocus
                                },
                        )
                        IglooButton(
                            text = confirmText,
                            onClick = onConfirm,
                            variant = confirmVariant,
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(confirmFocus)
                                .focusProperties {
                                    right = FocusRequester.Cancel
                                    up = FocusRequester.Cancel
                                    down = FocusRequester.Cancel
                                    left = dismissFocus
                                },
                        )
                    }
                    LaunchedEffect(Unit) { dismissFocus.requestFocus() }
                }
            }
        }
    }
}

/**
 * While the action is in flight the buttons are replaced by one **focusable** status line.
 *
 * Not disabled buttons: `clickable(enabled = false)` removes the focus target, and in an in-tree
 * modal that punches a hole in the trap — the next d-pad press would land on a card behind the
 * scrim. Keeping a pinned focusable here keeps focus inside the overlay and keeps the pins alive.
 */
@Composable
private fun PendingRow(
    text: String,
    focusRequester: FocusRequester,
) {
    var focused by remember { mutableStateOf(false) }

    IglooText(
        text = text,
        style = IglooTheme.typography.bodyMedium,
        color = IglooTheme.colors.cardForeground,
        modifier = Modifier
            .fillMaxWidth()
            .focusRing(focused = focused, radius = IglooTheme.radius.lg, scaleOnFocus = false)
            .focusRequester(focusRequester)
            .pinnedToScreen()
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .padding(IglooTheme.spacing.md)
            .clearAndSetSemantics {
                contentDescription = text
                liveRegion = LiveRegionMode.Polite
            },
    )

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}
