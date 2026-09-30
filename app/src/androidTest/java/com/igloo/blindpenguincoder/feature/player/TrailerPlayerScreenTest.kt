package com.igloo.blindpenguincoder.feature.player

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.AnimationScaleRule
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.feature.home.findActivity
import com.igloo.blindpenguincoder.playback.youtube.FakeTrailerPlayerEngine
import com.igloo.blindpenguincoder.playback.youtube.TrailerPlayerEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The trailer player's own contract (design-system.md section 11.8.1), driven entirely through
 * the fake engine: what the chrome sends across the engine seam, how the global key map behaves
 * with the chrome hidden versus visible, and how the player resolves — Ended and Back both close
 * it, an error pins its one Retry.
 */
@RunWith(AndroidJUnit4::class)
class TrailerPlayerScreenTest {

    @get:Rule(order = 0)
    val animationScale = AnimationScaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private lateinit var engine: FakeTrailerPlayerEngine
    private var closes = 0
    private var hostActivity: Activity? = null

    /**
     * A lifecycle the test drives directly. The screen observes [LocalLifecycleOwner] to silence
     * playback in standby, and the host activity's own lifecycle cannot be moved from a test
     * without tearing the composition down with it.
     */
    private lateinit var lifecycleOwner: TestLifecycleOwner

