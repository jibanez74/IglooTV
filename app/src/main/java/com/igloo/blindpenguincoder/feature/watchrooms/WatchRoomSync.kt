package com.igloo.blindpenguincoder.feature.watchrooms

import com.igloo.blindpenguincoder.core.ui.formatSpokenTime
import com.igloo.blindpenguincoder.data.model.WatchRoomClientEvent
import com.igloo.blindpenguincoder.data.model.WatchRoomCommandType
import com.igloo.blindpenguincoder.data.model.WatchRoomEventType
import com.igloo.blindpenguincoder.data.model.WatchRoomPlaybackState
import com.igloo.blindpenguincoder.data.model.WatchRoomServerEvent
import com.igloo.blindpenguincoder.playback.model.ROOM_DRIFT_TOLERANCE_SEC
import com.igloo.blindpenguincoder.playback.model.RoomPlayhead
import kotlin.math.abs

/** This TV's link to the room. */
enum class RoomConnection { Connecting, Live, Reconnecting }

/** One TalkBack announcement; a new [id] makes the same words a second announcement. */
data class RoomNotice(val text: String, val id: Long)

/** A command this TV sent, waiting for the server to send it back to everyone, this TV included. */
data class PendingEcho(val command: WatchRoomClientEvent, val sentAtMs: Long)

/** One step of [WatchRoomSync]: the next state, and the command to send for it, if any. */
data class RoomSyncStep(val sync: WatchRoomSync, val send: WatchRoomClientEvent? = null)

/**
 * A watch room as this TV knows it, fed by the room's socket and by this TV's own commands. Pure,
 * with the clock passed in, so every rule below is a unit test.
 *
 * [followGeneration] moves whenever the player must check itself against the room: a snapshot,
 * another member's command, or the server's echo of this TV's own. The press itself moves
 * [playhead] without it, since the player is already doing what was pressed.
 */
data class WatchRoomSync(
    val selfId: Long,
    val connection: RoomConnection = RoomConnection.Connecting,
    /** Null until the first snapshot: the room's state is unknown until then. */
    val playhead: RoomPlayhead? = null,
    val followGeneration: Long = 0,
    val connectedUserIds: List<Long> = emptyList(),
    val pendingEchoes: List<PendingEcho> = emptyList(),
    val notice: RoomNotice? = null,
) {
    /** Nobody but this user is connected (their other devices count as them). */
    val alone: Boolean get() = connectedUserIds.all { it == selfId }

    fun onEvent(event: WatchRoomServerEvent, nowMs: Long): RoomSyncStep = when (event.type) {
        WatchRoomEventType.RoomSnapshot -> event.playback
            ?.let { onSnapshot(it, event.connectedUserIds.orEmpty(), nowMs) }
            ?: RoomSyncStep(this)
        WatchRoomEventType.PlaybackChanged -> RoomSyncStep(
            event.playback?.let { onPlaybackChanged(it, nowMs) } ?: this,
        )
        WatchRoomEventType.MemberJoined -> RoomSyncStep(
            onPresence(event, "joined the room"),
        )
        WatchRoomEventType.MemberLeft -> RoomSyncStep(
            onPresence(event, "left the room"),
        )
        // The view model ends the room on a deletion; a pong answers a ping the TV never sends.
        WatchRoomEventType.RoomDeleted, WatchRoomEventType.Pong -> RoomSyncStep(this)
    }

    /**
     * This TV's play, pause or seek at [positionSec]. Sent while the room is live; otherwise it
     * only moves this TV, and the room's state wins when the link is back. Before the first
     * snapshot there is no room to command.
     */
    fun onLocalCommand(type: WatchRoomCommandType, positionSec: Double, nowMs: Long): RoomSyncStep {
        val current = playhead ?: return RoomSyncStep(this)
        val paused = when (type) {
            WatchRoomCommandType.Play -> false
            WatchRoomCommandType.Pause -> true
            WatchRoomCommandType.Seek -> current.paused
        }
        // The notice goes: a stale one would stand in for what the player says about this press.
        val moved = copy(playhead = RoomPlayhead(paused, positionSec, nowMs), notice = null)
        if (connection != RoomConnection.Live) return RoomSyncStep(moved)
        val command = WatchRoomClientEvent(type, positionSec)
        val echoes = pendingEchoes.unexpired(nowMs) + PendingEcho(command, nowMs)
        return RoomSyncStep(moved.copy(pendingEchoes = echoes.takeLast(MAX_PENDING_ECHOES)), send = command)
    }

    /** The socket is gone. Nothing sent on it will come back, so no echo is waited for. */
    fun onDisconnected(): WatchRoomSync = copy(
        connection = if (playhead == null) RoomConnection.Connecting else RoomConnection.Reconnecting,
        pendingEchoes = emptyList(),
    )

    private fun onSnapshot(
        playback: WatchRoomPlaybackState,
        connected: List<Long>,
        nowMs: Long,
    ): RoomSyncStep {
        val known = playhead
        val live = copy(connection = RoomConnection.Live, connectedUserIds = connected, pendingEchoes = emptyList())
        val ownPosition = known?.positionAt(nowMs) ?: 0.0
        // An emptied room forgets where it was and starts again paused at 0: this TV's own drop
        // was its last connection, or the server restarted. Coming back to it alone, this TV is
        // the only memory of the room, so it puts the room back where it is.
        val forgotten = playback.paused && playback.positionSec == 0.0 && ownPosition > 0.0
        if (known != null && forgotten && connected.all { it == selfId }) {
            val kept = known.copy(positionSec = ownPosition, atMs = nowMs)
            val command = WatchRoomClientEvent(
                if (kept.paused) WatchRoomCommandType.Pause else WatchRoomCommandType.Play,
                ownPosition,
            )
            return RoomSyncStep(
                live.copy(playhead = kept, pendingEchoes = listOf(PendingEcho(command, nowMs))),
                send = command,
            )
        }
        return RoomSyncStep(live.copy(playhead = playback.at(nowMs), followGeneration = followGeneration + 1))
    }

    private fun onPlaybackChanged(playback: WatchRoomPlaybackState, nowMs: Long): WatchRoomSync {
        val echoes = pendingEchoes.unexpired(nowMs)
        val head = echoes.firstOrNull()
        if (head != null && head.command.isEchoedBy(playback)) {
            val rest = echoes.drop(1)
            // This TV's own command, back from the server. With newer ones still on their way it
            // is out of date and changes nothing. The last one is the server's word on the room,
            // and is followed: usually this TV is already there and the drift tolerance makes it
            // a no-op, but another member's command can have landed between the press and its
            // echo and moved this player since — the server ran that one first, so this wins.
            if (rest.isNotEmpty()) return copy(pendingEchoes = rest)
            return copy(pendingEchoes = rest, playhead = playback.at(nowMs), followGeneration = followGeneration + 1)
        }
        val next = playback.at(nowMs)
        return copy(pendingEchoes = echoes, playhead = next, followGeneration = followGeneration + 1)
            .withNotice(describeChange(playhead, next, nowMs))
    }

    private fun onPresence(event: WatchRoomServerEvent, verb: String): WatchRoomSync {
        val moved = copy(connectedUserIds = event.connectedUserIds ?: connectedUserIds)
        val member = event.member?.takeIf { it.id != selfId } ?: return moved
        return moved.withNotice("${member.name} $verb")
    }

    private fun withNotice(text: String?): WatchRoomSync =
        if (text == null) this else copy(notice = RoomNotice(text, (notice?.id ?: 0) + 1))
}

