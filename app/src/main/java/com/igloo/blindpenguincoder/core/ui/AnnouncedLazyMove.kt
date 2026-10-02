package com.igloo.blindpenguincoder.core.ui

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Moves d-pad focus one line through a lazy grid or row so that a screen reader follows it.
 *
 * Compose's own move composes the next line and focuses its card in the same frame, and a node
 * focused in the frame it first appears is never announced (see [requestFocusAnnounced]). On TV,
 * TalkBack then reacts to the scroll that follows by moving input focus itself — to the last card
 * of the new line, which is how a column walk down a grid ends in the last column
 * (docs/design-system.md section 8.3). So while a screen reader runs, a press whose target line
 * is not yet placed is taken over: the list reveals part of the line while the origin line stays
 * on screen, waits for an accessibility snapshot to see the new cards, focuses the one in the
 * same column while holding the scroll, and settles the line once TalkBack has followed. A press
 * whose target is already placed, or has no line to go to, is left to Compose, whose move is
 * announced in that case. With no screen reader every press is left to Compose.
 *
 * The callers' lambdas read live state, so one instance outlives the list's recompositions and
 * can swallow key repeats while a move is in flight.
 */
internal class AnnouncedLazyMove(
    private val scope: CoroutineScope,
    private val scrollable: ScrollableState,
    private val screenReader: () -> Boolean,
    private val focusedIndex: () -> Int,
    private val lastIndex: () -> Int,
    private val isPlaced: (index: Int) -> Boolean,
    /** Signed pixels that show part of the target line while the origin line stays on screen. */
    private val revealDistance: (forward: Boolean) -> Float,
    /** Signed pixels that bring the focused line to its resting place once it is announced. */
    private val settleDistance: (forward: Boolean) -> Float,
    private val requesterFor: (index: Int) -> FocusRequester?,
    /** Whether focus is still on [origin]'s card, or already on [target]'s line, after the wait. */
    private val stillOnCourse: (origin: Int, target: Int) -> Boolean,
) {
    private var inFlight: Job? = null

    /**
     * Handles a previewed key event. Returns true when the press was taken over (or swallowed as a
     * repeat during a move) and false to leave it to Compose's focus search. [stride] is the
     * number of items per line: the column count for a grid, 1 for a row.
     */
    fun onPreviewKey(event: KeyEvent, forwardKey: Key, backKey: Key, stride: Int): Boolean {
        if (!screenReader() || event.type != KeyEventType.KeyDown) return false
        val forward = when (event.key) {
            forwardKey -> true
            backKey -> false
            else -> return false
        }
        if (inFlight?.isActive == true) return true
        val origin = focusedIndex()
        if (origin < 0) return false
        val lastIndex = lastIndex()
        val targetLine = origin / stride + if (forward) 1 else -1
        if (targetLine < 0 || targetLine > lastIndex / stride) return false
        // A shorter last line has no card in this column; its last card is the nearest.
        val target = (origin + if (forward) stride else -stride).coerceAtMost(lastIndex)
        if (isPlaced(target)) return false
        inFlight = scope.launch {
            scrollable.scrollBy(revealDistance(forward))
            awaitAccessibilitySnapshot(ANNOUNCED_LINE_WAIT_MS)
            if (!stillOnCourse(origin, target)) return@launch
            // Focus with the scroll held. Compose's bring-into-view would otherwise take the
            // origin line off screen in the next frame, and TalkBack for TV, finding the card it
            // was reading gone before it has handled the focus event, moves input focus to a
            // card of its own choosing. The hold refuses that scroll; once TalkBack has followed
            // the focus, the line settles here instead.
            scrollable.scroll(MutatePriority.PreventUserInput) {
                requesterFor(target)?.requestFocusSafely()
                delay(ANNOUNCED_LINE_WAIT_MS)
            }
            scrollable.animateScrollBy(settleDistance(forward))
        }
        return true
    }
}
