// Allocator and DefaultAllocator are part of Media3's unstable surface.
@file:androidx.annotation.OptIn(UnstableApi::class)

package com.igloo.blindpenguincoder.playback.media3

import androidx.media3.common.util.UnstableApi
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
        assertNull("allocated past the cap", third.result(WAIT_MS))

        allocator.release(first)
        assertNotNull("a release did not wake the load", third.result(DONE_MS))
        assertEquals(2 * length, allocator.totalBytesAllocated)
    }

    @Test
    fun aReleasedNodeWakesTheLoadToo() {
        val first = allocator.allocate()
        allocator.allocate()

        val third = allocateOnAnotherThread()
        assertNull(third.result(WAIT_MS))

        allocator.release(Node(first))
        assertNotNull(third.result(DONE_MS))
    }

    @Test
    fun goesPastTheCapOnceThePlayerHasBufferedThroughTheGrace() {
        allocator.allocate()
        allocator.allocate()

        val third = allocateOnAnotherThread()
        assertNull(third.result(WAIT_MS))

        allocator.playerNeedsData = true
        assertNull("a seek's brief buffering went past the cap", third.result(WAIT_MS))

        clockNanos.addAndGet(BoundedAllocator.BUFFERING_GRACE_MS * 1_000_000)
        assertNotNull("a stalled player was kept from its data", third.result(DONE_MS))
        allocator.allocate()
        assertEquals(4 * length, allocator.totalBytesAllocated)
    }

    @Test
    fun theGraceStartsOverEachTimeThePlayerBuffers() {
        allocator.allocate()
        allocator.allocate()
        allocator.playerNeedsData = true
        clockNanos.addAndGet(BoundedAllocator.BUFFERING_GRACE_MS * 1_000_000)
        allocator.playerNeedsData = false
        allocator.playerNeedsData = true

        val third = allocateOnAnotherThread()
        assertNull("an earlier buffering counted toward this one", third.result(WAIT_MS))
        clockNanos.addAndGet(BoundedAllocator.BUFFERING_GRACE_MS * 1_000_000)
        assertNotNull(third.result(DONE_MS))
    }

    @Test
    fun anInterruptedWaitAllocatesAndKeepsTheInterrupt() {
        allocator.allocate()
        allocator.allocate()

        val third = allocateOnAnotherThread()
        assertNull(third.result(WAIT_MS))

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
        assertNull("both loaders went past the cap", waiter.single().result(WAIT_MS))
        assertEquals(3 * length, roomForThree.totalBytesAllocated)

        roomForThree.release(first)
        assertNotNull(waiter.single().result(DONE_MS))
    }

    @Test
    fun theReusePoolIsTrimmedOnceAnOverflowIsPaidBack() {
        val held = mutableListOf(allocator.allocate(), allocator.allocate())
        allocator.playerNeedsData = true
        clockNanos.addAndGet(BoundedAllocator.BUFFERING_GRACE_MS * 1_000_000)
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
    }

    private class Node(private val allocation: Allocation) : Allocator.AllocationNode {
        override fun getAllocation(): Allocation = allocation
        override fun next(): Allocator.AllocationNode? = null
    }

    private companion object {
        /** Long enough that an allocation that should wait would have returned. */
        const val WAIT_MS = 300L
        const val DONE_MS = 5_000L
    }
}
