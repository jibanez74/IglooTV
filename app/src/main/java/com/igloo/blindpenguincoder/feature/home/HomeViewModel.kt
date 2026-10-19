package com.igloo.blindpenguincoder.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.core.error.flatMap
import com.igloo.blindpenguincoder.core.error.map
import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.core.ui.RatingBadgeSpec
import com.igloo.blindpenguincoder.core.ui.formatEpisodeCode
import com.igloo.blindpenguincoder.core.ui.formatRuntime
import com.igloo.blindpenguincoder.core.ui.formatSpokenRemainingTime
import com.igloo.blindpenguincoder.core.ui.formatSpokenTime
import com.igloo.blindpenguincoder.core.ui.orKeepContent
import com.igloo.blindpenguincoder.core.ui.progressFraction
import com.igloo.blindpenguincoder.core.ui.ratingBadgeSpec
import com.igloo.blindpenguincoder.core.ui.spoken
import com.igloo.blindpenguincoder.data.model.ContinueWatchingItem
import com.igloo.blindpenguincoder.data.model.LatestMovie
import com.igloo.blindpenguincoder.data.model.LatestShow
import com.igloo.blindpenguincoder.data.model.Movie
import com.igloo.blindpenguincoder.data.model.PlaybackMode
import com.igloo.blindpenguincoder.data.model.ShowDetailsData
import com.igloo.blindpenguincoder.data.model.ShowEpisodePlaybackData
import com.igloo.blindpenguincoder.data.model.ShowEpisodeTechnicalDetailsData
import com.igloo.blindpenguincoder.data.model.WatchProgress
import com.igloo.blindpenguincoder.data.repository.MovieRepository
import com.igloo.blindpenguincoder.data.repository.MusicRepository
import com.igloo.blindpenguincoder.data.repository.ShowRepository
import com.igloo.blindpenguincoder.feature.auth.toFailureNotice
import com.igloo.blindpenguincoder.feature.auth.toLibraryDisplayMessage
import com.igloo.blindpenguincoder.feature.movies.PlaybackSelection
import com.igloo.blindpenguincoder.feature.movies.buildVideoPlayRequest
import com.igloo.blindpenguincoder.feature.music.AlbumCardUi
import com.igloo.blindpenguincoder.feature.music.toCardUi
import com.igloo.blindpenguincoder.feature.shared.PosterItem
import com.igloo.blindpenguincoder.feature.shared.posterItem
import com.igloo.blindpenguincoder.feature.shows.formatSeasonCount
import com.igloo.blindpenguincoder.feature.shows.formatSpokenSeasonCount
import com.igloo.blindpenguincoder.feature.shows.numberedSeasons
import com.igloo.blindpenguincoder.images.TmdbImageSize
import com.igloo.blindpenguincoder.images.tmdbImageUrl
import com.igloo.blindpenguincoder.playback.model.PlaybackGateResult
import com.igloo.blindpenguincoder.playback.model.PlaybackMediaRef
import com.igloo.blindpenguincoder.playback.model.VideoPlayRequest
import com.igloo.blindpenguincoder.playback.model.evaluatePlaybackGate
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

/** A theater movie ready to render (section 11.3.2) — TMDB content, not library content. */
data class HomeTheaterMovie(
    val id: Long,
    val title: String,
    val year: String?,
    val posterUrl: String?,
    val rating: Double?,
)

/** The rails Home renders, in the order it renders them (docs/design-system.md section 11.3). */
enum class HomeRail { ContinueWatching, LatestMovies, LatestShows, LatestAlbums, InTheaters }

/** What a hero page features: the newest movie or the newest show (section 11.3.1). */
enum class HomeHeroKind { Movie, Show }

/**
 * One featured title, render-ready (section 11.3.1). Every field but kind, id and title may be
 * absent. [extent] is what the title runs to — "2h 50m" for a movie, "3 Seasons" for a show —
 * and [metadataDescription] is the metadata row's one spoken sentence, composed here so the chips
 * the row draws never reach TalkBack one by one.
 */
data class HomeHero(
    val kind: HomeHeroKind,
    val id: Long,
    val title: String,
    val backdropUrl: String?,
    val overview: String?,
    val year: String?,
    val certification: String?,
    val ratingBadge: RatingBadgeSpec?,
    val extent: String?,
    val metadataDescription: String?,
)