/**
 * The delay before reconnect [attempt] (0-based): 1, 2, 4, 8, then 16 seconds for as long as it
 * takes, the web client's schedule.
 */
fun roomReconnectDelayMs(attempt: Int): Long =
    (FIRST_RECONNECT_DELAY_MS shl attempt.coerceIn(0, RECONNECT_DOUBLINGS)).coerceAtMost(MAX_RECONNECT_DELAY_MS)

/** What another member did, in the words TalkBack speaks; null when nothing a viewer would notice changed. */
private fun describeChange(previous: RoomPlayhead?, next: RoomPlayhead, nowMs: Long): String? {
    if (previous == null) return null
    val at = formatSpokenTime(next.positionSec)
    return when {
        next.paused && !previous.paused -> "Room paused at $at"
        !next.paused && previous.paused -> "Room playing from $at"
        abs(next.positionSec - previous.positionAt(nowMs)) > ROOM_DRIFT_TOLERANCE_SEC -> "Room jumped to $at"
        else -> null
    }
}

private fun WatchRoomPlaybackState.at(nowMs: Long) = RoomPlayhead(paused, positionSec, nowMs)

/**
 * The server sends a command back as the state it set: the same play or pause, at the position
 * sent plus the moment it took to broadcast.
 */
private fun WatchRoomClientEvent.isEchoedBy(playback: WatchRoomPlaybackState): Boolean {
    val pausedMatches = when (type) {
        WatchRoomCommandType.Play -> !playback.paused
        WatchRoomCommandType.Pause -> playback.paused
        WatchRoomCommandType.Seek -> true
    }
    return pausedMatches && abs(playback.positionSec - positionSec) < ECHO_POSITION_TOLERANCE_SEC
}

/** An echo that has not come back by now never will; keeping it would hide a real change. */
private fun List<PendingEcho>.unexpired(nowMs: Long) = filter { nowMs - it.sentAtMs < ECHO_TIMEOUT_MS }

private const val ECHO_POSITION_TOLERANCE_SEC = 0.5
private const val ECHO_TIMEOUT_MS = 5_000L
private const val MAX_PENDING_ECHOES = 8
private const val FIRST_RECONNECT_DELAY_MS = 1_000L
private const val RECONNECT_DOUBLINGS = 4
private const val MAX_RECONNECT_DELAY_MS = 16_000L
