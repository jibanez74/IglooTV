// DefaultLoadControl and LoadControl are part of Media3's unstable surface.
@file:androidx.annotation.OptIn(UnstableApi::class)

package com.igloo.blindpenguincoder.playback.media3

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl

/**
 * Media3 buffers media in byte arrays on the Java heap, loading until about 144 MB of video and
 * audio are buffered by default. A high-bitrate remux reaches that within half a minute; on a
 * 192 MB heap that left nothing for the rest of the app, and the next allocation (a subtitle
 * bitmap, the player UI) crashed it. Loading now stops at a third of the heap, and never later
 * than Media3's default.
 *
 * That is where loading stops, not a cap on the allocator: a load in progress finishes, and a
 * subtitle stream reads on until 1 MiB of it has arrived since it was last continued, which
 * stops once the target is reached too (see [SubtitleStreamPeriod]). Both are small next to the
 * two thirds of the heap left over, and the large heap the manifest asks for is the margin on the
 * Shield.
 */
internal fun heapBoundedLoadControl(): LoadControl =
    DefaultLoadControl.Builder()
        .setTargetBufferBytes(videoBufferBudgetBytes(Runtime.getRuntime().maxMemory()))
        .build()

internal fun videoBufferBudgetBytes(maxHeapBytes: Long): Int =
    minOf(maxHeapBytes / 3, MEDIA3_DEFAULT_BUFFER_BYTES.toLong()).toInt()

internal const val MEDIA3_DEFAULT_BUFFER_BYTES =
    DefaultLoadControl.DEFAULT_VIDEO_BUFFER_SIZE + DefaultLoadControl.DEFAULT_AUDIO_BUFFER_SIZE
