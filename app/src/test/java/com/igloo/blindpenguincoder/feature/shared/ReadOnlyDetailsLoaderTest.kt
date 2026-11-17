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

    // Two re-reads of the same page (a watch room's member change, then a reconnect): the older
    // answer arriving last must not replace the newer one.
    @Test
    fun `an older read of the same page cannot land over a newer refresh`() = runTest {
        val pending = mutableListOf<Continuation<ApiResult<String>>>()
        val loader = ReadOnlyDetailsLoader(
            scope = backgroundScope,
            createState = { ScreenState(id = it) },
            openId = ScreenState::id,
            details = ScreenState::details,
            withDetails = { state, details -> state.copy(details = details) },
            // Ignores cancellation, so the generation guard is what is tested.
            fetch = { _: Long -> suspendCoroutine { pending += it } },
            toUi = { it: String -> it },
        )
        loader.open(1)
        runCurrent()
        pending.removeAt(0).resume(ApiResult.Success("v1"))
        runCurrent()

        loader.refresh()
        runCurrent()
        loader.refresh()
        runCurrent()
        val (older, newer) = pending
        newer.resume(ApiResult.Success("v3"))
        runCurrent()
        older.resume(ApiResult.Success("v2"))
        runCurrent()

        assertEquals(ScreenState(1, DetailsState.Loaded("v3")), loader.uiState.value)
        loader.close()
    }
}
