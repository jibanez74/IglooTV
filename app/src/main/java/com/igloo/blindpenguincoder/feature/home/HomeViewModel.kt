package com.igloo.blindpenguincoder.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.flatMap
import com.igloo.blindpenguincoder.core.error.map
import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.core.ui.formatEpisodeCode
import com.igloo.blindpenguincoder.core.ui.formatSpokenRemainingTime
import com.igloo.blindpenguincoder.core.ui.formatRuntime
import com.igloo.blindpenguincoder.core.ui.orKeepContent
import com.igloo.blindpenguincoder.core.ui.progressFraction
import com.igloo.blindpenguincoder.data.model.ContinueWatchingItem
import com.igloo.blindpenguincoder.data.model.LatestMovie
import com.igloo.blindpenguincoder.data.model.Movie
import com.igloo.blindpenguincoder.data.model.PlaybackMode
import com.igloo.blindpenguincoder.data.model.ShowEpisodePlaybackData
import com.igloo.blindpenguincoder.data.model.ShowEpisodeTechnicalDetailsData
import com.igloo.blindpenguincoder.data.model.WatchProgress
import com.igloo.blindpenguincoder.data.repository.MovieRepository
import com.igloo.blindpenguincoder.data.repository.MusicRepository
import com.igloo.blindpenguincoder.data.repository.ShowRepository
import com.igloo.blindpenguincoder.feature.auth.toLibraryDisplayMessage
import com.igloo.blindpenguincoder.feature.movies.PlaybackSelection
import com.igloo.blindpenguincoder.feature.movies.buildVideoPlayRequest
import com.igloo.blindpenguincoder.feature.shared.PosterItem
import com.igloo.blindpenguincoder.feature.shared.posterItem
import com.igloo.blindpenguincoder.images.TmdbImageSize
import com.igloo.blindpenguincoder.images.tmdbImageUrl
import com.igloo.blindpenguincoder.playback.model.MoviePlayRequest
import com.igloo.blindpenguincoder.playback.model.PlaybackGateResult
import com.igloo.blindpenguincoder.playback.model.PlaybackMediaRef
import com.igloo.blindpenguincoder.playback.model.evaluatePlaybackGate
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One Continue Watching card: a movie or a TV episode, each with its progress ready to draw. */
sealed interface HomeContinueItem {
    /**
     * The rail's `Long` item key. A movie id and an episode id can collide, so the kind rides
     * in the low bit: a movie is `id * 2`, an episode `id * 2 + 1`.
     */
    val railKey: Long
    val progressFraction: Float
    val progressDescription: String

    data class Movie(
        val movie: PosterItem,
        override val progressFraction: Float,
        override val progressDescription: String,
    ) : HomeContinueItem {
        override val railKey: Long get() = movie.id * 2
    }

    /** Wears the show's poster so the rail keeps one aspect; the episode is named below it. */
    data class Episode(
        val episodeId: Long,
        val showTitle: String,
        /** "S1 E3" */
        val episodeCode: String,
        val episodeName: String,
        val posterUrl: String?,
        override val progressFraction: Float,
        override val progressDescription: String,
    ) : HomeContinueItem {
        override val railKey: Long get() = episodeId * 2 + 1
        val subtitle: String get() = "$episodeCode · $episodeName"
    }
}

/** An album ready to render: nullable wire fields resolved, cover taken as the backend sends it. */
data class HomeAlbum(
    val id: Long,
    val title: String,
    val musician: String?,
    val coverUrl: String?,
)

/** A theater movie ready to render (section 11.3.2) — TMDB content, not library content. */
data class HomeTheaterMovie(
    val id: Long,
    val title: String,
    val year: String?,
    val posterUrl: String?,
    val rating: Double?,
)

/** The rails Home renders, in the order it renders them (docs/design-system.md section 11.3). */
enum class HomeRail { ContinueWatching, LatestMovies, LatestAlbums, InTheaters }

/** The featured movie, render-ready (section 11.3.1). Every field but id and title may be absent. */
data class HomeHero(
    val id: Long,
    val title: String,
    val backdropUrl: String?,
    val overview: String?,
    val metadataLine: String?,
)

/**
 * No Error member on purpose: the hero's data is the Recently Added rail's data, and that rail
 * already owns the error card and its Retry. A hero that cannot load hides instead.
 */
sealed interface HomeHeroState {
    data object Loading : HomeHeroState
    data object Hidden : HomeHeroState
    data class Loaded(val hero: HomeHero) : HomeHeroState
}

