package com.igloo.blindpenguincoder.feature.shows

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.feature.auth.toFailureNotice
import com.igloo.blindpenguincoder.playback.model.VideoPlayRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Prepares an episode for the video player on behalf of a launching screen — Home's Continue
 * Watching rail, the show page's Play/Resume and its episode cards — so every surface launches an
 * episode the same way, through one [EpisodePlayPreparer]. A press builds the request and emits it
 * on [playRequests]. Repeated presses on the same episode while its reads are in flight coalesce
 * into the one launch, and a press on a different episode abandons the earlier preparation: only
 * the latest intent should open a player. A failed read answers the press through [onNotice].
 */
internal class EpisodePlayLauncher(
    private val scope: CoroutineScope,
    private val preparer: EpisodePlayPreparer,
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

    /**
     * A press on [episodeId]'s card or on the hero's Play/Resume that names it. [promptForResume]
     * is false when the control already said Resume — the hero's verb, a card with a progress
     * bar — so the player starts at the saved position without asking again.
     */
    fun launch(episodeId: Long, promptForResume: Boolean) {
        if (this.episodeId == episodeId && job?.isActive == true) return
        job?.cancel()
        this.episodeId = episodeId
        onNotice(null)
        job = scope.launch {
            when (val prepared = preparer.prepare(episodeId, promptForResume)) {
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
}
