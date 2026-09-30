package com.igloo.blindpenguincoder.data.model

/**
 * Playback mode: direct stream or one of the server's HLS profiles. [hlsProfileId] is the
 * `{profile}` path segment of the HLS endpoints, or null for direct play; the ids mirror the
 * server's fixed `HLSAllowedProfiles` list, which is also the API path enum in the contract.
 */
enum class PlaybackMode(val hlsProfileId: String?) {
    Direct(null),
    Remux("remux"),
    P2160Mbps16("2160p_16mbps"),
    P1080Mbps8("1080p_8mbps"),
    P1080Mbps6("1080p_6mbps"),
    P1080Mbps4("1080p_4mbps"),
    P720Mbps3("720p_3mbps"),
}
