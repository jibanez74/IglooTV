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

    fun newScope(context: CoroutineContext = UnconfinedTestDispatcher()): CoroutineScope =
        CoroutineScope(context).also { scopes += it }

    override fun starting(description: Description) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    override fun finished(description: Description) {
        scopes.forEach { it.cancel() }
        scopes.clear()
        Dispatchers.resetMain()
    }
}
