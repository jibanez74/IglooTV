// LoadControl and PlayerId are part of Media3's unstable surface.
@file:androidx.annotation.OptIn(UnstableApi::class)

package com.igloo.blindpenguincoder.playback.media3

import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerBufferBudgetTest {

    private val mib = 1024L * 1024L

    @Test
    fun aStandardHeapKeepsTwoThirdsForTheRestOfTheApp() {
        // A Shield app that does not ask for a large heap: 192 MB.
        assertEquals((192 * mib / 3).toInt(), videoBufferBudgetBytes(192 * mib))
    }

    @Test
    fun aLargeHeapGetsMedia3sDefaultAndNoMore() {
        // A Shield app with largeHeap: 512 MB, whose third is above the default.
        assertEquals(MEDIA3_DEFAULT_BUFFER_BYTES, videoBufferBudgetBytes(512 * mib))
        assertEquals(MEDIA3_DEFAULT_BUFFER_BYTES, videoBufferBudgetBytes(Long.MAX_VALUE))
    }

    @Test
    fun theDefaultIsMedia3sVideoAndAudioBuffers() {
        assertEquals(2000 * 64 * 1024 + 200 * 64 * 1024, MEDIA3_DEFAULT_BUFFER_BYTES)
    }

    @Test
    fun theCapKeepsHalfTheHeapFreeAndSitsAboveTheTarget() {
        // A standard Shield heap, a Fire TV Stick's large heap, and a Shield's large heap.
        for (heap in listOf(192 * mib, 384 * mib, 512 * mib)) {
            assertEquals(heap / 2, allocationCapBytes(heap))
            val headroom = allocationCapBytes(heap) - videoBufferBudgetBytes(heap)
            // A whole 4 s segment of an 80 Mbps remux is 40 MB; the smallest heap still has 32.
            assertTrue("no room above the target on a ${heap / mib} MB heap", headroom >= 32 * mib)
        }
    }

    @Test
    fun theLoadControlCapsTheAllocatorAndForwardsEverythingElse() {
        val delegate = RecordingLoadControl()
        val loadControl = HeapBoundedLoadControl(delegate, capBytes = 4L * 64 * 1024)
        val playerId = PlayerId.UNSET

        val allocator = loadControl.getAllocator(playerId)
        assertTrue(allocator is BoundedAllocator)
        assertSame("one allocator per player", allocator, loadControl.getAllocator(playerId))
        allocator.allocate()
        assertEquals(64 * 1024, delegate.allocator.totalBytesAllocated)

        loadControl.playerNeedsData = true
        assertTrue((allocator as BoundedAllocator).playerNeedsData)

        loadControl.onPrepared(playerId)
        loadControl.onTracksSelected(PARAMETERS, TrackGroupArray.EMPTY, emptyArray())
        loadControl.onStopped(playerId)
        assertEquals(1234L, loadControl.getBackBufferDurationUs(playerId))
        assertTrue(loadControl.retainBackBufferFromKeyframe(playerId))
        assertTrue(loadControl.shouldContinueLoading(PARAMETERS))
        assertTrue(loadControl.shouldStartPlayback(PARAMETERS))
        assertTrue(
            loadControl.shouldContinuePreloading(
                playerId,
                Timeline.EMPTY,
                MediaSource.MediaPeriodId(Any()),
                0L,
            ),
        )
        loadControl.onReleased(playerId)

        assertEquals(
            listOf(
                "getAllocator", "onPrepared", "onTracksSelected", "onStopped",
                "getBackBufferDurationUs", "retainBackBufferFromKeyframe", "shouldContinueLoading",
                "shouldStartPlayback", "shouldContinuePreloading", "onReleased",
            ),
            delegate.calls,
        )

        val next = loadControl.getAllocator(playerId)
        assertNotSame("a released player's allocator was kept", allocator, next)
        assertTrue(
            "a new allocator missed the buffering flag",
            (next as BoundedAllocator).playerNeedsData,
        )
    }

    /** Answers the opposite of every LoadControl default, so a missed forward shows. */
    private class RecordingLoadControl : LoadControl {
        val calls = mutableListOf<String>()
        val allocator = DefaultAllocator(true, 64 * 1024)

        override fun getAllocator(playerId: PlayerId): Allocator {
            calls += "getAllocator"
            return allocator
        }

        override fun onPrepared(playerId: PlayerId) {
            calls += "onPrepared"
        }

        override fun onTracksSelected(
            parameters: LoadControl.Parameters,
            trackGroups: TrackGroupArray,
            trackSelections: Array<out ExoTrackSelection?>,
        ) {
            calls += "onTracksSelected"
        }

        override fun onStopped(playerId: PlayerId) {
            calls += "onStopped"
        }

        override fun onReleased(playerId: PlayerId) {
            calls += "onReleased"
        }

        override fun getBackBufferDurationUs(playerId: PlayerId): Long {
            calls += "getBackBufferDurationUs"
            return 1234L
        }

        override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean {
            calls += "retainBackBufferFromKeyframe"
            return true
        }

        override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
            calls += "shouldContinueLoading"
            return true
        }

        override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
            calls += "shouldStartPlayback"
            return true
        }

        override fun shouldContinuePreloading(
            playerId: PlayerId,
            timeline: Timeline,
            mediaPeriodId: MediaSource.MediaPeriodId,
            bufferedDurationUs: Long,
        ): Boolean {
            calls += "shouldContinuePreloading"
            return true
        }
    }

    private companion object {
        val PARAMETERS = LoadControl.Parameters(
            PlayerId.UNSET,
            Timeline.EMPTY,
            MediaSource.MediaPeriodId(Any()),
            0L,
            0L,
            1f,
            false,
            false,
            0L,
            0L,
        )
    }
}
