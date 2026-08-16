package com.igloo.blindpenguincoder.feature.home

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.core.ui.IglooScrim
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.PosterCardProgress
import com.igloo.blindpenguincoder.core.ui.SCRIM_ALPHA
import com.igloo.blindpenguincoder.core.ui.focusRing
import com.igloo.blindpenguincoder.core.ui.iglooSurface
import com.igloo.blindpenguincoder.data.model.AuthUser
import com.igloo.blindpenguincoder.feature.movies.MovieDetailsActions
import com.igloo.blindpenguincoder.feature.movies.MovieDetailsScreen
import com.igloo.blindpenguincoder.feature.movies.MovieDetailsUiState
import com.igloo.blindpenguincoder.feature.player.TrailerPlayerScreen
import com.igloo.blindpenguincoder.playback.youtube.TrailerPlayerEngine
import com.igloo.blindpenguincoder.playback.youtube.youTubeIFrameEngine

/** Which surface opened the details overlay, so Back can put focus back where it came from. */
private sealed interface DetailsOrigin {
    data object Hero : DetailsOrigin
    data class Rail(val rail: HomeRail) : DetailsOrigin

    companion object {
        /** One string, because the overlay outlives activity recreation but `remember` does not. */
        val Saver: Saver<DetailsOrigin?, String> = Saver(
            save = { origin ->
                when (origin) {
                    is Rail -> origin.rail.name
                    Hero -> HERO
                    null -> NONE
                }
            },
            restore = { saved ->
                when (saved) {
                    NONE -> null
                    HERO -> Hero
                    else -> Rail(HomeRail.valueOf(saved))
                }
            },
        )

        private const val HERO = "hero"
        private const val NONE = "none"
    }
}

/** Requests focus unless the requester has no node attached; reports whether it landed. */
private fun FocusRequester.requestFocusSafely(): Boolean =
    runCatching { requestFocus() }.isSuccess

/** What the trailer player overlay is playing: only what its screen renders, saveable so the
 * overlay survives activity recreation (the trailer itself restarts — a WebView cannot be
 * parceled, and a trailer losing its position is an accepted trade). */
private data class TrailerRequest(val key: String, val title: String, val typeLabel: String) {
    companion object {
        val Saver: Saver<TrailerRequest?, List<String>> = Saver(
            save = { request ->
                if (request == null) emptyList() else listOf(request.key, request.title, request.typeLabel)
            },
            restore = { saved ->
                if (saved.isEmpty()) null else TrailerRequest(saved[0], saved[1], saved[2])
            },
        )
    }
}

