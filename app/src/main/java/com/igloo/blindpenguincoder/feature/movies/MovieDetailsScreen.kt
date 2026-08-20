package com.igloo.blindpenguincoder.feature.movies

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.igloo.blindpenguincoder.core.design.IglooMotion
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.iglooTween
import com.igloo.blindpenguincoder.core.design.scaled
import com.igloo.blindpenguincoder.core.ui.IglooInlineError
import com.igloo.blindpenguincoder.core.ui.IglooMenu
import com.igloo.blindpenguincoder.core.ui.IglooMenuItem
import com.igloo.blindpenguincoder.core.ui.focusRing
import com.igloo.blindpenguincoder.core.ui.iglooEnterStagger
import com.igloo.blindpenguincoder.core.ui.pinnedToScreen
import com.igloo.blindpenguincoder.core.ui.rememberSpokenAccessibilityEnabled
import com.igloo.blindpenguincoder.core.ui.requestFocusSafely
import com.igloo.blindpenguincoder.data.model.PlaybackMode

/**
 * What the details overlay can do, grouped so the shell that hosts it keeps a readable
 * signature. Close is the host's own business (it owns Back and focus restoration) and is
 * deliberately not here.
 *
 * Which variant is passed is also what tells the screen *which* detail page it is rendering:
 * the two differ in their hero actions and in nothing else, so everything below the action row
 * is one implementation over one [MovieDetailsUi].
 */
sealed interface MovieDetailsActions {
    /** The full-screen error's Retry, which both pages have. */
    val onRetry: () -> Unit

    /**
     * A library movie's hero row: Play, the two optimistic toggles, and the More menu's items
     * (section 11.4.1). The menu's own open/close is the host's, like Back; what an item *does*
     * is the page's contract and lives here.
     */
    data class Library(
        val onPlay: () -> Unit,
        val onToggleWatched: () -> Unit,
        val onToggleLike: () -> Unit,
        val onWatchTogether: () -> Unit,
        val onTechnicalDetails: () -> Unit,
        val onIdentifyMovie: () -> Unit,
        val onDeleteMovie: () -> Unit,
        /**
         * The Playback Settings dialog's selections. Opening the dialog is the host's business
         * (like `onPlayVideo` — it is a host-owned overlay), so there is no open callback here;
         * what a selection *does* is the page's contract, like the toggles.
         */
        val onSelectPlaybackMode: (PlaybackMode) -> Unit,
        val onSelectAudioTrack: (Long) -> Unit,
        val onSelectSubtitle: (Long?) -> Unit,
        override val onRetry: () -> Unit,
    ) : MovieDetailsActions

    /**
     * An in-theaters movie's hero row: Play Trailer alone, and no row at all when TMDB lists no
     * trailer (section 11.4.2) — there is nothing to watch, mark, or like on a movie the library
     * does not hold. What the button plays is [MovieDetailsUi.heroTrailer], opened through the
     * screen's own `onPlayVideo`: the trailer is one of the extras, and the player is the same.
     */
    data class Theater(
        override val onRetry: () -> Unit,
    ) : MovieDetailsActions
}

/**
 * Which control asked for a video to play: a card in the extras rail, or the in-theaters hero's
 * Play Trailer button. The host opens the same player either way and uses this to restore focus
 * to the control that led away when the player closes (section 6.3).
 */
enum class VideoLaunchSite { ExtrasRail, Hero }

/**
 * The movie details screen (docs/design-system.md sections 11.4 and 11.4.2): a full-screen
 * in-tree overlay above the shell, opaque on the `background` token, with a full-bleed backdrop
 * that scrolls away with the hero. Back and focus-restore belong to the host
 * ([com.igloo.blindpenguincoder.feature.home.IglooApp]), which also fences the shell's focusables
 * while this is open — the screen itself only anchors entry focus: the hero's primary action
 * (or the state's stand-in for it) is the first focused element, and where an in-theaters movie
 * has no trailer to play, the first section below takes that anchor instead.
 *
 * The backdrop opts out of the safe area (section 2.5); chrome and text keep the inset.
 *
 * [onPlayVideo] and the two return requesters come from the host rather than
 * [MovieDetailsActions] for the same reason close is not in the bag: playing a video opens a
 * host-owned overlay, and the requesters are how the host restores focus to whichever control
 * launched it — a card in the extras rail, or the hero's Play Trailer button — when that overlay
 * closes.
 *
 * The More menu follows the same split: the host owns [moreMenuOpen] (it must gate its own Back
 * while any modal is up, section 9.3) and restores focus through [moreRequester] in
 * [onDismissMoreMenu]; the screen owns what the menu shows — [isAdmin] gates the admin-only
 * items out of composition entirely, so non-admins have nothing to focus or hear.
 *
 * The Playback Settings dialog repeats the pattern once more: [playbackSettingsOpen] and both
 * transitions are the host's (its Back gating and focus hand-off live beside the menu's), while
 * the selection callbacks in [MovieDetailsActions.Library] are the page's contract.
 */
