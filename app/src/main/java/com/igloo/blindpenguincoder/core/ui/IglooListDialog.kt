package com.igloo.blindpenguincoder.core.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import com.igloo.blindpenguincoder.core.design.IglooTheme

/**
 * A scrimmed card over one flat, scrolling list of rows, with actions pinned under it: the
 * Playback Settings dialog, the player's track and chapter menus — radio lists with a Done — and
 * the Watch Together dialog's invite checklist with its own buttons. The [IglooConfirmDialog]
 * recipe throughout (section 9.3) — in-tree, one alpha reveal, no exit animation, Back dismisses,
 * focus trapped, and the invoker restores focus in [onDismiss].
 *
 * [rows] draws the rows in list order, giving row `n` the `rowFocus(n)` modifier: up and down
 * walk the list and continue onto the first action, and every other direction cancels so focus
 * search terminates inside the card instead of falling through the scrim. Entry focus lands on
 * row [entryIndex], or on the first action while there are no rows, and moves to the row once a
 * list that arrives late lands. [header] sits above the list, [footer] between it and [buttons],
 * which wires its first action to `entry` (Down from the last row lands there) and its Up to `up`,
 * the last row or null with none. Done, by default.
 */
@Composable
internal fun IglooListDialog(
    title: String,
    rowCount: Int,
    entryIndex: Int,
    onDismiss: () -> Unit,
    testTag: String,
    paneTitle: String = title,
    doneTestTag: String = "",
    header: @Composable ColumnScope.() -> Unit = {},
    footer: @Composable ColumnScope.() -> Unit = {},
    buttons: @Composable ColumnScope.(entry: FocusRequester, up: FocusRequester?) -> Unit = { entry, up ->
        IglooButton(
            text = "Done",
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(entry)
                .pinnedSideways(up = up)
                .testTag(doneTestTag),
        )
    },
    rows: @Composable ColumnScope.(rowFocus: Modifier.(index: Int) -> Modifier) -> Unit,
) {
    // The host gates its own Back handlers while this is composed (section 9.3).
    BackHandler(onBack = onDismiss)

    val rowRequesters = remember(rowCount) { List(rowCount) { FocusRequester() } }
    val actionRequester = remember { FocusRequester() }
    // Entry focus, and again when the remote takes over from touch: a card opened by touch
    // opens in touch mode, where its clickable rows cannot take focus, so the first key would
    // otherwise land wherever focus search from nothing goes.
    // Rows arriving late (a list still loading on entry) take it from the action, but rows going
    // away take nothing: the player's track menu empties as its engine is replaced, in the frame
    // that closes it and hands focus back to the transport.
    val inputMode = LocalInputModeManager.current.inputMode
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(inputMode, rowCount > 0) {
        if (inputMode != InputMode.Keyboard) return@LaunchedEffect
        val row = rowRequesters.getOrNull(entryIndex)
        when {
            row != null -> row.requestFocusSafely()
            !entered -> actionRequester.requestFocusSafely()
        }
        entered = true
    }

    val rowFocus: Modifier.(Int) -> Modifier = { index ->
        focusRequester(rowRequesters[index]).pinnedSideways(
            up = rowRequesters.getOrNull(index - 1),
            down = rowRequesters.getOrNull(index + 1) ?: actionRequester,
        )
    }

    IglooDialogCard(title = title, paneTitle = paneTitle, modifier = Modifier.testTag(testTag)) {
        header()

        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.xs),
        ) {
            rows(rowFocus)
        }

        footer()

        buttons(actionRequester, rowRequesters.lastOrNull())
    }
}
