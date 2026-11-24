// Allocator is part of Media3's unstable surface.
@file:androidx.annotation.OptIn(UnstableApi::class)

package com.igloo.blindpenguincoder.playback.media3

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.Allocation
import androidx.media3.exoplayer.upstream.Allocator

/**
 * Holds the media buffered on the heap under [capBytes]: past it, [allocate] waits on the loading
 * thread until playback releases what it has played. The load control's target stops new loads
 * well before the cap; this bounds what a load already in progress, or a subtitle stream, can add
 * after that (see [heapBoundedLoadControl]).
 *
 * One exception, once [playerNeedsData] has stayed set for [BUFFERING_GRACE_MS]: audio and video
 * arrive interleaved in one load, so a loader held at the cap could be holding back the samples
 * the player needs to leave buffering, and nothing would ever be released. Then the allocation
 * goes through, as it did before there was a cap. The grace is for seeks: a seek buffers too, but
 * the player releases what it skipped within milliseconds, which unblocks the load without
 * passing the cap (on the Shield every seek at the cap used to go past it). An interrupted wait
 * (the loader cancelling its load) goes through too, with the interrupt kept, so the load stops at
 * its next read instead of here.
 */
internal class BoundedAllocator(
    private val inner: Allocator,
    private val capBytes: Long,
    private val nanoTime: () -> Long = System::nanoTime,
) : Allocator {

    private val lock = Object()

    /** Set while the player is buffering, waiting on the data a held load would bring. */
    @Volatile
    var playerNeedsData: Boolean = false
        set(value) {
            if (value && !field) needsDataSinceNanos = nanoTime()
            field = value
        }

    @Volatile
    private var needsDataSinceNanos = 0L

    /** Guarded by [lock]: an allocation went past the cap and its arrays may sit in the pool. */
    private var overflowed = false

    /**
     * Allocates under [lock], so two loaders (the HLS one and a subtitle stream's) cannot both
     * take the last block below the cap. The inner allocator's own monitor is only ever taken
     * inside [lock], never the other way round.
     */
    override fun allocate(): Allocation = synchronized(lock) {
        while (isAtCap() && !starvedPastGrace()) {
            try {
                lock.wait(RECHECK_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        if (isAtCap()) overflowed = true
        inner.allocate()
    }

    override fun release(allocation: Allocation) = synchronized(lock) {
        inner.release(allocation)
        afterRelease()
    }

    override fun release(allocationNode: Allocator.AllocationNode) = synchronized(lock) {
        inner.release(allocationNode)
        afterRelease()
    }

    override fun trim() = inner.trim()

    override fun getTotalBytesAllocated(): Int = inner.totalBytesAllocated

    override fun getIndividualAllocationLength(): Int = inner.individualAllocationLength

    private fun starvedPastGrace(): Boolean =
        playerNeedsData && nanoTime() - needsDataSinceNanos >= BUFFERING_GRACE_MS * 1_000_000

    private fun isAtCap(): Boolean =
        inner.totalBytesAllocated.toLong() + inner.individualAllocationLength > capBytes

    /**
     * Released blocks stay in the inner allocator's pool for reuse. After an overflow, that pool
     * would keep the arrays allocated past the cap on the heap, so once the media buffered is back
     * under the cap, the pool is trimmed to the load control's target.
     */
    private fun afterRelease() {
        if (overflowed && !isAtCap()) {
            inner.trim()
            overflowed = false
        }
        lock.notifyAll()
    }

    internal companion object {
        /** A wait also ends on every release; this bounds a missed wake-up and the grace. */
        const val RECHECK_MS = 100L

        /** Longer than a seek takes to release what it skipped, short next to a stall. */
        const val BUFFERING_GRACE_MS = 500L
    }
}
