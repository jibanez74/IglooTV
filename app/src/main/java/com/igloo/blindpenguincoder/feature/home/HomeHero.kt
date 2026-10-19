package com.igloo.blindpenguincoder.feature.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.igloo.blindpenguincoder.CONFIRM_KEYS
import com.igloo.blindpenguincoder.core.design.IglooEasing
import com.igloo.blindpenguincoder.core.design.IglooMotion
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.OVER_MEDIA_CONTROL_FILL
import com.igloo.blindpenguincoder.core.design.OVER_MEDIA_SECONDARY
import com.igloo.blindpenguincoder.core.design.iglooTween
import com.igloo.blindpenguincoder.core.design.overMedia
import com.igloo.blindpenguincoder.core.design.overMediaSideScrim
import com.igloo.blindpenguincoder.core.design.scaled
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.RatingBadge
import com.igloo.blindpenguincoder.core.ui.SkeletonStub
import com.igloo.blindpenguincoder.core.ui.focusRing
import com.igloo.blindpenguincoder.core.ui.insetHorizontally
import com.igloo.blindpenguincoder.core.ui.joinedLine
import com.igloo.blindpenguincoder.feature.shared.DetailChip

/**
 * The billboard at the top of home (docs/design-system.md section 11.3.1): the newest movie and
 * the newest show as two pages of one full-bleed hero, one page on screen at a time. D-pad right
 * and left turn the page; nothing ever turns it on its own (section 7.2). The caller renders it
 * only while [state] is Loading or Loaded — Hidden means no hero at all, and the Continue Watching
 * rail owns the pane's entry anchor instead.
 *
 * **One focus target, whichever page shows.** A page turn changes what the target says, not
 * where focus is, and TalkBack reads the new page out because the description of the node its
 * cursor is on changed. Two stacked targets — one per
 * page, sharing a rectangle — cannot work: Compose builds the accessibility tree topmost-first
 * and leaves out a node another one covers completely, so TalkBack only ever saw the top page,
 * a focus move onto the one beneath sent no event, and TalkBack moved input focus onto the top
 * page whenever it went looking for a node. The page on screen is hoisted as [pageKind], like
 * the rails' focus memory, so Home comes back on the page the user left.
 *
 * The Loading skeleton is the same target, so when the hero loads the focused node stays put and
 * only what it says changes — read out the same way, rather than by a focus handoff to a node
 * TalkBack has not seen yet.
 *
 * **The art reaches all four physical edges; the text does not.** [contentInset] is the pane's
 * gutter, and only the text plate takes it — that is what keeps the title out from under the nav
 * rail while letting the backdrop pass behind it. The focus ring rides the plate rather than the
 * bleeding surface for the same reason a backdrop may bleed and a word may not: a 3dp ring traced
 * round the panel would sit in the overscan margin, the one place section 2.5 says a TV may crop.
 *
 * Each page opens its own details page — [onOpenMovie] the movie's, [onOpenShow] the show's. A
 * null opener keeps that page a focus target but drops the button role and the "Open …" action,
 * the same contract as an inert poster card.
 */
