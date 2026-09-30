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

/**
 * The gate over a built request: its video, and [track] — the request's selected track unless
 * the caller (the engine, mid-session) is playing a different one — in [mode].
 */
fun evaluatePlaybackGate(
    request: VideoPlayRequest,
    canPlayVideoMime: (mimeType: String) -> Boolean,
    canPlayAudioMime: (mimeType: String, channels: Int?) -> Boolean,
    mode: PlaybackMode = request.mode,
    track: PlayableAudioTrack? = request.selectedAudioTrack,
): PlaybackGateResult = evaluatePlaybackGate(
    mode = mode,
    videoCodec = request.videoCodec,
    audioCodec = track?.codec,
    audioCodecProfile = track?.codecProfile,
    audioChannels = track?.channels,
    audioLabel = track?.label,
    canPlayVideoMime = canPlayVideoMime,
    canPlayAudioMime = { mime -> canPlayAudioMime(mime, track?.channels) },
)

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

/** An ffprobe codec name as the codec tables key it. */
internal fun normalizedCodec(codec: String): String = codec.trim().lowercase(Locale.US)

/** A codec's Media3 MIME type, and its name as a person would say it in a refusal. */
private class CodecNames(val mimeType: String, val displayName: String)

/**
 * ffprobe video codec names → Media3 video MIME types, as string constants so this file stays
 * JVM-pure. Only codecs whose support genuinely varies by device matter here — every TV decodes
 * H.264. The MS-MPEG-4 variants are what old DivX 3 AVI files carry: Media3 extracts them, yet
 * no Android device ships a decoder for them.
 */
private val VIDEO_CODECS = mapOf(
    "hevc" to CodecNames("video/hevc", "HEVC"),
    "vp9" to CodecNames("video/x-vnd.on2.vp9", "VP9"),
    "av1" to CodecNames("video/av01", "AV1"),
    "mpeg2video" to CodecNames("video/mpeg2", "MPEG-2"),
    "mpeg4" to CodecNames("video/mp4v-es", "MPEG-4 Part 2"),
    "msmpeg4v2" to CodecNames("video/mp42", "DivX 2 (MS-MPEG-4)"),
    "msmpeg4v3" to CodecNames("video/mp43", "DivX 3 (MS-MPEG-4)"),
    "vc1" to CodecNames("video/wvc1", "VC-1"),
)

internal fun videoCodecToMimeType(codec: String): String? =
    VIDEO_CODECS[normalizedCodec(codec)]?.mimeType

/** The video codec as a person would name it, for the gate's refusal. */
internal fun videoCodecDisplayName(codec: String): String {
    val name = normalizedCodec(codec)
    return VIDEO_CODECS[name]?.displayName ?: name.uppercase(Locale.US)
}

/**
 * ffprobe audio codec names → Media3 audio MIME types, as string constants so this file stays
 * JVM-pure. Only codecs whose support genuinely varies by device matter here; an unmapped
 * codec returns null and the gate lets it through.
 */
private fun audioCodecNames(codec: String, profile: String?): CodecNames? {
    val profileName = profile?.lowercase(Locale.US).orEmpty()
    return when (val name = normalizedCodec(codec)) {
        "truehd" -> CodecNames("audio/true-hd", "Dolby TrueHD")
        "ac3" -> CodecNames("audio/ac3", "Dolby Digital")
        "eac3" ->
            if (profileName.contains("joc") || profileName.contains("atmos")) {
                CodecNames("audio/eac3-joc", "Dolby Digital Plus with Atmos")
            } else {
                CodecNames("audio/eac3", "Dolby Digital Plus")
            }
        "dts" -> when {
            profileName.contains("dts:x") -> CodecNames("audio/vnd.dts.uhd;profile=p2", "DTS:X")
            profileName.contains("hd") -> CodecNames("audio/vnd.dts.hd", "DTS-HD")
            else -> CodecNames("audio/vnd.dts", "DTS")
        }
        "aac" -> CodecNames("audio/mp4a-latm", "AAC")
        "mp3" -> CodecNames("audio/mpeg", "MP3")
        "mp2" -> CodecNames("audio/mpeg-L2", "MP2")
        "flac" -> CodecNames("audio/flac", "FLAC")
        "opus" -> CodecNames("audio/opus", "OPUS")
        "vorbis" -> CodecNames("audio/vorbis", "VORBIS")
        else -> name.takeIf { it.startsWith("pcm_") }
            ?.let { CodecNames("audio/raw", it.uppercase(Locale.US)) }
    }
}

internal fun audioCodecToMimeType(codec: String, profile: String?): String? =
    audioCodecNames(codec, profile)?.mimeType

/** The codec as a person would name it — the gate's refusal must read, not decode. */
internal fun audioCodecDisplayName(codec: String, profile: String?): String =
    audioCodecNames(codec, profile)?.displayName ?: normalizedCodec(codec).uppercase(Locale.US)
