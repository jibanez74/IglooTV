package com.igloo.blindpenguincoder.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Server HLS transcode profiles. */
@Serializable
enum class HlsProfile {
    @SerialName("remux") Remux,
    @SerialName("2160p_16mbps") P2160Mbps16,
    @SerialName("1080p_8mbps") P1080Mbps8,
    @SerialName("1080p_6mbps") P1080Mbps6,
    @SerialName("1080p_4mbps") P1080Mbps4,
    @SerialName("720p_3mbps") P720Mbps3,
}

/** Playback mode: direct stream or one of the HLS profiles. */
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
