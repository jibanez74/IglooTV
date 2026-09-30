package com.igloo.blindpenguincoder.feature.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusRequester.Companion.Cancel
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooButton
import com.igloo.blindpenguincoder.core.ui.IglooButtonVariant
import com.igloo.blindpenguincoder.core.ui.IglooIcons
import com.igloo.blindpenguincoder.core.ui.IglooNotice
import com.igloo.blindpenguincoder.core.ui.IglooPinnedError
import com.igloo.blindpenguincoder.core.ui.iglooEnterStagger
import com.igloo.blindpenguincoder.core.ui.rememberRefocusAfterSwap
import com.igloo.blindpenguincoder.core.ui.requestFocusSafely
import com.igloo.blindpenguincoder.core.ui.withRequester
import com.igloo.blindpenguincoder.feature.shared.DetailChip
import com.igloo.blindpenguincoder.feature.shared.TrackRowMenu
import com.igloo.blindpenguincoder.feature.shared.TrackRowRequesters
import com.igloo.blindpenguincoder.feature.shared.TrackRowUi

/**
 * The pieces the album and musician detail overlays share (docs/design-system.md sections
 * 11.5.1 and 11.5.2): both are full-screen in-tree overlays above the shell, opaque on the
 * `background` token, with a full-bleed backdrop, a hero whose prose is one reading stop under
 * a screen reader, a Play/Shuffle action row, three-action track rows, a facts panel, and a
 * row-anchored More menu. Back and focus restore belong to the host; each screen only anchors
 * entry focus and wires its own vertical chain.
 */

/**
 * The overlay's frame: the entry anchor, the swap-capture pattern (section 11.4.1) that
 * re-lands focus on a state change only when the screen owned it, the three states, and the
 * one More menu hosted as the last child. [loaded] is the page's model once it has one and
 * [errorMessage] the full-screen error's text; with neither the [skeleton] shows. [trackRows]
 * are every row the page draws, in the order [content] composes them, so a menu opened by
 * index finds its track and returns focus to its More control on dismissal.
 */
@Composable
internal fun <T : Any> MusicDetailsScaffold(
    stateKey: Any,
    loaded: T?,
    errorMessage: String?,
    paneTitle: String,
    tag: String,
    trackRows: List<TrackRowUi>,
    retrySemanticLabel: String,
    onRetry: () -> Unit,
    onGoToAlbum: ((Long) -> Unit)?,
    onGoToArtist: ((Long) -> Unit)?,
    modifier: Modifier = Modifier,
    skeleton: @Composable (anchorRequester: FocusRequester) -> Unit,
    content: @Composable (
        loaded: T,
        entryRequester: FocusRequester,
        trackRequesters: List<TrackRowRequesters>,
        onOpenMore: (Int, Rect) -> Unit,
    ) -> Unit,
) {
    val colors = IglooTheme.colors
    val entryRequester = remember { FocusRequester() }

    // The hero's primary action is the first focused element on entry; while loading, the
    // skeleton's action-slot stub holds the anchor so focus already sits where the real button
    // will land. Requested safely because a trackless page anchors elsewhere.
    LaunchedEffect(Unit) { entryRequester.requestFocusSafely() }

    // Keyed on the state's class — a Loaded republish must not yank focus back.
    val onScreenFocus = rememberRefocusAfterSwap(stateKey) { entryRequester.requestFocusSafely() }
    // The row whose More menu is open, and the requesters its dismissal returns focus through.
    var trackMenu by remember(stateKey) { mutableStateOf<Pair<Int, Rect>?>(null) }
    val trackRequesters = remember(trackRows.size) { List(trackRows.size) { TrackRowRequesters() } }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .onFocusChanged { onScreenFocus(it.hasFocus) }
            .semantics {
                // The loaded pane announces the record itself; a pane-title change is spoken, so
                // the load completing names it rather than a generic frame (section 12).
                this.paneTitle = paneTitle
                isTraversalGroup = true
            }
            .testTag(tag),
    ) {
        // The menu is a small anchored card that occludes nothing, so the body leaves the
        // semantics tree while it is up (the movie details rule); the tag stays outside.
        Box(
            modifier = Modifier
                .testTag("${tag}_body")
                .then(if (trackMenu != null) Modifier.clearAndSetSemantics { } else Modifier),
        ) {
            when {
                loaded != null -> content(
                    loaded,
                    entryRequester,
                    trackRequesters,
                ) { index, bounds -> trackMenu = index to bounds }

                errorMessage != null -> IglooPinnedError(
                    message = errorMessage,
                    actionText = "Retry",
                    actionSemanticLabel = retrySemanticLabel,
                    actionRequester = entryRequester,
                    onAction = onRetry,
                )

                else -> skeleton(entryRequester)
            }
        }

        // Last child, over the body. Dismissal restores focus to the More that opened it, in
        // the callback rather than an effect (section 9.3); an item that opened the other
        // overlay replaces this one, so its restore finds nothing and safely no-ops.
        trackMenu?.let { (index, bounds) ->
            trackRows.getOrNull(index)?.let { track ->
                TrackRowMenu(
                    track = track,
                    anchorBounds = bounds,
                    onGoToAlbum = onGoToAlbum,
                    onGoToArtist = onGoToArtist,
                    onDismiss = {
                        trackMenu = null
                        trackRequesters.getOrNull(index)?.more?.requestFocusSafely()
                    },
                )
            }
        }
    }
}

