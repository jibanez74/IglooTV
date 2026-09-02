package com.igloo.blindpenguincoder.feature.movies

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import com.igloo.blindpenguincoder.core.ui.requestFocusSafely

/**
 * Which node of the Movies pane owns focus, tracked outside composition: the handoff
 * coordinator reads it during a content swap, when the outgoing node is mid-disposal and the
 * focus system's own answer is already stale.
 */
internal class MoviesFocusOwnership {
    var focusedMovieId: Long? = null
    var cardlessFocused: Boolean = false
    private var focusedChromeKey: String? = null

    /**
     * Focus is on a tab. Tabs select on focus, so a content replacement that lands while this
     * is true was caused by the very node that holds focus — and must not steal it.
     */
    var tabFocused: Boolean = false
        private set
    val screenOwnedFocus: Boolean
        get() = focusedMovieId != null || cardlessFocused || focusedChromeKey != null

    fun onMovieFocusChanged(movieId: Long, focused: Boolean) {
        if (focused) {
            focusedMovieId = movieId
            cardlessFocused = false
            focusedChromeKey = null
            tabFocused = false
        } else if (focusedMovieId == movieId) {
            focusedMovieId = null
        }
    }

    fun onChromeFocusChanged(key: String, focused: Boolean) {
        if (focused) {
            focusedChromeKey = key
            focusedMovieId = null
            cardlessFocused = false
            tabFocused = false
        } else if (focusedChromeKey == key) {
            focusedChromeKey = null
        }
    }

    fun onTabFocusChanged(key: String, focused: Boolean) {
        onChromeFocusChanged(key, focused)
        if (focused) {
            tabFocused = true
        } else if (focusedChromeKey == null) {
            tabFocused = false
        }
    }

    fun onCardlessFocusChanged(focused: Boolean) {
        cardlessFocused = focused
        if (focused) {
            focusedMovieId = null
            focusedChromeKey = null
            tabFocused = false
        }
    }
}

/** The generations the coordinator last acted on, so each change is handled exactly once. */
private class MoviesFocusHandoffMemory(
    var content: MoviesContent,
    var contentGeneration: Int,
    var silentReconcileGeneration: Int,
)

/**
 * Repairs focus when the pane's content changes underneath it: a replacement scrolls to top and
 * re-anchors, a silent Liked reconcile re-anchors only when the focused movie disappeared, and a
 * skeleton resolving into an error or empty state keeps focus in the pane.
 *
 * The one replacement that does not re-anchor is a tab switch: tabs select on focus, so the
 * page landing was caused by the tab the user is standing on, and pulling focus into the grid
 * would make the strip impossible to traverse. The grid still returns to the top so the next
 * press down lands on its entry cell.
 */
@Composable
internal fun MoviesFocusHandoffCoordinator(
    content: MoviesContent,
    contentGeneration: Int,
    silentReconcileGeneration: Int,
    gridState: LazyGridState,
    firstCardRequester: FocusRequester,
    cardlessHandoffRequester: FocusRequester,
    focusOwnership: MoviesFocusOwnership,
) {
    val memory = remember {
        MoviesFocusHandoffMemory(
            content = content,
            contentGeneration = contentGeneration,
            silentReconcileGeneration = silentReconcileGeneration,
        )
    }
    val focusedMovieId = focusOwnership.focusedMovieId
    val cardlessFocused = focusOwnership.cardlessFocused
    val screenOwnedFocus = focusOwnership.screenOwnedFocus
    val tabFocused = focusOwnership.tabFocused

    LaunchedEffect(content, contentGeneration, silentReconcileGeneration) {
        val outgoingContent = memory.content
        val contentReplaced = contentGeneration != memory.contentGeneration
        val reconciledSilently =
            silentReconcileGeneration != memory.silentReconcileGeneration
        memory.content = content
        memory.contentGeneration = contentGeneration
        memory.silentReconcileGeneration = silentReconcileGeneration

        when {
            contentReplaced && screenOwnedFocus -> {
                when (content) {
                    is MoviesContent.Populated -> {
                        gridState.scrollToItem(0)
                        if (!tabFocused) firstCardRequester.requestFocusSafely()
                    }

                    MoviesContent.Loading -> Unit
                    is MoviesContent.Error, is MoviesContent.Empty, MoviesContent.NoGenres ->
                        if (!tabFocused) cardlessHandoffRequester.requestFocusSafely()
                }
            }

            reconciledSilently &&
                focusedMovieId != null &&
                outgoingContent is MoviesContent.Populated &&
                !content.containsMovie(focusedMovieId) -> {
                when (content) {
                    is MoviesContent.Populated -> firstCardRequester.requestFocusSafely()
                    is MoviesContent.Empty -> cardlessHandoffRequester.requestFocusSafely()
                    MoviesContent.Loading, is MoviesContent.Error, MoviesContent.NoGenres -> Unit
                }
            }

            outgoingContent is MoviesContent.Loading &&
                cardlessFocused &&
                (
                    content is MoviesContent.Error ||
                        content is MoviesContent.Empty ||
                        content is MoviesContent.NoGenres
                    ) -> {
                cardlessHandoffRequester.requestFocusSafely()
            }
        }
    }
}