@Composable
fun HomeHero(
    state: HomeHeroState,
    pageKind: HomeHeroKind?,
    onPageChange: (HomeHeroKind) -> Unit,
    entryRequester: FocusRequester,
    leftFocusRequester: FocusRequester,
    downFocusRequester: FocusRequester,
    contentInset: PaddingValues,
    onOpenMovie: ((Long) -> Unit)?,
    onOpenShow: ((Long) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // A null page is the Loading skeleton, standing where the first page will land.
    val pages: List<HomeHero?> = when (state) {
        is HomeHeroState.Hidden -> return
        is HomeHeroState.Loading -> listOf(null)
        is HomeHeroState.Loaded -> state.pages
    }
    val colors = IglooTheme.colors
    var focused by remember { mutableStateOf(false) }
    val activeIndex = pages.indexOfFirst { it != null && it.kind == pageKind }.takeIf { it >= 0 } ?: 0
    val activePage = pages[activeIndex]
    val arts = pages.mapIndexed { index, page -> key(index) { rememberHeroArt(page?.backdropUrl) } }
    val transition = updateTransition(targetState = activeIndex, label = "heroPage")
    val openPage = activePage?.let { page ->
        val open = when (page.kind) {
            HomeHeroKind.Movie -> onOpenMovie
            HomeHeroKind.Show -> onOpenShow
        }
        open?.let { { it(page.id) } }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = HERO_MIN_HEIGHT.scaled())
            // Shows while an image fetches, and under a page with no backdrop at all.
            .background(if (state is HomeHeroState.Loaded) colors.card else colors.muted)
            .focusRequester(entryRequester)
            .focusProperties {
                left = leftFocusRequester
                right = FocusRequester.Cancel
                // Hand-wired: spatial resolution from a pane-wide surface picks whichever card
                // happens to win its beam heuristic. Down goes to the first rail's entry card,
                // so the rail's focus memory applies, same as spine re-entry.
                down = downFocusRequester
            }
            // Focus observation outside the focusable, per section 6.3's ordering rule.
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                val turnTo = when (event.key) {
                    // Right past the last page and left from the first fall through: right is
                    // cancelled, and left leaves for the spine.
                    Key.DirectionRight -> activeIndex + 1
                    Key.DirectionLeft -> activeIndex - 1
                    else -> null
                }
                val target = turnTo?.let { pages.getOrNull(it) }
                when {
                    target != null -> {
                        if (event.type == KeyEventType.KeyDown) onPageChange(target.kind)
                        true
                    }
                    openPage != null && event.key in CONFIRM_KEYS -> {
                        if (event.type == KeyEventType.KeyUp) openPage()
                        true
                    }
                    else -> false
                }
            }
            // Always focusable, never clickable, with the press handled above: the two are
            // different modifier nodes, and the skeleton turning into a page under focus
            // would otherwise swap one for the other and drop focus on the floor. Select, Enter
            // and keypad Enter open on release, as clickable does; TalkBack's click goes through
            // the semantics action.
            .focusable()
            // Outside the clear, like the focusable: semantics inside it are wiped.
            .testTag("home_hero")
            .clearAndSetSemantics {
                contentDescription = activePage?.let { heroPageDescription(it, activeIndex, pages.size) }
                    ?: LOADING_DESCRIPTION
                // No live region, on purpose. TalkBack speaks a description change on the node
                // its cursor is on, which covers a turn and a load under focus; a live region
                // would add a re-read whenever focus came or went, since every focus change
                // reports a content change on the node.
                if (openPage != null && activePage != null) {
                    role = Role.Button
                    onClick(label = "Open ${activePage.title}") {
                        openPage()
                        true
                    }
                }
            },
    ) {
        pages.forEachIndexed { index, page ->
            if (page != null) {
                key(index) {
                    HeroArtLayer(
                        url = page.backdropUrl,
                        art = arts[index],
                        pageAlpha = transition.artAlpha(index),
                        modifier = Modifier
                            .matchParentSize()
                            // The incoming page fades in over the outgoing one, which holds
                            // still underneath until it is covered: a dissolve, not a dip.
                            .zIndex(if (index == activeIndex) 1f else 0f),
                    )
                }
            }
        }
        // The fade into the page canvas the rails sit on, over whichever art is showing.
        Box(
            modifier = Modifier
                .matchParentSize()
                .zIndex(2f)
                .background(
                    Brush.verticalGradient(
                        BOTTOM_FADE_START to colors.background.copy(alpha = 0f),
                        1f to colors.background,
                    ),
                ),
        )

        // Every plate is composed, so the hero measures to the taller one and a turn never moves
        // the rails; only the active one is opaque.
        pages.forEachIndexed { index, page ->
            key(index) {
                val textAlpha = transition.textAlpha(index)
                val plateModifier = Modifier
                    .zIndex(3f)
                    .align(Alignment.BottomStart)
                    .graphicsLayer { alpha = textAlpha() }
                if (page == null) {
                    HeroSkeletonPlate(
                        focused = focused,
                        contentInset = contentInset,
                        modifier = plateModifier,
                    )
                } else {
                    HeroPlate(
                        hero = page,
                        overMedia = arts[index].loaded,
                        focused = focused && index == activeIndex,
                        contentInset = contentInset,
                        modifier = plateModifier,
                    )
                }
            }
        }

        if (pages.size > 1) {
            HeroPageIndicator(
                count = pages.size,
                activeIndex = activeIndex,
                overMedia = arts[activeIndex].loaded,
                modifier = Modifier
                    .zIndex(3f)
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = contentInset.calculateEndPadding(LocalLayoutDirection.current),
                        bottom = IglooTheme.layout.safeAreaVertical + IglooTheme.spacing.lg,
                    ),
            )
        }
    }
}

