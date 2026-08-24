package com.igloo.blindpenguincoder.playback.model

import com.igloo.blindpenguincoder.data.model.PlaybackMode

/**
 * Everything the player screen needs to start one movie, assembled by the details screen from
 * the movie, its technical details, the watch progress, and the session's playback selection.
 * Track choices travel as type-relative indexes — the Nth audio/subtitle stream in
 * `stream_index` order — because that ordering is what survives into the demuxed container,
 * where ExoPlayer exposes the same streams as the Nth track group of that type.
 */
data class MoviePlayRequest(
    val movieId: Long,
    val title: String,
    /** The details page's poster, re-used as the MediaSession artwork. Null = title only. */
    val posterUrl: String?,
    val mimeType: String,
    val mode: PlaybackMode,
    /** Null = the container's default track. */
    val audioTypeIndex: Int?,
    /** Null = subtitles off. */
    val subtitleTypeIndex: Int?,
    /** ffprobe codec of the selected audio track, for the pre-flight capability gate. */
    val audioCodec: String?,
    val audioCodecProfile: String?,
    /** Channel count of the selected audio track; passthrough support can depend on it. */
    val audioChannels: Int?,
    /** "English · 7.1 surround" — the gate's error message names what could not play. */
    val audioLabel: String?,
    /** Null = nothing to resume; the player then starts from the beginning without asking. */
    val resumeAtSec: Double?,
    val durationSec: Double?,
)
