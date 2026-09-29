package com.igloo.blindpenguincoder.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

/**
 * Attaches [requester] when there is one. Several requesters may ride the same node — a rail's
 * card is at once its entry anchor and the node an overlay returns focus to — and this keeps the
 * optional ones out of the modifier chain's shape.
 */
internal fun Modifier.withRequester(requester: FocusRequester?): Modifier =
    if (requester != null) focusRequester(requester) else this

/**
 * Requests focus unless the requester has no node attached; reports whether it landed.
 *
 * Restoring focus after an overlay closes is done in the callback that closes it, before the
 * nodes underneath have been recomposed, so the anchor a requester pointed at can be gone — a
 * rail whose list changed while the overlay was up, or a state whose action row is now absent.
 * Landing somewhere else is a worse restore than the right node and a far better outcome than
 * the crash `requestFocus()` would raise (design system section 9.3).
 */
internal fun FocusRequester.requestFocusSafely(): Boolean =
    runCatching { requestFocus() }.isSuccess

/**
 * Re-lands focus after [key] swaps what a subtree shows, but only if the subtree owned focus going
 * into the swap; report its focus through the returned setter (`hasFocus` for a container,
 * `isFocused` for a single control).
 *
 * The capture happens in the composition that makes the swap: the outgoing node only detaches —
 * clearing the reported focus — once that composition applies, so the flag still says whether
 * focus was here. [refocus] then runs once the incoming state's nodes exist. Without this, a
 * focused node disposing drops focus on the floor.
 */
@Composable
internal fun rememberRefocusAfterSwap(key: Any?, refocus: () -> Unit): (Boolean) -> Unit {
    val ownsFocus = remember { mutableStateOf(false) }
    val ownedFocusAtSwap = remember(key) { ownsFocus.value }
    LaunchedEffect(key) { if (ownedFocusAtSwap) refocus() }
    return remember { { focused -> ownsFocus.value = focused } }
}
