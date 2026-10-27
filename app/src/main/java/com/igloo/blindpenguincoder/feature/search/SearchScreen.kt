package com.igloo.blindpenguincoder.feature.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooButton
import com.igloo.blindpenguincoder.core.ui.IglooButtonVariant
import com.igloo.blindpenguincoder.core.ui.IglooEmpty
import com.igloo.blindpenguincoder.core.ui.IglooIcons
import com.igloo.blindpenguincoder.core.ui.IglooMediaRail
import com.igloo.blindpenguincoder.core.ui.IglooPosterCard
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.core.ui.IglooTextField
import com.igloo.blindpenguincoder.core.ui.SectionHeading
import com.igloo.blindpenguincoder.core.ui.announcedSection
import com.igloo.blindpenguincoder.core.ui.formatCount
import com.igloo.blindpenguincoder.core.ui.insetHorizontally
import com.igloo.blindpenguincoder.core.ui.rememberAnnouncedSectionColumn
import com.igloo.blindpenguincoder.core.ui.requestFocusAnnounced
import com.igloo.blindpenguincoder.core.ui.requestFocusSafely
import com.igloo.blindpenguincoder.core.ui.withRequester
import com.igloo.blindpenguincoder.feature.music.AlbumCard
import com.igloo.blindpenguincoder.feature.music.MusicianCard
import com.igloo.blindpenguincoder.feature.music.TrackFocusMemory
import com.igloo.blindpenguincoder.feature.music.TrackLikesUiState
import com.igloo.blindpenguincoder.feature.music.TracksList
import com.igloo.blindpenguincoder.feature.music.TracksListSkeleton
import com.igloo.blindpenguincoder.feature.shared.PagedState
import com.igloo.blindpenguincoder.feature.shared.PaneEmpty
import com.igloo.blindpenguincoder.feature.shared.PaneFirstPageError
import com.igloo.blindpenguincoder.feature.shared.PaneFocusHandoffCoordinator
import com.igloo.blindpenguincoder.feature.shared.PaneFocusOwnership
import com.igloo.blindpenguincoder.feature.shared.PaneGrid
import com.igloo.blindpenguincoder.feature.shared.PaneGridSkeleton
import com.igloo.blindpenguincoder.feature.shared.PaneHeader
import com.igloo.blindpenguincoder.feature.shared.PaneTabRow
import com.igloo.blindpenguincoder.feature.shared.PosterItem
import com.igloo.blindpenguincoder.feature.shared.PosterItemCard
import com.igloo.blindpenguincoder.feature.shared.TrackRow
import com.igloo.blindpenguincoder.feature.shared.TrackRowColumn
import com.igloo.blindpenguincoder.feature.shared.TrackRowFocus
import com.igloo.blindpenguincoder.feature.shared.TrackRowMenu
import com.igloo.blindpenguincoder.feature.shared.TrackRowRequesters
import com.igloo.blindpenguincoder.feature.shared.TrackRowUi
import com.igloo.blindpenguincoder.feature.shared.hasMoreActions
import com.igloo.blindpenguincoder.feature.shared.loadedItems
import com.igloo.blindpenguincoder.feature.shared.paneCardlessAnchor
import com.igloo.blindpenguincoder.feature.shared.paneCountLine
import com.igloo.blindpenguincoder.feature.shared.showingCountLine
import kotlinx.coroutines.flow.first

/**
 * The Search pane (docs/design-system.md section 11.6): a heading with the result count, a
 * Voice search action and a Clear action, the query field that summons the on-screen keyboard,
 * a tab strip (All · Movies · TV Shows · Albums · Musicians · Tracks) and the selected tab's
 * results — every category as a rail of its own cards on All, with a See-all card into the
 * category; one infinite-scrolling grid or track list on a category tab. Results update as the
 * user types; the keyboard's Search action, or a phrase the platform's recogniser heard through
 * [voiceSearch] (the button listens in place, showing its partial guesses in the field), sends
 * the query at once and lands focus on the results.
 *
 * The focus contract is the other panes' (section 6.3), with the field above the strip. With a
 * blank query the field is the pane's only focus target and [contentStartRequester] rides it;
 * otherwise the anchor rides the selected tab's entry item — the remembered one, or the first —
 * with the strip a press above, the field above that, and the header's actions (Voice search
 * where a recogniser answers, Clear while there is text) above the field. The left column exits to the spine,
 * the right edge is pinned, and the card-less states move the anchor to what they draw instead.
 * [returnRequester] rides the entry item too, so an overlay's Back lands on the card that led
 * away. Results landing while the field or a tab holds focus never pull it.
 */
