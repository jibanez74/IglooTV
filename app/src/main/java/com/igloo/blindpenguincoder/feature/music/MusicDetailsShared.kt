package com.igloo.blindpenguincoder.feature.music

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusRequester.Companion.Cancel
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.igloo.blindpenguincoder.core.design.IglooMotion
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.iglooTween
import com.igloo.blindpenguincoder.core.design.scaled
import com.igloo.blindpenguincoder.core.ui.IglooButton
import com.igloo.blindpenguincoder.core.ui.IglooButtonVariant
import com.igloo.blindpenguincoder.core.ui.IglooIcons
import com.igloo.blindpenguincoder.core.ui.IglooInlineError
import com.igloo.blindpenguincoder.core.ui.IglooNotice
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.focusRing
import com.igloo.blindpenguincoder.core.ui.iglooEnterStagger
import com.igloo.blindpenguincoder.core.ui.iglooSurface
import com.igloo.blindpenguincoder.core.ui.pinnedToScreen
import com.igloo.blindpenguincoder.core.ui.requestFocusSafely
import com.igloo.blindpenguincoder.core.ui.withRequester
import com.igloo.blindpenguincoder.feature.shared.SectionHeading
import com.igloo.blindpenguincoder.feature.shared.TrackRowMenu
import com.igloo.blindpenguincoder.feature.shared.TrackRowRequesters
import com.igloo.blindpenguincoder.feature.shared.TrackRowUi
import com.igloo.blindpenguincoder.feature.shared.readingStopTarget

/**
 * The pieces the album and musician detail overlays share (docs/design-system.md sections
 * 11.5.1 and 11.5.2): both are full-screen in-tree overlays above the shell, opaque on the
 * `background` token, with a full-bleed backdrop, a hero whose prose is one reading stop under
 * a screen reader, a Play/Shuffle action row, three-action track rows, a facts panel, and a
 * row-anchored More menu. Back and focus restore belong to the host; each screen only anchors
 * entry focus and wires its own vertical chain.
 */

/**
 * The overlay's frame: the entry anchor, the swap-capture pattern (section 11.4.1) that
 * re-lands focus on a state change only when the screen owned it, the three states, and the
 * one More menu hosted as the last child. [loaded] is the page's model once it has one and
 * [errorMessage] the full-screen error's text; with neither the [skeleton] shows. [trackRows]
 * are every row the page draws, in the order [content] composes them, so a menu opened by
 * index finds its track and returns focus to its More control on dismissal.
 */
