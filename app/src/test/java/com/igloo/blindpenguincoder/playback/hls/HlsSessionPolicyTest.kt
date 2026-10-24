package com.igloo.blindpenguincoder.playback.hls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SEGMENT = "/api/movies/7/hls/1080p_8mbps/segment_42.m4s"
private const val PLAYLIST = "/api/movies/7/hls/remux/playlist.m3u8"

class HlsSessionPolicyTest {

    // --- resume rewind ---

    @Test
    fun `resume rewinds ten seconds and floors to whole seconds`() {
        assertEquals(1790, hlsResumeStartSec(1800.7))
        assertEquals(0, hlsResumeStartSec(0.0))
        assertEquals(0, hlsResumeStartSec(9.9))
        assertEquals(0, hlsResumeStartSec(10.0))
        assertEquals(1, hlsResumeStartSec(11.5))
    }

    // --- seek rebase rule ---

    @Test
    fun `seeking before the actual start rebases`() {
        assertTrue(shouldRebaseHlsSeek(targetSec = 99.0, actualStartSec = 100.0, settledSec = 200.0))
    }

    @Test
    fun `the actual start itself is inside the session`() {
        assertFalse(shouldRebaseHlsSeek(targetSec = 100.0, actualStartSec = 100.0, settledSec = 200.0))
    }

    @Test
    fun `a seek within two minutes ahead rides the session`() {
        assertFalse(shouldRebaseHlsSeek(targetSec = 320.0, actualStartSec = 100.0, settledSec = 200.0))
    }

    @Test
    fun `the forward threshold is exclusive at exactly two minutes`() {
        assertFalse(shouldRebaseHlsSeek(targetSec = 200.0 + 120.0, actualStartSec = 0.0, settledSec = 200.0))
        assertTrue(shouldRebaseHlsSeek(targetSec = 200.0 + 120.1, actualStartSec = 0.0, settledSec = 200.0))
    }

    @Test
    fun `a seek the pending session covers only moves its target`() {
        assertFalse(shouldRebasePendingHlsSeek(targetSec = 1_000.0, pendingStartSec = 1_000))
        assertFalse(shouldRebasePendingHlsSeek(targetSec = 1_030.0, pendingStartSec = 1_000))
        assertFalse(shouldRebasePendingHlsSeek(targetSec = 1_120.0, pendingStartSec = 1_000))
    }

    @Test
    fun `the pending window is measured from the session's start, not the moving target`() {
        // A run of retargets each within two minutes of the last must still stop at the window.
        assertTrue(shouldRebasePendingHlsSeek(targetSec = 1_120.1, pendingStartSec = 1_000))
        assertTrue(shouldRebasePendingHlsSeek(targetSec = 999.0, pendingStartSec = 1_000))
    }

    // --- capacity retries ---

    @Test
    fun `capacity retries honor Retry-After and default to five seconds`() {
        assertEquals(7_000L, capacityRetryDelayMs(attempt = 1, retryAfterSec = 7))
        assertEquals(5_000L, capacityRetryDelayMs(attempt = 1, retryAfterSec = null))
        assertEquals(5_000L, capacityRetryDelayMs(attempt = 1, retryAfterSec = 0))
    }

    @Test
    fun `capacity retries stop after six attempts`() {
        assertEquals(5_000L, capacityRetryDelayMs(attempt = 6, retryAfterSec = null))
        assertNull(capacityRetryDelayMs(attempt = 7, retryAfterSec = null))
    }

    // --- session-lost retries ---

    @Test
    fun `lost sessions retry three times with a two second spacing`() {
        assertEquals(2_000L, sessionLostRetryDelayMs(attempt = 1))
        assertEquals(2_000L, sessionLostRetryDelayMs(attempt = 3))
        assertNull(sessionLostRetryDelayMs(attempt = 4))
    }

    // --- start budget ---

