@file:androidx.annotation.OptIn(UnstableApi::class)

package com.igloo.blindpenguincoder.playback.media3

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.igloo.blindpenguincoder.data.model.PlaybackMode
import com.igloo.blindpenguincoder.playback.hls.HlsManifestResult
import com.igloo.blindpenguincoder.playback.hls.HlsSessionApi
import com.igloo.blindpenguincoder.playback.hls.HlsSessionSpec
import com.igloo.blindpenguincoder.playback.model.HlsAudioProfile
import com.igloo.blindpenguincoder.playback.model.MoviePlayRequest
import com.igloo.blindpenguincoder.playback.model.MoviePlayerEvent
import com.igloo.blindpenguincoder.playback.model.PlayableAudioTrack
import com.igloo.blindpenguincoder.playback.model.PlayableSubtitleTrack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The engine's HLS orchestration on a real ExoPlayer: which choices reach the backend as a new
 * session, which are answered without one, and what happens when a preflight fails. Every URL
 * points at TEST-NET (RFC 5737), which never answers, so a prepared source holds in BUFFERING
 * and no assertion here depends on decoding a frame.
 *
 * The session lifecycle itself is unit-tested in `HlsSessionControllerTest`; what this covers is
 * the engine's side of the seam — the decisions that were previously reachable only by hand.
 */
