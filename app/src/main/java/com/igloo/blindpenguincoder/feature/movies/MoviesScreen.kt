package com.igloo.blindpenguincoder.feature.movies

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooButton
import com.igloo.blindpenguincoder.core.ui.IglooButtonVariant
import com.igloo.blindpenguincoder.core.ui.IglooEmpty
import com.igloo.blindpenguincoder.core.ui.IglooIcons
import com.igloo.blindpenguincoder.core.ui.IglooInlineError
import com.igloo.blindpenguincoder.core.ui.IglooNotice
import com.igloo.blindpenguincoder.core.ui.IglooPosterCard
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.core.ui.IglooSkeletonCell
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.focusRing
import com.igloo.blindpenguincoder.core.ui.requestFocusSafely
import com.igloo.blindpenguincoder.core.ui.withRequester
import com.igloo.blindpenguincoder.data.model.SortOrder
import java.text.NumberFormat

/** What the Movies pane needs from its view model, bundled rather than threaded as six lambdas. */
data class MoviesActions(
    val onRefresh: () -> Unit,
    val onRetryFirstPage: () -> Unit,
    val onRetryAppend: () -> Unit,
    val onLoadMore: () -> Unit,
    val onSelectFilter: (MoviesFilter) -> Unit,
    val onToggleSort: () -> Unit,
)

/**
 * The movie library index (docs/design-system.md section 11.4): a heading with the current
 * view's count, Sort and Refresh actions, and a filter chip row (All · Liked · genres) over an
 * infinite-scrolling poster grid.
 *
 * The grid pages itself. Unlike the web client's numbered pagination a d-pad user never sees a
 * page control — scrolling within [PREFETCH_ROWS] rows of the end asks for the next page, and
 * the only paging the screen renders is the tail below the last loaded row.
 *
 * [contentStartRequester] lands on the grid's entry cell — the remembered card if there is one,
 * otherwise the first — so entering the pane puts focus on content rather than on chrome, with
 * the filter chips one d-pad press above it and the header a press above them. In the states
 * with no cards the anchor moves to whatever the screen draws instead, because the shell's
 * focus model assumes the pane always has somewhere to land. The chip row renders in every grid
 * state — an empty Liked view or a failed first page must still let the user switch filters.
 */
