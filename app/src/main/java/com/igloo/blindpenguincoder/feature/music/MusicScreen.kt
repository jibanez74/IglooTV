package com.igloo.blindpenguincoder.feature.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooButton
import com.igloo.blindpenguincoder.core.ui.IglooButtonVariant
import com.igloo.blindpenguincoder.core.ui.IglooIcons
import com.igloo.blindpenguincoder.core.ui.IglooInlineError
import com.igloo.blindpenguincoder.core.ui.IglooPosterCard
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.countNoun
import com.igloo.blindpenguincoder.core.ui.requestFocusSafely
import com.igloo.blindpenguincoder.feature.shared.AppendState
import com.igloo.blindpenguincoder.feature.shared.PagedState
import com.igloo.blindpenguincoder.feature.shared.PaneEmpty
import com.igloo.blindpenguincoder.feature.shared.PaneFirstPageError
import com.igloo.blindpenguincoder.feature.shared.PaneFocusHandoffCoordinator
import com.igloo.blindpenguincoder.feature.shared.PaneFocusOwnership
import com.igloo.blindpenguincoder.feature.shared.PaneGrid
import com.igloo.blindpenguincoder.feature.shared.PaneGridSkeleton
import com.igloo.blindpenguincoder.feature.shared.PaneHeader
import com.igloo.blindpenguincoder.feature.shared.PaneRefreshButton
import com.igloo.blindpenguincoder.feature.shared.PaneTabRow
import com.igloo.blindpenguincoder.feature.shared.TabPresentation
import com.igloo.blindpenguincoder.feature.shared.asScrollPadding
import com.igloo.blindpenguincoder.feature.shared.TrackRow
import com.igloo.blindpenguincoder.feature.shared.TrackRowColumn
import com.igloo.blindpenguincoder.feature.shared.TrackRowFocus
import com.igloo.blindpenguincoder.feature.shared.TrackRowMenu
import com.igloo.blindpenguincoder.feature.shared.TrackRowRequesters
import com.igloo.blindpenguincoder.feature.shared.TrackRowSkeleton
import com.igloo.blindpenguincoder.feature.shared.TrackRowUi
import com.igloo.blindpenguincoder.feature.shared.hasMoreActions
import com.igloo.blindpenguincoder.feature.shared.loadedItems
import com.igloo.blindpenguincoder.feature.shared.paneCardlessAnchor
import com.igloo.blindpenguincoder.feature.shared.paneCountLine
import com.igloo.blindpenguincoder.feature.shared.showingCountLine

/** What the Music pane needs from its view model, bundled rather than threaded as ten lambdas. */
data class MusicActions(
    val onRefresh: () -> Unit,
    val onRetryFirstPage: () -> Unit,
    val onRetryAppend: () -> Unit,
    val onLoadMore: () -> Unit,
    /** A tab taking focus — debounced by the view model, because a slide crosses every tab. */
    val onSelectTab: (MusicTab) -> Unit,
    /** A press on a tab: deliberate, so it loads at once. */
    val onPressTab: (MusicTab) -> Unit,
    val onPlayTrack: (Long) -> Unit,
    val onPlayAll: () -> Unit,
    val onShuffleAll: () -> Unit,
    val onToggleLike: (Long) -> Unit,
)

/**
 * Which row and which of its three controls the Tracks tab last had focus on, so a return —
 * from the player, or from an album opened through More — lands on the exact control that led
 * away. Saved as `"id:column"`.
 */
data class TrackFocusMemory(val id: Long, val column: TrackRowColumn) {
    companion object {
        val Saver: Saver<TrackFocusMemory?, String> = Saver(
            save = { memory -> memory?.let { "${it.id}:${it.column.name}" } ?: "" },
            restore = { saved ->
                saved.split(':').takeIf { it.size == 2 }?.let { (id, column) ->
                    runCatching { TrackFocusMemory(id.toLong(), TrackRowColumn.valueOf(column)) }.getOrNull()
                }
            },
        )
    }
}

