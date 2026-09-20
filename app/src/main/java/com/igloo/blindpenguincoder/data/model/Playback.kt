package com.igloo.blindpenguincoder.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Playback mode: direct stream or one of the server's HLS profiles. */
@Serializable
enum class PlaybackMode {
    @SerialName("direct") Direct,
    @SerialName("remux") Remux,
    @SerialName("2160p_16mbps") P2160Mbps16,
    @SerialName("1080p_8mbps") P1080Mbps8,
    @SerialName("1080p_6mbps") P1080Mbps6,
    @SerialName("1080p_4mbps") P1080Mbps4,
    @SerialName("720p_3mbps") P720Mbps3,
}

/**
 * The `{profile}` path segment of the HLS endpoints, or null for direct play. The ids mirror
 * the server's fixed `HLSAllowedProfiles` list, which is also the API path enum in the contract.
 */
val PlaybackMode.hlsProfileId: String?
    get() = when (this) {
        PlaybackMode.Direct -> null
        PlaybackMode.Remux -> "remux"
        PlaybackMode.P2160Mbps16 -> "2160p_16mbps"
        PlaybackMode.P1080Mbps8 -> "1080p_8mbps"
        PlaybackMode.P1080Mbps6 -> "1080p_6mbps"
        PlaybackMode.P1080Mbps4 -> "1080p_4mbps"
        PlaybackMode.P720Mbps3 -> "720p_3mbps"
    }
