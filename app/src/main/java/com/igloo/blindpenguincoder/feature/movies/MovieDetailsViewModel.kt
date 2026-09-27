package com.igloo.blindpenguincoder.feature.movies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.map
import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import com.igloo.blindpenguincoder.core.ui.RatingBadgeSpec
import com.igloo.blindpenguincoder.core.ui.formatReleaseDate
import com.igloo.blindpenguincoder.core.ui.formatRemainingTime
import com.igloo.blindpenguincoder.core.ui.formatRuntime
import com.igloo.blindpenguincoder.core.ui.formatSpokenRemainingTime
import com.igloo.blindpenguincoder.core.ui.formatSpokenTimeThroughSeconds
import com.igloo.blindpenguincoder.core.ui.progressFraction
import com.igloo.blindpenguincoder.core.ui.ratingBadgeSpec
import com.igloo.blindpenguincoder.data.model.MovieDetailsData
import com.igloo.blindpenguincoder.data.model.MovieTechnicalDetailsData
import com.igloo.blindpenguincoder.data.model.PlaybackMode
import com.igloo.blindpenguincoder.data.model.WatchProgress
import com.igloo.blindpenguincoder.data.repository.MovieRepository
import com.igloo.blindpenguincoder.feature.auth.toLibraryDisplayMessage
import com.igloo.blindpenguincoder.images.TmdbImageSize
import com.igloo.blindpenguincoder.images.tmdbImageUrl
import com.igloo.blindpenguincoder.playback.model.MoviePlayRequest
import com.igloo.blindpenguincoder.playback.model.PlaybackGateResult
import com.igloo.blindpenguincoder.playback.model.PlaybackMediaRef
import com.igloo.blindpenguincoder.playback.model.evaluatePlaybackGate
import com.igloo.blindpenguincoder.playback.model.languageDisplayName
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One credit line in the Key Crew band: `"Director"` over `"Michael Mann"`. */
data class CrewEntry(val job: String, val name: String)

/** A cast member ready for the rail card; [photoUrl] is the w185 profile through the proxy. */
data class CastMemberUi(
    val id: Long,
    val name: String,
    val character: String?,
    val photoUrl: String?,
)

/**
 * An extra video ready for the rail card; [thumbnailUrl] is the authenticated YouTube proxy and
 * [key] is the YouTube video id the trailer player plays.
 */
data class ExtraVideoUi(
    val id: Long,
    val title: String,
    val typeLabel: String,
    val thumbnailUrl: String?,
    val key: String,
)

/** The fine-print rows at the page's end; every field may be absent. */
data class AboutUi(
    val production: String?,
    val language: String?,
    val budget: String?,
    val revenue: String?,
    /** TMDB's release status, which only the in-theaters page has (web parity). */
    val status: String? = null,
) {
    val isEmpty: Boolean
        get() = production == null && language == null && budget == null && revenue == null &&
            status == null
}

/** The thin strip under the actions; present only while a resume position is worth showing. */
data class ProgressUi(
    val fraction: Float,
    val remainingTimeLabel: String,
    val resumeStateDescription: String,
)

/**
 * The details screen, render-ready: `SqlNull*` wrappers unwrapped, image URLs built, badges
 * derived — composables read strings, never wire models. Fields fed by the secondary requests
 * (media badges, progress, watched, liked) start absent and fill in as those requests land.
 */