@Composable
fun MovieDetailsScreen(
    state: MovieDetailsState,
    actions: MovieDetailsActions,
    isAdmin: Boolean,
    onPlayVideo: (ExtraVideoUi, VideoLaunchSite) -> Unit,
    extrasReturnRequester: FocusRequester,
    heroTrailerReturnRequester: FocusRequester,
    moreMenuOpen: Boolean,
    onOpenMoreMenu: () -> Unit,
    onDismissMoreMenu: () -> Unit,
    moreRequester: FocusRequester,
    playbackSettingsOpen: Boolean,
    onOpenPlaybackSettings: () -> Unit,
    onDismissPlaybackSettings: () -> Unit,
    mutationNotice: String? = null,
    modifier: Modifier = Modifier,
    // Parameterized so tests can force both states: the reading-stop chain below depends on it,
    // and a test device with TalkBack running would otherwise pin the gate open.
    spokenAccessibilityEnabled: Boolean = rememberSpokenAccessibilityEnabled(),
) {
    val colors = IglooTheme.colors
    val entryRequester = remember { FocusRequester() }
    // Where the More trigger last landed, in root coordinates — the details overlay fills the
    // window, so root coordinates are also this screen's. The menu only exists once the trigger
    // has reported a position, and focus is trapped inside it while open, so the column cannot
    // scroll the anchor stale underneath it.
    var moreAnchor by remember { mutableStateOf<Rect?>(null) }

    // The hero's primary action is the first focused element on entry (section 11.4); while
    // loading, the skeleton's action-slot stub holds the anchor so focus already sits where the
    // real button will land. Requested safely because one state has nothing to anchor: an
    // in-theaters movie with no trailer, no cast, no extras and no about is all prose.
    LaunchedEffect(Unit) { entryRequester.requestFocusSafely() }

    // The IglooMediaRail swap-capture pattern: the outgoing state's focused node only detaches
    // once the composition applies, so this still sees whether the screen owned focus going in,
    // and the effect re-lands it on the incoming state's anchor. Keyed on the state's class —
    // a Loaded republish (a toggle, a badge arriving) must not yank focus back to the action row.
    var screenHasFocus by remember { mutableStateOf(false) }
    val hadFocusAtSwap = remember(state::class) { screenHasFocus }
    LaunchedEffect(state::class) {
        if (hadFocusAtSwap) entryRequester.requestFocusSafely()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .onFocusChanged { screenHasFocus = it.hasFocus }
            .semantics {
                // The loaded pane announces the movie itself; a pane-title change is spoken, so
                // arriving on a loaded page (or the load completing) names the film rather than
                // a generic frame (section 12's pane rule).
                paneTitle = (state as? MovieDetailsState.Loaded)?.movie?.title ?: "Movie details"
                isTraversalGroup = true
            }
            .testTag("movie_details"),
    ) {
        // hideFromAccessibility, not clearAndSetSemantics, while the menu covers this — the
        // same treatment the shell gets under the details overlay: nodes stay in the tree, so
        // a test can still assert what left traversal.
        Box(
            modifier = Modifier
                .testTag("details_body")
                .then(
                    if (moreMenuOpen || playbackSettingsOpen) {
                        Modifier.semantics { hideFromAccessibility() }
                    } else {
                        Modifier
                    },
                ),
        ) {
            DetailsBody(
                state = state,
                actions = actions,
                mutationNotice = mutationNotice,
                spokenAccessibilityEnabled = spokenAccessibilityEnabled,
                entryRequester = entryRequester,
                onPlayVideo = onPlayVideo,
                extrasReturnRequester = extrasReturnRequester,
                heroTrailerReturnRequester = heroTrailerReturnRequester,
                moreRequester = moreRequester,
                onOpenMoreMenu = onOpenMoreMenu,
                onMoreAnchorPositioned = { moreAnchor = it },
            )
        }

        // Last child, over the body, like every overlay in the stack. Playback Settings opens
        // the host-owned dialog below; the remaining items fire stubbed callbacks and close the
        // menu, nothing more, until each feature lands.
        val menuAnchor = moreAnchor
        if (moreMenuOpen && actions is MovieDetailsActions.Library && menuAnchor != null) {
            IglooMenu(
                title = "More options",
                items = buildList {
                    add(menuItem("Playback Settings", onOpenPlaybackSettings, onDismissMoreMenu))
                    add(menuItem("Watch Together", actions.onWatchTogether, onDismissMoreMenu))
                    add(menuItem("Technical Details", actions.onTechnicalDetails, onDismissMoreMenu))
                    if (isAdmin) {
                        add(menuItem("Identify Movie", actions.onIdentifyMovie, onDismissMoreMenu))
                        add(
                            menuItem(
                                "Delete Movie",
                                actions.onDeleteMovie,
                                onDismissMoreMenu,
                            ).copy(destructive = true, separatorBefore = true),
                        )
                    }
                },
                anchorBounds = menuAnchor,
                onDismiss = onDismissMoreMenu,
            )
        }

        // Loaded guard is belt-and-braces: the menu that opens this only exists on Loaded, and
        // background refresh failures keep Loaded — but a state swap must decompose the dialog
        // rather than crash a cast. The host flag then persists until details close, which is
        // the same outcome every stale-overlay flag gets.
        if (playbackSettingsOpen && actions is MovieDetailsActions.Library &&
            state is MovieDetailsState.Loaded
        ) {
            state.movie.playbackSettings?.let { settings ->
                PlaybackSettingsDialog(
                    settings = settings,
                    onSelectMode = actions.onSelectPlaybackMode,
                    onSelectAudio = actions.onSelectAudioTrack,
                    onSelectSubtitle = actions.onSelectSubtitle,
                    onDismiss = onDismissPlaybackSettings,
                )
            }
        }
    }
}

