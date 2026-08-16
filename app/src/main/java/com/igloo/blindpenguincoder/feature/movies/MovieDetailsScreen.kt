package com.igloo.blindpenguincoder.feature.movies

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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
import com.igloo.blindpenguincoder.core.ui.focusRing
import com.igloo.blindpenguincoder.core.ui.iglooEnterStagger

/**
 * What the details overlay can do, grouped so the shell that hosts it keeps a readable
 * signature. Close is the host's own business (it owns Back and focus restoration) and is
 * deliberately not here.
 */
data class MovieDetailsActions(
    val onPlay: () -> Unit,
    val onToggleWatched: () -> Unit,
    val onToggleLike: () -> Unit,
    val onRetry: () -> Unit,
)

/**
 * The movie details screen (docs/design-system.md section 11.4): a full-screen in-tree overlay
 * above the shell, opaque on the `background` token, with a full-bleed backdrop that scrolls
 * away with the hero. Back and focus-restore belong to the host ([com.igloo.blindpenguincoder.feature.home.IglooApp]),
 * which also fences the shell's focusables while this is open — the screen itself only anchors
 * entry focus: Play (or the state's stand-in for it) is the first focused element.
 *
 * The backdrop opts out of the safe area (section 2.5); chrome and text keep the inset.
 */
@Composable
fun MovieDetailsScreen(
    state: MovieDetailsState,
    actions: MovieDetailsActions,
    modifier: Modifier = Modifier,
) {
    val colors = IglooTheme.colors
    val entryRequester = remember { FocusRequester() }

    // Play is the first focused element on entry (section 11.4); while loading, the skeleton's
    // Play-slot stub holds the anchor so focus already sits where the real button will land.
    LaunchedEffect(Unit) { entryRequester.requestFocus() }

    // The IglooMediaRail swap-capture pattern: the outgoing state's focused node only detaches
    // once the composition applies, so this still sees whether the screen owned focus going in,
    // and the effect re-lands it on the incoming state's anchor. Keyed on the state's class —
    // a Loaded republish (a toggle, a badge arriving) must not yank focus back to Play.
    var screenHasFocus by remember { mutableStateOf(false) }
    val hadFocusAtSwap = remember(state::class) { screenHasFocus }
    LaunchedEffect(state::class) {
        if (hadFocusAtSwap) entryRequester.requestFocus()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .onFocusChanged { screenHasFocus = it.hasFocus }
            .semantics {
                paneTitle = "Movie details"
                isTraversalGroup = true
            }
            .testTag("movie_details"),
    ) {
        when (state) {
            is MovieDetailsState.Loading -> DetailsSkeleton(anchorRequester = entryRequester)

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
                    modifier = Modifier.width(IglooTheme.layout.authCardWidth),
                )
            }

            is MovieDetailsState.Loaded -> DetailsContent(
                movie = state.movie,
                playRequester = entryRequester,
                actions = actions,
            )
        }
    }
}

@Composable
private fun DetailsContent(
    movie: MovieDetailsUi,
    playRequester: FocusRequester,
    actions: MovieDetailsActions,
) {
    val colors = IglooTheme.colors
    val layout = IglooTheme.layout
    var imageFailed by remember(movie.backdropUrl) { mutableStateOf(false) }
    var imageLoaded by remember(movie.backdropUrl) { mutableStateOf(false) }
    val overMedia = movie.backdropUrl != null && !imageFailed
    // The screen's vertical chain, hand-wired end to end: actions -> cast -> about. Nothing is
    // left to a spatial search, because the shell composed underneath would be a candidate.
    val castEntryRequester = remember { FocusRequester() }
    val aboutRequester = remember { FocusRequester() }
    val belowActions = when {
        movie.cast.isNotEmpty() -> castEntryRequester
        !movie.about.isEmpty -> aboutRequester
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
            if (overMedia) {
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
                playRequester = playRequester,
                downRequester = belowActions,
                onPlay = actions.onPlay,
                onToggleWatched = actions.onToggleWatched,
                onToggleLike = actions.onToggleLike,
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
            castEntryRequester = castEntryRequester,
            aboutRequester = aboutRequester,
            upFromSections = playRequester,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = layout.safeAreaHorizontal,
                    end = layout.safeAreaHorizontal,
                    bottom = layout.safeAreaVertical,
                )
                .iglooEnterStagger(entered = entered, index = 0),
        )
    }
}

/**
 * Static geometry-matched stand-ins (section 10): poster and text stubs where the hero lands,
 * and an action-row whose Play-slot stub is the screen's one focusable anchor, so entry focus
 * taken during the load sits exactly where the real Play button appears.
 */
@Composable
private fun DetailsSkeleton(anchorRequester: FocusRequester) {
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
                    repeat(3) {
                        Box(
                            modifier = Modifier
                                .width(PLAY_STUB_WIDTH.scaled())
                                .heightIn(min = IglooTheme.sizes.controlHeight)
                                .background(colors.muted, RoundedCornerShape(IglooTheme.radius.lg)),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Pins every direction, for a state whose single focusable has nowhere of its own to go. The
 * overlay's shell is composed underneath it, so an unpinned edge is an escape hatch onto UI the
 * user cannot see (section 11.4.1).
 */
private fun Modifier.pinnedToScreen(): Modifier = focusProperties {
    left = FocusRequester.Cancel
    right = FocusRequester.Cancel
    up = FocusRequester.Cancel
    down = FocusRequester.Cancel
}

/** About 60% of the reference viewport's height (section 8.1); contains text, so a minimum. */
private val HERO_MIN_HEIGHT = 320.dp

/** The Play button's approximate footprint, so focus taken while loading does not jump. */
private val PLAY_STUB_WIDTH = 120.dp
