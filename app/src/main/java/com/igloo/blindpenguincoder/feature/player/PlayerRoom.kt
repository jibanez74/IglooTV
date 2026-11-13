package com.igloo.blindpenguincoder.feature.player

import androidx.compose.runtime.Immutable
import com.igloo.blindpenguincoder.playback.model.RoomNotice
import com.igloo.blindpenguincoder.playback.model.RoomPlayhead
import com.igloo.blindpenguincoder.playback.model.TransportCommand

/**
 * The watch room a player is showing (design-system section 11.10.2): where the room is, and
 * where this viewer's own commands go. The host rebuilds it as the room moves; the screen always
 * reads the latest.
 */
@Immutable
data class PlayerRoom(
    /** Null until the room's state is known. */
    val playhead: RoomPlayhead?,
    /** Moves whenever the player must check itself against [playhead]. */
    val followGeneration: Long,
    /** What another member last did, for TalkBack; it speaks over the player's own phase words. */
    val notice: RoomNotice?,
    /**
     * Whether the player opens playing. Read once: afterwards the room's state, not this, says
     * whether it plays.
     */
    val startPlaying: Boolean,
    /** The clock [playhead] was read on. */
    val clock: () -> Long,
    /** A play, pause or seek this viewer made, with where the player then was. */
    val onTransport: (command: TransportCommand, positionSec: Double) -> Unit,
    /** The first frame each engine plays, at [positionSec]. */
    val onPlaybackStarted: (positionSec: Double) -> Unit,
)
