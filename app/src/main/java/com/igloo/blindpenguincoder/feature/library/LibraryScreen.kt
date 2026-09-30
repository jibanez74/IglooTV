package com.igloo.blindpenguincoder.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.unit.Dp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooButton
import com.igloo.blindpenguincoder.core.ui.IglooButtonVariant
import com.igloo.blindpenguincoder.core.ui.IglooIcons
import com.igloo.blindpenguincoder.core.ui.IglooPosterCard
import com.igloo.blindpenguincoder.data.model.SortOrder
import com.igloo.blindpenguincoder.feature.shared.AppendState
import com.igloo.blindpenguincoder.feature.shared.GRID_PREFETCH_ROWS
import com.igloo.blindpenguincoder.feature.shared.PaneEmpty
import com.igloo.blindpenguincoder.feature.shared.PaneFirstPageError
import com.igloo.blindpenguincoder.feature.shared.PaneFocusHandoffCoordinator
import com.igloo.blindpenguincoder.feature.shared.PaneFocusOwnership
import com.igloo.blindpenguincoder.feature.shared.PaneGrid
import com.igloo.blindpenguincoder.feature.shared.PaneGridSkeleton
import com.igloo.blindpenguincoder.feature.shared.PaneHeader
import com.igloo.blindpenguincoder.feature.shared.PaneRefreshButton
import com.igloo.blindpenguincoder.feature.shared.PaneTabRow
import com.igloo.blindpenguincoder.feature.shared.paneCardlessAnchor
import com.igloo.blindpenguincoder.feature.shared.paneCountLine
import com.igloo.blindpenguincoder.feature.shared.showingCountLine

/** What a library pane needs from its view model, bundled rather than threaded as eight lambdas. */
data class LibraryActions(
    val onRefresh: () -> Unit,
    val onRetryFirstPage: () -> Unit,
    val onRetryAppend: () -> Unit,
    val onLoadMore: () -> Unit,
    /** A tab taking focus — debounced by the view model, because a slide crosses every tab. */
    val onSelectTab: (LibraryTab) -> Unit,
    /** A press on a tab: deliberate, so it switches at once. */
    val onPressTab: (LibraryTab) -> Unit,
    val onSelectGenre: (LibraryFilter.Genre) -> Unit,
    val onToggleSort: () -> Unit,
)

