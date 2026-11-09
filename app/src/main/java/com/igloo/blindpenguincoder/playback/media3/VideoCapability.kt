@file:androidx.annotation.OptIn(UnstableApi::class)

package com.igloo.blindpenguincoder.playback.media3

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import com.igloo.blindpenguincoder.playback.model.HLS_VIDEO_CODEC_HEVC
import com.igloo.blindpenguincoder.playback.model.HevcDecoderInfo
import com.igloo.blindpenguincoder.playback.model.canDeclareHevcCopy

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

/**
 * The `video_codecs` value this device's HLS manifests declare: [HLS_VIDEO_CODEC_HEVC] when
 * [canDeclareHevcCopy] holds for its decoders and display, null otherwise. Read at every engine
 * start, so a display that gains or loses HDR10 (another TV on the HDMI port) is honored by the
 * next playback. The decoders are the ones Media3's renderer chooses from, classified the way
 * it classifies them. A decoder whose capabilities cannot be read is skipped rather than
 * failing the whole probe, and any other failure reads as "no": the server then transcodes, as
 * it would without the declaration.
 */
fun deviceHlsVideoCodecs(context: Context): String? = runCatching {
    val decoders = MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_H265, false, false)
        .mapNotNull { runCatching { hevcDecoderInfo(it) }.getOrNull() }
    HLS_VIDEO_CODEC_HEVC.takeIf { canDeclareHevcCopy(decoders, displaySupportsHdr10(context)) }
}.getOrNull()

/**
 * Media3's `hardwareAccelerated` comes from the platform on API 29+; before it, Media3 infers
 * it from the codec name (Google's, FFmpeg's and vendor `sw` decoders count as software), so
 * this check and the renderer agree on what a software decoder is.
 */
private fun hevcDecoderInfo(info: MediaCodecInfo): HevcDecoderInfo {
    val capabilities = requireNotNull(info.capabilities)
    return HevcDecoderInfo(
        hardware = info.hardwareAccelerated && !info.softwareOnly,
        // A decoder that lists HDR10+ also lists one of these, and that constant needs API 29.
        main10 = capabilities.profileLevels.any {
            it.profile == CodecProfileLevel.HEVCProfileMain10 ||
                it.profile == CodecProfileLevel.HEVCProfileMain10HDR10
        },
        uhd = capabilities.videoCapabilities?.isSizeSupported(UHD_WIDTH, UHD_HEIGHT) == true,
    )
}

private fun displaySupportsHdr10(context: Context): Boolean {
    val display = context.getSystemService(DisplayManager::class.java)
        ?.getDisplay(Display.DEFAULT_DISPLAY)
        ?: return false
    val hdrTypes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        display.mode.supportedHdrTypes
    } else {
        @Suppress("DEPRECATION")
        display.hdrCapabilities?.supportedHdrTypes ?: intArrayOf()
    }
    return Display.HdrCapabilities.HDR_TYPE_HDR10 in hdrTypes
}

private const val UHD_WIDTH = 3840
private const val UHD_HEIGHT = 2160
