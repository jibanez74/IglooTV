package com.igloo.blindpenguincoder.feature.watchrooms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusRequester.Companion.Cancel
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.OVER_MEDIA_SECONDARY
import com.igloo.blindpenguincoder.core.design.overMedia
import com.igloo.blindpenguincoder.core.design.scaled
import com.igloo.blindpenguincoder.core.ui.IglooAvatar
import com.igloo.blindpenguincoder.core.ui.IglooButton
import com.igloo.blindpenguincoder.core.ui.IglooButtonVariant
import com.igloo.blindpenguincoder.core.ui.IglooConfirmDialog
import com.igloo.blindpenguincoder.core.ui.IglooIcons
import com.igloo.blindpenguincoder.core.ui.IglooNotice
import com.igloo.blindpenguincoder.core.ui.IglooPinnedError
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.PoliteAnnouncement
import com.igloo.blindpenguincoder.core.ui.SectionHeading
import com.igloo.blindpenguincoder.core.ui.clearedSemanticsIf
import com.igloo.blindpenguincoder.core.ui.formatSpokenTime
import com.igloo.blindpenguincoder.core.ui.formatTimecode
import com.igloo.blindpenguincoder.core.ui.rememberRefocusAfterSwap
import com.igloo.blindpenguincoder.core.ui.rememberSpokenAccessibilityEnabled
import com.igloo.blindpenguincoder.core.ui.requestFocusSafely
import com.igloo.blindpenguincoder.core.ui.withRequester
import com.igloo.blindpenguincoder.feature.shared.DetailsHero
import com.igloo.blindpenguincoder.feature.shared.DetailsHeroSkeleton
import com.igloo.blindpenguincoder.feature.shared.DetailsScrollColumn
import com.igloo.blindpenguincoder.feature.shared.DetailsState
import com.igloo.blindpenguincoder.feature.shared.HeroArtwork
import com.igloo.blindpenguincoder.feature.shared.HeroReadingStop
import com.igloo.blindpenguincoder.feature.shared.HeroTitle
import com.igloo.blindpenguincoder.feature.shared.detailsSections
import com.igloo.blindpenguincoder.feature.shared.heroHeaderModifier
import com.igloo.blindpenguincoder.feature.shared.readingStopTarget
import com.igloo.blindpenguincoder.playback.model.RoomPlayhead
import kotlinx.coroutines.delay

/**
 * One watch room (docs/design-system.md section 11.10.1): the movie over its backdrop, who hosts
 * the room and what it fixed, where it is now, the Watch action — for the owner Edit invitees and
 * Close room, for an invitee Leave room — over the people in it, each Watching or Away.
 *
 * Being on this screen is being in the room: its view model connects as it opens, and the start
 * effect here takes the link down with the app and back up with it. The host owns the room's
 * existence, Back and the player Watch opens; [watchReturnRequester] rides Watch so closing the
 * player lands back on it. Close room's and Leave room's confirmations and the Edit invitees
 * dialog are drawn here, and the host gates its own Back while [WatchRoomUiState.dialogOpen]
 * (section 9.3).
 */
