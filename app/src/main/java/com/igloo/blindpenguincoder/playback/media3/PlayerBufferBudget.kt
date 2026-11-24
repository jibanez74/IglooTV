// DefaultLoadControl and LoadControl are part of Media3's unstable surface.
@file:androidx.annotation.OptIn(UnstableApi::class)

package com.igloo.blindpenguincoder.playback.media3

import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/**
 * Media3 buffers media in byte arrays on the Java heap, loading until about 144 MB of video and
 * audio are buffered by default. A high-bitrate remux reaches that within half a minute; on a
 * 192 MB heap that left nothing for the rest of the app, and the next allocation (a subtitle
 * bitmap, the player UI) crashed it. Loading now stops at a third of the heap, and never later
 * than Media3's default.
 *
 * That target only stops new loads: a load in progress (a whole HLS segment) finishes, and a
 * subtitle stream reads on until 1 MiB of it has arrived since it was last continued (see
 * [SubtitleStreamPeriod]). So the allocator is also capped, at half the heap: a load that reaches
 * the cap waits for playback to release what it has played. [BoundedAllocator] names the two cases
 * it lets through, a player stalled waiting on that load and a load being cancelled.
 */
internal class HeapBoundedLoadControl(
    private val delegate: LoadControl,
    private val capBytes: Long,
) : LoadControl {

    private val allocators = HashMap<PlayerId, BoundedAllocator>()

    /** Tells every player's allocator whether its player is buffering (see [BoundedAllocator]). */
    @Volatile
    var playerNeedsData: Boolean = false
        set(value) {
            field = value
            synchronized(allocators) { allocators.values.forEach { it.playerNeedsData = value } }
        }

    override fun getAllocator(playerId: PlayerId): Allocator = synchronized(allocators) {
        allocators.getOrPut(playerId) {
            BoundedAllocator(delegate.getAllocator(playerId), capBytes)
                .also { it.playerNeedsData = playerNeedsData }
        }
    }

    override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>,
    ) = delegate.onTracksSelected(parameters, trackGroups, trackSelections)

    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)

    override fun onReleased(playerId: PlayerId) {
        delegate.onReleased(playerId)
        synchronized(allocators) { allocators.remove(playerId) }
    }

    override fun getBackBufferDurationUs(playerId: PlayerId): Long =
        delegate.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean =
        delegate.retainBackBufferFromKeyframe(playerId)

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean =
        delegate.shouldContinueLoading(parameters)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
        delegate.shouldStartPlayback(parameters)

    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean =
        delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)
}

/**
 * The caller must set [HeapBoundedLoadControl.playerNeedsData] from its player's
 * `onPlaybackStateChanged`: without it, a stalled player's only way past the cap is a cancelled
 * load (see [BoundedAllocator]).
 */
internal fun heapBoundedLoadControl(
    maxHeapBytes: Long = Runtime.getRuntime().maxMemory(),
): HeapBoundedLoadControl =
    HeapBoundedLoadControl(
        delegate = DefaultLoadControl.Builder()
            .setTargetBufferBytes(videoBufferBudgetBytes(maxHeapBytes))
            .build(),
        capBytes = allocationCapBytes(maxHeapBytes),
    )

internal fun videoBufferBudgetBytes(maxHeapBytes: Long): Int =
    minOf(maxHeapBytes / 3, MEDIA3_DEFAULT_BUFFER_BYTES.toLong()).toInt()

/**
 * Where loads wait, keeping half the heap for the rest of the app; [BoundedAllocator] has the two
 * exceptions.
 */
internal fun allocationCapBytes(maxHeapBytes: Long): Long = maxHeapBytes / 2

internal const val MEDIA3_DEFAULT_BUFFER_BYTES =
    DefaultLoadControl.DEFAULT_VIDEO_BUFFER_SIZE + DefaultLoadControl.DEFAULT_AUDIO_BUFFER_SIZE
