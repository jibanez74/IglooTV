package com.igloo.blindpenguincoder.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.data.model.LatestMovie
import com.igloo.blindpenguincoder.data.model.Movie
import com.igloo.blindpenguincoder.data.model.SqlNullInt64
import com.igloo.blindpenguincoder.data.model.SqlNullString
import com.igloo.blindpenguincoder.data.repository.MovieRepository
import com.igloo.blindpenguincoder.data.repository.MusicRepository
import com.igloo.blindpenguincoder.feature.auth.toLibraryDisplayMessage
import com.igloo.blindpenguincoder.images.TmdbImageSize
import com.igloo.blindpenguincoder.images.tmdbImageUrl
import java.util.Locale
import kotlin.math.ceil
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A movie ready to render: nullable wire fields resolved, poster path built into a URL. */
data class HomeMovie(
    val id: Long,
    val title: String,
    val year: Long?,
    val posterUrl: String?,
)

/** A movie in progress: the render-ready movie plus its progress, ready for the card. */
data class HomeContinueMovie(
    val movie: HomeMovie,
    val progressFraction: Float,
    val progressLabel: String,
)

/** An album ready to render: nullable wire fields resolved, cover taken as the backend sends it. */
data class HomeAlbum(
    val id: Long,
    val title: String,
    val musician: String?,
    val coverUrl: String?,
)

/** The rails Home renders, in the order it renders them (docs/design-system.md section 11.3). */
enum class HomeRail { ContinueWatching, LatestMovies, LatestAlbums }

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
    val latestMovies: IglooRailState<HomeMovie> = IglooRailState.Loading,
    val latestAlbums: IglooRailState<HomeAlbum> = IglooRailState.Loading,
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
    }

    /** The Retry the error state offers. Unlike [refresh] there is no content to protect. */
    fun retry(rail: HomeRail) {
        when (rail) {
            HomeRail.ContinueWatching -> loadContinueWatching(userInitiated = true)
            HomeRail.LatestMovies -> loadLatestMovies(userInitiated = true)
            HomeRail.LatestAlbums -> loadLatestAlbums(userInitiated = true)
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
                        movie = toHomeMovie(
                            id = movie.id,
                            title = movie.title,
                            posterPath = movie.posterPath,
                            year = movie.year,
                            apiBaseUrl = apiBaseUrl,
                        ),
                        progressFraction = progressFraction(movie.progressSec, movie.durationSec),
                        progressLabel = progressLabel(movie.progressSec, movie.durationSec),
                    )
                }
            }
            _uiState.update {
                it.copy(continueWatching = next.orKeep(it.continueWatching, userInitiated))
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
                    toHomeMovie(
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
                it.copy(latestMovies = next.orKeep(it.latestMovies, userInitiated))
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
                        title = album.title,
                        musician = album.musician.orNull()?.takeUnless { it.isBlank() },
                        // Used verbatim: the scanner stores an absolute Spotify URL or nothing,
                        // and there is no music image proxy to route it through.
                        coverUrl = album.cover.orNull()?.takeUnless { it.isBlank() },
                    )
                }
            }
            _uiState.update {
                it.copy(latestAlbums = next.orKeep(it.latestAlbums, userInitiated))
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

    /** The hero's [orKeep]: a background refresh that fails leaves a loaded hero alone. */
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
     * A background refresh that fails leaves what is on screen alone — a moment of bad wifi as
     * the TV wakes must not replace a working Home with two error cards. A Retry the user asked
     * for always shows the truth, and a rail with nothing to protect shows the error either way.
     */
    private fun <T> IglooRailState<T>.orKeep(
        current: IglooRailState<T>,
        userInitiated: Boolean,
    ): IglooRailState<T> =
        if (!userInitiated && this is IglooRailState.Error && current is IglooRailState.Loaded) {
            current
        } else {
            this
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

    private fun toHomeMovie(
        id: Long,
        title: String,
        posterPath: SqlNullString,
        year: SqlNullInt64,
        apiBaseUrl: String,
    ): HomeMovie = HomeMovie(
        id = id,
        title = title,
        year = year.orNull(),
        // w500 for a 148dp poster: crisp at TV densities, and the same cache entry
        // the detail screen will want later.
        posterUrl = tmdbImageUrl(
            apiBaseUrl = apiBaseUrl,
            size = TmdbImageSize.W500,
            path = posterPath.orNull(),
        ),
    )

    private fun toHomeHero(movie: Movie, apiBaseUrl: String): HomeHero = HomeHero(
        id = movie.id,
        title = movie.title,
        // w1280 for a pane-width backdrop (section 11.3.1); w500 would upscale visibly at 10ft.
        backdropUrl = tmdbImageUrl(
            apiBaseUrl = apiBaseUrl,
            size = TmdbImageSize.W1280,
            path = movie.backdropPath?.orNull(),
        ),
        overview = movie.overview?.orNull()?.takeUnless { it.isBlank() },
        metadataLine = heroMetadataLine(movie),
    )

    // Every numeric field is guarded against zero, not just null: the scraper writes TMDB's
    // "no data" as a valid 0 — an unrated movie would otherwise read "· 0.0".
    private fun heroMetadataLine(movie: Movie): String? = listOfNotNull(
        movie.year?.orNull()?.takeIf { it > 0 }?.toString(),
        movie.certification?.orNull()?.takeUnless { it.isBlank() },
        movie.runTime?.orNull()?.takeIf { it > 0 }?.let(::formatRuntime),
        movie.criticRating?.orNull()?.takeIf { it > 0 }
            ?.let { String.format(Locale.US, "%.1f", it) },
    )
        .joinToString(" · ")
        .ifEmpty { null }

    private fun formatRuntime(minutes: Long): String {
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0L -> "${rest}m"
            rest == 0L -> "${hours}h"
            else -> "${hours}h ${rest}m"
        }
    }

    private fun progressFraction(progressSec: Double, durationSec: Double): Float =
        if (durationSec > 0) (progressSec / durationSec).toFloat().coerceIn(0f, 1f) else 0f

    // The contract always sends a positive duration; the fallback is defensive only.
    private fun progressLabel(progressSec: Double, durationSec: Double): String {
        if (durationSec <= 0) return "In progress"
        val minutesLeft = ceil((durationSec - progressSec).coerceAtLeast(0.0) / 60.0)
            .toInt()
            .coerceAtLeast(1)
        return "$minutesLeft min left"
    }
}