@Composable
fun WatchRoomScreen(
    state: WatchRoomUiState,
    actions: WatchRoomActions,
    watchReturnRequester: FocusRequester,
    modifier: Modifier = Modifier,
    // Parameterized so tests can force both states, as the details pages do.
    spokenAccessibilityEnabled: Boolean = rememberSpokenAccessibilityEnabled(),
) {
    val entryRequester = remember { FocusRequester() }
    val closeRequester = remember { FocusRequester() }
    val leaveRequester = remember { FocusRequester() }
    val editRequester = remember { FocusRequester() }
    val page = (state.details as? DetailsState.Loaded)?.value
    // A dialog gone with the room still here — Cancel, Back, a save, or a request that failed —
    // hands focus back to the control that opened it (section 9.3). An effect, not the dismiss
    // callback, because the view model ends some of them; the opener stays composed either way,
    // so the request cannot land on a detached node.
    ReturnFocusWhenClosed(state.confirmingClose, closeRequester)
    ReturnFocusWhenClosed(state.confirmingLeave, leaveRequester)
    ReturnFocusWhenClosed(state.editing != null, editRequester)

    LifecycleStartEffect(actions) {
        actions.onForeground()
        onStopOrDispose { actions.onBackground() }
    }
    LaunchedEffect(Unit) { entryRequester.requestFocusSafely() }
    // Keyed on the state's class: a room that moves, or a member arriving, must not pull focus
    // back to Watch.
    val onScreenFocus = rememberRefocusAfterSwap(state.details::class, spokenAccessibilityEnabled) {
        entryRequester.requestFocusSafely()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(IglooTheme.colors.background)
            .onFocusChanged { onScreenFocus(it.hasFocus) }
            .semantics {
                paneTitle = page?.title?.let { "$it watch room" } ?: "Watch room"
                isTraversalGroup = true
            }
            .testTag("watch_room"),
    ) {
        // The confirmation is a modal: what it covers leaves the semantics tree (section 9.3).
        Box(modifier = Modifier.clearedSemanticsIf(state.dialogOpen)) {
            when (val details = state.details) {
                is DetailsState.Loading -> DetailsHeroSkeleton(
                    artworkAspect = IglooTheme.layout.posterAspect,
                    artworkShape = RoundedCornerShape(IglooTheme.radius.lg),
                    anchorWidth = 160.dp.scaled(),
                    loadingLabel = "Loading watch room",
                    anchorRequester = entryRequester,
                    trailingStubWidths = emptyList(),
                )

                is DetailsState.Error -> IglooPinnedError(
                    message = details.message,
                    actionText = "Retry",
                    actionSemanticLabel = "Retry loading watch room",
                    actionRequester = entryRequester,
                    onAction = actions.onRetry,
                )

                is DetailsState.Loaded -> RoomContent(
                    page = details.value,
                    state = state,
                    actions = actions,
                    entryRequester = entryRequester,
                    closeRequester = closeRequester,
                    leaveRequester = leaveRequester,
                    editRequester = editRequester,
                    watchReturnRequester = watchReturnRequester,
                    spokenAccessibilityEnabled = spokenAccessibilityEnabled,
                )
            }
        }
        // What another member did, for TalkBack wherever its focus is (section 12).
        PoliteAnnouncement(state.sync.notice?.text)

        if (state.confirmingClose && page != null) {
            IglooConfirmDialog(
                title = "Close this watch room?",
                body = "This closes the room for ${page.title} and sends everyone out of it. " +
                    "It can't be undone.",
                confirmText = "Close room",
                dismissText = "Cancel",
                onConfirm = actions.onConfirmClose,
                onDismiss = actions.onDismissClose,
                confirmVariant = IglooButtonVariant.Destructive,
                pending = state.closing,
                pendingText = "Closing room…",
            )
        }
        if (state.confirmingLeave && page != null) {
            IglooConfirmDialog(
                title = "Leave this watch room?",
                body = "You'll leave the room for ${page.title} and it drops off your list. " +
                    "Only ${page.members.firstOrNull { it.id == page.ownerId }?.name ?: "the host"} " +
                    "can invite you back.",
                confirmText = "Leave room",
                dismissText = "Cancel",
                onConfirm = actions.onConfirmLeave,
                onDismiss = actions.onDismissLeave,
                confirmVariant = IglooButtonVariant.Destructive,
                pending = state.leaving,
                pendingText = "Leaving room…",
            )
        }
        val editing = state.editing
        if (editing != null && page != null) {
            EditInviteesDialog(title = page.title, editing = editing, actions = actions)
        }
    }
}

@Composable
private fun ReturnFocusWhenClosed(open: Boolean, opener: FocusRequester) {
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (wasOpen && !open) opener.requestFocusSafely()
        wasOpen = open
    }
}

