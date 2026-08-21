package com.igloo.blindpenguincoder.playback.media3

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.audio.AudioCapabilities

/** The player's audio identity: media usage, movie content, and audio-focus handling. */
internal val moviePlaybackAudioAttributes: AudioAttributes = AudioAttributes.Builder()
    .setUsage(C.USAGE_MEDIA)
    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
    .build()

/**
 * Whether this device can make the selected audio track audible at all: either the current
 * output route passes the encoding through (HDMI/ARC to a receiver or the TV's own decoder),
 * or a local decoder exists. Consulted only by the pre-flight gate — playback itself lets
 * `DefaultAudioSink` negotiate passthrough on its own, and nothing here ever narrows the
 * output configuration.
 */
fun deviceCanPlayAudioMime(context: Context, mimeType: String, channelCount: Int?): Boolean {
    if (mimeType == "audio/raw") return true
    val channels = channelCount?.takeIf { it > 0 } ?: 6
    val format = Format.Builder()
        .setSampleMimeType(mimeType)
        .setChannelCount(channels)
        .setSampleRate(48_000)
        .build()
    val passthrough = AudioCapabilities
        .getCapabilities(context, moviePlaybackAudioAttributes, null)
        .isPassthroughPlaybackSupported(format, moviePlaybackAudioAttributes)
    return passthrough || hasDecoder(mimeType, channels)
}

private fun hasDecoder(mimeType: String, channels: Int): Boolean {
    // MediaFormat wants the bare type; Media3's DTS:X constant carries a ;profile suffix.
    val bareMime = mimeType.substringBefore(';')
    val mediaFormat = MediaFormat.createAudioFormat(bareMime, 48_000, channels)
    return runCatching {
        MediaCodecList(MediaCodecList.ALL_CODECS).findDecoderForFormat(mediaFormat) != null
    }.getOrDefault(false)
}
