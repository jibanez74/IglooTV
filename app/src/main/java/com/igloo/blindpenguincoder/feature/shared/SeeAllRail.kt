package com.igloo.blindpenguincoder.feature.shared

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooPosterCard
import com.igloo.blindpenguincoder.core.ui.IglooRailState

/**
 * A rail's entry where the rail can end in a way into the whole list: one of its items, or the
 * See-all card that closes it — a Search rail with more matches than it shows, a Home rail of
 * recent additions.
 */
internal sealed interface RailEntry<out T> {
    /** The entry's rail key, which a rail's focus memory holds. */
    val key: Long

    data class Item<T>(val item: T, override val key: Long) : RailEntry<T>

    data object SeeAll : RailEntry<Nothing> {
        /** No item's id, so a rail's focus memory can hold the card like any other. */
        override val key = -1L
    }
}

/** [items] as entries, closed by the See-all card when [seeAll]. */
internal fun <T> railEntries(
    items: List<T>,
    itemKey: (T) -> Long,
    seeAll: Boolean,
): List<RailEntry<T>> = buildList {
    items.forEach { add(RailEntry.Item(it, itemKey(it))) }
    if (seeAll) add(RailEntry.SeeAll)
}

/**
 * A loaded rail's cards, closed by the See-all card when [seeAll]. An empty rail gets none —
 * there is nothing to see more of, and its empty state already says so — and a loading or failed
 * one passes through.
 */
internal fun <T> IglooRailState<T>.withSeeAll(itemKey: (T) -> Long, seeAll: Boolean): IglooRailState<RailEntry<T>> =
    when (this) {
        is IglooRailState.Loaded -> IglooRailState.Loaded(railEntries(items, itemKey, seeAll && items.isNotEmpty()))
        is IglooRailState.Loading -> this
        is IglooRailState.Error -> this
    }

/**
 * The card that closes a rail: "See all" over [subtitle], drawn as the rail's own card with
 * [icon] where the artwork would be, and one sentence, [label], for TalkBack and the press.
 */
@Composable
internal fun SeeAllCard(
    subtitle: String,
    label: String,
    icon: ImageVector,
    aspect: Float,
    onClick: () -> Unit,
    modifier: Modifier,
    artworkRadius: Dp = IglooTheme.radius.lg,
    centerText: Boolean = false,
) {
    IglooPosterCard(
        title = "See all",
        subtitle = subtitle,
        imageUrl = null,
        onClick = onClick,
        actionLabel = label,
        semanticLabel = label,
        aspect = aspect,
        fallbackIcon = icon,
        artworkRadius = artworkRadius,
        centerText = centerText,
        modifier = modifier,
    )
}