@Composable
fun IglooApp(
    user: AuthUser,
    serverOrigin: String,
    signOut: SignOutUiState,
    home: HomeUiState,
    details: MovieDetailsUiState,
    detailsActions: MovieDetailsActions,
    onRetryRail: (HomeRail) -> Unit,
    onMovieSelected: ((Long) -> Unit)?,
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
    // Parked by the extras rail on its last-focused card, so closing the player restores focus
    // to the exact card that launched it (section 6.3).
    val extrasReturnRequester = remember { FocusRequester() }
    val closeTrailer = {
        trailerRequest = null
        // In the callback, not an effect, for the detach-race reason the details close documents.
        // The extras rail is still composed in every reachable case — the player only opens from
        // it, and nothing that runs under the player removes extras — but if the anchor is gone
        // anyway, the pane's anchor is a worse restore than the card and far better than a crash.
        if (!extrasReturnRequester.requestFocusSafely()) {
            contentStartRequester.requestFocusSafely()
        }
    }

    val openMovie: ((DetailsOrigin, Long) -> Unit)? = onMovieSelected?.let { select ->
        { origin, movieId ->
            detailsOrigin = origin
            select(movieId)
        }
    }

    // Every handler is gated explicitly rather than left to win on registration order —
    // design-system.md section 9.3 requires the host to be deliberate about Back. While the
    // trailer player is up, Back belongs to its own screen (chrome dismissal, then close).
    BackHandler(enabled = detailsOpen && !signOut.confirming && !trailerOpen) {
        val origin = detailsOrigin
        detailsOrigin = null
        onCloseDetails()
        // In the callback, not an effect: the overlay's nodes are disposed in the same frame,
        // and a late effect would request focus on a detached requester (section 9.3).
        val returnRequester = (origin as? DetailsOrigin.Rail)
            ?.takeIf { currentDestination == IglooDestination.Home }
            ?.let { railReturnRequesters.getValue(it.rail) }
        // A rail whose list changed while the overlay was open — a refresh that dropped the
        // movie — can leave its anchor uncomposed, and requesting an unattached requester
        // throws. Landing on the pane's anchor is a worse restore than the card, and a far
        // better outcome than crashing on Back.
        if (returnRequester == null || !returnRequester.requestFocusSafely()) {
            contentStartRequester.requestFocusSafely()
        }
    }
    BackHandler(enabled = !detailsOpen && !signOut.confirming && !trailerOpen && !railHasFocus) {
        railOpenedByBack = true
        navigationRequesters.getValue(currentDestination).requestFocus()
    }
    BackHandler(
        enabled = !detailsOpen && !signOut.confirming && !trailerOpen &&
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
            // The details header owns the notice while the overlay is up; rendering it here too
            // would only shift Home's rails behind a screen nobody can see. It surfaces here
            // when Back closes an overlay whose write had already failed.
            mutationNotice = details.mutationNotice.takeIf { !detailsOpen },
            onRetryRail = onRetryRail,
            openMovie = openMovie,
            railReturnRequesters = railReturnRequesters,
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
                        if (trailerOpen) {
                            Modifier.semantics { hideFromAccessibility() }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                MovieDetailsScreen(
                    state = details.details,
                    actions = detailsActions,
                    onPlayExtra = { video ->
                        trailerRequest = TrailerRequest(video.key, video.title, video.typeLabel)
                    },
                    extrasReturnRequester = extrasReturnRequester,
                    mutationNotice = details.mutationNotice,
                )
            }
        }

        // Last child: the player draws over everything, and its own BackHandler out-registers
        // the host's gated ones while it is mounted.
        trailerRequest?.let { request ->
            TrailerPlayerScreen(
                videoKey = request.key,
                title = request.title,
                typeLabel = request.typeLabel,
                onClose = closeTrailer,
                engineFactory = trailerEngineFactory,
            )
        }
    }

    // Land in the content pane with the rail at rest: the library is the first thing seen
    // and the first D-pad press moves focus instead of creating it. Once only: the content
    // pane outlives a destination change, so re-anchoring here would steal focus from the
    // card the user had just activated. Skipped entirely when something is already over the
    // shell — this effect runs after the overlay's own, so it would take focus off it.
    LaunchedEffect(Unit) {
        if (!detailsOpen && !trailerOpen) contentStartRequester.requestFocus()
    }
}

