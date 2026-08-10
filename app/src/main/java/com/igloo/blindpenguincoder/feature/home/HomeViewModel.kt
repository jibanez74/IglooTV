package com.igloo.blindpenguincoder.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.data.model.SqlNullInt64
import com.igloo.blindpenguincoder.data.model.SqlNullString
import com.igloo.blindpenguincoder.data.repository.MovieRepository
import com.igloo.blindpenguincoder.feature.auth.toLibraryDisplayMessage
import com.igloo.blindpenguincoder.images.TmdbImageSize
import com.igloo.blindpenguincoder.images.tmdbImageUrl
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

/** The rails Home renders, in the order it renders them (docs/design-system.md section 11.3). */
enum class HomeRail { ContinueWatching, LatestMovies }

/** Everything Home draws. One object, so a new rail does not re-thread every composable. */
data class HomeUiState(
    val continueWatching: IglooRailState<HomeContinueMovie> = IglooRailState.Loading,
    val latestMovies: IglooRailState<HomeMovie> = IglooRailState.Loading,
)

class HomeViewModel(
    private val movies: MovieRepository,
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
    }

    /** The Retry the error state offers. Unlike [refresh] there is no content to protect. */
    fun retry(rail: HomeRail) {
        when (rail) {
            HomeRail.ContinueWatching -> loadContinueWatching(userInitiated = true)
            HomeRail.LatestMovies -> loadLatestMovies(userInitiated = true)
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
            _uiState.update { it.copy(latestMovies = IglooRailState.Loading) }
        }
        launchLoad(HomeRail.LatestMovies) {
            val next = movies.latestMovies().toRailState { latest ->
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
            _uiState.update {
                it.copy(latestMovies = next.orKeep(it.latestMovies, userInitiated))
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
