package com.igloo.blindpenguincoder.feature.home

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.igloo.blindpenguincoder.core.design.IglooEasing
import com.igloo.blindpenguincoder.core.design.IglooMotion
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.iglooTween
import com.igloo.blindpenguincoder.core.design.scaled
import com.igloo.blindpenguincoder.core.navigation.IglooDestination
import com.igloo.blindpenguincoder.core.navigation.PrimaryIglooDestinations
import com.igloo.blindpenguincoder.core.ui.IglooButtonVariant
import com.igloo.blindpenguincoder.core.ui.IglooConfirmDialog
import com.igloo.blindpenguincoder.core.ui.IglooIcons
import com.igloo.blindpenguincoder.core.ui.IglooMediaRail
import com.igloo.blindpenguincoder.core.ui.IglooNotice
import com.igloo.blindpenguincoder.core.ui.IglooPosterCard
import com.igloo.blindpenguincoder.core.ui.IglooScrim
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.PosterCardProgress
import com.igloo.blindpenguincoder.core.ui.SCRIM_ALPHA
import com.igloo.blindpenguincoder.core.ui.focusRing
import com.igloo.blindpenguincoder.core.ui.iglooAuroraBackdrop
import com.igloo.blindpenguincoder.core.ui.rememberSpokenAccessibilityEnabled
import com.igloo.blindpenguincoder.core.ui.requestFocusSafely
import com.igloo.blindpenguincoder.data.model.AuthUser
import com.igloo.blindpenguincoder.feature.movies.MovieDetailsActions
import com.igloo.blindpenguincoder.feature.movies.MovieDetailsScreen
import com.igloo.blindpenguincoder.feature.movies.MovieDetailsUiState
import com.igloo.blindpenguincoder.feature.movies.MoviesActions
import com.igloo.blindpenguincoder.feature.movies.MoviesScreen
import com.igloo.blindpenguincoder.feature.movies.MoviesUiState
import com.igloo.blindpenguincoder.feature.movies.VideoLaunchSite
import com.igloo.blindpenguincoder.data.model.PlaybackMode
import com.igloo.blindpenguincoder.feature.player.MoviePlayerScreen
import com.igloo.blindpenguincoder.feature.player.MoviePlayerViewModel
import com.igloo.blindpenguincoder.feature.player.ProgressSyncUiState
import com.igloo.blindpenguincoder.feature.player.TrailerPlayerScreen
import com.igloo.blindpenguincoder.playback.media3.MoviePlayerEngine
import com.igloo.blindpenguincoder.playback.model.MoviePlayRequest
import com.igloo.blindpenguincoder.playback.model.PlayableAudioTrack
import com.igloo.blindpenguincoder.playback.model.PlayableSubtitleTrack
import com.igloo.blindpenguincoder.playback.model.PlaybackChapter
import kotlinx.serialization.json.Json
import com.igloo.blindpenguincoder.playback.youtube.TrailerPlayerEngine
import com.igloo.blindpenguincoder.playback.youtube.youTubeIFrameEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Which surface opened the details overlay, so Back can put focus back where it came from. */
private sealed interface DetailsOrigin {
    data object Hero : DetailsOrigin
    data class Rail(val rail: HomeRail) : DetailsOrigin

    /** The Movies grid. No payload: the grid's own focus memory names the cell, not the origin. */
    data object MoviesGrid : DetailsOrigin

    companion object {
        /** One string, because the overlay outlives activity recreation but `remember` does not. */
        val Saver: Saver<DetailsOrigin?, String> = Saver(
            save = { origin ->
                when (origin) {
                    is Rail -> origin.rail.name
                    Hero -> HERO
                    MoviesGrid -> MOVIES_GRID
                    null -> NONE
                }
            },
            restore = { saved ->
                when (saved) {
                    NONE -> null
                    HERO -> Hero
                    MOVIES_GRID -> MoviesGrid
                    else -> Rail(HomeRail.valueOf(saved))
                }
            },
        )

        private const val HERO = "hero"
        private const val MOVIES_GRID = "movies_grid"
        private const val NONE = "none"
    }
}

/** What the trailer player overlay is playing: only what its screen renders, plus where it was
 * launched from, saveable so the overlay survives activity recreation (the trailer itself
 * restarts — a WebView cannot be parceled, and a trailer losing its position is an accepted
 * trade). */
private data class TrailerRequest(
    val key: String,
    val title: String,
    val typeLabel: String,
    val origin: VideoLaunchSite,
) {
    companion object {
        val Saver: Saver<TrailerRequest?, List<String>> = Saver(
            save = { request ->
                if (request == null) {
                    emptyList()
                } else {
                    listOf(request.key, request.title, request.typeLabel, request.origin.name)
                }
            },
            restore = { saved ->
                if (saved.isEmpty()) {
                    null
                } else {
                    TrailerRequest(
                        key = saved[0],
                        title = saved[1],
                        typeLabel = saved[2],
                        origin = VideoLaunchSite.valueOf(saved[3]),
                    )
                }
            },
        )
    }
}

/** One current snapshot read by the long-lived play-request collector. */
private data class DeferredPlayContext(
    val openMovieId: Long?,
    val libraryDetails: Boolean,
    val moreMenuOpen: Boolean,
    val playbackSettingsOpen: Boolean,
    val trailerOpen: Boolean,
    val playerOpen: Boolean,
    val signOutConfirming: Boolean,
) {
    fun accepts(request: MoviePlayRequest): Boolean =
        libraryDetails && openMovieId == request.movieId && !moreMenuOpen &&
            !playbackSettingsOpen && !trailerOpen && !playerOpen && !signOutConfirming
}

/**
 * What the movie player overlay is playing, saveable so the overlay survives activity
 * recreation (the screen itself restarts the engine and re-seeks to its saved position).
 * Nullable fields ride as "" — no title is ever blank, so the encoding is unambiguous.
 */
