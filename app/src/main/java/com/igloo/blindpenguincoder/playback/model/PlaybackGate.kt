package com.igloo.blindpenguincoder.playback.model

import com.igloo.blindpenguincoder.data.model.PlaybackMode
import java.util.Locale

/**
 * The pre-flight decision made before the engine is even constructed: can this play request
 * start honestly on this device? Only Direct play can be refused. Its video must have a decoder:
 * Media3 does not fail on a video track nothing can decode, it quietly leaves it unselected and
 * plays the sound over a black screen. Its audio must be audible: standard Media3 cannot
 * software-decode TrueHD or DTS, so on a TV without passthrough for the selected track the
 * movie would start with silence, which is worse than a clear refusal naming the codec. HLS
 * modes always proceed — the backend guarantees the mux is playable, transcoding video it
 * cannot copy. Tracks covered by [isUnreliableHlsAudio] pass the audio rule: for those (and
 * only those) the engine substitutes a Remux session with server-side AC-3/E-AC-3 conversion
 * and surfaces the switch in the quality menu, so a refusal would block a request that can in
 * fact play well. Everything else keeps the rule that the gate never substitutes a different
 * mode: refusing with guidance keeps the user's choice theirs. Capability is injected so the
 * rules stay JVM-pure and testable.
 */
sealed interface PlaybackGateResult {
    data object Proceed : PlaybackGateResult
    data class Blocked(val message: String) : PlaybackGateResult
}

fun evaluatePlaybackGate(
    mode: PlaybackMode,
    videoCodec: String?,
    audioCodec: String?,
    audioCodecProfile: String?,
    audioChannels: Int?,
    audioLabel: String?,
    canPlayVideoMime: (String) -> Boolean,
    canPlayAudioMime: (String) -> Boolean,
): PlaybackGateResult {
    if (mode != PlaybackMode.Direct) return PlaybackGateResult.Proceed
    // Unknown or unmapped codecs proceed: the gate refuses only what it can prove unplayable,
    // and the player's own error surface catches whatever it could not foresee.
    val videoMimeType = videoCodec?.let(::videoCodecToMimeType)
    if (videoMimeType != null && !canPlayVideoMime(videoMimeType)) {
        return PlaybackGateResult.Blocked(
            "This TV can't play this title's ${videoCodecDisplayName(videoCodec)} video — it " +
                "has no decoder for it. Switch Playback Settings to one of the converted " +
                "qualities.",
        )
    }
    // Covered by the automatic Remux conversion — never refused, regardless of capability.
    if (isUnreliableHlsAudio(audioCodec, audioChannels)) return PlaybackGateResult.Proceed
    val mimeType = audioCodecToMimeType(audioCodec ?: return PlaybackGateResult.Proceed, audioCodecProfile)
        ?: return PlaybackGateResult.Proceed
    if (canPlayAudioMime(mimeType)) return PlaybackGateResult.Proceed
    val track = audioLabel?.let { " ($it)" }.orEmpty()
    return PlaybackGateResult.Blocked(
        "This TV can't play this title's ${audioCodecDisplayName(audioCodec, audioCodecProfile)} " +
            "audio track$track — it has no decoder for it and no compatible sound system is " +
            "connected. Try a different audio track, or switch Playback Settings to " +
            "\"${playbackModeLabel(PlaybackMode.Remux)}\".",
    )
}

/**
 * ffprobe video codec names → Media3 video MIME types, as string constants so this file stays
 * JVM-pure. Only codecs whose support genuinely varies by device matter here — every TV decodes
 * H.264. The MS-MPEG-4 variants are what old DivX 3 AVI files carry: Media3 extracts them, yet
 * no Android device ships a decoder for them.
 */
internal fun videoCodecToMimeType(codec: String): String? =
    when (codec.trim().lowercase(Locale.US)) {
        "hevc" -> "video/hevc"
        "vp9" -> "video/x-vnd.on2.vp9"
        "av1" -> "video/av01"
        "mpeg2video" -> "video/mpeg2"
        "mpeg4" -> "video/mp4v-es"
        "msmpeg4v2" -> "video/mp42"
        "msmpeg4v3" -> "video/mp43"
        "vc1" -> "video/wvc1"
        else -> null
    }

/** The video codec as a person would name it, for the gate's refusal. */
internal fun videoCodecDisplayName(codec: String): String =
    when (val name = codec.trim().lowercase(Locale.US)) {
        "hevc" -> "HEVC"
        "vp9" -> "VP9"
        "av1" -> "AV1"
        "mpeg2video" -> "MPEG-2"
        "mpeg4" -> "MPEG-4 Part 2"
        "msmpeg4v2" -> "DivX 2 (MS-MPEG-4)"
        "msmpeg4v3" -> "DivX 3 (MS-MPEG-4)"
        "vc1" -> "VC-1"
        else -> name.uppercase(Locale.US)
    }

/**
 * ffprobe codec names → Media3 audio MIME types, as string constants so this file stays
 * JVM-pure. Only codecs whose support genuinely varies by device matter here; an unmapped
 * codec returns null and the gate lets it through.
 */
internal fun audioCodecToMimeType(codec: String, profile: String?): String? {
    val name = codec.trim().lowercase(Locale.US)
    val profileName = profile?.lowercase(Locale.US).orEmpty()
    return when {
        name == "truehd" -> "audio/true-hd"
        name == "ac3" -> "audio/ac3"
        name == "eac3" ->
            if (profileName.contains("joc") || profileName.contains("atmos")) {
                "audio/eac3-joc"
            } else {
                "audio/eac3"
            }
        name == "dts" -> when {
            profileName.contains("dts:x") -> "audio/vnd.dts.uhd;profile=p2"
            profileName.contains("hd") -> "audio/vnd.dts.hd"
            else -> "audio/vnd.dts"
        }
        name == "aac" -> "audio/mp4a-latm"
        name == "mp3" -> "audio/mpeg"
        name == "mp2" -> "audio/mpeg-L2"
        name == "flac" -> "audio/flac"
        name == "opus" -> "audio/opus"
        name == "vorbis" -> "audio/vorbis"
        name.startsWith("pcm_") -> "audio/raw"
        else -> null
    }
}

/** The codec as a person would name it — the gate's refusal must read, not decode. */
internal fun audioCodecDisplayName(codec: String, profile: String?): String {
    val name = codec.trim().lowercase(Locale.US)
    val profileName = profile?.lowercase(Locale.US).orEmpty()
    return when {
        name == "truehd" -> "Dolby TrueHD"
        name == "ac3" -> "Dolby Digital"
        name == "eac3" ->
            if (profileName.contains("joc") || profileName.contains("atmos")) {
                "Dolby Digital Plus with Atmos"
            } else {
                "Dolby Digital Plus"
            }
        name == "dts" -> when {
            profileName.contains("dts:x") -> "DTS:X"
            profileName.contains("hd") -> "DTS-HD"
            else -> "DTS"
        }
        else -> codec.trim().uppercase(Locale.US)
    }
}
