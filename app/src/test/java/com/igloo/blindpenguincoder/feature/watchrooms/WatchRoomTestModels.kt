package com.igloo.blindpenguincoder.feature.watchrooms

import com.igloo.blindpenguincoder.data.model.WatchRoomEventType
import com.igloo.blindpenguincoder.data.model.WatchRoomPlaybackState
import com.igloo.blindpenguincoder.data.model.WatchRoomServerEvent

/** The room's state as the server sends it on connect, with who is [connected]. */
internal fun snapshot(paused: Boolean, position: Double, connected: List<Long>) = WatchRoomServerEvent(
    type = WatchRoomEventType.RoomSnapshot,
    playback = WatchRoomPlaybackState(paused, position),
    connectedUserIds = connected,
)
