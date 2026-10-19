package com.igloo.blindpenguincoder.core.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The screen-reader flag's two blind spots (see [rememberLiveFlag]): a change reported before it
 * is true — TalkBack switched on while the app runs reported its settings change while no spoken
 * service was bound yet — and a change made while the app was away, which reports nothing. An
 * instrumented run cannot switch TalkBack itself (`UiAutomation` suppresses it), so the flag is
 * driven through a fake probe and subscription.
 */
@RunWith(AndroidJUnit4::class)
class LiveFlagTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var truth = false
    private var reportChange: () -> Unit = {}
    private val lifecycleOwner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private fun setContent() {
        composeRule.runOnUiThread { lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED }
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                val flag = rememberLiveFlag(
                    key = Unit,
                    probe = { truth },
                    subscribe = { changed ->
                        reportChange = changed
                        val unsubscribe: () -> Unit = { reportChange = {} }
                        unsubscribe
                    },
                    settleMs = SETTLE_MS,
                )
                BasicText(text = if (flag) "on" else "off", modifier = Modifier.testTag("flag"))
            }
        }
        composeRule.waitForIdle()
    }

    private fun flag() = composeRule.onNodeWithTag("flag")

    @Test
    fun aChangeIsReadWhenItIsReported() {
        setContent()
        flag().assertTextEquals("off")

        truth = true
        composeRule.runOnUiThread { reportChange() }
        composeRule.waitForIdle()

        flag().assertTextEquals("on")
    }

    @Test
    fun aChangeReportedBeforeItIsTrueIsReadAgainOnceItSettles() {
        setContent()
        composeRule.mainClock.autoAdvance = false

        // The callback fires while the service is still binding: still false when read.
        composeRule.runOnUiThread { reportChange() }
        composeRule.mainClock.advanceTimeByFrame()
        flag().assertTextEquals("off")

        // Binding finishes and nothing reports it; the settled re-read picks it up.
        truth = true
        composeRule.mainClock.advanceTimeBy(SETTLE_MS + FRAME_MS)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        flag().assertTextEquals("on")
    }

    @Test
    fun aChangeMadeWhileTheAppWasAwayIsReadOnResume() {
        setContent()
        composeRule.runOnUiThread { lifecycleOwner.registry.currentState = Lifecycle.State.STARTED }
        composeRule.waitForIdle()

        truth = true
        flag().assertTextEquals("off")

        composeRule.runOnUiThread { lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED }
        composeRule.waitForIdle()

        flag().assertTextEquals("on")
    }
}

private const val SETTLE_MS = 1_000L
private const val FRAME_MS = 16L
