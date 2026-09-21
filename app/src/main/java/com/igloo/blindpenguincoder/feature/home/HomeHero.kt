package com.igloo.blindpenguincoder.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.overMedia
import com.igloo.blindpenguincoder.core.design.scaled
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.focusRing

/**
 * The featured-movie banner at the top of home (docs/design-system.md section 11.3.1): a
 * full-bleed backdrop, one focus target, no action until the details screen lands. The caller
 * renders it only while [state] is Loading or Loaded — Hidden means no hero at all, and the
 * Continue Watching rail owns the pane's entry anchor instead.
 *
 * **The art reaches all four physical edges; the text does not.** [contentInset] is the pane's
 * gutter, and only the text plate takes it — that is what keeps the title out from under the nav
 * rail while letting the backdrop pass behind it. The focus ring rides the plate rather than the
 * bleeding surface for the same reason a backdrop may bleed and a word may not: a 3dp ring traced
 * round the panel would sit in the overscan margin, the one place section 2.5 says a TV may crop.
 *
 * The Loading state holds the final geometry so entry focus taken during the load sits exactly
 * where the loaded hero lands. A backdrop that is missing or fails to fetch drops the section
 * 3.2 over-media treatment entirely: token colors on the card fill, no gradients.
 *
 * A null [onSelect] keeps the hero a focus target but drops the button role and the "Open …"
 * action, the same contract as an inert poster card.
 */
@Composable
fun HomeHero(
    state: HomeHeroState,
    entryRequester: FocusRequester,
    leftFocusRequester: FocusRequester,
    downFocusRequester: FocusRequester,
    contentInset: PaddingValues,
    onSelect: ((Long) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (state is HomeHeroState.Hidden) return
    val colors = IglooTheme.colors
    var focused by remember { mutableStateOf(false) }
    // Only a loaded hero has a movie to open; the skeleton stays a plain focus target.
    val openHero = (state as? HomeHeroState.Loaded)?.let { loaded ->
        onSelect?.let { select -> { select(loaded.hero.id) } }
    }

    val heroModifier = modifier
        .fillMaxWidth()
        .heightIn(min = HERO_MIN_HEIGHT.scaled())
        // The fill still shows while the image fetches, and under a hero with no backdrop at
        // all. What it no longer carries is the focus ring — that moved to the text plate, which
        // is bounded and always inside the safe area.
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
        // Focus observation outside the clickable, per section 6.3's ordering rule.
        .onFocusChanged { focused = it.isFocused }
        .then(
            if (openHero != null) {
                Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = openHero,
                )
            } else {
                Modifier.focusable()
            },
        )
        .testTag("home_hero")

    when (state) {
        is HomeHeroState.Hidden -> Unit
        is HomeHeroState.Loading -> HeroSkeleton(
            focused = focused,
            contentInset = contentInset,
            modifier = heroModifier.semantics {
                contentDescription = "Loading featured movie"
                liveRegion = LiveRegionMode.Polite
            },
        )

        is HomeHeroState.Loaded -> HeroContent(
            hero = state.hero,
            focused = focused,
            contentInset = contentInset,
            modifier = heroModifier.clearAndSetSemantics {
                contentDescription = listOfNotNull(
                    "Featured",
                    state.hero.title,
                    state.hero.metadataLine,
                    // The visual clamp on the overview is not an accessibility clamp —
                    // TalkBack reads the whole thing (section 11.3.1).
                    state.hero.overview,
                ).joinToString(". ")
                if (openHero != null) {
                    role = Role.Button
                    onClick(label = "Open ${state.hero.title}") {
                        openHero()
                        true
                    }
                }
            },
        )
    }
}