private val MoviePlayRequestSaver: Saver<MoviePlayRequest?, List<String>> = Saver(
    save = { request ->
        if (request == null) {
            emptyList()
        } else {
            listOf(
                request.movieId.toString(),
                request.title,
                request.posterUrl.orEmpty(),
                request.mimeType,
                request.mode.name,
                request.audioTypeIndex?.toString().orEmpty(),
                request.subtitleTypeIndex?.toString().orEmpty(),
                // Lists have no natural slot in this flat encoding; JSON is one symmetric line.
                Json.encodeToString(request.audioTracks),
                Json.encodeToString(request.subtitleTracks),
                request.resumeAtSec?.toString().orEmpty(),
                request.durationSec?.toString().orEmpty(),
                Json.encodeToString(request.chapters),
            )
        }
    },
    restore = { saved ->
        if (saved.isEmpty()) {
            null
        } else {
            MoviePlayRequest(
                movieId = saved[0].toLong(),
                title = saved[1],
                posterUrl = saved[2].ifEmpty { null },
                mimeType = saved[3],
                mode = PlaybackMode.valueOf(saved[4]),
                audioTypeIndex = saved[5].toIntOrNull(),
                subtitleTypeIndex = saved[6].toIntOrNull(),
                audioTracks = Json.decodeFromString<List<PlayableAudioTrack>>(saved[7]),
                subtitleTracks = Json.decodeFromString<List<PlayableSubtitleTrack>>(saved[8]),
                resumeAtSec = saved[9].toDoubleOrNull(),
                durationSec = saved[10].toDoubleOrNull(),
                chapters = Json.decodeFromString<List<PlaybackChapter>>(saved[11]),
            )
        }
    },
)

