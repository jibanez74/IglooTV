package com.igloo.blindpenguincoder.feature.home

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.igloo.blindpenguincoder.core.ui.IglooPosterCard
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.core.ui.IglooScrim
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.PosterCardProgress
import com.igloo.blindpenguincoder.core.ui.SCRIM_ALPHA
import com.igloo.blindpenguincoder.core.ui.focusRing
import com.igloo.blindpenguincoder.core.ui.iglooSurface
import com.igloo.blindpenguincoder.data.model.AuthUser

@Composable
fun IglooApp(
    user: AuthUser,
    signOut: SignOutUiState,
    home: HomeUiState,
    onRetryRail: (HomeRail) -> Unit,
    onMovieSelected: ((HomeMovie) -> Unit)?,
    onSwitchProfile: () -> Unit,
    onSignOut: () -> Unit,
    onSignOutConfirm: () -> Unit,
    onSignOutDismiss: () -> Unit,
) {
    var currentDestinationName by rememberSaveable { mutableStateOf(IglooDestination.Home.name) }
    val currentDestination = IglooDestination.valueOf(currentDestinationName)
    val contentStartRequester = remember { FocusRequester() }
    val signOutRequester = remember { FocusRequester() }
    val navigationRequesters = remember {
        PrimaryIglooDestinations.associateWith { FocusRequester() }
    }
    // The rail expands exactly while d-pad focus is inside it; railOpenedByBack remembers
    // whether the rail was entered with the Back button, so Back can mean "step outward":
    // content -> rail -> exit, but a rail entered by d-pad steps back into content instead.
    var railHasFocus by remember { mutableStateOf(false) }
    var railOpenedByBack by remember { mutableStateOf(false) }

    // Both are gated while the dialog is up, so Back reaches its own handler rather than winning
    // on registration order — design-system.md section 9.3 requires the host to be explicit.
    BackHandler(enabled = !signOut.confirming && !railHasFocus) {
        railOpenedByBack = true
        navigationRequesters.getValue(currentDestination).requestFocus()
    }
    BackHandler(enabled = !signOut.confirming && railHasFocus && !railOpenedByBack) {
        contentStartRequester.requestFocus()
    }
    // railHasFocus && railOpenedByBack: no handler enabled, so Back exits the app.

    IglooShell(
        user = user,
        currentDestination = currentDestination,
        home = home,
        onRetryRail = onRetryRail,
        onMovieSelected = onMovieSelected,
        // The rail stays open behind the dialog: the row that opened it must still be legible, so
        // the focus it gets back on cancel is not a surprise.
        railExpanded = railHasFocus || signOut.confirming,
        // Keep the hidden animation state at 0.60 so cancellation restores the rail scrim in the
        // same frame; IglooShell unmounts its actual draw node for the modal's whole lifetime.
        scrimmed = railHasFocus || signOut.confirming,
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
        // Restoring focus is the invoker's job and belongs in the callback, not an effect: on the
        // success path `confirming` clears in the same frame this whole arm is disposed, and a late
        // effect would call requestFocus() on a detached requester. See section 9.3.
        onSignOutDismiss = {
            onSignOutDismiss()
            signOutRequester.requestFocus()
        },
    )

    // Land in the content pane with the rail at rest: the library is the first thing seen
    // and the first D-pad press moves focus instead of creating it. Once only: the content
    // pane outlives a destination change, so re-anchoring here would steal focus from the
    // card the user had just activated.
    LaunchedEffect(Unit) {
        contentStartRequester.requestFocus()
    }
}

