package com.igloo.blindpenguincoder.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.core.ui.formatSpokenRemainingTime
import com.igloo.blindpenguincoder.core.ui.formatRuntime
import com.igloo.blindpenguincoder.core.ui.orKeepContent
import com.igloo.blindpenguincoder.core.ui.progressFraction
import com.igloo.blindpenguincoder.data.model.LatestMovie
import com.igloo.blindpenguincoder.data.model.Movie
import com.igloo.blindpenguincoder.data.repository.MovieRepository
import com.igloo.blindpenguincoder.data.repository.MusicRepository
import com.igloo.blindpenguincoder.feature.auth.toLibraryDisplayMessage
import com.igloo.blindpenguincoder.feature.shared.MoviePosterItem
import com.igloo.blindpenguincoder.feature.shared.moviePosterItem
import com.igloo.blindpenguincoder.images.TmdbImageSize
import com.igloo.blindpenguincoder.images.tmdbImageUrl
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A movie in progress: the render-ready movie plus its progress, ready for the card. */
data class HomeContinueMovie(
    val movie: MoviePosterItem,
    val progressFraction: Float,
    val progressDescription: String,
)

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
    val continueWatching: IglooRailState<HomeContinueMovie> = IglooRailState.Loading,
    val latestMovies: IglooRailState<MoviePosterItem> = IglooRailState.Loading,
    val latestAlbums: IglooRailState<HomeAlbum> = IglooRailState.Loading,
    val inTheaters: IglooRailState<HomeTheaterMovie> = IglooRailState.Loading,
)

class HomeViewModel(
    private val movies: MovieRepository,
    private val music: MusicRepository,
    private val serverUrl: ServerUrlProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val loads = mutableMapOf<HomeRail, Job>()

    /**
     * Re-reads every rail. Driven by the host's start effect rather than `init`, so a TV woken
     * from standby days later does not keep showing the library as it was when the session began.
     */
    fun refresh() {
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
            val next = movies.continueWatchingMovies().toRailState { inProgress ->
                val apiBaseUrl = serverUrl.require().apiBaseUrl
                inProgress.map { movie ->
                    HomeContinueMovie(
                        movie = moviePosterItem(
                            id = movie.id,
                            title = movie.title,
                            posterPath = movie.posterPath,
                            year = movie.year,
                            apiBaseUrl = apiBaseUrl,
                        ),
                        progressFraction = progressFraction(movie.progressSec, movie.durationSec),
                        progressDescription = formatSpokenRemainingTime(
                            movie.progressSec,
                            movie.durationSec,
                        ),
                    )
                }
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
                    moviePosterItem(
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
