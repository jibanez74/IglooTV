package com.igloo.blindpenguincoder.playback.model

import com.igloo.blindpenguincoder.data.model.PlaybackMode
import java.util.Locale

/**
 * The pre-flight decision made before the engine is even constructed: can this play request
 * start honestly on this device? Direct play is the only implemented mode, and standard Media3
 * cannot software-decode TrueHD or DTS — on a TV without passthrough for the selected track the
 * movie would start with silence, which is worse than a clear refusal naming the codec.
 * Capability is injected so the rules stay JVM-pure and testable.
 */
sealed interface PlaybackGateResult {
    data object Proceed : PlaybackGateResult
    data class Blocked(val message: String) : PlaybackGateResult
}

fun evaluatePlaybackGate(
    mode: PlaybackMode,
    audioCodec: String?,
    audioCodecProfile: String?,
    audioLabel: String?,
    canPlayMime: (String) -> Boolean,
): PlaybackGateResult {
    if (mode != PlaybackMode.Direct) {
        return PlaybackGateResult.Blocked(
            "\"${playbackModeLabel(mode)}\" streaming isn't available on this TV app yet. " +
                "Set Playback Settings to \"${playbackModeLabel(PlaybackMode.Direct)}\" " +
                "to play this movie.",
        )
    }
    // Unknown or unmapped codecs proceed: the gate refuses only what it can prove unplayable,
    // and the player's own error surface catches whatever it could not foresee.
    val mimeType = audioCodecToMimeType(audioCodec ?: return PlaybackGateResult.Proceed, audioCodecProfile)
        ?: return PlaybackGateResult.Proceed
    if (canPlayMime(mimeType)) return PlaybackGateResult.Proceed
    val track = audioLabel?.let { " ($it)" }.orEmpty()
    return PlaybackGateResult.Blocked(
        "This TV can't play this movie's ${audioCodecDisplayName(audioCodec, audioCodecProfile)} " +
            "audio track$track — it has no decoder for it and no compatible sound system is " +
            "connected. Try a different audio track, or " +
            "\"${playbackModeLabel(PlaybackMode.Remux)}\" when it becomes available.",
    )
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
