package com.igloo.blindpenguincoder.data.api

import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse

class MovieApi(
    private val client: HttpClient,
    private val serverUrl: ServerUrlProvider,
) {
    /** Newest additions to the library; the backend caps the list at 12. */
    suspend fun latestMovies(): HttpResponse =
        client.get("${serverUrl.require().apiBaseUrl}/movies/latest")

    /** Movies in progress for the current user, most recently watched first; capped at 12. */
    suspend fun continueWatchingMovies(): HttpResponse =
        client.get("${serverUrl.require().apiBaseUrl}/movies/continue-watching")

    /** Full stored metadata for one movie; backs the Home hero and the future details screen. */
    suspend fun movieDetails(id: Long): HttpResponse =
        client.get("${serverUrl.require().apiBaseUrl}/movies/details/$id")

    /** TMDB movies now playing in theaters — not library content; capped at 12. */
    suspend fun moviesInTheaters(): HttpResponse =
        client.get("${serverUrl.require().apiBaseUrl}/tmdb/movies/in-theaters")
}