data class MovieDetailsUi(
    val id: Long,
    val title: String,
    val tagline: String?,
    val backdropUrl: String?,
    val posterUrl: String?,
    val ratingBadge: RatingBadgeSpec?,
    val certification: String?,
    val mediaBadges: List<String>,
    val runtimeText: String?,
    val releaseDateText: String?,
    val genresLine: String?,
    val overview: String?,
    val keyCrew: List<CrewEntry>,
    val cast: List<CastMemberUi>,
    val extraVideos: List<ExtraVideoUi>,
    val about: AboutUi,
    val progress: ProgressUi?,
    val watched: Boolean?,
    val liked: Boolean?,
    /**
     * The metadata row spoken as one TalkBack stop, composed by [metadataDescription] once for
     * both sources so the sentence and the chips cannot drift apart.
     */
    val metadataDescription: String,
    /**
     * The hero's Play Trailer target on the in-theaters page (section 11.4.2) — null there when
     * TMDB lists no YouTube trailer, and always null for a library movie, whose hero action is
     * Play. Fields the other source cannot fill behave the same way: [mediaBadges] is empty and
     * [progress], [watched] and [liked] stay null for a movie the library does not hold.
     */
    val heroTrailer: ExtraVideoUi? = null,
    /**
     * The Playback Settings dialog, resolved from the technical-details streams and the user's
     * session-only selection. Null for the in-theaters page, which has no file to configure.
     */
    val playbackSettings: PlaybackSettingsUi? = null,
) {
    /**
     * Everything the hero's text column says, as the one sentence its screen-reader reading stop
     * announces (TV TalkBack never traverses plain text). The genre separators become commas —
     * "·" is a pause the eye takes and a symbol a screen reader stumbles over — and each part
     * sheds its own trailing period so a tagline that ends in one doesn't double up in the join.
     */
    val heroInfoDescription: String
        get() = listOfNotNull(
            title,
            tagline,
            metadataDescription.takeIf { it.isNotBlank() },
            genresLine?.replace(" · ", ", "),
        ).joinToString(". ") { it.trimEnd('.', ' ') }
}

sealed interface MovieDetailsState {
    data object Loading : MovieDetailsState
    data class Loaded(val movie: MovieDetailsUi) : MovieDetailsState
    data class Error(val message: String) : MovieDetailsState
}

/**
 * A failed read becomes the screen's error — unless it was a background refresh over content
 * already on screen: a TV waking from standby must not swap a readable page for an error card the
 * user never asked for. The rule is the same whichever source the page came from.
 */
internal fun MovieDetailsState.errorOrKeep(
    message: String,
    userInitiated: Boolean,
): MovieDetailsState =
    if (!userInitiated && this is MovieDetailsState.Loaded) {
        this
    } else {
        MovieDetailsState.Error(message)
    }

/** [openMovieId] is the overlay's existence: null means closed and [details] is meaningless. */
data class MovieDetailsUiState(
    val openMovieId: Long? = null,
    val details: MovieDetailsState = MovieDetailsState.Loading,
    val mutationNotice: String? = null,
)

private enum class PlaybackReadiness { Pending, Ready, Failed }

/**
 * [value] is the last renderable success; [readiness] says whether that value is fresh enough
 * to prepare playback. A failed refresh therefore leaves the page intact without letting Play
 * launch from data the server just failed to revalidate.
 */
private data class PlaybackRead<T>(
    val value: T? = null,
    val readiness: PlaybackReadiness = PlaybackReadiness.Pending,
) {
    fun begin(): PlaybackRead<T> = copy(readiness = PlaybackReadiness.Pending)

    fun settle(result: ApiResult<T>): PlaybackRead<T> = when (result) {
        is ApiResult.Success -> PlaybackRead(
            value = result.value,
            readiness = PlaybackReadiness.Ready,
        )
        is ApiResult.Failure -> copy(readiness = PlaybackReadiness.Failed)
    }

    fun renderableValueOrNull(): T? = value

    fun freshValueOrNull(): T? = value.takeIf { readiness == PlaybackReadiness.Ready }
}

