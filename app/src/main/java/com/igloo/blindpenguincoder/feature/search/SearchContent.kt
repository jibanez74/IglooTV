package com.igloo.blindpenguincoder.feature.search

import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.feature.shared.PagedState
import com.igloo.blindpenguincoder.feature.shared.PaneContent

/**
 * The five surfaces the Search pane can draw under its field — [IglooRailState] with the empty
 * case made explicit, plus [Prompt] for a blank query, where the field itself is the pane's one
 * focus target and nothing below it takes focus.
 */
internal sealed interface SearchContent : PaneContent {
    override val isPopulated: Boolean get() = this is Populated
    override val isSkeleton: Boolean get() = this is Loading
    override val isCardless: Boolean get() = this is Error || this is Empty
    override val isEmpty: Boolean get() = this is Empty

    data object Prompt : SearchContent
    data object Loading : SearchContent
    data class Error(val message: String) : SearchContent
    data object Empty : SearchContent
    data object Populated : SearchContent
}

/** The selected tab's surface; a blank [SearchUiState.searched] is the prompt whatever the tabs hold. */
internal fun SearchUiState.toSearchContent(): SearchContent {
    if (searched.isBlank()) return SearchContent.Prompt
    return when (val shown = selected.content) {
        IglooRailState.Loading -> SearchContent.Loading
        is IglooRailState.Error -> SearchContent.Error(shown.message)
        // The All view lands with every category present; it is empty when none has a row.
        is IglooRailState.Loaded -> if (shown.items.none(::drawable)) SearchContent.Empty else SearchContent.Populated
    }
}

/**
 * A section is drawn when it brought rows. A count with no rows — an index mid-rebuild can
 * answer that way — has nothing to put a card on, so it is left out rather than drawn empty.
 */
private fun drawable(item: Any?): Boolean = item !is SearchSectionUi || item.shownCount > 0

/** The All view's drawable sections, in order. */
internal fun PagedState<SearchSectionUi>.shownSections(): List<SearchSectionUi> =
    (content as? IglooRailState.Loaded)?.items.orEmpty().filter(::drawable)