@Composable
fun SearchScreen(
    state: SearchUiState,
    likes: TrackLikesUiState,
    actions: SearchActions,
    voiceSearch: VoiceSearch,
    contentInset: PaddingValues,
    memory: SearchPaneMemory,
    contentStartRequester: FocusRequester,
    navigationRequester: FocusRequester,
    returnRequester: FocusRequester,
    onMovieSelected: ((Long) -> Unit)?,
    onShowSelected: ((Long) -> Unit)?,
    onAlbumSelected: ((Long) -> Unit)?,
    onMusicianSelected: ((Long) -> Unit)?,
    spokenAccessibilityEnabled: Boolean,
) {
    val tab = state.tab
    val content = state.toSearchContent()
    val prompt = content is SearchContent.Prompt
    val clearShown = state.query.isNotEmpty()
    val voiceShown = voiceSearch.available
    val fieldRequester = remember { FocusRequester() }
    val voiceRequester = remember { FocusRequester() }
    val clearRequester = remember { FocusRequester() }
    val tabRowRequester = remember { FocusRequester() }
    val firstItemRequester = remember { FocusRequester() }
    val cardlessHandoffRequester = remember { FocusRequester() }
    val menuReturnRequester = remember { FocusRequester() }
    // The field and Clear join the tabs' "do not steal" set: results landing while the user
    // types must leave the keyboard where it is, exactly as a page landing under a focused tab.
    val focusOwnership = remember { PaneFocusOwnership(SEARCH_CHROME_FOCUS_KEYS) }
    val keyboard = LocalSoftwareKeyboardController.current
    // A See-all card, the keyboard's Search action or a voice result asks for focus on the
    // results: the node is composed in the next frame (the new tab's skeleton or its remembered
    // card), so the request waits one frame, and under a screen reader until it can be heard.
    var pendingContentHandoff by remember { mutableStateOf(false) }
    LaunchedEffect(pendingContentHandoff) {
        if (!pendingContentHandoff) return@LaunchedEffect
        withFrameNanos { }
        contentStartRequester.requestFocusAnnounced(screenReader = spokenAccessibilityEnabled)
        pendingContentHandoff = false
    }
    val landOnResults = { pendingContentHandoff = true }
    val voiceSession = voiceSearch.rememberSession { event ->
        actions.onVoiceSearch(event)
        if (event is VoiceSearchEvent.Done && event.outcome is VoiceSearchOutcome.Recognized) landOnResults()
    }
    // Back while the microphone is open stops it, as the button's second press does.
    BackHandler(enabled = state.voiceListening) { voiceSession.cancel() }
    // Which of the two header notices arrived last; each clears on its own terms.
    var likeNoticeIsNewer by remember { mutableStateOf(false) }
    LaunchedEffect(state.voiceNotice) { if (state.voiceNotice != null) likeNoticeIsNewer = false }
    LaunchedEffect(likes.notice) { if (likes.notice != null) likeNoticeIsNewer = true }
    LaunchedEffect(state.searched) { memory.onSearched(state.searched) }
    // The row whose More menu is open; the menu is the pane's last child, over everything.
    var trackMenu by remember(tab) { mutableStateOf<Pair<TrackRowUi, Rect>?>(null) }
    // The count line is a polite live region that re-announces on every change, so a search is
    // spoken once, when it settles: the wait keeps the line it replaced (the skeleton anchor
    // already says "Loading results"), while the visible line says "Searching…".
    val spokenCount = remember { SpokenCountMemory() }
    val countDescription = if (content is SearchContent.Loading) spokenCount.last else spokenCountLine(state, content)
    spokenCount.last = countDescription

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            // No horizontal padding here: each surface carries the pane's gutter as content
            // padding so the scroll surface spans the panel (section 8.3).
            modifier = Modifier
                .fillMaxSize()
                .testTag("search_body")
                .then(if (trackMenu != null) Modifier.clearAndSetSemantics { } else Modifier),
            verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
        ) {
            PaneHeader(
                title = "Search",
                countText = countLine(state, content),
                countDescription = countDescription,
                countTag = "search_count",
                // The line under the heading is the voice session's while it listens — the one
                // polite live region a screen reader hears "Listening" from — then the newer of
                // the attempt's notice (if it produced nothing) and a like failure; the older one
                // resurfaces when the newer clears.
                notice = when {
                    state.voiceListening -> VOICE_LISTENING_LINE
                    likeNoticeIsNewer -> likes.notice ?: state.voiceNotice
                    else -> state.voiceNotice ?: likes.notice
                },
                contentInset = contentInset,
                noticeTag = "search_notice",
            ) {
                // Composed only where a recogniser answers (section 11.6): on a Fire TV there
                // is none, and a button that cannot do what it says is the section 12 failure.
                if (voiceShown) {
                    val listening = state.voiceListening
                    IglooButton(
                        text = if (listening) VOICE_LISTENING_LABEL else VOICE_LABEL,
                        labelVariants = listOf(VOICE_LABEL, VOICE_LISTENING_LABEL),
                        onClick = if (listening) voiceSession::cancel else voiceSession::start,
                        variant = IglooButtonVariant.Ghost,
                        semanticLabel = if (listening) "Stop listening" else "Search by voice",
                        modifier = Modifier
                            .testTag("search_voice")
                            .focusRequester(voiceRequester)
                            .onFocusChanged { focusOwnership.onChromeFocusChanged(VOICE_FOCUS_KEY, it.isFocused) }
                            .focusProperties {
                                left = navigationRequester
                                right = if (clearShown) clearRequester else FocusRequester.Cancel
                                up = FocusRequester.Cancel
                                down = fieldRequester
                            },
                    )
                }
                // Composed only with something to clear: a Clear with nothing to do would
                // announce an action it cannot perform.
                if (clearShown) {
                    IglooButton(
                        text = "Clear",
                        onClick = {
                            actions.onClear()
                            // In the callback, not an effect: this button leaves with the text,
                            // and the field it hands focus to is still composed (section 9.3).
                            fieldRequester.requestFocusSafely()
                        },
                        variant = IglooButtonVariant.Ghost,
                        semanticLabel = "Clear the search",
                        modifier = Modifier
                            .testTag("search_clear")
                            .focusRequester(clearRequester)
                            .onFocusChanged { focusOwnership.onChromeFocusChanged(CLEAR_FOCUS_KEY, it.isFocused) }
                            .focusProperties {
                                left = if (voiceShown) voiceRequester else navigationRequester
                                right = FocusRequester.Cancel
                                up = FocusRequester.Cancel
                                down = fieldRequester
                            },
                    )
                }
            }

            IglooTextField(
                value = state.query,
                onValueChange = actions.onQueryChange,
                // Spoken only: the heading names the pane, and the prompt below is decorative,
                // so this label is the one sentence a screen reader hears for the field.
                label = FIELD_LABEL,
                labelVisible = false,
                placeholder = "Search your library",
                leadingIcon = IglooIcons.Search,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        keyboard?.hide()
                        actions.onSearchNow()
                        if (state.query.isNotBlank()) landOnResults()
                    },
                ),
                focusRequester = fieldRequester,
                upFocusRequester = if (voiceShown) voiceRequester else clearRequester.takeIf { clearShown },
                // With no results the field is the pane's floor; the strip is not composed.
                downFocusRequester = if (prompt) FocusRequester.Cancel else tabRowRequester,
                leftFocusRequester = navigationRequester,
                fieldModifier = Modifier
                    .withRequester(contentStartRequester.takeIf { prompt })
                    .onFocusChanged { focusOwnership.onChromeFocusChanged(FIELD_FOCUS_KEY, it.isFocused) }
                    .testTag("search_field"),
                modifier = Modifier.insetHorizontally(contentInset),
            )

            if (prompt) {
                // Decorative: a TV screen reader follows focus and never reaches plain text, so
                // the field's label carries the same prompt (section 12).
                IglooEmpty(
                    icon = IglooIcons.Search,
                    message = FIELD_LABEL,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(contentInset)
                        .padding(top = IglooTheme.spacing.xl)
                        .testTag("search_hint")
                        .clearAndSetSemantics { },
                )
                return@Column
            }

            PaneTabRow(
                tabs = SearchTab.entries,
                selected = tab,
                presentation = { it.presentation },
                testTag = "search_tabs",
                contentInset = contentInset,
                tabRowRequester = tabRowRequester,
                navigationRequester = navigationRequester,
                upRequester = fieldRequester,
                downRequester = contentStartRequester,
                onSelectTab = actions.onSelectTab,
                onPressTab = actions.onPressTab,
                onFocusChanged = focusOwnership::onChromeFocusChanged,
            )

            val cardlessAnchor = Modifier.paneCardlessAnchor(
                contentStartRequester = contentStartRequester,
                returnRequester = returnRequester,
                cardlessHandoffRequester = cardlessHandoffRequester,
                navigationRequester = navigationRequester,
                upRequester = tabRowRequester,
                focusOwnership = focusOwnership,
            )

            PaneFocusHandoffCoordinator(
                resetKey = tab,
                content = content,
                contentGeneration = state.selected.contentGeneration,
                scrollToTop = { memory.scrollToTop(tab) },
                // The All view has no first-card requester of its own: its entry rides the
                // first section's remembered card, which is where a replaced view re-anchors.
                firstItemRequester = if (tab == SearchTab.All) contentStartRequester else firstItemRequester,
                cardlessHandoffRequester = cardlessHandoffRequester,
                focusOwnership = focusOwnership,
                screenReader = spokenAccessibilityEnabled,
            )

            when (content) {
                SearchContent.Prompt -> Unit

                SearchContent.Loading -> when (tab) {
                    SearchTab.Tracks -> TracksListSkeleton(
                        contentInset = contentInset,
                        anchorModifier = cardlessAnchor,
                    )

                    else -> PaneGridSkeleton(
                        columns = IglooTheme.layout.gridColumns,
                        contentInset = contentInset,
                        // Not the count line's "Searching for …": two nodes speaking one phrase is
                        // the redundant announcement section 12 rules out.
                        loadingLabel = "Loading results",
                        anchorModifier = cardlessAnchor,
                        cardAspect = tab.kind?.cardAspect() ?: IglooTheme.layout.posterAspect,
                        artworkRadius = tab.kind?.artworkRadius() ?: IglooTheme.radius.lg,
                    )
                }

                is SearchContent.Error -> PaneFirstPageError(
                    message = content.message,
                    retryLabel = "Retry the search",
                    onRetry = actions.onRetryFirstPage,
                    anchorModifier = cardlessAnchor,
                    contentInset = contentInset,
                )

                SearchContent.Empty -> PaneEmpty(
                    icon = IglooIcons.Search,
                    message = emptyMessage(tab, state.searched),
                    anchorModifier = cardlessAnchor,
                    contentInset = contentInset,
                )

                SearchContent.Populated -> {
                    val grid = SearchGridWiring(
                        contentInset = contentInset,
                        contentStartRequester = contentStartRequester,
                        navigationRequester = navigationRequester,
                        returnRequester = returnRequester,
                        firstItemRequester = firstItemRequester,
                        upRequester = tabRowRequester,
                        focusOwnership = focusOwnership,
                        screenReader = spokenAccessibilityEnabled,
                        onLoadMore = actions.onLoadMore,
                        onRetryAppend = actions.onRetryAppend,
                    )
                    when (tab) {
                        SearchTab.All -> SearchAllResults(
                            sections = state.all.shownSections(),
                            likes = likes,
                            memory = memory,
                            focusOwnership = focusOwnership,
                            contentInset = contentInset,
                            contentStartRequester = contentStartRequester,
                            navigationRequester = navigationRequester,
                            returnRequester = returnRequester,
                            menuReturnRequester = menuReturnRequester,
                            menuOpenFor = trackMenu?.first?.id,
                            tabRowRequester = tabRowRequester,
                            screenReader = spokenAccessibilityEnabled,
                            onMovieSelected = onMovieSelected,
                            onShowSelected = onShowSelected,
                            onAlbumSelected = onAlbumSelected,
                            onMusicianSelected = onMusicianSelected,
                            onSeeAll = { kind ->
                                // The pressed card leaves with the All view in the next frame, and
                                // a focused node disposing hands focus to the platform's fallback
                                // (the navigation rail) until the results request below lands —
                                // a frame unaided, the announced wait under a screen reader. The
                                // strip survives the swap, and its selected tab is the one
                                // already selected, so parking focus there selects nothing.
                                tabRowRequester.requestFocusSafely()
                                landOnResults()
                                actions.onPressTab(kind.tab)
                            },
                            onPlayTrack = actions.onPlayTrack,
                            onToggleLike = actions.onToggleLike,
                            onOpenMore = { track, bounds -> trackMenu = track to bounds },
                        )

                        SearchTab.Movies -> SearchPosterGrid(
                            tab = tab,
                            paged = state.movies,
                            memory = memory,
                            wiring = grid,
                            onItemSelected = onMovieSelected,
                        )

                        SearchTab.Shows -> SearchPosterGrid(
                            tab = tab,
                            paged = state.shows,
                            memory = memory,
                            wiring = grid,
                            onItemSelected = onShowSelected,
                        )

                        SearchTab.Albums -> SearchGrid(
                            tab = tab,
                            paged = state.albums,
                            memory = memory,
                            wiring = grid,
                            itemId = { it.id },
                        ) { album, modifier ->
                            AlbumCard(
                                album = album,
                                onClick = onAlbumSelected?.let { open -> { open(album.id) } },
                                width = Dp.Unspecified,
                                modifier = modifier,
                            )
                        }

                        SearchTab.Musicians -> SearchGrid(
                            tab = tab,
                            paged = state.musicians,
                            memory = memory,
                            wiring = grid,
                            itemId = { it.id },
                        ) { musician, modifier ->
                            MusicianCard(
                                musician = musician,
                                onClick = onMusicianSelected?.let { open -> { open(musician.id) } },
                                width = Dp.Unspecified,
                                modifier = modifier,
                            )
                        }

                        SearchTab.Tracks -> TracksList(
                            entries = state.tracks.loadedItems().orEmpty(),
                            likes = likes,
                            paged = state.tracks,
                            refreshing = false,
                            listState = memory.tracksListState,
                            contentInset = contentInset,
                            contentStartRequester = contentStartRequester,
                            navigationRequester = navigationRequester,
                            returnRequester = returnRequester,
                            firstItemRequester = firstItemRequester,
                            menuReturnRequester = menuReturnRequester,
                            menuOpenFor = trackMenu?.first?.id,
                            upRequester = tabRowRequester,
                            lastFocusedTrack = memory.lastFocusedTrack,
                            onTrackFocused = { memory.lastFocusedTrack = it },
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

/** What every category grid is wired to alike. */
private class SearchGridWiring(
    val contentInset: PaddingValues,
    val contentStartRequester: FocusRequester,
    val navigationRequester: FocusRequester,
    val returnRequester: FocusRequester,
    val firstItemRequester: FocusRequester,
    val upRequester: FocusRequester,
    val focusOwnership: PaneFocusOwnership,
    val screenReader: Boolean,
    val onLoadMore: () -> Unit,
    val onRetryAppend: () -> Unit,
)

/** The Movies and TV Shows tabs: the library panes' poster grid over the search rows. */
@Composable
private fun SearchPosterGrid(
    tab: SearchTab,
    paged: PagedState<PosterItem>,
    memory: SearchPaneMemory,
    wiring: SearchGridWiring,
    onItemSelected: ((Long) -> Unit)?,
) {
    val kind = requireNotNull(tab.kind)
    SearchGrid(tab = tab, paged = paged, memory = memory, wiring = wiring, itemId = { it.id }) { item, modifier ->
        PosterItemCard(
            item = item,
            onClick = onItemSelected?.let { open -> { open(item.id) } },
            width = Dp.Unspecified,
            fallbackIcon = kind.icon,
            modifier = modifier,
        )
    }
}

/** One category tab's paged grid: the Music pane's, with this tab's memory and card. */
@Composable
private fun <T> SearchGrid(
    tab: SearchTab,
    paged: PagedState<T>,
    memory: SearchPaneMemory,
    wiring: SearchGridWiring,
    itemId: (T) -> Long,
    card: @Composable (item: T, modifier: Modifier) -> Unit,
) {
    val kind = requireNotNull(tab.kind)
    PaneGrid(
        items = paged.loadedItems().orEmpty(),
        itemId = itemId,
        testTag = "search_${kind.key}_grid",
        cardTag = { cardTag(kind, itemId(it)) },
        paged = paged,
        refreshing = false,
        columns = IglooTheme.layout.gridColumns,
        gridState = memory.gridState(tab),
        screenReader = wiring.screenReader,
        contentInset = wiring.contentInset,
        contentStartRequester = wiring.contentStartRequester,
        navigationRequester = wiring.navigationRequester,
        returnRequester = wiring.returnRequester,
        firstItemRequester = wiring.firstItemRequester,
        upRequester = wiring.upRequester,
        lastFocusedId = memory.lastFocusedIds[tab],
        onItemFocused = { memory.lastFocusedIds[tab] = it },
        focusOwnership = wiring.focusOwnership,
        onLoadMore = wiring.onLoadMore,
        onRetryAppend = wiring.onRetryAppend,
        retryLabel = "Retry loading more ${kind.noun(2)}",
        cardAspect = kind.cardAspect(),
        artworkRadius = kind.artworkRadius(),
        card = card,
    )
}

/**
 * The All view: one rail per category with matches, in category order, each ending in a
 * See-all card when it holds more than it shows; the tracks section is a plain column of rows
 * with a See-all button under them. Vertical moves between sections are announced for a screen
 * reader the way Home's are.
 */
@Composable
private fun SearchAllResults(
    sections: List<SearchSectionUi>,
    likes: TrackLikesUiState,
    memory: SearchPaneMemory,
    focusOwnership: PaneFocusOwnership,
    contentInset: PaddingValues,
    contentStartRequester: FocusRequester,
    navigationRequester: FocusRequester,
    returnRequester: FocusRequester,
    menuReturnRequester: FocusRequester,
    menuOpenFor: Long?,
    tabRowRequester: FocusRequester,
    screenReader: Boolean,
    onMovieSelected: ((Long) -> Unit)?,
    onShowSelected: ((Long) -> Unit)?,
    onAlbumSelected: ((Long) -> Unit)?,
    onMusicianSelected: ((Long) -> Unit)?,
    onSeeAll: (SearchKind) -> Unit,
    onPlayTrack: (Long) -> Unit,
    onToggleLike: (Long) -> Unit,
    onOpenMore: (TrackRowUi, Rect) -> Unit,
) {
    val order = sections.map { it.kind }
    val firstKind = order.first()
    // The return requester attaches to exactly one node: the section focus last rested in, or
    // the first when that section has no matches for this query.
    val returnKind = memory.lastFocusedSection?.takeIf { it in order } ?: firstKind
    val sectionColumn = rememberAnnouncedSectionColumn(
        scrollState = memory.allScrollState,
        screenReader = screenReader,
        order = order,
        requesterFor = { column, key ->
            if (key == firstKind) contentStartRequester else column.section(key).entry
        },
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("search_all")
            .then(sectionColumn.modifier)
            .verticalScroll(memory.allScrollState)
            // The scroll surface's own room, as the grids carry: the last section clears overscan.
            .padding(top = IglooTheme.spacing.sm, bottom = IglooTheme.layout.safeAreaVertical),
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.lg),
    ) {
        sections.forEachIndexed { index, section ->
            val kind = section.kind
            val tools = sectionColumn.section(kind)
            val isFirst = index == 0
            val isLast = index == sections.lastIndex
            val entryRequester = if (isFirst) contentStartRequester else tools.entry
            val returnHere = returnRequester.takeIf { kind == returnKind }
            // Each section reports under its own key, so a new search replacing the view under
            // a focused card is a hand-off the coordinator makes, not a focus dropped on the floor.
            val sectionKey = "search_section_${kind.key}"
            val sectionModifier = Modifier
                .announcedSection(tools)
                .onFocusChanged { focusOwnership.onChromeFocusChanged(sectionKey, it.hasFocus) }
                .testTag(sectionKey)
            val railUp = tabRowRequester.takeIf { isFirst }
            val railDown = FocusRequester.Cancel.takeIf { isLast }
            val rail = SearchRailWiring(
                section = section,
                contentInset = contentInset,
                entryRequester = entryRequester,
                navigationRequester = navigationRequester,
                returnRequester = returnHere,
                upRequester = railUp,
                downRequester = railDown,
                memory = memory,
                screenReader = screenReader,
                onSeeAll = onSeeAll,
                modifier = sectionModifier,
            )
            when (section) {
                is SearchSectionUi.Movies -> SearchRail(rail, section.items, { it.id }) { movie, itemModifier, aspect ->
                    PosterItemCard(
                        item = movie,
                        onClick = onMovieSelected?.let { open -> { open(movie.id) } },
                        aspect = aspect,
                        modifier = itemModifier.testTag(cardTag(kind, movie.id)),
                    )
                }

                is SearchSectionUi.Shows -> SearchRail(rail, section.items, { it.id }) { show, itemModifier, aspect ->
                    PosterItemCard(
                        item = show,
                        onClick = onShowSelected?.let { open -> { open(show.id) } },
                        aspect = aspect,
                        fallbackIcon = IglooIcons.TvShows,
                        modifier = itemModifier.testTag(cardTag(kind, show.id)),
                    )
                }

                is SearchSectionUi.Albums -> SearchRail(rail, section.items, { it.id }) { album, itemModifier, aspect ->
                    AlbumCard(
                        album = album,
                        onClick = onAlbumSelected?.let { open -> { open(album.id) } },
                        aspect = aspect,
                        modifier = itemModifier.testTag(cardTag(kind, album.id)),
                    )
                }

                is SearchSectionUi.Musicians -> SearchRail(rail, section.items, { it.id }) { musician, itemModifier, _ ->
                    MusicianCard(
                        musician = musician,
                        onClick = onMusicianSelected?.let { open -> { open(musician.id) } },
                        modifier = itemModifier.testTag(cardTag(kind, musician.id)),
                    )
                }

                is SearchSectionUi.Tracks -> SearchTracksSection(
                    section = section,
                    likes = likes,
                    memory = memory,
                    contentInset = contentInset,
                    entryRequester = entryRequester,
                    navigationRequester = navigationRequester,
                    returnRequester = returnHere,
                    menuReturnRequester = menuReturnRequester,
                    menuOpenFor = menuOpenFor,
                    upRequester = railUp,
                    pinDown = isLast,
                    canOpenAlbum = onAlbumSelected != null,
                    canOpenArtist = onMusicianSelected != null,
                    onSeeAll = { onSeeAll(SearchKind.Tracks) },
                    onPlayTrack = onPlayTrack,
                    onToggleLike = onToggleLike,
                    onOpenMore = onOpenMore,
                    modifier = sectionModifier,
                )
            }
        }
    }
}

/** A rail's entry: one of the category's rows, or the See-all card that closes the rail. */
private sealed interface RailEntry<out T> {
    data class Result<T>(val item: T, val key: Long) : RailEntry<T>
    data object SeeAll : RailEntry<Nothing>
}

private const val SEE_ALL_KEY = -1L

/** What every rail of the All view is wired to alike; the cards come from the section. */
private class SearchRailWiring(
    val section: SearchSectionUi,
    val contentInset: PaddingValues,
    val entryRequester: FocusRequester,
    val navigationRequester: FocusRequester,
    val returnRequester: FocusRequester?,
    val upRequester: FocusRequester?,
    val downRequester: FocusRequester?,
    val memory: SearchPaneMemory,
    val screenReader: Boolean,
    val onSeeAll: (SearchKind) -> Unit,
    val modifier: Modifier,
)

/**
 * One category's rail on the All view: the section title with its count, spoken once on entry
 * (section 11.3.2), the category's own cards, and the See-all card last when more matches exist.
 */
@Composable
private fun <T> SearchRail(
    wiring: SearchRailWiring,
    items: List<T>,
    itemKey: (T) -> Long,
    card: @Composable (item: T, itemModifier: Modifier, cardAspect: Float) -> Unit,
) {
    val section = wiring.section
    val kind = section.kind
    val memory = wiring.memory
    val entries = remember(items, section.hasMore) {
        buildList<RailEntry<T>> {
            items.forEach { add(RailEntry.Result(it, itemKey(it))) }
            if (section.hasMore) add(RailEntry.SeeAll)
        }
    }
    IglooMediaRail(
        title = sectionTitle(kind, section.total),
        state = IglooRailState.Loaded(entries),
        itemKey = { entry -> if (entry is RailEntry.Result) entry.key else SEE_ALL_KEY },
        entryRequester = wiring.entryRequester,
        leftFocusRequester = wiring.navigationRequester,
        lastFocusedKey = memory.lastFocusedRailKeys[kind],
        onItemFocused = { key ->
            memory.lastFocusedRailKeys[kind] = key
            memory.lastFocusedSection = kind
        },
        modifier = wiring.modifier,
        upFocusRequester = wiring.upRequester,
        downFocusRequester = wiring.downRequester,
        contentInset = wiring.contentInset,
        returnRequester = wiring.returnRequester,
        announceSectionTitle = true,
        screenReader = wiring.screenReader,
        cardAspect = kind.cardAspect(),
    ) { entry, itemModifier, cardAspect ->
        when (entry) {
            is RailEntry.Result -> card(entry.item, itemModifier, cardAspect)
            RailEntry.SeeAll -> SeeAllCard(
                kind = kind,
                total = section.total,
                aspect = cardAspect,
                onClick = { wiring.onSeeAll(kind) },
                modifier = itemModifier.testTag("search_see_all_${kind.key}"),
            )
        }
    }
}

/** The card that closes a rail with more matches than it shows: into the category's tab. */
@Composable
private fun SeeAllCard(
    kind: SearchKind,
    total: Long,
    aspect: Float,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val label = seeAllLabel(kind, total)
    IglooPosterCard(
        title = "See all",
        subtitle = "${formatCount(total)} ${kind.noun(total)}",
        imageUrl = null,
        onClick = onClick,
        actionLabel = label,
        semanticLabel = label,
        aspect = aspect,
        fallbackIcon = kind.icon,
        artworkRadius = kind.artworkRadius(),
        centerText = kind == SearchKind.Musicians,
        modifier = modifier,
    )
}

/**
 * The All view's tracks: the heading (folded into the first row's sentence at mapping time),
 * the rows, and a See-all button under them. A plain column, so every edge is wired: rows keep
 * their column up and down, the first row's up and the button's down are the neighbours'.
 */
@Composable
private fun SearchTracksSection(
    section: SearchSectionUi.Tracks,
    likes: TrackLikesUiState,
    memory: SearchPaneMemory,
    contentInset: PaddingValues,
    entryRequester: FocusRequester,
    navigationRequester: FocusRequester,
    returnRequester: FocusRequester?,
    menuReturnRequester: FocusRequester,
    menuOpenFor: Long?,
    upRequester: FocusRequester?,
    pinDown: Boolean,
    canOpenAlbum: Boolean,
    canOpenArtist: Boolean,
    onSeeAll: () -> Unit,
    onPlayTrack: (Long) -> Unit,
    onToggleLike: (Long) -> Unit,
    onOpenMore: (TrackRowUi, Rect) -> Unit,
    modifier: Modifier,
) {
    val rows = section.rows
    val requesters = remember(rows) { rows.map { TrackRowRequesters() } }
    val seeAllRequester = remember { FocusRequester() }
    val trackIds = remember(rows) { rows.map { it.id } }
    val remembered = memory.lastFocusedAllTrack
    val entryId = remembered?.id?.takeIf { it in trackIds } ?: trackIds.first()
    val entryColumn = remembered?.column?.takeIf { remembered.id == entryId } ?: TrackRowColumn.Play
    val lastIndex = rows.lastIndex
    val floor = when {
        section.hasMore -> seeAllRequester
        pinDown -> FocusRequester.Cancel
        else -> null
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(contentInset),
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
    ) {
        SectionHeading(sectionTitle(SearchKind.Tracks, section.total))
        rows.forEachIndexed { index, track ->
            val isEntry = track.id == entryId
            // Every requester the riders name is a key: the return requester comes and goes with
            // the section focus last rested in, and a stale capture would attach it to no node.
            val focus = remember(
                requesters, index, isEntry, entryColumn, menuOpenFor, upRequester, floor,
                entryRequester, returnRequester, navigationRequester, menuReturnRequester,
            ) {
                TrackRowFocus(
                    requesters = requesters[index],
                    up = { column -> requesters.getOrNull(index - 1)?.get(column) ?: upRequester },
                    down = { column -> requesters.getOrNull(index + 1)?.get(column) ?: floor },
                    left = navigationRequester,
                    riders = { column ->
                        listOfNotNull(
                            entryRequester.takeIf { isEntry && column == TrackRowColumn.Play },
                            returnRequester?.takeIf { isEntry && column == entryColumn },
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
                    memory.lastFocusedAllTrack = TrackFocusMemory(track.id, column)
                    memory.lastFocusedSection = SearchKind.Tracks
                },
            )
        }
        if (section.hasMore) {
            IglooButton(
                text = seeAllLabel(SearchKind.Tracks, section.total),
                onClick = onSeeAll,
                variant = IglooButtonVariant.Ghost,
                modifier = Modifier
                    .testTag("search_see_all_tracks")
                    .focusRequester(seeAllRequester)
                    .focusProperties {
                        left = navigationRequester
                        right = FocusRequester.Cancel
                        up = requesters[lastIndex].play
                        if (pinDown) down = FocusRequester.Cancel
                    },
            )
        }
    }
}

/** The home panes' own tags, so a result card is addressed exactly as it is there. */
private fun cardTag(kind: SearchKind, id: Long): String = when (kind) {
    SearchKind.Movies -> "poster_card_$id"
    SearchKind.Shows -> "show_card_$id"
    SearchKind.Albums -> "album_card_$id"
    SearchKind.Musicians -> "musician_card_$id"
    SearchKind.Tracks -> "track_$id"
}

@Composable
private fun SearchKind.cardAspect(): Float = when (this) {
    SearchKind.Movies, SearchKind.Shows -> IglooTheme.layout.posterAspect
    else -> IglooTheme.layout.albumAspect
}

@Composable
private fun SearchKind.artworkRadius(): Dp = when (this) {
    SearchKind.Musicians -> IglooTheme.radius.pill
    else -> IglooTheme.radius.lg
}

private fun emptyMessage(tab: SearchTab, query: String): String {
    val kind = tab.kind ?: return "No results for “$query”. Try different words."
    return "No ${kind.noun(2)} match “$query”. Try different words."
}

/** The spoken count line last announced, carried across a search's wait; see its use. */
private class SpokenCountMemory {
    var last: String = PROMPT_COUNT_LINE
}

/** The header's visible line under the title. */
private fun countLine(state: SearchUiState, content: SearchContent): String {
    val kind = state.tab.kind
    return when (content) {
        SearchContent.Prompt -> PROMPT_COUNT_LINE
        SearchContent.Loading -> "Searching…"
        is SearchContent.Error -> "—"
        SearchContent.Empty, SearchContent.Populated ->
            if (kind == null) {
                resultsCountLine(state.all.shownSections().totalMatches(), state.searched)
            } else {
                paneCountLine(state.selected.total, kind::noun)
            }
    }
}

/**
 * The header's spoken line: a polite live region, so each search's result is heard once as it
 * lands — the All view with its category breakdown, a category tab with its paging count.
 */
private fun spokenCountLine(state: SearchUiState, content: SearchContent): String {
    val kind = state.tab.kind
    return when (content) {
        SearchContent.Prompt, SearchContent.Loading -> PROMPT_COUNT_LINE
        is SearchContent.Error -> "Search for ${state.searched} failed"
        // Not the empty box's own sentence: it is the anchor focus lands on, and two nodes
        // speaking one phrase is the redundant announcement section 12 rules out.
        SearchContent.Empty -> if (kind == null) "0 results for ${state.searched}" else "0 ${kind.noun(0)}"
        SearchContent.Populated -> when {
            kind == null -> spokenResultsSummary(state.all.shownSections(), state.searched)
            else -> {
                val total = state.selected.total ?: 0L
                val loaded = state.selected.loadedItems()?.size ?: 0
                showingCountLine(loaded, total, kind.noun(total), kind.noun(2), state.selected.append)
            }
        }
    }
}

private const val PROMPT_COUNT_LINE = "Find anything in your library"
private const val FIELD_LABEL = "Search movies, TV shows, albums, musicians and tracks"
private const val FIELD_FOCUS_KEY = "search_field"
private const val VOICE_FOCUS_KEY = "search_voice"
private const val VOICE_LABEL = "Voice search"
private const val VOICE_LISTENING_LABEL = "Listening…"
internal const val VOICE_LISTENING_LINE = "Listening… say a title, album, musician or track."
private const val CLEAR_FOCUS_KEY = "search_clear"

/** The chrome keys a content swap must not steal focus from: the tabs, the field, Voice search and Clear. */
private val SEARCH_CHROME_FOCUS_KEYS: Set<String> =
    SearchTab.entries.mapTo(mutableSetOf()) { it.presentation.key } + FIELD_FOCUS_KEY + VOICE_FOCUS_KEY + CLEAR_FOCUS_KEY
