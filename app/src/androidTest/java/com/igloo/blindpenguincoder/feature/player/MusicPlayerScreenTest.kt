package com.igloo.blindpenguincoder.feature.player

import android.app.Activity
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.AnimationScaleRule
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.feature.home.findActivity
import com.igloo.blindpenguincoder.playback.media3.FakeMusicPlayerEngine
import com.igloo.blindpenguincoder.playback.model.MusicPlayRequest
import com.igloo.blindpenguincoder.playback.model.MusicPlayTrack
import com.igloo.blindpenguincoder.playback.model.MusicPlayerEvent
import com.igloo.blindpenguincoder.playback.model.MusicQueueSource
import com.igloo.blindpenguincoder.playback.queue.InertMusicQueueFetcher
import com.igloo.blindpenguincoder.data.repository.MusicQueueFetcher
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.data.model.ShuffleTracksData
import com.igloo.blindpenguincoder.data.model.SqlNullInt64
import com.igloo.blindpenguincoder.data.model.SqlNullString
import com.igloo.blindpenguincoder.data.model.TrackListItem
import com.igloo.blindpenguincoder.data.model.TracksData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The music player's contract (design-system.md section 11.8's music subsection), driven
 * entirely through the fake engine: playback starts at the queue position immediately (no
 * resume prompt), the skip keys mean tracks while Rewind/FastForward stay in-track seeks, an
 * auto-advance re-titles the chrome and the live region, and the player resolves the same ways
 * the movie player does — Ended and Back close it through the host, an error pins Retry, a
 * revoked session pins Close.
 */
@RunWith(AndroidJUnit4::class)
class MusicPlayerScreenTest {

    @get:Rule(order = 0)
    val animationScale = AnimationScaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private lateinit var engine: FakeMusicPlayerEngine
    private var closes = 0
    private var hostActivity: Activity? = null
    private var hostView: View? = null
    private lateinit var restorationTester: StateRestorationTester
    private val createdEngines = mutableListOf<FakeMusicPlayerEngine>()
    private val engineRequests = mutableListOf<MusicPlayRequest>()
    private val queueChanges = mutableListOf<MusicPlayRequest>()

    /** The host contract: closing unmounts the screen. */
    private var open by mutableStateOf(true)

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private lateinit var lifecycleOwner: TestLifecycleOwner

    private fun track(id: Long, title: String, durationSec: Double) = MusicPlayTrack(
        id = id,
        title = title,
        durationSec = durationSec,
        artistName = "The Beatles",
        albumTitle = "Help!",
        coverUrl = null,
    )

    private fun playRequest(
        source: MusicQueueSource = MusicQueueSource.Album(albumId = 11, title = "Help!"),
        startIndex: Int = 0,
    ) = MusicPlayRequest(
        source = source,
        startIndex = startIndex,
        tracks = listOf(
            track(id = 901, title = "Yesterday", durationSec = 125.0),
            track(id = 902, title = "Ticket to Ride", durationSec = 190.0),
            track(id = 903, title = "Act Naturally", durationSec = 110.0),
        ),
    )