@Composable
internal fun <T : Any> MusicDetailsScaffold(
    stateKey: Any,
    loaded: T?,
    errorMessage: String?,
    paneTitle: String,
    tag: String,
    trackRows: List<TrackRowUi>,
    retrySemanticLabel: String,
    onRetry: () -> Unit,
    onGoToAlbum: ((Long) -> Unit)?,
    onGoToArtist: ((Long) -> Unit)?,
    modifier: Modifier = Modifier,
    skeleton: @Composable (anchorRequester: FocusRequester) -> Unit,
    content: @Composable (
        loaded: T,
        entryRequester: FocusRequester,
        trackRequesters: List<TrackRowRequesters>,
        onOpenMore: (Int, Rect) -> Unit,
    ) -> Unit,
) {
    val colors = IglooTheme.colors
    val entryRequester = remember { FocusRequester() }

    // The hero's primary action is the first focused element on entry; while loading, the
    // skeleton's action-slot stub holds the anchor so focus already sits where the real button
    // will land. Requested safely because a trackless page anchors elsewhere.
    LaunchedEffect(Unit) { entryRequester.requestFocusSafely() }

    // Whether the screen owned focus going into a state swap, re-landing it on the incoming
    // state's anchor. Keyed on the state's class — a Loaded republish must not yank focus back.
    var screenHasFocus by remember { mutableStateOf(false) }
    val hadFocusAtSwap = remember(stateKey) { screenHasFocus }
    LaunchedEffect(stateKey) {
        if (hadFocusAtSwap) entryRequester.requestFocusSafely()
    }
    // The row whose More menu is open, and the requesters its dismissal returns focus through.
    var trackMenu by remember(stateKey) { mutableStateOf<Pair<Int, Rect>?>(null) }
    val trackRequesters = remember(trackRows.size) { List(trackRows.size) { TrackRowRequesters() } }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .onFocusChanged { screenHasFocus = it.hasFocus }
            .semantics {
                // The loaded pane announces the record itself; a pane-title change is spoken, so
                // the load completing names it rather than a generic frame (section 12).
                this.paneTitle = paneTitle
                isTraversalGroup = true
            }
            .testTag(tag),
    ) {
        // The menu is a small anchored card that occludes nothing, so the body leaves the
        // semantics tree while it is up (the movie details rule); the tag stays outside.
        Box(
            modifier = Modifier
                .testTag("${tag}_body")
                .then(if (trackMenu != null) Modifier.clearAndSetSemantics { } else Modifier),
        ) {
            when {
                loaded != null -> content(
                    loaded,
                    entryRequester,
                    trackRequesters,
                ) { index, bounds -> trackMenu = index to bounds }

                // The only region on screen, so Assertive is safe and right: the user just asked
                // for this page and is waiting on it (section 10).
                errorMessage != null -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(IglooTheme.layout.safeAreaHorizontal),
                    contentAlignment = Alignment.Center,
                ) {
                    IglooInlineError(
                        message = errorMessage,
                        actionText = "Retry",
                        actionSemanticLabel = retrySemanticLabel,
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

                else -> skeleton(entryRequester)
            }
        }

        // Last child, over the body. Dismissal restores focus to the More that opened it, in
        // the callback rather than an effect (section 9.3); an item that opened the other
        // overlay replaces this one, so its restore finds nothing and safely no-ops.
        trackMenu?.let { (index, bounds) ->
            trackRows.getOrNull(index)?.let { track ->
                TrackRowMenu(
                    track = track,
                    anchorBounds = bounds,
                    onGoToAlbum = onGoToAlbum,
                    onGoToArtist = onGoToArtist,
                    onDismiss = {
                        trackMenu = null
                        trackRequesters.getOrNull(index)?.more?.requestFocusSafely()
                    },
                )
            }
        }
    }
}

/**
 * The loaded page's frame: a scrolling column of the hero, an optional notice, and the
 * sections, which rise once on entry (section 7.2). The header is deliberately not staggered:
 * it holds the entry focus, and a rise would move the focused button's bounds.
 */
@Composable
internal fun MusicDetailsBody(
    notice: String?,
    noticeTag: String,
    hero: @Composable () -> Unit,
    sections: @Composable ColumnScope.() -> Unit,
) {
    val layout = IglooTheme.layout
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        hero()
        if (notice != null) {
            IglooNotice(
                text = notice,
                modifier = Modifier
                    .padding(horizontal = layout.safeAreaHorizontal)
                    .padding(bottom = IglooTheme.spacing.lg)
                    .testTag(noticeTag),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = layout.safeAreaVertical)
                .iglooEnterStagger(entered = entered, index = 0),
            verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.lg),
            content = sections,
        )
    }
}

/**
 * The hero region, full-bleed: the artwork blown up as a backdrop reaches the physical edges
 * and scrolls away with the header, so everything below reads on the plain token canvas. The
 * section 11.4.1 scrims sit over it — the black side gradient licenses the white text column,
 * the vertical token fade blends the backdrop into the canvas — and the whole stack fades in
 * rather than popping (section 7.2). [header] is told whether media has actually decoded
 * behind it, because section 3.2's literals are licensed only then; a non-null URL alone would
 * paint white text over the bare canvas for the whole load window.
 */