private fun menuItem(
    label: String,
    action: () -> Unit,
    dismiss: () -> Unit,
): IglooMenuItem = IglooMenuItem(
    label = label,
    onSelect = {
        // Action before dismiss, load-bearing: the host's onDismissMoreMenu suppresses its
        // focus restore when an action just opened the Playback Settings dialog, which it can
        // only see if the flag was written first.
        action()
        dismiss()
    },
)

@Composable
private fun DetailsBody(
    state: MovieDetailsState,
    actions: MovieDetailsActions,
    mutationNotice: String?,
    spokenAccessibilityEnabled: Boolean,
    entryRequester: FocusRequester,
    onPlayVideo: (ExtraVideoUi, VideoLaunchSite) -> Unit,
    extrasReturnRequester: FocusRequester,
    heroTrailerReturnRequester: FocusRequester,
    moreRequester: FocusRequester,
    onOpenMoreMenu: () -> Unit,
    onMoreAnchorPositioned: (Rect) -> Unit,
) {
    when (state) {
        is MovieDetailsState.Loading -> DetailsSkeleton(
                anchorRequester = entryRequester,
            // Geometry-matched to the row it stands in for: the in-theaters hero carries one
            // action and no resume strip, and a stand-in of the wrong shape would move the
            // anchor focus is sitting on when the real row lands.
            actionStubs = if (actions is MovieDetailsActions.Theater) 1 else LIBRARY_ACTIONS,
            reserveResumeSlot = actions is MovieDetailsActions.Library,
        )

        // The only region on screen, so Assertive is safe and right: the user just asked
        // for this page and is waiting on it (section 10).
        is MovieDetailsState.Error -> Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(IglooTheme.layout.safeAreaHorizontal),
            contentAlignment = Alignment.Center,
        ) {
            IglooInlineError(
                message = state.message,
                actionText = "Retry",
                actionSemanticLabel = "Retry loading movie details",
                onAction = actions.onRetry,
                // The screen's only focusable, so every direction is pinned: the shell is
                // still composed underneath, and a spatial search that escaped would strand
                // focus on a card nobody can see, with no way back to Retry.
                actionModifier = Modifier
                    .focusRequester(entryRequester)
                    .pinnedToScreen(),
                modifier = Modifier.width(IglooTheme.layout.dialogWidth),
            )
        }

        is MovieDetailsState.Loaded -> DetailsContent(
            movie = state.movie,
            mutationNotice = mutationNotice,
            spokenAccessibilityEnabled = spokenAccessibilityEnabled,
            entryRequester = entryRequester,
            actions = actions,
            onPlayVideo = onPlayVideo,
            extrasReturnRequester = extrasReturnRequester,
            heroTrailerReturnRequester = heroTrailerReturnRequester,
            moreRequester = moreRequester,
            onOpenMoreMenu = onOpenMoreMenu,
            onMoreAnchorPositioned = onMoreAnchorPositioned,
        )
    }
}