@Composable
private fun IglooShell(
    user: AuthUser,
    serverOrigin: String,
    currentDestination: IglooDestination,
    home: HomeUiState,
    mutationNotice: String?,
    onRetryRail: (HomeRail) -> Unit,
    openMovie: ((DetailsOrigin, Long) -> Unit)?,
    railReturnRequesters: Map<HomeRail, FocusRequester>,
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
                mutationNotice = mutationNotice,
                onRetryRail = onRetryRail,
                openMovie = openMovie,
                railReturnRequesters = railReturnRequesters,
                contentStartRequester = contentStartRequester,
                navigationRequesters = navigationRequesters,
                onDestinationSelected = onDestinationSelected,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = layout.navRailCollapsedWidth + IglooTheme.spacing.xl,
                        end = layout.safeAreaHorizontal,
                        top = layout.safeAreaVertical,
                        bottom = layout.safeAreaVertical,
                    ),
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
                        paneBranchIsHome(destination) == paneBranchIsHome(currentDestination)
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
    mutationNotice: String?,
    onRetryRail: (HomeRail) -> Unit,
    openMovie: ((DetailsOrigin, Long) -> Unit)?,
    railReturnRequesters: Map<HomeRail, FocusRequester>,
    contentStartRequester: FocusRequester,
    navigationRequesters: Map<IglooDestination, FocusRequester>,
    onDestinationSelected: (IglooDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = IglooTheme.colors
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

    Column(
        // Rail and content are each a traversal group, so TalkBack reads one block at a
        // time instead of geometrically interleaving rows that share a y position.
        modifier = modifier.semantics { isTraversalGroup = true },
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.lg),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm)) {
                // A live region on the title, not a merged one over the pair: activating a nav
                // item or a card swaps this header without moving focus, so it is the only thing
                // that can tell TalkBack the destination changed at all — but collapsing the two
                // texts into one node would take the supporting text out of the tree.
                IglooText(
                    text = currentDestination.label,
                    style = IglooTheme.typography.titleLarge,
                    color = colors.foreground,
                    modifier = Modifier.semantics {
                        heading()
                        liveRegion = LiveRegionMode.Polite
                    },
                )
                IglooText(
                    text = currentDestination.supportingText,
                    style = IglooTheme.typography.bodyMedium,
                    color = colors.mutedForeground,
                )
            }
            StatusBadge()
        }

        if (mutationNotice != null) {
            IglooNotice(
                text = mutationNotice,
                modifier = Modifier.testTag("shell_mutation_notice"),
            )
        }

        when (currentDestination) {
            IglooDestination.Home -> HomeRails(
                home = home,
                onRetryRail = onRetryRail,
                openMovie = openMovie,
                railReturnRequesters = railReturnRequesters,
                contentStartRequester = contentStartRequester,
                navigationRequester = navigationRequesters.getValue(IglooDestination.Home),
                lastFocusedByRail = lastFocusedByRail,
            )

            else -> PlaceholderContent(
                currentDestination = currentDestination,
                contentStartRequester = contentStartRequester,
                navigationRequesters = navigationRequesters,
                onDestinationSelected = onDestinationSelected,
            )
        }
    }

    // The two branches put contentStartRequester on different nodes, so on a cross-branch
    // switch the shell leaves focus on the rail row (see IglooShell) and the pane claims the
    // anchor here, once the incoming branch's node exists. Deliberately keyed on the branch
    // and not the destination: within the placeholder branch the anchor persists, and
    // activating a card there must keep focus where the user put it.
    var paneOnHome by remember { mutableStateOf(paneBranchIsHome(currentDestination)) }
    LaunchedEffect(currentDestination) {
        val onHome = paneBranchIsHome(currentDestination)
        if (onHome != paneOnHome) {
            paneOnHome = onHome
            contentStartRequester.requestFocus()
        }
    }
}

/** Which of ContentPane's two trees a destination renders; the focus anchor moves with it. */
private fun paneBranchIsHome(destination: IglooDestination): Boolean =
    destination == IglooDestination.Home

@Composable
private fun HomeRails(
    home: HomeUiState,
    onRetryRail: (HomeRail) -> Unit,
    openMovie: ((DetailsOrigin, Long) -> Unit)?,
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
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.lg),
    ) {
        if (heroVisible) {
            HomeHero(
                state = home.hero,
                entryRequester = contentStartRequester,
                leftFocusRequester = navigationRequester,
                downFocusRequester = continueEntryRequester,
                onSelect = openMovie?.let { open ->
                    { movieId -> open(DetailsOrigin.Hero, movieId) }
                },
                modifier = Modifier.onFocusChanged { heroHasFocus = it.hasFocus },
            )
        }

        IglooMediaRail(
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
                progress = PosterCardProgress(item.progressFraction, item.progressLabel),
                aspect = cardAspect,
                modifier = itemModifier.testTag("continue_card_${item.movie.id}"),
            )
        }

        IglooMediaRail(
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
        ) { movie, itemModifier, cardAspect ->
            InTheatersCard(
                movie = movie,
                aspect = cardAspect,
                modifier = itemModifier.testTag("theater_card_${movie.id}"),
            )
        }
    }
}

