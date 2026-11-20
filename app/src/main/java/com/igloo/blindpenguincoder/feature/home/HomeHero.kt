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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import com.igloo.blindpenguincoder.CONFIRM_KEYS
import com.igloo.blindpenguincoder.core.design.IglooEasing
import com.igloo.blindpenguincoder.core.design.IglooMotion
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.MEDIA_FALLBACK_TILE
import com.igloo.blindpenguincoder.core.design.OVER_MEDIA_CONTROL_FILL
import com.igloo.blindpenguincoder.core.design.OVER_MEDIA_HAIRLINE
import com.igloo.blindpenguincoder.core.design.OVER_MEDIA_SECONDARY
import com.igloo.blindpenguincoder.core.design.iglooTween
import com.igloo.blindpenguincoder.core.design.overMedia
import com.igloo.blindpenguincoder.core.design.overMediaSideScrim
import com.igloo.blindpenguincoder.core.design.scaled
import com.igloo.blindpenguincoder.core.ui.ArtworkState
import com.igloo.blindpenguincoder.core.ui.BackdropOrBlurredPoster
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.ProgressTrack
import com.igloo.blindpenguincoder.core.ui.SkeletonStub
import com.igloo.blindpenguincoder.core.ui.focusRing
import com.igloo.blindpenguincoder.core.ui.insetHorizontally
import com.igloo.blindpenguincoder.core.ui.joinedLine
import com.igloo.blindpenguincoder.core.ui.rememberArtworkState
import com.igloo.blindpenguincoder.core.ui.standInLoaded
import com.igloo.blindpenguincoder.feature.shared.HeroMetadataRow

/**
 * The billboard at the top of home (docs/design-system.md section 11.3.1): what is in progress,
 * then the newest movie and the newest show, as the pages of one full-bleed hero, one page on
 * screen at a time. D-pad right and left turn the page; nothing ever turns it on its own (section 7.2). The caller renders it
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
 * **The focus target is the text column, not the bleed.** The art is paint only; the one node
 * sits over the column the plates use — inside [contentInset], full height — so the box TalkBack
 * draws is where the focus ring is. With a physical remote, TalkBack for TV moves the arrows
 * itself, by geometry: a node spanning the panel began at x = 0 and had nothing to its left, so
 * Left never reached the nav rail and Right from the rail skipped the hero; and Down picks what
 * lies nearest below the node's middle — the rail's heading under the column, not a card under
 * the middle of the panel.
 *
 * Each page opens its own details page — [onOpenMovie] the movie's, [onOpenShow] the show's —
 * but the lead page featuring an episode in progress, whose press is [onResumeEpisode], the same
 * press its Continue Watching card takes.
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
    onOpenMovie: (Long) -> Unit,
    onOpenShow: (Long) -> Unit,
    onResumeEpisode: (Long) -> Unit,
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
    val backdrops = pages.mapIndexed { index, page ->
        key(index) { rememberArtworkState(page?.backdropUrl) }
    }
    // What stands in for a page's missing backdrop: its poster, blurred (section 3.2).
    val posters = pages.mapIndexed { index, page ->
        key(index) { rememberArtworkState(page?.posterUrl) }
    }
    val transition = updateTransition(targetState = activeIndex, label = "heroPage")
    val openPage = activePage?.let { page ->
        val open = when {
            page.resumeLabel != null -> onResumeEpisode
            page.kind == HomeHeroKind.Show -> onOpenShow
            else -> onOpenMovie
        }
        { open(page.id) }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        // The bleed: art, fade, plates and dots, out to every physical edge. Paint only — what
        // the hero says is the one node laid over it below.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = HERO_MIN_HEIGHT.scaled())
                // The missing-media tile under every page: it shows while an image fetches and
                // under a page with no picture at all, and it is what licenses the scrims and
                // the white words from a page's first frame (section 3.2).
                .background(if (state is HomeHeroState.Loaded) MEDIA_FALLBACK_TILE else colors.muted)
                .testTag("home_hero_bleed")
                .clearAndSetSemantics { },
        ) {
            pages.forEachIndexed { index, page ->
                if (page != null) {
                    key(index) {
                        HeroArtLayer(
                            backdrop = backdrops[index],
                            poster = posters[index],
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

            // Every plate is composed, so the hero measures to the taller one and a turn never
            // moves the rails; only the active one is opaque.
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

        // The one focus target: the text column the plates sit in, full height (see above).
        Box(
            modifier = Modifier
                .matchParentSize()
                .insetHorizontally(contentInset)
                .wrapContentWidth(Alignment.Start)
                .width(HERO_TEXT_MAX_WIDTH.scaled())
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
                // would otherwise swap one for the other and drop focus on the floor. Select,
                // Enter and keypad Enter open on release, as clickable does; TalkBack's click goes
                // through the semantics action.
                .focusable()
                // Outside the clear, like the focusable: semantics inside it are wiped.
                .testTag("home_hero")
                .clearAndSetSemantics {
                    contentDescription =
                        activePage?.let { heroPageDescription(it, activeIndex, pages.size) }
                            ?: LOADING_DESCRIPTION
                    // No live region, on purpose. TalkBack speaks a description change on the node
                    // its cursor is on, which covers a turn and a load under focus; a live region
                    // would add a re-read whenever focus came or went, since every focus change
                    // reports a content change on the node.
                    if (openPage != null) {
                        role = Role.Button
                        onClick(label = heroPressLabel(activePage)) {
                            openPage()
                            true
                        }
                    }
                },
        )
    }
}

/**
 * What the hero announces for a page, as its single cleared node: which feature it is and where
 * it sits among the pages, then the title, the metadata sentence and the full overview — the
 * visual three-line clamp is not an accessibility clamp. The position is what tells a
 * screen-reader user there is a page to the right; a lone page has no position to give.
 */
