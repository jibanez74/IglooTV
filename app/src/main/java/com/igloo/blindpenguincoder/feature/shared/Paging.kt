package com.igloo.blindpenguincoder.feature.shared

/**
 * What sits below the last loaded row of an infinite list (docs/design-system.md section
 * 11.4); the tail is the only place the user ever sees paging.
 *
 * [Idle] and [Loading] both draw the same skeletons, which is what keeps the surface from
 * jumping: the tail's height is identical before and during a request, so firing a prefetch
 * never reflows the surface under a focused cell.
 */
sealed interface AppendState {
    /** More pages exist and nothing is in flight. */
    data object Idle : AppendState
    data object Loading : AppendState
    data class Error(val message: String) : AppendState
    /** The last page has been loaded; there is no tail to draw. */
    data object End : AppendState
}
