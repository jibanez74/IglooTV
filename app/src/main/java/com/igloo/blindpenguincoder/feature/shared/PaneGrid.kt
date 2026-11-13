package com.igloo.blindpenguincoder.feature.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.unit.Dp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.AnnouncedLazyMove
import com.igloo.blindpenguincoder.core.ui.IglooInlineError
import com.igloo.blindpenguincoder.core.ui.IglooSkeletonAnchorCell
import com.igloo.blindpenguincoder.core.ui.IglooSkeletonTextureCell
import com.igloo.blindpenguincoder.core.ui.requestFocusSafely
import com.igloo.blindpenguincoder.core.ui.withRequester

/**
 * A pane's infinite-scrolling card grid (docs/design-system.md sections 11.4 and 11.5): the
 * library panes' posters and the Music pane's musician and album cards. It pages itself — within
 * [GRID_PREFETCH_ROWS] rows of the end it asks for the next page — and the only paging it renders
 * is the tail below the last loaded row. The cards, their focus and their edges are
 * [PaneCardGrid]'s.
 */
@Composable
internal fun <T> PaneGrid(
    items: List<T>,
    itemId: (T) -> Long,
    paged: PagedState<T>,
    refreshing: Boolean,
    columns: Int,
    gridState: LazyGridState,
    screenReader: Boolean,
    contentInset: PaddingValues,
    contentStartRequester: FocusRequester,
    navigationRequester: FocusRequester,
    returnRequester: FocusRequester,
    firstItemRequester: FocusRequester,
    upRequester: FocusRequester,
    lastFocusedId: Long?,
    onItemFocused: (Long) -> Unit,
    focusOwnership: PaneFocusOwnership,
    onLoadMore: () -> Unit,
    onRetryAppend: () -> Unit,
    retryLabel: String,
    testTag: String,
    cardTag: (T) -> String,
    cardAspect: Float,
    artworkRadius: Dp = IglooTheme.radius.lg,
    card: @Composable (item: T, modifier: Modifier) -> Unit,
) {
    val appendRetryReturnId = remember(items, lastFocusedId) {
        items.rememberedId(lastFocusedId, itemId)
    }
    val appendRetryReturnRequester = remember { FocusRequester() }
    var appendRetryFocused by remember { mutableStateOf(false) }
    var appendRetryHandoffPending by remember { mutableStateOf(false) }
    val append = paged.append

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
            last >= loadedCount - columns * GRID_PREFETCH_ROWS
        }
    }
    // A successful append rearms this effect even if every returned id overlapped the loaded
    // list. Refresh completion does the same after prefetch has deliberately paused.
    LaunchedEffect(
        shouldPrefetch,
        append,
        paged.appendGeneration,
        paged.contentGeneration,
        refreshing,
    ) {
        if (shouldPrefetch && append == AppendState.Idle && !refreshing) onLoadMore()
    }

    // Retry is the only focusable tail state. When it starts another request, move focus back to
    // the real card the user came from before disposing Retry; stable item keys then keep that
    // card focused whether the append succeeds or fails again.
    LaunchedEffect(append, appendRetryHandoffPending, appendRetryReturnId) {
        if (
            append == AppendState.Loading &&
            appendRetryHandoffPending &&
            appendRetryReturnId != null
        ) {
            appendRetryReturnRequester.requestFocusSafely()
            appendRetryHandoffPending = false
        }
    }

    PaneCardGrid(
        items = items,
        itemId = itemId,
        columns = columns,
        gridState = gridState,
        screenReader = screenReader,
        contentInset = contentInset,
        contentStartRequester = contentStartRequester,
        navigationRequester = navigationRequester,
        returnRequester = returnRequester,
        firstItemRequester = firstItemRequester,
        upRequester = upRequester,
        lastFocusedId = lastFocusedId,
        onItemFocused = onItemFocused,
        focusOwnership = focusOwnership,
        testTag = testTag,
        cardTag = cardTag,
        // Whether d-pad down from the last row has anywhere legitimate to go: only the tail's
        // Retry is focusable.
        lastRowPinsDown = append !is AppendState.Error,
        rememberedItemRequester = appendRetryReturnRequester,
        tail = {
            when (append) {
                // Skeletons render on Idle as well as Loading: the tail's height is then identical
                // before and during a request, so firing a prefetch never reflows the surface under
                // a focused cell. They are never a d-pad destination and never a TalkBack stop — a
                // node that vanishes when its page lands would drop focus on the floor (section 10)
                // — so d-pad down at the true end is a stable no-op until real cells replace them.
                AppendState.Idle, AppendState.Loading -> items(
                    count = columns * GRID_PREFETCH_ROWS,
                    key = { "tail_skeleton_$it" },
                ) { index ->
                    IglooSkeletonTextureCell(
                        cardAspect = cardAspect,
                        cardWidth = Dp.Unspecified,
                        artworkRadius = artworkRadius,
                        modifier = Modifier.testTag("tail_skeleton_$index"),
                    )
                }

                // Full width so d-pad down from any column reaches the Retry — the tail's one
                // focusable affordance, and exactly where focus is heading at that moment.
                is AppendState.Error -> item(
                    key = "tail_error",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    IglooInlineError(
                        message = append.message,
                        actionText = "Retry",
                        actionSemanticLabel = retryLabel,
                        onAction = {
                            appendRetryHandoffPending = appendRetryFocused
                            onRetryAppend()
                        },
                        actionModifier = Modifier
                            .onFocusChanged { appendRetryFocused = it.isFocused }
                            .focusProperties {
                                left = navigationRequester
                                right = FocusRequester.Cancel
                            },
                        // Polite, not Assertive: the grid above still works, so this reports on the
                        // tail rather than interrupting (section 12).
                        liveRegionMode = LiveRegionMode.Polite,
                    )
                }

                AppendState.End -> Unit
            }
        },
        card = card,
    )
}

