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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import com.igloo.blindpenguincoder.core.design.IglooTheme

/**
 * A scrimmed card of [IglooRadioRow]s over one flat, scrolling list, with a pinned Done: the
 * Playback Settings dialog and the player's track and chapter menus. The [IglooConfirmDialog]
 * recipe throughout (section 9.3) — in-tree, one alpha reveal, no exit animation, Back and Done
 * dismiss, focus trapped, and the invoker restores focus in [onDismiss].
 *
 * [rows] draws the rows in list order, giving row `n` the `rowFocus(n)` modifier: up and down
 * walk the list and continue onto Done, and every other direction cancels so focus search
 * terminates inside the card instead of falling through the scrim. Entry focus lands on row
 * [entryIndex]. [footer] sits between the list and Done.
 */
@Composable
internal fun IglooRadioListDialog(
    title: String,
    rowCount: Int,
    entryIndex: Int,
    onDismiss: () -> Unit,
    testTag: String,
    doneTestTag: String,
    paneTitle: String = title,
    footer: @Composable ColumnScope.() -> Unit = {},
    rows: @Composable ColumnScope.(rowFocus: Modifier.(index: Int) -> Modifier) -> Unit,
) {
    // The host gates its own Back handlers while this is composed (section 9.3).
    BackHandler(onBack = onDismiss)

    val rowRequesters = remember(rowCount) { List(rowCount) { FocusRequester() } }
    val doneRequester = remember { FocusRequester() }
    // Entry focus, and again when the remote takes over from touch: a card opened by touch
    // opens in touch mode, where its clickable rows cannot take focus, so the first key would
    // otherwise land wherever focus search from nothing goes.
    val inputMode = LocalInputModeManager.current.inputMode
    LaunchedEffect(inputMode) {
        if (inputMode == InputMode.Keyboard) rowRequesters.getOrNull(entryIndex)?.requestFocus()
    }

    val rowFocus: Modifier.(Int) -> Modifier = { index ->
        focusRequester(rowRequesters[index]).pinnedSideways(
            up = rowRequesters.getOrNull(index - 1),
            down = rowRequesters.getOrNull(index + 1) ?: doneRequester,
        )
    }

    IglooDialogCard(title = title, paneTitle = paneTitle, modifier = Modifier.testTag(testTag)) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.xs),
        ) {
            rows(rowFocus)
        }

        footer()

        IglooButton(
            text = "Done",
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(doneRequester)
                .pinnedSideways(up = rowRequesters.lastOrNull())
                .testTag(doneTestTag),
        )
    }
}