    private fun setContent() {
        engine = FakeTrailerPlayerEngine()
        closes = 0
        lifecycleOwner = TestLifecycleOwner()
        lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
        composeRule.setContent {
            val context = LocalContext.current
            SideEffect { hostActivity = context.findActivity() }
            IglooTheme {
                CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                    TrailerPlayerScreen(
                        videoKey = "0xbkYZbdIVw",
                        title = "Official Trailer",
                        typeLabel = "Trailer",
                        onClose = { closes += 1 },
                        engineFactory = { _, _ -> engine },
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun moveLifecycleTo(state: Lifecycle.State) {
        composeRule.runOnUiThread { lifecycleOwner.registry.currentState = state }
        composeRule.waitForIdle()
    }

    /** Just the standby traffic, in order — the transport assertions use `playbackCommands`. */
    private fun lifecycleCommands(): List<String> =
        engine.commands.filter { it == "hostPaused" || it == "hostResumed" }

    private fun startPlaying(durationSec: Double = 143.0) {
        engine.emit(TrailerPlayerEvent.Ready(durationSec))
        engine.emit(TrailerPlayerEvent.StateChange(1))
        composeRule.waitForIdle()
    }

    private fun pressBack() {
        composeRule.runOnUiThread {
            (checkNotNull(hostActivity) as ComponentActivity).onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
    }

    /** Runs the auto-hide clock dry so the chrome rests hidden over the playing video. */
    private fun letChromeHide() {
        composeRule.mainClock.autoAdvance = false
        composeRule.mainClock.advanceTimeBy(4_500)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
    }

    /** Past the screen's 12s ready watchdog, with room to spare. */
    private fun advancePastTheReadyWatchdog() {
        composeRule.mainClock.autoAdvance = false
        composeRule.mainClock.advanceTimeBy(12_500)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
    }

    @Test
    fun entryFocusLandsOnPlayPause() {
        setContent()

        composeRule.onNodeWithTag("trailer_play_pause").assertIsFocused()
        composeRule.onNodeWithTag("trailer_loading").assertExists()
    }

    @Test
    fun centerTogglesPlayPauseThroughTheEngine() {
        setContent()
        startPlaying()

        val playPause = composeRule.onNodeWithTag("trailer_play_pause")
        playPause.assertContentDescriptionEquals("Pause")
        playPause.performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(listOf("pause"), engine.playbackCommands)

        engine.emit(TrailerPlayerEvent.StateChange(2))
        composeRule.waitForIdle()

        playPause.assertContentDescriptionEquals("Play")
        playPause.performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(listOf("pause", "play"), engine.playbackCommands)
    }

    @Test
    fun centerCanPausePendingAutoplayWhileBuffering() {
        setContent()

        composeRule.onNodeWithTag("trailer_play_pause")
            .performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(listOf("pause"), engine.playbackCommands)

        engine.emit(TrailerPlayerEvent.StateChange(1))
        engine.emit(TrailerPlayerEvent.StateChange(3))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("trailer_play_pause")
            .performKeyInput { pressKey(Key.DirectionCenter) }

        assertEquals(listOf("pause", "pause"), engine.playbackCommands)
    }

    @Test
    fun dedicatedPlayPauseAndToggleKeysStayDistinct() {
        setContent()
        startPlaying()
        val transport = composeRule.onNodeWithTag("trailer_play_pause")

        transport.performKeyInput { pressKey(Key.MediaPause) }
        transport.performKeyInput { pressKey(Key.MediaPause) }
        transport.performKeyInput { pressKey(Key.MediaPlay) }
        transport.performKeyInput { pressKey(Key.MediaPlay) }
        transport.performKeyInput { pressKey(Key.MediaPlayPause) }

        assertEquals(
            listOf("pause", "pause", "play", "play", "pause"),
            engine.playbackCommands,
        )
    }

    @Test
    fun mediaTransportKeysSeekTenSecondsRegardlessOfChrome() {
        setContent()
        startPlaying()
        engine.emit(TrailerPlayerEvent.Time(currentSec = 30.0, durationSec = 143.0))
        composeRule.waitForIdle()

        val playPause = composeRule.onNodeWithTag("trailer_play_pause")
        playPause.performKeyInput { pressKey(Key.MediaRewind) }
        playPause.performKeyInput { pressKey(Key.MediaFastForward) }

        assertEquals(listOf("seek:20.0", "seek:30.0"), engine.playbackCommands)
        // The optimistic seek moved the bar without waiting for the next engine tick.
        composeRule.onNodeWithContentDescription("30 seconds of 2 minutes and 23 seconds")
            .assertExists()
    }

    @Test
    fun withChromeHiddenLeftSeeksInsteadOfMovingFocus() {
        setContent()
        startPlaying()
        engine.emit(TrailerPlayerEvent.Time(currentSec = 60.0, durationSec = 143.0))
        composeRule.waitForIdle()
        letChromeHide()

        val playPause = composeRule.onNodeWithTag("trailer_play_pause")
        playPause.performKeyInput { pressKey(Key.DirectionLeft) }

        // Hidden chrome: the key was swallowed as a seek and focus never walked to Rewind.
        assertEquals(listOf("seek:50.0"), engine.playbackCommands)
        playPause.assertIsFocused()

        // That key also revealed the chrome, so the same key now moves focus and seeks nothing.
        playPause.performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.onNodeWithTag("trailer_rewind").assertIsFocused()
        assertEquals(listOf("seek:50.0"), engine.playbackCommands)
    }

    @Test
    fun withChromeVisibleLeftAndRightWalkTheTransportAndPinAtTheEdges()  {
        setContent()
        startPlaying()

        val playPause = composeRule.onNodeWithTag("trailer_play_pause")
        playPause.performKeyInput { pressKey(Key.DirectionLeft) }
        val rewind = composeRule.onNodeWithTag("trailer_rewind")
        rewind.assertIsFocused()
        rewind.performKeyInput { pressKey(Key.DirectionLeft) }
        rewind.assertIsFocused()

        rewind.performKeyInput { pressKey(Key.DirectionRight) }
        playPause.assertIsFocused()
        playPause.performKeyInput { pressKey(Key.DirectionRight) }
        val forward = composeRule.onNodeWithTag("trailer_forward")
        forward.assertIsFocused()
        forward.performKeyInput { pressKey(Key.DirectionRight) }
        forward.assertIsFocused()

        forward.performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("trailer_back").assertIsFocused()
        composeRule.onNodeWithTag("trailer_back").performKeyInput { pressKey(Key.DirectionDown) }
        playPause.assertIsFocused()
    }

    @Test
    fun backHidesChromeThenSecondBackCloses() {
        setContent()
        startPlaying()

        pressBack()
        assertEquals(0, closes)

        pressBack()
        assertEquals(1, closes)
    }

    @Test
    fun backClosesDirectlyWhilePausedBecauseChromeMayNotHide() {
        setContent()
        startPlaying()
        engine.emit(TrailerPlayerEvent.StateChange(2))
        composeRule.waitForIdle()

        pressBack()
        assertEquals(1, closes)
    }

    @Test
    fun endedInvokesClose() {
        setContent()
        startPlaying()

        engine.emit(TrailerPlayerEvent.StateChange(0))
        composeRule.waitForIdle()

        assertEquals(1, closes)
    }

    @Test
    fun errorStateShowsPinnedRetryAndBackStillCloses() {
        setContent()
        engine.emit(TrailerPlayerEvent.Error(150))
        composeRule.waitForIdle()

        composeRule
            .onNodeWithText("YouTube doesn't allow this video to play outside youtube.com.")
            .assertExists()
        val retry = composeRule.onNodeWithContentDescription("Retry playing trailer")
        retry.assertIsFocused()
        listOf(Key.DirectionLeft, Key.DirectionDown, Key.DirectionUp, Key.DirectionRight)
            .forEach { key ->
                retry.performKeyInput { pressKey(key) }
                retry.assertIsFocused()
            }

        pressBack()
        assertEquals(1, closes)
    }

    /**
     * A TV that goes to standby must be silent, and coming back must not restart the video on its
     * own — the user resumes deliberately (the engine's own contract, and section 11.8.1).
     */
    @Test
    fun standbySilencesPlaybackAndReturningDoesNotResumeIt() {
        setContent()
        startPlaying()
        val transportBeforeStandby = engine.playbackCommands

        moveLifecycleTo(Lifecycle.State.CREATED)
        moveLifecycleTo(Lifecycle.State.RESUMED)

        // The mount replays a resume, then standby and the return each land exactly once.
        assertEquals(listOf("hostResumed", "hostPaused", "hostResumed"), lifecycleCommands())
        // Silencing is the engine's job on standby; the chrome sends no transport of its own,
        // and nothing auto-plays on the way back.
        assertEquals(transportBeforeStandby, engine.playbackCommands)
    }

    /**
     * The ready watchdog: a player that never reaches ready must resolve into an error the user
     * can act on rather than an indefinite spinner — and must leave a ready player alone.
     */
    @Test
    fun aPlayerThatNeverBecomesReadyFailsIntoAnActionableError() {
        setContent()

        advancePastTheReadyWatchdog()

        composeRule.onNodeWithText("The video player took too long to load.").assertExists()
        composeRule.onNodeWithContentDescription("Retry playing trailer").assertIsFocused()
    }

    @Test
    fun theReadyWatchdogLeavesAPlayerThatStartedAlone() {
        setContent()
        startPlaying()

        advancePastTheReadyWatchdog()

        composeRule.onNodeWithTag("trailer_play_pause").assertContentDescriptionEquals("Pause")
        composeRule.onNodeWithContentDescription("Retry playing trailer").assertDoesNotExist()
    }

    @Test
    fun retryDiscardsTheFailedEngineAndStartsANewOne() {
        setContent()
        val failedEngine = engine
        engine.emit(TrailerPlayerEvent.Error(5))
        composeRule.waitForIdle()

        // The retry press lands on a fresh engine; swap the fake the factory hands out first.
        engine = FakeTrailerPlayerEngine()
        composeRule.onNodeWithContentDescription("Retry playing trailer")
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        assertTrue("the failed engine must be released", failedEngine.released)
        composeRule.onNodeWithTag("trailer_loading").assertExists()
        composeRule.onNodeWithTag("trailer_play_pause").assertIsFocused()
    }
}
