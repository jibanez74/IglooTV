package com.igloo.blindpenguincoder.playback.model

/**
 * The server-side audio conversions the backend offers on personal HLS sessions, as the
 * `audio_codec`/`audio_channels` manifest query pair. The two values only exist together —
 * the server treats a lone parameter as a 400 — and the server owns everything else about
 * the profile (bitrate, 48 kHz sample rate, downmix layout). `audioChannels` is a ceiling:
 * a stereo source stays stereo, a 7.1 source becomes 5.1.
 */
enum class HlsAudioProfile(val audioCodec: String, val audioChannels: Int) {
    /** AC-3 5.1 — the target for multichannel AAC sources. */
    DolbyDigital("ac3", 6),
    /** E-AC-3 5.1 — the target for DTS-family sources. */
    DolbyDigitalPlus("eac3", 6),
}

/**
 * Whether this source audio is known to misbehave on the target TV/eARC chain: DTS-family
 * (no passthrough downstream, and a PCM decode arrives as 2.0) and multichannel AAC (decodes
 * to multichannel PCM, same 2.0 fate). Deliberately nothing else — TrueHD/Atmos and friends
 * pass through fine and must stay untouched; AAC with unknown or ≤2 channels is provably
 * safe to leave alone, and refusing to guess keeps the server's channel-metadata 422 out of
 * reach.
 */
fun isUnreliableHlsAudio(codec: String?, channels: Int?): Boolean {
    val name = codec?.let(::normalizedCodec) ?: return false
    return name == "dts" || (name == "aac" && channels != null && channels > 2)
}

/**
 * The conversion an HLS session should request for [track]'s audio, or null for the legacy
 * behavior. The mapping is fixed by product decision, not probed: DTS-family → E-AC-3 5.1,
 * multichannel AAC → AC-3 5.1.
 */
fun hlsAudioConversionFor(track: PlayableAudioTrack?): HlsAudioProfile? {
    if (track == null || !isUnreliableHlsAudio(track.codec, track.channels)) return null
    return when (normalizedCodec(track.codec)) {
        "dts" -> HlsAudioProfile.DolbyDigitalPlus
        else -> HlsAudioProfile.DolbyDigital
    }
}
