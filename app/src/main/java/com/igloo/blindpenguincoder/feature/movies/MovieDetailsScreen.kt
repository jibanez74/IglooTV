package com.igloo.blindpenguincoder.feature.movies

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.scaled
import com.igloo.blindpenguincoder.core.ui.IglooMenu
import com.igloo.blindpenguincoder.core.ui.IglooMenuItem
import com.igloo.blindpenguincoder.core.ui.IglooPinnedError
import com.igloo.blindpenguincoder.core.ui.iglooEnterStagger
import com.igloo.blindpenguincoder.core.ui.rememberRefocusAfterSwap
import com.igloo.blindpenguincoder.core.ui.rememberSpokenAccessibilityEnabled
import com.igloo.blindpenguincoder.core.ui.requestFocusSafely
import com.igloo.blindpenguincoder.data.model.PlaybackMode
import com.igloo.blindpenguincoder.feature.shared.DetailsHero
import com.igloo.blindpenguincoder.feature.shared.DetailsHeroSkeleton
import com.igloo.blindpenguincoder.feature.shared.DetailsState
import com.igloo.blindpenguincoder.feature.shared.heroHeaderModifier

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
     * A library movie's hero row: the two optimistic toggles and the More menu's items
     * (section 11.4.1). Play is not here — it opens the host-owned movie player, so it arrives
     * as a screen parameter beside `onPlayVideo`, with its own return requester. The menu's own
     * open/close is the host's, like Back; what an item *does* is the page's contract and lives
     * here.
     */
    data class Library(
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
 * [onPlay], [onPlayVideo] and the return requesters come from the host rather than
 * [MovieDetailsActions] for the same reason close is not in the bag: playing the movie or a
 * video opens a host-owned overlay, and the requesters are how the host restores focus to
 * whichever control launched it — the hero's Play button, a card in the extras rail, or the
 * in-theaters Play Trailer button — when that overlay closes.
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
    state: DetailsState<MovieDetailsUi>,
    actions: MovieDetailsActions,
    isAdmin: Boolean,
    onPlay: () -> Unit,
    playReturnRequester: FocusRequester,
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
    progressSyncError: String? = null,
    onRetryProgressSync: () -> Unit = {},
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

    // Keyed on the state's class — a Loaded republish (a toggle, a badge arriving) must not yank
    // focus back to the action row.
    val onScreenFocus = rememberRefocusAfterSwap(state::class) {
        entryRequester.requestFocusSafely()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .onFocusChanged { onScreenFocus(it.hasFocus) }
            .semantics {
                // The loaded pane announces the movie itself; a pane-title change is spoken, so
                // arriving on a loaded page (or the load completing) names the film rather than
                // a generic frame (section 12's pane rule).
                paneTitle = (state as? DetailsState.Loaded)?.value?.title ?: "Movie details"
                isTraversalGroup = true
            }
            .testTag("movie_details"),
    ) {
        // clearAndSetSemantics, not hideFromAccessibility, unlike the full-screen overlays: this
        // one has to take the covered controls *out of the semantics tree*, not merely flag them.
        // A hidden node is still in the tree, and TalkBack for TV leaves accessibility focus
        // parked on it — the More trigger it was sitting on when the menu opened. Nothing then
        // moves it: the menu's first row is composed already focused, and Compose emits
        // TYPE_VIEW_FOCUSED only for a node it has previously seen unfocused, so the reader goes
        // silent and the remote appears dead until Back. The shell under the details overlay and
        // the details screen under a player do not need this, because those overlays are
        // full-screen semantics nodes: what they cover is already dropped as occluded. The menu
        // is a small anchored card that occludes nothing.
        //
        // testTag stays outside the clear (the reading-stop ordering rule) so the tag survives.
        Box(
            modifier = Modifier
                .testTag("details_body")
                .then(
                    if (moreMenuOpen || playbackSettingsOpen) {
                        Modifier.clearAndSetSemantics { }
                    } else {
                        Modifier
                    },
                ),
        ) {
            DetailsBody(
                state = state,
                actions = actions,
                mutationNotice = mutationNotice,
                progressSyncError = progressSyncError,
                onRetryProgressSync = onRetryProgressSync,
                spokenAccessibilityEnabled = spokenAccessibilityEnabled,
                entryRequester = entryRequester,
                onPlay = onPlay,
                playReturnRequester = playReturnRequester,
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
            state is DetailsState.Loaded
        ) {
            state.value.playbackSettings?.let { settings ->
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
    state: DetailsState<MovieDetailsUi>,
    actions: MovieDetailsActions,
    mutationNotice: String?,
    progressSyncError: String?,
    onRetryProgressSync: () -> Unit,
    spokenAccessibilityEnabled: Boolean,
    entryRequester: FocusRequester,
    onPlay: () -> Unit,
    playReturnRequester: FocusRequester,
    onPlayVideo: (ExtraVideoUi, VideoLaunchSite) -> Unit,
    extrasReturnRequester: FocusRequester,
    heroTrailerReturnRequester: FocusRequester,
    moreRequester: FocusRequester,
    onOpenMoreMenu: () -> Unit,
    onMoreAnchorPositioned: (Rect) -> Unit,
) {
    when (state) {
        // Geometry-matched to the row it stands in for: the in-theaters hero carries one action
        // and no resume strip; the library hero's Play sits over the resume strip, followed by
        // Watched, Like and the square More trigger. A stand-in of the wrong shape would move
        // the anchor focus is sitting on when the real row lands.
        is DetailsState.Loading -> {
            val library = actions is MovieDetailsActions.Library
            val playStub = PLAY_STUB_WIDTH.scaled()
            DetailsHeroSkeleton(
                artworkAspect = IglooTheme.layout.posterAspect,
                artworkShape = RoundedCornerShape(IglooTheme.radius.lg),
                anchorWidth = playStub,
                loadingLabel = "Loading movie details",
                anchorRequester = entryRequester,
                trailingStubWidths = if (library) {
                    listOf(playStub, playStub, IglooTheme.sizes.controlHeight)
                } else {
                    emptyList()
                },
                belowAnchor = { if (library) ResumeProgress(progress = null, overMedia = false) },
            )
        }

        is DetailsState.Error -> IglooPinnedError(
            message = state.message,
            actionText = "Retry",
            actionSemanticLabel = "Retry loading movie details",
            actionRequester = entryRequester,
            onAction = actions.onRetry,
        )

        is DetailsState.Loaded -> DetailsContent(
            movie = state.value,
            mutationNotice = mutationNotice,
            progressSyncError = progressSyncError,
            onRetryProgressSync = onRetryProgressSync,
            spokenAccessibilityEnabled = spokenAccessibilityEnabled,
            entryRequester = entryRequester,
            actions = actions,
            onPlay = onPlay,
            playReturnRequester = playReturnRequester,
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
    progressSyncError: String?,
    onRetryProgressSync: () -> Unit,
    spokenAccessibilityEnabled: Boolean,
    entryRequester: FocusRequester,
    actions: MovieDetailsActions,
    onPlay: () -> Unit,
    playReturnRequester: FocusRequester,
    onPlayVideo: (ExtraVideoUi, VideoLaunchSite) -> Unit,
    extrasReturnRequester: FocusRequester,
    heroTrailerReturnRequester: FocusRequester,
    moreRequester: FocusRequester,
    onOpenMoreMenu: () -> Unit,
    onMoreAnchorPositioned: (Rect) -> Unit,
) {
    val colors = IglooTheme.colors
    val layout = IglooTheme.layout
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
    val progressRetryRequester = remember { FocusRequester() }
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
    val belowProgressError = when {
        stops -> overviewRequester
        hasCast -> castEntryRequester
        hasExtras -> extrasEntryRequester
        hasAbout -> aboutRequester
        else -> null
    }
    val belowActions = if (progressSyncError != null) {
        progressRetryRequester
    } else {
        belowProgressError
    }
    val upFromSections = when {
        progressSyncError != null -> progressRetryRequester
        hasHeroActions -> lastFocusedAction
        stops -> heroInfoRequester
        else -> null
    }
    val onProgressRetryFocus = rememberRefocusAfterSwap(progressSyncError) {
        if (progressSyncError == null) lastFocusedAction?.requestFocusSafely()
    }
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        DetailsHero(imageUrl = movie.backdropUrl, backdropTag = "details_backdrop") { overMedia ->
            MovieDetailsHeader(
                movie = movie,
                overMedia = overMedia,
                actions = actions,
                onPlay = onPlay,
                playReturnRequester = playReturnRequester,
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
                progressSyncError = progressSyncError,
                onRetryProgressSync = onRetryProgressSync,
                progressRetryRequester = progressRetryRequester,
                progressRetryUpRequester = lastFocusedAction,
                progressRetryDownRequester = belowProgressError,
                onProgressRetryFocusChanged = onProgressRetryFocus,
                modifier = heroHeaderModifier(),
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

/** The Play button's approximate footprint, so focus taken while loading does not jump. */
private val PLAY_STUB_WIDTH = 120.dp

