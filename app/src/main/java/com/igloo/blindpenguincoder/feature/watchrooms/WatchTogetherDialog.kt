package com.igloo.blindpenguincoder.feature.watchrooms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusRequester.Companion.Cancel
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooAvatar
import com.igloo.blindpenguincoder.core.ui.IglooButton
import com.igloo.blindpenguincoder.core.ui.IglooButtonVariant
import com.igloo.blindpenguincoder.core.ui.IglooCheckRow
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
    val createRequester = remember { FocusRequester() }
    val pendingRequester = remember { FocusRequester() }
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
            when (val list = state.people) {
                IglooRailState.Loading -> DialogProse("Loading people…")
                is IglooRailState.Error -> DialogProse("Couldn't load people: ${list.message}")
                is IglooRailState.Loaded -> if (list.items.isEmpty()) {
                    DialogProse("No one else has an account on this server yet.")
                }
            }
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
                IglooPendingRow(text = state.pendingText, focusRequester = pendingRequester)
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
                ) {
                    IglooButton(
                        text = "Cancel",
                        onClick = actions.onDismiss,
                        variant = IglooButtonVariant.Ghost,
                        // Its own width: Create's label is the longer one and must not truncate.
                        modifier = Modifier
                            .focusRequester(entry)
                            .focusProperties {
                                this.up = up ?: Cancel
                                left = Cancel
                                down = Cancel
                                right = createRequester
                            }
                            .testTag("watch_together_cancel"),
                    )
                    val failed = state.people is IglooRailState.Error
                    IglooButton(
                        text = if (failed) "Retry" else "Create and join room",
                        semanticLabel = if (failed) "Retry loading people" else "Create and join room",
                        // Inert until there is anyone to invite; the hint covers nobody chosen.
                        onClick = when {
                            failed -> actions.onRetry
                            people.isEmpty() -> null
                            else -> actions.onCreate
                        },
                        stateDescription = state.presets?.settingsLine?.takeUnless { failed },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(createRequester)
                            .focusProperties {
                                this.up = up ?: Cancel
                                left = entry
                                right = Cancel
                                down = Cancel
                            }
                            .testTag("watch_together_create"),
                    )
                }
            }
        },
    ) { rowFocus ->
        people.forEachIndexed { index, person ->
            val invited = person.id in state.selected
            IglooCheckRow(
                label = person.name,
                checked = invited,
                onToggle = { actions.onToggle(person.id) },
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
                    .testTag("watch_together_invitee_${person.id}"),
            )
        }
    }
}

/** Prose the user reads to decide: cardForeground, as the confirm dialog's body (section 9.3). */
@Composable
private fun DialogProse(text: String) {
    IglooText(
        text = text,
        style = IglooTheme.typography.bodyMedium,
        color = IglooTheme.colors.cardForeground,
    )
}

private const val INVITE_HEADING = "Invite people"
