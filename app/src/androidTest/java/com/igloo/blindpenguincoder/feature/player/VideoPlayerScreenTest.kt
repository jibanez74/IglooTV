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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
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
import com.igloo.blindpenguincoder.data.model.WatchProgressUpdateData
import com.igloo.blindpenguincoder.data.model.PlaybackMode
import com.igloo.blindpenguincoder.data.model.UpdateWatchProgressRequest
import com.igloo.blindpenguincoder.feature.home.findActivity
import com.igloo.blindpenguincoder.playback.media3.FakeVideoPlayerEngine
import com.igloo.blindpenguincoder.playback.model.VideoPlayRequest
import com.igloo.blindpenguincoder.playback.model.VideoPlayerEvent
import com.igloo.blindpenguincoder.playback.model.PlaybackChapter
import com.igloo.blindpenguincoder.playback.model.PlayableSubtitleTrack
import com.igloo.blindpenguincoder.playback.model.TrackOption
import com.igloo.blindpenguincoder.playback.model.playbackModeLabel
import com.igloo.blindpenguincoder.playback.model.PlaybackMediaRef
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
class VideoPlayerScreenTest {

    @get:Rule(order = 0)
    val animationScale = AnimationScaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private lateinit var engine: FakeVideoPlayerEngine
    private lateinit var viewModel: VideoPlayerViewModel
    private val savedRequests = mutableListOf<UpdateWatchProgressRequest>()
    private var closes = 0
    private var failProgressSaves = false
    private var hostActivity: Activity? = null
    private lateinit var restorationTester: StateRestorationTester
    private val restorationEngines = mutableListOf<FakeVideoPlayerEngine>()
    private val engineRequests = mutableListOf<VideoPlayRequest>()
    private val requestedModes = mutableListOf<PlaybackMode>()
    private var currentRequest by mutableStateOf<VideoPlayRequest?>(null)

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
        mode: PlaybackMode = PlaybackMode.Direct,
        subtitleTypeIndex: Int? = null,
        subtitleTracks: List<PlayableSubtitleTrack> = emptyList(),
    ) = VideoPlayRequest(
        media = PlaybackMediaRef.Movie(7),
        title = "Heat",
        posterUrl = null,
        mimeType = "video/x-matroska",
        mode = mode,
        audioTypeIndex = null,
        subtitleTypeIndex = subtitleTypeIndex,
        subtitleTracks = subtitleTracks,
        resumeAtSec = resumeAtSec,
        durationSec = 7200.0,
        chapters = chapters,
    )

    /** A wire list whose ordinal 1 is a bitmap stream an HLS source cannot serve. */
    private fun mixedSubtitleTracks() = listOf(
        PlayableSubtitleTrack(label = "English"),
        PlayableSubtitleTrack(label = "English · PGS", imageBased = true),
        PlayableSubtitleTrack(label = "Spanish"),
    )

    /** Three chapters, one with the blank title real file metadata produces. */
    private fun chapterFixture() = listOf(
        PlaybackChapter(title = "Opening Credits", startTimeSec = 0.0),
        PlaybackChapter(title = "", startTimeSec = 600.0),
        PlaybackChapter(title = "The Heist", startTimeSec = 1800.0),
    )

    private fun setContent(request: VideoPlayRequest = playRequest()) {
        engine = FakeVideoPlayerEngine()
        currentRequest = request
        engineRequests.clear()
        requestedModes.clear()
        savedRequests.clear()
        closes = 0
        failProgressSaves = false
        open = true
        viewModel = VideoPlayerViewModel(
            saveProgress = { _, body ->
                savedRequests += body
                if (failProgressSaves) {
                    ApiResult.Failure(AppError.Network)
                } else {
                    ApiResult.Success(WatchProgressUpdateData(watched = false))
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
                        VideoPlayerScreen(
                            request = requireNotNull(currentRequest),
                            viewModel = viewModel,
                            onClose = {
                                closes += 1
                                open = false
                            },
                            onPlaybackModeRequested = { mode ->
                                requestedModes += mode
                                currentRequest = requireNotNull(currentRequest).copy(mode = mode)
                            },
                            onTrackSelectionChanged = { audioTypeIndex, subtitleTypeIndex ->
                                currentRequest = requireNotNull(currentRequest).copy(
                                    audioTypeIndex = audioTypeIndex,
                                    subtitleTypeIndex = subtitleTypeIndex,
                                )
                            },
                            engineFactory = { _, engineRequest ->
                                engineRequests += engineRequest
                                engine.setCurrentTrackSelection(
                                    engineRequest.audioTypeIndex,
                                    engineRequest.subtitleTypeIndex,
                                )
                                engine
                            },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun setRestorableContent(request: VideoPlayRequest = playRequest()) {
        savedRequests.clear()
        closes = 0
        failProgressSaves = false
        open = true
        currentRequest = request
        restorationEngines.clear()
        engineRequests.clear()
        requestedModes.clear()
        viewModel = VideoPlayerViewModel(
            saveProgress = { _, body ->
                savedRequests += body
                ApiResult.Success(WatchProgressUpdateData(watched = false))
            },
            onWatchedStateCommitted = {},
        )
        lifecycleOwner = TestLifecycleOwner()
        lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
        restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            val context = LocalContext.current
            SideEffect { hostActivity = context.findActivity() }
            IglooTheme {
                CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                    if (open) {
                        VideoPlayerScreen(
                            request = requireNotNull(currentRequest),
                            viewModel = viewModel,
                            onClose = {
                                closes += 1
                                open = false
                            },
                            onPlaybackModeRequested = { mode ->
                                requestedModes += mode
                                currentRequest = requireNotNull(currentRequest).copy(mode = mode)
                            },
                            onTrackSelectionChanged = { audioTypeIndex, subtitleTypeIndex ->
                                currentRequest = requireNotNull(currentRequest).copy(
                                    audioTypeIndex = audioTypeIndex,
                                    subtitleTypeIndex = subtitleTypeIndex,
                                )
                            },
                            engineFactory = { _, engineRequest ->
                                engineRequests += engineRequest
                                FakeVideoPlayerEngine().also {
                                    it.setCurrentTrackSelection(
                                        engineRequest.audioTypeIndex,
                                        engineRequest.subtitleTypeIndex,
                                    )
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
        engine.emit(VideoPlayerEvent.Ready(durationSec))
        engine.emit(VideoPlayerEvent.IsPlayingChanged(true))
        composeRule.waitForIdle()
    }

    private fun emitTracks() {
        engine.audioTypeIndices["1:0"] = 0
        engine.audioTypeIndices["2:0"] = 1
        engine.subtitleTypeIndices["3:0"] = 0
        engine.emit(
            VideoPlayerEvent.TracksChanged(
                audio = listOf(
                    TrackOption(
                        id = "1:0",
                        label = "English · 5.1 surround",
                        selected = engine.currentAudioTypeIndex == null ||
                            engine.currentAudioTypeIndex == 0,
                    ),
                    TrackOption(
                        id = "2:0",
                        label = "Spanish · Stereo",
                        selected = engine.currentAudioTypeIndex == 1,
                    ),
                ),
                subtitles = listOf(
                    TrackOption(
                        id = "3:0",
                        label = "English",
                        selected = engine.currentSubtitleTypeIndex == 0,
                    ),
                ),
            ),
        )
        composeRule.waitForIdle()
    }

    /**
     * What the engine emits under HLS for [mixedSubtitleTracks] with the bitmap choice
     * remembered: the live VTT row plus the inert, selected image-based row.
     */
    private fun emitHlsSubtitleRows() {
        engine.subtitleTypeIndices["3:0"] = 0
        engine.emit(
            VideoPlayerEvent.TracksChanged(
                audio = emptyList(),
                subtitles = listOf(
                    TrackOption(id = "3:0", label = "English", selected = false),
                    TrackOption(
                        id = "image:1",
                        label = "English · PGS (image-based)",
                        selected = true,
                        enabled = false,
                    ),
                ),
            ),
        )
        composeRule.waitForIdle()
    }

    private fun emitQualityOptions(
        selectedId: String = "Direct",
        requestedMode: PlaybackMode = PlaybackMode.valueOf(selectedId),
    ) {
        engine.emit(qualityOptionsEvent(selectedId, requestedMode))
        composeRule.waitForIdle()
    }

    private fun qualityOptionsEvent(
        selectedId: String,
        requestedMode: PlaybackMode,
    ) = VideoPlayerEvent.QualityOptionsChanged(
        PlaybackMode.entries.map { mode ->
            TrackOption(
                id = mode.name,
                label = playbackModeLabel(mode),
                selected = selectedId == mode.name,
            )
        },
        requestedMode,
    )

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

    /** Delivers realistic one-second ticks, the cadence a real engine accrues played time from. */
    private fun playThrough(fromSec: Int, toSec: Int, durationSec: Double = 7200.0) {
        (fromSec..toSec).forEach { position ->
            engine.emit(VideoPlayerEvent.Time(position.toDouble(), durationSec))
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
        for (tag in listOf("movie_chapters", "movie_audio", "movie_subtitles", "movie_quality")) {
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
        // The heading shows a timecode; the button speaks it, because digits read as noise.
        resume.assertContentDescriptionEquals("Resume from 15 minutes")

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
        engine.emit(VideoPlayerEvent.Time(currentSec = 600.0, durationSec = 7200.0))
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
        engine.emit(VideoPlayerEvent.Time(currentSec = 930.0, durationSec = 7200.0))
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
        assertTrue(engine.released)
    }

    @Test
    fun centerTogglesPlayPauseThroughTheEngine() {
        setContent()
        startPlaying()

        val playPause = composeRule.onNodeWithTag("movie_play_pause")
        playPause.assertContentDescriptionEquals("Pause")
        playPause.performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(listOf("start:null:true", "pause"), engine.playbackCommands)

        engine.emit(VideoPlayerEvent.IsPlayingChanged(false))
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

        engine.emit(VideoPlayerEvent.PlayWhenReadyChanged(true))
        engine.emit(VideoPlayerEvent.IsPlayingChanged(true))
        engine.emit(VideoPlayerEvent.Buffering)
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
        engine.emit(VideoPlayerEvent.Time(currentSec = 30.0, durationSec = 7200.0))
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
        engine.emit(VideoPlayerEvent.Time(currentSec = 60.0, durationSec = 7200.0))
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
        assertTrue(engine.released)
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
    fun anInertImageBasedSubtitleRowIsFocusableSelectedAndNotActivatable() {
        setContent(
            playRequest(
                mode = PlaybackMode.Remux,
                subtitleTypeIndex = 1,
                subtitleTracks = mixedSubtitleTracks(),
            ),
        )
        startPlaying()
        emitHlsSubtitleRows()

        openPlayerMenu("movie_subtitles")

        // Entry focus lands on the remembered choice even though its row is inert.
        val inert = composeRule.onNodeWithTag("movie_track_image:1")
        inert.assertIsFocused()
        inert.assertIsSelected()
        inert.performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        // Inert means inert: no selection command crossed the seam, and the remembered choice
        // still owns the mark — "None" must not claim it.
        assertTrue(engine.playbackCommands.none { it.startsWith("subtitle:") })
        composeRule.onNodeWithTag("movie_track_none").assertIsNotSelected()

        // The focus chain continues past the inert row.
        inert.performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("movie_track_3:0").assertIsFocused()
    }

    @Test
    fun selectingARealVttUnderHlsReplacesTheBitmapMemory() {
        setContent(
            playRequest(
                mode = PlaybackMode.Remux,
                subtitleTypeIndex = 1,
                subtitleTracks = mixedSubtitleTracks(),
            ),
        )
        startPlaying()
        emitHlsSubtitleRows()

        openPlayerMenu("movie_subtitles")
        composeRule.onNodeWithTag("movie_track_image:1")
            .performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("movie_track_3:0")
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        // An explicit new choice wins: the persisted request now carries the text ordinal.
        assertTrue("subtitle:3:0" in engine.playbackCommands)
        assertEquals(0, requireNotNull(currentRequest).subtitleTypeIndex)
    }

    @Test
    fun qualityButtonAppearsOnlyOnceTheEngineReportsModes() {
        setContent()
        startPlaying()

        composeRule.onNodeWithTag("movie_quality").assertDoesNotExist()

        emitQualityOptions()

        composeRule.onNodeWithTag("movie_quality").assertExists()
    }

    @Test
    fun qualityMenuShowsAllSevenModesInNormativeOrder() {
        setContent()
        startPlaying()
        emitQualityOptions()

        openPlayerMenu("movie_quality")

        PlaybackMode.entries.forEach { mode ->
            composeRule.onNodeWithTag("movie_track_${mode.name}").assertExists()
        }
    }

    @Test
    fun qualityMenuSwitchesModeWithoutDismissingAndTheMarkFollowsTheEngine() {
        setContent()
        startPlaying()
        emitQualityOptions()

        openPlayerMenu("movie_quality")

        // Entry focus lands on the current mode; selection is not dismissal.
        composeRule.onNodeWithTag("movie_track_Direct").assertIsFocused()
        composeRule.onNodeWithTag("movie_track_Direct")
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("movie_track_Remux")
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        assertTrue("quality:Remux" in engine.playbackCommands)
        composeRule.onNodeWithTag("movie_track_menu").assertExists()
        // The press asks; it does not decide. Nothing is persisted until the engine says so.
        assertEquals(emptyList<PlaybackMode>(), requestedModes)
        assertEquals(PlaybackMode.Direct, requireNotNull(currentRequest).mode)

        // The selected mark is engine truth: it moves when the new session's options arrive.
        emitQualityOptions(selectedId = "Remux")
        composeRule.onNodeWithTag("movie_track_Remux").assertIsSelected()
        composeRule.onNodeWithTag("movie_track_Direct").assertIsNotSelected()
        assertEquals(listOf(PlaybackMode.Remux), requestedModes)
        assertEquals(PlaybackMode.Remux, requireNotNull(currentRequest).mode)

        // The collector must compare with the latest host request, not the Direct request it
        // captured when this engine was created. Otherwise the return to Direct is discarded.
        composeRule.onNodeWithTag("movie_track_Remux")
            .performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("movie_track_Direct")
            .performKeyInput { pressKey(Key.DirectionCenter) }
        emitQualityOptions(selectedId = "Direct", requestedMode = PlaybackMode.Direct)
        assertEquals(listOf(PlaybackMode.Remux, PlaybackMode.Direct), requestedModes)
        assertEquals(PlaybackMode.Direct, requireNotNull(currentRequest).mode)

        pressBack()

        composeRule.onNodeWithTag("movie_track_menu").assertDoesNotExist()
        composeRule.onNodeWithTag("movie_quality").assertIsFocused()

        composeRule.runOnUiThread {
            lifecycleOwner.registry.currentState = Lifecycle.State.CREATED
        }
        composeRule.waitForIdle()
        engine = FakeVideoPlayerEngine()
        composeRule.runOnUiThread {
            lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
        }
        composeRule.waitForIdle()
        assertEquals(PlaybackMode.Direct, engineRequests.last().mode)
    }

    @Test
    fun bufferingShowsTheEngineStatusNarrationUntilReadyClearsIt() {
        setContent()
        startPlaying()

        engine.emit(VideoPlayerEvent.Buffering)
        engine.emit(VideoPlayerEvent.StatusMessage("Waiting for the server to free up…"))
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Waiting for the server to free up…").assertExists()
        composeRule.onNodeWithContentDescription("Waiting for the server to free up…")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )

        engine.emit(VideoPlayerEvent.StatusMessage("Reconnecting to the stream…"))
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Reconnecting to the stream…")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )

        startPlaying()
        engine.emit(VideoPlayerEvent.Buffering)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Buffering…").assertExists()
        composeRule.onNodeWithContentDescription("Buffering")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
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
        engine.emit(VideoPlayerEvent.Time(currentSec = 700.0, durationSec = 7200.0))
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

        engine.emit(VideoPlayerEvent.Error("The movie stream stopped unexpectedly."))
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

        engine.emit(VideoPlayerEvent.Ended)
        composeRule.waitForIdle()

        assertEquals(1, closes)
        assertTrue(engine.released)
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
        engine.emit(VideoPlayerEvent.IsPlayingChanged(false))
        composeRule.waitForIdle()

        // Paused, so chrome may not hide: one Back closes.
        pressBack()

        assertEquals(1, closes)
        assertTrue(engine.released)
        // One cadence save becomes eligible just before exit, followed by the explicit final
        // save with a higher sequence. The final snapshot is the assertion that matters here.
        awaitSaveCount(2)
        assertEquals(600.0, savedRequests.last().progressSec, 0.001)
        assertEquals(listOf(1L, 2L), savedRequests.map { it.saveSequence })
    }

    @Test
    fun pauseWritesTheCurrentPositionAtOnce() {
        setContent()
        startPlaying()
        engine.emit(VideoPlayerEvent.Time(currentSec = 600.0, durationSec = 7200.0))
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("movie_play_pause")
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        assertEquals(listOf("start:null:true", "pause"), engine.playbackCommands)
        awaitSaveCount(1)
        assertEquals(600.0, savedRequests.single().progressSec, 0.001)
        assertEquals(7200.0, savedRequests.single().durationSec, 0.001)
    }

    @Test
    fun aTripToTheBackgroundWritesOnceAndTheRebuiltEngineWritesNothing() {
        setContent()
        startPlaying()
        engine.emit(VideoPlayerEvent.Time(currentSec = 600.0, durationSec = 7200.0))
        composeRule.waitForIdle()

        composeRule.runOnUiThread {
            lifecycleOwner.registry.currentState = Lifecycle.State.CREATED
        }
        composeRule.waitForIdle()
        awaitSaveCount(1)

        engine = FakeVideoPlayerEngine()
        composeRule.runOnUiThread {
            lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
        }
        composeRule.waitForIdle()

        assertEquals(listOf("start:600.0:false"), engine.playbackCommands)
        // ON_PAUSE and ON_STOP both flush the frozen position and collapse into one write; the
        // rebuilt engine's paused start is not a pause of anything and writes nothing.
        assertEquals(1, savedRequests.size)
        assertEquals(600.0, savedRequests.single().progressSec, 0.001)
    }

    @Test
    fun aTickAfterEndedDoesNotLowerTheExitSave() {
        setContent()
        startPlaying()
        engine.emit(VideoPlayerEvent.Time(currentSec = 7000.0, durationSec = 7200.0))
        engine.emit(VideoPlayerEvent.Ended)
        engine.emit(VideoPlayerEvent.Time(currentSec = 7001.0, durationSec = 7200.0))
        composeRule.waitForIdle()

        assertEquals(1, closes)
        awaitSaveCount(1)
        assertEquals(7200.0, savedRequests.single().progressSec, 0.001)
    }

    @Test
    fun errorPinsRetryAndRetryRestartsAtTheLastPosition() {
        setContent()
        val failedEngine = engine
        startPlaying()
        engine.emit(VideoPlayerEvent.Time(currentSec = 600.0, durationSec = 7200.0))
        engine.emit(VideoPlayerEvent.Error("The movie stream stopped unexpectedly."))
        composeRule.waitForIdle()

        composeRule.onNodeWithText("The movie stream stopped unexpectedly.").assertExists()
        val retry = composeRule.onNodeWithContentDescription("Retry playing movie")
        retry.assertIsFocused()

        // The retry press lands on a fresh engine; swap the fake the factory hands out first.
        engine = FakeVideoPlayerEngine()
        retry.performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        assertTrue("the failed engine must be released", failedEngine.released)
        assertEquals(listOf("start:600.0:true"), engine.playbackCommands)
    }

    @Test
    fun unauthorizedErrorOffersCloseInsteadOfRetry() {
        setContent()
        engine.emit(
            VideoPlayerEvent.Error("Your session is no longer valid.", unauthorized = true),
        )
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Retry playing movie").assertDoesNotExist()
        val close = composeRule.onNodeWithContentDescription("Close player")
        close.assertIsFocused()
        close.performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        assertEquals(1, closes)
        assertTrue(engine.released)
    }

    @Test
    fun backgroundReleasesOldEngineAndReconstructsPausedAtLastPosition() {
        setContent()
        startPlaying()
        engine.emit(VideoPlayerEvent.Time(currentSec = 600.0, durationSec = 7200.0))
        composeRule.waitForIdle()
        val oldEngine = engine
        assertEquals(1, oldEngine.surfaceCreateCount)
        val transportBeforeStandby = oldEngine.playbackCommands

        composeRule.runOnUiThread {
            lifecycleOwner.registry.currentState = Lifecycle.State.CREATED
        }
        composeRule.waitForIdle()
        assertTrue(oldEngine.released)
        // A late system/media command has no path back into a released background engine.
        oldEngine.play()
        assertEquals(transportBeforeStandby, oldEngine.playbackCommands)

        engine = FakeVideoPlayerEngine()
        composeRule.runOnUiThread {
            lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
        }
        composeRule.waitForIdle()

        assertEquals(
            listOf("hostResumed", "hostPaused"),
            oldEngine.commands.filter { it == "hostPaused" || it == "hostResumed" },
        )
        assertEquals(listOf("hostResumed"), engine.commands.filter { it == "hostResumed" })
        assertEquals(listOf("start:600.0:false"), engine.playbackCommands)
        // AndroidView reuses its hosted View at a stable call site. Keying the complete subtree
        // to engine identity is what makes the replacement attach fresh video/subtitle views.
        assertEquals(1, oldEngine.surfaceCreateCount)
        assertEquals(1, engine.surfaceCreateCount)
        val playPause = composeRule.onNodeWithTag("movie_play_pause")
        playPause.assertContentDescriptionEquals("Play")
        playPause.performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()
        assertEquals(listOf("start:600.0:false", "play"), engine.playbackCommands)
    }

    @Test
    fun backgroundDuringPendingQualityReconstructsThatRequestPaused() {
        setContent()
        startPlaying()
        engine.emit(VideoPlayerEvent.Time(currentSec = 600.0, durationSec = 7200.0))
        emitQualityOptions()
        emitQualityOptions(selectedId = "Direct", requestedMode = PlaybackMode.Remux)
        assertEquals(PlaybackMode.Remux, requireNotNull(currentRequest).mode)
        val oldEngine = engine

        composeRule.runOnUiThread {
            lifecycleOwner.registry.currentState = Lifecycle.State.CREATED
        }
        composeRule.waitForIdle()
        assertTrue(oldEngine.released)

        engine = FakeVideoPlayerEngine()
        composeRule.runOnUiThread {
            lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
        }
        composeRule.waitForIdle()

        assertEquals(PlaybackMode.Remux, engineRequests.last().mode)
        assertEquals(listOf("start:600.0:false"), engine.playbackCommands)
    }

    @Test
    fun terminalQualityFailureRestoresTheCommittedRequestBeforeRetry() {
        setContent()
        startPlaying()
        emitQualityOptions()
        engine.emit(qualityOptionsEvent("Direct", PlaybackMode.Remux))
        engine.emit(qualityOptionsEvent("Direct", PlaybackMode.Direct))
        engine.emit(VideoPlayerEvent.Error("The server refused the stream."))
        composeRule.waitForIdle()
        assertEquals(listOf(PlaybackMode.Remux, PlaybackMode.Direct), requestedModes)
        assertEquals(PlaybackMode.Direct, requireNotNull(currentRequest).mode)

        val failedEngine = engine
        engine = FakeVideoPlayerEngine()
        composeRule.onNodeWithContentDescription("Retry playing movie")
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        assertTrue(failedEngine.released)
        assertEquals(PlaybackMode.Direct, engineRequests.last().mode)
    }

    @Test
    fun requestedQualitySurvivesRetryAndSavedStateRecreation() {
        setRestorableContent()
        startPlaying()
        emitQualityOptions()
        openPlayerMenu("movie_quality")
        composeRule.onNodeWithTag("movie_track_Direct")
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("movie_track_Remux")
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()
        emitQualityOptions(selectedId = "Remux")

        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        assertEquals(PlaybackMode.Remux, engineRequests.last().mode)

        val recreated = engine
        recreated.emit(VideoPlayerEvent.Error("The movie stream stopped unexpectedly."))
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Retry playing movie")
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        assertTrue(recreated.released)
        assertEquals(PlaybackMode.Remux, engineRequests.last().mode)
    }

    @Test
    fun audioSubtitleAndSubtitlesOffSurviveSavedStateRecreation() {
        setRestorableContent()
        startPlaying()
        emitTracks()

        openPlayerMenu("movie_audio")
        composeRule.onNodeWithTag("movie_track_1:0")
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("movie_track_2:0")
            .performKeyInput { pressKey(Key.DirectionCenter) }
        pressBack()

        openPlayerMenu("movie_subtitles")
        composeRule.onNodeWithTag("movie_track_none")
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("movie_track_3:0")
            .performKeyInput { pressKey(Key.DirectionCenter) }
        pressBack()

        assertEquals(1, requireNotNull(currentRequest).audioTypeIndex)
        assertEquals(0, requireNotNull(currentRequest).subtitleTypeIndex)
        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        assertEquals(1, engineRequests.last().audioTypeIndex)
        assertEquals(0, engineRequests.last().subtitleTypeIndex)

        startPlaying()
        emitTracks()
        openPlayerMenu("movie_subtitles")
        composeRule.onNodeWithTag("movie_track_3:0")
            .performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("movie_track_none")
            .performKeyInput { pressKey(Key.DirectionCenter) }
        pressBack()

        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        assertEquals(1, engineRequests.last().audioTypeIndex)
        assertEquals(null, engineRequests.last().subtitleTypeIndex)
    }

    @Test
    fun aBitmapSubtitleChoiceSurvivesSavedStateRecreationUnderHls() {
        // The persist path must not launder the remembered bitmap ordinal into null (or into a
        // text track) just because the HLS engine cannot render it.
        setRestorableContent(
            playRequest(
                mode = PlaybackMode.Remux,
                subtitleTypeIndex = 1,
                subtitleTracks = mixedSubtitleTracks(),
            ),
        )
        startPlaying()
        emitHlsSubtitleRows()

        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        assertEquals(1, engineRequests.last().subtitleTypeIndex)
        assertEquals(1, requireNotNull(currentRequest).subtitleTypeIndex)
    }

    @Test
    fun backgroundWithAnOpenMenuPersistsTracksDismissesItAndFocusesPlayPause() {
        setContent()
        startPlaying()
        emitTracks()

        openPlayerMenu("movie_audio")
        composeRule.onNodeWithTag("movie_track_1:0")
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("movie_track_2:0")
            .performKeyInput { pressKey(Key.DirectionCenter) }
        val oldEngine = engine

        composeRule.runOnUiThread {
            lifecycleOwner.registry.currentState = Lifecycle.State.CREATED
        }
        composeRule.waitForIdle()
        assertTrue(oldEngine.released)

        engine = FakeVideoPlayerEngine()
        composeRule.runOnUiThread {
            lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
        }
        composeRule.waitForIdle()

        assertEquals(1, engineRequests.last().audioTypeIndex)
        composeRule.onNodeWithTag("movie_track_menu").assertDoesNotExist()
        composeRule.onNodeWithTag("movie_play_pause").assertIsFocused()
    }

    @Test
    fun aRefusedQualityLeavesPlaybackAndTheSavedRequestAlone() {
        setContent(playRequest(mode = PlaybackMode.Remux))
        startPlaying()
        emitQualityOptions(selectedId = "Remux")
        openPlayerMenu("movie_quality")

        composeRule.onNodeWithTag("movie_track_Remux")
            .performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("movie_track_Direct")
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()
        engine.emit(VideoPlayerEvent.ModeRefused(REFUSAL))
        composeRule.waitForIdle()

        // The refusal is shown in place, politely, without dismissing or seizing focus.
        composeRule.onNodeWithTag("movie_track_refusal").assertExists()
        composeRule.onNodeWithText(REFUSAL).assertExists()
        composeRule.onNodeWithTag("movie_track_menu").assertExists()
        composeRule.onNodeWithTag("movie_track_Direct").assertIsFocused()
        // What is playing, and what a replacement engine would rebuild, are untouched.
        composeRule.onNodeWithTag("movie_track_Remux").assertIsSelected()
        assertEquals(emptyList<PlaybackMode>(), requestedModes)
        assertEquals(PlaybackMode.Remux, requireNotNull(currentRequest).mode)

        pressBack()
        openPlayerMenu("movie_quality")

        // A refusal belongs to the visit that earned it.
        composeRule.onNodeWithTag("movie_track_refusal").assertDoesNotExist()
    }

    @Test
    fun anAcceptedSwitchClearsAStandingRefusal() {
        setContent()
        startPlaying()
        emitQualityOptions()
        openPlayerMenu("movie_quality")
        engine.emit(VideoPlayerEvent.ModeRefused(REFUSAL))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("movie_track_refusal").assertExists()

        emitQualityOptions(selectedId = "Remux")

        composeRule.onNodeWithTag("movie_track_refusal").assertDoesNotExist()
    }

    @Test
    fun anEffectiveProfileNeverRewritesTheRequestedMode() {
        setContent()
        startPlaying()

        // The remux safety gate answered a Remux request with a transcode profile: the mark
        // reports what ran, the saved request keeps what the user asked for.
        emitQualityOptions(selectedId = "P1080Mbps8", requestedMode = PlaybackMode.Remux)

        assertEquals(listOf(PlaybackMode.Remux), requestedModes)
        assertEquals(PlaybackMode.Remux, requireNotNull(currentRequest).mode)
        openPlayerMenu("movie_quality")
        composeRule.onNodeWithTag("movie_track_P1080Mbps8").assertIsSelected()
    }

    @Test
    fun repeatedBackgroundTripsDoNotRewindTheMovie() {
        setContent(playRequest(resumeAtSec = 600.0))
        composeRule.onNodeWithTag("movie_resume").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        // The resume point came from the backend, so a mode may rewind before it.
        assertEquals(listOf("start:600.0:true"), engine.playbackCommands)
        assertEquals(listOf(true), engine.startRewinds)

        repeat(2) {
            engine.emit(VideoPlayerEvent.Time(currentSec = 900.0, durationSec = 7200.0))
            composeRule.waitForIdle()
            composeRule.runOnUiThread {
                lifecycleOwner.registry.currentState = Lifecycle.State.CREATED
            }
            composeRule.waitForIdle()
            engine = FakeVideoPlayerEngine()
            composeRule.runOnUiThread {
                lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
            }
            composeRule.waitForIdle()

            // This visit's own playhead is not a resume point; rewinding before it again would
            // walk the movie backwards one buffer per Home press.
            assertEquals(listOf("start:900.0:false"), engine.playbackCommands)
            assertEquals(listOf(false), engine.startRewinds)
        }
    }

    @Test
    fun hostDrivenUnmountReleasesTheEngineImmediately() {
        setContent()
        val mountedEngine = engine

        composeRule.runOnUiThread { open = false }
        composeRule.waitForIdle()

        assertTrue(mountedEngine.released)
        assertEquals(1, mountedEngine.releaseCount)
    }

    @Test
    fun progressFailureIsPoliteReachableNonBlockingAndRetryRestoresTransportFocus() {
        setContent()
        startPlaying()
        failProgressSaves = true
        var position = 30.0
        while (position <= 46.0) {
            engine.emit(VideoPlayerEvent.Time(position, 7200.0))
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
        engine.emit(VideoPlayerEvent.Error("The movie stream stopped unexpectedly."))
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

    private companion object {
        /** The pre-play gate's shape, as the engine hands it back for an in-player Direct pick. */
        const val REFUSAL = "This TV can't play this movie's Dolby TrueHD audio track (English) — " +
            "it has no decoder for it and no compatible sound system is connected."
    }
}
