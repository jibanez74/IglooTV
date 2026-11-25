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
import com.igloo.blindpenguincoder.core.ui.IglooAvatar
import com.igloo.blindpenguincoder.core.ui.IglooButton
import com.igloo.blindpenguincoder.core.ui.IglooButtonVariant
import com.igloo.blindpenguincoder.core.ui.IglooCheckRow
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
    val saveRequester = remember { FocusRequester() }
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
            when (val list = editing.people) {
                IglooRailState.Loading -> DialogProse("Loading people…")
                is IglooRailState.Error -> DialogProse("Couldn't load people: ${list.message}")
                is IglooRailState.Loaded -> if (list.items.isEmpty()) {
                    DialogProse("No one else has an account on this server yet.")
                }
            }
        },
        buttons = { entry, up ->
            if (editing.saving) {
                IglooPendingRow(text = "Saving invitees…")
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
                ) {
                    IglooButton(
                        text = "Cancel",
                        onClick = actions.onDismissEdit,
                        variant = IglooButtonVariant.Ghost,
                        modifier = Modifier
                            .focusRequester(entry)
                            .focusProperties {
                                this.up = up ?: Cancel
                                left = Cancel
                                down = Cancel
                                right = saveRequester
                            }
                            .testTag("edit_invitees_cancel"),
                    )
                    val failed = editing.people is IglooRailState.Error
                    IglooButton(
                        text = if (failed) "Retry" else "Save invitees",
                        semanticLabel = if (failed) "Retry loading people" else "Save invitees",
                        onClick = when {
                            failed -> actions.onRetryInvitees
                            !editing.changed -> null
                            else -> actions.onSaveInvitees
                        },
                        stateDescription = if (failed || editing.changed) null else "No changes",
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(saveRequester)
                            .focusProperties {
                                this.up = up ?: Cancel
                                left = entry
                                right = Cancel
                                down = Cancel
                            }
                            .testTag("edit_invitees_save"),
                    )
                }
            }
        },
    ) { rowFocus ->
        people.forEachIndexed { index, person ->
            val invited = person.id in editing.selected
            IglooCheckRow(
                label = person.name,
                checked = invited,
                onToggle = { actions.onToggleInvitee(person.id) },
                actionLabel = if (invited) "Don't invite" else "Invite",
                leading = {
                    IglooAvatar(
                        name = person.name,
                        avatarUrl = person.avatarUrl,
                        size = IglooTheme.icons.lg,
                        textStyle = IglooTheme.typography.label,
                    )
                },
                modifier = Modifier
                    .rowFocus(index)
                    .testTag("edit_invitees_person_${person.id}"),
            )
        }
    }
}