@Composable
private fun HeroContent(
    hero: HomeHero,
    focused: Boolean,
    contentInset: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val colors = IglooTheme.colors
    var imageFailed by remember(hero.backdropUrl) { mutableStateOf(false) }
    val overMedia = hero.backdropUrl != null && !imageFailed

    Box(modifier = modifier) {
        if (overMedia) {
            AsyncImage(
                model = hero.backdropUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onState = { state ->
                    if (state is AsyncImagePainter.State.Error) imageFailed = true
                },
                modifier = Modifier.matchParentSize(),
            )
            // Over-media literals per section 3.2: the side gradient carries the text column
            // against busy art, the bottom gradient carries the title. Both static.
            //
            // Both size with matchParentSize, and the bottom one puts its fade in the stops
            // rather than in a 0.6 height fraction: the hero sits in a verticalScroll, so its
            // incoming maxHeight is unbounded and a fractional fill would measure to zero.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = 0.70f),
                            0.5f to Color.Black.copy(alpha = 0.35f),
                            1f to Color.Transparent,
                        ),
                    ),
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0.4f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.90f),
                        ),
                    ),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .heroTextPlate(focused = focused, contentInset = contentInset),
            verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
        ) {
            IglooText(
                text = hero.title,
                style = IglooTheme.typography.titleLarge.overMedia(overMedia),
                color = if (overMedia) Color.White else colors.cardForeground,
                maxLines = 2,
            )
            if (hero.metadataLine != null) {
                IglooText(
                    text = hero.metadataLine,
                    style = IglooTheme.typography.label.overMedia(overMedia),
                    color = if (overMedia) Color.White else colors.mutedForeground,
                    maxLines = 1,
                )
            }
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
}

/**
 * The focus plate: the bounded, always-inside-the-safe-area box that carries the section 6.1
 * treatment on behalf of the bleeding surface around it, and the pane's gutter on behalf of the
 * text inside it. Shared by both hero states so focus taken during the load sits exactly where
 * the loaded plate lands — the invariant the whole Loading geometry exists to keep.
 *
 * `scaleOnFocus` stays false: the plate sits on a gradient over art, and lifting text off its own
 * ground is the one motion section 7.2 will not buy.
 */
@Composable
private fun Modifier.heroTextPlate(
    focused: Boolean,
    contentInset: PaddingValues,
): Modifier {
    val direction = LocalLayoutDirection.current
    return this
        .padding(
            start = contentInset.calculateStartPadding(direction),
            end = contentInset.calculateEndPadding(direction),
            bottom = IglooTheme.layout.safeAreaVertical,
        )
        .widthIn(max = HERO_TEXT_MAX_WIDTH.scaled())
        .focusRing(
            focused = focused,
            radius = IglooTheme.radius.lg,
            // Section 3.2's chip-and-control ground: the plate has to read as a surface for the
            // ring to have anything to contract against, and it sits over media.
            fill = Color.Black.copy(alpha = 0.45f),
            scaleOnFocus = false,
        )
        .padding(IglooTheme.spacing.lg)
}

/** Text stubs where the loaded text column sits — static blocks, nothing loops (section 10). */
@Composable
private fun HeroSkeleton(
    focused: Boolean,
    contentInset: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val colors = IglooTheme.colors
    val stubShape = RoundedCornerShape(IglooTheme.radius.sm)
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .heroTextPlate(focused = focused, contentInset = contentInset),
            verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
        ) {
            Box(
                modifier = Modifier
                    .width(280.dp.scaled())
                    .heightIn(min = 28.dp.scaled())
                    .background(colors.border, stubShape),
            )
            Box(
                modifier = Modifier
                    .width(180.dp.scaled())
                    .heightIn(min = 14.dp.scaled())
                    .background(colors.border, stubShape),
            )
        }
    }
}

/**
 * Contains text, so a minimum, not a fixed height (section 2.6); one-off per section 2.8.
 *
 * 360dp of the 540dp reference viewport. It was 280dp when the hero sat under ~117dp of pane
 * header and gutter; starting at the top edge instead, 360 leaves *more* of the Continue Watching
 * rail showing than the old card did (540 - 360 = 180dp against 540 - 397 = 143dp), so section
 * 8.2's scroll affordance strengthens rather than weakens.
 */
private val HERO_MIN_HEIGHT = 360.dp

/** Readable measure for the overview at bodyMedium — roughly 50 characters a line. */
private val HERO_TEXT_MAX_WIDTH = 420.dp
