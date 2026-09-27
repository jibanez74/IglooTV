package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.map
import com.igloo.blindpenguincoder.data.api.MovieApi
import com.igloo.blindpenguincoder.data.model.ContinueWatchingData
import com.igloo.blindpenguincoder.data.model.ContinueWatchingItem
import com.igloo.blindpenguincoder.data.model.LatestMovie
import com.igloo.blindpenguincoder.data.model.LatestMoviesData
import com.igloo.blindpenguincoder.data.model.MovieDetailsData
import com.igloo.blindpenguincoder.data.model.MovieGenreWithCount
import com.igloo.blindpenguincoder.data.model.MovieGenresData
import com.igloo.blindpenguincoder.data.model.MovieLikeStatusData
import com.igloo.blindpenguincoder.data.model.MovieLikeToggleData
import com.igloo.blindpenguincoder.data.model.MovieTechnicalDetailsData
import com.igloo.blindpenguincoder.data.model.MovieWatchedData
import com.igloo.blindpenguincoder.data.model.MoviesLibraryData
import com.igloo.blindpenguincoder.data.model.MoviesStatsData
import com.igloo.blindpenguincoder.data.model.SetMovieWatchedRequest
import com.igloo.blindpenguincoder.data.model.SortOrder
import com.igloo.blindpenguincoder.data.model.TheaterMovie
import com.igloo.blindpenguincoder.data.model.TheaterMoviesData
import com.igloo.blindpenguincoder.data.model.TmdbMovie
import com.igloo.blindpenguincoder.data.model.TmdbMovieData
import com.igloo.blindpenguincoder.data.model.WatchProgress

class MovieRepository(
    private val api: MovieApi,
) {
    suspend fun latestMovies(): ApiResult<List<LatestMovie>> =
        envelopeData<LatestMoviesData>("latest movies") { api.latestMovies() }.map { it.movies }

    /** One page of the browsable library. The envelope's paging counts are part of the result. */
    suspend fun moviesLibrary(
        page: Long,
        perPage: Long,
        sort: SortOrder,
    ): ApiResult<MoviesLibraryData> =
        envelopeData("movies list") { api.moviesLibrary(page, perPage, sort) }

    suspend fun movieGenres(): ApiResult<List<MovieGenreWithCount>> =
        envelopeData<MovieGenresData>("movie genres") { api.movieGenres() }.map { it.genres }

    /** One page of one genre's movies; same result shape as [moviesLibrary]. */
    suspend fun genreMovies(
        genreId: Long,
        page: Long,
        perPage: Long,
        sort: SortOrder,
    ): ApiResult<MoviesLibraryData> =
        envelopeData("movies list") { api.genreMovies(genreId, page, perPage, sort) }

    /** One page of the current user's liked movies; same result shape as [moviesLibrary]. */
    suspend fun likedMovies(
        page: Long,
        perPage: Long,
        sort: SortOrder,
    ): ApiResult<MoviesLibraryData> =
        envelopeData("movies list") { api.likedMovies(page, perPage, sort) }

    suspend fun movieStats(): ApiResult<MoviesStatsData> =
        envelopeData("movie stats") { api.movieStats() }

    /** The movies and TV episodes in progress, in the server's order (most recent first). */
    suspend fun continueWatching(): ApiResult<List<ContinueWatchingItem>> =
        envelopeData<ContinueWatchingData>("continue watching") { api.continueWatching() }
            .map { it.items }

    suspend fun moviesInTheaters(): ApiResult<List<TheaterMovie>> =
        envelopeData<TheaterMoviesData>("in-theaters") { api.moviesInTheaters() }.map { it.movies }

    suspend fun tmdbMovie(tmdbId: Long): ApiResult<TmdbMovie> =
        envelopeData<TmdbMovieData>("TMDB movie") { api.tmdbMovie(tmdbId) }.map { it.movie }

    suspend fun movieDetails(id: Long): ApiResult<MovieDetailsData> =
        envelopeData("movie details") { api.movieDetails(id) }

    suspend fun movieTechnicalDetails(id: Long): ApiResult<MovieTechnicalDetailsData> =
        envelopeData("technical details") { api.movieTechnicalDetails(id) }

    suspend fun movieWatchProgress(id: Long): ApiResult<WatchProgress> =
        envelopeData("watch progress") { api.movieWatchProgress(id) }

    suspend fun setMovieWatched(id: Long, watched: Boolean): ApiResult<MovieWatchedData> =
        envelopeData("set watched") { api.setMovieWatched(id, SetMovieWatchedRequest(watched)) }

    suspend fun movieLikeStatus(id: Long): ApiResult<MovieLikeStatusData> =
        envelopeData("like status") { api.movieLikeStatus(id) }

    suspend fun toggleMovieLike(id: Long): ApiResult<MovieLikeToggleData> =
        envelopeData("like toggle") { api.toggleMovieLike(id) }
}
