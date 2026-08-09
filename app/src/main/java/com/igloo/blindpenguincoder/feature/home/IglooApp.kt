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
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.focusRing
import com.igloo.blindpenguincoder.core.ui.iglooSurface
import com.igloo.blindpenguincoder.data.model.AuthUser

@Composable
fun IglooApp(
    user: AuthUser,
    onSwitchProfile: () -> Unit,
    onLogout: () -> Unit,
) {
    var currentDestinationName by rememberSaveable { mutableStateOf(IglooDestination.Home.name) }
    val currentDestination = IglooDestination.valueOf(currentDestinationName)
    val contentStartRequester = remember { FocusRequester() }
    val navigationRequesters = remember {
        PrimaryIglooDestinations.associateWith { FocusRequester() }
    }
    // The rail expands exactly while d-pad focus is inside it; railOpenedByBack remembers
    // whether the rail was entered with the Back button, so Back can mean "step outward":
    // content -> rail -> exit, but a rail entered by d-pad steps back into content instead.
    var railHasFocus by remember { mutableStateOf(false) }
    var railOpenedByBack by remember { mutableStateOf(false) }

    BackHandler(enabled = !railHasFocus) {
        railOpenedByBack = true
        navigationRequesters.getValue(currentDestination).requestFocus()
    }
    BackHandler(enabled = railHasFocus && !railOpenedByBack) {
        contentStartRequester.requestFocus()
    }
    // railHasFocus && railOpenedByBack: no handler enabled, so Back exits the app.

    IglooShell(
        user = user,
        currentDestination = currentDestination,
        railExpanded = railHasFocus,
        onRailFocusChanged = { hasFocus ->
            if (!hasFocus) railOpenedByBack = false
            railHasFocus = hasFocus
        },
        contentStartRequester = contentStartRequester,
        navigationRequesters = navigationRequesters,
        onDestinationSelected = { currentDestinationName = it.name },
        onSwitchProfile = onSwitchProfile,
        onLogout = onLogout,
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
    railExpanded: Boolean,
    onRailFocusChanged: (Boolean) -> Unit,
    contentStartRequester: FocusRequester,
    navigationRequesters: Map<IglooDestination, FocusRequester>,
    onDestinationSelected: (IglooDestination) -> Unit,
    onSwitchProfile: () -> Unit,
    onLogout: () -> Unit,
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
        targetValue = if (railExpanded) RAIL_SCRIM_ALPHA else 0f,
        animationSpec = iglooTween(IglooMotion.STANDARD_MS),
        label = "railScrim",
    )

    // Backgrounds bleed to the physical edge; only chrome and text are inset for overscan.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        HomeContent(
            currentDestination = currentDestination,
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
        // Paint-only scrim: no clickable, focusable, or semantics modifiers, so it can
        // never intercept the d-pad and TalkBack does not know it exists.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    if (scrimAlpha > 0f) drawRect(colors.background.copy(alpha = scrimAlpha))
                },
        )
        NavigationRail(
            user = user,
            expanded = railExpanded,
            currentDestination = currentDestination,
            contentStartRequester = contentStartRequester,
            navigationRequesters = navigationRequesters,
            // Activating a destination hands focus to the content it just chose — that focus
            // move is also what collapses the rail. Cards in the pane keep the plain callback,
            // so activating one never steals focus from it.
            onDestinationSelected = { destination ->
                onDestinationSelected(destination)
                contentStartRequester.requestFocus()
            },
            onSwitchProfile = onSwitchProfile,
            onLogout = onLogout,
            modifier = Modifier
                .fillMaxHeight()
                .width(railWidth)
                .onFocusChanged { onRailFocusChanged(it.hasFocus) }
                .testTag("navigation_rail"),
        )
    }
}

@Composable
private fun HomeContent(
    currentDestination: IglooDestination,
    contentStartRequester: FocusRequester,
    navigationRequesters: Map<IglooDestination, FocusRequester>,
    onDestinationSelected: (IglooDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = IglooTheme.colors

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

/** Dim over the content pane while the rail overlays it — the 0.60 step from section 3.1. */
private const val RAIL_SCRIM_ALPHA = 0.60f