/**
 * No Error member on purpose: a hero page that cannot load hides instead. The movie and show
 * pages share their Recently Added rails' data and Retry. A details failure affects
 * only its hero page; the rail's cards remain available.
 *
 * [Loaded] holds one page per feature that loaded, the movie first. The hero stays [Loading]
 * until every feature has settled, so a page never appears or disappears under the user's focus
 * mid-load and the announced "1 of 2" is right from the first word.
 */
sealed interface HomeHeroState {
    data object Loading : HomeHeroState
    data object Hidden : HomeHeroState
    data class Loaded(val pages: List<HomeHero>) : HomeHeroState
}

/** Everything Home draws. One object, so a new rail does not re-thread every composable. */
data class HomeUiState(
    val hero: HomeHeroState = HomeHeroState.Loading,
    val continueWatching: IglooRailState<HomeContinueItem> = IglooRailState.Loading,
    val latestMovies: IglooRailState<PosterItem> = IglooRailState.Loading,
    val latestShows: IglooRailState<PosterItem> = IglooRailState.Loading,
    val latestAlbums: IglooRailState<AlbumCardUi> = IglooRailState.Loading,
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

    /** Each featured title's own load state; [HomeHeroState] is composed from them in order. */
    private val features: MutableMap<HomeHeroKind, Feature> =
        HomeHeroKind.entries.associateWithTo(mutableMapOf()) { Feature.Loading }

    // Conflated, like the details page's: the host consumes launches, and only the latest one
    // can matter once it does.
    private val playRequestChannel = Channel<VideoPlayRequest>(Channel.CONFLATED)

    /** An episode ready to play, once its preparation succeeded; the host opens the player. */
    val playRequests: Flow<VideoPlayRequest> = playRequestChannel.receiveAsFlow()

    private var resumeEpisodeId: Long? = null
    private var resumeJob: Job? = null

    /**
     * Re-reads every rail. Driven by the host's start effect rather than `init`, so a TV woken
     * from standby days later does not keep showing the library as it was when the session began.
     */
    fun refresh() {
        // The refresh owns the notice: a preparation from before the app left the foreground
        // must not post one, or open a player, behind the cleared state.
        resumeJob?.cancel()
        _uiState.update { it.copy(playbackNotice = null) }
        loadContinueWatching(userInitiated = false)
        loadLatestMovies(userInitiated = false)
        loadLatestShows(userInitiated = false)
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
            HomeRail.LatestShows -> loadLatestShows(userInitiated = true)
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
            val next = movies.continueWatching().toRailState { inProgress, apiBaseUrl ->
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
                        playbackNotice = prepared.error.toFailureNotice("prepare playback"),
                    )
                }
            }
        }
    }

    private suspend fun prepareEpisode(episodeId: Long): ApiResult<VideoPlayRequest> =
        coroutineScope {
            val playback = async { shows.episodePlayback(episodeId) }
            val technical = async { shows.episodeTechnicalDetails(episodeId) }
            val progress = async { shows.episodeWatchProgress(episodeId) }
            playback.await().flatMap { header ->
                technical.await().flatMap { file ->
                    progress.await().flatMap { saved ->
                        apiBaseUrl().map { apiBaseUrl ->
                            toEpisodePlayRequest(episodeId, header, file, saved, apiBaseUrl)
                        }
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
        apiBaseUrl: String,
    ): VideoPlayRequest {
        val title = listOf(
            header.show.name,
            formatEpisodeCode(header.season.seasonNumber, header.episode.episodeNumber),
            header.episode.name,
        ).joinToString(" · ")
        // The show's poster, as the rail's card shows it, re-used as session artwork.
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

    private fun loadLatestMovies(userInitiated: Boolean) {
        if (userInitiated) {
            _uiState.update { it.copy(latestMovies = IglooRailState.Loading) }
            setFeature(HomeHeroKind.Movie, Feature.Loading)
        }
        launchLoad(HomeRail.LatestMovies) {
            val result = movies.latestMovies()
            val next = result.toRailState { latest, apiBaseUrl ->
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
            loadMovieFeature(result, userInitiated)
        }
    }

    private fun loadLatestShows(userInitiated: Boolean) {
        if (userInitiated) {
            _uiState.update { it.copy(latestShows = IglooRailState.Loading) }
            setFeature(HomeHeroKind.Show, Feature.Loading)
        }
        launchLoad(HomeRail.LatestShows) {
            val result = shows.latestShows()
            val next = result.toRailState { latest, apiBaseUrl ->
                // Preserve the server's newest-first order and cap of 12.
                latest.map { show ->
                    posterItem(
                        id = show.id,
                        title = show.name,
                        posterPath = show.posterPath,
                        year = show.premiereYear,
                        apiBaseUrl = apiBaseUrl,
                    )
                }
            }
            // Publish the rail before enriching its first show for the hero.
            _uiState.update {
                it.copy(latestShows = next.orKeepContent(it.latestShows, keep = !userInitiated))
            }
            loadShowFeature(result, userInitiated)
        }
    }

    private fun loadLatestAlbums(userInitiated: Boolean) {
        if (userInitiated) {
            _uiState.update { it.copy(latestAlbums = IglooRailState.Loading) }
        }
        launchLoad(HomeRail.LatestAlbums) {
            // Server order is the contract (newest first) — do not re-sort. SimpleAlbum carries
            // no timestamp, so the route's own order is the only recency the client can show.
            val next = music.latestAlbums().toRailState { albums, _ -> albums.map { it.toCardUi() } }
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
            val next = movies.moviesInTheaters().toRailState { theater, apiBaseUrl ->
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
     * The movie page features the newest addition (section 11.3.1), so its subject comes out of
     * the rail's own response — one source, and the rail's Retry re-runs the page for free. Only
     * the backdrop, overview, and metadata need the second, per-movie request.
     */
    private suspend fun loadMovieFeature(
        latest: ApiResult<List<LatestMovie>>,
        userInitiated: Boolean,
    ) {
        val newest = when (latest) {
            is ApiResult.Failure -> {
                hideFeatureOrKeep(HomeHeroKind.Movie, userInitiated)
                return
            }
            is ApiResult.Success -> latest.value.firstOrNull() ?: run {
                // An empty library has no page to protect: hide unconditionally.
                setFeature(HomeHeroKind.Movie, Feature.Hidden)
                return
            }
        }
        val hero = movies.movieDetails(newest.id).flatMap { details ->
            apiBaseUrl().map { toMovieHero(details.movie, it) }
        }
        when (hero) {
            is ApiResult.Success -> setFeature(HomeHeroKind.Movie, Feature.Loaded(hero.value))
            is ApiResult.Failure -> hideFeatureOrKeep(HomeHeroKind.Movie, userInitiated)
        }
    }

    /** The newest show's details enrich the rail's response within the same cancellable job. */
    private suspend fun loadShowFeature(
        latest: ApiResult<List<LatestShow>>,
        userInitiated: Boolean,
    ) {
        val newest = when (latest) {
            is ApiResult.Failure -> {
                hideFeatureOrKeep(HomeHeroKind.Show, userInitiated)
                return
            }
            is ApiResult.Success -> latest.value.firstOrNull() ?: run {
                setFeature(HomeHeroKind.Show, Feature.Hidden)
                return
            }
        }
        val hero = shows.showDetails(newest.id).flatMap { details ->
            apiBaseUrl().map { toShowHero(details, it) }
        }
        when (hero) {
            is ApiResult.Success -> setFeature(HomeHeroKind.Show, Feature.Loaded(hero.value))
            is ApiResult.Failure -> hideFeatureOrKeep(HomeHeroKind.Show, userInitiated)
        }
    }

    /** The hero's [orKeepContent]: a background refresh that fails leaves a loaded page alone. */
    private fun hideFeatureOrKeep(kind: HomeHeroKind, userInitiated: Boolean) {
        if (!userInitiated && features.getValue(kind) is Feature.Loaded) return
        setFeature(kind, Feature.Hidden)
    }

    private fun setFeature(kind: HomeHeroKind, feature: Feature) {
        features[kind] = feature
        val slots = HomeHeroKind.entries.map { features.getValue(it) }
        val pages = slots.filterIsInstance<Feature.Loaded>().map { it.hero }
        val hero = when {
            slots.any { it is Feature.Loading } -> HomeHeroState.Loading
            pages.isEmpty() -> HomeHeroState.Hidden
            else -> HomeHeroState.Loaded(pages)
        }
        _uiState.update { it.copy(hero = hero) }
    }

    /**
     * A foreground refresh can arrive while the previous one is still in flight; without this
     * the older response can land last and overwrite the newer one.
     */
    private fun launchLoad(rail: HomeRail, block: suspend () -> Unit) {
        loads[rail]?.cancel()
        loads[rail] = viewModelScope.launch { block() }
    }

    private fun <T, R> ApiResult<T>.toRailState(
        map: (T, apiBaseUrl: String) -> List<R>,
    ): IglooRailState<R> =
        when (val mapped = flatMap { value -> apiBaseUrl().map { map(value, it) } }) {
            is ApiResult.Success -> IglooRailState.Loaded(mapped.value)
            is ApiResult.Failure -> IglooRailState.Error(mapped.error.toLibraryDisplayMessage())
        }

    /**
     * The address a response's paths resolve against. The session keeps it set while Home is
     * composed, but a sign-out can clear it between a response and its mapping; that reads as a
     * failed load rather than an exception thrown out of the coroutine.
     */
    private fun apiBaseUrl(): ApiResult<String> =
        serverUrl.current.value?.let { ApiResult.Success(it.apiBaseUrl) }
            ?: ApiResult.Failure(AppError.Unexpected("Server address cleared mid-load"))

    private fun toMovieHero(movie: Movie, apiBaseUrl: String): HomeHero {
        val ratingBadge = movie.criticRating?.orNull()?.takeIf { it > 0 }?.let(::ratingBadgeSpec)
        val certification = movie.certification?.orNullIfBlank()
        val year = movie.year?.orNull()?.takeIf { it > 0 }?.toString()
        val runtime = movie.runTime?.orNull()?.takeIf { it > 0 }
        return HomeHero(
            kind = HomeHeroKind.Movie,
            id = movie.id,
            title = movie.title,
            backdropUrl = heroBackdropUrl(apiBaseUrl, movie.backdropPath?.orNull()),
            overview = movie.overview?.orNullIfBlank(),
            year = year,
            certification = certification,
            ratingBadge = ratingBadge,
            extent = runtime?.let(::formatRuntime),
            metadataDescription = heroMetadataDescription(
                ratingBadge = ratingBadge,
                certification = certification,
                year = year,
                spokenExtent = runtime?.let { formatSpokenTime(it * 60.0) },
            ),
        )
    }

    private fun toShowHero(details: ShowDetailsData, apiBaseUrl: String): HomeHero {
        val show = details.show
        val ratingBadge = show.voteAverage.orNull()?.takeIf { it > 0 }?.let(::ratingBadgeSpec)
        val certification = show.certification.orNullIfBlank()
        val year = show.premiereYear.orNull()?.takeIf { it > 0 }?.toString()
        val seasons = details.seasons.numberedSeasons().size.toLong().takeIf { it > 0 }
        return HomeHero(
            kind = HomeHeroKind.Show,
            id = show.id,
            title = show.name,
            backdropUrl = heroBackdropUrl(apiBaseUrl, show.backdropPath.orNull()),
            overview = show.overview.orNullIfBlank(),
            year = year,
            certification = certification,
            ratingBadge = ratingBadge,
            extent = seasons?.let { formatSeasonCount(it) },
            metadataDescription = heroMetadataDescription(
                ratingBadge = ratingBadge,
                certification = certification,
                year = year,
                spokenExtent = seasons?.let { formatSpokenSeasonCount(it) },
            ),
        )
    }

    // w1280 for a pane-width backdrop (section 11.3.1); w500 would upscale visibly at 10ft.
    private fun heroBackdropUrl(apiBaseUrl: String, path: String?): String? =
        tmdbImageUrl(apiBaseUrl = apiBaseUrl, size = TmdbImageSize.W1280, path = path)
}

/** One featured title's own load state, before the hero composes its pages (section 11.3.1). */
private sealed interface Feature {
    data object Loading : Feature
    data object Hidden : Feature
    data class Loaded(val hero: HomeHero) : Feature
}

/**
 * The hero's metadata row as one sentence, in the order the row draws it: rating badge,
 * certification chip, then the year and extent. Every field is guarded against zero upstream —
 * the scraper writes TMDB's "no data" as a valid 0, and an unrated title must not read "0.0".
 */
internal fun heroMetadataDescription(
    ratingBadge: RatingBadgeSpec?,
    certification: String?,
    year: String?,
    spokenExtent: String?,
): String? = listOfNotNull(
    ratingBadge?.spoken(),
    certification,
    year,
    spokenExtent,
)
    .joinToString(", ")
    .ifEmpty { null }