/**
 * The music library index (docs/design-system.md section 11.5): a heading with the selected
 * section's count and a Refresh action, a tab strip (Musicians · Albums · Tracks), and one
 * infinite-scrolling surface per section — circular cards, square cards, or the flat track list
 * with letter headers and Play all / Shuffle all above it. Every section keeps its own pages,
 * scroll position and focus memory, so a switch shows what the section already holds and can
 * never fail.
 *
 * The focus contract is the Movies pane's: [contentStartRequester] lands on the selected
 * section's entry item, the strip is one press above it and the header a press above that, the
 * left column exits to the spine, the right edge is pinned, and the cardless states move the
 * anchor to whatever the section draws instead. [returnRequester] rides the entry item — on the
 * Tracks tab, the remembered control of the entry row — so an overlay's Back lands exactly
 * where the user left.
 */
@Composable
fun MusicScreen(
    state: MusicUiState,
    likes: TrackLikesUiState,
    actions: MusicActions,
    contentInset: PaddingValues,
    musiciansGridState: LazyGridState,
    albumsGridState: LazyGridState,
    tracksListState: LazyListState,
    contentStartRequester: FocusRequester,
    navigationRequester: FocusRequester,
    returnRequester: FocusRequester,
    lastFocusedMusicianId: Long?,
    onMusicianFocused: (Long) -> Unit,
    lastFocusedAlbumId: Long?,
    onAlbumFocused: (Long) -> Unit,
    lastFocusedTrack: TrackFocusMemory?,
    onTrackFocused: (TrackFocusMemory) -> Unit,
    onMusicianSelected: ((Long) -> Unit)?,
    onAlbumSelected: ((Long) -> Unit)?,
) {
    val columns = IglooTheme.layout.gridColumns
    val tab = state.tab
    val paged = when (tab) {
        MusicTab.Musicians -> state.musicians
        MusicTab.Albums -> state.albums
        MusicTab.Tracks -> state.tracks
    }
    val content = paged.toMusicContent()
    val refreshRequester = remember { FocusRequester() }
    val tabRowRequester = remember { FocusRequester() }
    val playAllRequester = remember { FocusRequester() }
    val shuffleAllRequester = remember { FocusRequester() }
    val firstItemRequester = remember { FocusRequester() }
    val cardlessHandoffRequester = remember { FocusRequester() }
    val menuReturnRequester = remember { FocusRequester() }
    val focusOwnership = remember { PaneFocusOwnership(MUSIC_TAB_FOCUS_KEYS) }
    // The Tracks tab's action row exists only over a populated list; the strip's way down and
    // the first row's way up follow whether it is composed.
    val trackActionsShown = tab == MusicTab.Tracks && content is MusicContent.Populated
    var lastFocusedTrackAction by remember { mutableStateOf(playAllRequester) }
    val contentUp = if (trackActionsShown) lastFocusedTrackAction else tabRowRequester
    // The row whose More menu is open; the menu is the pane's last child, over everything.
    var trackMenu by remember(tab) { mutableStateOf<Pair<TrackRowUi, Rect>?>(null) }
    val headerNotice = state.notice ?: likes.notice

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            // No horizontal padding here: each surface carries the pane's gutter as content
            // padding so the scroll surface spans the panel (section 8.3).
            modifier = Modifier
                .fillMaxSize()
                .testTag("music_body")
                .then(if (trackMenu != null) Modifier.clearAndSetSemantics { } else Modifier),
            verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
        ) {
            PaneHeader(
                title = "Music",
                countText = paneCountLine(state.selectedTotal, tab::noun),
                countDescription = spokenCount(
                    tab,
                    state.selectedTotal,
                    state.selectedLoadedCount,
                    paged.append,
                ),
                countTag = "music_count",
                notice = headerNotice,
                contentInset = contentInset,
                noticeTag = "music_notice",
            ) {
                PaneRefreshButton(
                    refreshing = state.refreshing,
                    semanticLabel = "Refresh the music library",
                    onRefresh = actions.onRefresh,
                    modifier = Modifier
                        .focusRequester(refreshRequester)
                        .onFocusChanged {
                            focusOwnership.onChromeFocusChanged(REFRESH_FOCUS_KEY, it.isFocused)
                        }
                        // Down is wired to the *selected* tab: tabs select on focus, and a spatial
                        // search would land on whichever tab happens to sit beneath and switch
                        // to it.
                        .focusProperties {
                            left = navigationRequester
                            down = tabRowRequester
                        },
                )
            }

            PaneTabRow(
                tabs = MusicTab.entries,
                selected = tab,
                presentation = { it.presentation },
                testTag = "music_tabs",
                contentInset = contentInset,
                tabRowRequester = tabRowRequester,
                navigationRequester = navigationRequester,
                refreshRequester = refreshRequester,
                downRequester = if (trackActionsShown) playAllRequester else contentStartRequester,
                onSelectTab = actions.onSelectTab,
                onPressTab = actions.onPressTab,
                onFocusChanged = focusOwnership::onChromeFocusChanged,
            )

            if (trackActionsShown) {
                TrackActionsRow(
                    shufflePending = state.shufflePending,
                    contentInset = contentInset,
                    playAllRequester = playAllRequester,
                    shuffleAllRequester = shuffleAllRequester,
                    navigationRequester = navigationRequester,
                    tabRowRequester = tabRowRequester,
                    contentStartRequester = contentStartRequester,
                    onActionFocused = { lastFocusedTrackAction = it },
                    onFocusChanged = focusOwnership::onChromeFocusChanged,
                    onPlayAll = actions.onPlayAll,
                    onShuffleAll = actions.onShuffleAll,
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
                resetKey = tab,
                content = content,
                contentGeneration = paged.contentGeneration,
                scrollToTop = {
                    when (tab) {
                        MusicTab.Musicians -> musiciansGridState.scrollToItem(0)
                        MusicTab.Albums -> albumsGridState.scrollToItem(0)
                        MusicTab.Tracks -> tracksListState.scrollToItem(0)
                    }
                },
                firstItemRequester = firstItemRequester,
                cardlessHandoffRequester = cardlessHandoffRequester,
                focusOwnership = focusOwnership,
            )

            when (content) {
                MusicContent.Loading -> when (tab) {
                    MusicTab.Tracks -> TracksListSkeleton(
                        contentInset = contentInset,
                        anchorModifier = cardlessAnchor,
                    )

                    else -> PaneGridSkeleton(
                        columns = columns,
                        contentInset = contentInset,
                        loadingLabel = "Loading ${tab.presentation.semanticLabel.lowercase()}",
                        anchorModifier = cardlessAnchor,
                        cardAspect = IglooTheme.layout.albumAspect,
                        artworkRadius = tab.artworkRadius(),
                    )
                }

                is MusicContent.Error -> PaneFirstPageError(
                    message = content.message,
                    retryLabel = "Retry loading ${tab.presentation.semanticLabel.lowercase()}",
                    onRetry = actions.onRetryFirstPage,
                    anchorModifier = cardlessAnchor,
                    contentInset = contentInset,
                )

                MusicContent.Empty -> PaneEmpty(
                    icon = tab.emptyIcon(),
                    message = tab.emptyMessage(),
                    anchorModifier = cardlessAnchor,
                    contentInset = contentInset,
                )

                is MusicContent.Populated -> when (tab) {
                    MusicTab.Musicians -> PaneGrid(
                        items = state.musicians.loadedItems().orEmpty(),
                        itemId = { it.id },
                        testTag = "musicians_grid",
                        cardTag = { "musician_card_${it.id}" },
                        paged = state.musicians,
                        refreshing = state.refreshing,
                        columns = columns,
                        gridState = musiciansGridState,
                        contentInset = contentInset,
                        contentStartRequester = contentStartRequester,
                        navigationRequester = navigationRequester,
                        returnRequester = returnRequester,
                        firstItemRequester = firstItemRequester,
                        upRequester = contentUp,
                        lastFocusedId = lastFocusedMusicianId,
                        onItemFocused = onMusicianFocused,
                        focusOwnership = focusOwnership,
                        onLoadMore = actions.onLoadMore,
                        onRetryAppend = actions.onRetryAppend,
                        retryLabel = "Retry loading more musicians",
                        cardAspect = IglooTheme.layout.albumAspect,
                        artworkRadius = IglooTheme.radius.pill,
                    ) { musician, modifier ->
                        IglooPosterCard(
                            title = musician.name,
                            subtitle = musician.countsLine,
                            semanticLabel = musician.spoken,
                            imageUrl = musician.thumbUrl,
                            onClick = onMusicianSelected?.let { open -> { open(musician.id) } },
                            aspect = IglooTheme.layout.albumAspect,
                            width = Dp.Unspecified,
                            fallbackIcon = IglooIcons.Person,
                            artworkRadius = IglooTheme.radius.pill,
                            centerText = true,
                            modifier = modifier,
                        )
                    }

                    MusicTab.Albums -> PaneGrid(
                        items = state.albums.loadedItems().orEmpty(),
                        itemId = { it.id },
                        testTag = "albums_grid",
                        cardTag = { "album_card_${it.id}" },
                        paged = state.albums,
                        refreshing = state.refreshing,
                        columns = columns,
                        gridState = albumsGridState,
                        contentInset = contentInset,
                        contentStartRequester = contentStartRequester,
                        navigationRequester = navigationRequester,
                        returnRequester = returnRequester,
                        firstItemRequester = firstItemRequester,
                        upRequester = contentUp,
                        lastFocusedId = lastFocusedAlbumId,
                        onItemFocused = onAlbumFocused,
                        focusOwnership = focusOwnership,
                        onLoadMore = actions.onLoadMore,
                        onRetryAppend = actions.onRetryAppend,
                        retryLabel = "Retry loading more albums",
                        cardAspect = IglooTheme.layout.albumAspect,
                    ) { album, modifier ->
                        IglooPosterCard(
                            title = album.title,
                            subtitle = album.subtitle,
                            imageUrl = album.coverUrl,
                            onClick = onAlbumSelected?.let { open -> { open(album.id) } },
                            aspect = IglooTheme.layout.albumAspect,
                            width = Dp.Unspecified,
                            fallbackIcon = IglooIcons.Music,
                            modifier = modifier,
                        )
                    }

                    MusicTab.Tracks -> TracksList(
                        entries = state.tracks.loadedItems().orEmpty(),
                        likes = likes,
                        paged = state.tracks,
                        refreshing = state.refreshing,
                        listState = tracksListState,
                        contentInset = contentInset,
                        contentStartRequester = contentStartRequester,
                        navigationRequester = navigationRequester,
                        returnRequester = returnRequester,
                        firstItemRequester = firstItemRequester,
                        menuReturnRequester = menuReturnRequester,
                        menuOpenFor = trackMenu?.first?.id,
                        upRequester = contentUp,
                        lastFocusedTrack = lastFocusedTrack,
                        onTrackFocused = onTrackFocused,
                        focusOwnership = focusOwnership,
                        canOpenAlbum = onAlbumSelected != null,
                        canOpenArtist = onMusicianSelected != null,
                        onLoadMore = actions.onLoadMore,
                        onRetryAppend = actions.onRetryAppend,
                        onPlayTrack = actions.onPlayTrack,
                        onToggleLike = actions.onToggleLike,
                        onOpenMore = { track, bounds -> trackMenu = track to bounds },
                    )
                }
            }
        }

        // Last child, over the body, like every anchored menu. Dismissal restores focus to
        // the More control that opened it, in the callback rather than an effect (section 9.3).
        trackMenu?.let { (track, bounds) ->
            TrackRowMenu(
                track = track,
                anchorBounds = bounds,
                onGoToAlbum = onAlbumSelected,
                onGoToArtist = onMusicianSelected,
                onDismiss = {
                    trackMenu = null
                    menuReturnRequester.requestFocusSafely()
                },
            )
        }
    }
}

