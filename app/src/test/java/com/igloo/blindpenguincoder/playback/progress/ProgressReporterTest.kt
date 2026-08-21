package com.igloo.blindpenguincoder.playback.progress

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.data.model.MovieWatchProgressUpdateData
import com.igloo.blindpenguincoder.data.model.UpdateMovieWatchProgressRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressReporterTest {

    // --- shouldSaveProgress ---

    @Test
    fun `no save before 15 seconds of actual playback`() {
        assertFalse(shouldSaveProgress(14.9, 100.0, 600.0, null))
        assertTrue(shouldSaveProgress(15.0, 100.0, 600.0, null))
    }

    @Test
    fun `no save before the 30 second position floor`() {
        assertFalse(shouldSaveProgress(20.0, 29.9, 600.0, null))
        assertTrue(shouldSaveProgress(20.0, 30.0, 600.0, null))
    }

    @Test
    fun `no save without a known duration`() {
        assertFalse(shouldSaveProgress(20.0, 100.0, 0.0, null))
    }

    @Test
    fun `saves keep a 15 second cadence`() {
        assertFalse(shouldSaveProgress(40.0, 100.0, 600.0, 14.9))
        assertTrue(shouldSaveProgress(40.0, 100.0, 600.0, 15.0))
    }

    // --- ProgressReporter ---

    private class RecordingSave(
        var result: (Long) -> ApiResult<MovieWatchProgressUpdateData> = {
            ApiResult.Success(MovieWatchProgressUpdateData(watched = false))
        },
    ) {
        val requests = mutableListOf<UpdateMovieWatchProgressRequest>()

        suspend fun save(
            movieId: Long,
            request: UpdateMovieWatchProgressRequest,
        ): ApiResult<MovieWatchProgressUpdateData> {
            requests += request
            return result(movieId)
        }
    }

    @Test
    fun `one session id spans every save and the sequence strictly increases`() = runTest {
        val recorder = RecordingSave()
        val reporter = ProgressReporter(movieId = 7, save = recorder::save)

        reporter.saveNow(45.0, 600.0)
        reporter.saveNow(60.0, 600.0)
        reporter.saveNow(75.0, 600.0)

        assertEquals(1, recorder.requests.map { it.saveSessionId }.distinct().size)
        assertEquals(reporter.sessionId, recorder.requests.first().saveSessionId)
        assertEquals(listOf(1L, 2L, 3L), recorder.requests.map { it.saveSequence })
    }

    @Test
    fun `a retry of the same position still takes a fresh sequence`() = runTest {
        val recorder = RecordingSave(result = { ApiResult.Failure(AppError.Network) })
        val reporter = ProgressReporter(movieId = 7, save = recorder::save)

        assertNull(reporter.saveNow(45.0, 600.0))
        recorder.result = { ApiResult.Success(MovieWatchProgressUpdateData(watched = false)) }
        assertEquals(false, reporter.saveNow(45.0, 600.0))

        // The retried save outsequences the failed attempt, so the server cannot drop it.
        assertEquals(listOf(1L, 2L), recorder.requests.map { it.saveSequence })
    }

    @Test
    fun `two sessions never share an id`() {
        val first = ProgressReporter(movieId = 7, save = { _, _ -> ApiResult.Failure(AppError.Network) })
        val second = ProgressReporter(movieId = 7, save = { _, _ -> ApiResult.Failure(AppError.Network) })
        assertFalse(first.sessionId == second.sessionId)
    }

    @Test
    fun `the server's watched verdict is surfaced`() = runTest {
        val recorder = RecordingSave(result = {
            ApiResult.Success(MovieWatchProgressUpdateData(watched = true))
        })
        val reporter = ProgressReporter(movieId = 7, save = recorder::save)
        assertEquals(true, reporter.saveNow(590.0, 600.0))
    }

    @Test
    fun `positions clamp into the file and a zero duration refuses to send`() = runTest {
        val recorder = RecordingSave()
        val reporter = ProgressReporter(movieId = 7, save = recorder::save)

        assertNull(reporter.saveNow(100.0, 0.0))
        assertTrue(recorder.requests.isEmpty())

        reporter.saveNow(-5.0, 600.0)
        reporter.saveNow(700.0, 600.0)
        assertEquals(listOf(0.0, 600.0), recorder.requests.map { it.progressSec })
    }
}