@Composable
internal fun MusicDetailsHero(
    imageUrl: String?,
    backdropTag: String,
    header: @Composable BoxScope.(overMedia: Boolean) -> Unit,
) {
    val colors = IglooTheme.colors
    var imageFailed by remember(imageUrl) { mutableStateOf(false) }
    var imageLoaded by remember(imageUrl) { mutableStateOf(false) }
    val showBackdrop = imageUrl != null && !imageFailed
    val overMedia = imageLoaded
    val backdropAlpha by animateFloatAsState(
        targetValue = if (imageLoaded) 1f else 0f,
        animationSpec = iglooTween(IglooMotion.PAGE_MS),
        label = "musicBackdrop",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HERO_MIN_HEIGHT.scaled()),
    ) {
        if (showBackdrop) {
            AsyncImage(
                model = imageUrl,
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
                    .testTag(backdropTag)
                    .matchParentSize()
                    .graphicsLayer { alpha = backdropAlpha },
            )
            // Alpha-zero stops come from the color itself — Color.Transparent is black at zero
            // and would gray the token fade.
            Box(
                modifier = Modifier
                    // Tagged only once the decode lands: the tag's presence is what a test reads
                    // as "the section 3.2 treatment is on".
                    .then(if (overMedia) Modifier.testTag("${backdropTag}_scrim") else Modifier)
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
        header(overMedia)
    }
}

/** The hero header's placement: bottom-left of the hero, inside the safe area. */
@Composable
internal fun BoxScope.heroHeaderModifier(): Modifier {
    val layout = IglooTheme.layout
    return Modifier
        .align(Alignment.BottomStart)
        .fillMaxWidth()
        .padding(
            start = layout.safeAreaHorizontal,
            end = layout.safeAreaHorizontal,
            top = layout.safeAreaVertical,
            bottom = IglooTheme.spacing.lg,
        )
}

/**
 * The hero's prose as one reading stop. TV TalkBack follows input focus and never traverses
 * plain text, so while a screen reader runs the whole block is a focus target — reachable by
 * pressing up from the action row — that speaks [description] in a single announcement. The
 * About panel's focus treatment: a fill and ring, no scale, because this carries no action to
 * promise; over the backdrop the fill is the section 3.2 black ground, not the token card.
 */
@Composable
internal fun MusicHeroReadingStop(
    enabled: Boolean,
    tag: String,
    overMedia: Boolean,
    requester: FocusRequester,
    downRequester: FocusRequester,
    description: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = IglooTheme.colors
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.then(
            if (enabled) {
                Modifier
                    .testTag(tag)
                    .focusRing(
                        focused = focused,
                        radius = IglooTheme.radius.lg,
                        fill = when {
                            !focused -> Color.Transparent
                            overMedia -> Color.Black.copy(alpha = 0.45f)
                            else -> colors.card.copy(alpha = 0.72f)
                        },
                        scaleOnFocus = false,
                    )
                    .focusRequester(requester)
                    .focusProperties {
                        up = Cancel
                        left = Cancel
                        right = Cancel
                        down = downRequester
                    }
                    .onFocusChanged { focused = it.isFocused }
                    .focusable()
                    .clearAndSetSemantics { contentDescription = description }
            } else {
                Modifier
            },
        ),
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
        content = content,
    )
}

/**
 * Metadata chips — visually a row, but one TalkBack stop: consecutive chip announcements would
 * be noise, and the hero reading stop already carries the full sentence.
 */
@Composable
internal fun MusicMetadataChips(parts: List<String>, overMedia: Boolean) {
    Row(
        modifier = Modifier.clearAndSetSemantics { contentDescription = parts.joinToString(", ") },
        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        parts.forEach { AlbumDetailChip(text = it, overMedia = overMedia) }
    }
}