/**
 * What the hero announces for a page, as its single cleared node: which feature it is and where
 * it sits among the pages, then the title, the metadata sentence and the full overview — the
 * visual three-line clamp is not an accessibility clamp. The position is what tells a
 * screen-reader user there is a page to the right; a lone page has no position to give.
 */
internal fun heroPageDescription(hero: HomeHero, index: Int, count: Int): String {
    val feature = "$RECENTLY_ADDED ${hero.kind.noun}"
    return listOfNotNull(
        if (count > 1) "$feature, ${index + 1} of $count" else feature,
        hero.title,
        hero.metadataDescription,
        hero.overview,
    ).joinToString(". ")
}

/**
 * Whether a page's backdrop has decoded or failed. Hoisted out of the art so the plate can read
 * it too: section 3.2's over-media literals are licensed only by media actually behind them, and
 * a URL alone would paint white text over the bare fill for the whole load window.
 */
@Stable
private class HeroArt {
    var loaded by mutableStateOf(false)
    var failed by mutableStateOf(false)
}

@Composable
private fun rememberHeroArt(url: String?): HeroArt = remember(url) { HeroArt() }

/**
 * One page's backdrop and its two over-media scrims, which fade in together once the image
 * decodes and are absent when there is none — the page is then the plain card fill (section 3.2).
 * The inactive page's image still loads, at zero alpha, so a page turn never waits on the network.
 */
@Composable
private fun HeroArtLayer(
    url: String?,
    art: HeroArt,
    pageAlpha: () -> Float,
    modifier: Modifier = Modifier,
) {
    if (url == null || art.failed) return
    val decodeAlpha by animateFloatAsState(
        targetValue = if (art.loaded) 1f else 0f,
        animationSpec = iglooTween(IglooMotion.PAGE_MS),
        label = "heroBackdrop",
    )
    Box(modifier = modifier.graphicsLayer { alpha = pageAlpha() * decodeAlpha }) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onState = { state ->
                when (state) {
                    is AsyncImagePainter.State.Success -> art.loaded = true
                    is AsyncImagePainter.State.Error -> art.failed = true
                    else -> Unit
                }
            },
            modifier = Modifier.matchParentSize(),
        )
        // Over-media literals per section 3.2. The side scrim carries the text column against
        // busy art — the detail hero's, because the old card ramp decays to almost nothing across
        // a full-bleed panel. The bottom one carries the title and the page dots. Both fill with
        // matchParentSize and put their fade in the stops: the hero sits in a verticalScroll, so
        // a fractional height would measure against an unbounded maximum.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    overMediaSideScrim(),
                ),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0.4f to Color.Black.copy(alpha = 0f),
                        1f to Color.Black.copy(alpha = 0.90f),
                    ),
                ),
        )
    }
}

/**
 * The page on screen fades in over the one leaving, which stays opaque underneath until the
 * dissolve completes and then drops out — two art layers at half alpha each would let the fill
 * show through mid-turn. Returned as a reader for the draw phase, so a page turn redraws the
 * layers without recomposing the hero every frame.
 */
@Composable
private fun Transition<Int>.artAlpha(index: Int): () -> Float {
    val alpha = animateFloat(
        transitionSpec = { iglooTween(IglooMotion.PAGE_MS) },
        label = "heroArt$index",
    ) { active -> if (active == index) 1f else 0f }
    return { if (isRunning && currentState == index) 1f else alpha.value }
}

/**
 * Text cannot dissolve through text and stay legible, so the outgoing plate leaves first and the
 * incoming one arrives after it, each over `micro`.
 */
@Composable
private fun Transition<Int>.textAlpha(index: Int): () -> Float {
    val alpha = animateFloat(
        transitionSpec = {
            if (targetState == index) {
                iglooTween(IglooMotion.MICRO_MS, delayMillis = IglooMotion.MICRO_MS)
            } else {
                iglooTween(IglooMotion.MICRO_MS, easing = IglooEasing.exit)
            }
        },
        label = "heroText$index",
    ) { active -> if (active == index) 1f else 0f }
    return { alpha.value }
}

