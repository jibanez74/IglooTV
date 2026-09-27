package com.igloo.blindpenguincoder.feature.player

import androidx.lifecycle.viewModelScope
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.data.model.UpdateWatchProgressRequest
import com.igloo.blindpenguincoder.data.model.WatchProgressUpdateData
import com.igloo.blindpenguincoder.playback.model.PlaybackMediaRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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

    private val requests = mutableListOf<UpdateWatchProgressRequest>()
    private var watchedResponse = false
    private var refreshes = 0

    private var save: suspend (PlaybackMediaRef, UpdateWatchProgressRequest) ->
    ApiResult<WatchProgressUpdateData> = { _, request ->
        requests += request
        ApiResult.Success(WatchProgressUpdateData(watched = watchedResponse))
    }

    /** The view model's scope rides Dispatchers.Main; share runTest's scheduler so virtual time moves both. */
    private fun test(block: suspend TestScope.(MoviePlayerViewModel) -> Unit) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val viewModel = MoviePlayerViewModel(
                saveProgress = { id, request -> save(id, request) },
                onWatchedStateCommitted = { refreshes++ },
            )
            viewModel.startSession(PlaybackMediaRef.Movie(7))
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
    fun `an early exit before the 30 second position floor saves nothing`() = test { viewModel ->
        viewModel.play(0.0, 10.0)
        viewModel.endSession(10.0, 600.0)
        advanceUntilIdle()
        assertTrue(requests.isEmpty())
        assertEquals(0, refreshes)
    }

    @Test
    fun `an exit after a seek saves the seek position without a played floor`() =
        test { viewModel ->
            viewModel.play(300.0, 310.0)
            viewModel.endSession(500.0, 600.0)
            advanceUntilIdle()
            assertEquals(500.0, requests.single().progressSec, 0.0)
            assertEquals(1, refreshes)
        }

    @Test
    fun `a final save needs no actual playback`() = test { viewModel ->
        viewModel.play(0.0, 5.0)
        viewModel.endSession(300.0, 600.0)
        advanceUntilIdle()
        assertEquals(1, requests.size)
        assertEquals(300.0, requests.single().progressSec, 0.0)
    }

    @Test
    fun `finishing within seconds of resuming still records the end`() = test { viewModel ->
        viewModel.play(590.0, 595.0)
        viewModel.endSession(600.0, 600.0)
        advanceUntilIdle()
        assertEquals(600.0, requests.single().progressSec, 0.0)
        assertEquals(1, refreshes)
    }

    @Test
    fun `a slow exit save is not abandoned`() = test { viewModel ->
        viewModel.play(0.0, 16.0)
        val record = save
        save = { id, request ->
            delay(5_000)
            record(id, request)
        }
        viewModel.endSession(300.0, 600.0)
        advanceTimeBy(2_001)
        assertTrue(requests.isEmpty())
        assertEquals(0, refreshes)
        advanceUntilIdle()
        assertEquals(300.0, requests.single().progressSec, 0.0)
        assertEquals(1, refreshes)
        assertTrue(viewModel.progressSyncUiState.value is ProgressSyncUiState.Synced)
    }

    @Test
    fun `an exit save survives the owner being cleared`() = test { viewModel ->
        viewModel.play(0.0, 16.0)
        val record = save
        save = { id, request ->
            delay(1_000)
            record(id, request)
        }
        viewModel.endSession(300.0, 600.0)
        viewModel.viewModelScope.cancel()
        advanceUntilIdle()
        assertEquals(300.0, requests.single().progressSec, 0.0)
        assertEquals(1, refreshes)
    }

    @Test
    fun `a pause writes the current position at once`() = test { viewModel ->
        viewModel.play(0.0, 5.0)
        viewModel.flushProgress(100.0, 600.0)
        advanceUntilIdle()
        assertEquals(100.0, requests.single().progressSec, 0.0)
        assertEquals(1L, requests.single().saveSequence)
        assertEquals(0, refreshes)
    }

    @Test
    fun `a pause before the position floor writes nothing`() = test { viewModel ->
        viewModel.play(0.0, 5.0)
        viewModel.flushProgress(10.0, 600.0)
        advanceUntilIdle()
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `a pause the server marks watched refreshes`() = test { viewModel ->
        watchedResponse = true
        viewModel.play(0.0, 5.0)
        viewModel.flushProgress(590.0, 600.0)
        advanceUntilIdle()
        assertEquals(1, requests.size)
        assertEquals(1, refreshes)
    }

    @Test
    fun `a flush within a second of the last dispatched position is skipped`() = test { viewModel ->
        viewModel.play(30.0, 45.0)
        assertEquals(1, requests.size)
        // ON_PAUSE and ON_STOP both flush the frozen position; only the first says anything.
        viewModel.flushProgress(45.5, 600.0)
        viewModel.flushProgress(45.5, 600.0)
        advanceUntilIdle()
        assertEquals(1, requests.size)
        viewModel.flushProgress(46.5, 600.0)
        viewModel.flushProgress(46.5, 600.0)
        advanceUntilIdle()
        assertEquals(2, requests.size)
        assertEquals(46.5, requests.last().progressSec, 0.0)
    }

    @Test
    fun `an exit right after a pause still writes and refreshes`() = test { viewModel ->
        viewModel.play(0.0, 5.0)
        viewModel.flushProgress(100.0, 600.0)
        viewModel.endSession(100.0, 600.0)
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L), requests.map { it.saveSequence })
        assertEquals(1, refreshes)
    }

    @Test
    fun `a flush restarts the cadence`() = test { viewModel ->
        viewModel.play(30.0, 45.0)
        viewModel.play(45.0, 50.0)
        viewModel.flushProgress(50.0, 600.0)
        advanceUntilIdle()
        assertEquals(2, requests.size)
        viewModel.play(50.0, 64.5)
        assertEquals(2, requests.size)
        viewModel.play(64.5, 65.0)
        assertEquals(3, requests.size)
        assertEquals(65.0, requests.last().progressSec, 0.0)
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
                ApiResult.Success(WatchProgressUpdateData(watched = false))
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
                ApiResult.Success(WatchProgressUpdateData(watched = false))
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
            ApiResult.Success(WatchProgressUpdateData(watched = false))
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
            val calls = mutableListOf<Pair<Long, UpdateWatchProgressRequest>>()
            var failMovieA = true
            save = { media, request ->
                val movieId = media.id
                calls += movieId to request
                if (movieId == 7L && failMovieA) {
                    ApiResult.Failure(AppError.Network)
                } else {
                    ApiResult.Success(WatchProgressUpdateData(watched = false))
                }
            }

            viewModel.play(30.0, 46.0)
            advanceUntilIdle()
            val movieAFailure = calls.single().second

            viewModel.startSession(PlaybackMediaRef.Movie(8))
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
            val calls = mutableListOf<Pair<Long, UpdateWatchProgressRequest>>()
            var failing = true
            save = { media, request ->
                val movieId = media.id
                calls += movieId to request
                if (failing) {
                    ApiResult.Failure(AppError.Network)
                } else {
                    ApiResult.Success(WatchProgressUpdateData(watched = false))
                }
            }

            viewModel.play(30.0, 46.0)
            viewModel.startSession(PlaybackMediaRef.Movie(8))
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
            val calls = mutableListOf<Pair<Long, UpdateWatchProgressRequest>>()
            var phase = 0
            save = { media, request ->
                val movieId = media.id
                calls += movieId to request
                when {
                    phase == 0 -> ApiResult.Failure(AppError.Network)
                    phase == 1 && movieId == 7L -> ApiResult.Failure(AppError.Timeout)
                    else -> ApiResult.Success(WatchProgressUpdateData(watched = false))
                }
            }

            viewModel.play(30.0, 46.0)
            viewModel.startSession(PlaybackMediaRef.Movie(8))
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
            val calls = mutableListOf<Pair<Long, UpdateWatchProgressRequest>>()
            val olderMovieAResult =
                CompletableDeferred<ApiResult<WatchProgressUpdateData>>()
            var retrying = false
            save = { media, request ->
                val movieId = media.id
                calls += movieId to request
                when {
                    retrying -> ApiResult.Success(
                        WatchProgressUpdateData(watched = false),
                    )
                    movieId == 8L -> ApiResult.Failure(AppError.Timeout)
                    request.saveSequence == 1L -> olderMovieAResult.await()
                    else -> ApiResult.Success(WatchProgressUpdateData(watched = false))
                }
            }

            viewModel.startSession(PlaybackMediaRef.Movie(8))
            viewModel.play(30.0, 46.0)
            advanceUntilIdle()

            viewModel.startSession(PlaybackMediaRef.Movie(7))
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