/**
 * The library index Movies and TV Shows share (docs/design-system.md section 11.4): a heading
 * with the current view's count, Sort and Refresh actions, a tab strip (All · Genres, plus
 * Liked where the backend keeps likes), the Genres tab's chip picker, and an infinite-scrolling
 * poster grid. [LibraryUiState.kind] decides the wording, the tags and the glyph.
 *
 * The grid pages itself. Unlike the web client's numbered pagination a d-pad user never sees a
 * page control — scrolling within [GRID_PREFETCH_ROWS] rows of the end asks for the next page, and
 * the only paging the screen renders is the tail below the last loaded row.
 *
 * [contentStartRequester] lands on the grid's entry cell — the remembered card if there is one,
 * otherwise the first — so entering the pane puts focus on content rather than on chrome, with
 * the tabs (and, on Genres, the picker) one d-pad press above it and the header a press above
 * them. In the states with no cards the anchor moves to whatever the screen draws instead,
 * because the shell's focus model assumes the pane always has somewhere to land. The tab strip
 * renders in every grid state, and the picker in every grid state while the Genres tab has a
 * list — an empty Liked view or a failed first page must still let the user switch sections.
 *
 * [returnRequester] rides the entry cell so an overlay's Back lands on the card that opened it;
 * null for a pane nothing opens from yet. A null [onItemSelected] renders inert cards — focus
 * targets with no action and no "Open" announcement — for the same reason.
 */
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    actions: LibraryActions,
    contentInset: PaddingValues,
    gridState: LazyGridState,
    contentStartRequester: FocusRequester,
    navigationRequester: FocusRequester,
    returnRequester: FocusRequester?,
    lastFocusedId: Long?,
    onItemFocused: (Long) -> Unit,
    onItemSelected: ((Long) -> Unit)?,
) {
    val kind = state.kind
    val columns = IglooTheme.layout.gridColumns
    val content = state.toLibraryContent()
    val refreshRequester = remember { FocusRequester() }
    val sortRequester = remember { FocusRequester() }
    val tabRowRequester = remember { FocusRequester() }
    val genreRowRequester = remember { FocusRequester() }
    val firstCardRequester = remember { FocusRequester() }
    val cardlessHandoffRequester = remember { FocusRequester() }
    // Every section's key, whether or not this source offers it: a superset costs nothing and
    // keeps the set fixed for the pane's whole life.
    val focusOwnership = remember(kind) {
        PaneFocusOwnership(LibraryTab.entries.mapTo(mutableSetOf()) { kind.presentation(it).key })
    }
    // The picker only exists with a list to pick from; without it the content's way up is the
    // tab strip, so the wired edge follows whichever row is actually composed.
    val genreRowShown = state.tab == LibraryTab.Genres && state.genres.isNotEmpty()
    val contentUp = if (genreRowShown) genreRowRequester else tabRowRequester

    Column(
        // No horizontal padding here: the grid carries the pane's gutter as contentPadding so
        // the scroll surface spans the panel (section 8.3). Padding the parent would re-clip it.
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
    ) {
        LibraryHeader(
            kind = kind,
            total = state.paged.total,
            loadedCount = content.loadedCount,
            filter = state.filter,
            genresLoading = content is LibraryContent.GenresLoading,
            append = state.paged.append,
            sort = state.sort,
            refreshing = state.refreshing,
            notice = state.notice,
            contentInset = contentInset,
            navigationRequester = navigationRequester,
            sortRequester = sortRequester,
            refreshRequester = refreshRequester,
            tabRowRequester = tabRowRequester,
            onToggleSort = actions.onToggleSort,
            onRefresh = actions.onRefresh,
            onFocusChanged = focusOwnership::onChromeFocusChanged,
        )

        PaneTabRow(
            tabs = state.tabs,
            selected = state.tab,
            presentation = kind::presentation,
            testTag = "${kind.tagPrefix}_tabs",
            contentInset = contentInset,
            tabRowRequester = tabRowRequester,
            navigationRequester = navigationRequester,
            refreshRequester = refreshRequester,
            downRequester = if (genreRowShown) genreRowRequester else contentStartRequester,
            onSelectTab = actions.onSelectTab,
            onPressTab = actions.onPressTab,
            onFocusChanged = focusOwnership::onChromeFocusChanged,
        )

        if (genreRowShown) {
            LibraryGenreRow(
                kind = kind,
                genres = state.genres,
                selected = state.genre,
                contentInset = contentInset,
                genreRowRequester = genreRowRequester,
                navigationRequester = navigationRequester,
                tabRowRequester = tabRowRequester,
                contentStartRequester = contentStartRequester,
                onSelectGenre = actions.onSelectGenre,
                onFocusChanged = focusOwnership::onChromeFocusChanged,
            )
        }

        val cardlessAnchor = Modifier.paneCardlessAnchor(
            contentStartRequester = contentStartRequester,
            returnRequester = returnRequester,
            cardlessHandoffRequester = cardlessHandoffRequester,
            navigationRequester = navigationRequester,
            upRequester = contentUp,
            focusOwnership = focusOwnership,
        )

        PaneFocusHandoffCoordinator(
            resetKey = Unit,
            content = content,
            contentGeneration = state.paged.contentGeneration,
            scrollToTop = { gridState.scrollToItem(0) },
            firstItemRequester = firstCardRequester,
            cardlessHandoffRequester = cardlessHandoffRequester,
            focusOwnership = focusOwnership,
            silentReconcileGeneration = state.silentReconcileGeneration,
            containsItem = LibraryContent::containsItem,
        )

        when (content) {
            // The Genres tab waiting on its list is a skeleton like any other wait: the grid it
            // will draw is unknown, and claiming the list is unavailable before it has landed
            // reports a failure that has not happened.
            LibraryContent.Loading, LibraryContent.GenresLoading -> PaneGridSkeleton(
                columns = columns,
                contentInset = contentInset,
                loadingLabel = content.loadingLabel(kind),
                anchorModifier = cardlessAnchor,
                cardAspect = IglooTheme.layout.posterAspect,
            )

            is LibraryContent.Error -> PaneFirstPageError(
                message = content.message,
                retryLabel = "Retry loading ${kind.libraryPhrase}",
                onRetry = actions.onRetryFirstPage,
                anchorModifier = cardlessAnchor,
                contentInset = contentInset,
            )

            // The placeholder shares the empty box's shape so the focus coordinator can treat
            // the two alike: one anchored node, nothing else to land on.
            is LibraryContent.Empty, LibraryContent.NoGenres -> PaneEmpty(
                icon = kind.icon,
                message = if (content is LibraryContent.Empty) {
                    kind.emptyMessage(content.filter)
                } else {
                    NO_GENRES_MESSAGE
                },
                anchorModifier = cardlessAnchor,
                contentInset = contentInset,
            )

            is LibraryContent.Populated -> PaneGrid(
                items = content.items,
                itemId = { it.id },
                paged = state.paged,
                refreshing = state.refreshing,
                columns = columns,
                gridState = gridState,
                contentInset = contentInset,
                contentStartRequester = contentStartRequester,
                navigationRequester = navigationRequester,
                returnRequester = returnRequester,
                firstItemRequester = firstCardRequester,
                upRequester = contentUp,
                lastFocusedId = lastFocusedId,
                onItemFocused = onItemFocused,
                focusOwnership = focusOwnership,
                onLoadMore = actions.onLoadMore,
                onRetryAppend = actions.onRetryAppend,
                retryLabel = "Retry loading more ${kind.plural}",
                testTag = "${kind.tagPrefix}_grid",
                cardTag = { kind.cardTag(it.id) },
                cardAspect = IglooTheme.layout.posterAspect,
            ) { item, modifier ->
                IglooPosterCard(
                    title = item.title,
                    subtitle = item.year?.toString(),
                    imageUrl = item.posterUrl,
                    onClick = onItemSelected?.let { open -> { open(item.id) } },
                    // Unspecified so the card fills its grid cell rather than taking the rail's
                    // fixed card width and leaving ragged gutters.
                    width = Dp.Unspecified,
                    fallbackIcon = kind.icon,
                    modifier = modifier,
                )
            }
        }
    }
}

