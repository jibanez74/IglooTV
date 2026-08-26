package com.igloo.blindpenguincoder.playback.hls

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
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
        val stops = mutableListOf<Pair<Long, String>>()
        var fetchOverride: (suspend (HlsSessionSpec) -> HlsManifestResult)? = null

        override suspend fun fetchHlsManifest(spec: HlsSessionSpec): HlsManifestResult {
            fetchedSpecs += spec
            fetchOverride?.let { return it(spec) }
            return queuedResults.removeFirstOrNull()
                ?: HlsManifestResult.Ready("remux", spec.startSec.toDouble())
        }

        override suspend fun stopHlsSession(movieId: Long, sessionUuid: String) {
            stops += movieId to sessionUuid
        }

        override fun hlsPlaylistUrl(spec: HlsSessionSpec): String =
            "https://server/api/movies/${spec.movieId}/hls/${spec.profileId}/playlist.m3u8"

        override fun movieSubtitleUrl(movieId: Long, trackIndex: Int, startSec: Double): String =
            "https://server/api/movies/$movieId/subtitles/$trackIndex/web.vtt"
    }

    @Test
    fun `a ready manifest returns the session start and clears the status`() = runTest {
        val api = FakeHlsApi()
        api.queuedResults += HlsManifestResult.Ready("1080p_8mbps", 87.4)
        val controller = HlsSessionController(7, api, backgroundScope, backgroundScope)
        val statuses = mutableListOf<String?>()

        val start = controller.start("remux", audioTypeIndex = 1, startSec = 90) { statuses += it }

        assertEquals("1080p_8mbps", start.effectiveProfileId)
        assertEquals(87.4, start.actualStartSec, 0.0)
        assertEquals(90, start.spec.startSec)
        assertEquals(1, start.spec.audioTypeIndex)
        assertEquals(listOf<String?>(null), statuses)
    }

    @Test
    fun `the session uuid is reused across uninterrupted hls restarts`() = runTest {
        val api = FakeHlsApi()
        val controller = HlsSessionController(7, api, backgroundScope, backgroundScope)

        controller.start("remux", 0, 0)
        controller.start("1080p_8mbps", 1, 500)

        assertEquals(1, api.fetchedSpecs.map { it.sessionUuid }.distinct().size)
        assertEquals(controller.sessionUuid, api.fetchedSpecs.first().sessionUuid)
    }

    @Test
    fun `stop rotates the uuid before a later start`() = runTest {
        val api = FakeHlsApi()
        val controller = HlsSessionController(7, api, backgroundScope, this)
        controller.start("remux", 0, 0)
        val stoppedUuid = controller.sessionUuid

        controller.releaseAndStop()
        val nextUuid = controller.sessionUuid
        controller.start("1080p_8mbps", 0, 60)
        runCurrent()

        assertTrue(stoppedUuid != nextUuid)
        assertEquals(stoppedUuid, api.stops.single().second)
        assertEquals(nextUuid, api.fetchedSpecs.last().sessionUuid)
    }

    @Test
    fun `a delayed old stop cannot target a newly started generation`() = runTest {
        val api = FakeHlsApi()
        val stopEntered = CompletableDeferred<String>()
        val allowStop = CompletableDeferred<Unit>()
        api.fetchOverride = { spec -> HlsManifestResult.Ready(spec.profileId, spec.startSec.toDouble()) }
        val delayingApi = object : HlsSessionApi by api {
            override suspend fun stopHlsSession(movieId: Long, sessionUuid: String) {
                stopEntered.complete(sessionUuid)
                allowStop.await()
                api.stopHlsSession(movieId, sessionUuid)
            }
        }
        val controller = HlsSessionController(7, delayingApi, backgroundScope, backgroundScope)
        controller.start("remux", 0, 0)
        val oldUuid = controller.sessionUuid

        controller.releaseAndStop()
        val newUuid = controller.sessionUuid
        controller.start("1080p_8mbps", 0, 60)
        assertEquals(oldUuid, stopEntered.await())
        assertTrue(oldUuid != newUuid)
        assertEquals(newUuid, api.fetchedSpecs.last().sessionUuid)

        allowStop.complete(Unit)
        runCurrent()
        assertEquals(listOf(7L to oldUuid), api.stops)
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
        val controller = HlsSessionController(7, api, backgroundScope, this)
        val result = CompletableDeferred<Result<HlsSessionStart>>()
        backgroundScope.launch {
            result.complete(runCatching { controller.start("remux", 0, 0) })
        }
        fetchStarted.await()

        controller.releaseAndStop()
        manifest.complete(HlsManifestResult.Ready("remux", 0.0))
        runCurrent()

        assertTrue(result.await().exceptionOrNull() is CancellationException)
        assertNull(controller.currentSpec())
    }

    @Test
    fun `busy responses narrate the wait, honor Retry-After, then succeed`() = runTest {
        val api = FakeHlsApi()
        api.queuedResults += HlsManifestResult.Busy(retryAfterSec = 3)
        api.queuedResults += HlsManifestResult.Ready("remux", 0.0)
        val controller = HlsSessionController(7, api, backgroundScope, backgroundScope)
        val statuses = mutableListOf<String?>()
        val before = currentTime

        controller.start("remux", 0, 0) { statuses += it }

        assertEquals(3_000L, currentTime - before)
        assertEquals(2, api.fetchedSpecs.size)
        assertTrue(statuses.first()!!.contains("Waiting"))
        assertNull(statuses.last())
    }

    @Test
    fun `exhausted capacity retries throw a readable failure`() = runTest {
        val api = FakeHlsApi()
        repeat(7) { api.queuedResults += HlsManifestResult.Busy(retryAfterSec = null) }
        val controller = HlsSessionController(7, api, backgroundScope, backgroundScope)

        try {
            controller.start("remux", 0, 0)
            fail("expected HlsStartException")
        } catch (expected: HlsStartException) {
            assertTrue(expected.message!!.contains("busy"))
        }
        assertEquals(7, api.fetchedSpecs.size)
    }

    @Test
    fun `a lost session bumps reload before retrying`() = runTest {
        val api = FakeHlsApi()
        api.queuedResults += HlsManifestResult.Lost
        api.queuedResults += HlsManifestResult.Ready("remux", 0.0)
        val controller = HlsSessionController(7, api, backgroundScope, backgroundScope)

        controller.start("remux", 0, 0)

        assertEquals(0, api.fetchedSpecs.first().reload)
        assertEquals(1, api.fetchedSpecs.last().reload)
    }

    @Test
    fun `a failed manifest throws immediately, carrying unauthorized`() = runTest {
        val api = FakeHlsApi()
        api.queuedResults += HlsManifestResult.Failed("Your session is no longer valid.", unauthorized = true)
        val controller = HlsSessionController(7, api, backgroundScope, backgroundScope)

        try {
            controller.start("remux", 0, 0)
            fail("expected HlsStartException")
        } catch (expected: HlsStartException) {
            assertTrue(expected.unauthorized)
        }
    }

    @Test
    fun `the keepalive refetches the started spec and reports a lost session`() = runTest {
        val api = FakeHlsApi()
        val controller = HlsSessionController(7, api, backgroundScope, backgroundScope)
        controller.start("remux", 2, 60)
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
        val controller = HlsSessionController(7, api, backgroundScope, backgroundScope)
        controller.start("remux", 2, 60)
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
        val controller = HlsSessionController(7, api, backgroundScope, this)
        controller.start("remux", 0, 0)

        val stoppedUuid = controller.sessionUuid
        controller.releaseAndStop()
        runCurrent()

        assertEquals(listOf(7L to stoppedUuid), api.stops)
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
        val controller = HlsSessionController(7, api, backgroundScope, this)
        val startup = backgroundScope.launch { controller.start("remux", 0, 0) }
        fetchStarted.await()

        startup.cancelAndJoin()
        val stoppedUuid = controller.sessionUuid
        controller.releaseAndStop()
        runCurrent()

        assertEquals(listOf(7L to stoppedUuid), api.stops)
    }

    @Test
    fun `release stops a session whose startup failed after issuing a manifest`() = runTest {
        val api = FakeHlsApi()
        api.queuedResults += HlsManifestResult.Failed("No stream.")
        val controller = HlsSessionController(7, api, backgroundScope, this)
        runCatching { controller.start("remux", 0, 0) }

        val stoppedUuid = controller.sessionUuid
        controller.releaseAndStop()
        runCurrent()

        assertEquals(listOf(7L to stoppedUuid), api.stops)
    }

    @Test
    fun `release before any session started sends no stop`() = runTest {
        val api = FakeHlsApi()
        val controller = HlsSessionController(7, api, backgroundScope, this)

        controller.releaseAndStop()
        runCurrent()

        assertTrue(api.stops.isEmpty())
    }

    @Test
    fun `a reserved generation rotates even before its manifest is issued`() = runTest {
        val api = FakeHlsApi()
        val controller = HlsSessionController(7, api, backgroundScope, this)
        val reservedUuid = controller.sessionUuid
        controller.reserveGeneration()

        controller.releaseAndStop()
        runCurrent()

        assertTrue(reservedUuid != controller.sessionUuid)
        assertTrue(api.stops.isEmpty())
    }

    @Test
    fun `repeated stop is idempotent and keepalive stays canceled`() = runTest {
        val api = FakeHlsApi()
        val controller = HlsSessionController(7, api, backgroundScope, this)
        controller.start("remux", 0, 0)
        controller.startKeepalive { fail("keepalive must be canceled") }

        controller.releaseAndStop()
        controller.releaseAndStop()
        runCurrent()
        advanceTimeBy(HLS_KEEPALIVE_INTERVAL_MS + 1)

        assertEquals(1, api.stops.size)
        assertEquals(1, api.fetchedSpecs.size)
    }
}