@Composable
fun IglooApp(
    user: AuthUser,
    serverOrigin: String,
    signOut: SignOutUiState,
    home: HomeUiState,
    movies: MoviesUiState,
    moviesActions: MoviesActions,
    details: MovieDetailsUiState,
    detailsActions: MovieDetailsActions,
    onRequestPlayback: () -> Unit,
    moviePlayerViewModel: MoviePlayerViewModel,
    moviePlayerEngineFactory: (Context, MoviePlayRequest) -> MoviePlayerEngine,
    playRequests: Flow<MoviePlayRequest> = emptyFlow(),
    onRetryRail: (HomeRail) -> Unit,
    onMovieSelected: ((Long) -> Unit)?,
    onTheaterMovieSelected: ((Long) -> Unit)?,
    onCloseDetails: () -> Unit,
    onSwitchProfile: () -> Unit,
    onSignOut: () -> Unit,
    onSignOutConfirm: () -> Unit,
    onSignOutDismiss: () -> Unit,
    // The real engine embeds under the server's own origin — the same real, attributable origin
    // the web client's trailer page has; YouTube rejects a borrowed youtube.com origin.
    trailerEngineFactory: (Context, String) -> TrailerPlayerEngine = { context, key ->
        youTubeIFrameEngine(context, key, serverOrigin)
    },
    // Resolved here and handed to the details overlay, and parameterized so focus tests can
    // force both states — a test device with TalkBack running would otherwise pin it open.
    spokenAccessibilityEnabled: Boolean = rememberSpokenAccessibilityEnabled(),
) {
    var currentDestinationName by rememberSaveable { mutableStateOf(IglooDestination.Home.name) }
    val currentDestination = IglooDestination.valueOf(currentDestinationName)
    val contentStartRequester = remember { FocusRequester() }
    val signOutRequester = remember { FocusRequester() }
    val navigationRequesters = remember {
        PrimaryIglooDestinations.associateWith { FocusRequester() }
    }
    // One per rail, pinned to whichever node that rail's focus memory points at, in every rail
    // state. Back out of the details overlay lands on the card that opened it (section 6.3).
    val railReturnRequesters = remember {
        HomeRail.entries.associateWith { FocusRequester() }
    }
    // The grid's sibling of railReturnRequesters: parked on the Movies grid's entry cell in
    // every grid state, so Back out of details lands on the card that opened it.
    val moviesReturnRequester = remember { FocusRequester() }
    // The rail expands exactly while d-pad focus is inside it; railOpenedByBack remembers
    // whether the rail was entered with the Back button, so Back can mean "step outward":
    // content -> rail -> exit, but a rail entered by d-pad steps back into content instead.
    var railHasFocus by remember { mutableStateOf(false) }
    var railOpenedByBack by remember { mutableStateOf(false) }
    var detailsOrigin by rememberSaveable(stateSaver = DetailsOrigin.Saver) {
        mutableStateOf<DetailsOrigin?>(null)
    }
    val detailsOpen = details.openMovieId != null
    // The trailer player is the third overlay layer (shell -> details -> player); the host owns
    // its existence and its focus restore, the same contract the details overlay lives under.
    var trailerRequest by rememberSaveable(stateSaver = TrailerRequest.Saver) {
        mutableStateOf<TrailerRequest?>(null)
    }
    val trailerOpen = trailerRequest != null
    // The movie player shares the details page's one player-overlay slot with the trailer. The
    // host owns its existence and focus restore, and an older deferred Play may not replace a
    // newer menu, dialog, or trailer action.
    var moviePlayRequest by rememberSaveable(stateSaver = MoviePlayRequestSaver) {
        mutableStateOf<MoviePlayRequest?>(null)
    }
    val playerOpen = moviePlayRequest != null
    val progressSync by moviePlayerViewModel.progressSyncUiState.collectAsStateWithLifecycle()
    val progressSyncError = (progressSync as? ProgressSyncUiState.Failed)?.message
    val playReturnRequester = remember { FocusRequester() }
    // Parked by the extras rail on its last-focused card, so closing the player restores focus
    // to the exact card that launched it (section 6.3). The in-theaters page can launch the same
    // player from its hero instead, and parks the second requester on that button.
    val extrasReturnRequester = remember { FocusRequester() }
    val heroTrailerReturnRequester = remember { FocusRequester() }
    // The details screen's More menu is host state for the same reason Back is host behavior:
    // section 9.3 makes the host gate its own handlers while any modal is up, so the host has
    // to know one is. Plain `remember` — an open menu is not worth surviving process death.
    var moreMenuOpen by remember { mutableStateOf(false) }
    val moreReturnRequester = remember { FocusRequester() }
    // The Playback Settings dialog is host state for the same reason the menu is; the two are
    // never up together — opening the dialog closes the menu in the same event.
    var playbackSettingsOpen by remember { mutableStateOf(false) }
    // Ephemeral by design: activity recreation must never finish a Play press made in the old
    // activity. Only an explicit Play activation arms it, and each emitted request consumes it.
    var deferredPlayArmed by remember { mutableStateOf(false) }
    val deferredPlayContext by rememberUpdatedState(
        DeferredPlayContext(
            openMovieId = details.openMovieId,
            libraryDetails = detailsActions is MovieDetailsActions.Library,
            moreMenuOpen = moreMenuOpen,
            playbackSettingsOpen = playbackSettingsOpen,
            trailerOpen = trailerOpen,
            playerOpen = playerOpen,
            signOutConfirming = signOut.confirming,
        ),
    )
    LaunchedEffect(details.openMovieId, detailsActions::class) {
        deferredPlayArmed = false
    }
    LaunchedEffect(playRequests) {
        playRequests.collect { request ->
            val eligible = deferredPlayArmed && deferredPlayContext.accepts(request)
            deferredPlayArmed = false
            if (eligible) moviePlayRequest = request
        }
    }
    // Back cannot close the details while the menu is up (its handler is gated on the flag), but
    // the overlay can still leave on its own — a session revalidation, a profile switch — and a
    // flag that outlived it would greet the next movie with a menu it never asked for.
    LaunchedEffect(detailsOpen) {
        if (!detailsOpen) {
            deferredPlayArmed = false
            moreMenuOpen = false
            playbackSettingsOpen = false
            // The player must not outlive the details page it launched from — a profile switch
            // or session revalidation that closes the overlay takes the movie with it.
            moviePlayRequest = null
        }
    }
    val closeMoviePlayer: () -> Unit = {
        moviePlayRequest = null
        // In the callback, not an effect, for the detach-race reason the details close
        // documents. The Play button is still composed in every reachable case; the pane's
        // anchor is the same last-resort fallback the other overlays use.
        if (!playReturnRequester.requestFocusSafely()) {
            contentStartRequester.requestFocusSafely()
        }
    }
    val closeTrailer = {
        val origin = trailerRequest?.origin
        trailerRequest = null
        // In the callback, not an effect, for the detach-race reason the details close documents.
        // The launching control is still composed in every reachable case — nothing that runs
        // under the player removes it — but if the anchor is gone anyway, the pane's anchor is a
        // worse restore than the card and far better than a crash.
        val returnRequester = when (origin) {
            VideoLaunchSite.Hero -> heroTrailerReturnRequester
            else -> extrasReturnRequester
        }
        if (!returnRequester.requestFocusSafely()) {
            contentStartRequester.requestFocusSafely()
        }
    }

    val openMovie: ((DetailsOrigin, Long) -> Unit)? = onMovieSelected?.let { select ->
        { origin, movieId ->
            detailsOrigin = origin
            select(movieId)
        }
    }
    // The theaters rail is the in-theaters page's only entrance, so its origin is not a parameter.
    val openTheaterMovie: ((Long) -> Unit)? = onTheaterMovieSelected?.let { select ->
        { tmdbId ->
            detailsOrigin = DetailsOrigin.Rail(HomeRail.InTheaters)
            select(tmdbId)
        }
    }

    // Every handler is gated explicitly rather than left to win on registration order —
    // design-system.md section 9.3 requires the host to be deliberate about Back. While either
    // player is up, Back belongs to its own screen (chrome dismissal, then close).
    BackHandler(
        enabled = detailsOpen && !signOut.confirming && !trailerOpen && !playerOpen &&
            !moreMenuOpen && !playbackSettingsOpen,
    ) {
        val origin = detailsOrigin
        detailsOrigin = null
        onCloseDetails()
        // In the callback, not an effect: the overlay's nodes are disposed in the same frame,
        // and a late effect would request focus on a detached requester (section 9.3).
        val returnRequester = when (origin) {
            is DetailsOrigin.Rail -> railReturnRequesters.getValue(origin.rail)
                .takeIf { currentDestination == IglooDestination.Home }
            DetailsOrigin.MoviesGrid -> moviesReturnRequester
                .takeIf { currentDestination == IglooDestination.Movies }
            else -> null
        }
        // A rail whose list changed while the overlay was open — a refresh that dropped the
        // movie — can leave its anchor uncomposed, and requesting an unattached requester
        // throws. Landing on the pane's anchor is a worse restore than the card, and a far
        // better outcome than crashing on Back.
        if (returnRequester == null || !returnRequester.requestFocusSafely()) {
            contentStartRequester.requestFocusSafely()
        }
    }
    BackHandler(
        enabled = !detailsOpen && !signOut.confirming && !trailerOpen && !playerOpen &&
            !railHasFocus,
    ) {
        railOpenedByBack = true
        navigationRequesters.getValue(currentDestination).requestFocus()
    }
    BackHandler(
        enabled = !detailsOpen && !signOut.confirming && !trailerOpen && !playerOpen &&
            railHasFocus && !railOpenedByBack,
    ) {
        contentStartRequester.requestFocus()
    }
    // railHasFocus && railOpenedByBack: no handler enabled, so Back exits the app.

    Box(modifier = Modifier.fillMaxSize()) {
        IglooShell(
            user = user,
            serverOrigin = serverOrigin,
            currentDestination = currentDestination,
            home = home,
            movies = movies,
            moviesActions = moviesActions,
            // The details header owns the notice while the overlay is up; rendering it here too
            // would only shift Home's rails behind a screen nobody can see. It surfaces here
            // when Back closes an overlay whose write had already failed.
            mutationNotice = details.mutationNotice.takeIf { !detailsOpen },
            onRetryRail = onRetryRail,
            openMovie = openMovie,
            openTheaterMovie = openTheaterMovie,
            railReturnRequesters = railReturnRequesters,
            moviesReturnRequester = moviesReturnRequester,
            // The rail stays open behind the dialog: the row that opened it must still be legible,
            // so the focus it gets back on cancel is not a surprise.
            railExpanded = railHasFocus || signOut.confirming,
            // Keep the hidden animation state at 0.60 so cancellation restores the rail scrim in
            // the same frame; IglooShell unmounts its actual draw node for the modal's lifetime.
            scrimmed = railHasFocus || signOut.confirming,
            // The overlay covers the shell completely, so the whole thing leaves TalkBack's
            // traversal while it is up — the same treatment the confirm dialog gets.
            hiddenFromAccessibility = signOut.confirming || detailsOpen,
            onRailFocusChanged = { hasFocus ->
                if (!hasFocus) railOpenedByBack = false
                railHasFocus = hasFocus
            },
            contentStartRequester = contentStartRequester,
            signOutRequester = signOutRequester,
            navigationRequesters = navigationRequesters,
            onDestinationSelected = { currentDestinationName = it.name },
            onSwitchProfile = onSwitchProfile,
            onSignOut = onSignOut,
            signOut = signOut,
            onSignOutConfirm = onSignOutConfirm,
            // Restoring focus is the invoker's job and belongs in the callback, not an effect: on
            // the success path `confirming` clears in the same frame this whole arm is disposed,
            // and a late effect would call requestFocus() on a detached requester. See section 9.3.
            onSignOutDismiss = {
                onSignOutDismiss()
                signOutRequester.requestFocus()
            },
        )

        // Drawn over the rail so nothing clips its focus glow. The shell stays composed
        // underneath: its rails keep their scroll and focus memory, which is what Back restores
        // onto. The same reasoning stacks once more: while the trailer player is up the details
        // screen stays composed (its extras rail holds the focus memory the player's close
        // restores onto) but leaves TalkBack traversal, exactly as the shell does under it.
        if (detailsOpen) {
            // hideFromAccessibility, not clearAndSetSemantics, for the same reason as the shell:
            // the nodes stay in the tree, so a test can still assert what is not traversable.
            Box(
                modifier = Modifier
                    .testTag("details_layer")
                    .then(
                        if (trailerOpen || playerOpen) {
                            Modifier.semantics { hideFromAccessibility() }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                MovieDetailsScreen(
                    state = details.details,
                    actions = detailsActions,
                    isAdmin = user.isAdmin,
                    onPlay = {
                        deferredPlayArmed = true
                        onRequestPlayback()
                    },
                    playReturnRequester = playReturnRequester,
                    onPlayVideo = { video, site ->
                        // A cheap invariant, not a reachable path today — the extras rail is
                        // unfocusable while the menu is up — so a stale open flag can never
                        // survive an overlay swap.
                        deferredPlayArmed = false
                        moreMenuOpen = false
                        trailerRequest = TrailerRequest(
                            key = video.key,
                            title = video.title,
                            typeLabel = video.typeLabel,
                            origin = site,
                        )
                    },
                    extrasReturnRequester = extrasReturnRequester,
                    heroTrailerReturnRequester = heroTrailerReturnRequester,
                    moreMenuOpen = moreMenuOpen,
                    onOpenMoreMenu = {
                        deferredPlayArmed = false
                        moreMenuOpen = true
                    },
                    // In the callback, not an effect, like every overlay's focus restore
                    // (section 9.3). The trigger is still composed in every reachable case;
                    // the pane anchor is the same last-resort fallback the other overlays use.
                    // The playbackSettingsOpen check is the menu→dialog hand-off: the menu item
                    // fires its action *before* this dismiss (menuItem's documented order), so
                    // the flag is already set and the restore is suppressed — the dialog's own
                    // entry effect takes focus instead of a transient frame on the trigger.
                    onDismissMoreMenu = {
                        moreMenuOpen = false
                        if (!playbackSettingsOpen && !moreReturnRequester.requestFocusSafely()) {
                            contentStartRequester.requestFocusSafely()
                        }
                    },
                    moreRequester = moreReturnRequester,
                    playbackSettingsOpen = playbackSettingsOpen,
                    onOpenPlaybackSettings = {
                        deferredPlayArmed = false
                        playbackSettingsOpen = true
                    },
                    // The dialog's dismiss restores to the More trigger — the surviving control
                    // that led away; the menu it passed through is long gone.
                    onDismissPlaybackSettings = {
                        playbackSettingsOpen = false
                        if (!moreReturnRequester.requestFocusSafely()) {
                            contentStartRequester.requestFocusSafely()
                        }
                    },
                    mutationNotice = details.mutationNotice,
                    progressSyncError = progressSyncError
                        .takeIf { detailsActions is MovieDetailsActions.Library },
                    onRetryProgressSync = moviePlayerViewModel::retryFailedSave,
                    spokenAccessibilityEnabled = spokenAccessibilityEnabled,
                )
            }
        }

        // Last children: the players draw over everything, and their own BackHandlers
        // out-register the host's gated ones while mounted. The two are never up together —
        // the trailer launches from surfaces the movie player covers, and vice versa.
        trailerRequest?.let { request ->
            TrailerPlayerScreen(
                videoKey = request.key,
                title = request.title,
                typeLabel = request.typeLabel,
                onClose = closeTrailer,
                engineFactory = trailerEngineFactory,
            )
        }
        moviePlayRequest?.let { request ->
            MoviePlayerScreen(
                request = request,
                viewModel = moviePlayerViewModel,
                onClose = closeMoviePlayer,
                onPlaybackModeRequested = { mode ->
                    moviePlayRequest = moviePlayRequest?.copy(mode = mode)
                },
                onTrackSelectionChanged = { audioTypeIndex, subtitleTypeIndex ->
                    moviePlayRequest = moviePlayRequest?.let { current ->
                        if (
                            current.audioTypeIndex == audioTypeIndex &&
                            current.subtitleTypeIndex == subtitleTypeIndex
                        ) {
                            current
                        } else {
                            current.copy(
                                audioTypeIndex = audioTypeIndex,
                                subtitleTypeIndex = subtitleTypeIndex,
                            )
                        }
                    }
                },
                engineFactory = moviePlayerEngineFactory,
            )
        }
    }

    // Land in the content pane with the rail at rest: the library is the first thing seen
    // and the first D-pad press moves focus instead of creating it. Once only: the content
    // pane outlives a destination change, so re-anchoring here would steal focus from the
    // card the user had just activated. Skipped entirely when something is already over the
    // shell — this effect runs after the overlay's own, so it would take focus off it.
    LaunchedEffect(Unit) {
        if (!detailsOpen && !trailerOpen && !playerOpen) contentStartRequester.requestFocus()
    }
}

@Composable
private fun IglooShell(
    user: AuthUser,
    serverOrigin: String,
    currentDestination: IglooDestination,
    home: HomeUiState,
    movies: MoviesUiState,
    moviesActions: MoviesActions,
    mutationNotice: String?,
    onRetryRail: (HomeRail) -> Unit,
    openMovie: ((DetailsOrigin, Long) -> Unit)?,
    openTheaterMovie: ((Long) -> Unit)?,
    railReturnRequesters: Map<HomeRail, FocusRequester>,
    moviesReturnRequester: FocusRequester,
    railExpanded: Boolean,
    scrimmed: Boolean,
    hiddenFromAccessibility: Boolean,
    onRailFocusChanged: (Boolean) -> Unit,
    contentStartRequester: FocusRequester,
    signOutRequester: FocusRequester,
    navigationRequesters: Map<IglooDestination, FocusRequester>,
    onDestinationSelected: (IglooDestination) -> Unit,
    onSwitchProfile: () -> Unit,
    onSignOut: () -> Unit,
    signOut: SignOutUiState,
    onSignOutConfirm: () -> Unit,
    onSignOutDismiss: () -> Unit,
) {
    val colors = IglooTheme.colors
    val layout = IglooTheme.layout

    // The rail's real layout width animates between its two authored states; the content
    // pane is padded by the collapsed width only, so expansion overlays it and the content
    // never reflows. Under reduced motion both drivers snap.
    val railWidth by animateDpAsState(
        targetValue = if (railExpanded) layout.navRailExpandedWidth else layout.navRailCollapsedWidth,
        animationSpec = iglooTween(
            durationMillis = IglooMotion.STANDARD_MS,
            easing = if (railExpanded) IglooEasing.standard else IglooEasing.exit,
        ),
        label = "railWidth",
    )
    val scrimAlpha by animateFloatAsState(
        targetValue = if (scrimmed) SCRIM_ALPHA else 0f,
        animationSpec = iglooTween(IglooMotion.STANDARD_MS),
        label = "railScrim",
    )

    // Backgrounds bleed to the physical edge; only chrome and text are inset for overscan.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        // One group so an overlay can hide the entire shell from TalkBack traversal at once.
        // hideFromAccessibility, not clearAndSetSemantics: the nodes stay in the semantics tree,
        // so a test can still assert the rail is not focused while the overlay is open.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag("shell_content")
                .then(
                    if (hiddenFromAccessibility) {
                        Modifier.semantics { hideFromAccessibility() }
                    } else {
                        Modifier
                    },
                ),
        ) {
            ContentPane(
                currentDestination = currentDestination,
                home = home,
                movies = movies,
                moviesActions = moviesActions,
                mutationNotice = mutationNotice,
                onRetryRail = onRetryRail,
                openMovie = openMovie,
                openTheaterMovie = openTheaterMovie,
                railReturnRequesters = railReturnRequesters,
                moviesReturnRequester = moviesReturnRequester,
                contentStartRequester = contentStartRequester,
                navigationRequesters = navigationRequesters,
                // The pane fills the panel and applies no gutter of its own. It used to box every
                // screen into 752x486dp, which is where the dead margins came from. The gutter is
                // handed down as contentInset instead, so each section applies the inset it owes
                // — and art, which owes none, reaches the physical edge (sections 2.5, 8.3).
                modifier = Modifier.fillMaxSize(),
            )
            // The modal owns the only scrim while mounted. Removing this node immediately avoids
            // compositing the rail's animated 0.60 layer under the dialog reveal.
            if (!signOut.confirming) {
                IglooScrim(alpha = scrimAlpha)
            }
            NavigationRail(
                user = user,
                serverOrigin = serverOrigin,
                expanded = railExpanded,
                currentDestination = currentDestination,
                contentStartRequester = contentStartRequester,
                signOutRequester = signOutRequester,
                navigationRequesters = navigationRequesters,
                // Activating a destination hands focus to the content it just chose — that focus
                // move is also what collapses the rail. Cards in the pane keep the plain callback,
                // so activating one never steals focus from it. The synchronous request is only
                // safe while the pane keeps its current tree: during this callback the requester
                // still points at the outgoing branch's node, and focusing a node the swap is
                // about to dispose hands focus to the platform's fallback (the first rail row)
                // instead of the new anchor. Cross-branch, focus stays on the rail row — which
                // survives — and ContentPane claims the anchor once the new branch is composed.
                onDestinationSelected = { destination ->
                    val sameBranch =
                        paneBranchOf(destination) == paneBranchOf(currentDestination)
                    onDestinationSelected(destination)
                    if (sameBranch) contentStartRequester.requestFocus()
                },
                onSwitchProfile = onSwitchProfile,
                onSignOut = onSignOut,
                modifier = Modifier
                    .fillMaxHeight()
                    .width(railWidth)
                    .onFocusChanged { onRailFocusChanged(it.hasFocus) }
                    .testTag("navigation_rail"),
            )
        }

        // Last child, so it draws over the rail and nothing clips the confirm button's glow.
        if (signOut.confirming) {
            IglooConfirmDialog(
                title = "Sign out of Igloo?",
                body = "${user.name} will be removed from this TV. You'll need to sign in " +
                    "again to watch here.",
                confirmText = "Sign out",
                dismissText = "Cancel",
                confirmVariant = IglooButtonVariant.Destructive,
                pending = signOut.pending,
                pendingText = "Signing out…",
                onConfirm = onSignOutConfirm,
                onDismiss = onSignOutDismiss,
            )
        }
    }
}

