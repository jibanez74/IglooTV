package com.igloo.blindpenguincoder.feature.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusRequester.Companion.Cancel
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.scaled
import com.igloo.blindpenguincoder.core.ui.IglooIcons
import com.igloo.blindpenguincoder.core.ui.IglooMediaRail
import com.igloo.blindpenguincoder.core.ui.IglooPosterCard
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.SectionHeading
import com.igloo.blindpenguincoder.core.ui.rememberSpokenAccessibilityEnabled

/*
 * The sections below a details hero that the movie and show pages share: overview, key crew, the
 * cast and extra-videos rails and the about panel. All of it sits past the backdrop's fade, on the
 * token canvas, so nothing here carries the section 3.2 over-media treatment. Each page keeps its
 * own section order and hand-wired vertical chain; these take their neighbours as requesters.
 *
 * Overview and Key Crew are prose, not targets, for a sighted d-pad user: moving down past them
 * scrolls them into view on the way. But TV TalkBack follows input focus and never reaches plain
 * text, so while a screen reader runs they join the chain as reading stops — the About panel's
 * pattern, which *is* always a focus target because content a d-pad can never scroll to may as
 * well not be on the page (the same reason section 10's empty rail is focusable).
 */

@Composable
internal fun OverviewSection(
    overview: String?,
    readingStop: Boolean,
    requester: FocusRequester,
    upRequester: FocusRequester?,
    downRequester: FocusRequester?,
    modifier: Modifier = Modifier,
) {
    val colors = IglooTheme.colors
    var focused by remember { mutableStateOf(false) }
    // Rendered even with nothing to say (web parity): a title without an overview reads as
    // "none available", not as a page with a piece missing — and the reading stop says the same.
    val text = overview ?: "No overview available."
    Column(
        modifier = modifier.then(
            if (readingStop) {
                Modifier.readingStopTarget(
                    tag = "details_overview_stop",
                    focused = focused,
                    requester = requester,
                    upRequester = upRequester,
                    downRequester = downRequester,
                    onFocusChanged = { focused = it },
                    description = "Overview. $text",
                )
            } else {
                Modifier
            },
        ),
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
    ) {
        SectionHeading("Overview")
        FadeClampedText(
            text = text,
            style = IglooTheme.typography.bodyMedium,
            // foreground, not mutedForeground: prose the user came to read has to clear the
            // section 12 body contrast target. The visual clamp is not an accessibility clamp —
            // the semantics tree carries the whole string.
            color = if (overview != null) colors.foreground else colors.mutedForeground,
            maxLines = OVERVIEW_MAX_LINES,
            modifier = Modifier.widthIn(max = SECTION_PROSE_MAX_WIDTH.scaled()),
        )
    }
}

/**
 * Prose clamped to [maxLines] that fades out instead of ellipsizing: an ellipsis mid-sentence
 * pretends the cut is deliberate punctuation, while the fade says plainly that the prose continues
 * past what fits. The fade dissolves into the `background` token, so the text must sit on the
 * plain canvas. The alpha-zero stop comes from the color itself — Color.Transparent is black at
 * zero and would gray the token fade.
 */
@Composable
internal fun FadeClampedText(
    text: String,
    style: TextStyle,
    color: Color,
    maxLines: Int,
    modifier: Modifier = Modifier,
) {
    val canvas = IglooTheme.colors.background
    var clamped by remember(text) { mutableStateOf(false) }
    val fadeHeight = PROSE_FADE_HEIGHT.scaled()
    IglooText(
        text = text,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Clip,
        onTextLayout = { clamped = it.hasVisualOverflow },
        modifier = modifier.drawWithContent {
            drawContent()
            if (clamped) {
                val fadePx = fadeHeight.toPx()
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to canvas.copy(alpha = 0f),
                        1f to canvas,
                        startY = size.height - fadePx,
                        endY = size.height,
                    ),
                    topLeft = Offset(0f, size.height - fadePx),
                    size = Size(size.width, fadePx),
                )
            }
        },
    )
}