    @Test
    fun `the start budget is shorter than the attempt budgets it bounds`() {
        // Six capacity attempts alone permit six manifest request timeouts back to back; the
        // wall-clock ceiling is what keeps that off a loading screen the viewer is watching.
        val attemptsOnly = HLS_CAPACITY_RETRY_MAX_ATTEMPTS * 5_000L
        assertTrue(HLS_START_TOTAL_BUDGET_MS > attemptsOnly)
        // Each attempt can spend the 90s manifest timeout plus the 5s fallback retry delay.
        assertTrue(HLS_START_TOTAL_BUDGET_MS < HLS_CAPACITY_RETRY_MAX_ATTEMPTS * 95_000L)
    }

    @Test
    fun `the start budget outlasts the server's worst cold start and a capacity retry`() {
        // A remux preflight, a transcode-permit wait, and the first-output wait: about 75s.
        val worstColdStartMs = 75_000L
        // The retry finds the preflight verdict stored, so it waits on the last two alone.
        val retryAfterBusyMs = 5_000L + 45_000L
        assertTrue(HLS_START_TOTAL_BUDGET_MS > worstColdStartMs + retryAfterBusyMs)
    }

    // --- Media3 load-error delays ---

    @Test
    fun `503 honors Retry-After and outlasts the Media3 default delay`() {
        // Media3's own delay is ignored for a 503: "not encoded yet" is worth the server's word.
        assertEquals(
            9_000L,
            hlsLoadRetryDelayMs(503, SEGMENT, 9, errorCount = 1, defaultRetryCount = 3, defaultRetryDelayMs = 0L),
        )
        assertEquals(
            5_000L,
            hlsLoadRetryDelayMs(503, PLAYLIST, null, errorCount = 6, defaultRetryCount = 3, defaultRetryDelayMs = 5_000L),
        )
        assertNull(
            hlsLoadRetryDelayMs(503, PLAYLIST, null, errorCount = 7, defaultRetryCount = 3, defaultRetryDelayMs = 5_000L),
        )
    }

    @Test
    fun `a playlist 503 gets the capacity budget and a segment 503 three fresh attempts`() {
        // Each segment attempt is a full 120s long-poll on the server, so the capacity budget
        // held a viewer about 14 minutes on one segment the encoder never reached.
        assertEquals(
            9_000L,
            hlsLoadRetryDelayMs(503, PLAYLIST, 9, 6, defaultRetryCount = 3, defaultRetryDelayMs = 5_000L),
        )
        assertNull(
            hlsLoadRetryDelayMs(503, PLAYLIST, 9, 7, defaultRetryCount = 3, defaultRetryDelayMs = 5_000L),
        )
        assertEquals(
            1_000L,
            hlsLoadRetryDelayMs(
                503,
                SEGMENT,
                1,
                HLS_SEGMENT_NOT_READY_MAX_RETRIES,
                defaultRetryCount = 3,
                defaultRetryDelayMs = 5_000L,
            ),
        )
        assertNull(
            hlsLoadRetryDelayMs(
                503,
                SEGMENT,
                1,
                HLS_SEGMENT_NOT_READY_MAX_RETRIES + 1,
                defaultRetryCount = 3,
                defaultRetryDelayMs = 5_000L,
            ),
        )
    }

    @Test
    fun `a segment 500 goes straight to the lost-session recovery`() {
        // FFmpeg died: every quick retry gets the same 500, and only recreating the session helps.
        assertNull(
            hlsLoadRetryDelayMs(500, SEGMENT, null, errorCount = 1, defaultRetryCount = 3, defaultRetryDelayMs = 1_000L),
        )
        // Anything else keeps Media3's own retries.
        assertEquals(
            1_000L,
            hlsLoadRetryDelayMs(500, PLAYLIST, null, errorCount = 1, defaultRetryCount = 3, defaultRetryDelayMs = 1_000L),
        )
    }