@Composable
fun MoviesScreen(
    state: MoviesUiState,
    actions: MoviesActions,
    contentInset: PaddingValues,
    gridState: LazyGridState,
    contentStartRequester: FocusRequester,
    navigationRequester: FocusRequester,
    returnRequester: FocusRequester,
    lastFocusedMovieId: Long?,
    onMovieFocused: (Long) -> Unit,
    onMovieSelected: ((Long) -> Unit)?,
) {
    val columns = IglooTheme.layout.gridColumns
    val content = state.toMoviesContent()
    val refreshRequester = remember { FocusRequester() }
    val sortRequester = remember { FocusRequester() }
    val filterRowRequester = remember { FocusRequester() }
    val firstCardRequester = remember { FocusRequester() }
    val cardlessHandoffRequester = remember { FocusRequester() }
    val focusOwnership = remember { MoviesFocusOwnership() }

    Column(
        // No horizontal padding here: the grid carries the pane's gutter as contentPadding so
        // the scroll surface spans the panel (section 8.3). Padding the parent would re-clip it.
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
    ) {
        MoviesHeader(
            totalMovies = state.totalMovies,
            loadedCount = (content as? MoviesContent.Populated)?.items?.size
                ?: 0.takeIf { content is MoviesContent.Empty },
            filter = state.filter,
            append = state.append,
            sort = state.sort,
            refreshing = state.refreshing,
            notice = state.notice,
            contentInset = contentInset,
            navigationRequester = navigationRequester,
            sortRequester = sortRequester,
            refreshRequester = refreshRequester,
            onToggleSort = actions.onToggleSort,
            onRefresh = actions.onRefresh,
            onFocusChanged = focusOwnership::onChromeFocusChanged,
        )

        MoviesFilterRow(
            genres = state.genres,
            selected = state.filter,
            contentInset = contentInset,
            filterRowRequester = filterRowRequester,
            navigationRequester = navigationRequester,
            refreshRequester = refreshRequester,
            contentStartRequester = contentStartRequester,
            onSelectFilter = actions.onSelectFilter,
            onFocusChanged = focusOwnership::onChromeFocusChanged,
        )

        // In the states with no cards the anchor also carries [returnRequester]: the details
        // overlay can outlive the card that opened it (the Liked reconcile empties the grid
        // underneath), and a detached return requester does not fail its focus request — it
        // silently no-ops, the host's fallback never runs, and the overlay's disposal hands
        // focus to the platform fallback in the navigation rail. A live anchor node is the fix.
        val cardlessAnchor = Modifier
            .withRequester(contentStartRequester)
            .withRequester(returnRequester)
            .withRequester(cardlessHandoffRequester)
            .focusProperties {
                left = navigationRequester
                up = filterRowRequester
            }
            .onFocusChanged { focusOwnership.onCardlessFocusChanged(it.isFocused) }

        MoviesFocusHandoffCoordinator(
            content = content,
            contentGeneration = state.contentGeneration,
            silentReconcileGeneration = state.silentReconcileGeneration,
            gridState = gridState,
            firstCardRequester = firstCardRequester,
            cardlessHandoffRequester = cardlessHandoffRequester,
            focusOwnership = focusOwnership,
        )

        when (content) {
            MoviesContent.Loading -> MoviesGridSkeleton(
                columns = columns,
                contentInset = contentInset,
                anchorModifier = cardlessAnchor,
            )

            is MoviesContent.Error -> IglooInlineError(
                message = content.message,
                actionText = "Retry",
                actionSemanticLabel = "Retry loading the movie library",
                onAction = actions.onRetryFirstPage,
                actionModifier = cardlessAnchor,
                modifier = Modifier.padding(contentInset),
            )

            is MoviesContent.Empty -> MoviesEmpty(
                message = emptyMessage(content.filter),
                contentInset = contentInset,
                anchorModifier = cardlessAnchor,
            )

            is MoviesContent.Populated -> MoviesGrid(
                items = content.items,
                append = state.append,
                appendGeneration = state.appendGeneration,
                refreshing = state.refreshing,
                contentGeneration = state.contentGeneration,
                columns = columns,
                gridState = gridState,
                contentInset = contentInset,
                contentStartRequester = contentStartRequester,
                navigationRequester = navigationRequester,
                returnRequester = returnRequester,
                firstCardRequester = firstCardRequester,
                filterRowRequester = filterRowRequester,
                lastFocusedMovieId = lastFocusedMovieId,
                onMovieFocused = onMovieFocused,
                focusOwnership = focusOwnership,
                onLoadMore = actions.onLoadMore,
                onRetryAppend = actions.onRetryAppend,
                onMovieSelected = onMovieSelected,
            )
        }
    }
}

