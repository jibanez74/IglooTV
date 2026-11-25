package com.igloo.blindpenguincoder.feature.watchrooms

import com.igloo.blindpenguincoder.core.config.ServerAddress
import com.igloo.blindpenguincoder.core.ui.countNoun
import com.igloo.blindpenguincoder.core.ui.formatCount
import com.igloo.blindpenguincoder.data.model.Movie
import com.igloo.blindpenguincoder.data.model.MovieTechnicalDetailsData
import com.igloo.blindpenguincoder.data.model.PlaybackMode
import com.igloo.blindpenguincoder.data.model.UserSummary
import com.igloo.blindpenguincoder.data.model.WatchRoomDetail
import com.igloo.blindpenguincoder.data.model.WatchRoomListItem
import com.igloo.blindpenguincoder.data.model.playbackModeOf
import com.igloo.blindpenguincoder.feature.movies.audioTrackLabel
import com.igloo.blindpenguincoder.feature.movies.primaryVideoStream
import com.igloo.blindpenguincoder.feature.movies.roomSettingsLine
import com.igloo.blindpenguincoder.feature.movies.subtitleTrackLabel
import com.igloo.blindpenguincoder.images.TmdbImageSize
import com.igloo.blindpenguincoder.images.serverImageUrl
import com.igloo.blindpenguincoder.images.tmdbImageUrl
import com.igloo.blindpenguincoder.playback.model.PlayableSubtitleTrack
import com.igloo.blindpenguincoder.playback.model.PlaybackMediaRef
import com.igloo.blindpenguincoder.playback.model.VideoPlayRequest

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
        hostLine = hostLine(owner, isOwner),
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

/**
 * One room as its own screen shows it (section 11.10.1), with the request its Watch button starts
 * the player on — everything but where the room is, which is read at the press.
 */
data class WatchRoomPageUi(
    val id: Long,
    val title: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val ownerId: Long,
    val isOwner: Boolean,
    val hostLine: String,
    /** What the room fixed when it was made: "Original quality — audio adjusted · English · 5.1 surround · Subtitles off". */
    val settingsLine: String,
    /** Everyone in the room, the owner first, then in the order they joined. */
    val members: List<RoomMemberUi>,
    val playRequest: VideoPlayRequest,
)

internal fun toWatchRoomPageUi(
    room: WatchRoomDetail,
    movie: Movie?,
    technical: MovieTechnicalDetailsData,
    server: ServerAddress,
): WatchRoomPageUi {
    val posterUrl = tmdbImageUrl(server.apiBaseUrl, TmdbImageSize.W500, room.moviePoster)
    // Positions in stream_index order: that is what the room's ordinals count (section 11.10.1).
    val audio = technical.audioStreams.sortedBy { it.streamIndex }
    val subtitles = technical.subtitles.sortedBy { it.streamIndex }
    val subtitleIndex = room.subtitleTrack?.toInt()?.takeIf { it in subtitles.indices }
    val mode = playbackModeOf(room.playbackMode)
    return WatchRoomPageUi(
        id = room.id,
        title = room.movieTitle,
        posterUrl = posterUrl,
        backdropUrl = tmdbImageUrl(server.apiBaseUrl, TmdbImageSize.W1280, movie?.backdropPath?.orNull()),
        ownerId = room.owner.id,
        isOwner = room.isOwner,
        hostLine = hostLine(room.owner, room.isOwner),
        settingsLine = roomSettingsLine(
            mode = mode,
            audio = audio.getOrNull(room.audioTrack.toInt())?.let { audioTrackLabel(it, room.audioTrack.toInt()) },
            subtitle = subtitleIndex?.let { subtitleTrackLabel(subtitles[it], it) },
        ),
        members = (listOf(room.owner) + room.members.filter { it.id != room.owner.id })
            .map { it.toRoomMemberUi(server) },
        playRequest = VideoPlayRequest(
            media = PlaybackMediaRef.Movie(room.movieId),
            title = room.movieTitle,
            posterUrl = posterUrl,
            mimeType = technical.movie.mimeType,
            // An unknown future mode still plays: the room's stream is HLS unless it is direct.
            mode = mode ?: PlaybackMode.Remux,
            videoCodec = primaryVideoStream(technical.videoStreams)?.codec,
            // The room's audio is baked into its stream, so the player is told of none: nothing
            // to pick, and no Direct-to-Remux turn for a track it would otherwise judge.
            audioTypeIndex = null,
            subtitleTypeIndex = subtitleIndex,
            subtitleTracks = subtitles.mapIndexed { index, subtitle ->
                PlayableSubtitleTrack(label = subtitleTrackLabel(subtitle, index))
            },
            resumeAtSec = null,
            durationSec = movie?.duration?.orNull(),
            promptForResume = false,
            watchRoomId = room.id,
        ),
    )
}

/** "Hosted by Maria", with "(you)" when the signed-in profile hosts the room. */
private fun hostLine(owner: UserSummary, isOwner: Boolean): String =
    if (isOwner) "Hosted by ${owner.name} (you)" else "Hosted by ${owner.name}"

internal fun UserSummary.toRoomMemberUi(server: ServerAddress) = RoomMemberUi(
    id = id,
    name = name,
    avatarUrl = serverImageUrl(server.origin, avatar),
)
