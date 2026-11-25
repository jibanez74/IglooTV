package com.igloo.blindpenguincoder.feature.watchrooms

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.testTag
import com.igloo.blindpenguincoder.core.ui.IglooListDialog
import com.igloo.blindpenguincoder.core.ui.IglooPendingRow
import com.igloo.blindpenguincoder.core.ui.IglooRailState

/**
 * The owner's Edit invitees (docs/design-system.md section 11.10.1), over the room: everyone on the
 * server as a checklist, the room's invitees checked, then Cancel and Save invitees. The
 * [IglooListDialog] shell, as Watch Together's: Back and Cancel dismiss, focus is trapped, and the
 * room hands focus back to Edit invitees. Save is inert until a check changes; anyone unchecked
 * leaves the room at once.
 */
@Composable
fun EditInviteesDialog(title: String, editing: EditInviteesUi, actions: WatchRoomActions) {
    val people = (editing.people as? IglooRailState.Loaded)?.items.orEmpty()
    IglooListDialog(
        title = "Edit invitees",
        rowCount = people.size,
        entryIndex = 0,
        onDismiss = actions.onDismissEdit,
        testTag = "edit_invitees_dialog",
        header = {
            DialogProse(
                "Choose who can join the room for $title. Anyone you uncheck leaves the room " +
                    "right away.",
            )
            PeopleListStatus(editing.people)
        },
        buttons = { entry, up ->
            if (editing.saving) {
                IglooPendingRow(text = "Saving invitees…")
            } else {
                InviteeButtons(
                    entry = entry,
                    up = up,
                    onCancel = actions.onDismissEdit,
                    cancelTag = "edit_invitees_cancel",
                    people = editing.people,
                    onRetry = actions.onRetryInvitees,
                    actionText = "Save invitees",
                    onAction = actions.onSaveInvitees.takeIf { editing.changed },
                    actionState = if (editing.changed) null else "No changes",
                    actionTag = "edit_invitees_save",
                )
            }
        },
    ) { rowFocus ->
        InviteeRows(
            people = people,
            selected = editing.selected,
            onToggle = actions.onToggleInvitee,
            rowFocus = rowFocus,
            rowTag = { "edit_invitees_person_$it" },
        )
    }
}