@Composable
private fun MoviesHeader(
    totalMovies: Long?,
    loadedCount: Int?,
    filter: MoviesFilter,
    append: MoviesAppendState,
    sort: SortOrder,
    refreshing: Boolean,
    notice: String?,
    contentInset: PaddingValues,
    navigationRequester: FocusRequester,
    sortRequester: FocusRequester,
    refreshRequester: FocusRequester,
    onToggleSort: () -> Unit,
    onRefresh: () -> Unit,
    onFocusChanged: (String, Boolean) -> Unit,
) {
    val colors = IglooTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(contentInset)
            .padding(top = IglooTheme.layout.safeAreaVertical),
        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.xs),
        ) {
            IglooText(
                text = "Movies",
                style = IglooTheme.typography.titleLarge,
                color = colors.foreground,
                modifier = Modifier.semantics { heading() },
            )
            // Counts only, never the titles: this region re-announces whenever the loaded count
            // changes, and a TalkBack user must not have the whole grid read back at them on
            // every appended page (section 12).
            IglooText(
                text = countLine(totalMovies),
                style = IglooTheme.typography.bodyMedium,
                color = colors.mutedForeground,
                modifier = Modifier
                    .testTag("movies_count")
                    .semantics {
                        contentDescription = spokenCount(totalMovies, loadedCount, filter, append)
                        liveRegion = LiveRegionMode.Polite
                    },
            )
            // A refresh that failed with cards still on screen: the failure is over and Refresh
            // is one press away, so this reports rather than offering a second, redundant Retry.
            if (notice != null) {
                IglooNotice(text = notice, modifier = Modifier.testTag("movies_notice"))
            }
        }
        IglooButton(
            // Direction-only on purpose: the backend sorts by title and offers no field choice.
            text = if (sort == SortOrder.Ascending) SORT_ASCENDING_LABEL else SORT_DESCENDING_LABEL,
            labelVariants = listOf(SORT_ASCENDING_LABEL, SORT_DESCENDING_LABEL),
            onClick = onToggleSort,
            variant = IglooButtonVariant.Ghost,
            // Never disabled, for the same focus-tree reason as Refresh below; the view model
            // guards a toggle that lands mid-reload by cancelling the in-flight page.
            enabled = true,
            semanticLabel = "Sort order",
            stateDescription = if (sort == SortOrder.Ascending) "A to Z" else "Z to A",
            actionLabel = if (sort == SortOrder.Ascending) "Sort Z to A" else "Sort A to Z",
            modifier = Modifier
                .testTag("movies_sort")
                .focusRequester(sortRequester)
                .onFocusChanged { onFocusChanged("movies_sort", it.isFocused) }
                .focusProperties { left = navigationRequester },
        )
        IglooButton(
            // A label swap, not a spinner: nothing in the product loops (section 7.2).
            // labelVariants reserves the wider label's width so the button does not resize
            // under its own focus ring the moment it is pressed.
            text = if (refreshing) REFRESHING_LABEL else REFRESH_LABEL,
            labelVariants = listOf(REFRESH_LABEL, REFRESHING_LABEL),
            onClick = onRefresh,
            variant = IglooButtonVariant.Ghost,
            // Deliberately never disabled. IglooButton is focusable only through its clickable
            // branch, so disabling it while refreshing would remove the very node the user is
            // focused on from the focus tree; the view model guards the repeat press instead.
            enabled = true,
            semanticLabel = "Refresh the movie library",
            stateDescription = "Refreshing".takeIf { refreshing },
            modifier = Modifier
                .testTag("movies_refresh")
                .focusRequester(refreshRequester)
                .onFocusChanged { onFocusChanged("movies_refresh", it.isFocused) }
                // A deterministic left chain: Refresh → Sort → the navigation spine.
                .focusProperties { left = sortRequester },
        )
    }
}