@Composable
private fun IglooShell(
    user: AuthUser,
    currentDestination: IglooDestination,
    home: HomeUiState,
    onRetryRail: (HomeRail) -> Unit,
    onMovieSelected: ((HomeMovie) -> Unit)?,
    railExpanded: Boolean,
    scrimmed: Boolean,
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
        // One group so the dialog can hide the entire shell from TalkBack traversal at once.
        // hideFromAccessibility, not clearAndSetSemantics: the nodes stay in the semantics tree,
        // so a test can still assert the rail is not focused while the dialog is open.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag("shell_content")
                .then(
                    if (signOut.confirming) {
                        Modifier.semantics { hideFromAccessibility() }
                    } else {
                        Modifier
                    },
                ),
        ) {
            ContentPane(
                currentDestination = currentDestination,
                home = home,
                onRetryRail = onRetryRail,
                onMovieSelected = onMovieSelected,
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
    onRetryRail: (HomeRail) -> Unit,
    onMovieSelected: ((HomeMovie) -> Unit)?,
    contentStartRequester: FocusRequester,
    navigationRequesters: Map<IglooDestination, FocusRequester>,
    onDestinationSelected: (IglooDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = IglooTheme.colors
    // Hoisted above the destination branch so a Home -> Movies -> Home round trip still knows
    // the card to restore (section 6.3), and saveable so process death does not forget it.
    // One per rail: each rail keeps its own focus memory.
    var lastFocusedContinueMovieId by rememberSaveable { mutableStateOf<Long?>(null) }
    var lastFocusedLatestMovieId by rememberSaveable { mutableStateOf<Long?>(null) }

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

        when (currentDestination) {
            IglooDestination.Home -> HomeRails(
                home = home,
                onRetryRail = onRetryRail,
                onMovieSelected = onMovieSelected,
                contentStartRequester = contentStartRequester,
                navigationRequester = navigationRequesters.getValue(IglooDestination.Home),
                lastFocusedContinueMovieId = lastFocusedContinueMovieId,
                onContinueMovieFocused = { lastFocusedContinueMovieId = it },
                lastFocusedLatestMovieId = lastFocusedLatestMovieId,
                onLatestMovieFocused = { lastFocusedLatestMovieId = it },
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
    onMovieSelected: ((HomeMovie) -> Unit)?,
    contentStartRequester: FocusRequester,
    navigationRequester: FocusRequester,
    lastFocusedContinueMovieId: Long?,
    onContinueMovieFocused: (Long) -> Unit,
    lastFocusedLatestMovieId: Long?,
    onLatestMovieFocused: (Long) -> Unit,
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
                modifier = Modifier.onFocusChanged { heroHasFocus = it.hasFocus },
            )
        }

        IglooMediaRail(
            title = "Continue Watching",
            state = home.continueWatching,
            itemKey = { it.movie.id },
            entryRequester = if (heroVisible) continueEntryRequester else contentStartRequester,
            leftFocusRequester = navigationRequester,
            lastFocusedKey = lastFocusedContinueMovieId,
            onItemFocused = onContinueMovieFocused,
            loadingLabel = "Loading continue watching",
            emptyIcon = IglooIcons.Movies,
            emptyText = "Nothing in progress yet. Movies you start watching appear here.",
            onRetry = { onRetryRail(HomeRail.ContinueWatching) },
        ) { item, itemModifier ->
            IglooPosterCard(
                title = item.movie.title,
                subtitle = item.movie.year?.toString(),
                imageUrl = item.movie.posterUrl,
                onClick = onMovieSelected?.let { select -> { select(item.movie) } },
                progress = PosterCardProgress(item.progressFraction, item.progressLabel),
                modifier = itemModifier.testTag("continue_card_${item.movie.id}"),
            )
        }

        IglooMediaRail(
            title = "Recently Added Movies",
            state = home.latestMovies,
            itemKey = { it.id },
            entryRequester = null,
            leftFocusRequester = navigationRequester,
            lastFocusedKey = lastFocusedLatestMovieId,
            onItemFocused = onLatestMovieFocused,
            loadingLabel = "Loading recently added movies",
            emptyIcon = IglooIcons.Movies,
            emptyText = "No movies in your library yet. Add a movies folder on the server and run a scan.",
            onRetry = { onRetryRail(HomeRail.LatestMovies) },
        ) { movie, itemModifier ->
            IglooPosterCard(
                title = movie.title,
                subtitle = movie.year?.toString(),
                imageUrl = movie.posterUrl,
                onClick = onMovieSelected?.let { select -> { select(movie) } },
                modifier = itemModifier.testTag("poster_card_${movie.id}"),
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
