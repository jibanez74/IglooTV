package com.igloo.blindpenguincoder.feature.shared

import com.igloo.blindpenguincoder.core.ui.IglooRailState

/**
 * The four surfaces a pane's list can draw — [IglooRailState] with the empty case made explicit —
 * so the screen and the focus coordinator branch on one model whatever the item type: a Music
 * tab, the Watch Rooms pane.
 */
internal sealed interface PaneListContent : PaneContent {
    override val isPopulated: Boolean get() = this is Populated
    override val isSkeleton: Boolean get() = this is Loading
    override val isCardless: Boolean get() = this is Error || this is Empty
    override val isEmpty: Boolean get() = this is Empty

    data object Loading : PaneListContent
    data class Error(val message: String) : PaneListContent
    data object Empty : PaneListContent
    data object Populated : PaneListContent
}

internal fun IglooRailState<*>.toPaneListContent(): PaneListContent = when (this) {
    IglooRailState.Loading -> PaneListContent.Loading
    is IglooRailState.Error -> PaneListContent.Error(message)
    is IglooRailState.Loaded ->
        if (items.isEmpty()) PaneListContent.Empty else PaneListContent.Populated
}