@Composable
private fun RoomContent(
    page: WatchRoomPageUi,
    state: WatchRoomUiState,
    actions: WatchRoomActions,
    entryRequester: FocusRequester,
    closeRequester: FocusRequester,
    leaveRequester: FocusRequester,
    editRequester: FocusRequester,
    watchReturnRequester: FocusRequester,
    spokenAccessibilityEnabled: Boolean,
) {
    val stops = spokenAccessibilityEnabled
    val infoStop = remember { FocusRequester() }
    val watchAction = remember { FocusRequester() }
    val peopleRequester = remember { FocusRequester() }
    // Under a screen reader the summary stop is the entry anchor, as on every details page
    // (section 11.4.1): it says what the room is before what it can do, Watch one press down.
    val infoRequester = if (stops) entryRequester else infoStop
    val watchRequester = if (stops) watchAction else entryRequester
    var lastAction by remember { mutableStateOf(watchRequester) }
    val playhead = state.sync.playhead
    val position = rememberRoomPosition(playhead, actions.clock)
    val status = roomStatus(state.sync.connection, playhead, position)

    DetailsScrollColumn(
        scrollState = rememberScrollState(),
        hero = {
            DetailsHero(imageUrl = page.backdropUrl, backdropTag = "watch_room_backdrop") { overMedia ->
                Row(
                    modifier = Modifier.heroHeaderModifier(this),
                    horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.xl),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    HeroArtwork(
                        imageUrl = page.posterUrl,
                        aspect = IglooTheme.layout.posterAspect,
                        radius = IglooTheme.radius.lg,
                        fallbackIcon = IglooIcons.Movies,
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
                    ) {
                        HeroReadingStop(
                            enabled = stops,
                            tag = "watch_room_info",
                            overMedia = overMedia,
                            requester = infoRequester,
                            downRequester = watchRequester,
                            // The status as it stands, not ticking: a description that changed
                            // every second would be read again and again.
                            description = listOf(page.title, page.hostLine, page.settingsLine, status.spoken)
                                .joinToString(". "),
                        ) {
                            HeroTitle(page.title, overMedia)
                            HeroLine(page.hostLine, overMedia)
                            HeroLine(page.settingsLine, overMedia, maxLines = 2)
                            RoomStatusLine(status, overMedia)
                        }
                        RoomActionRow(
                            page = page,
                            canWatch = playhead != null,
                            actions = actions,
                            overMedia = overMedia,
                            watchRequester = watchRequester,
                            closeRequester = closeRequester,
                            leaveRequester = leaveRequester,
                            editRequester = editRequester,
                            watchReturnRequester = watchReturnRequester,
                            upRequester = if (stops) infoRequester else Cancel,
                            downRequester = peopleRequester,
                            onActionFocused = { lastAction = it },
                        )
                        state.notice?.let { notice ->
                            IglooNotice(text = notice, modifier = Modifier.testTag("watch_room_notice"))
                        }
                    }
                }
            }
        },
    ) {
        PeopleSection(
            page = page,
            connectedUserIds = state.sync.connectedUserIds,
            selfId = state.sync.selfId,
            requester = peopleRequester,
            upRequester = lastAction,
            modifier = Modifier
                .detailsSections()
                .padding(horizontal = IglooTheme.layout.safeAreaHorizontal),
        )
    }
}

/**
 * Watch, then the owner's Edit invitees and Close room, or an invitee's Leave room. Watch is inert
 * until the room's state is known — the player has to know where to open. Every edge out of the
 * row is pinned but down, to the people.
 */
