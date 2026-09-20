package com.igloo.blindpenguincoder.feature.music

import com.igloo.blindpenguincoder.core.ui.IglooRailState

/**
 * The four surfaces a Music tab can draw — [IglooRailState] with the empty case made explicit —
 * so the screen and the focus coordinator branch on one model whatever the tab's item type.
 */
internal sealed interface MusicContent {
    data object Loading : MusicContent
    data class Error(val message: String) : MusicContent
    data object Empty : MusicContent
    data class Populated(val count: Int) : MusicContent
}

internal fun PagedState<*>.toMusicContent(): MusicContent = when (val shown = content) {
    IglooRailState.Loading -> MusicContent.Loading
    is IglooRailState.Error -> MusicContent.Error(shown.message)
    is IglooRailState.Loaded ->
        if (shown.items.isEmpty()) MusicContent.Empty else MusicContent.Populated(shown.items.size)
}

/** A surface with no cards, whose single anchored node is the pane's only focus target. */
internal val MusicContent.isCardless: Boolean
    get() = this is MusicContent.Error || this is MusicContent.Empty