@Composable
private fun MoviesFocusHandoffCoordinator(
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

@Composable
private fun MoviesGrid(
    items: List<MoviesGridItem>,
    append: MoviesAppendState,
    appendGeneration: Int,
    refreshing: Boolean,
    contentGeneration: Int,
    columns: Int,
    gridState: LazyGridState,
    contentInset: PaddingValues,
    contentStartRequester: FocusRequester,
    navigationRequester: FocusRequester,
    returnRequester: FocusRequester,
    firstCardRequester: FocusRequester,
    filterRowRequester: FocusRequester,
    lastFocusedMovieId: Long?,
    onMovieFocused: (Long) -> Unit,
    focusOwnership: MoviesFocusOwnership,
    onLoadMore: () -> Unit,
    onRetryAppend: () -> Unit,
    onMovieSelected: ((Long) -> Unit)?,
) {
    // The card focus memory restores to, falling back to the first so the pane always has an
    // anchor — the same contract the rails' entry key follows.
    val entryId = remember(items, lastFocusedMovieId) {
        lastFocusedMovieId?.takeIf { id -> items.any { it.id == id } } ?: items.first().id
    }
    val appendRetryReturnRequester = remember { FocusRequester() }
    var appendRetryFocused by remember { mutableStateOf(false) }
    var appendRetryHandoffPending by remember { mutableStateOf(false) }
    val appendRetryReturnId = lastFocusedMovieId?.takeIf { id -> items.any { it.id == id } }

    // layoutInfo changes on every scroll frame — and on a TV every d-pad press is a scroll frame
    // — so reading it straight from the composable would subscribe the whole grid to a per-frame
    // value. derivedStateOf interposes a boolean that flips twice per page instead.
    //
    // Measured against the real item count, never layoutInfo.totalItemsCount: that count
    // includes the skeleton and error tail, so the threshold would drift as the tail changed
    // shape and would fire against the error card.
    val loadedCount = items.size
    val shouldPrefetch by remember(gridState, columns, loadedCount) {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                ?: return@derivedStateOf false
            last >= loadedCount - columns * PREFETCH_ROWS
        }
    }
    // A successful append rearms this effect even if every returned id overlapped the loaded
    // list. Refresh completion does the same after prefetch has deliberately paused.
    LaunchedEffect(
        shouldPrefetch,
        append,
        appendGeneration,
        contentGeneration,
        refreshing,
    ) {
        if (shouldPrefetch && append == MoviesAppendState.Idle && !refreshing) onLoadMore()
    }

    // Retry is the only focusable tail state. When it starts another request, move focus back to
    // the real card the user came from before disposing Retry; stable movie keys then keep that
    // card focused whether the append succeeds or fails again.
    LaunchedEffect(append, appendRetryHandoffPending, appendRetryReturnId) {
        if (
            append == MoviesAppendState.Loading &&
            appendRetryHandoffPending &&
            appendRetryReturnId != null
        ) {
            appendRetryReturnRequester.requestFocusSafely()
            appendRetryHandoffPending = false
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = gridState,
        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.lg),
        contentPadding = contentInset.asGridPadding(),
        // A lazy grid sizes to its content up to the incoming constraint, so the surface — not
        // the cards — is what has to span the panel for the end inset to be scrolled through.
        modifier = Modifier
            .fillMaxWidth()
            .testTag("movies_grid"),
    ) {
        // One disjoint string key space, so a movie id can never collide with a placeholder's,
        // and stable across appends — which is what keeps focus pinned to the focused card when
        // a page lands underneath it.
        // Whether d-pad down from the last row has anywhere legitimate to go. The skeleton tail
        // is deliberately unfocusable, so without pinning this edge Compose's spatial search
        // leaves the pane entirely and lands in the navigation rail's lower section.
        val lastRowIsTheEdge = append !is MoviesAppendState.Error
        val lastRow = items.lastIndex / columns

        itemsIndexed(items, key = { _, item -> "movie_${item.id}" }) { index, item ->
            IglooPosterCard(
                title = item.title,
                subtitle = item.year,
                imageUrl = item.posterUrl,
                onClick = onMovieSelected?.let { open -> { open(item.id) } },
                // Unspecified so the card fills its grid cell rather than taking the rail's
                // fixed card width and leaving ragged gutters.
                width = Dp.Unspecified,
                modifier = Modifier
                    .withRequester(firstCardRequester.takeIf { index == 0 })
                    .withRequester(contentStartRequester.takeIf { item.id == entryId })
                    .withRequester(returnRequester.takeIf { item.id == entryId })
                    .withRequester(
                        appendRetryReturnRequester.takeIf { item.id == appendRetryReturnId },
                    )
                    .focusProperties {
                        if (index % columns == 0) left = navigationRequester
                        // The chip row is a sibling of the scroll surface, so the first row's
                        // way back up to the selected chip is wired rather than resolved
                        // spatially.
                        if (index < columns) up = filterRowRequester
                        if (lastRowIsTheEdge && index / columns == lastRow) {
                            down = FocusRequester.Cancel
                        }
                    }
                    .onFocusChanged {
                        focusOwnership.onMovieFocusChanged(item.id, it.isFocused)
                        if (it.isFocused) onMovieFocused(item.id)
                    }
                    .testTag("poster_card_${item.id}"),
            )
        }

        when (append) {
            // Skeletons render on Idle as well as Loading: the tail's height is then identical
            // before and during a request, so firing a prefetch never reflows the surface under
            // a focused cell. They are never a d-pad destination and never a TalkBack stop — a
            // node that vanishes when its page lands would drop focus on the floor (section 10)
            // — so d-pad down at the true end is a stable no-op until real cells replace them.
            MoviesAppendState.Idle, MoviesAppendState.Loading -> items(
                count = columns * PREFETCH_ROWS,
                key = { "tail_skeleton_$it" },
            ) { index ->
                IglooSkeletonCell(
                    focused = false,
                    cardAspect = IglooTheme.layout.posterAspect,
                    cardWidth = Dp.Unspecified,
                    modifier = Modifier
                        .testTag("tail_skeleton_$index")
                        .semantics { hideFromAccessibility() },
                )
            }

            // Full width so d-pad down from any column reaches the Retry — the tail's one
            // focusable affordance, and exactly where focus is heading at that moment.
            is MoviesAppendState.Error -> item(
                key = "tail_error",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                IglooInlineError(
                    message = append.message,
                    actionText = "Retry",
                    actionSemanticLabel = "Retry loading more movies",
                    onAction = {
                        appendRetryHandoffPending = appendRetryFocused
                        onRetryAppend()
                    },
                    actionModifier = Modifier
                        .onFocusChanged { appendRetryFocused = it.isFocused }
                        .focusProperties { left = navigationRequester },
                    // Polite, not Assertive: the grid above still works, so this reports on the
                    // tail rather than interrupting (section 12).
                    liveRegionMode = LiveRegionMode.Polite,
                )
            }

            MoviesAppendState.End -> Unit
        }
    }
}

