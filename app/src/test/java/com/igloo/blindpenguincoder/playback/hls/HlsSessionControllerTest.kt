package com.igloo.blindpenguincoder.playback.hls

import com.igloo.blindpenguincoder.playback.model.HLS_AUDIO_CONVERSION_UNAVAILABLE_MESSAGE
import com.igloo.blindpenguincoder.playback.model.HlsAudioProfile
import com.igloo.blindpenguincoder.playback.model.PlaybackMediaRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HlsSessionControllerTest {

    private class FakeHlsApi : HlsSessionApi {
        val queuedResults = ArrayDeque<HlsManifestResult>()
        val fetchedSpecs = mutableListOf<HlsSessionSpec>()
        val stops = mutableListOf<Pair<PlaybackMediaRef, String>>()
        var fetchOverride: (suspend (HlsSessionSpec) -> HlsManifestResult)? = null

        override suspend fun fetchHlsManifest(spec: HlsSessionSpec): HlsManifestResult {
            fetchedSpecs += spec
            fetchOverride?.let { return it(spec) }
            return queuedResults.removeFirstOrNull()
                ?: HlsManifestResult.Ready("remux", spec.startSec.toDouble())
        }

        override suspend fun stopHlsSession(media: PlaybackMediaRef, sessionUuid: String) {
            stops += media to sessionUuid
        }

        override fun hlsPlaylistUrl(spec: HlsSessionSpec): String =
            "https://server/api/movies/${spec.media.id}/hls/${spec.profileId}/playlist.m3u8"

        override fun subtitleUrl(media: PlaybackMediaRef, trackIndex: Int, startSec: Double): String =
            "https://server/api/movies/${media.id}/subtitles/$trackIndex/web.vtt"
    }

    private val movie = PlaybackMediaRef.Movie(7)
    private val firstUuid = "11111111-1111-4111-8111-111111111111"

    @Test
    fun `a ready manifest returns the session start and clears the status`() = runTest {
        val api = FakeHlsApi()
        api.queuedResults += HlsManifestResult.Ready("1080p_8mbps", 87.4)
        val controller = HlsSessionController(movie, api, backgroundScope, backgroundScope)
        val statuses = mutableListOf<String?>()

        val start = controller.start(
            "remux",
            audioTypeIndex = 1,
            audioProfile = null,
            startSec = 90,
        ) { statuses += it }

        assertEquals("1080p_8mbps", start.effectiveProfileId)
        assertEquals(87.4, start.actualStartSec, 0.0)
        assertEquals(90, api.fetchedSpecs.single().startSec)
        assertEquals(1, api.fetchedSpecs.single().audioTypeIndex)
        assertEquals(listOf<String?>(null), statuses)
    }

    @Test
    fun `the session uuid is reused across uninterrupted hls restarts`() = runTest {
        val api = FakeHlsApi()
        val controller =
            HlsSessionController(movie, api, backgroundScope, backgroundScope, sessionUuid = firstUuid)

        controller.start("remux", 0, null, 0)
        controller.start("1080p_8mbps", 1, null, 500)

        assertEquals(listOf(firstUuid), api.fetchedSpecs.map { it.sessionUuid }.distinct())
    }

    @Test
    fun `stop rotates the uuid before a later start`() = runTest {
        val api = FakeHlsApi()
        val controller = HlsSessionController(movie, api, backgroundScope, this, sessionUuid = firstUuid)
        controller.start("remux", 0, null, 0)

        controller.releaseAndStop()
        controller.start("1080p_8mbps", 0, null, 60)
        runCurrent()

        assertEquals(firstUuid, api.stops.single().second)
        assertTrue(api.fetchedSpecs.last().sessionUuid != firstUuid)
    }

    @Test
    fun `a delayed old stop cannot target a newly started generation`() = runTest {
        val api = FakeHlsApi()
        val stopEntered = CompletableDeferred<String>()
        val allowStop = CompletableDeferred<Unit>()
        api.fetchOverride = { spec -> HlsManifestResult.Ready(spec.profileId, spec.startSec.toDouble()) }
        val delayingApi = object : HlsSessionApi by api {
            override suspend fun stopHlsSession(media: PlaybackMediaRef, sessionUuid: String) {
                stopEntered.complete(sessionUuid)
                allowStop.await()
                api.stopHlsSession(media, sessionUuid)
            }
        }
        val controller = HlsSessionController(
            movie,
            delayingApi,
            backgroundScope,
            backgroundScope,
            sessionUuid = firstUuid,
        )
        controller.start("remux", 0, null, 0)

        controller.releaseAndStop()
        controller.start("1080p_8mbps", 0, null, 60)
        assertEquals(firstUuid, stopEntered.await())
        assertTrue(api.fetchedSpecs.last().sessionUuid != firstUuid)

        allowStop.complete(Unit)
        runCurrent()
        assertEquals(listOf(movie to firstUuid), api.stops)
    }

    @Test
    fun `a start invalidated while its manifest is in flight cannot publish`() = runTest {
        val api = FakeHlsApi()
        val fetchStarted = CompletableDeferred<Unit>()
        val manifest = CompletableDeferred<HlsManifestResult>()
        api.fetchOverride = {
            fetchStarted.complete(Unit)
            manifest.await()
        }
        val controller = HlsSessionController(movie, api, backgroundScope, this)
        val result = CompletableDeferred<Result<HlsSessionStart>>()
        backgroundScope.launch {
            result.complete(runCatching { controller.start("remux", 0, null, 0) })
        }
        fetchStarted.await()

        controller.releaseAndStop()
        manifest.complete(HlsManifestResult.Ready("remux", 0.0))
        runCurrent()

        assertTrue(result.await().exceptionOrNull() is CancellationException)
        // Nothing was published: a keepalive started now has no spec to refresh.
        controller.startKeepalive { }
        advanceTimeBy(HLS_KEEPALIVE_INTERVAL_MS + 1)
        assertEquals(1, api.fetchedSpecs.size)
    }

    @Test
    fun `busy responses narrate the wait, honor Retry-After, then succeed`() = runTest {
        val api = FakeHlsApi()
        api.queuedResults += HlsManifestResult.Busy(retryAfterSec = 3)
        api.queuedResults += HlsManifestResult.Ready("remux", 0.0)
        val controller = HlsSessionController(movie, api, backgroundScope, backgroundScope)
        val statuses = mutableListOf<String?>()
        val before = currentTime

        controller.start("remux", 0, null, 0) { statuses += it }

        assertEquals(3_000L, currentTime - before)
        assertEquals(2, api.fetchedSpecs.size)
        assertTrue(statuses.first()!!.contains("Waiting"))
        assertNull(statuses.last())
    }

    @Test
    fun `exhausted capacity retries throw a readable failure`() = runTest {
        val api = FakeHlsApi()
        repeat(7) { api.queuedResults += HlsManifestResult.Busy(retryAfterSec = null) }
        val controller = HlsSessionController(movie, api, backgroundScope, backgroundScope)

        try {
            controller.start("remux", 0, null, 0)
            fail("expected HlsStartException")
        } catch (expected: HlsStartException) {
            assertTrue(expected.message!!.contains("busy"))
        }
        assertEquals(7, api.fetchedSpecs.size)
    }

    @Test
    fun `an unanswering server gives up on the wall-clock budget, not the attempt count`() =
        runTest {
            val api = FakeHlsApi()
            // Never Busy, never Ready: every attempt burns a manifest request timeout, which is
            // exactly the shape the attempt budget alone cannot bound.
            api.fetchOverride = {
                delay(45_000)
                HlsManifestResult.Busy(retryAfterSec = null)
            }
            val controller = HlsSessionController(movie, api, backgroundScope, backgroundScope)
            val before = currentTime

            try {
                controller.start("remux", 0, null, 0)
                fail("expected HlsStartException")
            } catch (expected: HlsStartException) {
                assertTrue(expected.message!!.contains("busy"))
            }

            assertEquals(HLS_START_TOTAL_BUDGET_MS, currentTime - before)
            // Far short of the six attempts the capacity budget alone would have allowed.
            assertTrue(api.fetchedSpecs.size < HLS_CAPACITY_RETRY_MAX_ATTEMPTS)
        }

    @Test
    fun `the budget does not cut short a session that starts in time`() = runTest {
        val api = FakeHlsApi()
        api.queuedResults += HlsManifestResult.Busy(retryAfterSec = 3)
        api.queuedResults += HlsManifestResult.Ready("remux", 12.0)
        val controller = HlsSessionController(movie, api, backgroundScope, backgroundScope)

        val start = controller.start("remux", 0, null, 0)

        assertEquals(12.0, start.actualStartSec, 0.0)
    }

    @Test
    fun `a throwing keepalive tick neither ends the loop nor escapes the scope`() = runTest {
        val api = FakeHlsApi()
        val controller = HlsSessionController(movie, api, backgroundScope, backgroundScope)
        controller.start("remux", 2, null, 60)
        var lost = 0
        controller.startKeepalive { lost++ }

        // The repository deliberately rethrows programming errors; a tick must absorb them.
        api.fetchOverride = { error("server address requested before setup completed") }
        advanceTimeBy(HLS_KEEPALIVE_INTERVAL_MS + 1)
        assertEquals(2, api.fetchedSpecs.size)

        api.fetchOverride = null
        api.queuedResults += HlsManifestResult.Lost
        advanceTimeBy(HLS_KEEPALIVE_INTERVAL_MS + 1)

        assertEquals(3, api.fetchedSpecs.size)
        assertEquals(1, lost)
    }

    @Test
    fun `a lost session bumps reload before retrying`() = runTest {
        val api = FakeHlsApi()
        api.queuedResults += HlsManifestResult.Lost
        api.queuedResults += HlsManifestResult.Ready("remux", 0.0)
        val controller = HlsSessionController(movie, api, backgroundScope, backgroundScope)

        controller.start("remux", 0, null, 0)

        assertEquals(0, api.fetchedSpecs.first().reload)
        assertEquals(1, api.fetchedSpecs.last().reload)
    }

    @Test
    fun `an audio profile rides every retry, the started spec, and the keepalive`() = runTest {
        val api = FakeHlsApi()
        api.queuedResults += HlsManifestResult.Busy(retryAfterSec = 1)
        api.queuedResults += HlsManifestResult.Lost
        api.queuedResults += HlsManifestResult.Ready("remux", 0.0)
        val controller = HlsSessionController(movie, api, backgroundScope, backgroundScope)

        controller.start("remux", 0, HlsAudioProfile.DolbyDigitalPlus, 0)
        controller.startKeepalive { }
        advanceTimeBy(HLS_KEEPALIVE_INTERVAL_MS + 1)

        assertEquals(4, api.fetchedSpecs.size)
        assertTrue(api.fetchedSpecs.all { it.audioProfile == HlsAudioProfile.DolbyDigitalPlus })
        // The lost retry still bumped reload alongside the profile.
        assertEquals(1, api.fetchedSpecs.last().reload)
    }

    @Test
    fun `a rejected conversion falls back to legacy audio once, narrating the change`() = runTest {
        val api = FakeHlsApi()
        api.queuedResults += HlsManifestResult.Failed(
            "The server refused the stream (HTTP 400).",
            rejectedRequest = true,
        )
        api.queuedResults += HlsManifestResult.Ready("remux", 0.0)
        val controller = HlsSessionController(movie, api, backgroundScope, backgroundScope)
        val statuses = mutableListOf<String?>()

        controller.start("remux", 0, HlsAudioProfile.DolbyDigital, 0) { statuses += it }

        assertEquals(HlsAudioProfile.DolbyDigital, api.fetchedSpecs.first().audioProfile)
        assertNull(api.fetchedSpecs.last().audioProfile)
        assertEquals(listOf(HLS_AUDIO_CONVERSION_UNAVAILABLE_MESSAGE, null), statuses)
    }

    /** The fallback is one-shot: a second rejection is a genuine failure, not a loop. */
    @Test
    fun `a rejection after the legacy fallback throws`() = runTest {
        val api = FakeHlsApi()
        repeat(2) {
            api.queuedResults += HlsManifestResult.Failed(
                "The server refused the stream (HTTP 400).",
                rejectedRequest = true,
            )
        }
        val controller = HlsSessionController(movie, api, backgroundScope, backgroundScope)

        try {
            controller.start("remux", 0, HlsAudioProfile.DolbyDigitalPlus, 0)
            fail("expected HlsStartException")
        } catch (expected: HlsStartException) {
            assertTrue(expected.message!!.contains("400"))
        }
        assertEquals(2, api.fetchedSpecs.size)
    }

    @Test
    fun `a rejected request without a profile throws as before`() = runTest {
        val api = FakeHlsApi()
        api.queuedResults += HlsManifestResult.Failed(
            "The server refused the stream (HTTP 400).",
            rejectedRequest = true,
        )
        val controller = HlsSessionController(movie, api, backgroundScope, backgroundScope)

        try {
            controller.start("remux", 0, null, 0)
            fail("expected HlsStartException")
        } catch (expected: HlsStartException) {
            assertTrue(expected.message!!.contains("400"))
        }
        assertEquals(1, api.fetchedSpecs.size)
    }

    @Test
    fun `a failed manifest throws immediately, carrying unauthorized`() = runTest {
        val api = FakeHlsApi()
        api.queuedResults += HlsManifestResult.Failed("Your session is no longer valid.", unauthorized = true)
        val controller = HlsSessionController(movie, api, backgroundScope, backgroundScope)

        try {
            controller.start("remux", 0, null, 0)
            fail("expected HlsStartException")
        } catch (expected: HlsStartException) {
            assertTrue(expected.unauthorized)
        }
    }

    @Test
    fun `the keepalive refetches the started spec and reports a lost session`() = runTest {
        val api = FakeHlsApi()
        val controller = HlsSessionController(movie, api, backgroundScope, backgroundScope)
        controller.start("remux", 2, null, 60)
        var lost = 0
        controller.startKeepalive { lost++ }

        advanceTimeBy(HLS_KEEPALIVE_INTERVAL_MS + 1)
        assertEquals(2, api.fetchedSpecs.size)
        assertEquals(api.fetchedSpecs.first(), api.fetchedSpecs.last())
        assertEquals(0, lost)

        api.queuedResults += HlsManifestResult.Lost
        advanceTimeBy(HLS_KEEPALIVE_INTERVAL_MS + 1)
        assertEquals(1, lost)

        controller.cancelKeepalive()
        advanceTimeBy(HLS_KEEPALIVE_INTERVAL_MS + 1)
        assertEquals(3, api.fetchedSpecs.size)
    }

    @Test
    fun `the keepalive shrugs off transient failures and keeps ticking`() = runTest {
        val api = FakeHlsApi()
        val controller = HlsSessionController(movie, api, backgroundScope, backgroundScope)
        controller.start("remux", 2, null, 60)
        var lost = 0
        controller.startKeepalive { lost++ }

        api.queuedResults += HlsManifestResult.Busy(retryAfterSec = 9)
        advanceTimeBy(HLS_KEEPALIVE_INTERVAL_MS + 1)
        api.queuedResults += HlsManifestResult.Failed("flaky proxy")
        advanceTimeBy(HLS_KEEPALIVE_INTERVAL_MS + 1)

        assertEquals(0, lost)
        // Both failed ticks fetched, and the loop is still alive for the next interval.
        assertEquals(3, api.fetchedSpecs.size)
        advanceTimeBy(HLS_KEEPALIVE_INTERVAL_MS + 1)
        assertEquals(4, api.fetchedSpecs.size)
        assertEquals(0, lost)
    }

    @Test
    fun `release stops the started session on the surviving scope`() = runTest {
        val api = FakeHlsApi()
        val controller = HlsSessionController(movie, api, backgroundScope, this, sessionUuid = firstUuid)
        controller.start("remux", 0, null, 0)

        controller.releaseAndStop()
        runCurrent()

        assertEquals(listOf(movie to firstUuid), api.stops)
    }

    @Test
    fun `release stops a startup canceled while its first manifest is in flight`() = runTest {
        val api = FakeHlsApi()
        val fetchStarted = CompletableDeferred<Unit>()
        val manifest = CompletableDeferred<HlsManifestResult>()
        api.fetchOverride = {
            fetchStarted.complete(Unit)
            manifest.await()
        }
        val controller = HlsSessionController(movie, api, backgroundScope, this, sessionUuid = firstUuid)
        val startup = backgroundScope.launch { controller.start("remux", 0, null, 0) }
        fetchStarted.await()

        startup.cancelAndJoin()
        controller.releaseAndStop()
        runCurrent()

        assertEquals(listOf(movie to firstUuid), api.stops)
    }

    @Test
    fun `release stops a session whose startup failed after issuing a manifest`() = runTest {
        val api = FakeHlsApi()
        api.queuedResults += HlsManifestResult.Failed("No stream.")
        val controller = HlsSessionController(movie, api, backgroundScope, this, sessionUuid = firstUuid)
        runCatching { controller.start("remux", 0, null, 0) }

        controller.releaseAndStop()
        runCurrent()

        assertEquals(listOf(movie to firstUuid), api.stops)
    }

    @Test
    fun `release before any session started sends no stop`() = runTest {
        val api = FakeHlsApi()
        val controller = HlsSessionController(movie, api, backgroundScope, this)

        controller.releaseAndStop()
        runCurrent()

        assertTrue(api.stops.isEmpty())
    }

    @Test
    fun `a reserved generation rotates even before its manifest is issued`() = runTest {
        val api = FakeHlsApi()
        val controller = HlsSessionController(movie, api, backgroundScope, this, sessionUuid = firstUuid)
        controller.reserveGeneration()

        controller.releaseAndStop()
        runCurrent()
        controller.start("remux", 0, null, 0)

        assertTrue(api.fetchedSpecs.single().sessionUuid != firstUuid)
        assertTrue(api.stops.isEmpty())
    }

    @Test
    fun `repeated stop is idempotent and keepalive stays canceled`() = runTest {
        val api = FakeHlsApi()
        val controller = HlsSessionController(movie, api, backgroundScope, this)
        controller.start("remux", 0, null, 0)
        controller.startKeepalive { fail("keepalive must be canceled") }

        controller.releaseAndStop()
        controller.releaseAndStop()
        runCurrent()
        advanceTimeBy(HLS_KEEPALIVE_INTERVAL_MS + 1)

        assertEquals(1, api.stops.size)
        assertEquals(1, api.fetchedSpecs.size)
    }

    @Test
    fun `an episode's session is keyed and stopped by the episode ref`() = runTest {
        val api = FakeHlsApi()
        val episode = PlaybackMediaRef.Episode(900)
        val controller = HlsSessionController(episode, api, backgroundScope, this, sessionUuid = firstUuid)

        controller.start("remux", 0, null, 0)
        controller.releaseAndStop()
        runCurrent()

        assertEquals(episode, api.fetchedSpecs.single().media)
        assertEquals(listOf(episode to firstUuid), api.stops)
    }
}