    private fun setContent(
        request: MusicPlayRequest = playRequest(),
        spokenAccessibilityEnabled: Boolean = false,
        queueFetcher: MusicQueueFetcher = InertMusicQueueFetcher,
    ) {
        engine = FakeMusicPlayerEngine(request.tracks.map { it.durationSec })
        createdEngines.clear()
        queueChanges.clear()
        closes = 0
        open = true
        lifecycleOwner = TestLifecycleOwner()
        lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
        composeRule.setContent {
            val context = LocalContext.current
            val view = LocalView.current
            SideEffect {
                hostActivity = context.findActivity()
                hostView = view
            }
            IglooTheme {
                CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                    if (open) {
                        MusicPlayerScreen(
                            request = request,
                            onClose = {
                                closes += 1
                                open = false
                            },
                            engineFactory = { _, played ->
                                engineRequests += played
                                createdEngines += engine
                                engine
                            },
                            queueFetcher = queueFetcher,
                            onQueueChanged = { queueChanges += it },
                            spokenAccessibilityEnabled = spokenAccessibilityEnabled,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun setRestorableContent(request: MusicPlayRequest = playRequest()) {
        createdEngines.clear()
        closes = 0
        open = true
        lifecycleOwner = TestLifecycleOwner()
        lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
        restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            val context = LocalContext.current
            SideEffect { hostActivity = context.findActivity() }
            IglooTheme {
                CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                    if (open) {
                        MusicPlayerScreen(
                            request = request,
                            onClose = {
                                closes += 1
                                open = false
                            },
                            engineFactory = { _, played ->
                                FakeMusicPlayerEngine(played.tracks.map { it.durationSec }).also {
                                    engine = it
                                    createdEngines += it
                                }
                            },
                            queueFetcher = InertMusicQueueFetcher,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun startPlaying(durationSec: Double = 125.0) {
        engine.emit(MusicPlayerEvent.Ready(durationSec))
        engine.emit(MusicPlayerEvent.IsPlayingChanged(true))
        composeRule.waitForIdle()
    }

    private fun pressBack() {
        composeRule.runOnUiThread {
            (checkNotNull(hostActivity) as ComponentActivity).onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
    }

    /** Home and back: the trip that releases the engine and rebuilds a paused replacement. */
    private fun backgroundAndReturn() {
        composeRule.runOnUiThread {
            lifecycleOwner.registry.currentState = Lifecycle.State.CREATED
        }
        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
        }
        composeRule.waitForIdle()
    }

    /** The second track becomes current at 42 seconds in — the playhead every rebuild test uses. */
    private fun advanceToSecondTrack() {
        engine.emit(MusicPlayerEvent.TrackChanged(index = 1, durationSec = 190.0))
        engine.emit(MusicPlayerEvent.Time(currentSec = 42.0, durationSec = 190.0))
        composeRule.waitForIdle()
    }

    @Test
    fun startsAtTrackOneImmediatelyWithFocusOnPlayPause() {
        setContent()

        // No resume prompt: Play Album was itself the play press.
        assertEquals(listOf("start:0:0.0:true"), engine.playbackCommands)
        composeRule.onNodeWithTag("music_play_pause").assertIsFocused()
        composeRule.onNodeWithTag("music_loading").assertExists()
        composeRule.onNodeWithTag("music_track_title", useUnmergedTree = true)
            .assertTextEquals("Yesterday")
        composeRule.onNodeWithTag("music_track_position", useUnmergedTree = true)
            .assertTextEquals("Track 1 of 3 · The Beatles")
    }

    /** A row's Play names an entry other than the first; the queue starts there, not at the top. */
    @Test
    fun startsAtTheEntryThePressNamed() {
        setContent(request = playRequest(startIndex = 1))

        assertEquals(listOf("start:1:0.0:true"), engine.playbackCommands)
        composeRule.onNodeWithTag("music_track_title", useUnmergedTree = true)
            .assertTextEquals("Ticket to Ride")
        composeRule.onNodeWithTag("music_track_position", useUnmergedTree = true)
            .assertTextEquals("Track 2 of 3 · The Beatles")
    }

    @Test
    fun centerTogglesPlayPauseAndTheLabelFollowsTheIntent() {
        setContent()
        startPlaying()

        val playPause = composeRule.onNodeWithTag("music_play_pause")
        playPause.assertContentDescriptionEquals("Pause")
        playPause.performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        // The fake echoes the intent, which is what flips the label (section 11.8's intent rule).
        assertEquals(listOf("start:0:0.0:true", "pause"), engine.playbackCommands)
        playPause.assertContentDescriptionEquals("Play")

        playPause.performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        assertEquals(listOf("start:0:0.0:true", "pause", "play"), engine.playbackCommands)
        playPause.assertContentDescriptionEquals("Pause")
    }

    @Test
    fun dedicatedPlayPauseAndToggleKeysStayDistinct() {
        setContent()
        startPlaying()
        val transport = composeRule.onNodeWithTag("music_play_pause")

        transport.performKeyInput { pressKey(Key.MediaPause) }
        transport.performKeyInput { pressKey(Key.MediaPause) }
        transport.performKeyInput { pressKey(Key.MediaPlay) }
        transport.performKeyInput { pressKey(Key.MediaPlay) }
        transport.performKeyInput { pressKey(Key.MediaPlayPause) }
        composeRule.waitForIdle()

        assertEquals(
            listOf("start:0:0.0:true", "pause", "pause", "play", "play", "pause"),
            engine.playbackCommands,
        )
    }

    @Test
    fun skipKeysChangeTracksWhileRewindAndFastForwardSeekWithinTheTrack() {
        setContent()
        startPlaying()
        engine.emit(MusicPlayerEvent.Time(currentSec = 30.0, durationSec = 125.0))
        composeRule.waitForIdle()

        // On an album the skip keys mean tracks — intercepted before the shared map could
        // spend them on ±10s seeks — while Rewind/FastForward keep the in-track seek.
        val playPause = composeRule.onNodeWithTag("music_play_pause")
        playPause.performKeyInput { pressKey(Key.MediaNext) }
        playPause.performKeyInput { pressKey(Key.MediaSkipForward) }
        playPause.performKeyInput { pressKey(Key.MediaPrevious) }
        playPause.performKeyInput { pressKey(Key.MediaSkipBackward) }
        playPause.performKeyInput { pressKey(Key.MediaRewind) }
        playPause.performKeyInput { pressKey(Key.MediaFastForward) }
        composeRule.waitForIdle()

        assertEquals(
            listOf(
                "start:0:0.0:true",
                "next", "next", "previous", "previous",
                "seek:20.0", "seek:30.0",
            ),
            engine.playbackCommands,
        )
    }

    @Test
    fun transportButtonsSkipTracksThroughTheEngine() {
        setContent()
        startPlaying()

        composeRule.onNodeWithTag("music_next").requestFocus()
        composeRule.onNodeWithTag("music_next")
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.onNodeWithTag("music_previous").requestFocus()
        composeRule.onNodeWithTag("music_previous")
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        assertEquals(listOf("start:0:0.0:true", "next", "previous"), engine.playbackCommands)
    }

    @Test
    fun upFromTheTransportLandsOnBackAndDownReturnsToPlayPause() {
        setContent()
        startPlaying()

        composeRule.onNodeWithTag("music_track_metadata").assertDoesNotExist()
        composeRule.onNodeWithTag("music_play_pause")
            .performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("music_back").assertIsFocused()

        composeRule.onNodeWithTag("music_back")
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("music_play_pause").assertIsFocused()
    }

    @Test
    fun spokenAccessibilityAddsAnActionlessMetadataStopToTheVerticalFocusChain() {
        setContent(spokenAccessibilityEnabled = true)
        startPlaying()

        val metadata = composeRule.onNodeWithTag("music_track_metadata")
        metadata
            .assertContentDescriptionEquals("Yesterday. Track 1 of 3 · The Beatles.")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))

        composeRule.onNodeWithTag("music_play_pause")
            .performKeyInput { pressKey(Key.DirectionUp) }
        metadata.assertIsFocused()

        metadata.performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("music_back").assertIsFocused()

        composeRule.onNodeWithTag("music_back")
            .performKeyInput { pressKey(Key.DirectionDown) }
        metadata.assertIsFocused()

        metadata.performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("music_play_pause").assertIsFocused()
    }

    @Test
    fun playerKeepsTheHostAwakeAndRestoresThePreviousFlagOnUnmount() {
        setContent()
        val mountedHostView = checkNotNull(hostView)

        assertTrue(mountedHostView.keepScreenOn)
        composeRule.runOnUiThread { open = false }
        composeRule.waitForIdle()
        assertFalse(mountedHostView.keepScreenOn)

        // A host that already owned wakefulness keeps it after the player leaves.
        composeRule.runOnUiThread {
            mountedHostView.keepScreenOn = true
            open = true
        }
        composeRule.waitForIdle()
        assertTrue(mountedHostView.keepScreenOn)
        composeRule.runOnUiThread { open = false }
        composeRule.waitForIdle()
        assertTrue(mountedHostView.keepScreenOn)

        composeRule.runOnUiThread { mountedHostView.keepScreenOn = false }
    }

    @Test
    fun aTrackChangeRetitlesTheChromeAndTheLiveRegionAnnouncesTheNewTrack() {
        setContent()
        startPlaying()

        engine.emit(MusicPlayerEvent.TrackChanged(index = 1, durationSec = 190.0))
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("music_track_title", useUnmergedTree = true)
            .assertTextEquals("Ticket to Ride")
        composeRule.onNodeWithTag("music_track_position", useUnmergedTree = true)
            .assertTextEquals("Track 2 of 3 · The Beatles")
        // The auto-advance narration: same Playing phase, new sentence because the title rides
        // in it. Polite — it narrates, it never interrupts.
        composeRule.onNodeWithContentDescription("Playing: Ticket to Ride")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
    }

    @Test
    fun endedClosesThePlayerAndReleasesTheEngine() {
        setContent()
        startPlaying()

        engine.emit(MusicPlayerEvent.Ended)
        composeRule.waitForIdle()

        assertEquals(1, closes)
        assertTrue(engine.released)
    }

    @Test
    fun backClosesInOnePressAndReleasesTheEngine() {
        setContent()
        startPlaying()

        // No chrome-dismissal step: the chrome never hides, so Back always means leave.
        pressBack()

        assertEquals(1, closes)
        assertTrue(engine.released)
    }

    @Test
    fun errorPinsRetryAndRetryRebuildsAtThisVisitsPlayhead() {
        setContent()
        val failedEngine = engine
        startPlaying()
        advanceToSecondTrack()
        engine.emit(MusicPlayerEvent.Error("The album stream stopped unexpectedly."))
        composeRule.waitForIdle()

        composeRule.onNodeWithText("The album stream stopped unexpectedly.").assertExists()
        val retry = composeRule.onNodeWithContentDescription("Retry playing music")
        retry.assertIsFocused()

        // The retry press lands on a fresh engine; swap the fake the factory hands out first.
        engine = FakeMusicPlayerEngine(playRequest().tracks.map { it.durationSec })
        retry.performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        assertTrue("the failed engine must be released", failedEngine.released)
        assertEquals(listOf("start:1:42.0:true"), engine.playbackCommands)
        composeRule.onNodeWithTag("music_play_pause").assertIsFocused()
    }

    @Test
    fun unauthorizedErrorOffersCloseInsteadOfRetry() {
        setContent()
        engine.emit(
            MusicPlayerEvent.Error("Your session is no longer valid.", unauthorized = true),
        )
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Retry playing music").assertDoesNotExist()
        val close = composeRule.onNodeWithContentDescription("Close player")
        close.assertIsFocused()
        close.performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        assertEquals(1, closes)
        assertTrue(engine.released)
    }

    @Test
    fun mediaTransportAndSkipKeysAreInertOnTheErrorSurface() {
        setContent()
        startPlaying()
        engine.emit(MusicPlayerEvent.Error("The album stream stopped unexpectedly."))
        composeRule.waitForIdle()
        val commandsBefore = engine.playbackCommands.toList()

        // Swallowed, not just unhandled: an unhandled media key would fall back to the active
        // MediaSession and drive playback underneath the error surface.
        composeRule.onNodeWithContentDescription("Retry playing music").performKeyInput {
            pressKey(Key.MediaPlay)
            pressKey(Key.MediaPlayPause)
            pressKey(Key.MediaFastForward)
            pressKey(Key.MediaNext)
            pressKey(Key.MediaPrevious)
        }
        composeRule.waitForIdle()

        assertEquals(commandsBefore, engine.playbackCommands)
        composeRule.onNodeWithText("The album stream stopped unexpectedly.").assertExists()
    }

    @Test
    fun savedStateRestorationKeepsTheQueuePositionAndPausedIntent() {
        setRestorableContent()
        startPlaying()
        advanceToSecondTrack()
        val playPause = composeRule.onNodeWithTag("music_play_pause")
        playPause.performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()
        playPause.assertContentDescriptionEquals("Play")
        val originalEngine = engine

        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        assertTrue(originalEngine.released)
        assertEquals(2, createdEngines.size)
        assertEquals(listOf("start:1:42.0:false"), engine.playbackCommands)
        composeRule.onNodeWithTag("music_track_title", useUnmergedTree = true)
            .assertTextEquals("Ticket to Ride")
        composeRule.onNodeWithTag("music_play_pause")
            .assertIsFocused()
            .assertContentDescriptionEquals("Play")

        composeRule.onNodeWithTag("music_play_pause")
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        assertEquals(listOf("start:1:42.0:false", "play"), engine.playbackCommands)
    }

    @Test
    fun backgroundReleasesTheEngineAndReturnRebuildsPausedAtThePosition() {
        setContent()
        startPlaying()
        advanceToSecondTrack()
        val oldEngine = engine
        val transportBeforeStandby = oldEngine.playbackCommands

        composeRule.runOnUiThread {
            lifecycleOwner.registry.currentState = Lifecycle.State.CREATED
        }
        composeRule.waitForIdle()
        assertTrue(oldEngine.released)
        // A late system/media command has no path back into a released background engine.
        oldEngine.play()
        assertEquals(transportBeforeStandby, oldEngine.playbackCommands)

        engine = FakeMusicPlayerEngine(playRequest().tracks.map { it.durationSec })
        composeRule.runOnUiThread {
            lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
        }
        composeRule.waitForIdle()

        // The replacement resumes this visit's own playhead, paused: standby silenced the
        // music and only an explicit Play may bring it back.
        assertEquals(listOf("start:1:42.0:false"), engine.playbackCommands)
        val playPause = composeRule.onNodeWithTag("music_play_pause")
        playPause.assertContentDescriptionEquals("Play")
        playPause.performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()
        assertEquals(listOf("start:1:42.0:false", "play"), engine.playbackCommands)
    }

    /**
     * A replacement engine reports the queue's own start index the moment its playlist is set —
     * setting a playlist is itself an item transition. That report names the track already
     * current, so it must not be read as an advance: doing so zeroes the saved playhead, and
     * nothing repairs it before the next rebuild if no position tick ever arrives (a track that
     * fails before READY never produces one).
     */
    @Test
    fun theQueuesStartupTrackReportDoesNotEraseTheSavedPlayhead() {
        setContent()
        startPlaying()
        advanceToSecondTrack()

        engine = FakeMusicPlayerEngine(playRequest().tracks.map { it.durationSec })
        backgroundAndReturn()
        assertEquals(listOf("start:1:42.0:false"), engine.playbackCommands)

        // A second trip with no position tick in between: the playhead is still this visit's.
        engine = FakeMusicPlayerEngine(playRequest().tracks.map { it.durationSec })
        backgroundAndReturn()
        assertEquals(listOf("start:1:42.0:false"), engine.playbackCommands)
    }

    /**
     * The endless sources refill through the controller: once the playhead is within ten
     * tracks of the end, a batch is fetched, appended to the engine, and the chrome's count and
     * the host's saved request both grow with it.
     */
    @Test
    fun anEndlessQueueRefillsNearItsEndAndTheAppendReachesTheEngineAndTheHost() {
        val fetcher = ScriptedFetcher(
            pages = mutableListOf(
                (904L..953L).map { id -> libraryTrack(id, "Track $id") },
            ),
        )
        setContent(
            request = playRequest(source = MusicQueueSource.LibraryInOrder(nextOffset = 3, total = 53)),
            queueFetcher = fetcher,
        )
        startPlaying()
        composeRule.waitForIdle()

        // Three tracks loaded, playhead at 0: nine of runway is under the threshold.
        composeRule.waitUntil(5_000) { engine.commands.any { it.startsWith("append:") } }
        composeRule.waitForIdle()

        assertEquals(1, fetcher.pageRequests.size)
        assertEquals(3L, fetcher.pageRequests.single())
        assertTrue(engine.commands.single { it.startsWith("append:") }.startsWith("append:904,905,"))
        composeRule.onNodeWithTag("music_track_position", useUnmergedTree = true)
            .assertTextEquals("Track 1 of 53 · The Beatles · Help!")
        assertEquals(53, queueChanges.last().tracks.size)
        composeRule.onNodeWithTag("music_queue_notice").assertDoesNotExist()
    }

    @Test
    fun aFailedRefillKeepsTheQueueAndShowsThePoliteNotice() {
        setContent(
            request = playRequest(source = MusicQueueSource.LibraryShuffle),
            queueFetcher = InertMusicQueueFetcher,
        )
        startPlaying()

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("music_queue_notice").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("music_queue_notice")
            .assertTextEquals("Couldn't load more tracks. The queue will play out.")
        composeRule.onNodeWithTag("music_track_position", useUnmergedTree = true)
            .assertTextEquals("Track 1 · The Beatles · Help!")
        assertTrue(engine.commands.none { it.startsWith("append:") })
    }

    /** A rebuilt engine must start from the grown queue, not the request that launched the screen. */
    @Test
    fun aRebuiltEngineIsSeededWithTheGrownQueue() {
        val fetcher = ScriptedFetcher(
            pages = mutableListOf((904L..953L).map { id -> libraryTrack(id, "Track $id") }),
        )
        setContent(
            request = playRequest(source = MusicQueueSource.LibraryInOrder(nextOffset = 3, total = 53)),
            queueFetcher = fetcher,
        )
        startPlaying()
        composeRule.waitUntil(5_000) { engine.commands.any { it.startsWith("append:") } }
        composeRule.waitForIdle()

        engine = FakeMusicPlayerEngine()
        backgroundAndReturn()

        assertEquals(53, engineRequests.last().tracks.size)
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

    private fun libraryTrack(id: Long, title: String) = TrackListItem(
        id = id,
        title = title,
        duration = 200_000,
        albumId = SqlNullInt64(11, valid = true),
        albumTitle = SqlNullString("Help!", valid = true),
        albumCover = SqlNullString("", valid = false),
        musicianId = SqlNullInt64(4, valid = true),
        musicianName = SqlNullString("The Beatles", valid = true),
    )

    /** Answers the in-order pages it was given, then an empty last page. */
    private class ScriptedFetcher(
        private val pages: MutableList<List<TrackListItem>>,
    ) : MusicQueueFetcher {
        val pageRequests = mutableListOf<Long>()

        override suspend fun tracks(limit: Long, offset: Long): ApiResult<TracksData> {
            pageRequests += offset
            val page = pages.removeFirstOrNull().orEmpty()
            return ApiResult.Success(
                TracksData(
                    tracks = page,
                    total = 53,
                    offset = offset,
                    limit = limit,
                    hasMore = pages.isNotEmpty(),
                ),
            )
        }

        override suspend fun shuffleTracks(limit: Long, exclude: List<Long>): ApiResult<ShuffleTracksData> =
            ApiResult.Failure(AppError.Unexpected("not scripted"))
    }
}
