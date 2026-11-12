package com.igloo.blindpenguincoder.feature.watchrooms

import com.igloo.blindpenguincoder.core.config.ServerAddress
import com.igloo.blindpenguincoder.core.ui.countNoun
import com.igloo.blindpenguincoder.core.ui.formatCount
import com.igloo.blindpenguincoder.data.model.UserSummary
import com.igloo.blindpenguincoder.data.model.WatchRoomListItem
import com.igloo.blindpenguincoder.images.TmdbImageSize
import com.igloo.blindpenguincoder.images.serverImageUrl
import com.igloo.blindpenguincoder.images.tmdbImageUrl

/** How many members a room card draws and names before it counts the rest. */
internal const val ROOM_CARD_MEMBER_LIMIT = 4

/** A watch room ready to render: URLs resolved, and every line the card shows or speaks built. */
data class WatchRoomUi(
    val id: Long,
    val title: String,
    val posterUrl: String?,
    /** "Hosted by Maria", with "(you)" when the signed-in profile hosts the room. */
    val hostLine: String,
    /** The first [ROOM_CARD_MEMBER_LIMIT] members, in the order they joined. */
    val members: List<RoomMemberUi>,
    /** How many members [members] leaves out. */
    val overflowCount: Int,
    val memberLine: String,
    /** The card's one TalkBack sentence. */
    val description: String,
)

data class RoomMemberUi(
    val id: Long,
    val name: String,
    val avatarUrl: String?,
)

internal fun WatchRoomListItem.toWatchRoomUi(server: ServerAddress): WatchRoomUi {
    val shown = members.take(ROOM_CARD_MEMBER_LIMIT)
    val overflow = members.size - shown.size
    val memberLine = "${formatCount(members.size.toLong())} ${countNoun(members.size.toLong(), "member")}"
    return WatchRoomUi(
        id = id,
        title = movieTitle,
        // The library's poster size, so a room for a movie the grid has shown costs no request.
        posterUrl = tmdbImageUrl(server.apiBaseUrl, TmdbImageSize.W500, moviePoster),
        hostLine = if (isOwner) "Hosted by ${owner.name} (you)" else "Hosted by ${owner.name}",
        members = shown.map { it.toRoomMemberUi(server) },
        overflowCount = overflow,
        memberLine = memberLine,
        description = buildString {
            append(movieTitle)
            append(if (isOwner) ", hosted by you" else ", hosted by ${owner.name}")
            append(", $memberLine")
            if (shown.isNotEmpty()) {
                append(": ")
                append(shown.joinToString(", ") { it.name })
                if (overflow > 0) append(" and ${formatCount(overflow.toLong())} more")
            }
        },
    )
}

private fun UserSummary.toRoomMemberUi(server: ServerAddress) = RoomMemberUi(
    id = id,
    name = name,
    avatarUrl = serverImageUrl(server.origin, avatar),
)
