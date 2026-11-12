package com.igloo.blindpenguincoder.feature.watchrooms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.LifecycleStartEffect
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.navigation.IglooDestination
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.core.ui.SkeletonAnchor
import com.igloo.blindpenguincoder.core.ui.countNoun
import com.igloo.blindpenguincoder.core.ui.formatCount
import com.igloo.blindpenguincoder.core.ui.withRequester
import com.igloo.blindpenguincoder.feature.shared.PaneCardGrid
import com.igloo.blindpenguincoder.feature.shared.PaneFirstPageError
import com.igloo.blindpenguincoder.feature.shared.PaneFocusHandoffCoordinator
import com.igloo.blindpenguincoder.feature.shared.PaneFocusOwnership
import com.igloo.blindpenguincoder.feature.shared.PaneHeader
import com.igloo.blindpenguincoder.feature.shared.PaneListContent
import com.igloo.blindpenguincoder.feature.shared.PaneRefreshButton
import com.igloo.blindpenguincoder.feature.shared.PaneSkeletonGrid
import com.igloo.blindpenguincoder.feature.shared.paneCardlessAnchor
import com.igloo.blindpenguincoder.feature.shared.paneCountLine
import com.igloo.blindpenguincoder.feature.shared.toPaneListContent

/** The header button's resting text; the spoken label is [REFRESH_ROOMS_SPOKEN]. */
internal const val REFRESH_ROOMS_LABEL = "Refresh Watch Rooms"
internal const val REFRESH_ROOMS_SPOKEN = "Refresh watch rooms"

/**
 * The Watch Rooms pane (docs/design-system.md section 11.10): a heading with the room count and
 * the Refresh action, over the rooms the signed-in profile hosts or was invited to — wide cards,
 * two to a row — or, with none, the empty scene.
 *
 * The list is re-read whenever the pane comes on screen: opening it, and the app returning to
 * the foreground on it. That is the start effect here rather than the host's, which would fetch
 * a list nobody is looking at on every foreground.
 *
 * Focus: [contentStartRequester] lands on the entry card — the remembered one if it is still in
 * the list, otherwise the first — with Refresh one press above it. While loading it is the
 * first skeleton cell, and after a failed first load the error card's Retry. With no rooms it
 * is **the Refresh button**: the empty scene offers nothing to do, so the anchor goes to the one
 * control that does. The left column and the header exit to the spine at [navigationRequester];
 * every other edge is pinned. Nothing opens over the pane yet, so it has no return requester.
 */
