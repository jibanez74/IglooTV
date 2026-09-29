package com.igloo.blindpenguincoder.feature.shared

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.igloo.blindpenguincoder.core.design.IglooMotion
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.OVER_MEDIA_CONTROL_FILL
import com.igloo.blindpenguincoder.core.design.OVER_MEDIA_TERTIARY
import com.igloo.blindpenguincoder.core.design.iglooTween
import com.igloo.blindpenguincoder.core.design.overMedia
import com.igloo.blindpenguincoder.core.design.scaled
import com.igloo.blindpenguincoder.core.ui.ArtworkOrGlyph
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.focusRing
import com.igloo.blindpenguincoder.core.ui.iglooSurface
import com.igloo.blindpenguincoder.core.ui.pinnedToScreen

/*
 * The hero every details overlay shares (docs/design-system.md sections 11.4.1, 11.5.1 and
 * 11.5.2): the library and in-theaters movie pages, the album page and the musician page all
 * put artwork and a prose block over a full-bleed backdrop, with one reading stop for the prose
 * under a screen reader and a geometry-matched skeleton while the page loads.
 */

/**
 * The hero region, full-bleed: the artwork blown up as a backdrop reaches the physical edges
 * and scrolls away with the header, so everything below reads on the plain token canvas. Two
 * scrims with two jobs (sections 3.2 and 11.4): the black side gradient is the over-media
 * literal that licenses the white text column against busy art; the vertical fade is the token
 * gradient that blends the backdrop into the canvas the sections sit on. The whole stack fades
 * in rather than popping (section 7.2). [header] is told whether media has actually decoded
 * behind it, because section 3.2's literals are licensed only then; a non-null URL alone would
 * paint white text over the bare canvas for the whole load window.
 *
 * The stops are the detail hero's own, not section 3.2's home-hero ramp. That one is written for
 * a clipped card about 752dp wide; stretched across a full-bleed panel it has decayed to alpha
 * 0.14 by the time the metadata line ends, and the backdrop's highlights come back through the
 * text column.
 */
@Composable
internal fun DetailsHero(
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
        label = "detailsBackdrop",
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

/**
 * The hero header's placement: bottom-left of the hero, inside the safe area. Deliberately not
 * staggered: the header holds the entry focus, and a rise would move the focused button's bounds
 * while the scroll container is bringing it into view.
 */
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

/** The hero's artwork; decorative — it says nothing the text does not, so TalkBack skips it. */
@Composable
internal fun HeroArtwork(
    imageUrl: String?,
    aspect: Float,
    radius: Dp,
    fallbackIcon: ImageVector,
) {
    Box(
        modifier = Modifier
            .width(IglooTheme.layout.posterWidth)
            .aspectRatio(aspect)
            .iglooSurface(radius = radius, fill = IglooTheme.colors.muted),
        contentAlignment = Alignment.Center,
    ) {
        ArtworkOrGlyph(imageUrl, fallbackIcon)
    }
}

/**
 * The hero's prose as one reading stop. TV TalkBack follows input focus and never traverses
 * plain text, so while a screen reader runs the whole block is a focus target — reachable by
 * pressing up from the action row — that speaks [description] in a single announcement. Without
 * one it stays what it always was: text the d-pad passes by.
 */
@Composable
internal fun HeroReadingStop(
    enabled: Boolean,
    tag: String,
    overMedia: Boolean,
    requester: FocusRequester,
    downRequester: FocusRequester,
    description: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = if (enabled) {
            Modifier.readingStopTarget(
                tag = tag,
                focused = focused,
                requester = requester,
                upRequester = null,
                downRequester = downRequester,
                onFocusChanged = { focused = it },
                description = description,
                radius = IglooTheme.radius.lg,
                overMedia = overMedia,
            )
        } else {
            Modifier
        },
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
        content = content,
    )
}

