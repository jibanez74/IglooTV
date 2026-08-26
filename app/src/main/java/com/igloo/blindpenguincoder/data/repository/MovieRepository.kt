package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.core.network.safeApiCall
import com.igloo.blindpenguincoder.core.network.toTransportError
import com.igloo.blindpenguincoder.data.api.MovieApi
import com.igloo.blindpenguincoder.data.model.ApiEnvelope
import com.igloo.blindpenguincoder.data.model.ContinueWatchingMovie
import com.igloo.blindpenguincoder.data.model.ContinueWatchingMoviesData
import com.igloo.blindpenguincoder.data.model.LatestMovie
import com.igloo.blindpenguincoder.data.model.LatestMoviesData
import com.igloo.blindpenguincoder.data.model.MovieDetailsData
import com.igloo.blindpenguincoder.data.model.MovieLikeStatusData
import com.igloo.blindpenguincoder.data.model.MovieLikeToggleData
import com.igloo.blindpenguincoder.data.model.MovieTechnicalDetailsData
import com.igloo.blindpenguincoder.data.model.MovieWatchProgress
import com.igloo.blindpenguincoder.data.model.MovieWatchProgressUpdateData
import com.igloo.blindpenguincoder.data.model.MovieWatchedData
import com.igloo.blindpenguincoder.data.model.SetMovieWatchedRequest
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
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.discard

class MovieRepository(
    private val api: MovieApi,
) : HlsSessionApi {
    suspend fun latestMovies(): ApiResult<List<LatestMovie>> = safeApiCall(
        request = { api.latestMovies() },
        decode = { response ->
            response.body<ApiEnvelope<LatestMoviesData>>().data?.movies
                ?: error("Missing movies in latest movies response")
        },
    )

    suspend fun continueWatchingMovies(): ApiResult<List<ContinueWatchingMovie>> = safeApiCall(
        request = { api.continueWatchingMovies() },
        decode = { response ->
            response.body<ApiEnvelope<ContinueWatchingMoviesData>>().data?.movies
                ?: error("Missing movies in continue watching response")
        },
    )

    suspend fun moviesInTheaters(): ApiResult<List<TheaterMovie>> = safeApiCall(
        request = { api.moviesInTheaters() },
        decode = { response ->
            response.body<ApiEnvelope<TheaterMoviesData>>().data?.movies
                ?: error("Missing movies in in-theaters response")
        },
    )

    suspend fun tmdbMovie(tmdbId: Long): ApiResult<TmdbMovie> = safeApiCall(
        request = { api.tmdbMovie(tmdbId) },
        decode = { response ->
            response.body<ApiEnvelope<TmdbMovieData>>().data?.movie
                ?: error("Missing movie in TMDB movie response")
        },
    )

    suspend fun movieDetails(id: Long): ApiResult<MovieDetailsData> = safeApiCall(
        request = { api.movieDetails(id) },
        decode = { response ->
            response.body<ApiEnvelope<MovieDetailsData>>().data
                ?: error("Missing data in movie details response")
        },
    )

    suspend fun movieTechnicalDetails(id: Long): ApiResult<MovieTechnicalDetailsData> = safeApiCall(
        request = { api.movieTechnicalDetails(id) },
        decode = { response ->
            response.body<ApiEnvelope<MovieTechnicalDetailsData>>().data
                ?: error("Missing data in technical details response")
        },
    )

    /** Absolute direct-stream URL for Media3; not an API call, so no [ApiResult]. */
    fun movieStreamUrl(id: Long): String = api.movieStreamUrl(id)

    /**
     * The manifest fetch that creates/refreshes an HLS session. Not [safeApiCall]: 503 and 404
     * are protocol states the session controller retries through, not failures. Timeouts also
     * count as "busy" — the server legitimately holds a remux manifest while FFmpeg warms up.
     * Only transport failures are absorbed; a programming error must surface, not read as
     * "server unreachable".
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
        when (failure.toTransportError()) {
            AppError.Timeout -> HlsManifestResult.Busy(retryAfterSec = null)
            is AppError.Unexpected -> throw failure
            else -> HlsManifestResult.Failed(PLAYBACK_SERVER_UNREACHABLE_MESSAGE)
        }
    }

    /** Best-effort session teardown; the server's idle TTL is the real backstop. */
    override suspend fun stopHlsSession(movieId: Long, sessionUuid: String) {
        api.stopMovieHlsSession(movieId, sessionUuid)
    }

    override fun hlsPlaylistUrl(spec: HlsSessionSpec): String =
        api.movieHlsPlaylistUrl(spec.movieId, spec.profileId, hlsQueryParams(spec))

    override fun movieSubtitleUrl(movieId: Long, trackIndex: Int, startSec: Double): String =
        api.movieSubtitleUrl(movieId, trackIndex, startSec)

    suspend fun movieWatchProgress(id: Long): ApiResult<MovieWatchProgress> = safeApiCall(
        request = { api.movieWatchProgress(id) },
        decode = { response ->
            response.body<ApiEnvelope<MovieWatchProgress>>().data
                ?: error("Missing data in watch progress response")
        },
    )

    suspend fun updateWatchProgress(
        id: Long,
        body: UpdateMovieWatchProgressRequest,
    ): ApiResult<MovieWatchProgressUpdateData> = safeApiCall(
        request = { api.updateMovieWatchProgress(id, body) },
        decode = { response ->
            response.body<ApiEnvelope<MovieWatchProgressUpdateData>>().data
                ?: error("Missing data in watch progress update response")
        },
    )

    suspend fun setMovieWatched(id: Long, watched: Boolean): ApiResult<MovieWatchedData> = safeApiCall(
        request = { api.setMovieWatched(id, SetMovieWatchedRequest(watched)) },
        decode = { response ->
            response.body<ApiEnvelope<MovieWatchedData>>().data
                ?: error("Missing data in set watched response")
        },
    )

    suspend fun movieLikeStatus(id: Long): ApiResult<MovieLikeStatusData> = safeApiCall(
        request = { api.movieLikeStatus(id) },
        decode = { response ->
            response.body<ApiEnvelope<MovieLikeStatusData>>().data
                ?: error("Missing data in like status response")
        },
    )

    suspend fun toggleMovieLike(id: Long): ApiResult<MovieLikeToggleData> = safeApiCall(
        request = { api.toggleMovieLike(id) },
        decode = { response ->
            response.body<ApiEnvelope<MovieLikeToggleData>>().data
                ?: error("Missing data in like toggle response")
        },
    )
}