internal val MusicTab.presentation: TabPresentation
    get() = when (this) {
        MusicTab.Musicians -> TabPresentation("Musicians", "Musicians", "music_tab_musicians")
        MusicTab.Albums -> TabPresentation("Albums", "Albums", "music_tab_albums")
        MusicTab.Tracks -> TabPresentation("Tracks", "Tracks", "music_tab_tracks")
    }

/** The chrome keys the tab strip reports under; see [PaneFocusOwnership.tabFocused]. */
private val MUSIC_TAB_FOCUS_KEYS: Set<String> =
    MusicTab.entries.mapTo(mutableSetOf()) { it.presentation.key }

/**
 * Play all and Shuffle all over a populated track list. Play all steps back while Shuffle all
 * holds focus (the action-row recess rule). Shuffle all shows its wait on itself — a label
 * swap, never a spinner — and stays enabled so the focused node survives the request.
 */
@Composable
private fun TrackActionsRow(
    shufflePending: Boolean,
    contentInset: PaddingValues,
    playAllRequester: FocusRequester,
    shuffleAllRequester: FocusRequester,
    navigationRequester: FocusRequester,
    tabRowRequester: FocusRequester,
    contentStartRequester: FocusRequester,
    onActionFocused: (FocusRequester) -> Unit,
    onFocusChanged: (String, Boolean) -> Unit,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit,
) {
    var rowHasFocus by remember { mutableStateOf(false) }
    var playAllFocused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .padding(contentInset)
            .onFocusChanged { rowHasFocus = it.hasFocus },
        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
    ) {
        IglooButton(
            text = "Play all",
            onClick = onPlayAll,
            icon = IglooIcons.Play,
            semanticLabel = "Play all tracks",
            recessed = rowHasFocus && !playAllFocused,
            modifier = Modifier
                .testTag("music_play_all")
                .focusRequester(playAllRequester)
                .focusProperties {
                    left = navigationRequester
                    right = shuffleAllRequester
                    up = tabRowRequester
                    down = contentStartRequester
                }
                .onFocusChanged {
                    playAllFocused = it.isFocused
                    onFocusChanged(PLAY_ALL_FOCUS_KEY, it.isFocused)
                    if (it.isFocused) onActionFocused(playAllRequester)
                },
        )
        IglooButton(
            text = if (shufflePending) SHUFFLING_LABEL else SHUFFLE_ALL_LABEL,
            labelVariants = listOf(SHUFFLE_ALL_LABEL, SHUFFLING_LABEL),
            onClick = onShuffleAll,
            variant = IglooButtonVariant.Ghost,
            icon = IglooIcons.Shuffle,
            semanticLabel = "Shuffle all tracks",
            stateDescription = "Loading".takeIf { shufflePending },
            modifier = Modifier
                .testTag("music_shuffle_all")
                .focusRequester(shuffleAllRequester)
                .focusProperties {
                    left = playAllRequester
                    right = FocusRequester.Cancel
                    up = tabRowRequester
                    down = contentStartRequester
                }
                .onFocusChanged {
                    onFocusChanged(SHUFFLE_ALL_FOCUS_KEY, it.isFocused)
                    if (it.isFocused) onActionFocused(shuffleAllRequester)
                },
        )
    }
}

