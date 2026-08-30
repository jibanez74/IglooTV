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
    val screenOwnedFocus: Boolean
        get() = focusedMovieId != null || cardlessFocused || focusedChromeKey != null

    fun onMovieFocusChanged(movieId: Long, focused: Boolean) {
        if (focused) {
            focusedMovieId = movieId
            cardlessFocused = false
            focusedChromeKey = null
        } else if (focusedMovieId == movieId) {
            focusedMovieId = null
        }
    }

    fun onChromeFocusChanged(key: String, focused: Boolean) {
        if (focused) {
            focusedChromeKey = key
            focusedMovieId = null
            cardlessFocused = false
        } else if (focusedChromeKey == key) {
            focusedChromeKey = null
        }
    }

    fun onCardlessFocusChanged(focused: Boolean) {
        cardlessFocused = focused
        if (focused) {
            focusedMovieId = null
            focusedChromeKey = null
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
                        firstCardRequester.requestFocusSafely()
                    }

                    MoviesContent.Loading -> Unit
                    is MoviesContent.Error, is MoviesContent.Empty ->
                        cardlessHandoffRequester.requestFocusSafely()
                }
            }

            reconciledSilently &&
                focusedMovieId != null &&
                outgoingContent is MoviesContent.Populated &&
                !content.containsMovie(focusedMovieId) -> {
                when (content) {
                    is MoviesContent.Populated -> firstCardRequester.requestFocusSafely()
                    is MoviesContent.Empty -> cardlessHandoffRequester.requestFocusSafely()
                    MoviesContent.Loading, is MoviesContent.Error -> Unit
                }
            }

            outgoingContent is MoviesContent.Loading &&
                cardlessFocused &&
                (content is MoviesContent.Error || content is MoviesContent.Empty) -> {
                cardlessHandoffRequester.requestFocusSafely()
            }
        }
    }
}