class MovieDetailsViewModel(
    private val movies: MovieRepository,
    private val serverUrl: ServerUrlProvider,
    private val onWatchedStateCommitted: () -> Unit = {},
    private val onLikeStateCommitted: () -> Unit = {},
    /** The pre-flight gate's device capability, injected so the launch rules stay JVM-testable. */
    private val canPlayVideoMime: (mimeType: String) -> Boolean,
    private val canPlayAudioMime: (mimeType: String, channels: Int?) -> Boolean,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MovieDetailsUiState())
    val uiState: StateFlow<MovieDetailsUiState> = _uiState.asStateFlow()

    private val playRequestChannel = Channel<MoviePlayRequest>(Channel.CONFLATED)
    val playRequests: Flow<MoviePlayRequest> = playRequestChannel.receiveAsFlow()

    private enum class Read { Details, Technical, Progress, Like }

    /** Every per-type difference lives here, so a third toggle cannot half-land. */
    private enum class MutationType(val failurePrefix: String) {
        Watched("Couldn't update watched status: "),
        Like("Couldn't update like status: "),
    }

    private data class MutationIntent(
        val id: Long,
        val movieId: Long,
        val target: Boolean,
    )

    /** One movie's half of one toggle. Held and mutated by identity, so not a data class. */
    private class MutationState {
        var confirmed: Boolean? = null
        var epoch: Long = 0
        var reconcileAfterDrain: Boolean = false
        var successfulWriteSinceDrain: Boolean = false
        val pending: MutableList<MutationIntent> = mutableListOf()

        val displayed: Boolean?
            get() = pending.lastOrNull()?.target ?: confirmed

        /** Nothing queued, nothing owed, nothing left to announce. */
        val isSettled: Boolean
            get() = pending.isEmpty() && !reconcileAfterDrain && !successfulWriteSinceDrain
    }

    /** Reads are screen-owned and cancellable. Mutation workers are intentionally separate. */
    private val readJobs = mutableMapOf<Read, Job>()
    private val mutationJobs = mutableMapOf<MutationType, Job>()
    private val mutationQueues = MutationType.entries.associateWith { ArrayDeque<MutationIntent>() }
    private val mutationStates = MutationType.entries.associateWith {
        mutableMapOf<Long, MutationState>()
    }
    private var nextMutationId = 0L

    // The four requests land in any order; the fragments live here and every arrival republishes
    // the composed Loaded state from whatever has arrived so far. All are keyed to openMovieId:
    // open() clears them, and a response for a movie no longer open is dropped.
    private var wireDetails: MovieDetailsData? = null
    private var technicalRead = PlaybackRead<MovieTechnicalDetailsData>()
    private var progressRead = PlaybackRead<WatchProgress>()
    private var playIntentPending = false

    // Session-only (the user's decision): reset with the overlay, never persisted. Deliberately
    // not cleared with the fragments — a Retry of the same movie keeps the user's choices, and
    // the mapping's id-matching degrades to defaults if a track list changed underneath them.
    private var playbackSelection = PlaybackSelection()

    /** Opens the overlay on [movieId] and starts the four loads. */
    fun open(movieId: Long) {
        cancelReads()
        clearFragments()
        playbackSelection = PlaybackSelection()
        pruneSettledMutations(keep = movieId)
        _uiState.value = MovieDetailsUiState(
            openMovieId = movieId,
            details = MovieDetailsState.Loading,
            mutationNotice = null,
        )
        loadAll(movieId, userInitiated = true)
    }

    /**
     * Back from the overlay. Cancels the in-flight *reads* so a late response cannot reopen
     * state — but never the mutations: a Watched press followed straight away by Back is a
     * change the user made, and cancelling the PUT would silently drop it. Their own guards
     * already stop a late mutation from writing to a screen that has moved on.
     */
    fun close() {
        cancelReads()
        clearFragments()
        playbackSelection = PlaybackSelection()
        pruneSettledMutations(keep = null)
        _uiState.update {
            it.copy(openMovieId = null, details = MovieDetailsState.Loading)
        }
    }

    /** The full-screen error's Retry: user-initiated, so the screen returns to Loading truth. */
    fun retry() {
        val movieId = _uiState.value.openMovieId ?: return
        cancelReads()
        clearFragments()
        _uiState.update { it.copy(details = MovieDetailsState.Loading) }
        loadAll(movieId, userInitiated = true)
    }

    /**
     * Background re-read while the overlay is open (the host's start effect, matching Home): a
     * failure keeps what is on screen — a TV waking from standby must not swap a readable page
     * for an error card the user never asked for.
     */
    fun refresh() {
        val movieId = _uiState.value.openMovieId ?: return
        loadAll(movieId, userInitiated = false)
    }

    /** Optimistic: the button flips now and flips back if the server disagrees. */
    fun toggleWatched() {
        val movieId = _uiState.value.openMovieId ?: return
        val previous = mutationState(MutationType.Watched, movieId).displayed ?: false
        acceptMutation(MutationType.Watched, movieId, target = !previous)
    }

    /** Optimistic, like [toggleWatched]. The endpoint is a server-side toggle with no body. */
    fun toggleLike() {
        val movieId = _uiState.value.openMovieId ?: return
        // POST is a server-side toggle. Until the status read resolves there is no known base
        // state to toggle, so a stale UI event must be ignored instead of guessing "not liked".
        val previous = mutationState(MutationType.Like, movieId).displayed ?: return
        acceptMutation(MutationType.Like, movieId, target = !previous)
    }

    /** Playback Settings dialog choices. Local state only — nothing to send anywhere yet. */
    fun selectPlaybackMode(mode: PlaybackMode) {
        playbackSelection = playbackSelection.copy(mode = mode)
        publishLoaded()
    }

    fun selectAudioTrack(streamId: Long) {
        playbackSelection = playbackSelection.copy(audioStreamId = streamId)
        publishLoaded()
    }

    /** Null is the "None" row: subtitles off. */
    fun selectSubtitle(streamId: Long?) {
        playbackSelection = playbackSelection.copy(subtitleStreamId = streamId)
        publishLoaded()
    }

    /**
     * Records one Play intent. Required reads may finish in any order; a launch is emitted only
     * after both have succeeded, and repeated presses while preparing coalesce into that launch.
     */
    fun requestPlayback() {
        if (playIntentPending) return
        val movieId = _uiState.value.openMovieId ?: return
        playIntentPending = true
        _uiState.update { it.copy(mutationNotice = null) }

        if (technicalRead.readiness == PlaybackReadiness.Failed) loadTechnical(movieId)
        if (progressRead.readiness == PlaybackReadiness.Failed) loadProgress(movieId)
        completePlayIntentIfReady()
    }

    private fun completePlayIntentIfReady() {
        if (!playIntentPending) return
        val details = wireDetails ?: return
        val technical = technicalRead.freshValueOrNull() ?: return
        val progress = progressRead.freshValueOrNull() ?: return
        val request = buildVideoPlayRequest(
            media = PlaybackMediaRef.Movie(details.movie.id),
            title = details.movie.title,
            // The same poster the details page shows, re-used as the session artwork.
            posterUrl = tmdbImageUrl(
                serverUrl.require().apiBaseUrl,
                TmdbImageSize.W500,
                details.movie.posterPath?.orNull(),
            ),
            mimeType = technical.movie.mimeType,
            videoStreams = technical.videoStreams,
            audioStreams = technical.audioStreams,
            subtitles = technical.subtitles,
            chapters = technical.chapters,
            progress = progress,
            fileDurationSec = details.movie.duration?.orNull(),
            selection = playbackSelection,
        )
        return when (val gate = evaluatePlaybackGate(request, canPlayVideoMime, canPlayAudioMime)) {
            PlaybackGateResult.Proceed -> {
                playIntentPending = false
                playRequestChannel.trySend(request)
                Unit
            }
            is PlaybackGateResult.Blocked -> {
                playIntentPending = false
                _uiState.update { it.copy(mutationNotice = gate.message) }
            }
        }
    }

    private fun loadAll(movieId: Long, userInitiated: Boolean) {
        loadDetails(movieId, userInitiated)
        loadTechnical(movieId)
        loadProgress(movieId)
        loadLikeStatus(movieId)
    }

    private fun loadDetails(movieId: Long, userInitiated: Boolean) {
        launchRead(Read.Details) {
            val result = movies.movieDetails(movieId)
            if (_uiState.value.openMovieId != movieId) return@launchRead
            when (result) {
                is ApiResult.Success -> {
                    wireDetails = result.value
                    publishLoaded()
                    completePlayIntentIfReady()
                }
                is ApiResult.Failure -> {
                    playIntentPending = false
                    _uiState.update {
                        it.copy(
                            details = it.details.errorOrKeep(
                                result.error.toLibraryDisplayMessage(),
                                userInitiated,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun loadTechnical(movieId: Long) {
        technicalRead = technicalRead.begin()
        launchRead(Read.Technical) {
            val result = movies.movieTechnicalDetails(movieId)
            if (_uiState.value.openMovieId != movieId) return@launchRead
            technicalRead = technicalRead.settle(result)
            publishLoaded()
            onPreparationReadSettled(result)
        }
    }

    private fun loadProgress(movieId: Long) {
        val epochAtStart = mutationState(MutationType.Watched, movieId).epoch
        progressRead = progressRead.begin()
        launchRead(Read.Progress) {
            val result = movies.movieWatchProgress(movieId)
            if (_uiState.value.openMovieId != movieId) return@launchRead
            if (result is ApiResult.Success) {
                confirmIfFresh(MutationType.Watched, movieId, epochAtStart, result.value.watched)
            }
            progressRead = progressRead.settle(result)
            publishLoaded()
            onPreparationReadSettled(result)
        }
    }

    private fun onPreparationReadSettled(result: ApiResult<*>) {
        if (!playIntentPending) return
        when (result) {
            is ApiResult.Success -> completePlayIntentIfReady()
            is ApiResult.Failure -> {
                playIntentPending = false
                _uiState.update {
                    it.copy(
                        mutationNotice = "Couldn't prepare playback: " +
                            result.error.toLibraryDisplayMessage() +
                            " Press Play to retry preparation.",
                    )
                }
            }
        }
    }

    private fun loadLikeStatus(movieId: Long) {
        val epochAtStart = mutationState(MutationType.Like, movieId).epoch
        loadSecondary(movieId, Read.Like, { movies.movieLikeStatus(movieId) }) {
            confirmIfFresh(MutationType.Like, movieId, epochAtStart, it.isLiked)
        }
    }

    /**
     * Like status degrades instead of failing the screen. Technical details and progress use
     * [PlaybackRead] because their last success stays renderable after a failed refresh while
     * playback preparation independently requires a fresh success.
     */
    private fun <T> loadSecondary(
        movieId: Long,
        read: Read,
        fetch: suspend () -> ApiResult<T>,
        apply: (T) -> Unit,
    ) {
        launchRead(read) {
            val result = fetch()
            if (_uiState.value.openMovieId != movieId) return@launchRead
            if (result is ApiResult.Success) apply(result.value)
            publishLoaded()
        }
    }

    /**
     * The server can answer a read issued mid-write from before that write commits, so a status
     * value only becomes the confirmed one while its movie's mutation epoch has not moved since
     * the read began — which covers a read issued after the write began, the case a plain "is a
     * mutation running?" check misses. The rest of the same payload is nobody's to stomp and
     * lands either way.
     */
    private fun confirmIfFresh(
        type: MutationType,
        movieId: Long,
        epochAtStart: Long,
        value: Boolean,
    ) {
        val state = mutationState(type, movieId)
        if (state.epoch == epochAtStart) state.confirmed = value
    }

    private fun launchRead(read: Read, block: suspend () -> Unit) {
        readJobs[read]?.cancel()
        readJobs[read] = viewModelScope.launch { block() }
    }

    private fun cancelReads() {
        readJobs.values.forEach(Job::cancel)
        readJobs.clear()
    }

    private fun clearFragments() {
        wireDetails = null
        technicalRead = PlaybackRead()
        progressRead = PlaybackRead()
        playIntentPending = false
    }

    private fun mutationState(type: MutationType, movieId: Long): MutationState =
        mutationStates.getValue(type).getOrPut(movieId) { MutationState() }

    /**
     * Mutation state outlives the overlay on purpose — a write settling after Back still needs
     * somewhere to land — so it is dropped here instead of in [clearFragments], and only once
     * that movie has nothing outstanding. [keep] spares the movie being opened: reopening one
     * should paint the toggles it last confirmed rather than flicker back through "unknown"
     * while the status reads land again.
     */
    private fun pruneSettledMutations(keep: Long?) {
        mutationStates.values.forEach { byMovie ->
            byMovie.entries.removeAll { (movieId, state) -> movieId != keep && state.isSettled }
        }
    }

    private fun acceptMutation(type: MutationType, movieId: Long, target: Boolean) {
        val intent = MutationIntent(++nextMutationId, movieId, target)
        val state = mutationState(type, movieId)
        state.epoch += 1
        state.pending += intent
        mutationQueues.getValue(type).addLast(intent)
        _uiState.update { it.copy(mutationNotice = null) }
        publishLoaded()
        startMutationWorker(type)
    }

    /** One worker per mutation type preserves every accepted press in strict FIFO order. */
    private fun startMutationWorker(type: MutationType) {
        if (mutationJobs[type]?.isActive == true) return
        mutationJobs[type] = viewModelScope.launch {
            val queue = mutationQueues.getValue(type)
            while (queue.isNotEmpty()) {
                val intent = queue.removeFirst()
                when (val result = write(type, intent)) {
                    is ApiResult.Success -> settle(type, intent) {
                        confirmed = result.value
                        successfulWriteSinceDrain = true
                    }

                    is ApiResult.Failure -> {
                        _uiState.update {
                            it.copy(
                                mutationNotice = type.failurePrefix +
                                    result.error.toLibraryDisplayMessage(),
                            )
                        }
                        settle(type, intent) { reconcileAfterDrain = true }
                    }
                }
                finishDrainIfNeeded(type, intent.movieId)
            }
            mutationJobs.remove(type)
        }
    }

    /** Watched PUTs the value it wants; Like POSTs a server-side toggle with no body. */
    private suspend fun write(type: MutationType, intent: MutationIntent): ApiResult<Boolean> =
        when (type) {
            MutationType.Watched ->
                movies.setMovieWatched(intent.movieId, intent.target).map { it.watched }
            MutationType.Like ->
                movies.toggleMovieLike(intent.movieId).map { it.isLiked }
        }

    private suspend fun read(type: MutationType, movieId: Long): Boolean? = when (type) {
        MutationType.Watched ->
            (movies.movieWatchProgress(movieId) as? ApiResult.Success)?.value?.watched
        MutationType.Like ->
            (movies.movieLikeStatus(movieId) as? ApiResult.Success)?.value?.isLiked
    }

    /** Retires [intent], bumps the epoch so a read in flight defers, and repaints if visible. */
    private fun settle(
        type: MutationType,
        intent: MutationIntent,
        record: MutationState.() -> Unit,
    ) {
        val state = mutationState(type, intent.movieId)
        state.pending.remove(intent)
        state.record()
        state.epoch += 1
        if (_uiState.value.openMovieId == intent.movieId) publishLoaded()
    }

    private suspend fun finishDrainIfNeeded(type: MutationType, movieId: Long) {
        val state = mutationState(type, movieId)
        if (state.pending.isNotEmpty()) return

        if (state.reconcileAfterDrain) {
            val epochAtStart = state.epoch
            val reconciled = read(type, movieId)
            if (
                _uiState.value.openMovieId == movieId &&
                state.epoch == epochAtStart &&
                state.pending.isEmpty()
            ) {
                if (reconciled != null) state.confirmed = reconciled
                state.reconcileAfterDrain = false
                publishLoaded()
            }
        }

        // The reconcile read above suspends, so a press can have landed during it.
        if (state.pending.isEmpty() && state.successfulWriteSinceDrain) {
            state.successfulWriteSinceDrain = false
            when (type) {
                MutationType.Watched -> onWatchedStateCommitted()
                MutationType.Like -> onLikeStateCommitted()
            }
        }
    }

    /** Composes the Loaded state from whatever fragments have arrived. No details yet, no-op. */
    private fun publishLoaded() {
        val details = wireDetails ?: return
        _uiState.update { it.copy(details = MovieDetailsState.Loaded(toUi(details))) }
    }

    private fun toUi(details: MovieDetailsData): MovieDetailsUi {
        val movie = details.movie
        val apiBaseUrl = serverUrl.require().apiBaseUrl
        // Zero-guarded like the Home hero: the scraper writes TMDB's "no data" as a valid 0.
        val ratingBadge = movie.criticRating?.orNull()?.takeIf { it > 0 }?.let(::ratingBadgeSpec)
        val certification = movie.certification?.orNullIfBlank()
        val technical = technicalRead.renderableValueOrNull()
        val badges = technical?.let(::mediaBadges).orEmpty()
        val runtimeMinutes = movie.runTime?.orNull()?.takeIf { it > 0 }
        val releaseDateText = movie.releaseDate?.orNullIfBlank()?.let(::formatReleaseDate)
        return MovieDetailsUi(
            id = movie.id,
            title = movie.title,
            tagline = movie.tagLine?.orNullIfBlank(),
            backdropUrl = tmdbImageUrl(apiBaseUrl, TmdbImageSize.W1280, movie.backdropPath?.orNull()),
            posterUrl = tmdbImageUrl(apiBaseUrl, TmdbImageSize.W500, movie.posterPath?.orNull()),
            ratingBadge = ratingBadge,
            certification = certification,
            mediaBadges = badges,
            runtimeText = runtimeMinutes?.let(::formatRuntime),
            releaseDateText = releaseDateText,
            genresLine = joinedNames(details.genres.map { it.tag }, " · "),
            overview = movie.overview?.orNullIfBlank(),
            keyCrew = keyCrew(
                details.crew.map { CrewCredit(it.job, it.department, it.artistName) },
            ),
            cast = details.cast
                .sortedBy { it.castOrder }
                .take(CAST_LIMIT)
                .map { member ->
                    CastMemberUi(
                        id = member.id,
                        name = member.artistName,
                        character = member.character.takeIf { it.isNotBlank() },
                        photoUrl = tmdbImageUrl(
                            apiBaseUrl,
                            TmdbImageSize.W185,
                            member.artistProfile?.orNull(),
                        ),
                    )
                },
            extraVideos = youTubeExtraVideos(
                details.extraVideos.map {
                    VideoSource(
                        id = it.id,
                        title = it.title,
                        type = it.type,
                        site = it.site,
                        key = it.key,
                    )
                },
                apiBaseUrl,
            ),
            about = AboutUi(
                production = joinedNames(details.productionCompanies.map { it.name }, ", "),
                language = languageDisplayName(movie.language?.orNullIfBlank()),
                budget = movie.budget?.orNull()?.takeIf { it > 0 }?.let(::formatUsd),
                revenue = movie.revenue?.orNull()?.takeIf { it > 0 }?.let(::formatUsd),
            ),
            progress = progressUi(movie.id),
            watched = mutationState(MutationType.Watched, movie.id).displayed,
            liked = mutationState(MutationType.Like, movie.id).displayed,
            metadataDescription = metadataDescription(
                ratingBadge = ratingBadge,
                certification = certification,
                mediaBadges = badges,
                runtimeMinutes = runtimeMinutes,
                releaseDateText = releaseDateText,
            ),
            playbackSettings = playbackSettingsUi(
                audioStreams = technical?.audioStreams,
                subtitles = technical?.subtitles,
                selection = playbackSelection,
                videoCodec = technical?.let { primaryVideoStream(it.videoStreams) }?.codec,
                canPlayVideoMime = canPlayVideoMime,
                canPlayAudioMime = canPlayAudioMime,
            ),
        )
    }

    /**
     * A strip is worth showing from 30 seconds in until the position stops meaning anything —
     * the server itself flips to watched at 95% — and never once the movie is marked watched.
     */
    private fun progressUi(movieId: Long): ProgressUi? {
        if (mutationState(MutationType.Watched, movieId).displayed == true) return null
        val progress = progressRead.renderableValueOrNull()
        val progressSec = resumePositionSec(progress) ?: return null
        val durationSec = progress?.durationSec ?: return null
        return ProgressUi(
            fraction = progressFraction(progressSec, durationSec),
            remainingTimeLabel = formatRemainingTime(progressSec, durationSec),
            resumeStateDescription =
                "Resume from ${formatSpokenTimeThroughSeconds(progressSec)}; " +
                    formatSpokenRemainingTime(progressSec, durationSec),
        )
    }

    private fun mediaBadges(tech: MovieTechnicalDetailsData): List<String> = buildList {
        // Width thresholds deliberately catch scope/anamorphic sources (web parity): a 3840x1600
        // scope master is 4K even though its height is under 2160.
        val video = primaryVideoStream(tech.videoStreams)
        if (video != null) {
            when {
                video.width >= 3200 || video.height >= 2100 -> add("4K")
                video.width >= 1800 || video.height >= 1000 -> add("HD")
            }
            when (video.colorTransfer?.orNull()) {
                "smpte2084" -> add("HDR10")
                "arib-std-b67" -> add("HLG")
            }
        }
        val audio = tech.audioStreams.maxByOrNull { it.channels }
        if (audio != null && audio.channels >= SURROUND_MIN_CHANNELS) {
            val layout = audio.channelLayout?.orNull().orEmpty()
            // Only claim a named layout ffprobe actually reported; otherwise the generic word.
            add(
                when {
                    layout.startsWith("7.1") -> "7.1"
                    layout.startsWith("5.1") -> "5.1"
                    else -> "Surround"
                },
            )
        }
        if (tech.subtitles.isNotEmpty()) add("CC")
    }

    private companion object {
        const val SURROUND_MIN_CHANNELS = 6
    }
}
