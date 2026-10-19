package com.igloo.blindpenguincoder.core.ui

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.ScrollableState
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
 * Moves d-pad focus one section up or down a scrolling column so that a screen reader follows
 * it — [AnnouncedLazyMove] at section scale, for Home's hero and rails.
 *
 * Compose reports a focus change only for a node its last accessibility snapshot held, and that
 * snapshot holds what is on screen. A rail below the fold is composed but not on screen, so
 * Compose's own move focuses its entry card and scrolls it into view in one frame, and the
 * focus goes unannounced; TalkBack for TV then reacts to the scroll by re-reading whatever stayed
 * on screen (the card just left) and never speaks the new one. So while a screen reader runs, a
 * press whose target section is not yet on screen is taken over: [reveal] shows part of it while
 * the origin stays on screen, a snapshot is awaited, the target's entry takes focus with the
 * scroll held so the origin does not leave the screen before TalkBack has followed, and [settle]
 * brings the section to rest. A press whose target is already on screen, or has nowhere to go,
 * is left to Compose, whose move is announced in that case. With no screen reader every press is
 * left to Compose.
 *
 * Sections are named by stable keys, not positions: one can appear or go (a Retry row) while a
 * move is in flight, and every step resolves its section afresh — a target that is gone by then
 * ends the move. The lambdas read live state, so one instance outlives recompositions and can
 * swallow key repeats while a move is in flight.
 */
internal class AnnouncedSectionMove<K : Any>(
    private val scope: CoroutineScope,
    private val scrollable: ScrollableState,
    private val screenReader: () -> Boolean,
    /** The section holding focus, or null. */
    private val focusedSection: () -> K?,
    /** The section after (forward) or before this one, or null at either end. */
    private val neighbourOf: (key: K, forward: Boolean) -> K?,
    /** Whether the section's focus target is at least partly on screen. */
    private val isOnScreen: (key: K) -> Boolean,
    /** Shows part of the section — its leading part when moving forward, trailing when back. */
    private val reveal: suspend (key: K, forward: Boolean) -> Unit,
    private val settle: suspend (key: K) -> Unit,
    /** The section's focus target, or null once the section is gone. */
    private val requesterFor: (key: K) -> FocusRequester?,
) {
    private var inFlight: Job? = null

    /**
     * Handles a previewed key event. Returns true when the press was taken over (or swallowed as
     * a repeat during a move) and false to leave it to Compose's focus search.
     */
    fun onPreviewKey(event: KeyEvent): Boolean {
        if (!screenReader() || event.type != KeyEventType.KeyDown) return false
        val forward = when (event.key) {
            Key.DirectionDown -> true
            Key.DirectionUp -> false
            else -> return false
        }
        if (inFlight?.isActive == true) return true
        val origin = focusedSection() ?: return false
        val target = neighbourOf(origin, forward) ?: return false
        if (isOnScreen(target)) return false
        inFlight = scope.launch {
            reveal(target, forward)
            awaitAccessibilitySnapshot(ANNOUNCED_LINE_WAIT_MS)
            val now = focusedSection()
            if (now != origin && now != target) return@launch
            val requester = requesterFor(target) ?: return@launch
            scrollable.scroll(MutatePriority.PreventUserInput) {
                requester.requestFocusSafely()
                delay(ANNOUNCED_LINE_WAIT_MS)
            }
            settle(target)
        }
        return true
    }
}
