package com.igloo.blindpenguincoder.feature.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooButton
import com.igloo.blindpenguincoder.core.ui.IglooButtonVariant
import com.igloo.blindpenguincoder.core.ui.IglooFocusableEmpty
import com.igloo.blindpenguincoder.core.ui.IglooInlineError
import com.igloo.blindpenguincoder.core.ui.IglooNotice
import com.igloo.blindpenguincoder.core.ui.IglooTab
import com.igloo.blindpenguincoder.core.ui.IglooTabRow
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.formatCount
import com.igloo.blindpenguincoder.core.ui.withRequester

/** What a pane's tab strip draws, speaks and is addressed by for one section — one lookup, not three. */
data class TabPresentation(
    val label: String,
    val semanticLabel: String,
    /** Doubles as the focus-ownership key ([PaneFocusOwnership]) and the test tag. */
    val key: String,
)

/**
 * The pane's horizontal gutter belongs inside the scroll surface (docs/design-system.md section
 * 8.3), but its vertical values do not: the surface needs room of its own so the focus scale
 * and glow are not cross-axis clipped at the first row, and the safe area below so the last
 * row clears overscan.
 */
@Composable
fun PaddingValues.asScrollPadding(): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(direction),
        end = calculateEndPadding(direction),
        top = IglooTheme.spacing.md,
        bottom = IglooTheme.layout.safeAreaVertical,
    )
}

const val REFRESH_LABEL = "Refresh"
const val REFRESHING_LABEL = "Refreshing…"

/** How close to the end a grid gets before it asks for the next page, in rows. */
const val GRID_PREFETCH_ROWS = 2

/** Rows of card geometry a grid skeleton draws. */
const val SKELETON_ROWS = 3

/**
 * A pane's header: the title over its count line and any notice, with the pane's [actions] on
 * the right. The count line is a polite live region that speaks counts only, never titles — it
 * re-announces whenever the loaded count changes, and a TalkBack user must not have the whole
 * list read back at them on every appended page (section 12). The notice is a refresh that
 * failed with content still on screen: the failure is over and Refresh is one press away, so it
 * reports rather than offering a second, redundant Retry.
 */
@Composable
internal fun PaneHeader(
    title: String,
    countText: String,
    countDescription: String,
    countTag: String,
    notice: String?,
    contentInset: PaddingValues,
    noticeTag: String? = null,
    actions: @Composable RowScope.() -> Unit,
) {
    val colors = IglooTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(contentInset)
            .padding(top = IglooTheme.layout.safeAreaVertical),
        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.xs),
        ) {
            IglooText(
                text = title,
                style = IglooTheme.typography.titleLarge,
                color = colors.foreground,
                modifier = Modifier.semantics { heading() },
            )
            IglooText(
                text = countText,
                style = IglooTheme.typography.bodyMedium,
                color = colors.mutedForeground,
                modifier = Modifier
                    .testTag(countTag)
                    .semantics {
                        contentDescription = countDescription
                        liveRegion = LiveRegionMode.Polite
                    },
            )
            if (notice != null) {
                IglooNotice(
                    text = notice,
                    modifier = if (noticeTag != null) Modifier.testTag(noticeTag) else Modifier,
                )
            }
        }
        actions()
    }
}

/**
 * The header's Refresh. A label swap, not a spinner: nothing in the product loops (section 7.2),
 * and the reserved label variants keep the button from resizing under its own focus ring the
 * moment it is pressed. Deliberately never disabled: IglooButton is focusable only through its
 * clickable branch, so disabling it while refreshing would remove the very node the user is
 * focused on from the focus tree; the view model guards the repeat press instead.
 */
@Composable
internal fun PaneRefreshButton(
    refreshing: Boolean,
    semanticLabel: String,
    onRefresh: () -> Unit,
    modifier: Modifier,
) {
    IglooButton(
        text = if (refreshing) REFRESHING_LABEL else REFRESH_LABEL,
        labelVariants = listOf(REFRESH_LABEL, REFRESHING_LABEL),
        onClick = onRefresh,
        variant = IglooButtonVariant.Ghost,
        enabled = true,
        semanticLabel = semanticLabel,
        stateDescription = "Refreshing".takeIf { refreshing },
        modifier = modifier,
    )
}

/**
 * The pane's sections as an [IglooTabRow]. The selected tab carries [tabRowRequester] — the
 * content, any picker and the header all wire their edges to it — and it always attaches,
 * because the strip is fixed. Tabs select on focus; see [IglooTab]. The header and the rows
 * below are siblings of the strip, so both vertical edges are wired rather than resolved
 * spatially.
 */
