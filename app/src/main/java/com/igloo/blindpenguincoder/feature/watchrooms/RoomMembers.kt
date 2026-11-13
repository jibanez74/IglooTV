package com.igloo.blindpenguincoder.feature.watchrooms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.OVER_MEDIA_TERTIARY
import com.igloo.blindpenguincoder.core.design.overMedia
import com.igloo.blindpenguincoder.core.ui.IglooAvatar
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.formatCount
import com.igloo.blindpenguincoder.feature.shared.DetailsState

/**
 * A room's members as overlapping avatars, then how many more there are. Decorative: whatever it
 * sits on names them. Each avatar stands on a [rim] of the surface's own colour — the card's, or
 * black over the player's picture — so overlapping neighbours read apart.
 */
@Composable
internal fun MemberStack(
    members: List<RoomMemberUi>,
    overflowCount: Int,
    avatarSize: Dp,
    rim: Color,
) {
    val colors = IglooTheme.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(-IglooTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (member in members) {
            MemberStackCell(rim) {
                IglooAvatar(
                    name = member.name,
                    avatarUrl = member.avatarUrl,
                    size = avatarSize,
                    textStyle = IglooTheme.typography.label,
                )
            }
        }
        if (overflowCount > 0) {
            MemberStackCell(rim) {
                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = avatarSize)
                        .height(avatarSize)
                        .background(colors.muted, CircleShape)
                        .padding(horizontal = IglooTheme.spacing.xs),
                    contentAlignment = Alignment.Center,
                ) {
                    IglooText(
                        text = "+${formatCount(overflowCount.toLong())}",
                        style = IglooTheme.typography.label,
                        color = colors.mutedForeground,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun MemberStackCell(rim: Color, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .background(rim, CircleShape)
            .padding(MEMBER_RIM),
    ) {
        content()
    }
}

/**
 * Who is watching, at the far end of the player's top bar (section 11.10.2): a dot, "3 watching"
 * and their faces, or "Reconnecting…" while the room's link is down. Hidden from TalkBack, which
 * hears each arrival and departure as it happens instead.
 */
@Composable
fun RoomPresenceBadge(state: WatchRoomUiState) {
    val connected = (state.details as? DetailsState.Loaded)?.value?.members.orEmpty()
        .filter { it.id in state.sync.connectedUserIds }
    val reconnecting = state.sync.connection == RoomConnection.Reconnecting
    Row(
        modifier = Modifier
            .testTag("movie_room_presence")
            .semantics { hideFromAccessibility() },
        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(IglooTheme.sizes.dot)
                .background(if (reconnecting) OVER_MEDIA_TERTIARY else IglooTheme.colors.primary, CircleShape),
        )
        IglooText(
            text = if (reconnecting) RECONNECTING_LABEL else "${formatCount(connected.size.toLong())} watching",
            style = IglooTheme.typography.label.overMedia(true),
            color = Color.White,
            maxLines = 1,
        )
        if (!reconnecting) {
            MemberStack(
                members = connected.take(ROOM_CARD_MEMBER_LIMIT),
                overflowCount = 0,
                avatarSize = IglooTheme.icons.md,
                rim = Color.Black,
            )
        }
    }
}

/** The room's link is down and coming back; said on the room screen and over the player alike. */
internal const val RECONNECTING_LABEL = "Reconnecting…"

private val MEMBER_RIM: Dp = 2.dp
