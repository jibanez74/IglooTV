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
import java.text.NumberFormat

/** What the Movies pane needs from its view model, bundled rather than threaded as six lambdas. */
data class MoviesActions(
    val onRefresh: () -> Unit,
    val onRetryFirstPage: () -> Unit,
    val onRetryAppend: () -> Unit,
    val onLoadMore: () -> Unit,
)

/**
 * The movie library index (docs/design-system.md section 11.4): a heading with the library count
 * and a Refresh action over an infinite-scrolling poster grid.
 *
 * The grid pages itself. Unlike the web client's numbered pagination a d-pad user never sees a
 * page control — scrolling within [PREFETCH_ROWS] rows of the end asks for the next page, and
 * the only paging the screen renders is the tail below the last loaded row.
 *
 * [contentStartRequester] lands on the grid's entry cell — the remembered card if there is one,
 * otherwise the first — so entering the pane puts focus on content rather than on chrome, with
 * Refresh a d-pad press above it. In the states with no cards the anchor moves to whatever the
 * screen draws instead, because the shell's focus model assumes the pane always has somewhere
 * to land.
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
    /** The generation this pane has already scrolled to top for; hoisted so a destination
     *  round trip does not replay the reset and throw away the user's scroll position. */
    handledGeneration: Int,
    onGenerationHandled: (Int) -> Unit,
    onMovieSelected: ((Long) -> Unit)?,
) {
    val columns = IglooTheme.layout.gridColumns
    val grid = state.grid
    val refreshRequester = remember { FocusRequester() }

    Column(
        // No horizontal padding here: the grid carries the pane's gutter as contentPadding so
        // the scroll surface spans the panel (section 8.3). Padding the parent would re-clip it.
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
    ) {
        MoviesHeader(
            totalMovies = state.totalMovies,
            loadedCount = (grid as? IglooRailState.Loaded)?.items?.size,
            refreshing = state.refreshing,
            notice = state.notice,
            contentInset = contentInset,
            navigationRequester = navigationRequester,
            refreshRequester = refreshRequester,
            onRefresh = actions.onRefresh,
        )

        when (grid) {
            is IglooRailState.Loading -> MoviesGridSkeleton(
                columns = columns,
                contentInset = contentInset,
                anchorModifier = Modifier
                    .withRequester(contentStartRequester)
                    .focusProperties { left = navigationRequester },
            )

            is IglooRailState.Error -> IglooInlineError(
                message = grid.message,
                actionText = "Retry",
                actionSemanticLabel = "Retry loading the movie library",
                onAction = actions.onRetryFirstPage,
                actionModifier = Modifier
                    .withRequester(contentStartRequester)
                    .focusProperties { left = navigationRequester },
                modifier = Modifier.padding(contentInset),
            )

            is IglooRailState.Loaded -> if (grid.items.isEmpty()) {
                MoviesEmpty(
                    contentInset = contentInset,
                    anchorModifier = Modifier
                        .withRequester(contentStartRequester)
                        .focusProperties { left = navigationRequester },
                )
            } else {
                MoviesGrid(
                    items = grid.items,
                    append = state.append,
                    contentGeneration = state.contentGeneration,
                    handledGeneration = handledGeneration,
                    onGenerationHandled = onGenerationHandled,
                    columns = columns,
                    gridState = gridState,
                    contentInset = contentInset,
                    contentStartRequester = contentStartRequester,
                    navigationRequester = navigationRequester,
                    returnRequester = returnRequester,
                    refreshRequester = refreshRequester,
                    lastFocusedMovieId = lastFocusedMovieId,
                    onMovieFocused = onMovieFocused,
                    onLoadMore = actions.onLoadMore,
                    onRetryAppend = actions.onRetryAppend,
                    onMovieSelected = onMovieSelected,
                )
            }
        }
    }
}

@Composable
private fun MoviesHeader(
    totalMovies: Long?,
    loadedCount: Int?,
    refreshing: Boolean,
    notice: String?,
    contentInset: PaddingValues,
    navigationRequester: FocusRequester,
    refreshRequester: FocusRequester,
    onRefresh: () -> Unit,
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
                        contentDescription = spokenCount(totalMovies, loadedCount)
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
                .focusProperties { left = navigationRequester },
        )
    }
}