/** The hero's artwork; decorative — it repeats nothing the text does not say, so TalkBack skips it. */
@Composable
internal fun MusicHeroArtwork(
    imageUrl: String?,
    radius: Dp,
    fallbackIcon: ImageVector,
) {
    val colors = IglooTheme.colors
    var imageFailed by remember(imageUrl) { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .width(IglooTheme.layout.posterWidth)
            .aspectRatio(IglooTheme.layout.albumAspect)
            .iglooSurface(radius = radius, fill = colors.muted),
        contentAlignment = Alignment.Center,
    ) {
        if (imageUrl != null && !imageFailed) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onState = { state -> if (state is AsyncImagePainter.State.Error) imageFailed = true },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Image(
                imageVector = fallbackIcon,
                contentDescription = null,
                colorFilter = ColorFilter.tint(colors.mutedForeground),
                modifier = Modifier.size(IglooTheme.icons.lg),
            )
        }
    }
}

/**
 * The hero's primary action and Shuffle. Every direction out of the row is pinned: the shell
 * is still composed under the overlay, so an unpinned edge lets a spatial search land on a
 * card the user cannot see. The primary steps back while Shuffle holds focus, so the focused
 * control is the strongest thing in the row (the movie action row's recess rule). Each button
 * also carries the player's return requester while it is the control that launched it.
 */
@Composable
internal fun MusicHeroActionRow(
    primaryText: String,
    primarySemanticLabel: String,
    primaryTag: String,
    shuffleSemanticLabel: String,
    shuffleTag: String,
    overMedia: Boolean,
    primaryRequester: FocusRequester,
    primaryReturnRequester: FocusRequester?,
    shuffleRequester: FocusRequester,
    shuffleReturnRequester: FocusRequester?,
    upRequester: FocusRequester,
    downRequester: FocusRequester,
    onActionFocused: (FocusRequester) -> Unit,
    onPrimary: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Section 3.2: a Ghost button's transparent ground and token text are licensed only on a
    // token canvas; over the backdrop the button carries the same black ground as the chips.
    val ghostFill = if (overMedia) Color.Black.copy(alpha = 0.45f) else null
    val ghostContent = if (overMedia) Color.White else null
    val rowFocus = Modifier.focusProperties {
        up = upRequester
        down = downRequester
    }
    var rowHasFocus by remember { mutableStateOf(false) }
    var primaryFocused by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.onFocusChanged { rowHasFocus = it.hasFocus },
        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
    ) {
        IglooButton(
            text = primaryText,
            onClick = onPrimary,
            icon = IglooIcons.Play,
            semanticLabel = primarySemanticLabel,
            recessed = rowHasFocus && !primaryFocused,
            modifier = Modifier
                .testTag(primaryTag)
                .focusRequester(primaryRequester)
                .withRequester(primaryReturnRequester)
                .then(rowFocus)
                .focusProperties {
                    left = Cancel
                    right = shuffleRequester
                }
                .onFocusChanged {
                    primaryFocused = it.isFocused
                    if (it.isFocused) onActionFocused(primaryRequester)
                },
        )
        IglooButton(
            text = "Shuffle",
            onClick = onShuffle,
            variant = IglooButtonVariant.Ghost,
            icon = IglooIcons.Shuffle,
            restingFill = ghostFill,
            contentColor = ghostContent,
            semanticLabel = shuffleSemanticLabel,
            modifier = Modifier
                .testTag(shuffleTag)
                .focusRequester(shuffleRequester)
                .withRequester(shuffleReturnRequester)
                .then(rowFocus)
                .focusProperties { right = Cancel }
                .onFocusChanged { if (it.isFocused) onActionFocused(shuffleRequester) },
        )
    }
}

/**
 * The fine print, on the movie About panel's exact treatment: heading outside the focusable
 * panel, one focus stop, one cleared announcement with the heading folded in. Reachable but not
 * actionable — content a d-pad can never scroll to may as well not be on the page.
 */