/** The hero's title: the screen's one heading, white over media. */
@Composable
internal fun HeroTitle(text: String, overMedia: Boolean) {
    IglooText(
        text = text,
        style = IglooTheme.typography.titleLarge.overMedia(overMedia),
        color = if (overMedia) Color.White else IglooTheme.colors.foreground,
        maxLines = 2,
        modifier = Modifier.semantics { heading() },
    )
}

/** The hero's genres line, the tertiary text tier. */
@Composable
internal fun HeroGenresLine(text: String, overMedia: Boolean) {
    IglooText(
        text = text,
        style = IglooTheme.typography.label.overMedia(overMedia),
        color = if (overMedia) OVER_MEDIA_TERTIARY else IglooTheme.colors.mutedForeground,
        maxLines = 1,
    )
}

/**
 * The pill ground is the section 3.2 over-media chip literal — black with a translucent white
 * hairline, deliberately theme-blind because a backdrop is behind it. The fallback is the token
 * pair the badge alphas of section 3.1 prescribe for chrome on a plain canvas.
 */
@Composable
internal fun DetailChip(
    text: String,
    overMedia: Boolean,
) {
    val colors = IglooTheme.colors
    IglooText(
        text = text,
        style = IglooTheme.typography.label,
        color = if (overMedia) Color.White.copy(alpha = 0.90f) else colors.foreground,
        maxLines = 1,
        modifier = Modifier
            .iglooSurface(
                radius = IglooTheme.radius.pill,
                fill = if (overMedia) OVER_MEDIA_CONTROL_FILL else colors.muted,
                border = if (overMedia) Color.White.copy(alpha = 0.25f) else colors.border,
            )
            .padding(horizontal = 12.dp.scaled(), vertical = IglooTheme.spacing.xs),
    )
}

/**
 * Static geometry-matched stand-ins (section 10): the artwork and text stubs where the hero
 * lands, and an action row whose first slot is the screen's one focusable anchor, so entry
 * focus taken during the load sits exactly where the primary action appears. [belowAnchor]
 * reserves whatever the real primary action carries under it; [trailingStubWidths] are the
 * rest of the row's controls, so the loading -> loaded swap does not move the anchor.
 */
@Composable
internal fun DetailsHeroSkeleton(
    artworkAspect: Float,
    artworkShape: Shape,
    anchorWidth: Dp,
    loadingLabel: String,
    anchorRequester: FocusRequester,
    trailingStubWidths: List<Dp>,
    belowAnchor: @Composable () -> Unit = {},
) {
    val colors = IglooTheme.colors
    val layout = IglooTheme.layout
    val stubShape = RoundedCornerShape(IglooTheme.radius.sm)
    val controlShape = RoundedCornerShape(IglooTheme.radius.lg)
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
                    .aspectRatio(artworkAspect)
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
                    Column {
                        Box(
                            modifier = Modifier
                                .width(anchorWidth)
                                .heightIn(min = IglooTheme.sizes.controlHeight)
                                .focusRing(
                                    focused = focused,
                                    radius = IglooTheme.radius.lg,
                                    fill = colors.muted,
                                )
                                .focusRequester(anchorRequester)
                                // The screen's only focusable while loading, and the shell is
                                // still composed underneath: without this, Left or Down pressed
                                // before the page lands walks focus onto an invisible card.
                                .pinnedToScreen()
                                .onFocusChanged { focused = it.isFocused }
                                .focusable()
                                .clearAndSetSemantics {
                                    contentDescription = loadingLabel
                                    liveRegion = LiveRegionMode.Polite
                                },
                        )
                        belowAnchor()
                    }
                    trailingStubWidths.forEach { width ->
                        Box(
                            modifier = Modifier
                                .width(width)
                                .heightIn(min = IglooTheme.sizes.controlHeight)
                                .background(colors.muted, controlShape),
                        )
                    }
                }
            }
        }
    }
}

/** About 60% of the reference viewport's height (section 8.1); contains text, so a minimum. */
private val HERO_MIN_HEIGHT = 320.dp
