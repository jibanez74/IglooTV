package com.igloo.blindpenguincoder.feature.music

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
import com.igloo.blindpenguincoder.core.ui.pinnedToScreen
import com.igloo.blindpenguincoder.core.ui.rememberSpokenAccessibilityEnabled
import com.igloo.blindpenguincoder.core.ui.requestFocusSafely

/**
 * The album detail screen (docs/design-system.md section 11.5.1): a full-screen in-tree overlay
 * above the shell, opaque on the `background` token, the third occupant of the host's one
 * details slot. Back and focus-restore belong to the host
 * ([com.igloo.blindpenguincoder.feature.home.IglooApp]); the screen only anchors entry focus on
 * the hero's primary action — Play Album — or on the facts panel for an album with no tracks,
 * where the action row is not composed at all (web parity, and the inert-control rule).
 *
 * The backdrop is the album cover blown up full-bleed (the web page's treatment): there is no
 * separate backdrop asset for music, and the cover URL is used verbatim.
 *
 * [onPlayAlbum] and [onShuffle] open the host's music player overlay on the album's queue, in
 * order or freshly shuffled; [playReturnRequester] is parked on the action that launched it so
 * closing that player restores focus there (section 6.3), the movie details screen's pairing.
 */
@Composable
fun AlbumDetailsScreen(
    state: AlbumDetailsState,
    onRetry: () -> Unit,
    onPlayAlbum: () -> Unit,
    onShuffle: () -> Unit,
    playReturnRequester: FocusRequester,
    modifier: Modifier = Modifier,
    // Parameterized so tests can force both states: the reading-stop chain below depends on it,
    // and a test device with TalkBack running would otherwise pin the gate open.
    spokenAccessibilityEnabled: Boolean = rememberSpokenAccessibilityEnabled(),
) {
    val colors = IglooTheme.colors
    val entryRequester = remember { FocusRequester() }

    // The hero's primary action is the first focused element on entry; while loading, the
    // skeleton's action-slot stub holds the anchor so focus already sits where the real button
    // will land. Requested safely because a trackless album anchors on the facts panel instead.
    LaunchedEffect(Unit) { entryRequester.requestFocusSafely() }

    // The swap-capture pattern (section 11.4.1): whether the screen owned focus going into a
    // state swap, re-landing it on the incoming state's anchor. Keyed on the state's class —
    // a Loaded republish must not yank focus back to the action row.
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
                // The loaded pane announces the album itself; a pane-title change is spoken, so
                // the load completing names the record rather than a generic frame (section 12).
                paneTitle = (state as? AlbumDetailsState.Loaded)?.album?.title ?: "Album details"
                isTraversalGroup = true
            }
            .testTag("album_details"),
    ) {
        when (state) {
            is AlbumDetailsState.Loading -> AlbumDetailsSkeleton(anchorRequester = entryRequester)

            // The only region on screen, so Assertive is safe and right: the user just asked
            // for this page and is waiting on it (section 10).
            is AlbumDetailsState.Error -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(IglooTheme.layout.safeAreaHorizontal),
                contentAlignment = Alignment.Center,
            ) {
                IglooInlineError(
                    message = state.message,
                    actionText = "Retry",
                    actionSemanticLabel = "Retry loading album details",
                    onAction = onRetry,
                    // The screen's only focusable, so every direction is pinned: the shell is
                    // still composed underneath, and a spatial search that escaped would strand
                    // focus on a card nobody can see, with no way back to Retry.
                    actionModifier = Modifier
                        .focusRequester(entryRequester)
                        .pinnedToScreen(),
                    modifier = Modifier.width(IglooTheme.layout.dialogWidth),
                )
            }

            is AlbumDetailsState.Loaded -> AlbumDetailsContent(
                album = state.album,
                spokenAccessibilityEnabled = spokenAccessibilityEnabled,
                entryRequester = entryRequester,
                playReturnRequester = playReturnRequester,
                onPlayAlbum = onPlayAlbum,
                onShuffle = onShuffle,
            )
        }
    }
}