@RunWith(AndroidJUnit4::class)
class ExoMoviePlayerEngineTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private val stopScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val engines = mutableListOf<MoviePlayerEngine>()

    @After
    fun releaseAll() {
        instrumentation.runOnMainSync {
            engines.forEach { it.release() }
            engines.clear()
        }
        stopScope.cancel()
    }

    private class FakeHlsApi : HlsSessionApi {
        val fetched = mutableListOf<HlsSessionSpec>()
        val stopped = mutableListOf<String>()
        var result: HlsManifestResult = HlsManifestResult.Ready("remux", 0.0)
        var throwOnFetch: (() -> Nothing)? = null
        var suspendedResult: CompletableDeferred<HlsManifestResult>? = null

        override suspend fun fetchHlsManifest(spec: HlsSessionSpec): HlsManifestResult {
            fetched += spec
            throwOnFetch?.invoke()
            return suspendedResult?.await() ?: result
        }

        override suspend fun stopHlsSession(movieId: Long, sessionUuid: String) {
            stopped += sessionUuid
        }

        override fun hlsPlaylistUrl(spec: HlsSessionSpec): String =
            "https://203.0.113.1/movies/${spec.movieId}/hls/${spec.profileId}/playlist.m3u8"

        override fun movieSubtitleUrl(movieId: Long, trackIndex: Int, startSec: Double): String =
            "https://203.0.113.1/movies/$movieId/subtitles/$trackIndex/web.vtt"
    }

    private fun playRequest(
        mode: PlaybackMode = PlaybackMode.Direct,
        audioTracks: List<PlayableAudioTrack> = listOf(
            PlayableAudioTrack(label = "English · Surround", codec = "eac3", isDefault = true),
            PlayableAudioTrack(label = "Spanish · Stereo", codec = "aac"),
        ),
        subtitleTypeIndex: Int? = null,
        subtitleTracks: List<PlayableSubtitleTrack> = listOf(PlayableSubtitleTrack(label = "English")),
    ) = MoviePlayRequest(
        movieId = 7,
        title = "Heat",
        posterUrl = null,
        mimeType = "video/x-matroska",
        mode = mode,
        audioTypeIndex = null,
        subtitleTypeIndex = subtitleTypeIndex,
        audioTracks = audioTracks,
        subtitleTracks = subtitleTracks,
        resumeAtSec = null,
        durationSec = 7200.0,
    )

    private fun engine(
        request: MoviePlayRequest = playRequest(),
        api: FakeHlsApi = FakeHlsApi(),
        canPlayAudioMime: (String, Int?) -> Boolean = { _, _ -> true },
    ): MoviePlayerEngine {
        lateinit var built: MoviePlayerEngine
        instrumentation.runOnMainSync {
            built = exoMoviePlayerEngine(
                context = context,
                request = request,
                services = MoviePlaybackServices(
                    progressiveDataSourceFactory = DefaultHttpDataSource.Factory(),
                    hlsDataSourceFactory = DefaultHttpDataSource.Factory(),
                    directStreamUrl = { "https://203.0.113.1/movies/$it/stream" },
                    hlsSessionApi = api,
                    canPlayAudioMime = canPlayAudioMime,
                    stopScope = stopScope,
                ),
            )
            engines += built
        }
        return built
    }

    /** Pumps the main looper until [condition] holds; the engine's work is all posted there. */
    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(20)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun MoviePlayerEngine.errors(): List<MoviePlayerEvent.Error> =
        events.replayCache.filterIsInstance<MoviePlayerEvent.Error>()

    private fun MoviePlayerEngine.refusals(): List<String> =
        events.replayCache.filterIsInstance<MoviePlayerEvent.ModeRefused>().map { it.message }

    // --- starting a session ---

    @Test
    fun aResumeOverHlsRewindsBeforeItsStartButAReconstructionDoesNot() {
        // A position carried in from the backend lands mid-sentence, so the session starts a
        // little before it.
        val resumeApi = FakeHlsApi()
        val resuming = engine(playRequest(mode = PlaybackMode.Remux), resumeApi)
        onMain { resuming.startPlayback(600.0, initialPlayWhenReady = true, rewindOnResume = true) }
        waitFor("the resume manifest") { resumeApi.fetched.isNotEmpty() }
        assertEquals(590, resumeApi.fetched.single().startSec)

        // A replacement engine already knows this visit's own playhead. Rewinding before it
        // again is how repeated background trips walk the movie backwards.
        val rebuiltApi = FakeHlsApi()
        val rebuilt = engine(playRequest(mode = PlaybackMode.Remux), rebuiltApi)
        onMain { rebuilt.startPlayback(600.0, initialPlayWhenReady = true, rewindOnResume = false) }
        waitFor("the reconstruction manifest") { rebuiltApi.fetched.isNotEmpty() }
        assertEquals(600, rebuiltApi.fetched.single().startSec)
    }

    @Test
    fun theSessionNamesAConcreteAudioOrdinalEvenWithNoExplicitChoice() {
        val api = FakeHlsApi()
        val engine = engine(playRequest(mode = PlaybackMode.Remux), api)

        onMain { engine.startPlayback(null, initialPlayWhenReady = true, rewindOnResume = true) }
        waitFor("the manifest") { api.fetched.isNotEmpty() }

        // The default track, resolved: the backend requires an ordinal for a movie with audio.
        assertEquals(0, api.fetched.single().audioTypeIndex)
        assertEquals(0, api.fetched.single().startSec)
        assertEquals("remux", api.fetched.single().profileId)
    }

    @Test
    fun aVideoOnlyMovieSendsNoAudioTrack() {
        val api = FakeHlsApi()
        val engine = engine(playRequest(mode = PlaybackMode.Remux, audioTracks = emptyList()), api)

        onMain { engine.startPlayback(null, initialPlayWhenReady = true, rewindOnResume = true) }
        waitFor("the manifest") { api.fetched.isNotEmpty() }

        assertNull(api.fetched.single().audioTypeIndex)
    }

    // --- seeking against the session's window ---

    @Test
    fun onlySeeksOutsideTheProducedWindowRebaseTheSession() {
        val api = FakeHlsApi()
        api.result = HlsManifestResult.Ready("remux", actualStartSec = 87.4)
        val engine = engine(playRequest(mode = PlaybackMode.Remux), api)
        onMain { engine.startPlayback(90.0, initialPlayWhenReady = false, rewindOnResume = false) }
        waitFor("the first manifest") { api.fetched.size == 1 }

        // The playhead sits at the session's actual start; a near-forward seek rides the
        // player and the server's segment long-poll.
        onMain { engine.seekTo(150.0) }
        instrumentation.waitForIdleSync()
        assertEquals(1, api.fetched.size)

        // Before the session's media exists at all.
        onMain { engine.seekTo(20.0) }
        waitFor("the backwards rebase") { api.fetched.size == 2 }
        assertEquals(20, api.fetched[1].startSec)

        // Far past the playhead: the segments are not produced yet.
        onMain { engine.seekTo(1_000.0) }
        waitFor("the forward rebase") { api.fetched.size == 3 }
        assertEquals(1_000, api.fetched[2].startSec)
    }

    @Test
    fun anHlsAudioSwitchIsANewSessionAtTheSamePositionAndReselectingIsInert() {
        val api = FakeHlsApi()
        api.result = HlsManifestResult.Ready("remux", actualStartSec = 300.0)
        val engine = engine(playRequest(mode = PlaybackMode.Remux), api)
        onMain { engine.startPlayback(300.0, initialPlayWhenReady = false, rewindOnResume = false) }
        waitFor("the first manifest") { api.fetched.size == 1 }

        onMain { engine.selectAudioTrack("audio:1") }
        waitFor("the audio switch") { api.fetched.size == 2 }
        assertEquals(1, api.fetched[1].audioTypeIndex)
        assertEquals(300, api.fetched[1].startSec)

        // The mux carries one track; asking for the one already playing is not a new session.
        onMain { engine.selectAudioTrack("audio:1") }
        instrumentation.waitForIdleSync()
        assertEquals(2, api.fetched.size)
    }

    // --- swapping modes ---

    @Test
    fun leavingHlsForDirectEndsTheServerSessionAndReturningStartsAFreshOne() {
        val api = FakeHlsApi()
        val engine = engine(playRequest(mode = PlaybackMode.Remux), api)
        onMain { engine.startPlayback(null, initialPlayWhenReady = false, rewindOnResume = true) }
        waitFor("the first manifest") { api.fetched.size == 1 }
        val firstUuid = api.fetched.single().sessionUuid

        onMain { engine.selectPlaybackMode(PlaybackMode.Direct.name) }
        waitFor("the stop") { api.stopped.isNotEmpty() }
        assertEquals(listOf(firstUuid), api.stopped)

        onMain { engine.selectPlaybackMode(PlaybackMode.P1080Mbps8.name) }
        waitFor("the second manifest") { api.fetched.size == 2 }
        assertEquals("1080p_8mbps", api.fetched[1].profileId)
        // A stopped session is done; the replacement must not reuse its key.
        assertTrue(api.fetched[1].sessionUuid != firstUuid)
    }

    @Test
    fun theQualityLadderReportsTheEffectiveProfileWithoutRewritingTheRequest() {
        val api = FakeHlsApi()
        // The remux safety gate forced a transcode: the server answered with another profile.
        api.result = HlsManifestResult.Ready("1080p_8mbps", 0.0)
        val engine = engine(playRequest(mode = PlaybackMode.Remux), api)

        onMain { engine.startPlayback(null, initialPlayWhenReady = false, rewindOnResume = true) }
        waitFor("the quality options") {
            engine.events.replayCache.any { it is MoviePlayerEvent.QualityOptionsChanged }
        }

        val ladder = engine.events.replayCache
            .filterIsInstance<MoviePlayerEvent.QualityOptionsChanged>()
            .last()
        assertEquals(PlaybackMode.entries.size, ladder.options.size)
        assertEquals(PlaybackMode.P1080Mbps8.name, ladder.options.single { it.selected }.id)
        assertEquals(PlaybackMode.Remux, ladder.requestedMode)
    }

    @Test
    fun pendingQualityPublishesBeforePreflightThenSuccessCommitsAndFailureRollsBack() {
        val api = FakeHlsApi()
        val engine = engine(playRequest(), api)
        onMain { engine.startPlayback(null, initialPlayWhenReady = false, rewindOnResume = true) }
        waitFor("the direct quality options") {
            engine.events.replayCache.any { it is MoviePlayerEvent.QualityOptionsChanged }
        }

        val firstPreflight = CompletableDeferred<HlsManifestResult>()
        api.suspendedResult = firstPreflight
        onMain { engine.selectPlaybackMode(PlaybackMode.P1080Mbps8.name) }
        waitFor("the suspended preflight") { api.fetched.size == 1 }

        val pending = engine.events.replayCache
            .filterIsInstance<MoviePlayerEvent.QualityOptionsChanged>()
            .last()
        assertEquals(PlaybackMode.P1080Mbps8, pending.requestedMode)
        assertEquals(PlaybackMode.Direct.name, pending.options.single { it.selected }.id)

        firstPreflight.complete(HlsManifestResult.Ready("1080p_8mbps", 0.0))
        waitFor("the committed quality") {
            engine.events.replayCache
                .filterIsInstance<MoviePlayerEvent.QualityOptionsChanged>()
                .last()
                .options
                .single { it.selected }
                .id == PlaybackMode.P1080Mbps8.name
        }

        val secondPreflight = CompletableDeferred<HlsManifestResult>()
        api.suspendedResult = secondPreflight
        onMain { engine.selectPlaybackMode(PlaybackMode.P720Mbps3.name) }
        waitFor("the second suspended preflight") { api.fetched.size == 2 }
        assertEquals(
            PlaybackMode.P720Mbps3,
            engine.events.replayCache
                .filterIsInstance<MoviePlayerEvent.QualityOptionsChanged>()
                .last()
                .requestedMode,
        )

        secondPreflight.complete(HlsManifestResult.Failed("The server refused the stream."))
        waitFor("the terminal failure") { engine.errors().isNotEmpty() }

        val events = engine.events.replayCache
        val errorIndex = events.indexOfLast { it is MoviePlayerEvent.Error }
        val rollbackIndex = events.indexOfLast {
            it is MoviePlayerEvent.QualityOptionsChanged &&
                it.requestedMode == PlaybackMode.P1080Mbps8
        }
        assertTrue("the committed request must be restored before the error", rollbackIndex in 0 until errorIndex)
        val rollback = events[rollbackIndex] as MoviePlayerEvent.QualityOptionsChanged
        assertEquals(PlaybackMode.P1080Mbps8.name, rollback.options.single { it.selected }.id)
    }

    @Test
    fun selectingTheEffectiveRowCancelsPendingWithoutRewritingTheCommittedRequest() {
        val api = FakeHlsApi()
        api.result = HlsManifestResult.Ready("1080p_8mbps", 0.0)
        val engine = engine(playRequest(mode = PlaybackMode.Remux), api)
        onMain { engine.startPlayback(null, initialPlayWhenReady = false, rewindOnResume = true) }
        waitFor("the effective source") {
            engine.events.replayCache
                .filterIsInstance<MoviePlayerEvent.QualityOptionsChanged>()
                .lastOrNull()
                ?.options
                ?.single { it.selected }
                ?.id == PlaybackMode.P1080Mbps8.name
        }

        val preflight = CompletableDeferred<HlsManifestResult>()
        api.suspendedResult = preflight
        onMain { engine.selectPlaybackMode(PlaybackMode.P720Mbps3.name) }
        waitFor("the pending source") { api.fetched.size == 2 }
        onMain { engine.selectPlaybackMode(PlaybackMode.P1080Mbps8.name) }

        val restored = engine.events.replayCache
            .filterIsInstance<MoviePlayerEvent.QualityOptionsChanged>()
            .last()
        assertEquals(PlaybackMode.Remux, restored.requestedMode)
        assertEquals(PlaybackMode.P1080Mbps8.name, restored.options.single { it.selected }.id)

        preflight.complete(HlsManifestResult.Ready("720p_3mbps", 0.0))
        instrumentation.waitForIdleSync()
        val afterLateCompletion = engine.events.replayCache
            .filterIsInstance<MoviePlayerEvent.QualityOptionsChanged>()
            .last()
        assertEquals(restored, afterLateCompletion)
        assertTrue(engine.errors().isEmpty())
    }

    // --- refusing rather than substituting ---

    @Test
    fun switchingToDirectIsRefusedWhenThisTvCannotPlayTheSessionsAudioTrack() {
        val api = FakeHlsApi()
        val engine = engine(
            playRequest(
                mode = PlaybackMode.Remux,
                audioTracks = listOf(
                    PlayableAudioTrack(label = "English", codec = "truehd", isDefault = true),
                ),
            ),
            api,
            canPlayAudioMime = { _, _ -> false },
        )
        onMain { engine.startPlayback(null, initialPlayWhenReady = false, rewindOnResume = true) }
        waitFor("the first manifest") { api.fetched.size == 1 }

        onMain { engine.selectPlaybackMode(PlaybackMode.Direct.name) }
        waitFor("the refusal") { engine.refusals().isNotEmpty() }

        assertTrue(engine.refusals().single().contains("Dolby TrueHD"))
        // Refused, not substituted, and not a playback failure: the session keeps running.
        assertTrue(api.stopped.isEmpty())
        assertEquals(1, api.fetched.size)
        assertTrue(engine.errors().isEmpty())
        assertEquals(
            PlaybackMode.Remux,
            engine.events.replayCache
                .filterIsInstance<MoviePlayerEvent.QualityOptionsChanged>()
                .last()
                .requestedMode,
        )
    }

    @Test
    fun aPlayableTrackIsNeverRefused() {
        val api = FakeHlsApi()
        val engine = engine(playRequest(mode = PlaybackMode.Remux), api, canPlayAudioMime = { _, _ -> true })
        onMain { engine.startPlayback(null, initialPlayWhenReady = false, rewindOnResume = true) }
        waitFor("the first manifest") { api.fetched.size == 1 }

        onMain { engine.selectPlaybackMode(PlaybackMode.Direct.name) }
        waitFor("the stop") { api.stopped.isNotEmpty() }

        assertTrue(engine.refusals().isEmpty())
    }

    // --- the automatic audio conversion ---

    private fun dtsTrack() = PlayableAudioTrack(
        label = "English · DTS-HD",
        codec = "dts",
        codecProfile = "DTS-HD MA",
        channels = 6,
        isDefault = true,
    )

    @Test
    fun aDirectRequestOverADtsTrackStartsARemuxSessionWithTheConversionPair() {
        val api = FakeHlsApi()
        val engine = engine(playRequest(mode = PlaybackMode.Direct, audioTracks = listOf(dtsTrack())), api)

        onMain { engine.startPlayback(null, initialPlayWhenReady = false, rewindOnResume = true) }
        waitFor("the manifest") { api.fetched.isNotEmpty() }

        // Original video (remux copies it), converted soundtrack — never a Direct source.
        assertEquals("remux", api.fetched.single().profileId)
        assertEquals(HlsAudioProfile.DolbyDigitalPlus, api.fetched.single().audioProfile)

        // The quality menu tells the truth: Remux effective, Remux the reported intent.
        waitFor("the quality options") {
            engine.events.replayCache.any { it is MoviePlayerEvent.QualityOptionsChanged }
        }
        val ladder = engine.events.replayCache
            .filterIsInstance<MoviePlayerEvent.QualityOptionsChanged>()
            .last()
        assertEquals(PlaybackMode.Remux.name, ladder.options.single { it.selected }.id)
        assertEquals(PlaybackMode.Remux, ladder.requestedMode)

        // Re-picking Direct resolves back to Remux: a no-op, not a refusal or a new session.
        onMain { engine.selectPlaybackMode(PlaybackMode.Direct.name) }
        instrumentation.waitForIdleSync()
        assertEquals(1, api.fetched.size)
        assertTrue(engine.refusals().isEmpty())
        assertTrue(api.stopped.isEmpty())
    }

    @Test
    fun aDirectRequestOverAReliableTrackNeverTouchesTheBackend() {
        val api = FakeHlsApi()
        val engine = engine(
            playRequest(
                mode = PlaybackMode.Direct,
                audioTracks = listOf(
                    PlayableAudioTrack(label = "English · Stereo", codec = "aac", channels = 2, isDefault = true),
                ),
            ),
            api,
        )

        onMain { engine.startPlayback(null, initialPlayWhenReady = false, rewindOnResume = true) }
        waitFor("the quality options") {
            engine.events.replayCache.any { it is MoviePlayerEvent.QualityOptionsChanged }
        }

        assertTrue(api.fetched.isEmpty())
        val ladder = engine.events.replayCache
            .filterIsInstance<MoviePlayerEvent.QualityOptionsChanged>()
            .last()
        assertEquals(PlaybackMode.Direct.name, ladder.options.single { it.selected }.id)
    }

    @Test
    fun aChosenQualityKeepsItsProfileAndAddsTheConversionForTheTrack() {
        val ladderApi = FakeHlsApi()
        ladderApi.result = HlsManifestResult.Ready("1080p_8mbps", 0.0)
        val ladder = engine(
            playRequest(mode = PlaybackMode.P1080Mbps8, audioTracks = listOf(dtsTrack())),
            ladderApi,
        )
        onMain { ladder.startPlayback(null, initialPlayWhenReady = false, rewindOnResume = true) }
        waitFor("the ladder manifest") { ladderApi.fetched.isNotEmpty() }
        assertEquals("1080p_8mbps", ladderApi.fetched.single().profileId)
        assertEquals(HlsAudioProfile.DolbyDigitalPlus, ladderApi.fetched.single().audioProfile)

        val remuxApi = FakeHlsApi()
        val remux = engine(
            playRequest(
                mode = PlaybackMode.Remux,
                audioTracks = listOf(
                    PlayableAudioTrack(label = "English · 5.1", codec = "aac", channels = 6, isDefault = true),
                ),
            ),
            remuxApi,
        )
        onMain { remux.startPlayback(null, initialPlayWhenReady = false, rewindOnResume = true) }
        waitFor("the remux manifest") { remuxApi.fetched.isNotEmpty() }
        assertEquals("remux", remuxApi.fetched.single().profileId)
        assertEquals(HlsAudioProfile.DolbyDigital, remuxApi.fetched.single().audioProfile)
    }

    @Test
    fun anHlsAudioSwitchRecomputesTheConversionPerTrack() {
        val api = FakeHlsApi()
        val engine = engine(
            playRequest(
                mode = PlaybackMode.Remux,
                audioTracks = listOf(
                    dtsTrack(),
                    PlayableAudioTrack(label = "Spanish · Stereo", codec = "aac", channels = 2),
                ),
            ),
            api,
        )
        onMain { engine.startPlayback(null, initialPlayWhenReady = false, rewindOnResume = true) }
        waitFor("the first manifest") { api.fetched.size == 1 }
        assertEquals(HlsAudioProfile.DolbyDigitalPlus, api.fetched[0].audioProfile)

        onMain { engine.selectAudioTrack("audio:1") }
        waitFor("the switch to the stereo track") { api.fetched.size == 2 }
        assertNull(api.fetched[1].audioProfile)

        onMain { engine.selectAudioTrack("audio:0") }
        waitFor("the switch back") { api.fetched.size == 3 }
        assertEquals(HlsAudioProfile.DolbyDigitalPlus, api.fetched[2].audioProfile)
    }

    // --- the subtitle choice across Direct/HLS swaps ---

    /** The wire list of a movie mixing text and image-based streams; ordinal 1 is the bitmap. */
    private fun mixedSubtitles() = listOf(
        PlayableSubtitleTrack(label = "English"),
        PlayableSubtitleTrack(label = "English · PGS", imageBased = true),
        PlayableSubtitleTrack(label = "Spanish"),
    )

    private fun selectedQualityId(engine: MoviePlayerEngine): String? =
        engine.events.replayCache
            .filterIsInstance<MoviePlayerEvent.QualityOptionsChanged>()
            .lastOrNull()
            ?.options
            ?.singleOrNull { it.selected }
            ?.id

    /** Reads the live selection parameters on the main thread via the instrumentation seam. */
    private fun selectionParameters(engine: MoviePlayerEngine): TrackSelectionParameters {
        lateinit var params: TrackSelectionParameters
        onMain { params = (engine as ExoMoviePlayerEngine).currentTrackSelectionParameters }
        return params
    }

    private fun hasTextOverride(params: TrackSelectionParameters): Boolean =
        params.overrides.keys.any { it.type == C.TRACK_TYPE_TEXT }

    @Test
    fun switchingToHlsWithABitmapSubtitleGoesDeterministicallyOffAndKeepsTheChoice() {
        val api = FakeHlsApi()
        val engine = engine(
            playRequest(subtitleTypeIndex = 1, subtitleTracks = mixedSubtitles()),
            api,
        )
        onMain { engine.startPlayback(null, initialPlayWhenReady = false, rewindOnResume = true) }
        waitFor("the direct source") { selectedQualityId(engine) == PlaybackMode.Direct.name }

        onMain { engine.selectPlaybackMode(PlaybackMode.Remux.name) }
        waitFor("the committed remux source") { selectedQualityId(engine) == PlaybackMode.Remux.name }

        // The choice survives, but nothing may render for it: text off, no stale override left
        // for Media3 to trade against a sideloaded VTT.
        assertEquals(1, engine.currentSubtitleTypeIndex)
        val params = selectionParameters(engine)
        assertTrue(params.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
        assertFalse(hasTextOverride(params))
    }

    @Test
    fun reconstructingIntoHlsWithABitmapOrdinalStaysOffButKeepsTheOrdinal() {
        // The lifecycle path: a rebuilt engine is seeded straight from the persisted request.
        val api = FakeHlsApi()
        val engine = engine(
            playRequest(mode = PlaybackMode.Remux, subtitleTypeIndex = 1, subtitleTracks = mixedSubtitles()),
            api,
        )
        onMain { engine.startPlayback(null, initialPlayWhenReady = false, rewindOnResume = false) }
        waitFor("the committed remux source") { selectedQualityId(engine) == PlaybackMode.Remux.name }

        assertEquals(1, engine.currentSubtitleTypeIndex)
        val params = selectionParameters(engine)
        assertTrue(params.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
        assertFalse(hasTextOverride(params))
    }

    @Test
    fun returningToDirectReenablesTextForTheRememberedChoice() {
        // The override itself needs real track groups, which the TEST-NET fixture never
        // produces; that mapping leg is covered by TrackOptionsTest. What the engine owns is
        // keeping the ordinal and re-enabling the text type for the swap-apply to use.
        val api = FakeHlsApi()
        val engine = engine(
            playRequest(mode = PlaybackMode.Remux, subtitleTypeIndex = 1, subtitleTracks = mixedSubtitles()),
            api,
        )
        onMain { engine.startPlayback(null, initialPlayWhenReady = false, rewindOnResume = false) }
        waitFor("the committed remux source") { selectedQualityId(engine) == PlaybackMode.Remux.name }

        onMain { engine.selectPlaybackMode(PlaybackMode.Direct.name) }
        waitFor("the direct source") { selectedQualityId(engine) == PlaybackMode.Direct.name }

        assertEquals(1, engine.currentSubtitleTypeIndex)
        assertFalse(selectionParameters(engine).disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
    }

    @Test
    fun aTextOrdinalKeepsTextEnabledUnderHls() {
        val api = FakeHlsApi()
        val engine = engine(
            playRequest(mode = PlaybackMode.Remux, subtitleTypeIndex = 0, subtitleTracks = mixedSubtitles()),
            api,
        )
        onMain { engine.startPlayback(null, initialPlayWhenReady = false, rewindOnResume = false) }
        waitFor("the committed remux source") { selectedQualityId(engine) == PlaybackMode.Remux.name }

        assertEquals(0, engine.currentSubtitleTypeIndex)
        assertFalse(selectionParameters(engine).disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
    }

    @Test
    fun anUnresolvableSubtitleOptionIdIsANoOp() {
        // An id that maps to no wire ordinal must not half-apply and corrupt the memory.
        val engine = engine(playRequest(subtitleTypeIndex = 1, subtitleTracks = mixedSubtitles()))
        onMain { engine.startPlayback(null, initialPlayWhenReady = false, rewindOnResume = true) }
        waitFor("the direct source") { selectedQualityId(engine) == PlaybackMode.Direct.name }

        onMain { engine.selectSubtitleTrack("5:0") }
        instrumentation.waitForIdleSync()
        assertEquals(1, engine.currentSubtitleTypeIndex)
    }

    // --- failing honestly ---

    @Test
    fun aRefusedManifestSurfacesItsMessageAndStopsEveryBackendPath() {
        val api = FakeHlsApi()
        api.result = HlsManifestResult.Failed("The server refused the stream (HTTP 500).")
        val engine = engine(playRequest(mode = PlaybackMode.Remux), api)

        onMain { engine.startPlayback(null, initialPlayWhenReady = true, rewindOnResume = true) }
        waitFor("the error") { engine.errors().isNotEmpty() }

        assertEquals("The server refused the stream (HTTP 500).", engine.errors().single().message)
        // The terminal boundary stops the session it had already asked the server to create.
        waitFor("the stop") { api.stopped.isNotEmpty() }
        // And nothing may restart behind the error screen.
        onMain { engine.selectPlaybackMode(PlaybackMode.P720Mbps3.name) }
        instrumentation.waitForIdleSync()
        assertEquals(1, api.fetched.size)
    }

    @Test
    fun aProgrammingErrorInTheManifestPathReachesTheErrorSurfaceInsteadOfTheCrashHandler() {
        val api = FakeHlsApi()
        // What `serverUrl.require()` throws, and what the repository deliberately rethrows.
        api.throwOnFetch = { error("Server address requested before setup completed") }
        val engine = engine(playRequest(mode = PlaybackMode.Remux), api)

        onMain { engine.startPlayback(null, initialPlayWhenReady = true, rewindOnResume = true) }
        waitFor("the error") { engine.errors().isNotEmpty() }

        assertTrue(engine.errors().single().message.contains("Server address"))
    }
}
