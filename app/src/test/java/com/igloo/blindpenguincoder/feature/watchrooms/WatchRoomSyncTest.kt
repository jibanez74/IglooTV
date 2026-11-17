package com.igloo.blindpenguincoder.feature.watchrooms

import com.igloo.blindpenguincoder.data.model.UserSummary
import com.igloo.blindpenguincoder.data.model.WatchRoomClientEvent
import com.igloo.blindpenguincoder.data.model.WatchRoomCommandType
import com.igloo.blindpenguincoder.data.model.WatchRoomEventType
import com.igloo.blindpenguincoder.data.model.WatchRoomPlaybackState
import com.igloo.blindpenguincoder.data.model.WatchRoomServerEvent
import com.igloo.blindpenguincoder.playback.model.RoomPlayhead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchRoomSyncTest {

    private val me = 1L
    private val maria = UserSummary(id = 2, name = "Maria", avatar = null)

    private fun snapshot(paused: Boolean, position: Double, connected: List<Long> = listOf(me)) =
        WatchRoomServerEvent(
            type = WatchRoomEventType.RoomSnapshot,
            playback = WatchRoomPlaybackState(paused, position),
            connectedUserIds = connected,
        )

    private fun changed(paused: Boolean, position: Double) =
        WatchRoomServerEvent(type = WatchRoomEventType.PlaybackChanged, playback = WatchRoomPlaybackState(paused, position))

    /** A room this TV joined at [nowMs], with Maria in it too unless [connected] says otherwise. */
    private fun live(paused: Boolean = false, position: Double = 100.0, nowMs: Long = 0, connected: List<Long> = listOf(me, 2)) =
        WatchRoomSync(selfId = me).onEvent(snapshot(paused, position, connected), nowMs).sync

    @Test
    fun `the first snapshot makes the room live and asks the player to follow it`() {
        val step = WatchRoomSync(selfId = me).onEvent(snapshot(paused = false, position = 61.5, connected = listOf(1, 2)), 500)

        assertEquals(RoomConnection.Live, step.sync.connection)
        assertEquals(RoomPlayhead(false, 61.5, 500), step.sync.playhead)
        assertEquals(1L, step.sync.followGeneration)
        assertEquals(listOf(1L, 2L), step.sync.connectedUserIds)
        assertFalse(step.sync.alone)
        assertNull(step.send)
        assertNull(step.sync.notice)
    }

    @Test
    fun `this user's other devices do not stop the room from being theirs alone`() {
        assertTrue(live(connected = listOf(me)).alone)
    }

    @Test
    fun `a local command is sent, moves the playhead and waits for its echo without a follow`() {
        val room = live()

        val step = room.onLocalCommand(WatchRoomCommandType.Pause, 104.0, nowMs = 4_000)

        assertEquals(WatchRoomClientEvent(WatchRoomCommandType.Pause, 104.0), step.send)
        assertEquals(RoomPlayhead(true, 104.0, 4_000), step.sync.playhead)
        assertEquals(room.followGeneration, step.sync.followGeneration)
        assertEquals(1, step.sync.pendingEchoes.size)
    }

    @Test
    fun `a seek keeps the room's play or pause`() {
        val step = live(paused = true).onLocalCommand(WatchRoomCommandType.Seek, 300.0, nowMs = 1_000)

        assertTrue(step.sync.playhead!!.paused)
    }

    @Test
    fun `the echo of the last command is followed quietly`() {
        val sent = live().onLocalCommand(WatchRoomCommandType.Pause, 104.0, nowMs = 4_000).sync

        val echoed = sent.onEvent(changed(paused = true, position = 104.0), 4_050).sync

        assertTrue(echoed.pendingEchoes.isEmpty())
        assertEquals(sent.followGeneration + 1, echoed.followGeneration)
        assertNull(echoed.notice)
    }

    @Test
    fun `an echo overtaken by a newer command changes nothing`() {
        val sent = live()
            .onLocalCommand(WatchRoomCommandType.Seek, 200.0, nowMs = 1_000).sync
            .onLocalCommand(WatchRoomCommandType.Seek, 210.0, nowMs = 1_200).sync

        val first = sent.onEvent(changed(paused = false, position = 200.01), 1_250).sync

        assertEquals(sent.followGeneration, first.followGeneration)
        assertEquals(RoomPlayhead(false, 210.0, 1_200), first.playhead)
        val second = first.onEvent(changed(paused = false, position = 210.0), 1_300).sync
        assertEquals(sent.followGeneration + 1, second.followGeneration)
        assertTrue(second.pendingEchoes.isEmpty())
    }

    @Test
    fun `another member's pause is followed and announced`() {
        val room = live(paused = false, position = 100.0, nowMs = 0)

        val paused = room.onEvent(changed(paused = true, position = 720.0), 1_000).sync

        assertEquals(room.followGeneration + 1, paused.followGeneration)
        assertEquals(RoomPlayhead(true, 720.0, 1_000), paused.playhead)
        assertEquals("Room paused at 12 minutes", paused.notice?.text)
    }

    @Test
    fun `another member's play and jump each have their own words`() {
        val room = live(paused = true, position = 100.0)

        val playing = room.onEvent(changed(paused = false, position = 100.0), 1_000).sync
        assertEquals("Room playing from 1 minute and 40 seconds", playing.notice?.text)
        val jumped = playing.onEvent(changed(paused = false, position = 3_720.0), 2_000).sync
        assertEquals("Room jumped to 1 hour and 2 minutes", jumped.notice?.text)
    }

    @Test
    fun `a change no viewer would notice is followed without a word`() {
        val room = live(paused = false, position = 100.0, nowMs = 0)

        val same = room.onEvent(changed(paused = false, position = 110.5), 10_000).sync

        assertEquals(room.followGeneration + 1, same.followGeneration)
        assertNull(same.notice)
    }

    @Test
    fun `a command that crosses another member's is still settled by its echo`() {
        // This TV pauses at 100 while Maria jumps to 500; the server runs Maria's first.
        val sent = live().onLocalCommand(WatchRoomCommandType.Pause, 100.0, nowMs = 1_000).sync

        val foreign = sent.onEvent(changed(paused = false, position = 500.0), 1_050).sync
        assertEquals("Room playing from 8 minutes and 20 seconds", foreign.notice?.text)
        assertEquals(1, foreign.pendingEchoes.size)
        val settled = foreign.onEvent(changed(paused = true, position = 100.0), 1_100).sync

        assertEquals(RoomPlayhead(true, 100.0, 1_100), settled.playhead)
        assertEquals(foreign.followGeneration + 1, settled.followGeneration)
    }

    @Test
    fun `an echo that never came back stops being waited for`() {
        val sent = live().onLocalCommand(WatchRoomCommandType.Pause, 100.0, nowMs = 1_000).sync

        // Six seconds on, an identical pause is someone else's and is announced.
        val later = sent.onEvent(changed(paused = true, position = 100.0), 7_000).sync

        assertTrue(later.pendingEchoes.isEmpty())
    }

    @Test
    fun `joins and leaves update who is connected and name the member`() {
        val room = live(connected = listOf(me))

        val joined = room.onEvent(
            WatchRoomServerEvent(WatchRoomEventType.MemberJoined, member = maria, connectedUserIds = listOf(me, 2)),
            1_000,
        ).sync
        assertEquals("Maria joined the room", joined.notice?.text)
        assertFalse(joined.alone)
        val left = joined.onEvent(
            WatchRoomServerEvent(WatchRoomEventType.MemberLeft, member = maria, connectedUserIds = listOf(me)),
            2_000,
        ).sync
        assertEquals("Maria left the room", left.notice?.text)
        assertTrue(left.alone)
        assertTrue(left.notice!!.id > joined.notice!!.id)
    }

    @Test
    fun `a local command clears a stale notice so the player's own words are spoken`() {
        val noticed = live().onEvent(changed(paused = true, position = 100.0), 1_000).sync

        assertNull(noticed.onLocalCommand(WatchRoomCommandType.Play, 100.0, 2_000).sync.notice)
    }

    @Test
    fun `commands while the link is down move only this TV`() {
        val down = live().onDisconnected()

        val step = down.onLocalCommand(WatchRoomCommandType.Pause, 140.0, nowMs = 5_000)

        assertEquals(RoomConnection.Reconnecting, down.connection)
        assertNull(step.send)
        assertEquals(RoomPlayhead(true, 140.0, 5_000), step.sync.playhead)
        assertTrue(step.sync.pendingEchoes.isEmpty())
    }

    @Test
    fun `no command is sent before the room's state is known`() {
        val step = WatchRoomSync(selfId = me).onLocalCommand(WatchRoomCommandType.Play, 0.0, 0)

        assertNull(step.send)
        assertNull(step.sync.playhead)
    }

    @Test
    fun `a room that forgot its place while this TV was away alone is put back`() {
        // Playing at 100 when the link dropped; ten seconds later the server has forgotten the room.
        val down = live(paused = false, position = 100.0, nowMs = 0, connected = listOf(me)).onDisconnected()

        val step = down.onEvent(snapshot(paused = true, position = 0.0, connected = listOf(me)), 10_000)

        assertEquals(WatchRoomClientEvent(WatchRoomCommandType.Play, 110.0), step.send)
        assertEquals(RoomPlayhead(false, 110.0, 10_000), step.sync.playhead)
        assertEquals(down.followGeneration, step.sync.followGeneration)
        assertEquals(RoomConnection.Live, step.sync.connection)
    }

    @Test
    fun `a reset room with others in it is theirs to follow`() {
        val down = live(paused = false, position = 100.0).onDisconnected()

        val step = down.onEvent(snapshot(paused = true, position = 0.0, connected = listOf(me, 2)), 10_000)

        assertNull(step.send)
        assertEquals(RoomPlayhead(true, 0.0, 10_000), step.sync.playhead)
        assertEquals(down.followGeneration + 1, step.sync.followGeneration)
    }

    @Test
    fun `reconnects wait 1, 2, 4, 8 and then 16 seconds for good`() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 16_000L, 16_000L), (0..6).map(::roomReconnectDelayMs))
    }
}
