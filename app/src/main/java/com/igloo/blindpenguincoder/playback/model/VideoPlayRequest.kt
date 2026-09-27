package com.igloo.blindpenguincoder.playback.model

import com.igloo.blindpenguincoder.data.model.PlaybackMode
import kotlinx.serialization.Serializable

/**
 * Everything the player screen needs to start one movie or TV episode, assembled by the
 * launching screen from the title's metadata, its technical details, the watch progress, and the
 * session's playback selection.
 * Track choices travel as type-relative indexes — the Nth audio/subtitle stream in
 * `stream_index` order — because that ordering is what survives everywhere downstream: it is
 * the demuxed container's track-group order under direct play, and the backend's
 * `audio_track`/`trackIndex` ordinal for HLS sessions and sideloaded subtitles.
 */
data class VideoPlayRequest(
    val media: PlaybackMediaRef,
    val title: String,
    /** The details page's poster, re-used as the MediaSession artwork. Null = title only. */
    val posterUrl: String?,
    val mimeType: String,
    val mode: PlaybackMode,
    /** The primary video stream's ffprobe codec; the capability gate reads it. Null = unknown. */
    val videoCodec: String? = null,
    /** Null = the container's default track. */
    val audioTypeIndex: Int?,
    /** Null = subtitles off. */
    val subtitleTypeIndex: Int?,
    /** All audio streams in `stream_index` order; list position is the type index. */
    val audioTracks: List<PlayableAudioTrack> = emptyList(),
    /**
     * All subtitle streams in `stream_index` order, image-based rows included — their position
     * is the backend's `trackIndex` ordinal, so filtering here would shift every URL after them.
     */
    val subtitleTracks: List<PlayableSubtitleTrack> = emptyList(),
    /** Null = nothing to resume; the player then starts from the beginning without asking. */
    val resumeAtSec: Double?,
    val durationSec: Double?,
    /** Ascending by start time. Empty when the file carries no chapter metadata. */
    val chapters: List<PlaybackChapter> = emptyList(),
) {
    /**
     * The audio ordinal with the default resolved: HLS sessions must name a concrete
     * `audio_track` whenever the movie has audio. Null only for a video-only movie.
     */
    val effectiveAudioTypeIndex: Int?
        get() = audioTypeIndex
            ?: audioTracks.indexOfFirst { it.isDefault }.takeIf { it >= 0 }
            ?: if (audioTracks.isNotEmpty()) 0 else null

    /** The track behind [effectiveAudioTypeIndex]; the capability gate reads its codec. */
    val selectedAudioTrack: PlayableAudioTrack?
        get() = effectiveAudioTypeIndex?.let(audioTracks::getOrNull)
}

/**
 * Whether the chosen subtitle ordinal can actually render on a source in the given mode. The
 * chosen ordinal is remembered even where it cannot render — an HLS session only serves the
 * text streams as sideloaded VTT — so the player must disable the text renderer outright
 * rather than leave Media3 free to auto-select an unrelated track. Direct trusts the container
 * over [VideoPlayRequest.subtitleTracks]: the wire list can be degraded or empty while the
 * demuxed file still carries the stream.
 */
internal fun VideoPlayRequest.subtitleRenderableInMode(typeIndex: Int?, hls: Boolean): Boolean =
    when {
        typeIndex == null -> false
        !hls -> true
        else -> subtitleTracks.getOrNull(typeIndex)?.imageBased == false
    }

/** One audio stream as the player needs it: menu label plus the capability gate's codec facts. */
@Serializable
data class PlayableAudioTrack(
    val label: String,
    val codec: String,
    val codecProfile: String? = null,
    val channels: Int? = null,
    val isDefault: Boolean = false,
)

/** One subtitle stream; image-based tracks exist only for direct play and ordinal stability. */
@Serializable
data class PlayableSubtitleTrack(
    val label: String,
    val imageBased: Boolean = false,
)

/**
 * One chapter as the player needs it. The title travels raw — file metadata leaves it blank
 * often enough that the "Chapter N" fallback is display logic, not data. Serializable so the
 * whole list rides one saved-state slot as JSON.
 */
@Serializable
data class PlaybackChapter(
    val title: String,
    val startTimeSec: Double,
)
