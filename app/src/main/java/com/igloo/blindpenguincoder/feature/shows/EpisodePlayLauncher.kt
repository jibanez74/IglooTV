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
import com.igloo.blindpenguincoder.feature.auth.toFailureNotice
import com.igloo.blindpenguincoder.feature.movies.PlaybackSelection
import com.igloo.blindpenguincoder.feature.movies.buildVideoPlayRequest
import com.igloo.blindpenguincoder.images.TmdbImageSize
import com.igloo.blindpenguincoder.images.tmdbImageUrl
import com.igloo.blindpenguincoder.playback.model.PlaybackGateResult
import com.igloo.blindpenguincoder.playback.model.PlaybackMediaRef
import com.igloo.blindpenguincoder.playback.model.VideoPlayRequest
import com.igloo.blindpenguincoder.playback.model.evaluatePlaybackGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Prepares an episode for the video player on behalf of a launching screen — Home's Continue
 * Watching rail, the show page's Play/Resume and its episode cards — so every surface launches an
 * episode the same way. A press reads the episode's header, technical details and the caller's
 * saved position in parallel, builds the request and emits it on [playRequests]. Repeated presses
 * on the same episode while those reads are in flight coalesce into the one launch, and a press on
 * a different episode abandons the earlier preparation: only the latest intent should open a
 * player. The first failed read answers the press through [onNotice] without waiting out the rest.
 *
 * Neither surface has a Playback Settings dialog, so the request carries the defaults; when the
 * capability gate refuses Direct play of the file's video or default audio track, the launch falls
 * back to Remux rather than blocking on guidance the user cannot follow from here. For video the
 * server cannot copy, it answers that Remux with a transcode. The gate's rule that it never
 * overrides a choice holds: no choice was made.
 */
internal class EpisodePlayLauncher(
    private val scope: CoroutineScope,
    private val shows: ShowRepository,
    private val serverUrl: ServerUrlProvider,
    private val canPlayVideoMime: (mimeType: String) -> Boolean,
    private val canPlayAudioMime: (mimeType: String, channels: Int?) -> Boolean,
    /** Why the last press did not reach the player, or null to clear it. */
    private val onNotice: (String?) -> Unit,
) {
    // Conflated, like the movie page's: the host consumes launches, and only the latest one can
    // matter once it does.
    private val playRequestChannel = Channel<VideoPlayRequest>(Channel.CONFLATED)

    /** An episode ready to play, once its preparation succeeded; the host opens the player. */
    val playRequests: Flow<VideoPlayRequest> = playRequestChannel.receiveAsFlow()

    private var episodeId: Long? = null
    private var job: Job? = null

    /** A press on [episodeId]'s card or on the hero's Play/Resume that names it. */
    fun launch(episodeId: Long) {
        if (this.episodeId == episodeId && job?.isActive == true) return
        job?.cancel()
        this.episodeId = episodeId
        onNotice(null)
        job = scope.launch {
            when (val prepared = prepare(episodeId)) {
                is ApiResult.Success -> playRequestChannel.trySend(prepared.value)
                is ApiResult.Failure -> onNotice(prepared.error.toFailureNotice("prepare playback"))
            }
        }
    }

    /**
     * Abandons a preparation in flight, drops a request built but not yet delivered, and clears
     * the notice. The owner calls it when it refreshes or leaves: a preparation from before the
     * app left the foreground must not post a notice, or open a player, behind the state the user
     * now sees — nor may a request a recreated host would otherwise pick up.
     */
    fun reset() {
        job?.cancel()
        job = null
        episodeId = null
        playRequestChannel.tryReceive()
        onNotice(null)
    }

    private suspend fun prepare(episodeId: Long): ApiResult<VideoPlayRequest> = coroutineScope {
        val playback = async { shows.episodePlayback(episodeId) }
        val technical = async { shows.episodeTechnicalDetails(episodeId) }
        val progress = async { shows.episodeWatchProgress(episodeId) }
        playback.await().flatMap { header ->
            technical.await().flatMap { file ->
                progress.await().withApiBaseUrl(serverUrl).map { (saved, apiBaseUrl) ->
                    toPlayRequest(episodeId, header, file, saved, apiBaseUrl)
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
        return when (evaluatePlaybackGate(request, canPlayVideoMime, canPlayAudioMime)) {
            PlaybackGateResult.Proceed -> request
            is PlaybackGateResult.Blocked -> build(PlaybackSelection(mode = PlaybackMode.Remux))
        }
    }
}