/** The words of one page, bottom-left on its plate (section 11.3.1). */
@Composable
private fun HeroPlate(
    hero: HomeHero,
    overMedia: Boolean,
    focused: Boolean,
    contentInset: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val colors = IglooTheme.colors
    Column(
        modifier = modifier.heroTextPlate(
            focused = focused,
            overMedia = overMedia,
            contentInset = contentInset,
        ),
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
    ) {
        HeroEyebrow(kind = hero.kind, overMedia = overMedia)
        IglooText(
            text = hero.title,
            style = IglooTheme.typography.display.overMedia(overMedia),
            color = if (overMedia) Color.White else colors.cardForeground,
            maxLines = 2,
        )
        HeroMetadataRow(hero = hero, overMedia = overMedia)
        if (hero.overview != null) {
            IglooText(
                text = hero.overview,
                style = IglooTheme.typography.bodyMedium.overMedia(overMedia),
                color = if (overMedia) Color.White else colors.mutedForeground,
                maxLines = 3,
            )
        }
    }
}

/**
 * "RECENTLY ADDED · TV SHOW" behind a short aurora mark: the kicker that says what the page is
 * before the title says which. Aurora is the one accent licensed over media (section 3.2), and the
 * mark is decoration, so the words carry the meaning on their own.
 */
@Composable
private fun HeroEyebrow(kind: HomeHeroKind, overMedia: Boolean) {
    val label = "$RECENTLY_ADDED · ${kind.noun}"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .width(EYEBROW_MARK_WIDTH.scaled())
                .height(EYEBROW_MARK_HEIGHT.scaled())
                .background(IglooTheme.colors.aurora, RoundedCornerShape(IglooTheme.radius.pill)),
        )
        IglooText(
            text = label.uppercase(),
            style = IglooTheme.typography.label
                .copy(letterSpacing = EYEBROW_TRACKING)
                .overMedia(overMedia),
            color = if (overMedia) OVER_MEDIA_SECONDARY else IglooTheme.colors.mutedForeground,
            maxLines = 1,
        )
    }
}

/**
 * Rating badge, certification chip, then year and extent — the detail hero's order, so the two
 * surfaces read alike. The row speaks nothing itself; [HomeHero.metadataDescription] is its
 * sentence on the page's target.
 */
@Composable
private fun HeroMetadataRow(hero: HomeHero, overMedia: Boolean) {
    val plainLine = joinedLine(listOfNotNull(hero.year, hero.extent), " · ")
    if (hero.ratingBadge == null && hero.certification == null && plainLine == null) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hero.ratingBadge != null) {
            RatingBadge(
                spec = hero.ratingBadge,
                radius = IglooTheme.radius.pill,
                verticalPadding = IglooTheme.spacing.xs,
            )
        }
        if (hero.certification != null) DetailChip(text = hero.certification, overMedia = overMedia)
        if (plainLine != null) {
            IglooText(
                text = plainLine,
                style = IglooTheme.typography.label.overMedia(overMedia),
                color = if (overMedia) OVER_MEDIA_SECONDARY else IglooTheme.colors.mutedForeground,
                maxLines = 1,
                modifier = Modifier.padding(start = IglooTheme.spacing.xs),
            )
        }
    }
}

/**
 * Which page is on screen, bottom-right: a long pill for the active page, dots for the rest.
 * Decoration only — the page targets already say "1 of 2" — so it has no semantics of its own.
 */
@Composable
private fun HeroPageIndicator(
    count: Int,
    activeIndex: Int,
    overMedia: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = IglooTheme.colors
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val active = index == activeIndex
            val width by animateDpAsState(
                targetValue = if (active) PAGE_PILL_ACTIVE_WIDTH.scaled() else PAGE_PILL_HEIGHT.scaled(),
                animationSpec = iglooTween(IglooMotion.STANDARD_MS),
                label = "heroPagePill$index",
            )
            val color = when {
                active && overMedia -> Color.White
                active -> colors.foreground
                overMedia -> Color.White.copy(alpha = 0.40f)
                else -> colors.border
            }
            Box(
                modifier = Modifier
                    .width(width)
                    .height(PAGE_PILL_HEIGHT.scaled())
                    .background(color, RoundedCornerShape(IglooTheme.radius.pill))
                    .testTag(if (active) "home_hero_dot_active" else "home_hero_dot"),
            )
        }
    }
}

