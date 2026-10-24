package com.igloo.blindpenguincoder.feature.shows

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.flatMap
import com.igloo.blindpenguincoder.core.error.map
import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import com.igloo.blindpenguincoder.core.network.withApiBaseUrl
import com.igloo.blindpenguincoder.core.ui.formatEpisodeCode
import com.igloo.blindpenguincoder.data.model.PlaybackMode
import com.igloo.blindpenguincoder.data.model.ShowEpisodePlaybackData
import com.igloo.blindpenguincoder.data.model.ShowEpisodeTechnicalDetailsData
import com.igloo.blindpenguincoder.data.model.WatchProgress
import com.igloo.blindpenguincoder.data.repository.ShowRepository
import com.igloo.blindpenguincoder.feature.movies.PlaybackSelection
import com.igloo.blindpenguincoder.feature.movies.buildVideoPlayRequest
import com.igloo.blindpenguincoder.feature.movies.resumePositionSec
import com.igloo.blindpenguincoder.images.TmdbImageSize
import com.igloo.blindpenguincoder.images.tmdbImageUrl
import com.igloo.blindpenguincoder.playback.model.EpisodeContext
import com.igloo.blindpenguincoder.playback.model.PlaybackGateResult
import com.igloo.blindpenguincoder.playback.model.PlaybackMediaRef
import com.igloo.blindpenguincoder.playback.model.UpNextEpisode
import com.igloo.blindpenguincoder.playback.model.VideoPlayRequest
import com.igloo.blindpenguincoder.playback.model.evaluatePlaybackGate
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope

/**
 * Builds the video player's request for one episode, for every surface that starts one — Home's
 * Continue Watching rail and the show page through [EpisodePlayLauncher], and the player itself
 * when it advances to the next episode. The episode's header, technical details and the caller's
 * saved position are read in parallel; the first failed read answers without waiting out the rest.
 *
 * None of those surfaces has a Playback Settings dialog, so the request carries the defaults; when
 * the capability gate refuses Direct play of the file's video or default audio track, the request
 * falls back to Remux rather than blocking on guidance the user cannot follow from there. For video
 * the server cannot copy, it answers that Remux with a transcode. The gate's rule that it never
 * overrides a choice holds: no choice was made.
 */
class EpisodePlayPreparer(
    private val shows: ShowRepository,
    private val serverUrl: ServerUrlProvider,
    private val canPlayVideoMime: (mimeType: String) -> Boolean,
    private val canPlayAudioMime: (mimeType: String, channels: Int?) -> Boolean,
) {
    /**
     * The request for [episodeId]. [promptForResume] false starts at the saved position without
     * the Resume prompt: the control that asked already said Resume, or this is a hand-off.
     */
    suspend fun prepare(episodeId: Long, promptForResume: Boolean): ApiResult<VideoPlayRequest> =
        coroutineScope {
            val playback = async { shows.episodePlayback(episodeId) }
            val technical = async { shows.episodeTechnicalDetails(episodeId) }
            val progress = async { shows.episodeWatchProgress(episodeId) }
            playback.await().flatMap { header ->
                technical.await().flatMap { file ->
                    progress.await().withApiBaseUrl(serverUrl).map { (saved, apiBaseUrl) ->
                        toPlayRequest(episodeId, header, file, saved, apiBaseUrl, promptForResume)
                    }
                }
            }.also { prepared ->
                // The first failure answers the press; the reads still in flight are not waited out.
                if (prepared is ApiResult.Failure) coroutineContext.cancelChildren()
            }
        }

    private fun toPlayRequest(
        episodeId: Long,
        header: ShowEpisodePlaybackData,
        file: ShowEpisodeTechnicalDetailsData,
        saved: WatchProgress,
        apiBaseUrl: String,
        promptForResume: Boolean,
    ): VideoPlayRequest {
        val title = listOf(
            header.show.name,
            formatEpisodeCode(header.season.seasonNumber, header.episode.episodeNumber),
            header.episode.name,
        ).joinToString(" · ")
        // The show's poster, as its cards and page show it, re-used as session artwork.
        val posterUrl = tmdbImageUrl(
            apiBaseUrl,
            TmdbImageSize.W500,
            header.show.posterPath.orNull(),
        )
        val build = { selection: PlaybackSelection ->
            buildVideoPlayRequest(
                media = PlaybackMediaRef.Episode(episodeId),
                title = title,
                posterUrl = posterUrl,
                mimeType = file.file.mimeType,
                videoStreams = file.videoStreams,
                audioStreams = file.audioStreams,
                subtitles = file.subtitles,
                chapters = file.chapters,
                progress = saved,
                fileDurationSec = file.file.duration.orNull(),
                selection = selection,
            )
        }
        val request = build(PlaybackSelection())
        val gated = when (evaluatePlaybackGate(request, canPlayVideoMime, canPlayAudioMime)) {
            PlaybackGateResult.Proceed -> request
            is PlaybackGateResult.Blocked -> build(PlaybackSelection(mode = PlaybackMode.Remux))
        }
        return gated.copy(
            episode = header.toEpisodeContext(apiBaseUrl),
            promptForResume = promptForResume,
        )
    }
}

/** The season this episode belongs to and the one the player offers after it. */
private fun ShowEpisodePlaybackData.toEpisodeContext(apiBaseUrl: String): EpisodeContext =
    EpisodeContext(
        seasonNumber = season.seasonNumber,
        upNext = nextEpisode?.let { next ->
            UpNextEpisode(
                id = next.id,
                seasonNumber = next.seasonNumber,
                episodeNumber = next.episodeNumber,
                name = next.name,
                stillUrl = tmdbImageUrl(apiBaseUrl, TmdbImageSize.W500, next.stillPath.orNull()),
                // The card's verb only; the hand-off re-reads the position when it prepares.
                resumable = !next.watched &&
                    resumePositionSec(next.progressSec.orNull(), next.durationSec.orNull()) != null,
            )
        },
    )