    @Test
    fun `non-503 failures stop at the Media3 default threshold`() {
        listOf<Int?>(401, 404, 502, null).forEach { responseCode ->
            assertEquals(
                2_000L,
                hlsLoadRetryDelayMs(
                    responseCode,
                    SEGMENT,
                    null,
                    errorCount = 3,
                    defaultRetryCount = 3,
                    defaultRetryDelayMs = 2_000L,
                ),
            )
            assertNull(
                hlsLoadRetryDelayMs(
                    responseCode,
                    SEGMENT,
                    null,
                    errorCount = 4,
                    defaultRetryCount = 3,
                    defaultRetryDelayMs = 3_000L,
                ),
            )
        }
    }

    @Test
    fun `Media3 fail-fast errors stay immediate`() {
        assertNull(
            hlsLoadRetryDelayMs(
                responseCode = null,
                requestPath = null,
                retryAfterSec = null,
                errorCount = 1,
                defaultRetryCount = 3,
                defaultRetryDelayMs = null,
            ),
        )
    }

    // --- recovery spacing ---

    @Test
    fun `mid-play recoveries stay two seconds apart`() {
        // The first of an incident runs at once.
        assertEquals(0L, sessionLostRecoveryDelayMs(lastRecoveryAtMs = 0L, nowMs = 1_000_000L))
        // A failure right after the last recovery waits out the rest of the spacing.
        assertEquals(1_800L, sessionLostRecoveryDelayMs(lastRecoveryAtMs = 10_000L, nowMs = 10_200L))
        assertEquals(0L, sessionLostRecoveryDelayMs(lastRecoveryAtMs = 10_000L, nowMs = 12_000L))
        // The last one is still waiting to run: the next waits the full spacing after it, no more.
        assertEquals(2_000L, sessionLostRecoveryDelayMs(lastRecoveryAtMs = 11_800L, nowMs = 10_300L))
    }

    // --- mid-play lost-session recovery ---

    @Test
    fun `only movie and episode HLS asset 404s recover in place`() {
        listOf(
            "/api/movies/7/hls/remux/playlist.m3u8",
            "/api/movies/7/hls/remux/init.mp4",
            "/api/movies/7/hls/remux/segment_42.m4s",
            "/api/shows/episodes/900/hls/remux/playlist.m3u8",
            "/api/shows/episodes/900/hls/remux/init.mp4",
            EPISODE_SEGMENT_PATH,
        ).forEach { path ->
            assertTrue(shouldRecoverLostHlsSession(404, path, recoveries = 0))
        }
        assertFalse(
            shouldRecoverLostHlsSession(
                404,
                "/api/movies/7/subtitles/0/web.vtt",
                recoveries = 0,
            ),
        )
        assertFalse(shouldRecoverLostHlsSession(404, "/api/watch-rooms/7/hls/playlist.m3u8", 0))
        assertFalse(
            shouldRecoverLostHlsSession(404, "/api/shows/episodes/900/subtitles/0/web.vtt", 0),
        )
        assertFalse(shouldRecoverLostHlsSession(503, MOVIE_SEGMENT_PATH, recoveries = 0))
        assertFalse(shouldRecoverLostHlsSession(null, MOVIE_SEGMENT_PATH, recoveries = 0))
    }

    @Test
    fun `a segment 500 recovers because the server replaces a failed session`() {
        assertTrue(shouldRecoverLostHlsSession(500, MOVIE_SEGMENT_PATH, recoveries = 0))
        assertTrue(shouldRecoverLostHlsSession(500, EPISODE_SEGMENT_PATH, recoveries = 0))
        assertFalse(shouldRecoverLostHlsSession(500, "/api/movies/7/hls/remux/playlist.m3u8", 0))
        assertFalse(shouldRecoverLostHlsSession(500, "/api/movies/7/hls/remux/init.mp4", 0))
        assertFalse(
            shouldRecoverLostHlsSession(500, MOVIE_SEGMENT_PATH, HLS_SESSION_LOST_MAX_ATTEMPTS),
        )
    }

