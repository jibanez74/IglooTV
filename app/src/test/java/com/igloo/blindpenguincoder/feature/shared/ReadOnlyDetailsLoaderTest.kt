package com.igloo.blindpenguincoder.feature.shared

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadOnlyDetailsLoaderTest {
    private data class ScreenState(
        val id: Long? = null,
        val details: DetailsState<String> = DetailsState.Loading,
    )

    @Test
    fun `late success and failure cannot replace a different open page`() = runTest {
        assertLateResultsIgnored(close = false)
    }

    @Test
    fun `late success and failure cannot reopen a closed overlay`() = runTest {
        assertLateResultsIgnored(close = true)
    }

    private fun TestScope.assertLateResultsIgnored(close: Boolean) {
        val lateResults = listOf(
            ApiResult.Success("old page"),
            ApiResult.Failure(AppError.Network),
        )
        for (result in lateResults) {
            lateinit var pending: Continuation<ApiResult<String>>
            val loader = ReadOnlyDetailsLoader(
                scope = backgroundScope,
                createState = { ScreenState(id = it) },
                openId = ScreenState::id,
                details = ScreenState::details,
                withDetails = { state, details -> state.copy(details = details) },
                fetch = { id: Long ->
                    if (id == 1L) {
                        // Deliberately ignore cancellation to exercise the stale-id guard too.
                        suspendCoroutine { pending = it }
                    } else {
                        ApiResult.Success("new page")
                    }
                },
                toUi = { it: String -> it },
            )
            loader.open(1)
            runCurrent()

            if (close) loader.close() else loader.open(2)
            runCurrent()
            pending.resume(result)
            runCurrent()

            assertEquals(
                if (close) ScreenState() else ScreenState(2, DetailsState.Loaded("new page")),
                loader.uiState.value,
            )
            loader.close()
        }
    }
}
