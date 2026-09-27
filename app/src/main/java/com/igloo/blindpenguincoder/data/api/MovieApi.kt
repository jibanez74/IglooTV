package com.igloo.blindpenguincoder.data.api

import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import com.igloo.blindpenguincoder.data.model.SetMovieWatchedRequest
import com.igloo.blindpenguincoder.data.model.SortOrder
import io.ktor.client.HttpClient
import io.ktor.client.request.get
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
     * One page of the browsable library, title-ordered server-side. [perPage] is clamped at
     * [MAX_LIBRARY_PER_PAGE] by the backend; [sort] is only a direction — the endpoint offers
     * no sort-field choice.
     */
    suspend fun moviesLibrary(page: Long, perPage: Long, sort: SortOrder): HttpResponse =
        client.pagedList("${serverUrl.require().apiBaseUrl}/movies/library", page, perPage, sort)

    /** All movie genres with per-genre counts, tag-ordered server-side. */
    suspend fun movieGenres(): HttpResponse =
        client.get("${serverUrl.require().apiBaseUrl}/movies/genres")

    /** One page of one genre's movies; same paging contract as [moviesLibrary]. */
    suspend fun genreMovies(
        genreId: Long,
        page: Long,
        perPage: Long,
        sort: SortOrder,
    ): HttpResponse =
        client.pagedList(
            "${serverUrl.require().apiBaseUrl}/movies/genres/$genreId/movies",
            page,
            perPage,
            sort,
        )

    /** One page of the current user's liked movies; same paging contract as [moviesLibrary]. */
    suspend fun likedMovies(page: Long, perPage: Long, sort: SortOrder): HttpResponse =
        client.pagedList("${serverUrl.require().apiBaseUrl}/movies/liked", page, perPage, sort)

    /** Library-wide counts; today just the total number of movies. */
    suspend fun movieStats(): HttpResponse =
        client.get("${serverUrl.require().apiBaseUrl}/movies/stats")

    /**
     * Movies and TV episodes in progress for the current user, most recently watched first;
     * capped at 12 across both kinds.
     */
    suspend fun continueWatching(): HttpResponse =
        client.get("${serverUrl.require().apiBaseUrl}/continue-watching")

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

    /** Current user's saved position and watched flag for one movie. */
    suspend fun movieWatchProgress(id: Long): HttpResponse =
        client.get("${serverUrl.require().apiBaseUrl}/movies/$id/watch-progress")

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
