package com.igloo.blindpenguincoder.playback.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomFollowTest {

    @Test
    fun `a playing room moves on in real time and a paused one stays put`() {
        assertEquals(70.0, RoomPlayhead(paused = false, positionSec = 60.0, atMs = 1_000).positionAt(11_000), 0.0)
        assertEquals(60.0, RoomPlayhead(paused = true, positionSec = 60.0, atMs = 1_000).positionAt(11_000), 0.0)
    }

    @Test
    fun `a clock read before the playhead does not run the room backwards`() {
        assertEquals(60.0, RoomPlayhead(paused = false, positionSec = 60.0, atMs = 5_000).positionAt(4_000), 0.0)
    }

    @Test
    fun `drift inside the tolerance plays on without a seek`() {
        val follow = followRoom(localSec = 101.0, RoomPlayhead(false, 100.0, atMs = 0), nowMs = 1_000)

        assertNull(follow.seekToSec)
        assertTrue(follow.play)
    }

    @Test
    fun `drift past the tolerance seeks to where the room is now`() {
        val follow = followRoom(localSec = 40.0, RoomPlayhead(false, 100.0, atMs = 0), nowMs = 2_000)

        assertEquals(102.0, follow.seekToSec!!, 0.0)
        assertTrue(follow.play)
    }

    @Test
    fun `a paused room pauses the player, seeking only when it is far off`() {
        assertNull(followRoom(localSec = 99.0, RoomPlayhead(true, 100.0, atMs = 0), nowMs = 9_000).seekToSec)
        val far = followRoom(localSec = 10.0, RoomPlayhead(true, 100.0, atMs = 0), nowMs = 9_000)
        assertEquals(100.0, far.seekToSec!!, 0.0)
        assertFalse(far.play)
    }
}