@Composable
internal fun <T> PaneTabRow(
    tabs: List<T>,
    selected: T,
    presentation: (T) -> TabPresentation,
    testTag: String,
    contentInset: PaddingValues,
    tabRowRequester: FocusRequester,
    navigationRequester: FocusRequester,
    refreshRequester: FocusRequester,
    downRequester: FocusRequester,
    onSelectTab: (T) -> Unit,
    onPressTab: (T) -> Unit,
    onFocusChanged: (String, Boolean) -> Unit,
) {
    val direction = LocalLayoutDirection.current
    IglooTabRow(
        modifier = Modifier
            .padding(
                start = contentInset.calculateStartPadding(direction),
                end = contentInset.calculateEndPadding(direction),
            )
            .testTag(testTag),
    ) {
        tabs.forEachIndexed { index, tab ->
            val spec = presentation(tab)
            IglooTab(
                text = spec.label,
                selected = tab == selected,
                onSelect = { onSelectTab(tab) },
                onPress = { onPressTab(tab) },
                semanticLabel = spec.semanticLabel,
                actionLabel = "Show ${spec.semanticLabel.lowercase()}",
                modifier = Modifier
                    .withRequester(tabRowRequester.takeIf { tab == selected })
                    .onFocusChanged { onFocusChanged(spec.key, it.isFocused) }
                    .focusProperties {
                        up = refreshRequester
                        down = downRequester
                        if (index == 0) left = navigationRequester
                        if (index == tabs.lastIndex) right = FocusRequester.Cancel
                    }
                    .testTag(spec.key),
            )
        }
    }
}

/**
 * The one anchor a pane's card-less states (skeleton, error, empty) draw. It carries every
 * requester the pane hands out: the details overlay can outlive the card that opened it (a
 * reconcile can empty the list underneath), and a detached return requester does not fail its
 * focus request — it silently no-ops, the host's fallback never runs, and the overlay's disposal
 * hands focus to the platform fallback in the navigation rail. A live anchor node is the fix.
 */
internal fun Modifier.paneCardlessAnchor(
    contentStartRequester: FocusRequester,
    returnRequester: FocusRequester?,
    cardlessHandoffRequester: FocusRequester,
    navigationRequester: FocusRequester,
    upRequester: FocusRequester,
    focusOwnership: PaneFocusOwnership,
): Modifier = this
    .withRequester(contentStartRequester)
    .withRequester(returnRequester)
    .withRequester(cardlessHandoffRequester)
    .focusProperties {
        left = navigationRequester
        up = upRequester
        right = FocusRequester.Cancel
    }
    .onFocusChanged { focusOwnership.onCardlessFocusChanged(it.isFocused) }

/** A first page that failed: the error card, its Retry the pane's anchor. */
@Composable
internal fun PaneFirstPageError(
    message: String,
    retryLabel: String,
    onRetry: () -> Unit,
    anchorModifier: Modifier,
    contentInset: PaddingValues,
) {
    IglooInlineError(
        message = message,
        actionText = "Retry",
        actionSemanticLabel = retryLabel,
        onAction = onRetry,
        actionModifier = anchorModifier,
        modifier = Modifier.padding(contentInset),
    )
}

/** An empty list: the focusable empty box, the pane's anchor. */
@Composable
internal fun PaneEmpty(
    icon: ImageVector,
    message: String,
    anchorModifier: Modifier,
    contentInset: PaddingValues,
) {
    IglooFocusableEmpty(
        anchorModifier = anchorModifier,
        icon = icon,
        message = message,
        contentPadding = IglooTheme.spacing.lg,
        modifier = Modifier
            .fillMaxWidth()
            .padding(contentInset),
        contentAlignment = Alignment.Center,
    )
}

/** The header's visible count: `"1,234 movies"`, or a dash until the count is known. */
internal fun paneCountLine(total: Long?, noun: (Long) -> String): String =
    if (total == null) "—" else "${formatCount(total)} ${noun(total)}"

/**
 * The spoken count once pages are on screen, `"Showing 48 of 1,234 movies"`, which also says
 * `"Loading more movies"` while the next page is out.
 */
internal fun showingCountLine(
    loadedCount: Int,
    total: Long,
    noun: String,
    pluralNoun: String,
    append: AppendState,
): String = buildString {
    append("Showing $loadedCount of ${formatCount(total)} $noun")
    if (append == AppendState.Loading) append(". Loading more $pluralNoun.")
}