/**
 * The focus plate: the bounded, always-inside-the-safe-area box that carries the section 6.1
 * treatment on behalf of the bleeding page target around it, and the pane's gutter on behalf of
 * the text inside it. Shared by the skeleton and every page so focus taken during the load sits
 * exactly where the loaded plate lands.
 *
 * `scaleOnFocus` stays false: the plate sits on a gradient over art, and lifting text off its own
 * ground is the one motion section 7.2 will not buy. The ground arrives with focus and leaves
 * with it, on the ring's own `micro` driver, and there is no resting border: at rest the words
 * sit on the side scrim the way the detail hero's do, so the billboard reads as art with a title
 * on it rather than a card over the art. Focused, the plate carries section 3.2's control ground
 * so the ring has a surface to contract against; off media it uses `muted`.
 */
@Composable
private fun Modifier.heroTextPlate(
    focused: Boolean,
    overMedia: Boolean,
    contentInset: PaddingValues,
): Modifier {
    val ground = if (overMedia) OVER_MEDIA_CONTROL_FILL else IglooTheme.colors.muted
    val fill by animateColorAsState(
        targetValue = if (focused) ground else ground.copy(alpha = 0f),
        animationSpec = iglooTween(IglooMotion.MICRO_MS),
        label = "heroPlateGround",
    )
    return this
        .insetHorizontally(contentInset)
        .padding(bottom = IglooTheme.layout.safeAreaVertical)
        .widthIn(max = HERO_TEXT_MAX_WIDTH.scaled())
        .focusRing(
            focused = focused,
            radius = IglooTheme.radius.lg,
            fill = fill,
            scaleOnFocus = false,
            restBorder = false,
        )
        .padding(IglooTheme.spacing.lg)
}

/** Text stubs where the loaded plate sits — static blocks, nothing loops (section 10). */
@Composable
private fun HeroSkeletonPlate(
    focused: Boolean,
    contentInset: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val colors = IglooTheme.colors
    Column(
        modifier = modifier.heroTextPlate(
            focused = focused,
            overMedia = false,
            contentInset = contentInset,
        ),
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
    ) {
        SkeletonStub(height = 14.dp, fill = colors.border, modifier = Modifier.width(160.dp.scaled()))
        SkeletonStub(height = 40.dp, fill = colors.border, modifier = Modifier.width(300.dp.scaled()))
        SkeletonStub(height = 18.dp, fill = colors.border, modifier = Modifier.width(200.dp.scaled()))
    }
}

private const val LOADING_DESCRIPTION = "Loading featured titles"

/** What every page is featured as; the eyebrow upper-cases it, the announcement speaks it. */
private const val RECENTLY_ADDED = "Recently added"

private val HomeHeroKind.noun: String
    get() = when (this) {
        HomeHeroKind.Movie -> "movie"
        HomeHeroKind.Show -> "TV show"
    }

/**
 * Contains text, so a minimum, not a fixed height (section 2.6); one-off per section 2.8.
 *
 * 360dp of the 540dp reference viewport: starting at the top edge, that leaves 180dp of the
 * Continue Watching rail showing, so section 8.2's scroll affordance holds.
 */
private val HERO_MIN_HEIGHT = 360.dp

/** Readable measure for the overview at bodyMedium — roughly 50 characters a line. */
private val HERO_TEXT_MAX_WIDTH = 420.dp

/** Where the art starts dissolving into the canvas: below the plate's text, inside its padding. */
private const val BOTTOM_FADE_START = 0.88f

private val EYEBROW_MARK_WIDTH = 4.dp
private val EYEBROW_MARK_HEIGHT = 16.dp

/** Tracking for the upper-case kicker; capitals set tight read as a shout at ten feet. */
private val EYEBROW_TRACKING = 1.5.sp

private val PAGE_PILL_HEIGHT = 8.dp
private val PAGE_PILL_ACTIVE_WIDTH = 32.dp
