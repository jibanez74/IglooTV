package com.igloo.blindpenguincoder.playback.model

import kotlin.math.abs

/**
 * Where a watch room is, read at [atMs] on the local monotonic clock. The server's own timestamp
 * is never used: the moment its message arrived stands in for it, as on the web, so the TV's
 * clock and the server's never have to agree.
 */
data class RoomPlayhead(
    val paused: Boolean,
    val positionSec: Double,
    val atMs: Long,
) {
    /** Where the room is at [nowMs]: still while paused, moving on in real time while playing. */
    fun positionAt(nowMs: Long): Double =
        if (paused) positionSec else positionSec + (nowMs - atMs).coerceAtLeast(0) / MILLIS_PER_SECOND
}

/** One TalkBack announcement of what another member did; a new [id] makes the same words a second one. */
data class RoomNotice(val text: String, val id: Long)

/** What a member's player does to match the room: seek first if [seekToSec] is set, then play or pause. */
data class RoomFollow(
    val seekToSec: Double?,
    val play: Boolean,
)

/**
 * Matches a player at [localSec] to [playhead]. It seeks only past [ROOM_DRIFT_TOLERANCE_SEC]:
 * two players fed the same stream drift by a few hundred milliseconds, and a seek for less than a
 * second and a half costs more (a rebuffer) than the drift it would cure. The same tolerance makes
 * a member's own command, which the server sends back to everyone, a no-op on the sender.
 */
fun followRoom(localSec: Double, playhead: RoomPlayhead, nowMs: Long): RoomFollow {
    val target = playhead.positionAt(nowMs)
    return RoomFollow(
        seekToSec = target.takeIf { abs(it - localSec) > ROOM_DRIFT_TOLERANCE_SEC },
        play = !playhead.paused,
    )
}

/** The web client's `WATCH_ROOM_SYNC_DRIFT_THRESHOLD_SEC`, so both clients settle the same way. */
const val ROOM_DRIFT_TOLERANCE_SEC = 1.5

private const val MILLIS_PER_SECOND = 1000.0