/** Credits in rows of three label-over-name pairs: a movie's director and writers, a show's creators. */
@Composable
internal fun KeyCrewSection(
    crew: List<CrewEntry>,
    readingStop: Boolean,
    requester: FocusRequester,
    upRequester: FocusRequester?,
    downRequester: FocusRequester?,
    modifier: Modifier = Modifier,
) {
    val colors = IglooTheme.colors
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = modifier.then(
            if (readingStop) {
                Modifier.readingStopTarget(
                    tag = "details_key_crew_stop",
                    focused = focused,
                    requester = requester,
                    upRequester = upRequester,
                    downRequester = downRequester,
                    onFocusChanged = { focused = it },
                    description = "Key Crew. " +
                        crew.joinToString(". ") { "${it.job}: ${it.name}" },
                )
            } else {
                Modifier
            },
        ),
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
    ) {
        SectionHeading("Key Crew")
        crew.chunked(CREW_COLUMNS).forEach { rowEntries ->
            Row(
                // The overview's measure, so adjacent prose sections share one right edge
                // instead of the crew columns stretching a name across the whole pane.
                modifier = Modifier
                    .widthIn(max = SECTION_PROSE_MAX_WIDTH.scaled())
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.xl),
            ) {
                rowEntries.forEach { entry ->
                    Column(modifier = Modifier.weight(1f)) {
                        IglooText(
                            text = entry.job,
                            style = IglooTheme.typography.label,
                            color = colors.mutedForeground,
                            maxLines = 1,
                        )
                        IglooText(
                            text = entry.name,
                            style = IglooTheme.typography.bodyMedium,
                            color = colors.foreground,
                            maxLines = 1,
                        )
                    }
                }
                // Pad the last row, so a lone director does not stretch across the pane.
                repeat(CREW_COLUMNS - rowEntries.size) {
                    Column(modifier = Modifier.weight(1f)) {}
                }
            }
        }
    }
}

/**
 * A details-page rail: the rail primitive with per-rail focus memory, the one-anchor invariant
 * and the entry requester for free. The cast and extras rails are always [IglooRailState.Loaded];
 * the show page's episode rail passes whatever state its season is in, so the loading, error and
 * empty passthroughs only matter there. Each caller decides whether its cards act.
 *
 * Every direction out of the rail is pinned, in every state — on the cards and on the skeleton,
 * Retry or empty anchor alike. The shell is still composed underneath the overlay, so an unpinned
 * edge would let a spatial focus search land on a card the user cannot see (the same reason
 * section 9.3's dialog pins all four directions). Up and down go to the neighbouring sections
 * rather than whichever target wins a beam heuristic.
 */
@Composable
internal fun <T> DetailsRailSection(
    title: String,
    state: IglooRailState<T>,
    itemKey: (T) -> Long,
    contentInset: PaddingValues,
    entryRequester: FocusRequester,
    upRequester: FocusRequester?,
    downRequester: FocusRequester?,
    modifier: Modifier = Modifier,
    returnRequester: FocusRequester? = null,
    cardAspect: Float = IglooTheme.layout.posterAspect,
    cardWidth: Dp = IglooTheme.layout.posterWidth,
    screenReader: Boolean = rememberSpokenAccessibilityEnabled(),
    announceSectionTitle: Boolean = false,
    loadingLabel: String = "Loading",
    emptyIcon: ImageVector = IglooIcons.Movies,
    emptyText: String = "Nothing to show",
    onRetry: () -> Unit = {},
    card: @Composable (item: T, itemModifier: Modifier, cardAspect: Float) -> Unit,
) {
    var lastFocusedId by rememberSaveable { mutableStateOf<Long?>(null) }
    IglooMediaRail(
        title = title,
        state = state,
        itemKey = itemKey,
        entryRequester = entryRequester,
        // The overlay owns the whole screen; there is no spine to exit to on the left.
        leftFocusRequester = Cancel,
        upFocusRequester = upRequester ?: Cancel,
        downFocusRequester = downRequester ?: Cancel,
        lastFocusedKey = lastFocusedId,
        onItemFocused = { lastFocusedId = it },
        modifier = modifier,
        contentInset = contentInset,
        loadingLabel = loadingLabel,
        emptyIcon = emptyIcon,
        emptyText = emptyText,
        onRetry = onRetry,
        returnRequester = returnRequester,
        announceSectionTitle = announceSectionTitle,
        screenReader = screenReader,
        cardAspect = cardAspect,
        cardWidth = cardWidth,
        itemContent = card,
    )
}