/**
 * The paged grid both card tabs share: the Movies grid's paging trigger, tail, edge pinning
 * and focus memory, with the card itself handed in.
 */
/**
 * The Tracks tab's flat list: letter headers the eye reads, rows with three actions each, and
 * the paging tail. Row-to-row focus moves are spatial and keep their column; only the edges are
 * wired — the first row's up to the last-focused action button, the last row's down pinned
 * unless the tail is a Retry, the left edge to the spine, the right edge pinned by the row.
 */
@Composable
private fun TracksList(
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
@Composable
private fun TracksListSkeleton(
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

@Composable
private fun MusicTab.artworkRadius(): Dp = when (this) {
    MusicTab.Musicians -> IglooTheme.radius.pill
    else -> IglooTheme.radius.lg
}

private fun MusicTab.noun(count: Long): String = countNoun(
    count,
    when (this) {
        MusicTab.Musicians -> "musician"
        MusicTab.Albums -> "album"
        MusicTab.Tracks -> "track"
    },
)

private fun MusicTab.emptyIcon(): ImageVector = when (this) {
    MusicTab.Musicians -> IglooIcons.Person
    else -> IglooIcons.Music
}

internal fun MusicTab.emptyMessage(): String = when (this) {
    MusicTab.Musicians -> "No musicians in your library yet. Add a music folder on the server and run a scan."
    MusicTab.Albums -> "No albums in your library yet. Add a music folder on the server and run a scan."
    MusicTab.Tracks -> "No tracks in your library yet. Add a music folder on the server and run a scan."
}

private fun spokenCount(tab: MusicTab, total: Long?, loadedCount: Int?, append: AppendState): String =
    when {
        total == null -> "Loading the music library"
        loadedCount == null -> paneCountLine(total, tab::noun)
        else -> showingCountLine(loadedCount, total, tab.noun(total), tab.noun(2), append)
    }

private const val SHUFFLE_ALL_LABEL = "Shuffle all"
private const val SHUFFLING_LABEL = "Shuffling…"

private const val REFRESH_FOCUS_KEY = "music_refresh"
private const val PLAY_ALL_FOCUS_KEY = "music_play_all"
private const val SHUFFLE_ALL_FOCUS_KEY = "music_shuffle_all"

/** How close to the end the track list gets before it asks for the next page, in rows. */
private const val LIST_PREFETCH_ROWS = 6
private const val LIST_SKELETON_ROWS = 8
