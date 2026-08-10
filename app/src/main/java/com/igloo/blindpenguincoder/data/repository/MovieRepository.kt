package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.network.safeApiCall
import com.igloo.blindpenguincoder.data.api.MovieApi
import com.igloo.blindpenguincoder.data.model.ApiEnvelope
import com.igloo.blindpenguincoder.data.model.LatestMovie
import com.igloo.blindpenguincoder.data.model.LatestMoviesData
import io.ktor.client.call.body

class MovieRepository(
    private val api: MovieApi,
) {
    suspend fun latestMovies(): ApiResult<List<LatestMovie>> = safeApiCall(
        request = { api.latestMovies() },
        decode = { response ->
            response.body<ApiEnvelope<LatestMoviesData>>().data?.movies
                ?: error("Missing movies in latest movies response")
        },
    )
}
