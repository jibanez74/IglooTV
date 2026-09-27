package com.igloo.blindpenguincoder.playback.progress

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.data.model.UpdateWatchProgressRequest
import com.igloo.blindpenguincoder.data.model.WatchProgressUpdateData
import com.igloo.blindpenguincoder.playback.model.PlaybackMediaRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        var result: (PlaybackMediaRef) -> ApiResult<WatchProgressUpdateData> = {
            ApiResult.Success(WatchProgressUpdateData(watched = false))
        },
    ) {
        val requests = mutableListOf<UpdateWatchProgressRequest>()

        val media = mutableListOf<PlaybackMediaRef>()

        suspend fun save(
            media: PlaybackMediaRef,
            request: UpdateWatchProgressRequest,
        ): ApiResult<WatchProgressUpdateData> {
            this.media += media
            requests += request
            return result(media)
        }
    }

    @Test
    fun `one session id spans every save and the sequence strictly increases`() = runTest {
        val recorder = RecordingSave()
        val reporter = ProgressReporter(media = PlaybackMediaRef.Movie(7), save = recorder::save)

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
        val reporter = ProgressReporter(media = PlaybackMediaRef.Movie(7), save = recorder::save)

        assertTrue(reporter.saveNow(45.0, 600.0) is ApiResult.Failure)
        recorder.result = { ApiResult.Success(WatchProgressUpdateData(watched = false)) }
        assertEquals(
            false,
            (reporter.saveNow(45.0, 600.0) as ApiResult.Success).value.watched,
        )

        // The retried save outsequences the failed attempt, so the server cannot drop it.
        assertEquals(listOf(1L, 2L), recorder.requests.map { it.saveSequence })
    }

    @Test
    fun `two sessions never share an id`() {
        val first = ProgressReporter(media = PlaybackMediaRef.Movie(7), save = { _, _ -> ApiResult.Failure(AppError.Network) })
        val second = ProgressReporter(media = PlaybackMediaRef.Movie(7), save = { _, _ -> ApiResult.Failure(AppError.Network) })
        assertFalse(first.sessionId == second.sessionId)
    }

    @Test
    fun `the server's watched verdict is surfaced`() = runTest {
        val recorder = RecordingSave(result = {
            ApiResult.Success(WatchProgressUpdateData(watched = true))
        })
        val reporter = ProgressReporter(media = PlaybackMediaRef.Movie(7), save = recorder::save)
        assertEquals(
            true,
            (reporter.saveNow(590.0, 600.0) as ApiResult.Success).value.watched,
        )
    }

    @Test
    fun `positions clamp into the file and a zero duration refuses to send`() = runTest {
        val recorder = RecordingSave()
        val reporter = ProgressReporter(media = PlaybackMediaRef.Movie(7), save = recorder::save)

        assertTrue(reporter.saveNow(100.0, 0.0) is ApiResult.Failure)
        assertTrue(recorder.requests.isEmpty())

        reporter.saveNow(-5.0, 600.0)
        reporter.saveNow(700.0, 600.0)
        assertEquals(listOf(0.0, 600.0), recorder.requests.map { it.progressSec })
    }

    // --- shouldPersistProgress ---

    @Test
    fun `pause, background and exit writes need the position floor or completion`() {
        assertFalse(shouldPersistProgress(29.9, 600.0))
        assertTrue(shouldPersistProgress(30.0, 600.0))
        // A short film finishing under the floor still reaches the server's watched rule.
        assertFalse(shouldPersistProgress(18.9, 20.0))
        assertTrue(shouldPersistProgress(19.0, 20.0))
    }

    @Test
    fun `an unusable snapshot is never written`() {
        assertFalse(shouldPersistProgress(300.0, 0.0))
        assertFalse(shouldPersistProgress(-1.0, 600.0))
        assertFalse(shouldPersistProgress(Double.NaN, 600.0))
        assertFalse(shouldPersistProgress(300.0, Double.POSITIVE_INFINITY))
        assertFalse(shouldPersistProgress(Double.POSITIVE_INFINITY, 600.0))
    }
}
