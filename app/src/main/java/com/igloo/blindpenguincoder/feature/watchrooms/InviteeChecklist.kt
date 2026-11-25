package com.igloo.blindpenguincoder.feature.watchrooms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusRequester.Companion.Cancel
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooButton
import com.igloo.blindpenguincoder.core.ui.IglooButtonVariant
import com.igloo.blindpenguincoder.core.ui.IglooCheckRow
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.core.ui.IglooText

// The pieces Watch Together and Edit invitees share: both are an IglooListDialog over a checklist
// of everyone the room can invite, with Cancel and one action under it (section 11.10).

/**
 * Prose the user reads to decide: cardForeground, as the confirm dialog's body (section 9.3).
 */
@Composable
internal fun DialogProse(text: String) {
    IglooText(
        text = text,
        style = IglooTheme.typography.bodyMedium,
        color = IglooTheme.colors.cardForeground,
    )
}

/** What the checklist holds while it is not a list of people: loading, failed, or nobody. */
@Composable
internal fun PeopleListStatus(people: IglooRailState<RoomMemberUi>) {
    when (people) {
        IglooRailState.Loading -> DialogProse("Loading people…")
        is IglooRailState.Error -> DialogProse("Couldn't load people: ${people.message}")
        is IglooRailState.Loaded -> if (people.items.isEmpty()) {
            DialogProse("No one else has an account on this server yet.")
        }
    }
}

/** One check row per person, each tagged [rowTag] of its id. */
@Composable
internal fun InviteeRows(
    people: List<RoomMemberUi>,
    selected: Set<Long>,
    onToggle: (userId: Long) -> Unit,
    rowFocus: Modifier.(index: Int) -> Modifier,
    rowTag: (userId: Long) -> String,
) {
    people.forEachIndexed { index, person ->
        val invited = person.id in selected
        IglooCheckRow(
            label = person.name,
            checked = invited,
            onToggle = { onToggle(person.id) },
            actionLabel = if (invited) "Don't invite" else "Invite",
            leading = { RoomMemberAvatar(person) },
            modifier = Modifier
                .rowFocus(index)
                .testTag(rowTag(person.id)),
        )
    }
}

/**
 * Cancel, then the dialog's own action — which is Retry while the people failed to load. Cancel
 * takes its own width: the action's label is the longer one and must not truncate. [entry] and
 * [up] are the list dialog's.
 */
@Composable
internal fun InviteeButtons(
    entry: FocusRequester,
    up: FocusRequester?,
    onCancel: () -> Unit,
    cancelTag: String,
    people: IglooRailState<RoomMemberUi>,
    onRetry: () -> Unit,
    actionText: String,
    /** Null leaves the action inert. */
    onAction: (() -> Unit)?,
    actionState: String?,
    actionTag: String,
) {
    val actionRequester = remember { FocusRequester() }
    val failed = people is IglooRailState.Error
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
    ) {
        IglooButton(
            text = "Cancel",
            onClick = onCancel,
            variant = IglooButtonVariant.Ghost,
            modifier = Modifier
                .focusRequester(entry)
                .focusProperties {
                    this.up = up ?: Cancel
                    left = Cancel
                    down = Cancel
                    right = actionRequester
                }
                .testTag(cancelTag),
        )
        IglooButton(
            text = if (failed) "Retry" else actionText,
            semanticLabel = if (failed) "Retry loading people" else actionText,
            onClick = if (failed) onRetry else onAction,
            stateDescription = actionState.takeUnless { failed },
            modifier = Modifier
                .weight(1f)
                .focusRequester(actionRequester)
                .focusProperties {
                    this.up = up ?: Cancel
                    left = entry
                    right = Cancel
                    down = Cancel
                }
                .testTag(actionTag),
        )
    }
}
