// DefaultLoadControl and LoadControl are part of Media3's unstable surface.
@file:androidx.annotation.OptIn(UnstableApi::class)

package com.igloo.blindpenguincoder.playback.media3

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl

/**
 * Media3 buffers media in byte arrays on the Java heap, by default up to about 144 MB of video
 * and audio. A high-bitrate remux reaches that within half a minute; on a 192 MB heap that left
 * nothing for the rest of the app, and the next allocation (a subtitle bitmap, the player UI)
 * crashed it. The buffer gets a third of the heap, and never more than Media3's default.
 */
internal fun heapBoundedLoadControl(maxHeapBytes: Long = Runtime.getRuntime().maxMemory()): LoadControl =
    DefaultLoadControl.Builder()
        .setTargetBufferBytes(videoBufferBudgetBytes(maxHeapBytes))
        .build()

internal fun videoBufferBudgetBytes(maxHeapBytes: Long): Int =
    minOf(maxHeapBytes / 3, MEDIA3_DEFAULT_BUFFER_BYTES.toLong()).toInt()

internal const val MEDIA3_DEFAULT_BUFFER_BYTES =
    DefaultLoadControl.DEFAULT_VIDEO_BUFFER_SIZE + DefaultLoadControl.DEFAULT_AUDIO_BUFFER_SIZE