@Composable
private fun DetailsContent(
    movie: MovieDetailsUi,
    mutationNotice: String?,
    spokenAccessibilityEnabled: Boolean,
    entryRequester: FocusRequester,
    actions: MovieDetailsActions,
    onPlayVideo: (ExtraVideoUi, VideoLaunchSite) -> Unit,
    extrasReturnRequester: FocusRequester,
    heroTrailerReturnRequester: FocusRequester,
    moreRequester: FocusRequester,
    onOpenMoreMenu: () -> Unit,
    onMoreAnchorPositioned: (Rect) -> Unit,
) {
    val colors = IglooTheme.colors
    val layout = IglooTheme.layout
    var imageFailed by remember(movie.backdropUrl) { mutableStateOf(false) }
    var imageLoaded by remember(movie.backdropUrl) { mutableStateOf(false) }
    val showBackdrop = movie.backdropUrl != null && !imageFailed
    // Section 3.2's literals are licensed only by media actually behind them, so the white
    // treatment waits for the decode — a non-null URL alone would paint white text over the
    // bare token canvas for the whole load window.
    val overMedia = imageLoaded
    // What the hero's action row is: the library page always has Play, while the in-theaters page
    // has Play Trailer only for as long as TMDB lists one (section 11.4.2).
    val onPlayTrailer: (() -> Unit)? = movie.heroTrailer
        ?.takeIf { actions is MovieDetailsActions.Theater }
        ?.let { trailer -> { onPlayVideo(trailer, VideoLaunchSite.Hero) } }
    val hasHeroActions = actions is MovieDetailsActions.Library || onPlayTrailer != null

    // The screen's vertical chain, hand-wired end to end: actions -> cast -> extras -> about —
    // and, while a screen reader runs, the reading stops between them: hero info above the
    // actions, Overview and Key Crew before the cast. Nothing is left to a spatial search,
    // because the shell composed underneath would be a candidate.
    val watchedRequester = remember { FocusRequester() }
    val likeRequester = remember { FocusRequester() }
    val heroInfoRequester = remember { FocusRequester() }
    val overviewStop = remember { FocusRequester() }
    val keyCrewStop = remember { FocusRequester() }
    val castEntry = remember { FocusRequester() }
    val extrasEntry = remember { FocusRequester() }
    val about = remember { FocusRequester() }
    val hasCast = movie.cast.isNotEmpty()
    val hasExtras = movie.extraVideos.isNotEmpty()
    val hasAbout = !movie.about.isEmpty
    // With no action row there is nothing in the header to anchor entry focus on, so the first
    // section below owns the anchor: the screen's requester *is* that section's entry requester,
    // which lands entry focus there and leaves every edge wired at the section untouched. With
    // the reading stops in, the Overview stop is always that first section; without them the
    // anchor attaches to nothing at all on a page that is only prose, which is why the entry
    // request is made safely.
    val sectionAnchor = entryRequester.takeIf { !hasHeroActions }
    val stops = spokenAccessibilityEnabled
    val overviewRequester = if (sectionAnchor != null && stops) sectionAnchor else overviewStop
    val castEntryRequester =
        if (sectionAnchor != null && !stops && hasCast) sectionAnchor else castEntry
    val extrasEntryRequester =
        if (sectionAnchor != null && !stops && !hasCast && hasExtras) sectionAnchor else extrasEntry
    val aboutRequester =
        if (sectionAnchor != null && !stops && !hasCast && !hasExtras) sectionAnchor else about
    // Up from the sections returns to whichever action the user left, not unconditionally to the
    // primary one — the same focus memory the cast rail keeps for its own cards. While the hero
    // has no actions the reading-stop chain climbs to the hero info stop instead, and with the
    // stops out too it stays null, so up from the first section stays on the screen instead of
    // pointing at a requester attached to nothing.
    var lastFocusedAction by remember(hasHeroActions) {
        mutableStateOf(entryRequester.takeIf { hasHeroActions })
    }
    val upFromSections = when {
        hasHeroActions -> lastFocusedAction
        stops -> heroInfoRequester
        else -> null
    }
    val belowActions = when {
        stops -> overviewRequester
        hasCast -> castEntryRequester
        hasExtras -> extrasEntryRequester
        hasAbout -> aboutRequester
        else -> null
    }
    // The backdrop fades in on top of the token canvas instead of popping (section 7.2's
    // overlay-reveal case); under reduced motion iglooTween snaps it.
    val backdropAlpha by animateFloatAsState(
        targetValue = if (imageLoaded) 1f else 0f,
        animationSpec = iglooTween(IglooMotion.PAGE_MS),
        label = "detailsBackdrop",
    )
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // The hero region. Full-bleed: the backdrop reaches the physical edges and scrolls
        // away with the header, so everything below it reads on the plain token canvas.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = HERO_MIN_HEIGHT.scaled()),
        ) {
            if (showBackdrop) {
                AsyncImage(
                    model = movie.backdropUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onState = { state ->
                        when (state) {
                            is AsyncImagePainter.State.Success -> imageLoaded = true
                            is AsyncImagePainter.State.Error -> imageFailed = true
                            else -> Unit
                        }
                    },
                    modifier = Modifier
                        .testTag("details_backdrop")
                        .matchParentSize()
                        .graphicsLayer { alpha = backdropAlpha },
                )
                // Two scrims with two jobs (sections 3.2 and 11.4): the black side gradient is
                // the over-media literal that licenses the white text column against busy art;
                // the vertical fade is the token gradient that blends the backdrop into the
                // canvas the sections sit on. Alpha-zero stops come from the color itself —
                // Color.Transparent is black at zero and would gray the token fade.
                //
                // The stops are the detail hero's own, not section 3.2's home-hero ramp. That one
                // is written for a clipped card about 752dp wide; stretched across a full-bleed
                // panel it has decayed to alpha 0.14 by the time the metadata line ends, and the
                // backdrop's highlights come back through the text column.
                Box(
                    modifier = Modifier
                        // Tagged only once the decode lands: the tag's presence is what a test
                        // reads as "the section 3.2 treatment is on", the same way the resume
                        // track's tag exists only while the strip is visible.
                        .then(
                            if (overMedia) Modifier.testTag("details_backdrop_scrim") else Modifier,
                        )
                        .matchParentSize()
                        .graphicsLayer { alpha = backdropAlpha }
                        .background(
                            Brush.horizontalGradient(
                                0f to Color.Black.copy(alpha = 0.80f),
                                0.65f to Color.Black.copy(alpha = 0.55f),
                                1f to Color.Black.copy(alpha = 0f),
                            ),
                        ),
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                0.45f to colors.background.copy(alpha = 0f),
                                1f to colors.background,
                            ),
                        ),
                )
            }

            MovieDetailsHeader(
                movie = movie,
                overMedia = overMedia,
                actions = actions,
                onPlayTrailer = onPlayTrailer,
                spokenAccessibilityEnabled = spokenAccessibilityEnabled,
                heroInfoRequester = heroInfoRequester,
                primaryRequester = entryRequester,
                heroTrailerReturnRequester = heroTrailerReturnRequester,
                watchedRequester = watchedRequester,
                likeRequester = likeRequester,
                moreRequester = moreRequester,
                downRequester = belowActions,
                onActionFocused = { lastFocusedAction = it },
                onOpenMoreMenu = onOpenMoreMenu,
                onMoreAnchorPositioned = onMoreAnchorPositioned,
                mutationNotice = mutationNotice,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(
                        start = layout.safeAreaHorizontal,
                        end = layout.safeAreaHorizontal,
                        top = layout.safeAreaVertical,
                        bottom = IglooTheme.spacing.lg,
                    ),
                // Deliberately not staggered: the header holds the entry focus, and the rise
                // moves the focused button's visual bounds while the scroll container is
                // bringing it into view — the column ends up parked 12dp down, with the hero
                // pushed into the overscan margin. The backdrop's fade carries the entrance
                // here; the sections below animate because nothing there has focus yet.
            )
        }

        MovieDetailsSections(
            movie = movie,
            spokenAccessibilityEnabled = spokenAccessibilityEnabled,
            overviewRequester = overviewRequester,
            keyCrewRequester = keyCrewStop,
            castEntryRequester = castEntryRequester,
            extrasEntryRequester = extrasEntryRequester,
            extrasReturnRequester = extrasReturnRequester,
            aboutRequester = aboutRequester,
            upFromSections = upFromSections,
            onPlayExtra = { video -> onPlayVideo(video, VideoLaunchSite.ExtrasRail) },
            // The horizontal inset is handed down rather than applied here: the prose sections
            // take it, the cast and extras rails carry it as scroll padding so their cards run
            // off the panel's edge instead of stopping short of it (section 8.3).
            contentInset = PaddingValues(horizontal = layout.safeAreaHorizontal),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = layout.safeAreaVertical)
                .iglooEnterStagger(entered = entered, index = 0),
        )
    }
}