@Composable
fun WatchRoomsScreen(
    state: WatchRoomsUiState,
    actions: WatchRoomsActions,
    contentInset: PaddingValues,
    gridState: LazyGridState,
    contentStartRequester: FocusRequester,
    navigationRequester: FocusRequester,
    lastFocusedId: Long?,
    onItemFocused: (Long) -> Unit,
    spokenAccessibilityEnabled: Boolean,
) {
    val content = state.rooms.toPaneListContent()
    val rooms = (state.rooms as? IglooRailState.Loaded)?.items.orEmpty()
    val empty = content is PaneListContent.Empty
    val refreshRequester = remember { FocusRequester() }
    val firstCardRequester = remember { FocusRequester() }
    val cardlessHandoffRequester = remember { FocusRequester() }
    val focusOwnership = remember { PaneFocusOwnership(tabKeys = emptySet()) }

    LifecycleStartEffect(actions) {
        actions.onShown()
        onStopOrDispose { }
    }

    Column(
        // No horizontal padding here: the grid carries the pane's gutter as contentPadding so
        // the scroll surface spans the panel (section 8.3).
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
    ) {
        PaneHeader(
            title = IglooDestination.WatchRooms.label,
            countText = paneCountLine(rooms.size.toLong().takeIf { state.rooms is IglooRailState.Loaded }) {
                countNoun(it, "room")
            },
            countDescription = spokenCount(state.rooms),
            countTag = "watch_rooms_count",
            notice = state.notice,
            contentInset = contentInset,
            noticeTag = "watch_rooms_notice",
        ) {
            PaneRefreshButton(
                refreshing = state.refreshing,
                semanticLabel = REFRESH_ROOMS_SPOKEN,
                onRefresh = actions.onRefresh,
                label = REFRESH_ROOMS_LABEL,
                restingState = NO_ROOMS_SPOKEN.takeIf { empty },
                modifier = Modifier
                    .focusRequester(refreshRequester)
                    .withRequester(contentStartRequester.takeIf { empty })
                    .withRequester(cardlessHandoffRequester.takeIf { empty })
                    .onFocusChanged {
                        focusOwnership.onChromeFocusChanged(REFRESH_FOCUS_KEY, it.isFocused)
                    }
                    .focusProperties {
                        left = navigationRequester
                        up = FocusRequester.Cancel
                        right = FocusRequester.Cancel
                        // With no rooms nothing below takes focus, and an unpinned edge lets the
                        // spatial search leave the pane for the navigation rail's footer.
                        down = if (empty) FocusRequester.Cancel else contentStartRequester
                    },
            )
        }

        val cardlessAnchor = Modifier.paneCardlessAnchor(
            contentStartRequester = contentStartRequester,
            returnRequester = null,
            cardlessHandoffRequester = cardlessHandoffRequester,
            navigationRequester = navigationRequester,
            upRequester = refreshRequester,
            focusOwnership = focusOwnership,
        )

        PaneFocusHandoffCoordinator(
            resetKey = Unit,
            content = content,
            contentGeneration = state.contentGeneration,
            scrollToTop = { gridState.scrollToItem(0) },
            firstItemRequester = firstCardRequester,
            cardlessHandoffRequester = cardlessHandoffRequester,
            focusOwnership = focusOwnership,
            silentReconcileGeneration = state.reconcileGeneration,
            containsItem = { _, id -> rooms.any { it.id == id } },
            screenReader = spokenAccessibilityEnabled,
        )

        val columns = IglooTheme.layout.roomGridColumns
        when (content) {
            PaneListContent.Loading -> PaneSkeletonGrid(
                columns = columns,
                contentInset = contentInset,
                anchorCell = {
                    SkeletonAnchor(cardlessAnchor, loadingLabel = "Loading watch rooms") { focused, modifier ->
                        WatchRoomCardSkeleton(focused, modifier)
                    }
                },
                textureCell = {
                    WatchRoomCardSkeleton(
                        focused = false,
                        modifier = Modifier.semantics { hideFromAccessibility() },
                    )
                },
            )

            is PaneListContent.Error -> PaneFirstPageError(
                message = content.message,
                retryLabel = "Retry loading watch rooms",
                onRetry = actions.onRefresh,
                anchorModifier = cardlessAnchor,
                contentInset = contentInset,
            )

            PaneListContent.Empty -> WatchRoomsEmptyScene(contentInset)

            PaneListContent.Populated -> PaneCardGrid(
                items = rooms,
                itemId = { it.id },
                columns = columns,
                gridState = gridState,
                screenReader = spokenAccessibilityEnabled,
                contentInset = contentInset,
                contentStartRequester = contentStartRequester,
                navigationRequester = navigationRequester,
                returnRequester = null,
                firstItemRequester = firstCardRequester,
                upRequester = refreshRequester,
                lastFocusedId = lastFocusedId,
                onItemFocused = onItemFocused,
                focusOwnership = focusOwnership,
                testTag = "watch_rooms_grid",
                cardTag = { "watch_room_card_${it.id}" },
            ) { room, modifier ->
                WatchRoomCard(room = room, modifier = modifier)
            }
        }
    }
}

/**
 * The header's spoken line. The loading phrase is not the skeleton anchor's own "Loading watch
 * rooms": two nodes speaking the same words is the redundant announcement section 12 rules out.
 */
private fun spokenCount(rooms: IglooRailState<WatchRoomUi>): String = when (rooms) {
    IglooRailState.Loading -> "Loading your watch rooms"
    is IglooRailState.Error -> "Watch rooms unavailable"
    is IglooRailState.Loaded -> {
        val count = rooms.items.size.toLong()
        "${formatCount(count)} ${countNoun(count, "watch room")}"
    }
}

/** The key the Refresh button reports its focus under. */
private const val REFRESH_FOCUS_KEY = "watch_rooms_refresh"
