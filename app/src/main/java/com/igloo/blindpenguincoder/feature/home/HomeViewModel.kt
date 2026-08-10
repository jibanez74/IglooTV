package com.igloo.blindpenguincoder.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.data.model.SqlNullInt64
import com.igloo.blindpenguincoder.data.model.SqlNullString
import com.igloo.blindpenguincoder.data.repository.MovieRepository
import com.igloo.blindpenguincoder.feature.auth.toDisplayMessage
import com.igloo.blindpenguincoder.images.TmdbImageSize
import com.igloo.blindpenguincoder.images.tmdbImageUrl
import kotlin.math.ceil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

class HomeViewModel(
    private val movies: MovieRepository,
    private val serverUrl: ServerUrlProvider,
) : ViewModel() {

    private val _continueWatching =
        MutableStateFlow<IglooRailState<HomeContinueMovie>>(IglooRailState.Loading)
    val continueWatching: StateFlow<IglooRailState<HomeContinueMovie>> =
        _continueWatching.asStateFlow()

    private val _latestMovies = MutableStateFlow<IglooRailState<HomeMovie>>(IglooRailState.Loading)
    val latestMovies: StateFlow<IglooRailState<HomeMovie>> = _latestMovies.asStateFlow()

    init {
        loadContinueWatching()
        loadLatestMovies()
    }

    fun retryContinueWatching() {
        loadContinueWatching()
    }

    fun retryLatestMovies() {
        loadLatestMovies()
    }

    private fun loadContinueWatching() {
        _continueWatching.value = IglooRailState.Loading
        viewModelScope.launch {
            _continueWatching.value = when (val result = movies.continueWatchingMovies()) {
                is ApiResult.Success -> {
                    val apiBaseUrl = serverUrl.require().apiBaseUrl
                    // Server order is the contract (most recently watched first) — do not re-sort.
                    IglooRailState.Loaded(
                        result.value.map { movie ->
                            HomeContinueMovie(
                                movie = toHomeMovie(
                                    id = movie.id,
                                    title = movie.title,
                                    posterPath = movie.posterPath,
                                    year = movie.year,
                                    apiBaseUrl = apiBaseUrl,
                                ),
                                progressFraction = progressFraction(
                                    progressSec = movie.progressSec,
                                    durationSec = movie.durationSec,
                                ),
                                progressLabel = progressLabel(
                                    progressSec = movie.progressSec,
                                    durationSec = movie.durationSec,
                                ),
                            )
                        },
                    )
                }
                is ApiResult.Failure -> IglooRailState.Error(result.error.toDisplayMessage())
            }
        }
    }

    private fun loadLatestMovies() {
        _latestMovies.value = IglooRailState.Loading
        viewModelScope.launch {
            _latestMovies.value = when (val result = movies.latestMovies()) {
                is ApiResult.Success -> {
                    val apiBaseUrl = serverUrl.require().apiBaseUrl
                    IglooRailState.Loaded(
                        result.value.map { movie ->
                            toHomeMovie(
                                id = movie.id,
                                title = movie.title,
                                posterPath = movie.posterPath,
                                year = movie.year,
                                apiBaseUrl = apiBaseUrl,
                            )
                        },
                    )
                }
                // A 401 also fires the auth event bus, which tears this screen down before the
                // message could render; every other failure is worth showing with a Retry.
                is ApiResult.Failure -> IglooRailState.Error(result.error.toDisplayMessage())
            }
        }
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
