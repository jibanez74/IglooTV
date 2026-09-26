package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.core.error.map
import com.igloo.blindpenguincoder.core.network.safeApiCall
import com.igloo.blindpenguincoder.core.network.toTransportError
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
import com.igloo.blindpenguincoder.data.model.MovieWatchProgress
import com.igloo.blindpenguincoder.data.model.MovieWatchProgressUpdateData
import com.igloo.blindpenguincoder.data.model.MovieWatchedData
import com.igloo.blindpenguincoder.data.model.MoviesLibraryData
import com.igloo.blindpenguincoder.data.model.MoviesStatsData
import com.igloo.blindpenguincoder.data.model.SetMovieWatchedRequest
import com.igloo.blindpenguincoder.data.model.SortOrder
import com.igloo.blindpenguincoder.data.model.TheaterMovie
import com.igloo.blindpenguincoder.data.model.TheaterMoviesData
import com.igloo.blindpenguincoder.data.model.TmdbMovie
import com.igloo.blindpenguincoder.data.model.TmdbMovieData
import com.igloo.blindpenguincoder.data.model.UpdateMovieWatchProgressRequest
import com.igloo.blindpenguincoder.playback.hls.HlsManifestResult
import com.igloo.blindpenguincoder.playback.hls.HlsSessionApi
import com.igloo.blindpenguincoder.playback.hls.HlsSessionSpec
import com.igloo.blindpenguincoder.playback.hls.hlsQueryParams
import com.igloo.blindpenguincoder.playback.hls.parseHlsManifestResponse
import com.igloo.blindpenguincoder.playback.model.PLAYBACK_SERVER_UNREACHABLE_MESSAGE
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.discard

class MovieRepository(
    private val api: MovieApi,
) : HlsSessionApi {
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

    /**
     * The movies in progress, in the server's order. The row also carries TV episodes; the
     * client has no episode screen yet, so those are dropped rather than rendered as dead-end
     * cards.
     */
    suspend fun continueWatchingMovies(): ApiResult<List<ContinueWatchingItem>> =
        envelopeData<ContinueWatchingData>("continue watching") { api.continueWatching() }
            .map { data -> data.items.filter { it.isMovie } }

    suspend fun moviesInTheaters(): ApiResult<List<TheaterMovie>> =
        envelopeData<TheaterMoviesData>("in-theaters") { api.moviesInTheaters() }.map { it.movies }

    suspend fun tmdbMovie(tmdbId: Long): ApiResult<TmdbMovie> =
        envelopeData<TmdbMovieData>("TMDB movie") { api.tmdbMovie(tmdbId) }.map { it.movie }

    suspend fun movieDetails(id: Long): ApiResult<MovieDetailsData> =
        envelopeData("movie details") { api.movieDetails(id) }

    suspend fun movieTechnicalDetails(id: Long): ApiResult<MovieTechnicalDetailsData> =
        envelopeData("technical details") { api.movieTechnicalDetails(id) }

    /** Absolute direct-stream URL for Media3; not an API call, so no [ApiResult]. */
    fun movieStreamUrl(id: Long): String = api.movieStreamUrl(id)

    /**
     * The manifest fetch that creates/refreshes an HLS session. Not [safeApiCall]: 503 and 404
     * are protocol states the session controller retries through, not failures. Established
     * request/socket timeouts count as "busy" because the server may legitimately hold a remux
     * manifest while FFmpeg warms up; a connection timeout means the server was never reached.
     * Only transport failures are absorbed, and programming errors must surface.
     */
    override suspend fun fetchHlsManifest(spec: HlsSessionSpec): HlsManifestResult = try {
        val response = api.movieHlsPlaylist(spec.movieId, spec.profileId, hlsQueryParams(spec))
        val status = response.status.value
        val headers = response.headers.entries().associate { (name, values) ->
            name.lowercase() to values.firstOrNull()
        }
        response.bodyAsChannel().discard()
        parseHlsManifestResponse(status, spec) { name -> headers[name.lowercase()] }
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        if (failure.hasConnectionTimeoutCause()) {
            HlsManifestResult.Failed(PLAYBACK_SERVER_UNREACHABLE_MESSAGE)
        } else {
            when (failure.toTransportError()) {
                AppError.Timeout -> HlsManifestResult.Busy(retryAfterSec = null)
                is AppError.Unexpected -> throw failure
                else -> HlsManifestResult.Failed(PLAYBACK_SERVER_UNREACHABLE_MESSAGE)
            }
        }
    }

    private fun Throwable.hasConnectionTimeoutCause(): Boolean =
        generateSequence<Throwable>(this) { it.cause }
            .take(HLS_CAUSE_CHAIN_LIMIT)
            .any { it is ConnectTimeoutException }

    /** Best-effort session teardown; the server's idle TTL is the real backstop. */
    override suspend fun stopHlsSession(movieId: Long, sessionUuid: String) {
        api.stopMovieHlsSession(movieId, sessionUuid)
    }

    override fun hlsPlaylistUrl(spec: HlsSessionSpec): String =
        api.movieHlsPlaylistUrl(spec.movieId, spec.profileId, hlsQueryParams(spec))

    override fun movieSubtitleUrl(movieId: Long, trackIndex: Int, startSec: Double): String =
        api.movieSubtitleUrl(movieId, trackIndex, startSec)

    suspend fun movieWatchProgress(id: Long): ApiResult<MovieWatchProgress> =
        envelopeData("watch progress") { api.movieWatchProgress(id) }

    suspend fun updateWatchProgress(
        id: Long,
        body: UpdateMovieWatchProgressRequest,
    ): ApiResult<MovieWatchProgressUpdateData> =
        envelopeData("watch progress update") { api.updateMovieWatchProgress(id, body) }

    suspend fun setMovieWatched(id: Long, watched: Boolean): ApiResult<MovieWatchedData> =
        envelopeData("set watched") { api.setMovieWatched(id, SetMovieWatchedRequest(watched)) }

    suspend fun movieLikeStatus(id: Long): ApiResult<MovieLikeStatusData> =
        envelopeData("like status") { api.movieLikeStatus(id) }

    suspend fun toggleMovieLike(id: Long): ApiResult<MovieLikeToggleData> =
        envelopeData("like toggle") { api.toggleMovieLike(id) }

    private companion object {
        const val HLS_CAUSE_CHAIN_LIMIT = 8
    }
}