/** A card's lazy key; see [PaneCardGrid]'s items for why it is a string. */
private fun cardKey(id: Long): String = "item_$id"

/** The remembered card's id, while that card is still loaded. */
private fun <T> List<T>.rememberedId(lastFocusedId: Long?, itemId: (T) -> Long): Long? =
    lastFocusedId?.takeIf { id -> any { itemId(it) == id } }

/**
 * A pane's card grid, whatever feeds it: [PaneGrid]'s pages, or a list that arrives whole (the
 * Watch Rooms pane, section 11.10). [tail] draws whatever follows the last card.
 *
 * Focus: [contentStartRequester] and [returnRequester] ride the entry card — the remembered one
 * if it is on screen, otherwise the first card on screen — so the pane always has an anchor a
 * request can land on and an overlay's Back lands on the card that opened it;
 * [rememberedItemRequester] rides the remembered card only. The left column exits to
 * [navigationRequester], the first row climbs to [upRequester], the right edge is pinned, and so
 * is the bottom while [lastRowPinsDown] holds. While a screen reader runs ([screenReader]), Up and
 * Down into a row that is not on screen yet go through [AnnouncedLazyMove], so TalkBack follows
 * them. [card] draws one item with the modifier that carries all of that.
 */
@Composable
internal fun <T> PaneCardGrid(
    items: List<T>,
    itemId: (T) -> Long,
    columns: Int,
    gridState: LazyGridState,
    screenReader: Boolean,
    contentInset: PaddingValues,
    contentStartRequester: FocusRequester,
    navigationRequester: FocusRequester,
    returnRequester: FocusRequester?,
    firstItemRequester: FocusRequester,
    upRequester: FocusRequester,
    lastFocusedId: Long?,
    onItemFocused: (Long) -> Unit,
    focusOwnership: PaneFocusOwnership,
    testTag: String,
    cardTag: (T) -> String,
    lastRowPinsDown: Boolean = true,
    rememberedItemRequester: FocusRequester? = null,
    tail: LazyGridScope.() -> Unit = {},
    card: @Composable (item: T, modifier: Modifier) -> Unit,
) {
    // The card focus memory restores to, falling back to the first so the pane always has an
    // anchor — the same contract the rails' entry key follows.
    val rememberedId = remember(items, lastFocusedId) { items.rememberedId(lastFocusedId, itemId) }
    // ...but only a card the grid has on screen. A requester can only land on a composed node,
    // and the lazy grid composes the visible rows alone: a remembered card that is still loaded
    // yet scrolled away — a card from the Genres tab after the All tab lands at the top — left
    // the anchor on nothing, and the strip's Down, the rail row and its Right all went nowhere.
    // Before the first layout nothing is known to be on screen, so the memory stands.
    val entryId by remember(gridState, items, rememberedId) {
        derivedStateOf(structuralEqualityPolicy()) {
            val onScreen = gridState.layoutInfo.visibleItemsInfo
            val remembered = rememberedId?.takeIf { id -> onScreen.any { it.key == cardKey(id) } }
            val firstOnScreen = onScreen.firstNotNullOfOrNull { info ->
                items.getOrNull(info.index)?.let(itemId)?.takeIf { cardKey(it) == info.key }
            }
            remembered ?: firstOnScreen ?: rememberedId ?: itemId(items.first())
        }
    }
    val itemRequesters = remember(items) { items.associate { itemId(it) to FocusRequester() } }

    val currentItems by rememberUpdatedState(items)
    val currentRequesters by rememberUpdatedState(itemRequesters)
    val currentItemId by rememberUpdatedState(itemId)
    val currentColumns by rememberUpdatedState(columns)
    val screenReaderOn by rememberUpdatedState(screenReader)
    val scope = rememberCoroutineScope()
    val rowMove = remember(gridState, focusOwnership) {
        val focusedIndex = {
            val focusedId = focusOwnership.focusedItemId
            if (focusedId == null) -1 else currentItems.indexOfFirst { currentItemId(it) == focusedId }
        }
        AnnouncedLazyMove(
            scope = scope,
            scrollable = gridState,
            screenReader = { screenReaderOn },
            focusedIndex = focusedIndex,
            lastIndex = { currentItems.lastIndex },
            // Placed with visible bounds: an accessibility snapshot only holds nodes that show
            // on screen, and after a scroll to a line the grid still lists the line above it
            // while it sits wholly outside the viewport.
            isPlaced = { index ->
                val info = gridState.layoutInfo
                info.visibleItemsInfo.any {
                    it.index == index &&
                        it.offset.y + it.size.height > info.viewportStartOffset &&
                        it.offset.y < info.viewportEndOffset
                }
            },
            // Half of the target row, while at least a quarter of the origin row stays.
            revealDistance = { forward ->
                val info = gridState.layoutInfo
                val visible = info.visibleItemsInfo
                val originIndex = focusedIndex()
                val origin = visible.firstOrNull { it.index == originIndex }
                val row = visible.filter { it.row == origin?.row }.ifEmpty { visible }
                val height = row.maxOfOrNull { it.size.height } ?: 0
                val pitch = height + info.mainAxisItemSpacing
                val distance = when {
                    origin == null -> pitch / 2
                    forward -> {
                        val targetTop = origin.offset.y + pitch
                        val keepOrigin = origin.offset.y + origin.size.height - height / 4
                        (targetTop + height / 2 - info.viewportEndOffset)
                            .coerceIn(0, (keepOrigin - info.viewportStartOffset).coerceAtLeast(0))
                    }
                    else -> {
                        val targetBottom = origin.offset.y - info.mainAxisItemSpacing
                        val keepOrigin = origin.offset.y + height / 4
                        (info.viewportStartOffset - (targetBottom - height / 2))
                            .coerceIn(0, (info.viewportEndOffset - keepOrigin).coerceAtLeast(0))
                    }
                }
                if (forward) distance.toFloat() else -distance.toFloat()
            },
            // The whole row, against the edge the d-pad was heading for.
            settleDistance = { forward ->
                val info = gridState.layoutInfo
                val visible = info.visibleItemsInfo
                val index = focusedIndex()
                val focused = visible.firstOrNull { it.index == index }
                val row = visible.filter { it.row == focused?.row }
                when {
                    focused == null -> 0f
                    forward -> (row.maxOf { it.offset.y + it.size.height } - info.viewportEndOffset)
                        .coerceAtLeast(0).toFloat()
                    else -> (row.minOf { it.offset.y } - info.viewportStartOffset)
                        .coerceAtMost(0).toFloat()
                }
            },
            requesterFor = { index ->
                currentItems.getOrNull(index)?.let { currentRequesters[currentItemId(it)] }
            },
            stillOnCourse = { origin, target ->
                val now = focusedIndex()
                now == origin || (now >= 0 && now / currentColumns == target / currentColumns)
            },
        )
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = gridState,
        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.lg),
        contentPadding = contentInset.asScrollPadding(),
        // A lazy grid sizes to its content up to the incoming constraint, so the surface — not
        // the cards — is what has to span the panel for the end inset to be scrolled through.
        modifier = Modifier
            .fillMaxWidth()
            .onPreviewKeyEvent {
                rowMove.onPreviewKey(it, Key.DirectionDown, Key.DirectionUp, columns)
            }
            .testTag(testTag),
    ) {
        // Nothing below the last row is focusable while it is pinned, so without the pin
        // Compose's spatial search leaves the pane entirely and lands in the navigation rail's
        // lower section.
        val lastRow = items.lastIndex / columns

        // One disjoint string key space, so an item id can never collide with a placeholder's,
        // and stable across appends — which is what keeps focus pinned to the focused card when
        // a page lands underneath it.
        itemsIndexed(items, key = { _, item -> cardKey(itemId(item)) }) { index, item ->
            val id = itemId(item)
            card(
                item,
                Modifier
                    .focusRequester(itemRequesters.getValue(id))
                    .withRequester(firstItemRequester.takeIf { index == 0 })
                    .withRequester(contentStartRequester.takeIf { id == entryId })
                    .withRequester(returnRequester.takeIf { id == entryId })
                    .withRequester(rememberedItemRequester.takeIf { id == rememberedId })
                    .focusProperties {
                        if (index % columns == 0) left = navigationRequester
                        // The rows above are siblings of the scroll surface, so the first row's
                        // way back up to the strip is wired rather than resolved spatially.
                        if (index < columns) up = upRequester
                        if (lastRowPinsDown && index / columns == lastRow) {
                            down = FocusRequester.Cancel
                        }
                        // The grid is the panel's right edge, so the last column has nowhere
                        // legitimate to go — and neither has the final card of a partial last
                        // row, whose remaining cells are unfocusable tail skeletons. Unpinned,
                        // the search leaves the grid and resolves against the pane's siblings,
                        // landing back on the rows above.
                        if (index % columns == columns - 1 || index == items.lastIndex) {
                            right = FocusRequester.Cancel
                        }
                    }
                    .onFocusChanged {
                        focusOwnership.onItemFocusChanged(id, it.isFocused)
                        if (it.isFocused) onItemFocused(id)
                    }
                    .testTag(cardTag(item)),
            )
        }

        tail()
    }
}

