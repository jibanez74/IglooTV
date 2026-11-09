package com.igloo.blindpenguincoder.playback.media3

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Display
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
 * start, so a display that gains or loses HDR10 (another TV on the HDMI port, HDR turned off in
 * the system settings) is honored by the next playback. Any failure reads as "no": the server
 * then transcodes, as it would without the declaration.
 */
fun deviceHlsVideoCodecs(context: Context): String? = runCatching {
    val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { info ->
            !info.isEncoder &&
                info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) }
        }
        .map(::hevcDecoderInfo)
    HLS_VIDEO_CODEC_HEVC.takeIf { canDeclareHevcCopy(decoders, displaySupportsHdr10(context)) }
}.getOrNull()

private fun hevcDecoderInfo(info: MediaCodecInfo): HevcDecoderInfo {
    val capabilities = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
    return HevcDecoderInfo(
        hardware = isHardwareDecoder(info),
        // A decoder that lists HDR10+ also lists one of these, and that constant needs API 29.
        main10 = capabilities.profileLevels.any {
            it.profile == CodecProfileLevel.HEVCProfileMain10 ||
                it.profile == CodecProfileLevel.HEVCProfileMain10HDR10
        },
        uhd = capabilities.videoCapabilities?.isSizeSupported(UHD_WIDTH, UHD_HEIGHT) == true,
    )
}

/**
 * API 29 reports this directly. Before it, the platform's own software codecs are recognized
 * by name: Google's OMX and Codec2 implementations are the only software HEVC decoders a TV
 * ships with.
 */
private fun isHardwareDecoder(info: MediaCodecInfo): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        info.isHardwareAccelerated
    } else {
        val name = info.name.lowercase()
        !name.startsWith("omx.google.") && !name.startsWith("c2.android.")
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
    @Suppress("DEPRECATION")
    return Display.HdrCapabilities.HDR_TYPE_HDR10 in hdrTypes
}

private const val UHD_WIDTH = 3840
private const val UHD_HEIGHT = 2160
