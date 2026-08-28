@file:androidx.annotation.OptIn(UnstableApi::class)

package com.igloo.blindpenguincoder.playback.media3

import android.os.SystemClock
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
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

        override suspend fun fetchHlsManifest(spec: HlsSessionSpec): HlsManifestResult {
            fetched += spec
            throwOnFetch?.invoke()
            return result
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
    ) = MoviePlayRequest(
        movieId = 7,
        title = "Heat",
        posterUrl = null,
        mimeType = "video/x-matroska",
        mode = mode,
        audioTypeIndex = null,
        subtitleTypeIndex = null,
        audioTracks = audioTracks,
        subtitleTracks = listOf(PlayableSubtitleTrack(label = "English")),
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