/** The billing's top ten; the cards have no destination yet and stay inert. */
@Composable
internal fun CastSection(
    cast: List<CastMemberUi>,
    contentInset: PaddingValues,
    entryRequester: FocusRequester,
    upRequester: FocusRequester?,
    downRequester: FocusRequester?,
) {
    DetailsRailSection(
        title = "Cast",
        state = IglooRailState.Loaded(cast),
        itemKey = { it.id },
        contentInset = contentInset,
        entryRequester = entryRequester,
        upRequester = upRequester,
        downRequester = downRequester,
    ) { member, itemModifier, aspect ->
        IglooPosterCard(
            title = member.name,
            subtitle = member.character,
            detail = member.episodeCountText,
            imageUrl = member.photoUrl,
            onClick = null,
            aspect = aspect,
            fallbackIcon = IglooIcons.Person,
            modifier = itemModifier.testTag("cast_card_${member.id}"),
        )
    }
}

/**
 * The §8.2 wide-card rail: 16:9 thumbnails through the YouTube proxy, the video's type as the
 * card's one context line. Where activating a card opens the trailer player overlay (section
 * 11.8.1), each announces "Play {title}" — the honest verb, where the default "Open" promises a
 * page — and [returnRequester] rides the rail's last-focused card, so closing the player restores
 * focus to the exact card that launched it. A null [onPlayExtra] draws the cards inert: focusable,
 * announcing their title and type, and no action.
 */
@Composable
internal fun ExtraVideosSection(
    videos: List<ExtraVideoUi>,
    contentInset: PaddingValues,
    entryRequester: FocusRequester,
    upRequester: FocusRequester?,
    downRequester: FocusRequester?,
    returnRequester: FocusRequester?,
    onPlayExtra: ((ExtraVideoUi) -> Unit)?,
) {
    DetailsRailSection(
        title = "Extra Videos",
        state = IglooRailState.Loaded(videos),
        itemKey = { it.id },
        contentInset = contentInset,
        entryRequester = entryRequester,
        upRequester = upRequester,
        downRequester = downRequester,
        returnRequester = returnRequester,
        cardAspect = IglooTheme.layout.wideAspect,
        cardWidth = IglooTheme.layout.wideCardWidth,
    ) { video, itemModifier, aspect ->
        IglooPosterCard(
            title = video.title,
            subtitle = video.typeLabel,
            imageUrl = video.thumbnailUrl,
            onClick = onPlayExtra?.let { play -> { play(video) } },
            actionLabel = "Play ${video.title}",
            aspect = aspect,
            width = IglooTheme.layout.wideCardWidth,
            modifier = itemModifier.testTag("extra_card_${video.id}"),
        )
    }
}

/** The fine print, "About {title}", on the [FactsSection] treatment every details page shares. */
@Composable
internal fun AboutSection(
    title: String,
    facts: List<FactUi>,
    requester: FocusRequester,
    upRequester: FocusRequester?,
    modifier: Modifier = Modifier,
) {
    FactsSection(
        heading = "About $title",
        tag = "details_about",
        facts = facts,
        description = "About $title. " + facts.joinToString(". ") { "${it.label}: ${it.value}" },
        requester = requester,
        upRequester = upRequester,
        valueMaxLines = 2,
        modifier = modifier,
    )
}

/** Readable measure for bodyMedium prose — roughly 70 characters a line. */
private val SECTION_PROSE_MAX_WIDTH = 620.dp

/** About one bodyMedium line, so the fade dissolves the last visible line's descenders. */
private val PROSE_FADE_HEIGHT = 22.dp

private const val OVERVIEW_MAX_LINES = 6
private const val CREW_COLUMNS = 3
