package com.igloo.blindpenguincoder.feature.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.scaled
import com.igloo.blindpenguincoder.core.ui.IglooIconButton
import com.igloo.blindpenguincoder.core.ui.IglooIcons
import com.igloo.blindpenguincoder.core.ui.IglooMenu
import com.igloo.blindpenguincoder.core.ui.IglooMenuItem
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.focusRing

/** The three controls of a track row, left to right. */
enum class TrackRowColumn { Play, Like, More }

/** One requester per control, so a host can wire and restore focus to any of the three. */
@Stable
class TrackRowRequesters {
    val play = FocusRequester()
    val like = FocusRequester()
    val more = FocusRequester()

    operator fun get(column: TrackRowColumn): FocusRequester = when (column) {
        TrackRowColumn.Play -> play
        TrackRowColumn.Like -> like
        TrackRowColumn.More -> more
    }
}

/**
 * How a row sits in its list's focus chain. [up] and [down] answer per column so a vertical
 * move keeps its column; a null answer leaves that direction to the spatial search, which is
 * what a lazy list wants (the next row's requester may not be attached yet), while a plain
 * column hands every edge explicitly. [left] is the spine on a pane and Cancel on an overlay.
 * [riders] are extra requesters a host parks on one control — the pane's entry anchor, the
 * node a player returns focus to — for the row that currently holds that role.
 */
@Stable
class TrackRowFocus(
    val requesters: TrackRowRequesters,
    val up: (TrackRowColumn) -> FocusRequester?,
    val down: (TrackRowColumn) -> FocusRequester?,
    val left: FocusRequester?,
    val riders: (TrackRowColumn) -> List<FocusRequester> = { emptyList() },
)

/**
 * One track with its three actions — play, like, more — all focusable and none hidden until
 * focus (docs/design-system.md section 11.5). The row itself is never a focus target: while
 * any control holds focus the row paints a `muted @ 0.50` ground at the control radius, which
 * at ten feet says which row the three controls belong to.
 *
 * TalkBack hears the row's information once. Play carries the whole sentence ([TrackRowUi
 * .spokenInfo]) plus "Liked" as its state; Like names itself with the like state and an action
 * that names the track; More names the track in its label. The text nodes are cleared.
 *
 * [liked] null means the like set is unknown: Like stays a focus target but announces no
 * action, the inert-control rule. [onOpenMore] null keeps More composed and inert for the same
 * reason — dropping it would break the column geometry every row shares.
 */