@Composable
private fun RoomActionRow(
    page: WatchRoomPageUi,
    canWatch: Boolean,
    actions: WatchRoomActions,
    overMedia: Boolean,
    watchRequester: FocusRequester,
    closeRequester: FocusRequester,
    leaveRequester: FocusRequester,
    editRequester: FocusRequester,
    watchReturnRequester: FocusRequester,
    upRequester: FocusRequester,
    downRequester: FocusRequester,
    onActionFocused: (FocusRequester) -> Unit,
) {
    val buttons = buildList {
        add(watchRequester)
        if (page.isOwner) {
            add(editRequester)
            add(closeRequester)
        } else {
            add(leaveRequester)
        }
    }
    fun Modifier.inRow(requester: FocusRequester): Modifier {
        val index = buttons.indexOf(requester)
        return focusRequester(requester)
            .focusProperties {
                left = buttons.getOrNull(index - 1) ?: Cancel
                right = buttons.getOrNull(index + 1) ?: Cancel
                up = upRequester
                down = downRequester
            }
            .onFocusChanged { if (it.isFocused) onActionFocused(requester) }
    }
    Row(
        modifier = Modifier.padding(top = IglooTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
    ) {
        IglooButton(
            text = "Watch",
            onClick = actions.onWatch.takeIf { canWatch },
            icon = IglooIcons.Play,
            semanticLabel = "Watch ${page.title}",
            stateDescription = if (canWatch) null else "Connecting to the room",
            actionLabel = "Watch",
            modifier = Modifier
                .testTag("watch_room_watch")
                .withRequester(watchReturnRequester)
                .inRow(watchRequester),
        )
        if (page.isOwner) {
            IglooButton(
                text = "Edit invitees",
                onClick = actions.onEditInvitees,
                variant = IglooButtonVariant.Ghost,
                overMedia = overMedia,
                modifier = Modifier
                    .testTag("watch_room_edit")
                    .inRow(editRequester),
            )
            IglooButton(
                text = "Close room",
                onClick = actions.onRequestClose,
                variant = IglooButtonVariant.Destructive,
                modifier = Modifier
                    .testTag("watch_room_close")
                    .inRow(closeRequester),
            )
        } else {
            IglooButton(
                text = "Leave room",
                onClick = actions.onRequestLeave,
                variant = IglooButtonVariant.Ghost,
                overMedia = overMedia,
                modifier = Modifier
                    .testTag("watch_room_leave")
                    .inRow(leaveRequester),
            )
        }
    }
}

/**
 * Everyone in the room, the host first, each Watching — connected, on this screen or in the
 * player — or Away. One focus stop and one TalkBack node, like the details pages' facts panel:
 * reachable so a d-pad can bring it into view, with nothing to press.
 */
@Composable
private fun PeopleSection(
    page: WatchRoomPageUi,
    connectedUserIds: List<Long>,
    selfId: Long,
    requester: FocusRequester,
    upRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val colors = IglooTheme.colors
    var focused by remember { mutableStateOf(false) }
    val people = page.members.map { member ->
        RoomPersonUi(
            member = member,
            name = if (member.id == selfId) "${member.name} (you)" else member.name,
            host = member.id == page.ownerId,
            watching = member.id in connectedUserIds,
        )
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm)) {
        SectionHeading(PEOPLE_HEADING)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .readingStopTarget(
                    tag = "watch_room_people",
                    focused = focused,
                    requester = requester,
                    upRequester = upRequester,
                    downRequester = null,
                    onFocusChanged = { focused = it },
                    description = "$PEOPLE_HEADING: " + people.joinToString(". ") { it.spoken },
                )
                .padding(IglooTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
        ) {
            people.forEach { person ->
                Row(
                    modifier = Modifier.testTag("watch_room_member_${person.member.id}"),
                    horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IglooAvatar(
                        name = person.member.name,
                        avatarUrl = person.member.avatarUrl,
                        size = IglooTheme.icons.lg,
                        textStyle = IglooTheme.typography.label,
                    )
                    IglooText(
                        text = person.name,
                        style = IglooTheme.typography.bodyMedium,
                        color = colors.foreground,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    if (person.host) {
                        IglooText(
                            text = "Host",
                            style = IglooTheme.typography.label,
                            color = colors.mutedForeground,
                            maxLines = 1,
                        )
                    }
                    PresenceDot(watching = person.watching)
                    IglooText(
                        text = if (person.watching) "Watching" else "Away",
                        style = IglooTheme.typography.label,
                        color = colors.mutedForeground,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private data class RoomPersonUi(
    val member: RoomMemberUi,
    val name: String,
    val host: Boolean,
    val watching: Boolean,
) {
    val spoken: String
        get() = listOfNotNull(name, "host".takeIf { host }, if (watching) "watching" else "away")
            .joinToString(", ")
}

@Composable
private fun PresenceDot(watching: Boolean) {
    Box(
        modifier = Modifier
            .size(IglooTheme.sizes.dot)
            .background(if (watching) IglooTheme.colors.primary else IglooTheme.colors.muted, CircleShape),
    )
}

/** Where the room is, drawn and spoken. */
private data class RoomStatus(val text: String, val spoken: String, val playing: Boolean)

private fun roomStatus(connection: RoomConnection, playhead: RoomPlayhead?, positionSec: Double?): RoomStatus =
    when {
        connection == RoomConnection.Reconnecting -> RoomStatus(RECONNECTING_LABEL, "Reconnecting", false)
        playhead == null || positionSec == null -> RoomStatus("Connecting…", "Connecting", false)
        playhead.paused -> RoomStatus(
            "Paused at ${formatTimecode(positionSec)}",
            "Paused at ${formatSpokenTime(positionSec)}",
            false,
        )
        else -> RoomStatus("Playing · ${formatTimecode(positionSec)}", "Playing", true)
    }

/** The room's position now, moving on each second while it plays. */
@Composable
private fun rememberRoomPosition(playhead: RoomPlayhead?, clock: () -> Long): Double? {
    var now by remember { mutableLongStateOf(clock()) }
    LaunchedEffect(playhead) {
        now = clock()
        while (playhead != null && !playhead.paused) {
            delay(STATUS_TICK_MS)
            now = clock()
        }
    }
    return playhead?.positionAt(now)
}

@Composable
private fun RoomStatusLine(status: RoomStatus, overMedia: Boolean) {
    Row(
        modifier = Modifier.testTag("watch_room_status"),
        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (status.playing) PresenceDot(watching = true)
        HeroLine(status.text, overMedia)
    }
}

/** A line of the hero's prose under the title, the secondary tier over media. */
@Composable
private fun HeroLine(text: String, overMedia: Boolean, maxLines: Int = 1) {
    IglooText(
        text = text,
        style = IglooTheme.typography.bodyMedium.overMedia(overMedia),
        color = if (overMedia) OVER_MEDIA_SECONDARY else IglooTheme.colors.mutedForeground,
        maxLines = maxLines,
    )
}

private const val PEOPLE_HEADING = "People in this room"
private const val STATUS_TICK_MS = 1_000L