@Composable
private fun LibraryHeader(
    kind: LibraryKind,
    total: Long?,
    loadedCount: Int?,
    filter: LibraryFilter?,
    genresLoading: Boolean,
    append: AppendState,
    sort: SortOrder,
    refreshing: Boolean,
    notice: String?,
    contentInset: PaddingValues,
    navigationRequester: FocusRequester,
    sortRequester: FocusRequester,
    refreshRequester: FocusRequester,
    tabRowRequester: FocusRequester,
    onToggleSort: () -> Unit,
    onRefresh: () -> Unit,
    onFocusChanged: (String, Boolean) -> Unit,
) {
    PaneHeader(
        title = kind.heading,
        countText = paneCountLine(total.takeIf { filter != null }, kind::noun),
        countDescription = spokenCount(kind, total, loadedCount, filter, genresLoading, append),
        countTag = "${kind.tagPrefix}_count",
        notice = notice,
        contentInset = contentInset,
    ) {
        IglooButton(
            // Direction-only on purpose: the backend sorts by title and offers no field choice.
            text = if (sort == SortOrder.Ascending) SORT_ASCENDING_LABEL else SORT_DESCENDING_LABEL,
            labelVariants = listOf(SORT_ASCENDING_LABEL, SORT_DESCENDING_LABEL),
            onClick = onToggleSort,
            variant = IglooButtonVariant.Ghost,
            // Never disabled, for the same focus-tree reason as Refresh; the view model guards a
            // toggle that lands mid-reload by cancelling the in-flight page.
            enabled = true,
            semanticLabel = "Sort order",
            stateDescription = if (sort == SortOrder.Ascending) "A to Z" else "Z to A",
            actionLabel = if (sort == SortOrder.Ascending) "Sort Z to A" else "Sort A to Z",
            modifier = Modifier
                .focusRequester(sortRequester)
                .onFocusChanged { onFocusChanged("${kind.tagPrefix}_sort", it.isFocused) }
                // Down is wired to the *selected* tab: tabs select on focus, and a spatial
                // search would land on whichever tab happens to sit beneath and switch to it.
                .focusProperties {
                    left = navigationRequester
                    down = tabRowRequester
                },
        )
        PaneRefreshButton(
            refreshing = refreshing,
            semanticLabel = "Refresh ${kind.libraryPhrase}",
            onRefresh = onRefresh,
            modifier = Modifier
                .focusRequester(refreshRequester)
                .onFocusChanged { onFocusChanged("${kind.tagPrefix}_refresh", it.isFocused) }
                // A deterministic left chain: Refresh → Sort → the navigation spine.
                .focusProperties {
                    left = sortRequester
                    down = tabRowRequester
                },
        )
    }
}

/** The glyph a poster-less card and the empty box show for this library. */
private val LibraryKind.icon: ImageVector
    get() = when (this) {
        LibraryKind.Movies -> IglooIcons.Movies
        LibraryKind.Shows -> IglooIcons.TvShows
    }

/**
 * The visible line stays generic; only the spoken form names the active filter. A null
 * [filter] is one of the Genres tab's two card-less surfaces, where the count belongs to a list
 * the user cannot see — and the two must not sound alike, because only one of them is a failure.
 */
private fun spokenCount(
    kind: LibraryKind,
    total: Long?,
    loadedCount: Int?,
    filter: LibraryFilter?,
    genresLoading: Boolean,
    appendState: AppendState,
): String =
    when {
        // Not the anchor's own "Loading genres": two nodes speaking the same phrase is the
        // redundant announcement section 12 rules out, and this line is about scale.
        filter == null -> if (genresLoading) "Loading the genre list" else "Genres unavailable"
        total == null -> "Loading ${kind.libraryPhrase}"
        loadedCount == null -> paneCountLine(total, kind::noun)
        else -> showingCountLine(
            loadedCount = loadedCount,
            total = total,
            noun = filterNoun(kind, filter, total),
            pluralNoun = kind.plural,
            append = appendState,
        )
    }

private fun filterNoun(kind: LibraryKind, filter: LibraryFilter, count: Long): String =
    when (filter) {
        LibraryFilter.All -> kind.noun(count)
        LibraryFilter.Liked -> "liked ${kind.noun(count)}"
        is LibraryFilter.Genre -> "${filter.tag} ${kind.noun(count)}"
    }

private const val NO_GENRES_MESSAGE = "Genres aren't available right now. Refresh to try again."
private const val SORT_ASCENDING_LABEL = "A–Z"
private const val SORT_DESCENDING_LABEL = "Z–A"
