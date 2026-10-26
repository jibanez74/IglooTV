package com.igloo.blindpenguincoder.feature.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooInlineError
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.feature.shared.AppendState
import com.igloo.blindpenguincoder.feature.shared.PagedState
import com.igloo.blindpenguincoder.feature.shared.PaneFocusOwnership
import com.igloo.blindpenguincoder.feature.shared.TrackRow
import com.igloo.blindpenguincoder.feature.shared.TrackRowColumn
import com.igloo.blindpenguincoder.feature.shared.TrackRowFocus
import com.igloo.blindpenguincoder.feature.shared.TrackRowRequesters
import com.igloo.blindpenguincoder.feature.shared.TrackRowSkeleton
import com.igloo.blindpenguincoder.feature.shared.TrackRowUi
import com.igloo.blindpenguincoder.feature.shared.asScrollPadding
import com.igloo.blindpenguincoder.feature.shared.hasMoreActions

/**
 * The Tracks tab's flat list: letter headers the eye reads, rows with three actions each, and
 * the paging tail. Row-to-row focus moves are spatial and keep their column; only the edges are
 * wired — the first row's up to the last-focused action button, the last row's down pinned
 * unless the tail is a Retry, the left edge to the spine, the right edge pinned by the row.
 */
@Composable
internal fun TracksList(
    entries: List<TracksEntry>,
    likes: TrackLikesUiState,
    paged: PagedState<TracksEntry>,
    refreshing: Boolean,
    listState: LazyListState,
    contentInset: PaddingValues,
    contentStartRequester: FocusRequester,
    navigationRequester: FocusRequester,
    returnRequester: FocusRequester,
    firstItemRequester: FocusRequester,
    menuReturnRequester: FocusRequester,
    menuOpenFor: Long?,
    upRequester: FocusRequester,
    lastFocusedTrack: TrackFocusMemory?,
    onTrackFocused: (TrackFocusMemory) -> Unit,
    focusOwnership: PaneFocusOwnership,
    canOpenAlbum: Boolean,
    canOpenArtist: Boolean,
    onLoadMore: () -> Unit,
    onRetryAppend: () -> Unit,
    onPlayTrack: (Long) -> Unit,
    onToggleLike: (Long) -> Unit,
    onOpenMore: (TrackRowUi, Rect) -> Unit,
) {
    val trackIds = remember(entries) { entries.mapNotNull { (it as? TracksEntry.Track)?.track?.id } }
    val entryId = remember(trackIds, lastFocusedTrack) {
        lastFocusedTrack?.id?.takeIf { it in trackIds } ?: trackIds.first()
    }
    val entryColumn = lastFocusedTrack?.column?.takeIf { lastFocusedTrack.id == entryId } ?: TrackRowColumn.Play
    val lastTrackIndex = entries.indexOfLast { it is TracksEntry.Track }
    val firstTrackIndex = entries.indexOfFirst { it is TracksEntry.Track }
    val append = paged.append

    val loadedCount = entries.size
    val shouldPrefetch by remember(listState, loadedCount) {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                ?: return@derivedStateOf false
            last >= loadedCount - LIST_PREFETCH_ROWS
        }
    }
    LaunchedEffect(shouldPrefetch, append, paged.appendGeneration, paged.contentGeneration, refreshing) {
        if (shouldPrefetch && append == AppendState.Idle && !refreshing) onLoadMore()
    }

    LazyColumn(
        state = listState,
        contentPadding = contentInset.asScrollPadding(),
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.xs),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("tracks_list"),
    ) {
        itemsIndexed(
            entries,
            key = { _, entry ->
                when (entry) {
                    is TracksEntry.Letter -> "letter_${entry.letter}"
                    is TracksEntry.Track -> "track_${entry.track.id}"
                }
            },
        ) { index, entry ->
            when (entry) {
                // Plain text a TV screen reader never reaches; the letter is folded into the
                // first row under it at mapping time.
                is TracksEntry.Letter -> IglooText(
                    text = entry.letter,
                    style = IglooTheme.typography.titleMedium,
                    color = IglooTheme.colors.primary,
                    modifier = Modifier
                        .padding(
                            start = IglooTheme.spacing.sm,
                            top = if (index == 0) 0.dp else IglooTheme.spacing.md,
                        )
                        .testTag("tracks_letter_${entry.letter}")
                        .clearAndSetSemantics { },
                )

                is TracksEntry.Track -> {
                    val track = entry.track
                    val isEntry = track.id == entryId
                    val requesters = remember { TrackRowRequesters() }
                    val focus = remember(
                        requesters, index, isEntry, entryColumn, menuOpenFor, upRequester, append,
                        firstTrackIndex, lastTrackIndex,
                    ) {
                        TrackRowFocus(
                            requesters = requesters,
                            up = { if (index == firstTrackIndex) upRequester else null },
                            down = {
                                if (index == lastTrackIndex && append !is AppendState.Error) {
                                    FocusRequester.Cancel
                                } else {
                                    null
                                }
                            },
                            left = navigationRequester,
                            riders = { column ->
                                listOfNotNull(
                                    firstItemRequester.takeIf { index == firstTrackIndex && column == TrackRowColumn.Play },
                                    contentStartRequester.takeIf { isEntry && column == TrackRowColumn.Play },
                                    returnRequester.takeIf { isEntry && column == entryColumn },
                                    menuReturnRequester.takeIf { menuOpenFor == track.id && column == TrackRowColumn.More },
                                )
                            },
                        )
                    }
                    TrackRow(
                        track = track,
                        liked = likes.isLiked(track.id),
                        likePending = track.id in likes.pendingIds,
                        focus = focus,
                        onPlay = { onPlayTrack(track.id) },
                        onToggleLike = { onToggleLike(track.id) },
                        onOpenMore = if (track.hasMoreActions(canOpenAlbum, canOpenArtist)) {
                            { bounds -> onOpenMore(track, bounds) }
                        } else {
                            null
                        },
                        onColumnFocused = { column ->
                            focusOwnership.onItemFocusChanged(track.id, true)
                            onTrackFocused(TrackFocusMemory(track.id, column))
                        },
                        modifier = Modifier.onFocusChanged {
                            if (!it.hasFocus) focusOwnership.onItemFocusChanged(track.id, false)
                        },
                    )
                }
            }
        }

        when (append) {
            AppendState.Idle, AppendState.Loading -> items(
                count = LIST_PREFETCH_ROWS,
                key = { "tail_skeleton_$it" },
            ) { index ->
                TrackRowSkeleton(modifier = Modifier.testTag("tail_skeleton_$index"))
            }

            is AppendState.Error -> item(key = "tail_error") {
                IglooInlineError(
                    message = append.message,
                    actionText = "Retry",
                    actionSemanticLabel = "Retry loading more tracks",
                    onAction = onRetryAppend,
                    actionModifier = Modifier.focusProperties {
                        left = navigationRequester
                        right = FocusRequester.Cancel
                    },
                    liveRegionMode = LiveRegionMode.Polite,
                )
            }

            AppendState.End -> Unit
        }
    }
}

/** Row-shaped placeholders under a stub action row, the first Play slot being the anchor. */
// The anchor's modifier, not this composable's; lint's naming rule is about the latter.
@Suppress("ModifierParameter")
@Composable
internal fun TracksListSkeleton(
    contentInset: PaddingValues,
    anchorModifier: Modifier,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(contentInset),
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.xs),
    ) {
        TrackRowSkeleton(anchorModifier = anchorModifier)
        repeat(LIST_SKELETON_ROWS - 1) { TrackRowSkeleton() }
    }
}

/** How close to the end the track list gets before it asks for the next page, in rows. */
private const val LIST_PREFETCH_ROWS = 6
private const val LIST_SKELETON_ROWS = 8