@Composable
internal fun MusicFactsSection(
    heading: String,
    tag: String,
    facts: List<AlbumFactUi>,
    description: String,
    requester: FocusRequester,
    upRequester: FocusRequester?,
    valueMaxLines: Int,
    modifier: Modifier = Modifier,
) {
    val colors = IglooTheme.colors
    var focused by remember { mutableStateOf(false) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm)) {
        SectionHeading(heading)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .readingStopTarget(
                    tag = tag,
                    focused = focused,
                    requester = requester,
                    upRequester = upRequester,
                    downRequester = null,
                    onFocusChanged = { focused = it },
                    description = description,
                )
                .padding(IglooTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.xs),
        ) {
            facts.forEach { fact ->
                Row(horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm)) {
                    IglooText(
                        text = "${fact.label}:",
                        style = IglooTheme.typography.label,
                        color = colors.mutedForeground,
                        maxLines = 1,
                    )
                    IglooText(
                        text = fact.value,
                        style = IglooTheme.typography.bodyMedium,
                        color = colors.foreground,
                        maxLines = valueMaxLines,
                    )
                }
            }
        }
    }
}

/**
 * Static geometry-matched stand-ins (section 10): the artwork and text stubs where the hero
 * lands, and a two-slot action row whose first slot is the screen's one focusable anchor, so
 * entry focus taken during the load sits exactly where the primary action appears.
 */
@Composable
internal fun MusicDetailsHeroSkeleton(
    artworkShape: Shape,
    primaryStubWidth: Dp,
    loadingLabel: String,
    anchorRequester: FocusRequester,
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
            modifier = heroHeaderModifier(),
            horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.xl),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                modifier = Modifier
                    .width(layout.posterWidth)
                    .aspectRatio(layout.albumAspect)
                    .background(colors.muted, artworkShape),
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
                            .width(primaryStubWidth.scaled())
                            .heightIn(min = IglooTheme.sizes.controlHeight)
                            .focusRing(
                                focused = focused,
                                radius = IglooTheme.radius.lg,
                                fill = colors.muted,
                            )
                            .focusRequester(anchorRequester)
                            // The screen's only focusable while loading, and the shell is still
                            // composed underneath: without this, Left or Down pressed before the
                            // page lands walks focus onto an invisible card.
                            .pinnedToScreen()
                            .onFocusChanged { focused = it.isFocused }
                            .focusable()
                            .clearAndSetSemantics {
                                contentDescription = loadingLabel
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

/**
 * Which control launched the music player, so its close lands back on that control: the
 * hero's primary action, Shuffle, or one row's Play. Saved, because the player itself survives
 * recreation and its close afterwards still has to find the launching node.
 */
internal sealed interface PlayLaunchSite {
    data object Primary : PlayLaunchSite
    data object Shuffle : PlayLaunchSite
    data class Row(val index: Int) : PlayLaunchSite

    companion object {
        val Saver: Saver<PlayLaunchSite, String> = Saver(
            save = { site ->
                when (site) {
                    Primary -> "play"
                    Shuffle -> "shuffle"
                    is Row -> "row:${site.index}"
                }
            },
            restore = { saved ->
                when {
                    saved == "shuffle" -> Shuffle
                    saved.startsWith("row:") -> saved.removePrefix("row:").toIntOrNull()?.let(::Row) ?: Primary
                    else -> Primary
                }
            },
        )
    }
}

@Composable
internal fun rememberPlayLaunchSite(): MutableState<PlayLaunchSite> =
    rememberSaveable(stateSaver = PlayLaunchSite.Saver) { mutableStateOf(PlayLaunchSite.Primary) }

/** The row a [PlayLaunchSite.Row] names, when the page still has that row. */
internal fun PlayLaunchSite.rowIn(rows: List<*>): Int? =
    (this as? PlayLaunchSite.Row)?.index?.takeIf { it in rows.indices }

/** About 60% of the reference viewport's height (section 8.1); contains text, so a minimum. */
private val HERO_MIN_HEIGHT = 320.dp

private val SHUFFLE_STUB_WIDTH = 128.dp
