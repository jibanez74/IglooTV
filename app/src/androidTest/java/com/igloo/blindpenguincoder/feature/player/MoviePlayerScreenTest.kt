package com.igloo.blindpenguincoder.feature.player

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.AnimationScaleRule
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.data.model.MovieWatchProgressUpdateData
import com.igloo.blindpenguincoder.data.model.PlaybackMode
import com.igloo.blindpenguincoder.data.model.UpdateMovieWatchProgressRequest
import com.igloo.blindpenguincoder.feature.home.findActivity
import com.igloo.blindpenguincoder.playback.media3.FakeMoviePlayerEngine
import com.igloo.blindpenguincoder.playback.model.MoviePlayRequest
import com.igloo.blindpenguincoder.playback.model.MoviePlayerEvent
import com.igloo.blindpenguincoder.playback.model.PlaybackChapter
import com.igloo.blindpenguincoder.playback.model.TrackOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The movie player's contract (design-system.md section 11.8), driven entirely through the fake
 * engine: the resume decision, what the chrome sends across the engine seam, the track menus,
 * and how the player resolves — Ended and Back close it through the host and the exit save
 * lands, an error pins Retry, a revoked session pins Close.
 */
@RunWith(AndroidJUnit4::class)
class MoviePlayerScreenTest {

    @get:Rule(order = 0)
    val animationScale = AnimationScaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private lateinit var engine: FakeMoviePlayerEngine
    private lateinit var viewModel: MoviePlayerViewModel
    private val savedRequests = mutableListOf<UpdateMovieWatchProgressRequest>()
    private var closes = 0
    private var failProgressSaves = false
    private var hostActivity: Activity? = null
    private lateinit var restorationTester: StateRestorationTester
    private val restorationEngines = mutableListOf<FakeMoviePlayerEngine>()

    /** The host contract: closing unmounts the screen, which is what fires the exit save. */
    private var open by mutableStateOf(true)

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private lateinit var lifecycleOwner: TestLifecycleOwner

    private fun playRequest(
        resumeAtSec: Double? = null,
        chapters: List<PlaybackChapter> = emptyList(),
    ) = MoviePlayRequest(
        movieId = 7,
        title = "Heat",
        posterUrl = null,
        mimeType = "video/x-matroska",
        mode = PlaybackMode.Direct,
        audioTypeIndex = null,
        subtitleTypeIndex = null,
        audioCodec = null,
        audioCodecProfile = null,
        audioChannels = null,
        audioLabel = null,
        resumeAtSec = resumeAtSec,
        durationSec = 7200.0,
        chapters = chapters,
    )

    /** Three chapters, one with the blank title real file metadata produces. */
    private fun chapterFixture() = listOf(
        PlaybackChapter(title = "Opening Credits", startTimeSec = 0.0),
        PlaybackChapter(title = "", startTimeSec = 600.0),
        PlaybackChapter(title = "The Heist", startTimeSec = 1800.0),
    )

