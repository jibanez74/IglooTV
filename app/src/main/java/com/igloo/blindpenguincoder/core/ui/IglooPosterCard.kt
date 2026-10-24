package com.igloo.blindpenguincoder.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.OVER_MEDIA_TRACK

/**
 * Watch progress shown on a poster card. The description joins the card's one TalkBack
 * announcement, so a bar can never render without being announced (design system section 12).
 */
data class PosterCardProgress(
    val fraction: Float,
    val description: String,
)

/**
 * Artwork with its title and one optional line of context below. The artwork carries the focus
 * treatment — ring, scale, and glow stay on it while the text keeps still — but the whole card
 * is one focus target and one TalkBack node.
 *
 * [aspect] defaults to the 2:3 movie poster; album art passes `albumAspect`, and wide video
 * cards pass `wideAspect` with `wideCardWidth` as [width] (section 8.2). A [width] of
 * [Dp.Unspecified] makes the card fill its parent instead, which is what a `LazyVerticalGrid`
 * cell wants — the cell is already sized and a fixed card width would leave ragged gutters. A
 * null or failed image falls back to [fallbackIcon] on the muted fill; the text below is
 * unchanged, so the card loses nothing but the artwork. An optional progress bar sits on the
 * artwork's bottom edge and is announced through [PosterCardProgress.description].
 *
 * A null [onClick] still renders a focusable card — the rails' focus model needs every card to
 * be a landing site — but it drops the button role and the "Open …" action, because announcing
 * an action that does nothing is worse for a screen reader than announcing none.
 *
 * [actionLabel] replaces the default "Open [title]" action label when pressing the card does
 * something other than open a page — a video card plays, so it says "Play …" (section 12: the
 * announced action must match what pressing actually does). [semanticLabel] replaces the default
 * "title, subtitle" announcement when the visible pair does not read well as a sentence.
 *
 * [detail] is an optional third line under [subtitle] — a cast card's "228 episodes" — in the
 * same quiet tier, and joins the spoken sentence after it.
 *
 * [artworkRadius] is the artwork's corner: `radius.lg` for posters and covers, `radius.pill` for
 * a musician thumbnail, which the focus ring resolves to a circle (section 8.2). A circle wants
 * its text centred beneath it, which is what [centerText] does.
 */
@Composable
fun IglooPosterCard(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    detail: String? = null,
    actionLabel: String? = null,
    semanticLabel: String? = null,
    progress: PosterCardProgress? = null,
    aspect: Float = IglooTheme.layout.posterAspect,
    width: Dp = IglooTheme.layout.posterWidth,
    fallbackIcon: ImageVector = IglooIcons.Movies,
    artworkRadius: Dp = IglooTheme.radius.lg,
    centerText: Boolean = false,
) {
    val colors = IglooTheme.colors
    var focused by remember { mutableStateOf(false) }
    val spokenDescription = mediaSectionDescription(
        semanticLabel ?: listOfNotNull(title, subtitle, detail, progress?.description)
            .joinToString(", "),
    )

    Column(
        horizontalAlignment = if (centerText) Alignment.CenterHorizontally else Alignment.Start,
        modifier = modifier
            .cardWidth(width)
            .mediaCardInteraction(
                onFocusChanged = { focused = it },
                onClick = onClick,
                description = spokenDescription,
                actionLabel = actionLabel ?: "Open $title",
            ),
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                // focusRing owns the clip, so the muted fill doubles as the image backdrop and
                // nothing here may clip over it.
                .focusRing(
                    focused = focused,
                    radius = artworkRadius,
                    fill = colors.muted,
                ),
            contentAlignment = Alignment.Center,
        ) {
            ArtworkOrGlyph(imageUrl, fallbackIcon)
            if (progress != null) {
                // focusRing clips the corners.
                ProgressTrack(
                    fraction = progress.fraction,
                    ground = OVER_MEDIA_TRACK,
                    fill = colors.primary,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
        val textAlign = if (centerText) TextAlign.Center else TextAlign.Start
        IglooText(
            text = title,
            style = IglooTheme.typography.bodyMedium.copy(textAlign = textAlign),
            color = colors.foreground,
            maxLines = 2,
        )
        if (subtitle != null) {
            IglooText(
                text = subtitle,
                style = IglooTheme.typography.label.copy(textAlign = textAlign),
                color = colors.mutedForeground,
                maxLines = 1,
            )
        }
        if (detail != null) {
            IglooText(
                text = detail,
                style = IglooTheme.typography.label.copy(textAlign = textAlign),
                color = colors.mutedForeground,
                maxLines = 1,
            )
        }
    }
}

/**
 * How a media card takes focus, a press and a screen reader, shared with the In Theaters card:
 * focus observed outside the clickable (section 6.3), a `clickable` with no indication because
 * the focus ring is the indication, and one cleared node reading [description]. A null [onClick]
 * leaves a plain focus target — the rails' focus model needs every card to be a landing site —
 * with no role and no action, because an action that does nothing is worse announced than
 * absent.
 */
internal fun Modifier.mediaCardInteraction(
    onFocusChanged: (Boolean) -> Unit,
    onClick: (() -> Unit)?,
    description: String,
    actionLabel: String,
    /**
     * A held Center — foundation's key timer fires it after the system long-press timeout and
     * swallows the release's click. TalkBack is not offered a long-click gesture TV has no idiom
     * for; the same choice travels as a named entry of [customActions] instead.
     */
    onLongClick: (() -> Unit)? = null,
    customActions: List<CustomAccessibilityAction> = emptyList(),
): Modifier = this
    .onFocusChanged { onFocusChanged(it.isFocused) }
    .then(
        when {
            onClick == null -> Modifier.focusable()
            onLongClick == null ->
                Modifier.clickable(interactionSource = null, indication = null, onClick = onClick)
            else -> Modifier.combinedClickable(
                interactionSource = null,
                indication = null,
                onLongClick = onLongClick,
                onClick = onClick,
            )
        },
    )
    .clearAndSetSemantics {
        contentDescription = description
        if (onClick != null) {
            role = Role.Button
            onClick(label = actionLabel) {
                onClick()
                true
            }
        }
        if (customActions.isNotEmpty()) this.customActions = customActions
    }

/**
 * A grid cell already has a width; a rail's card does not. [Dp.Unspecified] fills the parent
 * instead — applying `width()` unconditionally would override a caller's `fillMaxWidth()`, which
 * comes second in the chain, and leave grid cards at the rail's size with ragged gutters.
 */
internal fun Modifier.cardWidth(width: Dp): Modifier =
    then(if (width == Dp.Unspecified) Modifier.fillMaxWidth() else Modifier.width(width))