@Composable
private fun ContentPane(
    currentDestination: IglooDestination,
    home: HomeUiState,
    movies: MoviesUiState,
    moviesActions: MoviesActions,
    mutationNotice: String?,
    onRetryRail: (HomeRail) -> Unit,
    openMovie: ((DetailsOrigin, Long) -> Unit)?,
    openTheaterMovie: ((Long) -> Unit)?,
    railReturnRequesters: Map<HomeRail, FocusRequester>,
    moviesReturnRequester: FocusRequester,
    contentStartRequester: FocusRequester,
    navigationRequesters: Map<IglooDestination, FocusRequester>,
    modifier: Modifier = Modifier,
) {
    // Hoisted above the destination branch so a Home -> Movies -> Home round trip still knows
    // the card to restore (section 6.3), and saveable so process death does not forget it.
    // One map keyed by rail: each rail keeps its own focus memory, and a new rail is one entry
    // instead of another var/callback pair threaded through every signature.
    val lastFocusedByRail = rememberSaveable(
        saver = listSaver<SnapshotStateMap<HomeRail, Long>, Any>(
            save = { map -> map.flatMap { (rail, id) -> listOf(rail.name, id) } },
            restore = { saved ->
                mutableStateMapOf<HomeRail, Long>().apply {
                    saved.chunked(2).forEach { (rail, id) ->
                        put(HomeRail.valueOf(rail as String), id as Long)
                    }
                }
            },
        ),
    ) { mutableStateMapOf() }

    // Hoisted beside lastFocusedByRail and for the same reason: a Movies -> Home -> Movies round
    // trip must land on the same cell. A plain var rather than another map entry — HomeRail names
    // Home's rails, and the library grid is not one of them.
    var lastFocusedMovieId by rememberSaveable { mutableStateOf<Long?>(null) }
    // ContentPane's `when` has no SaveableStateHolder, so a rememberSaveable inside the removed
    // subtree is discarded on a destination switch. Held here, the grid's scroll position
    // survives a trip to Home and back.
    val moviesGridState = rememberLazyGridState()

    // The pane's one gutter, handed to the sections instead of applied here: the collapsed rail
    // plus a reading gutter on the start, the overscan inset on the end. Chrome and text take it;
    // a backdrop and a rail's scroll surface deliberately do not (sections 2.5, 8.3).
    val contentInset = PaddingValues(
        start = IglooTheme.layout.navRailCollapsedWidth + IglooTheme.spacing.xl,
        end = IglooTheme.layout.safeAreaHorizontal,
    )

    Box(
        modifier = modifier.testTag("content_pane").semantics {
            // Rail and content are each a traversal group, so TalkBack reads one block at a
            // time instead of geometrically interleaving rows that share a y position.
            isTraversalGroup = true
            // The pane header that used to carry the destination name went with the boxed layout
            // — Home's hero is full-bleed now and starts at the top edge, so no text node holds
            // it any more. Activating a nav row swaps the pane without moving focus, so this is
            // the only thing left that can tell TalkBack the destination changed at all. A pane
            // title rather than a live region on a hidden node: the platform fires
            // CONTENT_CHANGE_TYPE_PANE_TITLE on the value change, it is the mechanism the menu,
            // both dialogs and both overlays already use, and it does not put a second node
            // carrying the destination's name into the tree to collide with the rail's own row.
            paneTitle = currentDestination.label
        },
    ) {
        when (currentDestination) {
            IglooDestination.Home -> HomeRails(
                home = home,
                mutationNotice = mutationNotice,
                contentInset = contentInset,
                onRetryRail = onRetryRail,
                openMovie = openMovie,
                openTheaterMovie = openTheaterMovie,
                railReturnRequesters = railReturnRequesters,
                contentStartRequester = contentStartRequester,
                navigationRequester = navigationRequesters.getValue(IglooDestination.Home),
                lastFocusedByRail = lastFocusedByRail,
            )

            IglooDestination.Movies -> MoviesScreen(
                state = movies,
                actions = moviesActions,
                contentInset = contentInset,
                gridState = moviesGridState,
                contentStartRequester = contentStartRequester,
                navigationRequester = navigationRequesters.getValue(IglooDestination.Movies),
                returnRequester = moviesReturnRequester,
                lastFocusedMovieId = lastFocusedMovieId,
                onMovieFocused = { lastFocusedMovieId = it },
                // The pane's mutationNotice is the details overlay's write report; the grid has
                // its own notice for a refresh that failed, and two in one header would confuse.
                onMovieSelected = openMovie?.let { open ->
                    { movieId -> open(DetailsOrigin.MoviesGrid, movieId) }
                },
            )

            else -> PlaceholderContent(
                currentDestination = currentDestination,
                mutationNotice = mutationNotice,
                contentInset = contentInset,
                contentStartRequester = contentStartRequester,
                navigationRequester = navigationRequesters.getValue(currentDestination),
            )
        }
    }

    // Each branch puts contentStartRequester on a different node, so on a cross-branch switch
    // the shell leaves focus on the rail row (see IglooShell) and the pane claims the anchor
    // here, once the incoming branch's node exists. Deliberately keyed on the branch and not
    // the destination: within the placeholder branch the anchor persists, and activating a card
    // there must keep focus where the user put it.
    var paneBranch by remember { mutableStateOf(paneBranchOf(currentDestination)) }
    LaunchedEffect(currentDestination) {
        val branch = paneBranchOf(currentDestination)
        if (branch != paneBranch) {
            paneBranch = branch
            contentStartRequester.requestFocus()
        }
    }
}

