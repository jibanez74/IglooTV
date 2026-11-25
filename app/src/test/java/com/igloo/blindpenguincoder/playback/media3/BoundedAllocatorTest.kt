// Allocator and DefaultAllocator are part of Media3's unstable surface.
@file:androidx.annotation.OptIn(UnstableApi::class)

package com.igloo.blindpenguincoder.playback.media3

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.upstream.Allocation
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedAllocatorTest {

    private val length = 64 * 1024
    private val inner = DefaultAllocator(/* trimOnReset= */ true, length)

    private val clockNanos = AtomicLong(0L)

    /** The fake clock moves past a buffering player's grace. */
    private fun passTheGrace() {
        clockNanos.addAndGet(BoundedAllocator.BUFFERING_GRACE_MS * 1_000_000)
    }

    /** Room for exactly two allocations. */
    private val allocator =
        BoundedAllocator(inner, capBytes = 2L * length, nanoTime = clockNanos::get)

    @Test
    fun allocatesAtOnceUnderTheCap() {
        allocator.allocate()
        allocator.allocate()
        assertEquals(2 * length, allocator.totalBytesAllocated)
        assertEquals(length, allocator.individualAllocationLength)
    }

    @Test
    fun waitsAtTheCapUntilPlaybackReleases() {
        val first = allocator.allocate()
        allocator.allocate()

        val third = allocateOnAnotherThread()
        third.assertHeld("allocated past the cap")

        allocator.release(first)
        assertNotNull("a release did not wake the load", third.result(DONE_MS))
        assertEquals(2 * length, allocator.totalBytesAllocated)
    }

    @Test
    fun aReleasedNodeWakesTheLoadToo() {
        val first = allocator.allocate()
        allocator.allocate()

        val third = allocateOnAnotherThread()
        third.assertHeld()

        allocator.release(Node(first))
        assertNotNull(third.result(DONE_MS))
    }

    @Test
    fun goesPastTheCapOnceThePlayerHasBufferedThroughTheGrace() {
        allocator.allocate()
        allocator.allocate()

        val third = allocateOnAnotherThread()
        third.assertHeld()

        allocator.playerNeedsData = true
        third.assertHeld("a seek's brief buffering went past the cap")

        passTheGrace()
        assertNotNull("a stalled player was kept from its data", third.result(DONE_MS))
        allocator.allocate()
        assertEquals(4 * length, allocator.totalBytesAllocated)
    }

    @Test
    fun theGraceStartsOverEachTimeThePlayerBuffers() {
        allocator.allocate()
        allocator.allocate()
        allocator.playerNeedsData = true
        passTheGrace()
        allocator.playerNeedsData = false
        allocator.playerNeedsData = true

        val third = allocateOnAnotherThread()
        third.assertHeld("an earlier buffering counted toward this one")
        passTheGrace()
        assertNotNull(third.result(DONE_MS))
    }

    @Test
    fun anInterruptedWaitAllocatesAndKeepsTheInterrupt() {
        allocator.allocate()
        allocator.allocate()

        val third = allocateOnAnotherThread()
        third.assertHeld()

        third.thread.interrupt()
        assertNotNull("a cancelled load stayed blocked", third.result(DONE_MS))
        assertTrue("the interrupt was swallowed", third.interrupted.get())
    }

    @Test
    fun twoLoadersCannotBothTakeTheLastBlock() {
        val roomForThree =
            BoundedAllocator(inner, capBytes = 3L * length, nanoTime = clockNanos::get)
        val first = roomForThree.allocate()
        roomForThree.allocate()

        val loaders = List(2) { allocateOnAnotherThread(roomForThree) }
        val deadline = System.nanoTime() + DONE_MS * 1_000_000
        while (loaders.none { it.allocation.get() != null } && System.nanoTime() < deadline) {
            Thread.sleep(5)
        }
        val (winner, waiter) = loaders.partition { it.allocation.get() != null }
        assertEquals("exactly one loader should take the last block", 1, winner.size)
        waiter.single().assertHeld("both loaders went past the cap")
        assertEquals(3 * length, roomForThree.totalBytesAllocated)

        roomForThree.release(first)
        assertNotNull(waiter.single().result(DONE_MS))
    }

    @Test
    fun theReusePoolIsTrimmedOnceAnOverflowIsPaidBack() {
        val held = mutableListOf(allocator.allocate(), allocator.allocate())
        allocator.playerNeedsData = true
        passTheGrace()
        held += allocator.allocate()
        held += allocator.allocate()
        assertEquals(4 * length, allocator.totalBytesAllocated)

        held.forEach(allocator::release)
        // Trimmed as the buffer fell back under the cap; only the last block released is pooled.
        assertEquals(length, inner.unusedBytesAllocated)
    }

    @Test
    fun theReusePoolIsLeftAloneWithoutAnOverflow() {
        val held = listOf(allocator.allocate(), allocator.allocate())
        held.forEach(allocator::release)
        assertEquals(2 * length, inner.unusedBytesAllocated)
    }

    @Test
    fun aMultiBlockNodeIsCountedOncePerBlock() {
        val first = allocator.allocate()
        val second = allocator.allocate()
        allocator.release(Node(first, Node(second)))
        assertEquals(0, allocator.totalBytesAllocated)
        assertEquals(0, inner.totalBytesAllocated)
    }

    @Test
    fun blocksReleasedAfterThePlayerIsReleasedStillCount() {
        // In production the inner allocator is the load control's per-player one, whose count
        // throws once onReleased drops the player; a deferred HLS cleanup releases after that.
        val playerId = PlayerId.UNSET
        val control = DefaultLoadControl()
        control.onPrepared(playerId)
        val perPlayer = control.getAllocator(playerId)
        val blockLength = perPlayer.individualAllocationLength
        val bounded =
            BoundedAllocator(perPlayer, capBytes = 2L * blockLength, nanoTime = clockNanos::get)
        bounded.playerNeedsData = true
        passTheGrace()
        val held = List(3) { bounded.allocate() }

        control.onReleased(playerId)
        held.forEach(bounded::release)
        assertEquals(0, bounded.totalBytesAllocated)
    }

    @Test
    fun releasingHandsTheMemoryBackToTheInnerAllocator() {
        val allocation = allocator.allocate()
        allocator.release(allocation)
        assertEquals(0, inner.totalBytesAllocated)
        assertFalse(allocator.playerNeedsData)
    }

    private fun allocateOnAnotherThread(on: BoundedAllocator = allocator): PendingAllocation {
        val pending = PendingAllocation()
        pending.thread = Thread {
            pending.allocation.set(on.allocate())
            pending.interrupted.set(Thread.currentThread().isInterrupted)
            pending.done.countDown()
        }.also {
            it.isDaemon = true
            it.start()
        }
        return pending
    }

    private class PendingAllocation {
        lateinit var thread: Thread
        val allocation = AtomicReference<Allocation?>()
        val interrupted = AtomicBoolean(false)
        val done = CountDownLatch(1)

        fun result(timeoutMs: Long): Allocation? {
            done.await(timeoutMs, TimeUnit.MILLISECONDS)
            return allocation.get()
        }

        /**
         * Asserts the worker is parked in the allocator's wait, not merely late to start: waits
         * (bounded) until it has either returned or reached the wait, then requires the latter.
         */
        fun assertHeld(message: String = "allocated past the cap") {
            val deadline = System.nanoTime() + DONE_MS * 1_000_000
            while (done.count > 0 &&
                thread.state != Thread.State.TIMED_WAITING &&
                System.nanoTime() < deadline
            ) {
                Thread.sleep(1)
            }
            assertNull(message, result(WAIT_MS))
            assertEquals(
                "the worker never reached the allocator's wait",
                Thread.State.TIMED_WAITING,
                thread.state,
            )
        }
    }

    private class Node(
        private val allocation: Allocation,
        private val next: Allocator.AllocationNode? = null,
    ) : Allocator.AllocationNode {
        override fun getAllocation(): Allocation = allocation
        override fun next(): Allocator.AllocationNode? = next
    }

    private companion object {
        /** Long enough that an allocation that should wait would have returned. */
        const val WAIT_MS = 300L
        const val DONE_MS = 5_000L
    }
}