internal fun heroPageDescription(hero: HomeHero, index: Int, count: Int): String {
    val feature = hero.kind.feature
    return listOfNotNull(
        if (count > 1) "$feature, ${index + 1} of $count" else feature,
        hero.title,
        hero.metadataDescription,
        hero.overview,
    ).joinToString(". ")
}

/**
 * One page's picture and its two over-media scrims. The picture is the backdrop, or the poster
 * blurred when the title has none, fading in once it decodes; with neither, the scrims lie on
 * the missing-media tile alone (section 3.2). The scrims do not wait for the picture: the tile
 * is under them from the first frame. The inactive page's image still loads, at zero alpha, so
 * a page turn never waits on the network.
 */
@Composable
private fun HeroArtLayer(
    backdrop: ArtworkState,
    poster: ArtworkState,
    pageAlpha: () -> Float,
    modifier: Modifier = Modifier,
) {
    val decodeAlpha by animateFloatAsState(
        targetValue = if (standInLoaded(backdrop, poster)) 1f else 0f,
        animationSpec = iglooTween(IglooMotion.PAGE_MS),
        label = "heroBackdrop",
    )
    Box(modifier = modifier.graphicsLayer { alpha = pageAlpha() }) {
        BackdropOrBlurredPoster(
            backdrop = backdrop,
            poster = poster,
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { alpha = decodeAlpha },
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
    focused: Boolean,
    contentInset: PaddingValues,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.heroTextPlate(
            focused = focused,
            overMedia = true,
            contentInset = contentInset,
        ),
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
    ) {
        HeroEyebrow(kind = hero.kind)
        IglooText(
            text = hero.title,
            style = IglooTheme.typography.display.overMedia(true),
            color = Color.White,
            maxLines = 2,
        )
        HeroMetadataRow(hero = hero)
        if (hero.progressFraction != null) {
            ProgressTrack(
                fraction = hero.progressFraction,
                // White, not the black track a poster card wears: focused, the plate's own
                // ground is the over-media black, and a black track disappears into it.
                ground = OVER_MEDIA_HAIRLINE,
                fill = IglooTheme.colors.primary,
                modifier = Modifier.width(HERO_PROGRESS_WIDTH.scaled()),
                rounded = true,
            )
        }
        if (hero.overview != null) {
            IglooText(
                text = hero.overview,
                style = IglooTheme.typography.bodyMedium.overMedia(true),
                color = Color.White,
                maxLines = 3,
            )
        }
    }
}

/**
 * "CONTINUE WATCHING" or "RECENTLY ADDED · TV SHOW" behind a short aurora mark: the kicker that
 * says what the page is before the title says which. Aurora is the one accent licensed over media (section 3.2), and the
 * mark is decoration, so the words carry the meaning on their own.
 */
@Composable
private fun HeroEyebrow(kind: HomeHeroKind) {
    val label = kind.eyebrow
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
                .overMedia(true),
            color = OVER_MEDIA_SECONDARY,
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
private fun HeroMetadataRow(hero: HomeHero) {
    val plainLine = joinedLine(listOfNotNull(hero.year, hero.extent), " · ")
    if (hero.ratingBadge == null && hero.certification == null && plainLine == null) return
    HeroMetadataRow(hero.ratingBadge, listOfNotNull(hero.certification), plainLine, overMedia = true)
}

/**
 * Which page is on screen, bottom-right: a long pill for the active page, dots for the rest.
 * Decoration only — the page targets already say "1 of 2" — so it has no semantics of its own.
 */
@Composable
private fun HeroPageIndicator(
    count: Int,
    activeIndex: Int,
    modifier: Modifier = Modifier,
) {
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
            val color = if (active) Color.White else Color.White.copy(alpha = 0.40f)
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
 * so the ring has a surface to contract against; the loading skeleton, which stands on the
 * token canvas rather than the tile, uses `muted`.
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

/** What a recent addition's page is featured as; the eyebrow upper-cases it. */
private const val RECENTLY_ADDED = "Recently added"

/** What a page is featured as, spoken: the announcement's first words. */
private val HomeHeroKind.feature: String
    get() = when (this) {
        HomeHeroKind.Continue -> "Continue watching"
        HomeHeroKind.Movie -> "$RECENTLY_ADDED movie"
        HomeHeroKind.Show -> "$RECENTLY_ADDED TV show"
    }

/** The same, as the kicker draws it: a recent addition's kind stands apart behind a dot. */
private val HomeHeroKind.eyebrow: String
    get() = when (this) {
        HomeHeroKind.Continue -> feature
        HomeHeroKind.Movie -> "$RECENTLY_ADDED · movie"
        HomeHeroKind.Show -> "$RECENTLY_ADDED · TV show"
    }

/**
 * What a press on [hero] does, for TalkBack: an episode in progress resumes, in the words its
 * Continue Watching card uses; every other page opens its title's page.
 */
internal fun heroPressLabel(hero: HomeHero): String = hero.resumeLabel ?: "Open ${hero.title}"

/**
 * Contains text, so a minimum, not a fixed height (section 2.6); one-off per section 2.8.
 *
 * 360dp of the 540dp reference viewport: starting at the top edge, that leaves 180dp of the
 * Continue Watching rail showing, so section 8.2's scroll affordance holds.
 */
private val HERO_MIN_HEIGHT = 360.dp

/** Readable measure for the overview at bodyMedium — roughly 50 characters a line. */
private val HERO_TEXT_MAX_WIDTH = 420.dp

/** The lead page's progress strip: short enough to read as a measure, not a rule under the text. */
private val HERO_PROGRESS_WIDTH = 200.dp

/** Where the art starts dissolving into the canvas: below the plate's text, inside its padding. */
private const val BOTTOM_FADE_START = 0.88f

private val EYEBROW_MARK_WIDTH = 4.dp
private val EYEBROW_MARK_HEIGHT = 16.dp

/** Tracking for the upper-case kicker; capitals set tight read as a shout at ten feet. */
private val EYEBROW_TRACKING = 1.5.sp

private val PAGE_PILL_HEIGHT = 8.dp
private val PAGE_PILL_ACTIVE_WIDTH = 32.dp
