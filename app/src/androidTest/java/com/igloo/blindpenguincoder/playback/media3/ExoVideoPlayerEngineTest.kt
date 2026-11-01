@file:androidx.annotation.OptIn(UnstableApi::class)

package com.igloo.blindpenguincoder.playback.media3

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.igloo.blindpenguincoder.data.model.PlaybackMode
import com.igloo.blindpenguincoder.playback.hls.HLS_SEEK_SETTLE_MS
import com.igloo.blindpenguincoder.playback.hls.HLS_SESSION_LOST_MAX_ATTEMPTS
import com.igloo.blindpenguincoder.playback.hls.HlsManifestResult
import com.igloo.blindpenguincoder.playback.hls.HlsSessionApi
import com.igloo.blindpenguincoder.playback.hls.HlsSessionSpec
import com.igloo.blindpenguincoder.playback.model.HlsAudioProfile
import com.igloo.blindpenguincoder.playback.model.PlayableAudioTrack
import com.igloo.blindpenguincoder.playback.model.PlayableSubtitleTrack
import com.igloo.blindpenguincoder.playback.model.PlaybackMediaRef
import com.igloo.blindpenguincoder.playback.model.SUBTITLES_UNAVAILABLE_MESSAGE
import com.igloo.blindpenguincoder.playback.model.TrackOption
import com.igloo.blindpenguincoder.playback.model.VideoPlayRequest
import com.igloo.blindpenguincoder.playback.model.VideoPlayerEvent
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
class ExoVideoPlayerEngineTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private val stopScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val engines = mutableListOf<VideoPlayerEngine>()

    @After
    fun releaseAll() {
        instrumentation.runOnMainSync {
            engines.forEach { it.release() }
            engines.clear()
        }
        stopScope.cancel()
    }

    /** [pathPrefix] lets a test put the session assets under the server's real `/api` routes. */
    private class FakeHlsApi(private val pathPrefix: String = "") : HlsSessionApi {
        val fetched = mutableListOf<HlsSessionSpec>()
        val fetchedAtMs = mutableListOf<Long>()
        val stopped = mutableListOf<String>()
        val subtitleUrls = mutableListOf<String>()
        var result: HlsManifestResult = HlsManifestResult.Ready("remux", 0.0)
        var throwOnFetch: (() -> Nothing)? = null
        var suspendedResult: CompletableDeferred<HlsManifestResult>? = null

        override suspend fun fetchHlsManifest(
            spec: HlsSessionSpec,
            keepalive: Boolean,
        ): HlsManifestResult {
            fetched += spec
            fetchedAtMs += SystemClock.elapsedRealtime()
            throwOnFetch?.invoke()
            return suspendedResult?.await() ?: result
        }

        override suspend fun stopHlsSession(media: PlaybackMediaRef, sessionUuid: String) {
            stopped += sessionUuid
        }

        override fun hlsPlaylistUrl(spec: HlsSessionSpec): String =
            "https://203.0.113.1$pathPrefix/movies/${spec.media.id}/hls/${spec.profileId}/playlist.m3u8"

        override fun subtitleStreamUrl(media: PlaybackMediaRef, trackIndex: Int, startSec: Double): String {
            val query = if (startSec > 0.0) "?start=$startSec" else ""
            return "https://203.0.113.1$pathPrefix/movies/${media.id}/subtitles/$trackIndex/stream.mkv$query"
                .also { subtitleUrls += it }
        }
    }

    private fun playRequest(
        mode: PlaybackMode = PlaybackMode.Direct,
        videoCodec: String? = null,
        audioTracks: List<PlayableAudioTrack> = listOf(
            PlayableAudioTrack(label = "English · Surround", codec = "eac3", isDefault = true),
            PlayableAudioTrack(label = "Spanish · Stereo", codec = "aac"),
        ),
        subtitleTypeIndex: Int? = null,
        subtitleTracks: List<PlayableSubtitleTrack> = listOf(PlayableSubtitleTrack(label = "English")),
    ) = VideoPlayRequest(
        media = PlaybackMediaRef.Movie(7),
        title = "Heat",
        posterUrl = null,
        mimeType = "video/x-matroska",
        mode = mode,
        videoCodec = videoCodec,
        audioTypeIndex = null,
        subtitleTypeIndex = subtitleTypeIndex,
        audioTracks = audioTracks,
        subtitleTracks = subtitleTracks,
        resumeAtSec = null,
        durationSec = 7200.0,
    )

    private fun engine(
        request: VideoPlayRequest = playRequest(),
        api: FakeHlsApi = FakeHlsApi(),
        canPlayAudioMime: (String, Int?) -> Boolean = { _, _ -> true },
        canPlayVideoMime: (String) -> Boolean = { true },
        hlsDataSourceFactory: DataSource.Factory = DefaultHttpDataSource.Factory(),
    ): VideoPlayerEngine {
        lateinit var built: VideoPlayerEngine
        instrumentation.runOnMainSync {
            built = exoVideoPlayerEngine(
                context = context,
                request = request,
                services = VideoPlaybackServices(
                    progressiveDataSourceFactory = DefaultHttpDataSource.Factory(),
                    hlsDataSourceFactory = hlsDataSourceFactory,
                    directStreamUrl = { "https://203.0.113.1/movies/${it.id}/stream" },
                    hlsSessionApi = api,
                    canPlayVideoMime = canPlayVideoMime,
                    canPlayAudioMime = canPlayAudioMime,
                    stopScope = stopScope,
                ),
            )
            engines += built
        }
        return built
    }

    /** Pumps the main looper until [condition] holds; the engine's work is all posted there. */
    private fun waitFor(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(20)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun VideoPlayerEngine.errors(): List<VideoPlayerEvent.Error> =
        events.replayCache.filterIsInstance<VideoPlayerEvent.Error>()

    private fun VideoPlayerEngine.refusals(): List<String> =
        events.replayCache.filterIsInstance<VideoPlayerEvent.ModeRefused>().map { it.message }

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
    fun aRunOfStepsPastTheWindowRebasesOnceAtItsFinalTarget() {
        val api = FakeHlsApi()
        val engine = engine(playRequest(mode = PlaybackMode.Remux), api)
        onMain { engine.startPlayback(null, initialPlayWhenReady = false, rewindOnResume = false) }
        waitFor("the first manifest") { api.fetched.size == 1 }

        // A held D-pad key. ExoPlayer reports each step's target as its position, so measuring
        // from there never crossed the window and left the player minutes past the encoder.
        onMain { (1..30).forEach { engine.seekTo(it * 10.0) } }
        waitFor("the settled rebase") { api.fetched.size == 2 }
        assertEquals(300, api.fetched[1].startSec)

        SystemClock.sleep(HLS_SEEK_SETTLE_MS * 2)
        instrumentation.waitForIdleSync()
        assertEquals(2, api.fetched.size)
    }

    @Test
    fun aStepBackIntoRangeDropsTheHeldRebase() {
        val api = FakeHlsApi()
        val engine = engine(playRequest(mode = PlaybackMode.Remux), api)
        onMain { engine.startPlayback(null, initialPlayWhenReady = false, rewindOnResume = false) }
        waitFor("the first manifest") { api.fetched.size == 1 }

        onMain {
            engine.seekTo(1_000.0)
            engine.seekTo(60.0)
        }
        SystemClock.sleep(HLS_SEEK_SETTLE_MS * 2)
        instrumentation.waitForIdleSync()
        assertEquals(1, api.fetched.size)
    }

    @Test
    fun aSeekThePendingSessionCoversMovesWhereItsSourceIsPrepared() {
        val api = FakeHlsApi()
        val engine = engine(playRequest(mode = PlaybackMode.Remux), api)
        onMain { engine.startPlayback(null, initialPlayWhenReady = false, rewindOnResume = false) }
        waitFor("the first manifest") { api.fetched.size == 1 }

        val preflight = CompletableDeferred<HlsManifestResult>()
        api.suspendedResult = preflight
        onMain { engine.seekTo(1_000.0) }
        waitFor("the pending rebase") { api.fetched.size == 2 }

        // Against the frozen source this was another far seek, and a near one was lost when
        // the pending source replaced it.
        onMain { engine.seekTo(1_030.0) }
        preflight.complete(HlsManifestResult.Ready("remux", 1_000.0))
        waitFor("the source prepared at the moved target") {
            engine.events.replayCache
                .filterIsInstance<VideoPlayerEvent.Time>()
                .lastOrNull()
                ?.currentSec == 1_030.0
        }
        assertEquals(2, api.fetched.size)
    }

    @Test
    fun aFarSeekDuringAPendingSwitchKeepsTheSwitch() {
        val api = FakeHlsApi()
        val engine = engine(playRequest(mode = PlaybackMode.Remux), api)
        onMain { engine.startPlayback(null, initialPlayWhenReady = false, rewindOnResume = false) }
        waitFor("the first manifest") { api.fetched.size == 1 }

        api.suspendedResult = CompletableDeferred()
        onMain { engine.selectPlaybackMode(PlaybackMode.P1080Mbps8.name) }
        waitFor("the pending switch") { api.fetched.size == 2 }

        api.suspendedResult = null
        onMain { engine.seekTo(3_000.0) }
        waitFor("the rebase") { api.fetched.size == 3 }
        assertEquals(3_000, api.fetched[2].startSec)
        assertEquals("1080p_8mbps", api.fetched[2].profileId)
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
            engine.events.replayCache.any { it is VideoPlayerEvent.QualityOptionsChanged }
        }

        val ladder = engine.events.replayCache
            .filterIsInstance<VideoPlayerEvent.QualityOptionsChanged>()
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
            engine.events.replayCache.any { it is VideoPlayerEvent.QualityOptionsChanged }
        }

        val firstPreflight = CompletableDeferred<HlsManifestResult>()
        api.suspendedResult = firstPreflight
        onMain { engine.selectPlaybackMode(PlaybackMode.P1080Mbps8.name) }
        waitFor("the suspended preflight") { api.fetched.size == 1 }

        val pending = engine.events.replayCache
            .filterIsInstance<VideoPlayerEvent.QualityOptionsChanged>()
            .last()
        assertEquals(PlaybackMode.P1080Mbps8, pending.requestedMode)
        assertEquals(PlaybackMode.Direct.name, pending.options.single { it.selected }.id)

        firstPreflight.complete(HlsManifestResult.Ready("1080p_8mbps", 0.0))
        waitFor("the committed quality") {
            engine.events.replayCache
                .filterIsInstance<VideoPlayerEvent.QualityOptionsChanged>()
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
                .filterIsInstance<VideoPlayerEvent.QualityOptionsChanged>()
                .last()
                .requestedMode,
        )

        secondPreflight.complete(HlsManifestResult.Failed("The server refused the stream."))
        waitFor("the terminal failure") { engine.errors().isNotEmpty() }

        val events = engine.events.replayCache
        val errorIndex = events.indexOfLast { it is VideoPlayerEvent.Error }
        val rollbackIndex = events.indexOfLast {
            it is VideoPlayerEvent.QualityOptionsChanged &&
                it.requestedMode == PlaybackMode.P1080Mbps8
        }
        assertTrue("the committed request must be restored before the error", rollbackIndex in 0 until errorIndex)
        val rollback = events[rollbackIndex] as VideoPlayerEvent.QualityOptionsChanged
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
                .filterIsInstance<VideoPlayerEvent.QualityOptionsChanged>()
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
            .filterIsInstance<VideoPlayerEvent.QualityOptionsChanged>()
            .last()
        assertEquals(PlaybackMode.Remux, restored.requestedMode)
        assertEquals(PlaybackMode.P1080Mbps8.name, restored.options.single { it.selected }.id)

        preflight.complete(HlsManifestResult.Ready("720p_3mbps", 0.0))
        instrumentation.waitForIdleSync()
        val afterLateCompletion = engine.events.replayCache
            .filterIsInstance<VideoPlayerEvent.QualityOptionsChanged>()
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
                .filterIsInstance<VideoPlayerEvent.QualityOptionsChanged>()
                .last()
                .requestedMode,
        )
    }

    @Test
    fun switchingToDirectIsRefusedWhenThisTvCannotDecodeTheVideo() {
        val api = FakeHlsApi()
        val engine = engine(
            playRequest(mode = PlaybackMode.Remux, videoCodec = "msmpeg4v3"),
            api,
            canPlayVideoMime = { false },
        )
        onMain { engine.startPlayback(null, initialPlayWhenReady = false, rewindOnResume = true) }
        waitFor("the first manifest") { api.fetched.size == 1 }

        onMain { engine.selectPlaybackMode(PlaybackMode.Direct.name) }
        waitFor("the refusal") { engine.refusals().isNotEmpty() }

        assertTrue(engine.refusals().single().contains("DivX 3"))
        assertTrue(api.stopped.isEmpty())
        assertTrue(engine.errors().isEmpty())
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
            engine.events.replayCache.any { it is VideoPlayerEvent.QualityOptionsChanged }
        }
        val ladder = engine.events.replayCache
            .filterIsInstance<VideoPlayerEvent.QualityOptionsChanged>()
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
            engine.events.replayCache.any { it is VideoPlayerEvent.QualityOptionsChanged }
        }

        assertTrue(api.fetched.isEmpty())
        val ladder = engine.events.replayCache
            .filterIsInstance<VideoPlayerEvent.QualityOptionsChanged>()
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
        PlayableSubtitleTrack(label = "English · PGS"),
        PlayableSubtitleTrack(label = "Spanish"),
    )

    private fun selectedQualityId(engine: VideoPlayerEngine): String? =
        engine.events.replayCache
            .filterIsInstance<VideoPlayerEvent.QualityOptionsChanged>()
            .lastOrNull()
            ?.options
            ?.singleOrNull { it.selected }
            ?.id

    /** Reads the live selection parameters on the main thread via the instrumentation seam. */
    private fun selectionParameters(engine: VideoPlayerEngine): TrackSelectionParameters {
        lateinit var params: TrackSelectionParameters
        onMain { params = (engine as ExoVideoPlayerEngine).currentTrackSelectionParameters }
        return params
    }

    private fun hasTextOverride(params: TrackSelectionParameters): Boolean =
        params.overrides.keys.any { it.type == C.TRACK_TYPE_TEXT }

    @Test
    fun switchingToHlsWithABitmapSubtitleKeepsItOn() {
        // An HLS session streams the original track next to the video, so a bitmap choice
        // renders there exactly as under Direct.
        val api = FakeHlsApi()
        val engine = engine(
            playRequest(subtitleTypeIndex = 1, subtitleTracks = mixedSubtitles()),
            api,
        )
        onMain { engine.startPlayback(null, initialPlayWhenReady = false, rewindOnResume = true) }
        waitFor("the direct source") { selectedQualityId(engine) == PlaybackMode.Direct.name }

        onMain { engine.selectPlaybackMode(PlaybackMode.Remux.name) }
        waitFor("the committed remux source") { selectedQualityId(engine) == PlaybackMode.Remux.name }

        assertEquals(1, engine.currentSubtitleTypeIndex)
        assertFalse(selectionParameters(engine).disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
        assertEquals(listOf("https://203.0.113.1/movies/7/subtitles/1/stream.mkv"), api.subtitleUrls)
    }

    @Test
    fun reconstructingIntoHlsWithABitmapOrdinalStreamsIt() {
        // The lifecycle path: a rebuilt engine is seeded straight from the persisted request.
        val api = FakeHlsApi()
        api.result = HlsManifestResult.Ready("remux", 584.25)
        val engine = engine(
            playRequest(mode = PlaybackMode.Remux, subtitleTypeIndex = 1, subtitleTracks = mixedSubtitles()),
            api,
        )
        onMain { engine.startPlayback(590.0, initialPlayWhenReady = false, rewindOnResume = false) }
        waitFor("the committed remux source") { selectedQualityId(engine) == PlaybackMode.Remux.name }

        assertEquals(1, engine.currentSubtitleTypeIndex)
        assertFalse(selectionParameters(engine).disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
        // The stream's start is the session's actual start, which puts it on the same timeline.
        assertEquals(listOf("https://203.0.113.1/movies/7/subtitles/1/stream.mkv?start=584.25"), api.subtitleUrls)
    }

    @Test
    fun anHlsSessionWithoutSubtitlesStreamsNone() {
        val api = FakeHlsApi()
        val engine = engine(playRequest(mode = PlaybackMode.Remux, subtitleTracks = mixedSubtitles()), api)
        onMain { engine.startPlayback(null, initialPlayWhenReady = false, rewindOnResume = false) }
        waitFor("the committed remux source") { selectedQualityId(engine) == PlaybackMode.Remux.name }

        assertNull(engine.currentSubtitleTypeIndex)
        assertTrue(selectionParameters(engine).disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
        assertTrue(api.subtitleUrls.isEmpty())
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

    // --- mid-play lost-session recovery ---

    /**
     * Serves a one-segment playlist and answers every segment with a 500, which is what a
     * session whose FFmpeg dies at the same point in the film looks like to the player.
     * [failedAtMs] records when each 500 was answered.
     */
    private fun segmentsFailWith500(failedAtMs: MutableList<Long>): DataSource.Factory = DataSource.Factory {
        object : DataSource {
            private var delegate: ByteArrayDataSource? = null

            override fun addTransferListener(transferListener: TransferListener) = Unit

            override fun open(dataSpec: DataSpec): Long {
                if (dataSpec.uri.path?.endsWith("playlist.m3u8") != true) {
                    synchronized(failedAtMs) { failedAtMs += SystemClock.elapsedRealtime() }
                    throw HttpDataSource.InvalidResponseCodeException(
                        500,
                        /* responseMessage= */ null,
                        /* cause= */ null,
                        emptyMap(),
                        dataSpec,
                        ByteArray(0),
                    )
                }
                val playlist = "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:4\n" +
                    "#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:4.0,\nsegment_0.m4s\n#EXT-X-ENDLIST\n"
                return ByteArrayDataSource(playlist.toByteArray()).also { delegate = it }.open(dataSpec)
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                requireNotNull(delegate).read(buffer, offset, length)

            override fun getUri() = delegate?.uri

            override fun close() {
                delegate?.close()
                delegate = null
            }
        }
    }

    @Test
    fun aFailureThatRepeatsAtOncePacesItsRecoveriesTwoSecondsApart() {
        // Recreating the session at once spent the whole recovery budget in well under a second
        // when the new session failed the same way, as one whose FFmpeg dies at a fixed point does.
        val api = FakeHlsApi(pathPrefix = "/api")
        val failedAtMs = mutableListOf<Long>()
        val engine = engine(
            playRequest(mode = PlaybackMode.Remux),
            api,
            hlsDataSourceFactory = segmentsFailWith500(failedAtMs),
        )

        onMain { engine.startPlayback(null, initialPlayWhenReady = true, rewindOnResume = true) }
        waitFor("the recoveries to run out", timeoutMs = 15_000) { engine.errors().isNotEmpty() }

        // The first start, then one manifest per recovery.
        assertEquals(1 + HLS_SESSION_LOST_MAX_ATTEMPTS, api.fetched.size)
        // The first recovery of the incident runs at once: measured from the failure that set
        // it off, so the playlist load before it does not count.
        val firstFailureMs = synchronized(failedAtMs) { failedAtMs.first() }
        val firstRecoveryWaitMs = api.fetchedAtMs[1] - firstFailureMs
        assertTrue("first recovery waited $firstRecoveryWaitMs ms", firstRecoveryWaitMs < 1_000)
        // Each one after waits out the spacing from the recovery before it.
        val gaps = api.fetchedAtMs.zipWithNext { earlier, later -> later - earlier }
        gaps.drop(1).forEach { gap -> assertTrue("recoveries $gap ms apart", gap >= 1_900) }
    }

    // --- subtitle streams over a real session (fixtures served by SubtitleFixtureServer) ---

    private fun streamedSubtitleSelected(engine: VideoPlayerEngine): Boolean {
        var selected = false
        onMain {
            selected = (engine as ExoVideoPlayerEngine).currentTracks.groups.any {
                it.type == C.TRACK_TYPE_TEXT && it.isSelected &&
                    it.getTrackFormat(0).id?.startsWith(SUBTITLE_STREAM_ID_PREFIX) == true
            }
        }
        return selected
    }

    private fun VideoPlayerEngine.subtitleRows(): List<TrackOption> =
        events.replayCache.filterIsInstance<VideoPlayerEvent.TracksChanged>().lastOrNull()?.subtitles.orEmpty()

    private fun VideoPlayerEngine.statusMessages(): List<String?> =
        events.replayCache.filterIsInstance<VideoPlayerEvent.StatusMessage>().map { it.message }

    private fun VideoPlayerEngine.latestTimeSec(): Double =
        events.replayCache.filterIsInstance<VideoPlayerEvent.Time>().lastOrNull()?.currentSec ?: 0.0

    private fun SubtitleFixtureServer.playlistRequests(): Int =
        synchronized(requests) { requests.count { it.endsWith("playlist.m3u8") } }

    private fun streamingEngine(
        server: SubtitleFixtureServer,
        api: FakeHlsApi,
        subtitleTypeIndex: Int?,
    ): VideoPlayerEngine {
        val engine = engine(
            playRequest(mode = PlaybackMode.Remux, subtitleTypeIndex = subtitleTypeIndex, subtitleTracks = mixedSubtitles()),
            api,
            hlsDataSourceFactory = server.factory,
        )
        onMain { engine.startPlayback(null, initialPlayWhenReady = true, rewindOnResume = false) }
        return engine
    }

    @Test
    fun aChosenBitmapTrackStreamsNextToTheSessionAndIsTheSelectedText() {
        val server = SubtitleFixtureServer()
        val api = FakeHlsApi(pathPrefix = "/api")
        val engine = streamingEngine(server, api, subtitleTypeIndex = 0)

        waitFor("the streamed subtitle to be selected", timeoutMs = 15_000) { streamedSubtitleSelected(engine) }

        assertTrue(server.subtitleRequests(0) >= 1)
        assertEquals(listOf(true, false, false), engine.subtitleRows().map { it.selected })
        assertEquals(listOf("sub:0", "sub:1", "sub:2"), engine.subtitleRows().map { it.id })
        assertTrue("${engine.errors()}", engine.errors().isEmpty())
    }

    private fun VideoPlayerEngine.cueTexts(): List<String> {
        var texts = emptyList<String>()
        onMain { texts = (this as ExoVideoPlayerEngine).currentCues.mapNotNull { it.text?.toString() } }
        return texts
    }

    /**
     * A subtitle chosen before playback — by the default rule or the dialog — on a session played
     * from its start: the cues must reach the screen without any in-player pick.
     */
    @Test
    fun aSubtitleChosenBeforePlaybackRendersFromTheSessionStart() {
        val server = SubtitleFixtureServer()
        val api = FakeHlsApi(pathPrefix = "/api")
        val engine = streamingEngine(server, api, subtitleTypeIndex = 1)

        waitFor("the cue at 1.0 s", timeoutMs = 15_000) { "One" in engine.cueTexts() }
        assertTrue("${engine.errors()}", engine.errors().isEmpty())
    }

    /** A remux session still being produced: Media3 plays its EVENT playlist as live. */
    @Test
    fun aSubtitleChosenBeforePlaybackRendersOnALiveRemuxSession() {
        val server = SubtitleFixtureServer()
        server.eventPlaylist = true
        val api = FakeHlsApi(pathPrefix = "/api")
        val engine = streamingEngine(server, api, subtitleTypeIndex = 1)

        waitFor("the cue at 1.0 s", timeoutMs = 15_000) { "One" in engine.cueTexts() }
        assertTrue("${engine.errors()}", engine.errors().isEmpty())
    }

    /**
     * The default rule turns forced tracks on, and a forced track is sparse: between its cues the
     * server's stream sends nothing but keepalives. The video must load and play on regardless —
     * here through the start-up path, where the engine's own pick restarts the stream.
     */
    @Test
    fun aSubtitleStreamWaitingForItsNextCueDoesNotStallTheVideo() {
        val server = SubtitleFixtureServer()
        server.subtitleHoldMs = 9_000
        val api = FakeHlsApi(pathPrefix = "/api")
        val startedAt = SystemClock.uptimeMillis()
        val engine = streamingEngine(server, api, subtitleTypeIndex = 1)

        waitFor("playback past 6 s", timeoutMs = 20_000) { engine.latestTimeSec() >= 6.0 }

        val tookMs = SystemClock.uptimeMillis() - startedAt
        assertTrue("6 s of video took $tookMs ms: it waited for the subtitle stream", tookMs < 8_500)
        val afterReady = engine.events.replayCache.dropWhile { it !is VideoPlayerEvent.Ready }
        assertTrue("rebuffered: $afterReady", afterReady.none { it is VideoPlayerEvent.Buffering })
        assertTrue("${engine.errors()}", engine.errors().isEmpty())
    }

    @Test
    fun anotherTrackUnderHlsStreamsOnTheSameSession() {
        val server = SubtitleFixtureServer()
        val api = FakeHlsApi(pathPrefix = "/api")
        val engine = streamingEngine(server, api, subtitleTypeIndex = 0)
        waitFor("the first stream", timeoutMs = 15_000) { streamedSubtitleSelected(engine) }

        onMain { engine.selectSubtitleTrack("sub:2") }
        waitFor("the second stream", timeoutMs = 15_000) {
            server.subtitleRequests(2) >= 1 && streamedSubtitleSelected(engine)
        }

        // A subtitle change never costs a new server session, or a transcode permit.
        assertEquals(1, api.fetched.size)
        assertEquals(2, engine.currentSubtitleTypeIndex)
        assertEquals(listOf(false, false, true), engine.subtitleRows().map { it.selected })
        assertTrue("${engine.errors()}", engine.errors().isEmpty())
    }

    /**
     * A track picked while a mode switch is pending waits for the new source; cancelling the
     * switch keeps the old source, which must then stream the pick the menu shows.
     */
    @Test
    fun aTrackPickedDuringAPendingSwitchStreamsWhenTheSwitchIsCancelled() {
        val server = SubtitleFixtureServer()
        val api = FakeHlsApi(pathPrefix = "/api")
        val engine = streamingEngine(server, api, subtitleTypeIndex = 0)
        waitFor("the first stream", timeoutMs = 15_000) { streamedSubtitleSelected(engine) }

        val preflight = CompletableDeferred<HlsManifestResult>()
        api.suspendedResult = preflight
        onMain { engine.selectPlaybackMode(PlaybackMode.P720Mbps3.name) }
        waitFor("the pending source") { api.fetched.size == 2 }
        onMain { engine.selectSubtitleTrack("sub:2") }
        instrumentation.waitForIdleSync()
        assertEquals("a stream started while the switch was pending", 0, server.subtitleRequests(2))

        // Selecting the effective row cancels the switch.
        onMain { engine.selectPlaybackMode(PlaybackMode.Remux.name) }
        waitFor("the picked stream", timeoutMs = 15_000) {
            server.subtitleRequests(2) >= 1 && streamedSubtitleSelected(engine)
        }
        preflight.complete(HlsManifestResult.Ready("720p_3mbps", 0.0))
        instrumentation.waitForIdleSync()

        assertEquals(2, engine.currentSubtitleTypeIndex)
        assertEquals(listOf(false, false, true), engine.subtitleRows().map { it.selected })
        assertTrue("${engine.errors()}", engine.errors().isEmpty())
    }

    @Test
    fun noneTurnsTextOffAndTheSameTrackComesBackInPlace() {
        val server = SubtitleFixtureServer()
        val api = FakeHlsApi(pathPrefix = "/api")
        val engine = streamingEngine(server, api, subtitleTypeIndex = 1)
        waitFor("the stream", timeoutMs = 15_000) { streamedSubtitleSelected(engine) }
        val playlistsBefore = server.playlistRequests()

        onMain { engine.selectSubtitleTrack(null) }
        waitFor("text to turn off") { !streamedSubtitleSelected(engine) }
        assertNull(engine.currentSubtitleTypeIndex)
        assertTrue(selectionParameters(engine).disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))

        onMain { engine.selectSubtitleTrack("sub:1") }
        waitFor("text to come back", timeoutMs = 15_000) { streamedSubtitleSelected(engine) }

        // Back in place: the source was not prepared again, so the playlist was not reloaded.
        assertEquals(playlistsBefore, server.playlistRequests())
        assertEquals(1, engine.currentSubtitleTypeIndex)
        assertEquals(1, api.fetched.size)
    }

    @Test
    fun aFailingSubtitleStreamCostsTheSubtitlesNeverTheMovie() {
        val server = SubtitleFixtureServer()
        server.subtitleFailure = 404
        val api = FakeHlsApi(pathPrefix = "/api")
        val engine = streamingEngine(server, api, subtitleTypeIndex = 1)

        waitFor("the track to be dropped", timeoutMs = 15_000) { engine.currentSubtitleTypeIndex == null }
        waitFor("playback to carry on", timeoutMs = 15_000) { engine.latestTimeSec() > 1.0 }

        assertTrue(engine.statusMessages().contains(SUBTITLES_UNAVAILABLE_MESSAGE))
        assertTrue("${engine.errors()}", engine.errors().isEmpty())
        assertEquals(1, api.fetched.size)
        assertEquals(listOf(false, false, false), engine.subtitleRows().map { it.selected })
    }

    @Test
    fun aLostSessionWithASubtitleStreamIsStillRecoveredAsASession() {
        // A segment 404 is the session's loss, not the subtitles': it must take the session
        // recovery path and keep the subtitle choice.
        val server = SubtitleFixtureServer()
        server.segmentFailure = 404
        val api = FakeHlsApi(pathPrefix = "/api")
        val engine = streamingEngine(server, api, subtitleTypeIndex = 1)

        waitFor("a session recovery", timeoutMs = 15_000) { api.fetched.size >= 2 }

        assertEquals(1, engine.currentSubtitleTypeIndex)
        assertFalse(engine.statusMessages().contains(SUBTITLES_UNAVAILABLE_MESSAGE))
    }
}
