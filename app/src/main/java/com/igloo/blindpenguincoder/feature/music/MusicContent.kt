package com.igloo.blindpenguincoder.feature.music

import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.feature.shared.PagedState
import com.igloo.blindpenguincoder.feature.shared.PaneContent

/**
 * The four surfaces a Music tab can draw — [IglooRailState] with the empty case made explicit —
 * so the screen and the focus coordinator branch on one model whatever the tab's item type.
 */
internal sealed interface MusicContent : PaneContent {
    override val isPopulated: Boolean get() = this is Populated
    override val isSkeleton: Boolean get() = this is Loading
    override val isCardless: Boolean get() = this is Error || this is Empty
    override val isEmpty: Boolean get() = this is Empty

    data object Loading : MusicContent
    data class Error(val message: String) : MusicContent
    data object Empty : MusicContent
    data object Populated : MusicContent
}

internal fun PagedState<*>.toMusicContent(): MusicContent = when (val shown = content) {
    IglooRailState.Loading -> MusicContent.Loading
    is IglooRailState.Error -> MusicContent.Error(shown.message)
    is IglooRailState.Loaded ->
        if (shown.items.isEmpty()) MusicContent.Empty else MusicContent.Populated
}
