package com.igloo.blindpenguincoder.feature.shared

/**
 * A details overlay's page (docs/design-system.md sections 11.4.1 and 11.5.1): the library
 * movie, in-theaters, album and musician screens all load, show, and fail the same way.
 */
sealed interface DetailsState<out T> {
    data object Loading : DetailsState<Nothing>
    data class Loaded<T>(val value: T) : DetailsState<T>
    data class Error(val message: String) : DetailsState<Nothing>
}

/**
 * A failed read becomes the screen's error — unless it was a background refresh over content
 * already on screen: a TV waking from standby must not swap a readable page for an error card the
 * user never asked for.
 */
internal fun <T> DetailsState<T>.errorOrKeep(
    message: String,
    userInitiated: Boolean,
): DetailsState<T> =
    if (!userInitiated && this is DetailsState.Loaded) this else DetailsState.Error(message)
