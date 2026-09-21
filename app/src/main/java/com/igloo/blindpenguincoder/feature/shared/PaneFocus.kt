package com.igloo.blindpenguincoder.feature.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import com.igloo.blindpenguincoder.core.ui.requestFocusSafely

/**
 * What a pane's content model tells the focus coordinator: each pane keeps its own sealed
 * surfaces (the Movies pane has the Genres tab's two extra waits) and answers these four
 * questions about them.
 */
interface PaneContent {
    /** Cards or rows are on screen, so there is a first item to land on. */
    val isPopulated: Boolean

    /** Card geometry with one focusable anchor, and nothing yet to hand focus to. */
    val isSkeleton: Boolean

    /** No cards: the single anchored node is the pane's only focus target. */
    val isCardless: Boolean

    /** The loaded-but-empty case among the cardless ones. */
    val isEmpty: Boolean
}

/**
 * Which node of a pane owns focus, tracked outside composition: the handoff coordinator reads
 * it during a content swap, when the outgoing node is mid-disposal and the focus system's own
 * answer is already stale. [tabKeys] are the chrome keys the pane's tab strip reports under.
 */
class PaneFocusOwnership(private val tabKeys: Set<String>) {
    var focusedItemId: Long? = null
    var cardlessFocused: Boolean = false
    private var focusedChromeKey: String? = null

    val screenOwnedFocus: Boolean
        get() = focusedItemId != null || cardlessFocused || focusedChromeKey != null

    /**
     * Focus is on a tab. Derived rather than stored, because the two focus-changed callbacks of
     * a d-pad move arrive in either order: a second flag would have to be cleared by whichever
     * of them ran last, and the losing order left it stuck true. Tabs select on focus, so a
     * content replacement landing while this holds was caused by the very node that holds
     * focus — and must not steal it.
     */
    val tabFocused: Boolean
        get() = focusedChromeKey in tabKeys

    fun onItemFocusChanged(itemId: Long, focused: Boolean) {
        if (focused) {
            focusedItemId = itemId
            cardlessFocused = false
            focusedChromeKey = null
        } else if (focusedItemId == itemId) {
            focusedItemId = null
        }
    }

    fun onChromeFocusChanged(key: String, focused: Boolean) {
        if (focused) {
            focusedChromeKey = key
            focusedItemId = null
            cardlessFocused = false
        } else if (focusedChromeKey == key) {
            focusedChromeKey = null
        }
    }

    fun onCardlessFocusChanged(focused: Boolean) {
        cardlessFocused = focused
        if (focused) {
            focusedItemId = null
            focusedChromeKey = null
        }
    }
}

/** The generations the coordinator last acted on, so each change is handled exactly once. */
private class PaneFocusHandoffMemory<C : PaneContent>(
    var content: C,
    var contentGeneration: Int,
    var silentReconcileGeneration: Int,
)

/**
 * Repairs focus when a pane's content changes underneath it: a replacement scrolls to top and
 * re-anchors, a silent reconcile (Movies' Liked grid) re-anchors only when the focused item
 * disappeared, and a skeleton resolving into items or a cardless state keeps focus in the
 * pane. The memory is remembered per [resetKey], so a pane whose tabs each hold their own
 * content (Music) passes the tab: switching to one is then never itself a handoff and focus
 * stays on the strip.
 *
 * The one replacement that does not re-anchor is a page landing while a tab holds focus: tabs
 * select on focus, so the tab the user is standing on caused it, and pulling focus into the
 * content would make the strip impossible to traverse. The surface still returns to the top so
 * the next press down lands on its entry item.
 */
@Composable
fun <C : PaneContent> PaneFocusHandoffCoordinator(
    resetKey: Any?,
    content: C,
    contentGeneration: Int,
    scrollToTop: suspend () -> Unit,
    firstItemRequester: FocusRequester,
    cardlessHandoffRequester: FocusRequester,
    focusOwnership: PaneFocusOwnership,
    silentReconcileGeneration: Int = 0,
    containsItem: (C, Long) -> Boolean = { _, _ -> true },
) {
    val memory = remember(resetKey) {
        PaneFocusHandoffMemory(content, contentGeneration, silentReconcileGeneration)
    }
    val focusedItemId = focusOwnership.focusedItemId
    val cardlessFocused = focusOwnership.cardlessFocused
    val screenOwnedFocus = focusOwnership.screenOwnedFocus
    val tabFocused = focusOwnership.tabFocused

    LaunchedEffect(resetKey, content, contentGeneration, silentReconcileGeneration) {
        val outgoingContent = memory.content
        val contentReplaced = contentGeneration != memory.contentGeneration
        val reconciledSilently = silentReconcileGeneration != memory.silentReconcileGeneration
        memory.content = content
        memory.contentGeneration = contentGeneration
        memory.silentReconcileGeneration = silentReconcileGeneration

        when {
            contentReplaced && screenOwnedFocus -> when {
                content.isPopulated -> {
                    scrollToTop()
                    if (!tabFocused) firstItemRequester.requestFocusSafely()
                }

                content.isCardless -> if (!tabFocused) cardlessHandoffRequester.requestFocusSafely()

                else -> Unit
            }

            reconciledSilently &&
                focusedItemId != null &&
                outgoingContent.isPopulated &&
                !containsItem(content, focusedItemId) -> when {
                content.isPopulated -> firstItemRequester.requestFocusSafely()
                content.isEmpty -> cardlessHandoffRequester.requestFocusSafely()
                else -> Unit
            }

            outgoingContent.isSkeleton && cardlessFocused && content.isPopulated -> {
                scrollToTop()
                firstItemRequester.requestFocusSafely()
            }

            outgoingContent.isSkeleton && cardlessFocused && content.isCardless ->
                cardlessHandoffRequester.requestFocusSafely()
        }
    }
}
