package com.igloo.blindpenguincoder.feature.movies

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
import com.igloo.blindpenguincoder.core.ui.focusRing

/**
 * Everything below the hero: overview, key crew, the cast and extra-videos rails, and the
 * fine-print about block. All of it sits past the backdrop's fade, on the token canvas, so
 * nothing here carries the section 3.2 over-media treatment. Sections with nothing to show are
 * skipped entirely rather than rendering empty shells.
 *
 * Overview and Key Crew are prose, not targets: they sit between the hero and the cast rail, so
 * moving down from the actions scrolls them into view on the way. The About block *is* a focus
 * target — it is below the last rail, and content a d-pad can never scroll to may as well not
 * be on the page (the same reason section 10's empty rail is focusable).
 */
@Composable
internal fun MovieDetailsSections(
    movie: MovieDetailsUi,
    castEntryRequester: FocusRequester,
    extrasEntryRequester: FocusRequester,
    aboutRequester: FocusRequester,
    upFromSections: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val hasCast = movie.cast.isNotEmpty()
    val hasExtras = movie.extraVideos.isNotEmpty()
    val hasAbout = !movie.about.isEmpty

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.lg),
    ) {
        OverviewSection(overview = movie.overview)
        if (movie.keyCrew.isNotEmpty()) {
            KeyCrewSection(crew = movie.keyCrew)
        }
        if (hasCast) {
            CastSection(
                cast = movie.cast,
                entryRequester = castEntryRequester,
                upRequester = upFromSections,
                downRequester = when {
                    hasExtras -> extrasEntryRequester
                    hasAbout -> aboutRequester
                    else -> null
                },
            )
        }
        if (hasExtras) {
            ExtraVideosSection(
                videos = movie.extraVideos,
                entryRequester = extrasEntryRequester,
                // The cast rail's entry requester rides its last-focused card, so up from the
                // extras lands where the user left the cast, not on its first card.
                upRequester = if (hasCast) castEntryRequester else upFromSections,
                downRequester = if (hasAbout) aboutRequester else null,
            )
        }
        if (hasAbout) {
            AboutSection(
                title = movie.title,
                about = movie.about,
                requester = aboutRequester,
                upRequester = when {
                    hasExtras -> extrasEntryRequester
                    hasCast -> castEntryRequester
                    else -> upFromSections
                },
            )
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    IglooText(
        text = text,
        style = IglooTheme.typography.titleMedium,
        color = IglooTheme.colors.foreground,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun OverviewSection(overview: String?) {
    val colors = IglooTheme.colors
    var clamped by remember(overview) { mutableStateOf(false) }
    val fadeHeight = OVERVIEW_FADE_HEIGHT.scaled()
    Column(verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm)) {
        SectionHeading("Overview")
        IglooText(
            // Rendered even with nothing to say (web parity): a movie without an overview reads
            // as "none available", not as a page with a piece missing.
            text = overview ?: "No overview available.",
            style = IglooTheme.typography.bodyMedium,
            // foreground, not mutedForeground: prose the user came to read has to clear the
            // section 12 body contrast target. The visual clamp is not an accessibility clamp —
            // the semantics tree carries the whole string.
            color = if (overview != null) colors.foreground else colors.mutedForeground,
            maxLines = OVERVIEW_MAX_LINES,
            // A clamped synopsis fades out instead of ellipsizing: an ellipsis mid-sentence
            // pretends the cut is deliberate punctuation, while the fade says plainly that the
            // prose continues past what fits. The alpha-zero stop comes from the color itself —
            // Color.Transparent is black at zero and would gray the token fade.
            overflow = TextOverflow.Clip,
            onTextLayout = { clamped = it.hasVisualOverflow },
            modifier = Modifier
                .widthIn(max = SECTION_PROSE_MAX_WIDTH.scaled())
                .drawWithContent {
                    drawContent()
                    if (clamped) {
                        val fadePx = fadeHeight.toPx()
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to colors.background.copy(alpha = 0f),
                                1f to colors.background,
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
}

/** Director first, then the writing credits, in rows of three label-over-name pairs. */
@Composable
private fun KeyCrewSection(crew: List<CrewEntry>) {
    val colors = IglooTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm)) {
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
 * A detail-screen rail: the rail primitive with a state that is always Loaded, so per-rail
 * focus memory, the one-anchor invariant, and the entry requester come for free. Cards are
 * focusable but inert — there is no destination for them yet, and a card that announces
 * "Open …" and then does nothing is worse than one that announces none.
 *
 * Every direction out of the rail is pinned. The shell is still composed underneath this
 * overlay, so an unpinned edge would let a spatial focus search land on a card the user cannot
 * see (the same reason section 9.3's dialog pins all four directions). Up and down go to the
 * neighbouring sections rather than whichever target wins a beam heuristic — the same
 * hand-wiring the home hero uses for its down target.
 */
@Composable
private fun <T> DetailsRailSection(
    title: String,
    items: List<T>,
    itemKey: (T) -> Long,
    entryRequester: FocusRequester,
    upRequester: FocusRequester,
    downRequester: FocusRequester?,
    cardAspect: Float = IglooTheme.layout.posterAspect,
    cardWidth: Dp = IglooTheme.layout.posterWidth,
    card: @Composable (item: T, itemModifier: Modifier, cardAspect: Float) -> Unit,
) {
    var lastFocusedId by rememberSaveable { mutableStateOf<Long?>(null) }
    IglooMediaRail(
        title = title,
        state = IglooRailState.Loaded(items),
        itemKey = itemKey,
        entryRequester = entryRequester,
        // The overlay owns the whole screen; there is no spine to exit to on the left.
        leftFocusRequester = Cancel,
        lastFocusedKey = lastFocusedId,
        onItemFocused = { lastFocusedId = it },
        cardAspect = cardAspect,
        cardWidth = cardWidth,
    ) { item, itemModifier, aspect ->
        card(
            item,
            itemModifier.focusProperties {
                up = upRequester
                down = downRequester ?: Cancel
            },
            aspect,
        )
    }
}

@Composable
private fun CastSection(
    cast: List<CastMemberUi>,
    entryRequester: FocusRequester,
    upRequester: FocusRequester,
    downRequester: FocusRequester?,
) {
    DetailsRailSection(
        title = "Cast",
        items = cast,
        itemKey = { it.id },
        entryRequester = entryRequester,
        upRequester = upRequester,
        downRequester = downRequester,
    ) { member, itemModifier, aspect ->
        IglooPosterCard(
            title = member.name,
            subtitle = member.character,
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
 * card's one context line. Trailer playback is a later feature, so the cards are inert under
 * the same contract as the cast.
 */
@Composable
private fun ExtraVideosSection(
    videos: List<ExtraVideoUi>,
    entryRequester: FocusRequester,
    upRequester: FocusRequester,
    downRequester: FocusRequester?,
) {
    DetailsRailSection(
        title = "Extra Videos",
        items = videos,
        itemKey = { it.id },
        entryRequester = entryRequester,
        upRequester = upRequester,
        downRequester = downRequester,
        cardAspect = IglooTheme.layout.wideAspect,
        cardWidth = IglooTheme.layout.wideCardWidth,
    ) { video, itemModifier, aspect ->
        IglooPosterCard(
            title = video.title,
            subtitle = video.typeLabel,
            imageUrl = video.thumbnailUrl,
            onClick = null,
            aspect = aspect,
            width = IglooTheme.layout.wideCardWidth,
            modifier = itemModifier.testTag("extra_card_${video.id}"),
        )
    }
}

/**
 * The fine print. The heading lives outside the focusable panel, so it lines up with the other
 * section headings despite the panel's inner padding and keeps its heading semantics for
 * TalkBack's heading navigation. The rows are one focus stop and one TalkBack node: four
 * two-word rows would be four announcements of nothing much, and the block carries no action to
 * gate — the heading node directly above supplies the "About" framing, so the panel does not
 * repeat it.
 */
@Composable
private fun AboutSection(
    title: String,
    about: AboutUi,
    requester: FocusRequester,
    upRequester: FocusRequester,
) {
    val colors = IglooTheme.colors
    var focused by remember { mutableStateOf(false) }
    val rows = listOfNotNull(
        about.production?.let { "Production" to it },
        about.language?.let { "Original language" to it },
        about.budget?.let { "Budget" to it },
        about.revenue?.let { "Revenue" to it },
    )

    Column(verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm)) {
        SectionHeading("About $title")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Before the cleared semantics, which wipe everything below them in the chain.
                .testTag("details_about")
                // Panel radius, not the button radius the rest of the app's focusables use, and
                // the focused fill instead of a bare ring: this is a focus target only so a d-pad
                // can scroll to it (section 11.4.1), and it carries no action to promise.
                .focusRing(
                    focused = focused,
                    radius = IglooTheme.radius.xl,
                    fill = if (focused) colors.card.copy(alpha = 0.72f) else Color.Transparent,
                    scaleOnFocus = false,
                )
                .focusRequester(requester)
                .focusProperties {
                    up = upRequester
                    down = Cancel
                    left = Cancel
                    right = Cancel
                }
                .onFocusChanged { focused = it.isFocused }
                .focusable()
                .clearAndSetSemantics {
                    contentDescription = rows.joinToString(". ") { "${it.first}: ${it.second}" }
                }
                .padding(IglooTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.xs),
        ) {
            rows.forEach { (label, value) ->
                Row(horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm)) {
                    IglooText(
                        text = "$label:",
                        style = IglooTheme.typography.label,
                        color = colors.mutedForeground,
                        maxLines = 1,
                    )
                    IglooText(
                        text = value,
                        style = IglooTheme.typography.bodyMedium,
                        color = colors.foreground,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

/** Readable measure for bodyMedium prose — roughly 70 characters a line. */
private val SECTION_PROSE_MAX_WIDTH = 620.dp

/** About one bodyMedium line, so the fade dissolves the last visible line's descenders. */
private val OVERVIEW_FADE_HEIGHT = 22.dp

private const val OVERVIEW_MAX_LINES = 6
private const val CREW_COLUMNS = 3