/** Which of ContentPane's trees a destination renders; the focus anchor moves with the branch. */
private enum class PaneBranch { Home, Movies, Placeholder }

private fun paneBranchOf(destination: IglooDestination): PaneBranch = when (destination) {
    IglooDestination.Home -> PaneBranch.Home
    IglooDestination.Movies -> PaneBranch.Movies
    else -> PaneBranch.Placeholder
}

@Composable
private fun HomeRails(
    home: HomeUiState,
    mutationNotice: String?,
    contentInset: PaddingValues,
    onRetryRail: (HomeRail) -> Unit,
    openMovie: ((DetailsOrigin, Long) -> Unit)?,
    openTheaterMovie: ((Long) -> Unit)?,
    railReturnRequesters: Map<HomeRail, FocusRequester>,
    contentStartRequester: FocusRequester,
    navigationRequester: FocusRequester,
    lastFocusedByRail: MutableMap<HomeRail, Long>,
) {
    // The hero owns the pane's entry anchor whenever it is visible; the Continue Watching rail
    // takes it back when the hero hides (section 11.3.1). heroVisible gates both attachment
    // sites in the same composition, so the requester is never on two nodes at once.
    val heroVisible = home.hero !is HomeHeroState.Hidden
    // The hero's d-pad down target: attached to the Continue rail's entry anchor in every rail
    // state, so down always lands where spine re-entry would.
    val continueEntryRequester = remember { FocusRequester() }
    var heroHasFocus by remember { mutableStateOf(false) }
    // Captured during the composition that swaps hero states — the same trap IglooMediaRail
    // documents: the outgoing node only detaches once the composition applies, so this still
    // sees whether the hero owned focus going in. By the time the effect runs the requester
    // already sits on the incoming hero node, or on the Continue rail's anchor if the hero hid.
    val heroHadFocusAtSwap = remember(home.hero) { heroHasFocus }
    LaunchedEffect(home.hero) {
        if (heroHadFocusAtSwap) {
            contentStartRequester.requestFocus()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.lg),
    ) {
        // Inside the scroll and above the hero: it is text, so it owes the inset, and it scrolls
        // away with the content rather than permanently costing the hero its top edge. The hero
        // holds entry focus, so the scroll is at 0 and the notice is on screen when it exists.
        if (mutationNotice != null) {
            IglooNotice(
                text = mutationNotice,
                modifier = Modifier
                    .padding(contentInset)
                    .padding(top = IglooTheme.layout.safeAreaVertical)
                    .testTag("shell_mutation_notice"),
            )
        }

        if (heroVisible) {
            HomeHero(
                state = home.hero,
                entryRequester = contentStartRequester,
                leftFocusRequester = navigationRequester,
                downFocusRequester = continueEntryRequester,
                contentInset = contentInset,
                onSelect = openMovie?.let { open ->
                    { movieId -> open(DetailsOrigin.Hero, movieId) }
                },
                modifier = Modifier.onFocusChanged { heroHasFocus = it.hasFocus },
            )
        }

        IglooMediaRail(
            contentInset = contentInset,
            title = "Continue Watching",
            state = home.continueWatching,
            itemKey = { it.movie.id },
            entryRequester = if (heroVisible) continueEntryRequester else contentStartRequester,
            leftFocusRequester = navigationRequester,
            lastFocusedKey = lastFocusedByRail[HomeRail.ContinueWatching],
            onItemFocused = { lastFocusedByRail[HomeRail.ContinueWatching] = it },
            loadingLabel = "Loading continue watching",
            emptyIcon = IglooIcons.Movies,
            emptyText = "Nothing in progress yet. Movies you start watching appear here.",
            onRetry = { onRetryRail(HomeRail.ContinueWatching) },
            returnRequester = railReturnRequesters.getValue(HomeRail.ContinueWatching),
        ) { item, itemModifier, cardAspect ->
            IglooPosterCard(
                title = item.movie.title,
                subtitle = item.movie.year?.toString(),
                imageUrl = item.movie.posterUrl,
                onClick = openMovie?.let { open ->
                    { open(DetailsOrigin.Rail(HomeRail.ContinueWatching), item.movie.id) }
                },
                progress = PosterCardProgress(item.progressFraction, item.progressDescription),
                aspect = cardAspect,
                modifier = itemModifier.testTag("continue_card_${item.movie.id}"),
            )
        }

        IglooMediaRail(
            contentInset = contentInset,
            title = "Recently Added Movies",
            state = home.latestMovies,
            itemKey = { it.id },
            entryRequester = null,
            leftFocusRequester = navigationRequester,
            lastFocusedKey = lastFocusedByRail[HomeRail.LatestMovies],
            onItemFocused = { lastFocusedByRail[HomeRail.LatestMovies] = it },
            loadingLabel = "Loading recently added movies",
            emptyIcon = IglooIcons.Movies,
            emptyText = "No movies in your library yet. Add a movies folder on the server and run a scan.",
            onRetry = { onRetryRail(HomeRail.LatestMovies) },
            returnRequester = railReturnRequesters.getValue(HomeRail.LatestMovies),
        ) { movie, itemModifier, cardAspect ->
            IglooPosterCard(
                title = movie.title,
                subtitle = movie.year?.toString(),
                imageUrl = movie.posterUrl,
                onClick = openMovie?.let { open ->
                    { open(DetailsOrigin.Rail(HomeRail.LatestMovies), movie.id) }
                },
                aspect = cardAspect,
                modifier = itemModifier.testTag("poster_card_${movie.id}"),
            )
        }

        IglooMediaRail(
            contentInset = contentInset,
            title = "Recently Added Albums",
            state = home.latestAlbums,
            itemKey = { it.id },
            entryRequester = null,
            leftFocusRequester = navigationRequester,
            lastFocusedKey = lastFocusedByRail[HomeRail.LatestAlbums],
            onItemFocused = { lastFocusedByRail[HomeRail.LatestAlbums] = it },
            loadingLabel = "Loading recently added albums",
            emptyIcon = IglooIcons.Music,
            emptyText = "No albums in your library yet. Add a music folder on the server and run a scan.",
            onRetry = { onRetryRail(HomeRail.LatestAlbums) },
            cardAspect = IglooTheme.layout.albumAspect,
        ) { album, itemModifier, cardAspect ->
            IglooPosterCard(
                title = album.title,
                subtitle = album.musician,
                imageUrl = album.coverUrl,
                // Focusable but inert: album detail has no destination yet, and a card that
                // announces "Open …" and then does nothing is worse than one that announces none.
                onClick = null,
                aspect = cardAspect,
                fallbackIcon = IglooIcons.Music,
                modifier = itemModifier.testTag("album_card_${album.id}"),
            )
        }

        IglooMediaRail(
            contentInset = contentInset,
            title = "Now Playing in Theaters",
            state = home.inTheaters,
            itemKey = { it.id },
            entryRequester = null,
            leftFocusRequester = navigationRequester,
            lastFocusedKey = lastFocusedByRail[HomeRail.InTheaters],
            onItemFocused = { lastFocusedByRail[HomeRail.InTheaters] = it },
            loadingLabel = "Loading movies in theaters",
            emptyIcon = IglooIcons.Movies,
            emptyText = "No movies are playing in theaters right now. Check back later.",
            onRetry = { onRetryRail(HomeRail.InTheaters) },
            returnRequester = railReturnRequesters.getValue(HomeRail.InTheaters),
        ) { movie, itemModifier, cardAspect ->
            InTheatersCard(
                movie = movie,
                aspect = cardAspect,
                onClick = openTheaterMovie?.let { open -> { open(movie.id) } },
                modifier = itemModifier.testTag("theater_card_${movie.id}"),
            )
        }
    }
}

