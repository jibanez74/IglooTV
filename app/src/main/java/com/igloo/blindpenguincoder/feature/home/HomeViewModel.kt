package com.igloo.blindpenguincoder.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.data.repository.MovieRepository
import com.igloo.blindpenguincoder.feature.auth.toDisplayMessage
import com.igloo.blindpenguincoder.images.TmdbImageSize
import com.igloo.blindpenguincoder.images.tmdbImageUrl
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

class HomeViewModel(
    private val movies: MovieRepository,
    private val serverUrl: ServerUrlProvider,
) : ViewModel() {

    private val _latestMovies = MutableStateFlow<IglooRailState<HomeMovie>>(IglooRailState.Loading)
    val latestMovies: StateFlow<IglooRailState<HomeMovie>> = _latestMovies.asStateFlow()

    init {
        load()
    }

    fun retry() {
        load()
    }

    private fun load() {
        _latestMovies.value = IglooRailState.Loading
        viewModelScope.launch {
            _latestMovies.value = when (val result = movies.latestMovies()) {
                is ApiResult.Success -> {
                    // w500 for a 148dp poster: crisp at TV densities, and the same cache entry
                    // the detail screen will want later.
                    val apiBaseUrl = serverUrl.require().apiBaseUrl
                    IglooRailState.Loaded(
                        result.value.map { movie ->
                            HomeMovie(
                                id = movie.id,
                                title = movie.title,
                                year = movie.year.orNull(),
                                posterUrl = tmdbImageUrl(
                                    apiBaseUrl = apiBaseUrl,
                                    size = TmdbImageSize.W500,
                                    path = movie.posterPath.orNull(),
                                ),
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
}