/** Card-geometry placeholders, so focus taken while loading sits where the first card will land. */
@Composable
private fun MoviesGridSkeleton(
    columns: Int,
    contentInset: PaddingValues,
    anchorModifier: Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.lg),
        contentPadding = contentInset.asGridPadding(),
        userScrollEnabled = false,
        modifier = Modifier.fillMaxWidth(),
    ) {
        // Only the first cell is real to focus and TalkBack; the rest are texture.
        item(key = "skeleton_anchor") {
            IglooSkeletonCell(
                focused = focused,
                cardAspect = IglooTheme.layout.posterAspect,
                cardWidth = Dp.Unspecified,
                modifier = anchorModifier
                    .onFocusChanged { focused = it.isFocused }
                    .focusable()
                    .semantics {
                        contentDescription = "Loading movies"
                        liveRegion = LiveRegionMode.Polite
                    },
            )
        }
        items(count = columns * SKELETON_ROWS - 1, key = { "skeleton_$it" }) {
            IglooSkeletonCell(
                focused = false,
                cardAspect = IglooTheme.layout.posterAspect,
                cardWidth = Dp.Unspecified,
                modifier = Modifier.semantics { hideFromAccessibility() },
            )
        }
    }
}

@Composable
private fun MoviesEmpty(
    message: String,
    contentInset: PaddingValues,
    anchorModifier: Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        // Focusable deliberately: the grid is the pane's only content, and an unfocusable empty
        // state would leave the pane with no anchor and break the shell's focus model.
        modifier = Modifier
            .fillMaxWidth()
            .padding(contentInset)
            .focusRing(focused = focused, radius = IglooTheme.radius.lg)
            .then(anchorModifier)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clearAndSetSemantics {
                contentDescription = message
                liveRegion = LiveRegionMode.Polite
            }
            .padding(IglooTheme.spacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        IglooEmpty(icon = IglooIcons.Movies, message = message)
    }
}