    private fun setContent(request: MoviePlayRequest = playRequest()) {
        engine = FakeMoviePlayerEngine()
        savedRequests.clear()
        closes = 0
        failProgressSaves = false
        open = true
        viewModel = MoviePlayerViewModel(
            saveProgress = { _, body ->
                savedRequests += body
                if (failProgressSaves) {
                    ApiResult.Failure(AppError.Network)
                } else {
                    ApiResult.Success(MovieWatchProgressUpdateData(watched = false))
                }
            },
            onWatchedStateCommitted = {},
        )
        lifecycleOwner = TestLifecycleOwner()
        lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
        composeRule.setContent {
            val context = LocalContext.current
            SideEffect { hostActivity = context.findActivity() }
            IglooTheme {
                CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                    if (open) {
                        MoviePlayerScreen(
                            request = request,
                            viewModel = viewModel,
                            onClose = {
                                closes += 1
                                open = false
                            },
                            engineFactory = { _, _ -> engine },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun setRestorableContent(request: MoviePlayRequest = playRequest()) {
        savedRequests.clear()
        closes = 0
        failProgressSaves = false
        open = true
        restorationEngines.clear()
        viewModel = MoviePlayerViewModel(
            saveProgress = { _, body ->
                savedRequests += body
                ApiResult.Success(MovieWatchProgressUpdateData(watched = false))
            },
            onWatchedStateCommitted = {},
        )
        lifecycleOwner = TestLifecycleOwner()
        lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
        restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            IglooTheme {
                CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                    if (open) {
                        MoviePlayerScreen(
                            request = request,
                            viewModel = viewModel,
                            onClose = {
                                closes += 1
                                open = false
                            },
                            engineFactory = { _, _ ->
                                FakeMoviePlayerEngine().also {
                                    engine = it
                                    restorationEngines += it
                                }
                            },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun startPlaying(durationSec: Double = 7200.0) {
        engine.emit(MoviePlayerEvent.Ready(durationSec))
        engine.emit(MoviePlayerEvent.IsPlayingChanged(true))
        composeRule.waitForIdle()
    }

    private fun emitTracks() {
        engine.emit(
            MoviePlayerEvent.TracksChanged(
                audio = listOf(
                    TrackOption(id = "1:0", label = "English · 5.1 surround", selected = true),
                    TrackOption(id = "2:0", label = "Spanish · Stereo", selected = false),
                ),
                subtitles = listOf(
                    TrackOption(id = "3:0", label = "English", selected = false),
                ),
            ),
        )
        composeRule.waitForIdle()
    }

    private fun pressBack() {
        composeRule.runOnUiThread {
            (checkNotNull(hostActivity) as ComponentActivity).onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
    }

    private fun letChromeHide() {
        composeRule.mainClock.autoAdvance = false
        composeRule.mainClock.advanceTimeBy(4_500)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
    }

    private fun awaitSaveCount(expected: Int) {
        composeRule.waitUntil(timeoutMillis = 5_000) { savedRequests.size >= expected }
    }

    /** Delivers realistic one-second ticks so final saves meet the actual-playback floor. */
    private fun playThrough(fromSec: Int, toSec: Int, durationSec: Double = 7200.0) {
        (fromSec..toSec).forEach { position ->
            engine.emit(MoviePlayerEvent.Time(position.toDouble(), durationSec))
        }
        composeRule.waitForIdle()
    }

    /**
     * Key events land on the focused node, so a menu opens by walking focus onto its button —
     * Right through whichever of the optional buttons this request and engine put in the row.
     */
    private fun openPlayerMenu(buttonTag: String) {
        composeRule.onNodeWithTag("movie_play_pause")
            .performKeyInput { pressKey(Key.DirectionRight) }
        var focusedTag = "movie_forward"
        for (tag in listOf("movie_chapters", "movie_audio", "movie_subtitles")) {
            if (focusedTag == buttonTag) break
            if (composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) continue
            composeRule.onNodeWithTag(focusedTag)
                .performKeyInput { pressKey(Key.DirectionRight) }
            focusedTag = tag
        }
        val button = composeRule.onNodeWithTag(buttonTag)
        button.assertIsFocused()
        button.performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()
    }

    @Test
    fun noResumeStartsFromTheBeginningAndFocusLandsOnPlayPause() {
        setContent()

        assertEquals(listOf("start:null:true"), engine.playbackCommands)
        composeRule.onNodeWithTag("movie_play_pause").assertIsFocused()
        composeRule.onNodeWithTag("movie_loading").assertExists()
        composeRule.onNodeWithTag("movie_resume_prompt").assertDoesNotExist()
    }

    @Test
    fun resumePromptGatesStartAndResumeStartsAtThePosition() {
        setContent(playRequest(resumeAtSec = 900.0))

        // No decision, no playback — and the prompt owns focus.
        assertEquals(emptyList<String>(), engine.playbackCommands)
        composeRule.onNodeWithText("Resume from 15:00?").assertExists()
        val resume = composeRule.onNodeWithTag("movie_resume")
        resume.assertIsFocused()

        resume.performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        assertEquals(listOf("start:900.0:true"), engine.playbackCommands)
        composeRule.onNodeWithTag("movie_resume_prompt").assertDoesNotExist()
        composeRule.onNodeWithTag("movie_play_pause").assertIsFocused()
    }

    @Test
    fun startOverStartsFromTheBeginning() {
        setContent(playRequest(resumeAtSec = 900.0))

        composeRule.onNodeWithTag("movie_resume")
            .performKeyInput { pressKey(Key.DirectionDown) }
        val startOver = composeRule.onNodeWithTag("movie_start_over")
        startOver.assertIsFocused()
        startOver.performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        assertEquals(listOf("start:null:true"), engine.playbackCommands)
    }

    @Test
    fun pausedIntentAndPositionRestoreWithoutAutoplayUntilExplicitPlay() {
        setRestorableContent()
        startPlaying()
        engine.emit(MoviePlayerEvent.Time(currentSec = 600.0, durationSec = 7200.0))
        composeRule.waitForIdle()

        val playPause = composeRule.onNodeWithTag("movie_play_pause")
        playPause.performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()
        playPause.assertContentDescriptionEquals("Play")
        val originalEngine = engine

        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        assertTrue(originalEngine.released)
        assertEquals(2, restorationEngines.size)
        assertEquals(listOf("start:600.0:false"), engine.playbackCommands)
        composeRule.onNodeWithText("10:00", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("movie_play_pause")
            .assertIsFocused()
            .assertContentDescriptionEquals("Play")

        composeRule.onNodeWithTag("movie_play_pause")
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        assertEquals(listOf("start:600.0:false", "play"), engine.playbackCommands)
        composeRule.onNodeWithTag("movie_play_pause")
            .assertContentDescriptionEquals("Pause")
    }

    @Test
    fun playingIntentResumeChoiceAndPositionRestoreWithAutoplay() {
        setRestorableContent(playRequest(resumeAtSec = 900.0))
        composeRule.onNodeWithTag("movie_resume")
            .performKeyInput { pressKey(Key.DirectionCenter) }
        startPlaying()
        engine.emit(MoviePlayerEvent.Time(currentSec = 930.0, durationSec = 7200.0))
        composeRule.waitForIdle()
        val originalEngine = engine

        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        assertTrue(originalEngine.released)
        assertEquals(2, restorationEngines.size)
        assertEquals(listOf("start:930.0:true"), engine.playbackCommands)
        composeRule.onNodeWithText("15:30", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("movie_resume_prompt").assertDoesNotExist()
        composeRule.onNodeWithTag("movie_play_pause")
            .assertIsFocused()
            .assertContentDescriptionEquals("Pause")
    }

    @Test
    fun backDuringTheResumePromptLeavesThePlayer() {
        setContent(playRequest(resumeAtSec = 900.0))

        pressBack()

        assertEquals(1, closes)
        assertEquals(emptyList<String>(), engine.playbackCommands)
    }

    @Test
    fun centerTogglesPlayPauseThroughTheEngine() {
        setContent()
        startPlaying()

        val playPause = composeRule.onNodeWithTag("movie_play_pause")
        playPause.assertContentDescriptionEquals("Pause")
        playPause.performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(listOf("start:null:true", "pause"), engine.playbackCommands)

        engine.emit(MoviePlayerEvent.IsPlayingChanged(false))
        composeRule.waitForIdle()

        playPause.assertContentDescriptionEquals("Play")
        playPause.performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(listOf("start:null:true", "pause", "play"), engine.playbackCommands)
    }

    @Test
    fun centerCanPausePendingAutoplayDuringInitialBufferingAndRebuffering() {
        setContent()

        composeRule.onNodeWithTag("movie_play_pause")
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()
        assertEquals(listOf("start:null:true", "pause"), engine.playbackCommands)

        engine.emit(MoviePlayerEvent.PlayWhenReadyChanged(true))
        engine.emit(MoviePlayerEvent.IsPlayingChanged(true))
        engine.emit(MoviePlayerEvent.Buffering)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("movie_play_pause")
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        assertEquals(listOf("start:null:true", "pause", "pause"), engine.playbackCommands)
    }

    @Test
    fun dedicatedPlayPauseAndToggleKeysStayDistinct() {
        setContent()
        startPlaying()
        val transport = composeRule.onNodeWithTag("movie_play_pause")

        transport.performKeyInput { pressKey(Key.MediaPause) }
        transport.performKeyInput { pressKey(Key.MediaPause) }
        transport.performKeyInput { pressKey(Key.MediaPlay) }
        transport.performKeyInput { pressKey(Key.MediaPlay) }
        transport.performKeyInput { pressKey(Key.MediaPlayPause) }
        composeRule.waitForIdle()

        assertEquals(
            listOf("start:null:true", "pause", "pause", "play", "play", "pause"),
            engine.playbackCommands,
        )
    }

    @Test
    fun mediaTransportKeysSeekTenSecondsRegardlessOfChrome() {
        setContent()
        startPlaying()
        engine.emit(MoviePlayerEvent.Time(currentSec = 30.0, durationSec = 7200.0))
        composeRule.waitForIdle()
        letChromeHide()

        val playPause = composeRule.onNodeWithTag("movie_play_pause")
        playPause.performKeyInput { pressKey(Key.MediaRewind) }
        playPause.performKeyInput { pressKey(Key.MediaFastForward) }

        assertEquals(
            listOf("start:null:true", "seek:20.0", "seek:30.0"),
            engine.playbackCommands,
        )
    }

    @Test
    fun withChromeHiddenLeftSeeksInsteadOfMovingFocus() {
        setContent()
        startPlaying()
        engine.emit(MoviePlayerEvent.Time(currentSec = 60.0, durationSec = 7200.0))
        composeRule.waitForIdle()
        letChromeHide()

        val playPause = composeRule.onNodeWithTag("movie_play_pause")
        playPause.performKeyInput { pressKey(Key.DirectionLeft) }

        assertEquals(listOf("start:null:true", "seek:50.0"), engine.playbackCommands)
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
    fun trackButtonsAppearOnlyOnceTheEngineReportsChoices() {
        setContent()
        startPlaying()

        composeRule.onNodeWithTag("movie_audio").assertDoesNotExist()
        composeRule.onNodeWithTag("movie_subtitles").assertDoesNotExist()

        emitTracks()

        composeRule.onNodeWithTag("movie_audio").assertExists()
        composeRule.onNodeWithTag("movie_subtitles").assertExists()
    }

    @Test
    fun audioMenuSelectionSwitchesTheTrackAndBackRestoresFocus() {
        setContent()
        startPlaying()
        emitTracks()

        openPlayerMenu("movie_audio")

        // Entry focus lands on the selected row; selection is not dismissal.
        composeRule.onNodeWithTag("movie_track_1:0").assertIsFocused()
        composeRule.onNodeWithTag("movie_track_1:0")
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("movie_track_2:0")
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        assertTrue("audio:2:0" in engine.playbackCommands)
        composeRule.onNodeWithTag("movie_track_menu").assertExists()

        pressBack()

        composeRule.onNodeWithTag("movie_track_menu").assertDoesNotExist()
        composeRule.onNodeWithTag("movie_audio").assertIsFocused()
    }

    @Test
    fun subtitleNoneRowTurnsSubtitlesOff() {
        setContent()
        startPlaying()
        emitTracks()

        openPlayerMenu("movie_subtitles")

        // Nothing selected, so entry focus is the "None" row.
        val none = composeRule.onNodeWithTag("movie_track_none")
        none.assertIsFocused()
        none.performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        assertTrue("subtitle:null" in engine.playbackCommands)
    }

    @Test
    fun aSingleChapterShowsNoChaptersButton() {
        setContent(playRequest(chapters = chapterFixture().take(1)))
        startPlaying()

        composeRule.onNodeWithTag("movie_chapters").assertDoesNotExist()
    }

    @Test
    fun chaptersButtonIsRequestDrivenAndPresentBeforeTheEngineReportsTracks() {
        setContent(playRequest(chapters = chapterFixture()))
        startPlaying()

        // Unlike the track buttons, which wait for TracksChanged.
        composeRule.onNodeWithTag("movie_chapters").assertExists()
        composeRule.onNodeWithTag("movie_audio").assertDoesNotExist()
        composeRule.onNodeWithTag("movie_subtitles").assertDoesNotExist()
    }

    @Test
    fun chapterMenuOpensOnTheCurrentChapterWithSpokenLabelsAndFallbackNames() {
        setContent(playRequest(chapters = chapterFixture()))
        startPlaying()
        // Inside the second chapter, whose title is blank.
        engine.emit(MoviePlayerEvent.Time(currentSec = 700.0, durationSec = 7200.0))
        composeRule.waitForIdle()

        openPlayerMenu("movie_chapters")

        val current = composeRule.onNodeWithTag("movie_chapter_1")
        current.assertIsFocused()
        current.assertIsSelected()
        // Spoken words, not a timecode, and no repeated title for the blank chapter.
        current.assertContentDescriptionEquals("Chapter 2 of 3, starts at 10 minutes")
        composeRule.onNodeWithTag("movie_chapter_2")
            .assertContentDescriptionEquals("Chapter 3 of 3, The Heist, starts at 30 minutes")
    }

    @Test
    fun chapterSelectionSeeksDismissesAndRestoresFocusToTheChaptersButton() {
        setContent(playRequest(chapters = chapterFixture()))
        startPlaying()

        openPlayerMenu("movie_chapters")
        composeRule.onNodeWithTag("movie_chapter_0").assertIsFocused()
        composeRule.onNodeWithTag("movie_chapter_0")
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("movie_chapter_1")
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        // A chapter pick is a jump, so unlike a track pick it dismisses.
        assertTrue("seek:600.0" in engine.playbackCommands)
        composeRule.onNodeWithTag("movie_chapter_menu").assertDoesNotExist()
        composeRule.onNodeWithTag("movie_chapters").assertIsFocused()
    }

    @Test
    fun backDismissesTheChapterMenuWithoutSeeking() {
        setContent(playRequest(chapters = chapterFixture()))
        startPlaying()

        openPlayerMenu("movie_chapters")
        pressBack()

        composeRule.onNodeWithTag("movie_chapter_menu").assertDoesNotExist()
        assertTrue(engine.playbackCommands.none { it.startsWith("seek:") })
        composeRule.onNodeWithTag("movie_chapters").assertIsFocused()
    }

    @Test
    fun aPlaybackErrorDismissesAnOpenMenuSoRetryIsNotHiddenUnderneathIt() {
        setContent(playRequest(chapters = chapterFixture()))
        startPlaying()
        openPlayerMenu("movie_chapters")

        engine.emit(MoviePlayerEvent.Error("The movie stream stopped unexpectedly."))
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("movie_chapter_menu").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Retry playing movie").assertIsFocused()
    }

    @Test
    fun mediaTransportKeysAreInertWhileTheChapterMenuIsOpen() {
        setContent(playRequest(chapters = chapterFixture()))
        startPlaying()
        openPlayerMenu("movie_chapters")
        val commandsBefore = engine.playbackCommands.toList()

        composeRule.onNodeWithTag("movie_chapter_0").performKeyInput {
            pressKey(Key.MediaPause)
            pressKey(Key.MediaPlayPause)
            pressKey(Key.MediaRewind)
        }
        composeRule.waitForIdle()

        assertEquals(commandsBefore, engine.playbackCommands)
        composeRule.onNodeWithTag("movie_chapter_menu").assertExists()
    }

    @Test
    fun endedClosesThePlayerAndTheExitSaveRecordsTheEnd() {
        setContent()
        startPlaying()
        // Playback below the position floor accrues real time without causing a cadence write;
        // Ended must turn the final snapshot into the known full duration.
        playThrough(fromSec = 0, toSec = 16)

        engine.emit(MoviePlayerEvent.Ended)
        composeRule.waitForIdle()

        assertEquals(1, closes)
        awaitSaveCount(1)
        val save = savedRequests.single()
        assertEquals(7200.0, save.progressSec, 0.001)
        assertEquals(7200.0, save.durationSec, 0.001)
    }

    @Test
    fun backClosesAndTheExitSaveRecordsTheLastPosition() {
        setContent()
        startPlaying()
        playThrough(fromSec = 584, toSec = 600)
        engine.emit(MoviePlayerEvent.IsPlayingChanged(false))
        composeRule.waitForIdle()

        // Paused, so chrome may not hide: one Back closes.
        pressBack()

        assertEquals(1, closes)
        // One cadence save becomes eligible just before exit, followed by the explicit final
        // save with a higher sequence. The final snapshot is the assertion that matters here.
        awaitSaveCount(2)
        assertEquals(600.0, savedRequests.last().progressSec, 0.001)
        assertEquals(listOf(1L, 2L), savedRequests.map { it.saveSequence })
    }

    @Test
    fun errorPinsRetryAndRetryRestartsAtTheLastPosition() {
        setContent()
        val failedEngine = engine
        startPlaying()
        engine.emit(MoviePlayerEvent.Time(currentSec = 600.0, durationSec = 7200.0))
        engine.emit(MoviePlayerEvent.Error("The movie stream stopped unexpectedly."))
        composeRule.waitForIdle()

        composeRule.onNodeWithText("The movie stream stopped unexpectedly.").assertExists()
        val retry = composeRule.onNodeWithContentDescription("Retry playing movie")
        retry.assertIsFocused()

        // The retry press lands on a fresh engine; swap the fake the factory hands out first.
        engine = FakeMoviePlayerEngine()
        retry.performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        assertTrue("the failed engine must be released", failedEngine.released)
        assertEquals(listOf("start:600.0:true"), engine.playbackCommands)
    }

    @Test
    fun unauthorizedErrorOffersCloseInsteadOfRetry() {
        setContent()
        engine.emit(
            MoviePlayerEvent.Error("Your session is no longer valid.", unauthorized = true),
        )
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Retry playing movie").assertDoesNotExist()
        val close = composeRule.onNodeWithContentDescription("Close player")
        close.assertIsFocused()
        close.performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        assertEquals(1, closes)
    }

    @Test
    fun standbySilencesPlaybackAndReturningDoesNotResumeIt() {
        setContent()
        startPlaying()
        val transportBeforeStandby = engine.playbackCommands

        composeRule.runOnUiThread {
            lifecycleOwner.registry.currentState = Lifecycle.State.CREATED
        }
        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
        }
        composeRule.waitForIdle()

        assertEquals(
            listOf("hostResumed", "hostPaused", "hostResumed"),
            engine.commands.filter { it == "hostPaused" || it == "hostResumed" },
        )
        assertEquals(transportBeforeStandby, engine.playbackCommands)
        val playPause = composeRule.onNodeWithTag("movie_play_pause")
        playPause.assertContentDescriptionEquals("Play")
        playPause.performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()
        assertEquals(transportBeforeStandby + "play", engine.playbackCommands)
    }

    @Test
    fun progressFailureIsPoliteReachableNonBlockingAndRetryRestoresTransportFocus() {
        setContent()
        startPlaying()
        failProgressSaves = true
        var position = 30.0
        while (position <= 46.0) {
            engine.emit(MoviePlayerEvent.Time(position, 7200.0))
            position += 0.5
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("movie_progress_error").assertExists()
        val transport = composeRule.onNodeWithTag("movie_play_pause")
        transport.assertIsFocused()
        transport.performKeyInput { pressKey(Key.DirectionUp) }
        val retry = composeRule.onNodeWithTag("movie_progress_retry")
        retry.assertIsFocused()

        failProgressSaves = false
        retry.performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("movie_progress_error").assertDoesNotExist()
        transport.assertIsFocused()
        assertTrue(engine.playbackCommands.none { it == "pause" })
    }

    // Transport keys must be swallowed, not just unhandled, while a modal or the error surface
    // is up: an unhandled media key falls back to the active MediaSession and would drive
    // playback underneath the dialog.

    @Test
    fun mediaTransportKeysAreInertDuringTheResumePrompt() {
        setContent(playRequest(resumeAtSec = 900.0))

        composeRule.onNodeWithTag("movie_resume").performKeyInput {
            pressKey(Key.MediaPlay)
            pressKey(Key.MediaPause)
            pressKey(Key.MediaPlayPause)
            pressKey(Key.MediaFastForward)
        }
        composeRule.waitForIdle()

        assertEquals(emptyList<String>(), engine.playbackCommands)
        composeRule.onNodeWithTag("movie_resume_prompt").assertExists()
    }

    @Test
    fun mediaTransportKeysAreInertWhileATrackMenuIsOpen() {
        setContent()
        startPlaying()
        emitTracks()
        openPlayerMenu("movie_audio")
        val commandsBefore = engine.playbackCommands.toList()

        composeRule.onNodeWithTag("movie_track_1:0").performKeyInput {
            pressKey(Key.MediaPause)
            pressKey(Key.MediaPlayPause)
            pressKey(Key.MediaRewind)
        }
        composeRule.waitForIdle()

        assertEquals(commandsBefore, engine.playbackCommands)
        composeRule.onNodeWithTag("movie_track_menu").assertExists()
    }

    @Test
    fun mediaTransportKeysAreInertOnTheErrorSurface() {
        setContent()
        startPlaying()
        engine.emit(MoviePlayerEvent.Error("The movie stream stopped unexpectedly."))
        composeRule.waitForIdle()
        val commandsBefore = engine.playbackCommands.toList()

        composeRule.onNodeWithContentDescription("Retry playing movie").performKeyInput {
            pressKey(Key.MediaPlay)
            pressKey(Key.MediaPlayPause)
            pressKey(Key.MediaFastForward)
        }
        composeRule.waitForIdle()

        assertEquals(commandsBefore, engine.playbackCommands)
        composeRule.onNodeWithText("The movie stream stopped unexpectedly.").assertExists()
    }
}