/** Everything Home draws. One object, so a new rail does not re-thread every composable. */
data class HomeUiState(
    val hero: HomeHeroState = HomeHeroState.Loading,
    val continueWatching: IglooRailState<HomeContinueItem> = IglooRailState.Loading,
    val latestMovies: IglooRailState<PosterItem> = IglooRailState.Loading,
    val latestAlbums: IglooRailState<HomeAlbum> = IglooRailState.Loading,
    val inTheaters: IglooRailState<HomeTheaterMovie> = IglooRailState.Loading,
    /** Why the last episode press did not reach the player; cleared by the next press. */
    val playbackNotice: String? = null,
)

class HomeViewModel(
    private val movies: MovieRepository,
    private val shows: ShowRepository,
    private val music: MusicRepository,
    private val serverUrl: ServerUrlProvider,
    private val canPlayVideoMime: (mimeType: String) -> Boolean,
    private val canPlayAudioMime: (mimeType: String, channels: Int?) -> Boolean,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val loads = mutableMapOf<HomeRail, Job>()

    // Conflated, like the details page's: the host consumes launches, and only the latest one
    // can matter once it does.
    private val playRequestChannel = Channel<MoviePlayRequest>(Channel.CONFLATED)

    /** An episode ready to play, once its preparation succeeded; the host opens the player. */
    val playRequests: Flow<MoviePlayRequest> = playRequestChannel.receiveAsFlow()

    private var resumeEpisodeId: Long? = null
    private var resumeJob: Job? = null

    /**
     * Re-reads every rail. Driven by the host's start effect rather than `init`, so a TV woken
     * from standby days later does not keep showing the library as it was when the session began.
     */
    fun refresh() {
        _uiState.update { it.copy(playbackNotice = null) }
        loadContinueWatching(userInitiated = false)
        loadLatestMovies(userInitiated = false)
        loadLatestAlbums(userInitiated = false)
        loadInTheaters(userInitiated = false)
    }

    /** Refreshes only the rail a watched mutation can change, retaining loaded cards in place. */
    fun refreshContinueWatching() {
        loadContinueWatching(userInitiated = false)
    }

    /** The Retry the error state offers. Unlike [refresh] there is no content to protect. */
    fun retry(rail: HomeRail) {
        when (rail) {
            HomeRail.ContinueWatching -> loadContinueWatching(userInitiated = true)
            HomeRail.LatestMovies -> loadLatestMovies(userInitiated = true)
            HomeRail.LatestAlbums -> loadLatestAlbums(userInitiated = true)
            HomeRail.InTheaters -> loadInTheaters(userInitiated = true)
        }
    }

    private fun loadContinueWatching(userInitiated: Boolean) {
        if (userInitiated) {
            _uiState.update { it.copy(continueWatching = IglooRailState.Loading) }
        }
        launchLoad(HomeRail.ContinueWatching) {
            // Server order is the contract (most recently watched first) — do not re-sort.
            val next = movies.continueWatching().toRailState { inProgress ->
                val apiBaseUrl = serverUrl.require().apiBaseUrl
                inProgress.mapNotNull { item -> toContinueItem(item, apiBaseUrl) }
            }
            _uiState.update {
                it.copy(
                    continueWatching = next.orKeepContent(
                        it.continueWatching,
                        keep = !userInitiated,
                    ),
                )
            }
        }
    }

    private fun toContinueItem(item: ContinueWatchingItem, apiBaseUrl: String): HomeContinueItem? {
        val fraction = progressFraction(item.progressSec, item.durationSec)
        val description = formatSpokenRemainingTime(item.progressSec, item.durationSec)
        return when {
            item.isMovie -> HomeContinueItem.Movie(
                movie = posterItem(
                    id = item.id,
                    title = item.title,
                    posterPath = item.posterPath,
                    year = item.year,
                    apiBaseUrl = apiBaseUrl,
                ),
                progressFraction = fraction,
                progressDescription = description,
            )
            item.isEpisode -> HomeContinueItem.Episode(
                episodeId = item.id,
                showTitle = item.title,
                // The contract requires all three on an episode; a row missing one cannot be
                // named honestly, so it is dropped rather than drawn with a broken subtitle.
                episodeCode = formatEpisodeCode(
                    seasonNumber = item.seasonNumber ?: return null,
                    episodeNumber = item.episodeNumber ?: return null,
                ),
                episodeName = item.episodeName ?: return null,
                posterUrl = tmdbImageUrl(apiBaseUrl, TmdbImageSize.W500, item.posterPath.orNull()),
                progressFraction = fraction,
                progressDescription = description,
            )
            else -> null
        }
    }

    /**
     * A Continue Watching episode card's press. Like the details page's Play, the launch waits
     * for the episode's header, technical details and saved position, and repeated presses
     * while those are in flight coalesce into the one launch. A press on a different card
     * abandons the earlier preparation: only the latest intent should open a player.
     */
    fun resumeEpisode(episodeId: Long) {
        if (resumeEpisodeId == episodeId && resumeJob?.isActive == true) return
        resumeJob?.cancel()
        resumeEpisodeId = episodeId
        _uiState.update { it.copy(playbackNotice = null) }
        resumeJob = viewModelScope.launch {
            when (val prepared = prepareEpisode(episodeId)) {
                is ApiResult.Success -> playRequestChannel.trySend(prepared.value)
                is ApiResult.Failure -> _uiState.update {
                    it.copy(
                        playbackNotice = "Couldn't prepare playback: " +
                            prepared.error.toLibraryDisplayMessage(),
                    )
                }
            }
        }
    }

    private suspend fun prepareEpisode(episodeId: Long): ApiResult<MoviePlayRequest> =
        coroutineScope {
            val playback = async { shows.episodePlayback(episodeId) }
            val technical = async { shows.episodeTechnicalDetails(episodeId) }
            val progress = async { shows.episodeWatchProgress(episodeId) }
            playback.await().flatMap { header ->
                technical.await().flatMap { file ->
                    progress.await().map { saved ->
                        toEpisodePlayRequest(episodeId, header, file, saved)
                    }
                }
            }.also { prepared ->
                // The first failure answers the press; the reads still in flight are not waited out.
                if (prepared is ApiResult.Failure) coroutineContext.cancelChildren()
            }
        }

    /**
     * Home has no Playback Settings dialog, so the request carries the defaults; when the
     * capability gate refuses Direct play of the file's video or default audio track, the launch
     * falls back to Remux rather than blocking on guidance the user cannot follow from here. For
     * video the server cannot copy, it answers that Remux with a transcode. The gate's rule that
     * it never overrides a choice holds: no choice was made.
     */
    private fun toEpisodePlayRequest(
        episodeId: Long,
        header: ShowEpisodePlaybackData,
        file: ShowEpisodeTechnicalDetailsData,
        saved: WatchProgress,
    ): MoviePlayRequest {
        val title = listOf(
            header.show.name,
            formatEpisodeCode(header.season.seasonNumber, header.episode.episodeNumber),
            header.episode.name,
        ).joinToString(" · ")
        // The show's poster, as the rail's card shows it, re-used as session artwork.
        val posterUrl = tmdbImageUrl(
            serverUrl.require().apiBaseUrl,
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

    private fun loadLatestMovies(userInitiated: Boolean) {
        if (userInitiated) {
            _uiState.update {
                it.copy(latestMovies = IglooRailState.Loading, hero = HomeHeroState.Loading)
            }
        }
        launchLoad(HomeRail.LatestMovies) {
            val result = movies.latestMovies()
            val next = result.toRailState { latest ->
                val apiBaseUrl = serverUrl.require().apiBaseUrl
                latest.map { movie ->
                    posterItem(
                        id = movie.id,
                        title = movie.title,
                        posterPath = movie.posterPath,
                        year = movie.year,
                        apiBaseUrl = apiBaseUrl,
                    )
                }
            }
            // Rail first, so the list is on screen while the hero's details request runs.
            _uiState.update {
                it.copy(latestMovies = next.orKeepContent(it.latestMovies, keep = !userInitiated))
            }
            loadHero(result, userInitiated)
        }
    }

    private fun loadLatestAlbums(userInitiated: Boolean) {
        if (userInitiated) {
            _uiState.update { it.copy(latestAlbums = IglooRailState.Loading) }
        }
        launchLoad(HomeRail.LatestAlbums) {
            // Server order is the contract (newest first) — do not re-sort. SimpleAlbum carries
            // no timestamp, so the route's own order is the only recency the client can show.
            val next = music.latestAlbums().toRailState { albums ->
                albums.map { album ->
                    HomeAlbum(
                        id = album.id,
                        // The contract requires a title but not a non-blank one; an untagged rip
                        // must not render an empty title line or a nameless announcement.
                        title = album.title.ifBlank { "Untitled album" },
                        musician = album.musician.orNullIfBlank(),
                        // Used verbatim: the scanner stores an absolute Spotify URL or nothing,
                        // and there is no music image proxy to route it through.
                        coverUrl = album.cover.orNullIfBlank(),
                    )
                }
            }
            _uiState.update {
                it.copy(latestAlbums = next.orKeepContent(it.latestAlbums, keep = !userInitiated))
            }
        }
    }

    private fun loadInTheaters(userInitiated: Boolean) {
        if (userInitiated) {
            _uiState.update { it.copy(inTheaters = IglooRailState.Loading) }
        }
        launchLoad(HomeRail.InTheaters) {
            val next = movies.moviesInTheaters().toRailState { theater ->
                val apiBaseUrl = serverUrl.require().apiBaseUrl
                theater
                    // The TMDB route's order is not a contract the way the library rails' is;
                    // newest release first, matching the web client's own client-side sort.
                    // "YYYY-MM-DD" dates sort correctly as strings.
                    .sortedByDescending { it.releaseDate }
                    .map { movie ->
                        HomeTheaterMovie(
                            id = movie.id.toLong(),
                            title = movie.title,
                            year = movie.releaseDate.take(4).takeIf { it.length == 4 },
                            posterUrl = tmdbImageUrl(
                                apiBaseUrl = apiBaseUrl,
                                size = TmdbImageSize.W500,
                                path = movie.posterPath,
                            ),
                            // TMDB sends 0 for an unrated movie; the card must not badge "0.0".
                            rating = movie.voteAverage.takeIf { it > 0 },
                        )
                    }
            }
            _uiState.update {
                it.copy(inTheaters = next.orKeepContent(it.inTheaters, keep = !userInitiated))
            }
        }
    }

    /**
     * The hero features the newest addition (section 11.3.1), so its subject comes out of the
     * rail's own response — one source, and the rail's Retry re-runs the hero for free. Only the
     * backdrop, overview, and metadata need the second, per-movie request.
     */
    private suspend fun loadHero(latest: ApiResult<List<LatestMovie>>, userInitiated: Boolean) {
        val newest = when (latest) {
            is ApiResult.Failure -> {
                hideHeroOrKeep(userInitiated)
                return
            }
            is ApiResult.Success -> latest.value.firstOrNull() ?: run {
                // An empty library has no hero to protect: hide unconditionally.
                _uiState.update { it.copy(hero = HomeHeroState.Hidden) }
                return
            }
        }
        when (val details = movies.movieDetails(newest.id)) {
            is ApiResult.Success -> _uiState.update {
                it.copy(
                    hero = HomeHeroState.Loaded(
                        toHomeHero(details.value.movie, serverUrl.require().apiBaseUrl),
                    ),
                )
            }
            is ApiResult.Failure -> hideHeroOrKeep(userInitiated)
        }
    }

    /** The hero's [orKeepContent]: a background refresh that fails leaves a loaded hero alone. */
    private fun hideHeroOrKeep(userInitiated: Boolean) {
        _uiState.update {
            if (!userInitiated && it.hero is HomeHeroState.Loaded) {
                it
            } else {
                it.copy(hero = HomeHeroState.Hidden)
            }
        }
    }

    /**
     * A foreground refresh can arrive while the previous one is still in flight; without this
     * the older response can land last and overwrite the newer one.
     */
    private fun launchLoad(rail: HomeRail, block: suspend () -> Unit) {
        loads[rail]?.cancel()
        loads[rail] = viewModelScope.launch { block() }
    }

    private fun <T, R> ApiResult<T>.toRailState(map: (T) -> List<R>): IglooRailState<R> =
        when (this) {
            is ApiResult.Success -> IglooRailState.Loaded(map(value))
            is ApiResult.Failure -> IglooRailState.Error(error.toLibraryDisplayMessage())
        }

    private fun toHomeHero(movie: Movie, apiBaseUrl: String): HomeHero = HomeHero(
        id = movie.id,
        title = movie.title,
        // w1280 for a pane-width backdrop (section 11.3.1); w500 would upscale visibly at 10ft.
        backdropUrl = tmdbImageUrl(
            apiBaseUrl = apiBaseUrl,
            size = TmdbImageSize.W1280,
            path = movie.backdropPath?.orNull(),
        ),
        overview = movie.overview?.orNullIfBlank(),
        metadataLine = heroMetadataLine(movie),
    )

    // Every numeric field is guarded against zero, not just null: the scraper writes TMDB's
    // "no data" as a valid 0 — an unrated movie would otherwise read "· 0.0".
    private fun heroMetadataLine(movie: Movie): String? = listOfNotNull(
        movie.year?.orNull()?.takeIf { it > 0 }?.toString(),
        movie.certification?.orNullIfBlank(),
        movie.runTime?.orNull()?.takeIf { it > 0 }?.let(::formatRuntime),
        movie.criticRating?.orNull()?.takeIf { it > 0 }
            ?.let { String.format(Locale.US, "%.1f", it) },
    )
        .joinToString(" · ")
        .ifEmpty { null }

}