@Composable
private fun AlbumDetailsContent(
    album: AlbumDetailsUi,
    spokenAccessibilityEnabled: Boolean,
    entryRequester: FocusRequester,
    playReturnRequester: FocusRequester,
    onPlayAlbum: () -> Unit,
    onShuffle: () -> Unit,
) {
    val colors = IglooTheme.colors
    val layout = IglooTheme.layout
    var imageFailed by remember(album.coverUrl) { mutableStateOf(false) }
    var imageLoaded by remember(album.coverUrl) { mutableStateOf(false) }
    val showBackdrop = album.coverUrl != null && !imageFailed
    // Section 3.2's literals are licensed only by media actually behind them, so the white
    // treatment waits for the decode — a non-null URL alone would paint white text over the
    // bare token canvas for the whole load window.
    val overMedia = imageLoaded

    // A trackless album composes no action row at all: a Play button over zero tracks is a
    // control that takes focus and does nothing (web parity — the web page hides both buttons).
    val trackRows = album.discs.flatMap { it.tracks }
    val hasHeroActions = trackRows.isNotEmpty()

    // The screen's vertical chain, hand-wired end to end: actions -> track rows -> facts panel —
    // and, while a screen reader runs, the hero info reading stop above the actions. Nothing is
    // left to a spatial search, because the shell composed underneath would be a candidate.
    val heroInfoRequester = remember { FocusRequester() }
    val shuffleRequester = remember { FocusRequester() }
    val factsStop = remember { FocusRequester() }
    val trackRequesters = remember(trackRows.size) { List(trackRows.size) { FocusRequester() } }
    // With no action row the facts panel owns the entry anchor: the screen's requester *is* the
    // panel's, which lands entry focus there with every edge wired at the panel untouched.
    val factsRequester = if (hasHeroActions) factsStop else entryRequester
    // Up from the rows and the panel returns to whichever action the user left, not
    // unconditionally to Play Album — the same focus memory the movie action row keeps.
    var lastFocusedAction by remember(hasHeroActions) {
        mutableStateOf(entryRequester.takeIf { hasHeroActions })
    }
    val belowActions = trackRequesters.firstOrNull() ?: factsRequester
    val upFromBelow = when {
        hasHeroActions -> lastFocusedAction
        spokenAccessibilityEnabled -> heroInfoRequester
        else -> null
    }

    // The backdrop fades in on top of the token canvas instead of popping (section 7.2's
    // overlay-reveal case); under reduced motion iglooTween snaps it.
    val backdropAlpha by animateFloatAsState(
        targetValue = if (imageLoaded) 1f else 0f,
        animationSpec = iglooTween(IglooMotion.PAGE_MS),
        label = "albumBackdrop",
    )
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // The hero region. Full-bleed: the cover-as-backdrop reaches the physical edges and
        // scrolls away with the header, so everything below reads on the plain token canvas.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = HERO_MIN_HEIGHT.scaled()),
        ) {
            if (showBackdrop) {
                AsyncImage(
                    model = album.coverUrl,
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
                        .testTag("album_backdrop")
                        .matchParentSize()
                        .graphicsLayer { alpha = backdropAlpha },
                )
                // The section 11.4.1 detail-hero scrims, verbatim: the black side gradient
                // licenses the white text column, the vertical token fade blends the backdrop
                // into the canvas the sections sit on. Alpha-zero stops come from the color
                // itself — Color.Transparent is black at zero and would gray the token fade.
                Box(
                    modifier = Modifier
                        // Tagged only once the decode lands: the tag's presence is what a test
                        // reads as "the section 3.2 treatment is on".
                        .then(
                            if (overMedia) Modifier.testTag("album_backdrop_scrim") else Modifier,
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

            AlbumDetailsHeader(
                album = album,
                overMedia = overMedia,
                hasHeroActions = hasHeroActions,
                spokenAccessibilityEnabled = spokenAccessibilityEnabled,
                heroInfoRequester = heroInfoRequester,
                primaryRequester = entryRequester,
                playReturnRequester = playReturnRequester,
                shuffleRequester = shuffleRequester,
                downRequester = belowActions,
                onActionFocused = { lastFocusedAction = it },
                onPlayAlbum = onPlayAlbum,
                onShuffle = onShuffle,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(
                        start = layout.safeAreaHorizontal,
                        end = layout.safeAreaHorizontal,
                        top = layout.safeAreaVertical,
                        bottom = IglooTheme.spacing.lg,
                    ),
                // Deliberately not staggered: the header holds the entry focus, and a rise would
                // move the focused button's visual bounds (the section 11.4.1 motion rule). The
                // backdrop's fade carries the entrance here.
            )
        }

        AlbumDetailsSections(
            album = album,
            trackRequesters = trackRequesters,
            factsRequester = factsRequester,
            upFromBelow = upFromBelow,
            contentInset = PaddingValues(horizontal = layout.safeAreaHorizontal),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = layout.safeAreaVertical)
                .iglooEnterStagger(entered = entered, index = 0),
        )
    }
}

/**
 * Static geometry-matched stand-ins (section 10): the square cover and text stubs where the
 * hero lands, and a two-slot action row whose first slot is the screen's one focusable anchor,
 * so entry focus taken during the load sits exactly where Play Album appears.
 */
@Composable
private fun AlbumDetailsSkeleton(anchorRequester: FocusRequester) {
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
                    .aspectRatio(layout.albumAspect)
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
                            .width(PLAY_ALBUM_STUB_WIDTH.scaled())
                            .heightIn(min = IglooTheme.sizes.controlHeight)
                            .focusRing(
                                focused = focused,
                                radius = IglooTheme.radius.lg,
                                fill = colors.muted,
                            )
                            .focusRequester(anchorRequester)
                            // The screen's only focusable while loading, and the shell is still
                            // composed underneath: without this, Left or Down pressed before the
                            // album lands walks focus onto an invisible card.
                            .pinnedToScreen()
                            .onFocusChanged { focused = it.isFocused }
                            .focusable()
                            .clearAndSetSemantics {
                                contentDescription = "Loading album details"
                                liveRegion = LiveRegionMode.Polite
                            },
                    )
                    Box(
                        modifier = Modifier
                            .width(SHUFFLE_STUB_WIDTH.scaled())
                            .heightIn(min = IglooTheme.sizes.controlHeight)
                            .background(colors.muted, RoundedCornerShape(IglooTheme.radius.lg)),
                    )
                }
            }
        }
    }
}

/** About 60% of the reference viewport's height (section 8.1); contains text, so a minimum. */
private val HERO_MIN_HEIGHT = 320.dp

/** The Play Album button's approximate footprint, so focus taken while loading does not jump. */
private val PLAY_ALBUM_STUB_WIDTH = 168.dp

private val SHUFFLE_STUB_WIDTH = 128.dp