/**
 * Static geometry-matched stand-ins (section 10): poster and text stubs where the hero lands,
 * and an action row of [actionStubs] whose first slot is the screen's one focusable anchor, so
 * entry focus taken during the load sits exactly where the real primary button appears.
 * [reserveResumeSlot] reserves the strip only the library hero can grow.
 */
@Composable
private fun DetailsSkeleton(
    anchorRequester: FocusRequester,
    actionStubs: Int,
    reserveResumeSlot: Boolean,
) {
    val colors = IglooTheme.colors
    val layout = IglooTheme.layout
    val stubShape = RoundedCornerShape(IglooTheme.radius.sm)
    var focused by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HERO_MIN_HEIGHT.scaled()),
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(
                    start = layout.safeAreaHorizontal,
                    end = layout.safeAreaHorizontal,
                    top = layout.safeAreaVertical,
                    bottom = IglooTheme.spacing.lg,
                ),
            horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.xl),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                modifier = Modifier
                    .width(layout.posterWidth)
                    .aspectRatio(layout.posterAspect)
                    .background(colors.muted, RoundedCornerShape(IglooTheme.radius.lg)),
            )
            Column(verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md)) {
                Box(
                    modifier = Modifier
                        .width(320.dp.scaled())
                        .heightIn(min = 30.dp.scaled())
                        .background(colors.muted, stubShape),
                )
                Box(
                    modifier = Modifier
                        .width(220.dp.scaled())
                        .heightIn(min = 16.dp.scaled())
                        .background(colors.muted, stubShape),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md)) {
                    // The stub carries the same reserved resume slot as the real Play column,
                    // so the loading -> loaded swap does not move the anchor the focus sits on.
                    Column {
                        Box(
                            modifier = Modifier
                                .width(PLAY_STUB_WIDTH.scaled())
                                .heightIn(min = IglooTheme.sizes.controlHeight)
                                .focusRing(
                                    focused = focused,
                                    radius = IglooTheme.radius.lg,
                                    fill = colors.muted,
                                )
                                .focusRequester(anchorRequester)
                                // The screen's only focusable while loading, and the shell is still
                                // composed underneath: without this, Left or Down pressed before the
                                // movie lands walks focus onto an invisible card.
                                .pinnedToScreen()
                                .onFocusChanged { focused = it.isFocused }
                                .focusable()
                                .clearAndSetSemantics {
                                    contentDescription = "Loading movie details"
                                    liveRegion = LiveRegionMode.Polite
                                },
                        )
                        if (reserveResumeSlot) {
                            ResumeProgress(progress = null, overMedia = false)
                        }
                    }
                    repeat(actionStubs - 1) { index ->
                        // The library row ends in the square More trigger; a wide stub there
                        // would shift the row's geometry when the real row lands.
                        val squareStub = reserveResumeSlot && index == actionStubs - 2
                        Box(
                            modifier = Modifier
                                .width(
                                    if (squareStub) {
                                        IglooTheme.sizes.controlHeight
                                    } else {
                                        PLAY_STUB_WIDTH.scaled()
                                    },
                                )
                                .heightIn(min = IglooTheme.sizes.controlHeight)
                                .background(colors.muted, RoundedCornerShape(IglooTheme.radius.lg)),
                        )
                    }
                }
            }
        }
    }
}

/** About 60% of the reference viewport's height (section 8.1); contains text, so a minimum. */
private val HERO_MIN_HEIGHT = 320.dp

/** The Play button's approximate footprint, so focus taken while loading does not jump. */
private val PLAY_STUB_WIDTH = 120.dp

/** Play, Watched, Like, More — what the library hero's skeleton has to stand in for. */
private const val LIBRARY_ACTIONS = 4
