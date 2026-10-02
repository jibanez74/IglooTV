package com.igloo.blindpenguincoder.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.delay

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
 * Focuses a node that has only just been composed so that a screen reader hears about it.
 *
 * Two things can swallow the focus announcement. First, Compose sends `TYPE_VIEW_FOCUSED` only
 * when a node that was already in its previous accessibility snapshot becomes focused, and those
 * snapshots are taken in batches about 100ms apart, so a node focused in the frame it first
 * appears is never announced. Second, TalkBack for TV ignores focus events for a while after a
 * pane-title change, which it handles as a window transition. Measured on a Shield, a request
 * 150ms after the new pane appeared was dropped, and one about 700ms after was followed. Either way
 * TalkBack's cursor stays on whatever it was reading before.
 *
 * With [screenReader] on, this waits one frame and [ANNOUNCED_FOCUS_WAIT_MS], then requests focus.
 * With it off, focus moves at once. [shouldFocus] is checked after the wait, so a caller can skip
 * the request if the user has already moved focus somewhere else.
 */
internal suspend fun FocusRequester.requestFocusAnnounced(
    screenReader: Boolean,
    shouldFocus: () -> Boolean = { true },
) {
    if (screenReader) awaitAccessibilitySnapshot(ANNOUNCED_FOCUS_WAIT_MS)
    if (shouldFocus()) requestFocusSafely()
}

/**
 * Waits until a node placed in the current frame has been seen by an accessibility snapshot: one
 * frame so it is laid out, then [waitMs] for the batch that reads it. Focus moved after this is
 * announced; focus moved before it is silently dropped (see [requestFocusAnnounced]).
 */
internal suspend fun awaitAccessibilitySnapshot(waitMs: Long) {
    withFrameNanos { }
    delay(waitMs)
}

/** Outlasts both Compose's accessibility batch and TalkBack for TV's pane-change window. */
internal const val ANNOUNCED_FOCUS_WAIT_MS = 700L

/**
 * The waits of a lazy line move ([AnnouncedLazyMove]): one accessibility batch with margin before
 * the focus request, and the same again while the scroll is held after it. Measured on a Shield,
 * TalkBack for TV put its cursor on the newly focused card about 70ms after the focus event.
 */
internal const val ANNOUNCED_LINE_WAIT_MS = 250L

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
