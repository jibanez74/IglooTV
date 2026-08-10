package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MovieRepositoryTest {

    @Test
    fun `latest movies hits the contract path with the bearer token`() = runTest {
        var request: HttpRequestData? = null
        val http = TestHttp {
            request = it
            jsonResponse(latestMoviesJson(latestMovieJson(id = 5, title = "Heat")))
        }
        http.profiles.setPending("igd_test")

        val result = http.movieRepository.latestMovies()

        val captured = requireNotNull(request)
        assertEquals("/api/movies/latest", captured.url.encodedPath)
        assertEquals("Bearer igd_test", captured.headers[HttpHeaders.Authorization])
        val movies = (result as ApiResult.Success).value
        assertEquals(listOf(5L), movies.map { it.id })
        assertEquals("Heat", movies.single().title)
        assertEquals("/heat.jpg", movies.single().posterPath.orNull())
        assertEquals(1995L, movies.single().year.orNull())
    }

    @Test
    fun `invalid wire nulls decode to absent values`() = runTest {
        val http = TestHttp {
            jsonResponse(latestMoviesJson(latestMovieJson(posterPath = null, year = null)))
        }

        val movie = (http.movieRepository.latestMovies() as ApiResult.Success).value.single()

        assertNull(movie.posterPath.orNull())
        assertNull(movie.year.orNull())
    }

    @Test
    fun `an empty library is a success with no movies`() = runTest {
        val http = TestHttp { jsonResponse(latestMoviesJson()) }

        val result = http.movieRepository.latestMovies()

        assertTrue((result as ApiResult.Success).value.isEmpty())
    }

    @Test
    fun `a dead token maps to Unauthorized`() = runTest {
        val http = TestHttp {
            jsonResponse("""{"error":true,"message":"gone"}""", HttpStatusCode.Unauthorized)
        }

        val result = http.movieRepository.latestMovies()

        assertEquals(AppError.Unauthorized, (result as ApiResult.Failure).error)
    }

    @Test
    fun `a server failure preserves the backend message`() = runTest {
        val http = TestHttp {
            jsonResponse(
                """{"error":true,"message":"scan in progress"}""",
                HttpStatusCode.InternalServerError,
            )
        }

        val result = http.movieRepository.latestMovies()

        val error = (result as ApiResult.Failure).error as AppError.Api
        assertEquals("scan in progress", error.message)
        assertEquals(500, error.status)
    }

    @Test
    fun `a success envelope with no data is Unexpected`() = runTest {
        val http = TestHttp { jsonResponse("""{"error":false,"message":"ok"}""") }

        val result = http.movieRepository.latestMovies()

        assertTrue((result as ApiResult.Failure).error is AppError.Unexpected)
    }
}