@Composable
private fun MoviesGrid(
    items: List<MoviesGridItem>,
    append: MoviesAppendState,
    contentGeneration: Int,
    handledGeneration: Int,
    onGenerationHandled: (Int) -> Unit,
    columns: Int,
    gridState: LazyGridState,
    contentInset: PaddingValues,
    contentStartRequester: FocusRequester,
    navigationRequester: FocusRequester,
    returnRequester: FocusRequester,
    refreshRequester: FocusRequester,
    lastFocusedMovieId: Long?,
    onMovieFocused: (Long) -> Unit,
    onLoadMore: () -> Unit,
    onRetryAppend: () -> Unit,
    onMovieSelected: ((Long) -> Unit)?,
) {
    // The card focus memory restores to, falling back to the first so the pane always has an
    // anchor — the same contract the rails' entry key follows.
    val entryId = remember(items, lastFocusedMovieId) {
        lastFocusedMovieId?.takeIf { id -> items.any { it.id == id } } ?: items.first().id
    }

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
    // Edge-triggered on the boolean rather than run every frame. The view model's guards make a
    // repeat call idempotent regardless, because a focus change or a resize can re-run this.
    LaunchedEffect(shouldPrefetch) {
        if (shouldPrefetch) onLoadMore()
    }

    // A wholesale replacement — a Refresh, or the first page landing — returns the user to the
    // top with focus on the entry cell. An append never bumps the generation, so a page landing
    // under a scrolled user moves nothing. The handled value is hoisted above this composable
    // because leaving and re-entering the pane would otherwise replay the reset.
    LaunchedEffect(contentGeneration) {
        if (contentGeneration != handledGeneration) {
            onGenerationHandled(contentGeneration)
            gridState.scrollToItem(0)
            contentStartRequester.requestFocusSafely()
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
                    .withRequester(contentStartRequester.takeIf { item.id == entryId })
                    .withRequester(returnRequester.takeIf { item.id == entryId })
                    .focusProperties {
                        if (index % columns == 0) left = navigationRequester
                        // The header is a sibling of the scroll surface, so the first row's way
                        // back up to Refresh is wired rather than resolved spatially.
                        if (index < columns) up = refreshRequester
                        if (lastRowIsTheEdge && index / columns == lastRow) {
                            down = FocusRequester.Cancel
                        }
                    }
                    .onFocusChanged { if (it.isFocused) onMovieFocused(item.id) }
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
                    modifier = if (index == 0 && append == MoviesAppendState.Loading) {
                        // Only the first cell speaks; the rest are texture.
                        Modifier.clearAndSetSemantics {
                            contentDescription = "Loading more movies"
                            liveRegion = LiveRegionMode.Polite
                        }
                    } else {
                        Modifier.semantics { hideFromAccessibility() }
                    },
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
                    onAction = onRetryAppend,
                    actionModifier = Modifier.focusProperties { left = navigationRequester },
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
private fun MoviesEmpty(contentInset: PaddingValues, anchorModifier: Modifier) {
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
                contentDescription = EMPTY_MESSAGE
                liveRegion = LiveRegionMode.Polite
            }
            .padding(IglooTheme.spacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        IglooEmpty(icon = IglooIcons.Movies, message = EMPTY_MESSAGE)
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

private fun spokenCount(totalMovies: Long?, loadedCount: Int?): String = when {
    totalMovies == null -> "Loading the movie library"
    loadedCount == null -> countLine(totalMovies)
    else -> "Showing $loadedCount of ${NUMBER_FORMAT.format(totalMovies)} ${plural(totalMovies)}"
}

private fun plural(count: Long): String = if (count == 1L) "movie" else "movies"

private val NUMBER_FORMAT: NumberFormat = NumberFormat.getIntegerInstance()

private const val EMPTY_MESSAGE = "No movies found in your library."
private const val REFRESH_LABEL = "Refresh"
private const val REFRESHING_LABEL = "Refreshing…"

/** How close to the end the grid gets before it asks for the next page, in rows. */
private const val PREFETCH_ROWS = 2

private const val SKELETON_ROWS = 3
