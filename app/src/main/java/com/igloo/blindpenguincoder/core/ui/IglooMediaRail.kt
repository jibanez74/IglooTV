package com.igloo.blindpenguincoder.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.scaled

/** What a media rail renders. An empty [Loaded] list is the empty state, not an error. */
sealed interface IglooRailState<out T> {
    data object Loading : IglooRailState<Nothing>
    data class Loaded<T>(val items: List<T>) : IglooRailState<T>
    data class Error(val message: String) : IglooRailState<Nothing>
}

/**
 * A horizontal media rail (docs/design-system.md sections 8.3, 11.3): section header over a
 * LazyRow of cards, with grid-matched loading skeletons, an empty state, and an inline error.
 *
 * Every state keeps exactly one focus anchor alive, because the content pane's whole focus
 * model — the shell's initial focus, Back stepping outward, d-pad right from the spine —
 * assumes the pane always has somewhere to land. [entryRequester] (the shell's content-start
 * requester when this rail is the pane's first section) and [leftFocusRequester] (the spine
 * row to exit to) are pinned to that anchor in whichever state holds it.
 *
 * Focus is restored per rail: [lastFocusedKey] names the card focus re-enters on, both from
 * the spine and across a destination round-trip, where the list is recreated scrolled so the
 * remembered card is composed and can take focus. It is snapshot state on purpose — the
 * recomposition it triggers is what moves [entryRequester] onto the remembered card, so spine
 * re-entry lands there without the rail being rebuilt.
 *
 * [returnRequester] rides the same anchor in every state: an overlay opened from this rail
 * requests it on close, so Back lands on the card that led away (section 6.3) — or on the
 * rail's surviving anchor when the list changed underneath.
 */
