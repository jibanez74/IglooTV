package com.igloo.blindpenguincoder.feature.music

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import com.igloo.blindpenguincoder.core.ui.requestFocusSafely

/** The chrome keys the tab strip reports under; see [MusicFocusOwnership.tabFocused]. */
private val MUSIC_TAB_FOCUS_KEYS: Set<String> =
    MusicTab.entries.mapTo(mutableSetOf()) { it.presentation.key }

/**
 * Which node of the Music pane owns focus, tracked outside composition — the Movies pane's
 * recipe: the handoff coordinator reads it during a content swap, when the outgoing node is
 * mid-disposal and the focus system's own answer is already stale.
 */
internal class MusicFocusOwnership {
    var focusedItemId: Long? = null
    var cardlessFocused: Boolean = false
    private var focusedChromeKey: String? = null

    val screenOwnedFocus: Boolean
        get() = focusedItemId != null || cardlessFocused || focusedChromeKey != null

    /** Derived, not stored, for the reason the Movies ownership documents. */
    val tabFocused: Boolean
        get() = focusedChromeKey in MUSIC_TAB_FOCUS_KEYS

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

private class MusicFocusHandoffMemory(var content: MusicContent, var contentGeneration: Int)

/**
 * Repairs focus when the selected tab's content changes underneath it: a replacement scrolls
 * to top and re-anchors, and a skeleton resolving into items or a cardless state keeps focus
 * in the pane. Remembered per tab, so switching tabs — whose content the tab already holds —
 * is never itself a handoff and focus stays on the strip. The one replacement that does not
 * re-anchor is a page landing while a tab holds focus: the tab caused it.
 */
@Composable
internal fun MusicFocusHandoffCoordinator(
    tab: MusicTab,
    content: MusicContent,
    contentGeneration: Int,
    scrollToTop: suspend () -> Unit,
    firstItemRequester: FocusRequester,
    cardlessHandoffRequester: FocusRequester,
    focusOwnership: MusicFocusOwnership,
) {
    val memory = remember(tab) { MusicFocusHandoffMemory(content, contentGeneration) }
    val cardlessFocused = focusOwnership.cardlessFocused
    val screenOwnedFocus = focusOwnership.screenOwnedFocus
    val tabFocused = focusOwnership.tabFocused

    LaunchedEffect(tab, content, contentGeneration) {
        val outgoingContent = memory.content
        val contentReplaced = contentGeneration != memory.contentGeneration
        memory.content = content
        memory.contentGeneration = contentGeneration

        when {
            contentReplaced && screenOwnedFocus -> when {
                content is MusicContent.Populated -> {
                    scrollToTop()
                    if (!tabFocused) firstItemRequester.requestFocusSafely()
                }

                content.isCardless -> if (!tabFocused) cardlessHandoffRequester.requestFocusSafely()

                else -> Unit
            }

            outgoingContent is MusicContent.Loading && cardlessFocused && content is MusicContent.Populated -> {
                scrollToTop()
                firstItemRequester.requestFocusSafely()
            }

            outgoingContent is MusicContent.Loading && cardlessFocused && content.isCardless ->
                cardlessHandoffRequester.requestFocusSafely()
        }
    }
}
