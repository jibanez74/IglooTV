package com.igloo.blindpenguincoder.playback.model

/**
 * The user-facing sentences for playback failures that more than one layer can be first to
 * detect — the HLS manifest preflight, the session controller's retry budgets, and the
 * player's own load errors — plus the narration those budgets show while they wait. Spelled
 * once so the same wait or failure reads the same no matter which layer produced it.
 */

const val PLAYBACK_UNAUTHORIZED_MESSAGE =
    "Your session is no longer valid. Sign in again to keep watching."

const val PLAYBACK_SESSION_LOST_MESSAGE =
    "The playback session was lost and could not be recreated."

const val PLAYBACK_SERVER_BUSY_MESSAGE =
    "The server is busy converting other streams. Try again shortly."

const val PLAYBACK_SERVER_UNREACHABLE_MESSAGE =
    "The server could not be reached. Check the connection and try again."

fun playbackServerRefusedMessage(httpStatus: Int): String =
    "The server refused the stream (HTTP $httpStatus)."

/** Narration while the capacity-retry budget waits out a 503. */
const val HLS_WAITING_FOR_CAPACITY_MESSAGE = "Waiting for the server to free up…"

/** Narration while the session-lost budget recreates an evicted session. */
const val HLS_RECONNECTING_MESSAGE = "Reconnecting to the stream…"