@Composable
fun <T> IglooMediaRail(
    title: String,
    state: IglooRailState<T>,
    itemKey: (T) -> Long,
    entryRequester: FocusRequester?,
    leftFocusRequester: FocusRequester,
    lastFocusedKey: Long?,
    onItemFocused: (Long) -> Unit,
    modifier: Modifier = Modifier,
    // Only rails whose state can actually be Loading or Empty need to speak here; a rail that
    // is always Loaded (the cast rail) leaves the defaults, which never render.
    loadingLabel: String = "Loading",
    emptyIcon: ImageVector = IglooIcons.Movies,
    emptyText: String = "Nothing to show",
    onRetry: () -> Unit = {},
    returnRequester: FocusRequester? = null,
    cardAspect: Float = IglooTheme.layout.posterAspect,
    // The skeleton takes both geometry values, so it is shaped by the same tokens as the card
    // it replaces — the grid-matching rule (section 8.2) holds structurally instead of by
    // convention. [cardWidth] is `wideCardWidth` for 16:9 video rails.
    cardWidth: Dp = IglooTheme.layout.posterWidth,
    itemContent: @Composable (item: T, itemModifier: Modifier, cardAspect: Float) -> Unit,
) {
    val localAnchor = remember { FocusRequester() }
    val items = (state as? IglooRailState.Loaded)?.items
    val itemRequesters = remember(items) {
        items.orEmpty().associate { itemKey(it) to FocusRequester() }
    }
    val entryKey = when {
        items.isNullOrEmpty() -> null
        lastFocusedKey != null && itemRequesters.containsKey(lastFocusedKey) -> lastFocusedKey
        else -> itemKey(items.first())
    }

    var railHasFocus by remember { mutableStateOf(false) }
    // Captured during the composition that swaps states — the outgoing state's focused node
    // only detaches (clearing railHasFocus) once that composition applies, so this still sees
    // whether the rail owned focus going in. The effect then lands focus on the new state's
    // anchor, which exists by the time effects run. Without this, a focused skeleton disposing
    // would drop focus on the floor.
    val hadFocusAtSwap = remember(state) { railHasFocus }
    LaunchedEffect(state) {
        if (hadFocusAtSwap) {
            (entryKey?.let(itemRequesters::getValue) ?: localAnchor).requestFocus()
        }
    }

    val anchorModifier = Modifier
        .focusRequester(localAnchor)
        .withRequester(entryRequester)
        .withRequester(returnRequester)
        .focusProperties {
            left = leftFocusRequester
            right = FocusRequester.Cancel
        }

    Column(
        modifier = modifier.onFocusChanged { railHasFocus = it.hasFocus },
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
    ) {
        IglooText(
            text = title,
            style = IglooTheme.typography.titleMedium,
            color = IglooTheme.colors.foreground,
            modifier = Modifier.semantics { heading() },
        )

        when (state) {
            is IglooRailState.Loading -> RailSkeleton(
                anchorModifier = anchorModifier,
                loadingLabel = loadingLabel,
                cardAspect = cardAspect,
                cardWidth = cardWidth,
            )

            // Rail-shaped, not form-shaped: the card is bounded to the cards it replaces so a
            // failure reads as this rail's, and Polite because rails fail independently and two
            // Assertive regions would cut each other off (section 12).
            is IglooRailState.Error -> IglooInlineError(
                message = state.message,
                actionText = "Retry",
                actionSemanticLabel = "Retry loading $title",
                onAction = onRetry,
                actionModifier = anchorModifier,
                liveRegionMode = LiveRegionMode.Polite,
                modifier = Modifier
                    .padding(vertical = IglooTheme.spacing.md)
                    .widthIn(max = railErrorWidth()),
            )

            is IglooRailState.Loaded -> if (state.items.isEmpty()) {
                RailEmpty(
                    anchorModifier = anchorModifier,
                    emptyIcon = emptyIcon,
                    emptyText = emptyText,
                )
            } else {
                // Created scrolled to the entry card so a rail rebuilt on returning to this
                // destination has the remembered card composed — a focus requester can only
                // land on a node that exists.
                val listState = rememberLazyListState(
                    initialFirstVisibleItemIndex = state.items
                        .indexOfFirst { itemKey(it) == entryKey }
                        .coerceAtLeast(0),
                )
                LazyRow(
                    state = listState,
                    horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
                    // Vertical room inside the scroll surface, so the focus scale and glow are
                    // not cross-axis-clipped; the row's horizontal extremes still trim the glow,
                    // the same accepted artifact section 6.1 records for the spine.
                    contentPadding = PaddingValues(vertical = IglooTheme.spacing.md),
                ) {
                    itemsIndexed(state.items, key = { _, item -> itemKey(item) }) { index, item ->
                        val key = itemKey(item)
                        itemContent(
                            item,
                            Modifier
                                .focusRequester(itemRequesters.getValue(key))
                                .withRequester(entryRequester.takeIf { key == entryKey })
                                .withRequester(returnRequester.takeIf { key == entryKey })
                                .focusProperties {
                                    if (index == 0) left = leftFocusRequester
                                    if (index == state.items.lastIndex) right = FocusRequester.Cancel
                                }
                                .onFocusChanged { if (it.isFocused) onItemFocused(key) },
                            cardAspect,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Card-geometry placeholders, so focus taken while loading sits exactly where the first card
 * will land — which is why [cardAspect] must match the rail's real card (section 10). Only the
 * first cell is real to focus and TalkBack; the rest are texture. Static on purpose — nothing
 * in the product loops (design-system.md section 7.2).
 */
@Composable
private fun RailSkeleton(
    anchorModifier: Modifier,
    loadingLabel: String,
    cardAspect: Float,
    cardWidth: Dp,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.padding(vertical = IglooTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
    ) {
        SkeletonCell(
            focused = focused,
            cardAspect = cardAspect,
            cardWidth = cardWidth,
            modifier = anchorModifier
                .onFocusChanged { focused = it.isFocused }
                .focusable()
                .semantics {
                    contentDescription = loadingLabel
                    liveRegion = LiveRegionMode.Polite
                },
        )
        repeat(SKELETON_CELLS - 1) {
            SkeletonCell(
                focused = false,
                cardAspect = cardAspect,
                cardWidth = cardWidth,
                modifier = Modifier.semantics { hideFromAccessibility() },
            )
        }
    }
}

@Composable
private fun SkeletonCell(
    focused: Boolean,
    cardAspect: Float,
    cardWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = IglooTheme.colors
    val stubShape = RoundedCornerShape(IglooTheme.radius.sm)
    Column(
        modifier = modifier.width(cardWidth),
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(cardAspect)
                .focusRing(
                    focused = focused,
                    radius = IglooTheme.radius.lg,
                    fill = colors.muted,
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .height(14.dp.scaled())
                .background(colors.muted, stubShape),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .height(10.dp.scaled())
                .background(colors.muted, stubShape),
        )
    }
}

@Composable
private fun RailEmpty(
    anchorModifier: Modifier,
    emptyIcon: ImageVector,
    emptyText: String,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        // Focusable deliberately: when the rail is the pane's only section, an unfocusable
        // empty state would leave the pane with no anchor and break the shell's focus model.
        modifier = Modifier
            .focusRing(focused = focused, radius = IglooTheme.radius.lg)
            .then(anchorModifier)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clearAndSetSemantics {
                contentDescription = emptyText
                liveRegion = LiveRegionMode.Polite
            }
            .padding(IglooTheme.spacing.xl),
    ) {
        IglooEmpty(icon = emptyIcon, message = emptyText)
    }
}

/** Three cards and their gaps: wide enough for a sentence, still visibly one rail's worth. */
@Composable
private fun railErrorWidth(): Dp =
    IglooTheme.layout.posterWidth * 3 + IglooTheme.spacing.md * 2

private const val SKELETON_CELLS = 6