/**
 * The non-Home destinations, until each grows a real screen. Full-bleed like the hero, so the
 * shell reads as one product rather than as a dashboard bolted onto a TV: the destination's own
 * name and supporting line over the ambient backdrop, bottom-left on the pane's gutter.
 *
 * One focus target, and it is inert — the same contract as an empty rail (section 10) and an
 * actionless poster card. The pane's focus model requires an anchor in every state; it does not
 * require that the anchor go anywhere, and a card announcing an action it cannot perform would
 * be worse than one announcing none.
 */
@Composable
private fun PlaceholderContent(
    currentDestination: IglooDestination,
    mutationNotice: String?,
    contentInset: PaddingValues,
    contentStartRequester: FocusRequester,
    navigationRequester: FocusRequester,
) {
    val colors = IglooTheme.colors
    val direction = LocalLayoutDirection.current
    // Constant rather than rememberAmbientProgress(): section 7.2's one-loop carve-out names the
    // welcome screen alone, and a destination the user sits on is exactly where drift would be
    // motion in the periphery with nothing to say.
    val still = remember { mutableFloatStateOf(0f) }
    var focused by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .iglooAuroraBackdrop(still),
    ) {
        if (mutationNotice != null) {
            IglooNotice(
                text = mutationNotice,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(contentInset)
                    .padding(top = IglooTheme.layout.safeAreaVertical)
                    .testTag("shell_mutation_notice"),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(
                    start = contentInset.calculateStartPadding(direction),
                    end = contentInset.calculateEndPadding(direction),
                    bottom = IglooTheme.layout.safeAreaVertical,
                )
                .widthIn(max = PLACEHOLDER_TEXT_MAX_WIDTH.scaled())
                .focusRing(
                    focused = focused,
                    radius = IglooTheme.radius.lg,
                    scaleOnFocus = false,
                )
                .focusRequester(contentStartRequester)
                .focusProperties {
                    left = navigationRequester
                    right = FocusRequester.Cancel
                    up = FocusRequester.Cancel
                    down = FocusRequester.Cancel
                }
                .onFocusChanged { focused = it.isFocused }
                .focusable()
                .clearAndSetSemantics {
                    heading()
                    contentDescription =
                        "${currentDestination.label}. ${currentDestination.supportingText}"
                }
                .padding(IglooTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
        ) {
            IglooText(
                text = currentDestination.label,
                style = IglooTheme.typography.display,
                color = colors.foreground,
            )
            IglooText(
                text = currentDestination.supportingText,
                style = IglooTheme.typography.bodyLarge,
                color = colors.foreground,
            )
        }
    }
}

/** Readable measure for the supporting line at bodyLarge; one-off per section 2.8. */
private val PLACEHOLDER_TEXT_MAX_WIDTH = 520.dp