@Composable
fun TrackRow(
    track: TrackRowUi,
    liked: Boolean?,
    likePending: Boolean,
    focus: TrackRowFocus,
    onPlay: () -> Unit,
    onToggleLike: () -> Unit,
    onOpenMore: ((Rect) -> Unit)?,
    modifier: Modifier = Modifier,
    onColumnFocused: (TrackRowColumn) -> Unit = {},
) {
    val colors = IglooTheme.colors
    var rowHasFocus by remember { mutableStateOf(false) }
    var moreBounds by remember { mutableStateOf(Rect.Zero) }
    val ground = if (rowHasFocus) colors.muted.copy(alpha = 0.50f) else Color.Transparent

    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("track_row_${track.id}")
            .onFocusChanged { rowHasFocus = it.hasFocus }
            .background(ground, RoundedCornerShape(IglooTheme.radius.lg))
            .padding(horizontal = IglooTheme.spacing.sm, vertical = IglooTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (track.indexText != null) {
            IglooText(
                text = track.indexText,
                style = IglooTheme.typography.label,
                color = colors.mutedForeground,
                maxLines = 1,
                modifier = Modifier
                    .widthIn(min = TRACK_INDEX_MIN_WIDTH.scaled())
                    .clearAndSetSemantics { },
            )
        }
        IglooIconButton(
            icon = IglooIcons.Play,
            semanticLabel = track.spokenInfo,
            onClick = onPlay,
            actionLabel = "Play",
            stateDescription = "Liked".takeIf { liked == true },
            modifier = Modifier
                .testTag("track_play_${track.id}")
                .trackColumn(
                    column = TrackRowColumn.Play,
                    focus = focus,
                    left = focus.left,
                    right = focus.requesters.like,
                    onColumnFocused = onColumnFocused,
                ),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .clearAndSetSemantics { },
        ) {
            IglooText(
                text = track.title,
                style = IglooTheme.typography.bodyMedium,
                color = colors.foreground,
                maxLines = 1,
            )
            if (track.subtitle != null) {
                IglooText(
                    text = track.subtitle,
                    style = IglooTheme.typography.label,
                    color = colors.mutedForeground,
                    maxLines = 1,
                )
            }
        }
        if (track.durationText.isNotEmpty()) {
            IglooText(
                text = track.durationText,
                style = IglooTheme.typography.label,
                color = colors.mutedForeground,
                maxLines = 1,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
        IglooIconButton(
            icon = if (liked == true) IglooIcons.HeartFilled else IglooIcons.Heart,
            semanticLabel = "Like",
            onClick = onToggleLike.takeIf { liked != null },
            // The pending treatment sits on the control that started it (section 10): the
            // glyph already shows the optimistic value, so only its weight changes.
            iconTint = when {
                likePending -> colors.mutedForeground
                liked == true -> colors.primary
                else -> null
            },
            stateDescription = when {
                liked == null -> "Like status unavailable"
                likePending -> if (liked) "Liked, saving" else "Not liked, saving"
                liked -> "Liked"
                else -> "Not liked"
            },
            actionLabel = if (liked == true) "Unlike ${track.title}" else "Like ${track.title}",
            modifier = Modifier
                .testTag("track_like_${track.id}")
                .trackColumn(
                    column = TrackRowColumn.Like,
                    focus = focus,
                    left = focus.requesters.play,
                    right = focus.requesters.more,
                    onColumnFocused = onColumnFocused,
                ),
        )
        IglooIconButton(
            icon = IglooIcons.MoreVertical,
            semanticLabel = if (onOpenMore != null) {
                "More actions for ${track.title}"
            } else {
                "More actions for ${track.title}. None available."
            },
            onClick = onOpenMore?.let { open -> { open(moreBounds) } },
            actionLabel = "More actions for ${track.title}",
            modifier = Modifier
                .testTag("track_more_${track.id}")
                .onGloballyPositioned { moreBounds = it.boundsInRoot() }
                .trackColumn(
                    column = TrackRowColumn.More,
                    focus = focus,
                    left = focus.requesters.like,
                    right = FocusRequester.Cancel,
                    onColumnFocused = onColumnFocused,
                ),
        )
    }
}

private fun Modifier.trackColumn(
    column: TrackRowColumn,
    focus: TrackRowFocus,
    left: FocusRequester?,
    right: FocusRequester,
    onColumnFocused: (TrackRowColumn) -> Unit,
): Modifier {
    var chain: Modifier = focusRequester(focus.requesters[column])
    focus.riders(column).forEach { rider -> chain = chain.focusRequester(rider) }
    return chain
        .focusProperties {
            this.right = right
            if (left != null) this.left = left
            focus.up(column)?.let { this.up = it }
            focus.down(column)?.let { this.down = it }
        }
        .onFocusChanged { if (it.isFocused) onColumnFocused(column) }
}

/**
 * A row-shaped loading stand-in of the real row's height and column layout (section 10), so
 * content arriving under focus moves nothing. The Play slot of one row can be the surface's
 * anchor — focusable, announcing [loadingLabel] politely — through [anchorModifier]; every
 * other skeleton row is texture, invisible to focus and TalkBack.
 */
@Composable
fun TrackRowSkeleton(
    modifier: Modifier = Modifier,
    showIndex: Boolean = false,
    anchorModifier: Modifier? = null,
    loadingLabel: String = "Loading tracks",
) {
    val colors = IglooTheme.colors
    val stubShape = RoundedCornerShape(IglooTheme.radius.sm)
    val controlShape = RoundedCornerShape(IglooTheme.radius.lg)
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (anchorModifier == null) Modifier.semantics { hideFromAccessibility() } else Modifier)
            .padding(horizontal = IglooTheme.spacing.sm, vertical = IglooTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showIndex) {
            Box(
                modifier = Modifier
                    .width(TRACK_INDEX_MIN_WIDTH.scaled())
                    .height(10.dp.scaled())
                    .background(colors.muted, stubShape),
            )
        }
        Box(
            modifier = Modifier
                .size(IglooTheme.sizes.controlHeight)
                .then(
                    if (anchorModifier != null) {
                        anchorModifier
                            .focusRing(focused = focused, radius = IglooTheme.radius.lg, fill = colors.muted)
                            .onFocusChanged { focused = it.isFocused }
                            .focusable()
                            .semantics {
                                contentDescription = loadingLabel
                                liveRegion = LiveRegionMode.Polite
                            }
                    } else {
                        Modifier.background(colors.muted, controlShape)
                    },
                ),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.xs),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.45f)
                    .height(14.dp.scaled())
                    .background(colors.muted, stubShape),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.3f)
                    .height(10.dp.scaled())
                    .background(colors.muted, stubShape),
            )
        }
        Box(
            modifier = Modifier
                .width(36.dp.scaled())
                .height(10.dp.scaled())
                .background(colors.muted, stubShape),
        )
        repeat(2) {
            Box(
                modifier = Modifier
                    .size(IglooTheme.sizes.controlHeight)
                    .background(colors.muted, controlShape),
            )
        }
    }
}

/**
 * The row's More menu — "Go to album", "Go to artist" — anchored to the More control's bounds
 * and hosted as the last child of the screen that owns the row (the [IglooMenu] contract). An
 * item first hands the destination to the host, then dismisses; a row offers More at all only
 * when [hasMoreActions] says one of the two can be opened from where it sits.
 */
@Composable
fun TrackRowMenu(
    track: TrackRowUi,
    anchorBounds: Rect,
    onGoToAlbum: ((Long) -> Unit)?,
    onGoToArtist: ((Long) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val items = buildList {
        val albumId = track.albumId
        if (albumId != null && onGoToAlbum != null) {
            add(IglooMenuItem("Go to album", onSelect = { onGoToAlbum(albumId); onDismiss() }))
        }
        val musicianId = track.musicianId
        if (musicianId != null && onGoToArtist != null) {
            add(IglooMenuItem("Go to artist", onSelect = { onGoToArtist(musicianId); onDismiss() }))
        }
    }
    IglooMenu(
        title = "More actions",
        items = items,
        anchorBounds = anchorBounds,
        onDismiss = onDismiss,
    )
}

/** Whether More has anywhere to go from this row, given what the host can open. */
fun TrackRowUi.hasMoreActions(canOpenAlbum: Boolean, canOpenArtist: Boolean): Boolean =
    (albumId != null && canOpenAlbum) || (musicianId != null && canOpenArtist)

/** Wide enough for a two-digit index without the titles ragged-lefting between rows. */
private val TRACK_INDEX_MIN_WIDTH = 28.dp
