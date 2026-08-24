package com.igloo.blindpenguincoder.feature.player

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.data.model.MovieWatchProgressUpdateData
import com.igloo.blindpenguincoder.data.model.UpdateMovieWatchProgressRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MoviePlayerViewModelTest {

    private val requests = mutableListOf<UpdateMovieWatchProgressRequest>()
    private var watchedResponse = false
    private var refreshes = 0

    private var save: suspend (Long, UpdateMovieWatchProgressRequest) ->
    ApiResult<MovieWatchProgressUpdateData> = { _, request ->
        requests += request
        ApiResult.Success(MovieWatchProgressUpdateData(watched = watchedResponse))
    }

    /** The view model's scope rides Dispatchers.Main; share runTest's scheduler so virtual time moves both. */
    private fun test(block: suspend TestScope.(MoviePlayerViewModel) -> Unit) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val viewModel = MoviePlayerViewModel(
                saveProgress = { id, request -> save(id, request) },
                onWatchedStateCommitted = { refreshes++ },
            )
            viewModel.startSession(movieId = 7)
            block(viewModel)
        } finally {
            Dispatchers.resetMain()
        }
    }

    /** Half-second ticks from [fromSec] to [toSec], all playing — the engine's cadence. */
    private fun MoviePlayerViewModel.play(fromSec: Double, toSec: Double, durationSec: Double = 600.0) {
        var position = fromSec
        while (position < toSec) {
            onTick(position, durationSec, isPlaying = true)
            position += 0.5
        }
        onTick(toSec, durationSec, isPlaying = true)
    }

    @Test
    fun `the first save lands after 15 played seconds past the 30 second floor`() = test { viewModel ->
        viewModel.play(20.0, 34.5)
        assertTrue(requests.isEmpty())

        viewModel.play(34.5, 36.0)
        assertEquals(1, requests.size)
        assertEquals(1L, requests.single().saveSequence)
    }

    @Test
    fun `saves repeat on a 15 second cadence with a rising sequence`() = test { viewModel ->
        // 46 played seconds cross the 15s cadence at 15, 30, and 45: exactly three saves.
        viewModel.play(30.0, 76.0)
        assertEquals(3, requests.size)
        assertEquals(listOf(1L, 2L, 3L), requests.map { it.saveSequence })
        assertEquals(1, requests.map { it.saveSessionId }.distinct().size)
    }

    @Test
    fun `a seek does not count as played time`() = test { viewModel ->
        // 10 played seconds, then a long jump, then 4 more: still under the 15s minimum,
        // even though the position leapt far past the 30s floor.
        viewModel.play(0.0, 10.0)
        viewModel.onTick(500.0, 600.0, isPlaying = true)
        viewModel.play(500.0, 504.0)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `paused ticks accumulate nothing`() = test { viewModel ->
        repeat(100) { viewModel.onTick(50.0, 600.0, isPlaying = false) }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `a watched verdict mid-session refreshes continue watching`() = test { viewModel ->
        watchedResponse = true
        viewModel.play(580.0, 596.0)
        assertEquals(1, requests.size)
        assertEquals(1, refreshes)
    }

    @Test
    fun `ending the session saves once more and refreshes`() = test { viewModel ->
        viewModel.play(30.0, 46.0)
        viewModel.endSession(46.0, 600.0)
        advanceUntilIdle()
        assertEquals(2, requests.size)
        assertEquals(listOf(1L, 2L), requests.map { it.saveSequence })
        assertEquals(1, refreshes)
    }

    @Test
    fun `an early exit below the floor saves nothing`() = test { viewModel ->
        viewModel.play(0.0, 10.0)
        viewModel.endSession(10.0, 600.0)
        advanceUntilIdle()
        assertTrue(requests.isEmpty())
        assertEquals(0, refreshes)
    }

    @Test
    fun `resume and seek followed by exit before 15 played seconds sends no write`() =
        test { viewModel ->
            viewModel.play(300.0, 310.0)
            viewModel.endSession(500.0, 600.0)
            advanceUntilIdle()
            assertTrue(requests.isEmpty())
        }

    @Test
    fun `a valid final save requires 15 seconds of actual playback`() = test { viewModel ->
        viewModel.play(0.0, 16.0)
        viewModel.endSession(300.0, 600.0)
        advanceUntilIdle()
        assertEquals(1, requests.size)
        assertEquals(300.0, requests.single().progressSec, 0.0)
    }

    @Test
    fun `a hung exit save gives up after the timeout and still refreshes`() = test { viewModel ->
        viewModel.play(0.0, 16.0)
        save = { _, _ -> awaitCancellation() }
        viewModel.endSession(300.0, 600.0)
        assertEquals(0, refreshes)
        advanceUntilIdle()
        assertEquals(0, refreshes)
        assertTrue(viewModel.progressSyncUiState.value is ProgressSyncUiState.Failed)
    }

    @Test
    fun `ending twice writes only once`() = test { viewModel ->
        viewModel.play(0.0, 16.0)
        viewModel.endSession(300.0, 600.0)
        viewModel.endSession(300.0, 600.0)
        advanceUntilIdle()
        assertEquals(1, requests.size)
    }

    @Test
    fun `periodic failure is non-blocking and a retry reuses the session with a higher sequence`() =
        test { viewModel ->
            save = { _, request ->
                requests += request
                ApiResult.Failure(AppError.Network)
            }
            viewModel.play(30.0, 46.0)
            advanceUntilIdle()

            val failed = viewModel.progressSyncUiState.value as ProgressSyncUiState.Failed
            assertTrue(failed.message.contains("Couldn't reach the server"))

            save = { _, request ->
                requests += request
                ApiResult.Success(MovieWatchProgressUpdateData(watched = false))
            }
            viewModel.retryFailedSave()
            advanceUntilIdle()

            assertEquals(ProgressSyncUiState.Synced, viewModel.progressSyncUiState.value)
            assertEquals(listOf(1L, 2L), requests.map { it.saveSequence })
            assertEquals(1, requests.map { it.saveSessionId }.distinct().size)
            assertEquals(1, refreshes)
        }

    @Test
    fun `a later cadence success clears a periodic error`() = test { viewModel ->
        var fail = true
        save = { _, request ->
            requests += request
            if (fail) {
                ApiResult.Failure(AppError.Timeout)
            } else {
                ApiResult.Success(MovieWatchProgressUpdateData(watched = false))
            }
        }
        viewModel.play(30.0, 46.0)
        advanceUntilIdle()
        assertTrue(viewModel.progressSyncUiState.value is ProgressSyncUiState.Failed)

        fail = false
        viewModel.play(46.0, 61.0)
        advanceUntilIdle()

        assertEquals(ProgressSyncUiState.Synced, viewModel.progressSyncUiState.value)
        assertEquals(1, refreshes)
    }

    @Test
    fun `exit failure remains retryable after the session ends`() = test { viewModel ->
        save = { _, request ->
            requests += request
            ApiResult.Failure(AppError.Network)
        }
        viewModel.play(0.0, 16.0)
        viewModel.endSession(300.0, 600.0)
        advanceUntilIdle()

        assertTrue(viewModel.progressSyncUiState.value is ProgressSyncUiState.Failed)
        save = { _, request ->
            requests += request
            ApiResult.Success(MovieWatchProgressUpdateData(watched = false))
        }
        viewModel.retryFailedSave()
        advanceUntilIdle()

        assertEquals(ProgressSyncUiState.Synced, viewModel.progressSyncUiState.value)
        assertEquals(listOf(1L, 2L), requests.map { it.saveSequence })
        assertEquals(1, refreshes)
    }

    @Test
    fun `movie A failure survives movie B success and retries with A session`() =
        test { viewModel ->
            val calls = mutableListOf<Pair<Long, UpdateMovieWatchProgressRequest>>()
            var failMovieA = true
            save = { movieId, request ->
                calls += movieId to request
                if (movieId == 7L && failMovieA) {
                    ApiResult.Failure(AppError.Network)
                } else {
                    ApiResult.Success(MovieWatchProgressUpdateData(watched = false))
                }
            }

            viewModel.play(30.0, 46.0)
            advanceUntilIdle()
            val movieAFailure = calls.single().second

            viewModel.startSession(movieId = 8)
            viewModel.play(30.0, 46.0)
            advanceUntilIdle()

            assertTrue(viewModel.progressSyncUiState.value is ProgressSyncUiState.Failed)
            failMovieA = false
            viewModel.retryFailedSave()
            advanceUntilIdle()

            assertEquals(ProgressSyncUiState.Synced, viewModel.progressSyncUiState.value)
            assertEquals(listOf(7L, 8L, 7L), calls.map { it.first })
            val movieARetry = calls.last().second
            assertEquals(movieAFailure.saveSessionId, movieARetry.saveSessionId)
            assertEquals(movieAFailure.saveSequence + 1, movieARetry.saveSequence)
            assertEquals(movieAFailure.progressSec, movieARetry.progressSec, 0.0)
        }

    @Test
    fun `retry resends every failed session sequentially and refreshes once`() =
        test { viewModel ->
            val calls = mutableListOf<Pair<Long, UpdateMovieWatchProgressRequest>>()
            var failing = true
            save = { movieId, request ->
                calls += movieId to request
                if (failing) {
                    ApiResult.Failure(AppError.Network)
                } else {
                    ApiResult.Success(MovieWatchProgressUpdateData(watched = false))
                }
            }

            viewModel.play(30.0, 46.0)
            viewModel.startSession(movieId = 8)
            viewModel.play(40.0, 56.0)
            advanceUntilIdle()
            val initialByMovie = calls.associate { it.first to it.second }

            failing = false
            viewModel.retryFailedSave()
            advanceUntilIdle()

            assertEquals(listOf(7L, 8L, 7L, 8L), calls.map { it.first })
            calls.drop(2).forEach { (movieId, retry) ->
                val initial = requireNotNull(initialByMovie[movieId])
                assertEquals(initial.saveSessionId, retry.saveSessionId)
                assertEquals(initial.saveSequence + 1, retry.saveSequence)
                assertEquals(initial.progressSec, retry.progressSec, 0.0)
            }
            assertEquals(ProgressSyncUiState.Synced, viewModel.progressSyncUiState.value)
            assertEquals(1, refreshes)
        }

    @Test
    fun `a session that fails again remains pending after other retries succeed`() =
        test { viewModel ->
            val calls = mutableListOf<Pair<Long, UpdateMovieWatchProgressRequest>>()
            var phase = 0
            save = { movieId, request ->
                calls += movieId to request
                when {
                    phase == 0 -> ApiResult.Failure(AppError.Network)
                    phase == 1 && movieId == 7L -> ApiResult.Failure(AppError.Timeout)
                    else -> ApiResult.Success(MovieWatchProgressUpdateData(watched = false))
                }
            }

            viewModel.play(30.0, 46.0)
            viewModel.startSession(movieId = 8)
            viewModel.play(30.0, 46.0)
            advanceUntilIdle()

            phase = 1
            viewModel.retryFailedSave()
            advanceUntilIdle()

            assertTrue(viewModel.progressSyncUiState.value is ProgressSyncUiState.Failed)
            assertEquals(listOf(7L, 8L, 7L, 8L), calls.map { it.first })

            phase = 2
            viewModel.retryFailedSave()
            advanceUntilIdle()

            assertEquals(7L, calls.last().first)
            assertEquals(3L, calls.last().second.saveSequence)
            assertEquals(ProgressSyncUiState.Synced, viewModel.progressSyncUiState.value)
        }

    @Test
    fun `later same-session success wins when an older failure resolves last`() =
        test { viewModel ->
            val calls = mutableListOf<Pair<Long, UpdateMovieWatchProgressRequest>>()
            val olderMovieAResult =
                CompletableDeferred<ApiResult<MovieWatchProgressUpdateData>>()
            var retrying = false
            save = { movieId, request ->
                calls += movieId to request
                when {
                    retrying -> ApiResult.Success(
                        MovieWatchProgressUpdateData(watched = false),
                    )
                    movieId == 8L -> ApiResult.Failure(AppError.Timeout)
                    request.saveSequence == 1L -> olderMovieAResult.await()
                    else -> ApiResult.Success(MovieWatchProgressUpdateData(watched = false))
                }
            }

            viewModel.startSession(movieId = 8)
            viewModel.play(30.0, 46.0)
            advanceUntilIdle()

            viewModel.startSession(movieId = 7)
            viewModel.play(30.0, 46.0)
            runCurrent()
            viewModel.endSession(finalPositionSec = 46.0, durationSec = 600.0)
            runCurrent()

            assertEquals(listOf(1L, 2L), calls.filter { it.first == 7L }.map { it.second.saveSequence })
            olderMovieAResult.complete(ApiResult.Failure(AppError.Network))
            advanceUntilIdle()

            val remainingFailure =
                viewModel.progressSyncUiState.value as ProgressSyncUiState.Failed
            assertTrue(remainingFailure.message.contains("too long"))

            retrying = true
            viewModel.retryFailedSave()
            advanceUntilIdle()

            assertEquals(8L, calls.last().first)
            assertEquals(ProgressSyncUiState.Synced, viewModel.progressSyncUiState.value)
        }
}
