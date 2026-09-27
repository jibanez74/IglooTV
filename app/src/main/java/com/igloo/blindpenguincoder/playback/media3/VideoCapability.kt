package com.igloo.blindpenguincoder.playback.media3

import android.media.MediaCodecList

/**
 * Whether any decoder on this device accepts the video MIME type. Consulted only by the
 * pre-flight gate, and deliberately by type alone: a size or profile check would second-guess
 * Media3's own decoder selection and refuse files it would in fact play.
 */
fun deviceCanDecodeVideoMime(mimeType: String): Boolean = runCatching {
    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
        !info.isEncoder && info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
    }
}.getOrDefault(false)
