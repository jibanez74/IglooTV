package com.igloo.blindpenguincoder.data.api

import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import com.igloo.blindpenguincoder.data.model.SetMovieWatchedRequest
import com.igloo.blindpenguincoder.data.model.SortOrder
import com.igloo.blindpenguincoder.data.model.UpdateMovieWatchProgressRequest
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType

class MovieApi(
    private val client: HttpClient,
    private val serverUrl: ServerUrlProvider,
) {
    /** Newest additions to the library; the backend caps the list at 12. */
    suspend fun latestMovies(): HttpResponse =
        client.get("${serverUrl.require().apiBaseUrl}/movies/latest")

    /**
     * One page of the browsable library, title-ordered server-side. [perPage] is capped at
     * [MAX_LIBRARY_PER_PAGE] by the backend; [sort] is only a direction — the endpoint offers
     * no sort-field choice.
     */
    suspend fun moviesLibrary(page: Long, perPage: Long, sort: SortOrder): HttpResponse =
        client.get("${serverUrl.require().apiBaseUrl}/movies/library") {
            parameter("page", page)
            parameter("per_page", perPage)
            parameter("sort", sort.wireName)
        }

    /** Library-wide counts; today just the total number of movies. */
    suspend fun movieStats(): HttpResponse =
        client.get("${serverUrl.require().apiBaseUrl}/movies/stats")

    /** Movies in progress for the current user, most recently watched first; capped at 12. */
    suspend fun continueWatchingMovies(): HttpResponse =
        client.get("${serverUrl.require().apiBaseUrl}/movies/continue-watching")

    /** Full stored metadata for one movie; backs the Home hero and the future details screen. */
    suspend fun movieDetails(id: Long): HttpResponse =
        client.get("${serverUrl.require().apiBaseUrl}/movies/details/$id")

    /** TMDB movies now playing in theaters — not library content; capped at 12. */
    suspend fun moviesInTheaters(): HttpResponse =
        client.get("${serverUrl.require().apiBaseUrl}/tmdb/movies/in-theaters")

    /** One TMDB movie by its TMDB id; backs the in-theaters detail screen. */
    suspend fun tmdbMovie(tmdbId: Long): HttpResponse =
        client.get("${serverUrl.require().apiBaseUrl}/tmdb/movies/$tmdbId")

    /** Probed streams, subtitles, and chapters for one movie; source of the media badges. */
    suspend fun movieTechnicalDetails(id: Long): HttpResponse =
        client.get("${serverUrl.require().apiBaseUrl}/movies/$id/technical-details")

    /**
     * Absolute URL of the direct stream. Media3 fetches it on its own HTTP stack (Range/206),
     * not through Ktor, so this is a string rather than a request.
     */
    fun movieStreamUrl(id: Long): String =
        "${serverUrl.require().apiBaseUrl}/movies/$id/stream"

    /**
     * The HLS manifest, which implicitly creates or refreshes the playback session. The query
     * pairs come pre-built (see `hlsQueryParams`) so their names are spelled in one place.
     * The server may hold a remux manifest up to 30s waiting for FFmpeg's first segments, so
     * this request gets a longer budget than the client default.
     */
    suspend fun movieHlsPlaylist(
        id: Long,
        profileId: String,
        query: List<Pair<String, String>>,
    ): HttpResponse =
        client.get("${serverUrl.require().apiBaseUrl}/movies/$id/hls/$profileId/playlist.m3u8") {
            query.forEach { (name, value) -> parameter(name, value) }
            timeout {
                requestTimeoutMillis = HLS_MANIFEST_TIMEOUT_MS
                socketTimeoutMillis = HLS_MANIFEST_TIMEOUT_MS
            }
        }

    /** Same manifest address as a string for Media3, which fetches on its own stack. */
    fun movieHlsPlaylistUrl(id: Long, profileId: String, query: List<Pair<String, String>>): String {
        val suffix = query.joinToString("&") { (name, value) -> "$name=$value" }
        return "${serverUrl.require().apiBaseUrl}/movies/$id/hls/$profileId/playlist.m3u8?$suffix"
    }

    /** Ends one personal HLS session; scoped to the client's own session UUID. */
    suspend fun stopMovieHlsSession(id: Long, sessionUuid: String): HttpResponse =
        client.post("${serverUrl.require().apiBaseUrl}/movies/$id/hls/session/stop") {
            parameter("playback_session", sessionUuid)
        }

    /**
     * Sideloaded WebVTT for one text subtitle track. [startSec] must be the session's
     * effective start so cues land on the rebased timeline; zero (direct play) omits it.
     */
    fun movieSubtitleUrl(id: Long, trackIndex: Int, startSec: Double): String {
        val base = "${serverUrl.require().apiBaseUrl}/movies/$id/subtitles/$trackIndex/web.vtt"
        return if (startSec > 0.0) "$base?start=$startSec" else base
    }

    companion object {
        /** The backend rejects a larger `per_page` on every paged movie endpoint. */
        const val MAX_LIBRARY_PER_PAGE = 48L

        private const val HLS_MANIFEST_TIMEOUT_MS = 45_000L
    }

    /** Current user's saved position and watched flag for one movie. */
    suspend fun movieWatchProgress(id: Long): HttpResponse =
        client.get("${serverUrl.require().apiBaseUrl}/movies/$id/watch-progress")

    suspend fun updateMovieWatchProgress(
        id: Long,
        body: UpdateMovieWatchProgressRequest,
    ): HttpResponse =
        client.put("${serverUrl.require().apiBaseUrl}/movies/$id/watch-progress") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    suspend fun setMovieWatched(id: Long, body: SetMovieWatchedRequest): HttpResponse =
        client.put("${serverUrl.require().apiBaseUrl}/movies/$id/watch-progress/watched") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    suspend fun movieLikeStatus(id: Long): HttpResponse =
        client.get("${serverUrl.require().apiBaseUrl}/movies/$id/like-status")

    /** Server-side toggle — no request body; the response carries the new state. */
    suspend fun toggleMovieLike(id: Long): HttpResponse =
        client.post("${serverUrl.require().apiBaseUrl}/movies/$id/like")
}