@Composable
private fun PlaceholderContent(
    currentDestination: IglooDestination,
    contentStartRequester: FocusRequester,
    navigationRequesters: Map<IglooDestination, FocusRequester>,
    onDestinationSelected: (IglooDestination) -> Unit,
) {
    val colors = IglooTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.lg)) {
        HeroPanel()

        IglooText(
            text = "Start here",
            style = IglooTheme.typography.titleMedium,
            color = colors.foreground,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
        ) {
            FeatureCard(
                title = "Movies",
                body = "API contract loaded. Poster rails and playback will plug into this shell.",
                modifier = Modifier.weight(1f),
                focusRequester = contentStartRequester,
                leftFocusRequester = navigationRequesters.getValue(currentDestination),
                onClick = { onDestinationSelected(IglooDestination.Movies) },
            )
            FeatureCard(
                title = "Music",
                body = "Coil, Media3, Ktor, and DataStore are ready for feature work.",
                modifier = Modifier.weight(1f),
                onClick = { onDestinationSelected(IglooDestination.Music) },
            )
            FeatureCard(
                title = "Settings",
                body = "Server setup and theme persistence can be added without changing the app frame.",
                modifier = Modifier.weight(1f),
                onClick = { onDestinationSelected(IglooDestination.Settings) },
            )
        }
    }
}

@Composable
private fun StatusBadge() {
    val colors = IglooTheme.colors
    Box(
        modifier = Modifier
            .iglooSurface(
                radius = IglooTheme.radius.pill,
                fill = colors.aurora.copy(alpha = 0.16f),
                border = colors.aurora.copy(alpha = 0.48f),
            )
            .padding(horizontal = IglooTheme.spacing.md, vertical = IglooTheme.spacing.sm),
    ) {
        IglooText(
            text = "Base app",
            style = IglooTheme.typography.label,
            color = colors.aurora,
        )
    }
}

@Composable
private fun HeroPanel() {
    val colors = IglooTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .iglooSurface(radius = IglooTheme.radius.xl, fill = colors.card)
            .padding(IglooTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
    ) {
        IglooText(
            text = "Your library, ready",
            style = IglooTheme.typography.titleLarge,
            color = colors.cardForeground,
        )
        IglooText(
            text = "This Android TV foundation is remote-first, dark by default, and ready for the Igloo backend contract.",
            style = IglooTheme.typography.bodyLarge,
            color = colors.mutedForeground,
        )
    }
}

@Composable
private fun FeatureCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    leftFocusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    val colors = IglooTheme.colors
    var focused by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .heightIn(min = 178.dp.scaled())
            .focusRing(
                focused = focused,
                radius = IglooTheme.radius.xl,
                fill = if (focused) colors.card.copy(alpha = 0.96f) else colors.muted,
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .then(
                if (leftFocusRequester != null) {
                    Modifier.focusProperties {
                        left = leftFocusRequester
                    }
                } else {
                    Modifier
                },
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .clearAndSetSemantics {
                contentDescription = "$title. $body"
                role = Role.Button
                onClick(label = "Open $title") {
                    onClick()
                    true
                }
            }
            .padding(IglooTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
    ) {
        IglooText(
            text = title,
            style = IglooTheme.typography.titleMedium,
            color = colors.foreground,
            maxLines = 1,
        )
        IglooText(
            text = body,
            style = IglooTheme.typography.bodyMedium,
            color = colors.mutedForeground,
            maxLines = 4,
        )
    }
}
