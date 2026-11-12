package com.igloo.blindpenguincoder

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.coroutines.CoroutineContext
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Runs each test with `Dispatchers.Main` on an unconfined test dispatcher, after the class's own
 * `@After` cancels every scope [newScope] handed out: SessionManager collects auth events for as
 * long as its scope lives, and leaking one leaves a collector running into the next test class.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule : TestWatcher() {
    private val scopes = mutableListOf<CoroutineScope>()
    private val closers = mutableListOf<() -> Unit>()

    fun newScope(context: CoroutineContext = UnconfinedTestDispatcher()): CoroutineScope =
        CoroutineScope(context).also { scopes += it }

    /**
     * Runs [close] when the test ends. A view model outlives the test body — its scope is not
     * runTest's child — so an unfinished load would still be on `Dispatchers.Main` when the next
     * class calls setMain. Closing cancels it, which is also what Back does in production.
     */
    fun closeAfter(close: () -> Unit) {
        closers += close
    }

    override fun starting(description: Description) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    override fun finished(description: Description) {
        closers.forEach { it() }
        closers.clear()
        scopes.forEach { it.cancel() }
        scopes.clear()
        Dispatchers.resetMain()
    }
}