/** Card-geometry placeholders, so focus taken while loading sits where the first card will land. */
// The anchor's modifier, not this composable's; lint's naming rule is about the latter.
@Suppress("ModifierParameter")
@Composable
internal fun PaneGridSkeleton(
    columns: Int,
    contentInset: PaddingValues,
    loadingLabel: String,
    anchorModifier: Modifier,
    cardAspect: Float,
    artworkRadius: Dp = IglooTheme.radius.lg,
) {
    PaneSkeletonGrid(
        columns = columns,
        contentInset = contentInset,
        anchorCell = {
            IglooSkeletonAnchorCell(
                anchorModifier = anchorModifier,
                loadingLabel = loadingLabel,
                cardAspect = cardAspect,
                cardWidth = Dp.Unspecified,
                artworkRadius = artworkRadius,
            )
        },
        textureCell = {
            IglooSkeletonTextureCell(
                cardAspect = cardAspect,
                cardWidth = Dp.Unspecified,
                artworkRadius = artworkRadius,
            )
        },
    )
}

/**
 * [SKELETON_ROWS] rows of placeholders on [PaneCardGrid]'s geometry. Only the first cell,
 * [anchorCell], is real to focus and TalkBack; the rest are [textureCell]s.
 */
@Composable
internal fun PaneSkeletonGrid(
    columns: Int,
    contentInset: PaddingValues,
    anchorCell: @Composable () -> Unit,
    textureCell: @Composable () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.lg),
        contentPadding = contentInset.asScrollPadding(),
        userScrollEnabled = false,
        modifier = Modifier.fillMaxWidth(),
    ) {
        item(key = "skeleton_anchor") { anchorCell() }
        items(count = columns * SKELETON_ROWS - 1, key = { "skeleton_$it" }) { textureCell() }
    }
}
