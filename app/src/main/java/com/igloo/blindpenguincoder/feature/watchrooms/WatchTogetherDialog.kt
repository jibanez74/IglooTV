package com.igloo.blindpenguincoder.feature.watchrooms

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooListDialog
import com.igloo.blindpenguincoder.core.ui.IglooPendingRow
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.core.ui.IglooText

/**
 * The Watch Together dialog (docs/design-system.md section 11.10.3), over a movie's page: what the
 * room will fix, then who to invite as a checklist, then Cancel and "Create and join room". The
 * [IglooListDialog] shell, so Back and Cancel dismiss, focus is trapped, and the host restores it
 * to the More trigger. The settings line is plain text a TV screen reader never reaches, so Create
 * carries it as its state.
 */
@Composable
fun WatchTogetherDialog(state: WatchTogetherUiState, actions: WatchTogetherActions) {
    val people = (state.people as? IglooRailState.Loaded)?.items.orEmpty()
    IglooListDialog(
        title = "Watch together",
        rowCount = people.size,
        entryIndex = 0,
        onDismiss = actions.onDismiss,
        testTag = "watch_together_dialog",
        header = {
            DialogProse(
                "Create a private room for ${state.title}. Everyone you invite gets these settings " +
                    "and can play, pause and seek for everyone.",
            )
            state.presets?.let { presets ->
                IglooText(
                    text = presets.settingsLine,
                    style = IglooTheme.typography.bodyMedium,
                    color = IglooTheme.colors.mutedForeground,
                    modifier = Modifier.testTag("watch_together_presets"),
                )
            }
            IglooText(
                text = INVITE_HEADING,
                style = IglooTheme.typography.label,
                color = IglooTheme.colors.mutedForeground,
                modifier = Modifier
                    .padding(top = IglooTheme.spacing.sm)
                    .semantics { heading() },
            )
            PeopleListStatus(state.people)
        },
        footer = {
            state.hint?.let { hint ->
                IglooText(
                    text = hint,
                    style = IglooTheme.typography.bodyMedium,
                    color = IglooTheme.colors.cardForeground,
                    modifier = Modifier
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .testTag("watch_together_hint"),
                )
            }
        },
        buttons = { entry, up ->
            if (state.creating) {
                IglooPendingRow(text = state.pendingText)
            } else {
                InviteeButtons(
                    entry = entry,
                    up = up,
                    onCancel = actions.onDismiss,
                    cancelTag = "watch_together_cancel",
                    people = state.people,
                    onRetry = actions.onRetry,
                    actionText = "Create and join room",
                    // Inert until there is anyone to invite; the hint covers nobody chosen.
                    onAction = actions.onCreate.takeUnless { people.isEmpty() },
                    actionState = state.presets?.settingsLine,
                    actionTag = "watch_together_create",
                )
            }
        },
    ) { rowFocus ->
        InviteeRows(
            people = people,
            selected = state.selected,
            onToggle = actions.onToggle,
            rowFocus = rowFocus,
            rowTag = { "watch_together_invitee_$it" },
        )
    }
}

private const val INVITE_HEADING = "Invite people"