/**
 * The loaded page's frame: a scrolling column of the hero, an optional notice, and the
 * sections, which rise once on entry (section 7.2). The header is deliberately not staggered:
 * it holds the entry focus, and a rise would move the focused button's bounds.
 */
@Composable
internal fun MusicDetailsBody(
    notice: String?,
    noticeTag: String,
    hero: @Composable () -> Unit,
    sections: @Composable ColumnScope.() -> Unit,
) {
    val layout = IglooTheme.layout
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        hero()
        if (notice != null) {
            IglooNotice(
                text = notice,
                modifier = Modifier
                    .padding(horizontal = layout.safeAreaHorizontal)
                    .padding(bottom = IglooTheme.spacing.lg)
                    .testTag(noticeTag),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = layout.safeAreaVertical)
                .iglooEnterStagger(entered = entered, index = 0),
            verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.lg),
            content = sections,
        )
    }
}

/**
 * Metadata chips — visually a row, but one TalkBack stop: consecutive chip announcements would
 * be noise, and the hero reading stop already carries the full sentence.
 */
@Composable
internal fun MusicMetadataChips(parts: List<String>, overMedia: Boolean) {
    Row(
        modifier = Modifier.clearAndSetSemantics { contentDescription = parts.joinToString(", ") },
        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        parts.forEach { DetailChip(text = it, overMedia = overMedia) }
    }
}

/**
 * The hero's primary action and Shuffle. Every direction out of the row is pinned: the shell
 * is still composed under the overlay, so an unpinned edge lets a spatial search land on a
 * card the user cannot see. The primary steps back while Shuffle holds focus, so the focused
 * control is the strongest thing in the row (the movie action row's recess rule). Each button
 * also carries the player's return requester while it is the control that launched it.
 */
@Composable
internal fun MusicHeroActionRow(
    primaryText: String,
    primarySemanticLabel: String,
    primaryTag: String,
    shuffleSemanticLabel: String,
    shuffleTag: String,
    overMedia: Boolean,
    primaryRequester: FocusRequester,
    primaryReturnRequester: FocusRequester?,
    shuffleRequester: FocusRequester,
    shuffleReturnRequester: FocusRequester?,
    upRequester: FocusRequester,
    downRequester: FocusRequester,
    onActionFocused: (FocusRequester) -> Unit,
    onPrimary: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rowFocus = Modifier.focusProperties {
        up = upRequester
        down = downRequester
    }
    var rowHasFocus by remember { mutableStateOf(false) }
    var primaryFocused by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.onFocusChanged { rowHasFocus = it.hasFocus },
        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
    ) {
        IglooButton(
            text = primaryText,
            onClick = onPrimary,
            icon = IglooIcons.Play,
            semanticLabel = primarySemanticLabel,
            recessed = rowHasFocus && !primaryFocused,
            modifier = Modifier
                .testTag(primaryTag)
                .focusRequester(primaryRequester)
                .withRequester(primaryReturnRequester)
                .then(rowFocus)
                .focusProperties {
                    left = Cancel
                    right = shuffleRequester
                }
                .onFocusChanged {
                    primaryFocused = it.isFocused
                    if (it.isFocused) onActionFocused(primaryRequester)
                },
        )
        IglooButton(
            text = "Shuffle",
            onClick = onShuffle,
            variant = IglooButtonVariant.Ghost,
            icon = IglooIcons.Shuffle,
            overMedia = overMedia,
            semanticLabel = shuffleSemanticLabel,
            modifier = Modifier
                .testTag(shuffleTag)
                .focusRequester(shuffleRequester)
                .withRequester(shuffleReturnRequester)
                .then(rowFocus)
                .focusProperties { right = Cancel }
                .onFocusChanged { if (it.isFocused) onActionFocused(shuffleRequester) },
        )
    }
}

/**
 * Which control launched the music player, so its close lands back on that control: the
 * hero's primary action, Shuffle, or one row's Play. Saved, because the player itself survives
 * recreation and its close afterwards still has to find the launching node.
 */
internal sealed interface PlayLaunchSite {
    data object Primary : PlayLaunchSite
    data object Shuffle : PlayLaunchSite
    data class Row(val index: Int) : PlayLaunchSite

    companion object {
        val Saver: Saver<PlayLaunchSite, String> = Saver(
            save = { site ->
                when (site) {
                    Primary -> "play"
                    Shuffle -> "shuffle"
                    is Row -> "row:${site.index}"
                }
            },
            restore = { saved ->
                when {
                    saved == "shuffle" -> Shuffle
                    saved.startsWith("row:") -> saved.removePrefix("row:").toIntOrNull()?.let(::Row) ?: Primary
                    else -> Primary
                }
            },
        )
    }
}

@Composable
internal fun rememberPlayLaunchSite(): MutableState<PlayLaunchSite> =
    rememberSaveable(stateSaver = PlayLaunchSite.Saver) { mutableStateOf(PlayLaunchSite.Primary) }

/** The row a [PlayLaunchSite.Row] names, when the page still has that row. */
internal fun PlayLaunchSite.rowIn(rows: List<*>): Int? =
    (this as? PlayLaunchSite.Row)?.index?.takeIf { it in rows.indices }

/** The hero's Shuffle button's approximate footprint, for the loading skeleton. */
internal val SHUFFLE_STUB_WIDTH = 128.dp
