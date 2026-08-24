package com.igloo.blindpenguincoder.feature.movies

import com.igloo.blindpenguincoder.data.model.Movie
import com.igloo.blindpenguincoder.data.model.MovieTechnicalDetailsData
import com.igloo.blindpenguincoder.data.model.MovieWatchProgress
import com.igloo.blindpenguincoder.playback.model.MoviePlayRequest
import com.igloo.blindpenguincoder.playback.model.PlaybackChapter

/**
 * Assembles the player's start request from the details screen's fragments. The effective track
 * choice comes from [playbackSettingsUi] — the same resolution the Playback Settings dialog
 * renders — so what the user was shown and what plays can never disagree.
 */
internal fun buildMoviePlayRequest(
    movie: Movie,
    posterUrl: String?,
    technical: MovieTechnicalDetailsData?,
    progress: MovieWatchProgress?,
    selection: PlaybackSelection,
): MoviePlayRequest {
    val settings = playbackSettingsUi(
        audioStreams = technical?.audioStreams,
        subtitles = technical?.subtitles,
        selection = selection,
    )

    val audioStreams = technical?.audioStreams.orEmpty()
    val audioIndexInWireOrder = audioStreams.indexOfFirst { it.id == settings.selectedAudioId }
        .takeIf { it >= 0 }
    val effectiveAudio = audioIndexInWireOrder?.let(audioStreams::get)

    return MoviePlayRequest(
        movieId = movie.id,
        title = movie.title,
        posterUrl = posterUrl,
        mimeType = movie.mimeType,
        mode = settings.selectedMode,
        audioTypeIndex = typeIndexOf(settings.selectedAudioId, audioStreams.map { it.id to it.streamIndex }),
        subtitleTypeIndex = typeIndexOf(
            settings.selectedSubtitleId,
            technical?.subtitles.orEmpty().map { it.id to it.streamIndex },
        ),
        audioCodec = effectiveAudio?.codec,
        audioCodecProfile = effectiveAudio?.codecProfile?.orNull(),
        audioChannels = effectiveAudio?.channels?.toInt(),
        audioLabel = effectiveAudio?.let { audioTrackLabel(it, audioIndexInWireOrder) },
        resumeAtSec = resumePositionSec(progress),
        durationSec = progress?.durationSec ?: movie.duration?.orNull(),
        // Sorted here: the player's active-chapter scan and "Chapter N" numbering assume
        // ascending start times, and the wire list is not trusted to arrive sorted.
        chapters = technical?.chapters.orEmpty()
            .sortedBy { it.startTime }
            .map { PlaybackChapter(title = it.title, startTimeSec = it.startTime.toDouble()) },
    )
}

/**
 * The wire id of an effective choice, as the type-relative index [MoviePlayRequest] carries:
 * the Nth stream of that type in `stream_index` order — the ordering that survives demuxing,
 * where ExoPlayer exposes the same streams as the Nth track group of the type. The wire list is
 * not trusted to arrive sorted. Null in, null out: no explicit track, or subtitles off.
 */
private fun typeIndexOf(selectedId: Long?, idsWithStreamIndex: List<Pair<Long, Long>>): Int? {
    selectedId ?: return null
    return idsWithStreamIndex
        .sortedBy { (_, streamIndex) -> streamIndex }
        .indexOfFirst { (id, _) -> id == selectedId }
        .takeIf { it >= 0 }
}

/**
 * The saved position, when it is worth resuming from: at least [RESUME_MIN_SEC] in and short of
 * [RESUME_MAX_RATIO] of the runtime (the server itself flips to watched at 98%). Null means
 * "start from the beginning without asking". One definition serves both the details screen's
 * progress strip and the player's resume prompt.
 */
internal fun resumePositionSec(progress: MovieWatchProgress?): Double? {
    val progressSec = progress?.progressSec ?: return null
    val durationSec = progress.durationSec ?: return null
    if (progressSec < RESUME_MIN_SEC || durationSec <= 0) return null
    if (progressSec / durationSec >= RESUME_MAX_RATIO) return null
    return progressSec
}

internal const val RESUME_MIN_SEC = 30.0
internal const val RESUME_MAX_RATIO = 0.98
