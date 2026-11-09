package com.igloo.blindpenguincoder.playback.model

/** The `video_codecs` value that lets a Remux copy HEVC instead of transcoding it. */
const val HLS_VIDEO_CODEC_HEVC = "hevc"

/** One HEVC decoder as the copy decision sees it; the Android adapter fills it in. */
data class HevcDecoderInfo(
    /** A hardware decoder; software HEVC cannot keep up with a 4K copy on a TV. */
    val hardware: Boolean,
    /** Decodes HEVC Main 10, the profile of every HDR10 and HLG source. */
    val main10: Boolean,
    /** Accepts 3840x2160 frames, the size most copied HEVC sources have. */
    val uhd: Boolean,
)

/**
 * Whether this device may declare `video_codecs=hevc`. The server then copies any HEVC Main or
 * Main 10 4:2:0 source untouched, HDR signalling included, up to its full size. So unlike the
 * Direct play gate, which refuses only what it can prove unplayable, this check is strict: a
 * false positive is a 4K HDR stream the device stutters on or shows in washed-out colors,
 * while a false negative only keeps the server's H.264 transcode, the behavior before the
 * declaration existed. It needs one hardware decoder with Main 10 at 3840x2160, and a display
 * that renders HDR10, the transfer of nearly every HDR title, so a copied HDR10 picture is not
 * shown with the wrong one. The rarer HLG sources are copied too and rely on the device's own
 * handling when the display lacks HLG.
 */
fun canDeclareHevcCopy(decoders: List<HevcDecoderInfo>, displaySupportsHdr10: Boolean): Boolean =
    displaySupportsHdr10 && decoders.any { it.hardware && it.main10 && it.uhd }
