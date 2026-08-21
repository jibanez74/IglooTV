package com.igloo.blindpenguincoder.feature.player

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.data.model.MovieWatchProgressUpdateData
import com.igloo.blindpenguincoder.data.model.UpdateMovieWatchProgressRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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
    fun `an early exit below the floor saves nothing but still refreshes`() = test { viewModel ->
        viewModel.play(0.0, 10.0)
        viewModel.endSession(10.0, 600.0)
        advanceUntilIdle()
        assertTrue(requests.isEmpty())
        assertEquals(1, refreshes)
    }

    @Test
    fun `a hung exit save gives up after the timeout and still refreshes`() = test { viewModel ->
        save = { _, _ -> awaitCancellation() }
        viewModel.endSession(300.0, 600.0)
        assertEquals(0, refreshes)
        advanceUntilIdle()
        assertEquals(1, refreshes)
    }

    @Test
    fun `ending twice writes only once`() = test { viewModel ->
        viewModel.endSession(300.0, 600.0)
        viewModel.endSession(300.0, 600.0)
        advanceUntilIdle()
        assertEquals(1, requests.size)
    }
}