    @Test
    fun `the recovery budget starts over only after a quiet incident window`() {
        val last = 1_000_000L
        assertEquals(2, sessionLostRecoveriesAt(2, last, last + HLS_SESSION_LOST_INCIDENT_WINDOW_MS - 1))
        assertEquals(0, sessionLostRecoveriesAt(2, last, last + HLS_SESSION_LOST_INCIDENT_WINDOW_MS))
        // Never recovered yet: the elapsed-realtime clock is far past the zero default.
        assertEquals(0, sessionLostRecoveriesAt(0, 0L, last))
    }

    // --- end-of-media 404 ---

    @Test
    fun `a segment 404 marked past-end is the end of the media`() {
        val pastEnd = mapOf<String?, List<String>>(
            null to listOf("HTTP/1.1 404 Not Found"),
            "x-igloo-segment" to listOf("past-end"),
        )
        assertTrue(isPastEndHlsSegment(404, MOVIE_SEGMENT_PATH, pastEnd))
        assertTrue(isPastEndHlsSegment(404, EPISODE_SEGMENT_PATH, pastEnd))
    }

    @Test
    fun `an unmarked 404 or a marker anywhere else is not the end`() {
        val pastEnd = mapOf<String?, List<String>>("X-Igloo-Segment" to listOf("past-end"))
        assertFalse(isPastEndHlsSegment(404, MOVIE_SEGMENT_PATH, emptyMap()))
        assertFalse(isPastEndHlsSegment(404, MOVIE_SEGMENT_PATH, null))
        assertFalse(
            isPastEndHlsSegment(
                404,
                MOVIE_SEGMENT_PATH,
                mapOf<String?, List<String>>("X-Igloo-Segment" to listOf("other")),
            ),
        )
        assertFalse(isPastEndHlsSegment(500, MOVIE_SEGMENT_PATH, pastEnd))
        assertFalse(isPastEndHlsSegment(404, "/api/movies/7/hls/remux/playlist.m3u8", pastEnd))
        assertFalse(isPastEndHlsSegment(404, null, pastEnd))
    }

    @Test
    fun `recovery stops once the budget is spent`() {
        assertTrue(
            shouldRecoverLostHlsSession(
                404,
                MOVIE_SEGMENT_PATH,
                recoveries = HLS_SESSION_LOST_MAX_ATTEMPTS - 1,
            ),
        )
        assertFalse(
            shouldRecoverLostHlsSession(
                404,
                MOVIE_SEGMENT_PATH,
                recoveries = HLS_SESSION_LOST_MAX_ATTEMPTS,
            ),
        )
    }

    // --- Retry-After header scan ---

    @Test
    fun `Retry-After is found case-insensitively past the null-key status line`() {
        val headers = mapOf<String?, List<String>>(
            null to listOf("HTTP/1.1 503 Service Unavailable"),
            "retry-after" to listOf("12"),
        )
        assertEquals(12, retryAfterSecondsFrom(headers))
    }

    @Test
    fun `a missing, empty, or non-numeric Retry-After is null`() {
        assertNull(retryAfterSecondsFrom(null))
        assertNull(retryAfterSecondsFrom(emptyMap()))
        assertNull(retryAfterSecondsFrom(mapOf<String?, List<String>>("Retry-After" to emptyList())))
        assertNull(
            retryAfterSecondsFrom(
                mapOf<String?, List<String>>("Retry-After" to listOf("Wed, 26 Aug 2026 07:28:00 GMT")),
            ),
        )
    }

    // --- constants the backend contract pins ---

    @Test
    fun `the segment read timeout outlasts the server's two minute long-poll`() {
        assertTrue(HLS_SEGMENT_READ_TIMEOUT_MS > 120_000)
    }

    @Test
    fun `the keepalive beats the server's five minute idle TTL`() {
        assertTrue(HLS_KEEPALIVE_INTERVAL_MS < 300_000L)
    }

    private companion object {
        const val MOVIE_SEGMENT_PATH = "/api/movies/7/hls/remux/segment_1.m4s"
        const val EPISODE_SEGMENT_PATH = "/api/shows/episodes/900/hls/remux/segment_1.m4s"
    }
}