/**
 * The pane's horizontal gutter belongs inside the scroll surface (section 8.3), but its vertical
 * values do not: the grid needs room of its own so the focus scale and glow are not cross-axis
 * clipped at the first row, and the safe area below so the last row clears overscan.
 */
@Composable
private fun PaddingValues.asGridPadding(): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(direction),
        end = calculateEndPadding(direction),
        top = IglooTheme.spacing.md,
        bottom = IglooTheme.layout.safeAreaVertical,
    )
}

private fun countLine(totalMovies: Long?): String =
    if (totalMovies == null) "—" else "${NUMBER_FORMAT.format(totalMovies)} ${plural(totalMovies)}"

/** The visible line stays generic; only the spoken form names the active filter. */
private fun spokenCount(
    totalMovies: Long?,
    loadedCount: Int?,
    filter: MoviesFilter,
    appendState: MoviesAppendState,
): String =
    when {
        totalMovies == null -> "Loading the movie library"
        loadedCount == null -> countLine(totalMovies)
        else -> buildString {
            append("Showing $loadedCount of ")
            append("${NUMBER_FORMAT.format(totalMovies)} ${filterNoun(filter, totalMovies)}")
            if (appendState == MoviesAppendState.Loading) {
                append(". Loading more movies.")
            }
        }
    }

private sealed interface MoviesContent {
    data object Loading : MoviesContent
    data class Error(val message: String) : MoviesContent
    data class Empty(val filter: MoviesFilter) : MoviesContent
    data class Populated(val items: List<MoviesGridItem>) : MoviesContent
}

private fun MoviesUiState.toMoviesContent(): MoviesContent = when (val grid = grid) {
    IglooRailState.Loading -> MoviesContent.Loading
    is IglooRailState.Error -> MoviesContent.Error(grid.message)
    is IglooRailState.Loaded -> if (grid.items.isEmpty()) {
        MoviesContent.Empty(filter)
    } else {
        MoviesContent.Populated(grid.items)
    }
}

private fun MoviesContent.containsMovie(movieId: Long): Boolean =
    this is MoviesContent.Populated && items.any { it.id == movieId }

private class MoviesFocusOwnership {
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

private class MoviesFocusHandoffMemory(
    var content: MoviesContent,
    var contentGeneration: Int,
    var silentReconcileGeneration: Int,
)

private fun filterNoun(filter: MoviesFilter, count: Long): String = when (filter) {
    MoviesFilter.All -> plural(count)
    MoviesFilter.Liked -> "liked ${plural(count)}"
    is MoviesFilter.Genre -> "${filter.tag} ${plural(count)}"
}

private fun emptyMessage(filter: MoviesFilter): String = when (filter) {
    MoviesFilter.All -> "No movies found in your library."
    MoviesFilter.Liked ->
        "No liked movies yet. Like a movie from its details page and it will appear here."
    is MoviesFilter.Genre -> "No ${filter.tag} movies in your library."
}

/** Shared with the filter row's chip labels; internal so the split files spell them once. */
internal fun plural(count: Long): String = if (count == 1L) "movie" else "movies"

internal val NUMBER_FORMAT: NumberFormat = NumberFormat.getIntegerInstance()

private const val REFRESH_LABEL = "Refresh"
private const val REFRESHING_LABEL = "Refreshing…"
private const val SORT_ASCENDING_LABEL = "A–Z"
private const val SORT_DESCENDING_LABEL = "Z–A"

/** How close to the end the grid gets before it asks for the next page, in rows. */
private const val PREFETCH_ROWS = 2

private const val SKELETON_ROWS = 3
