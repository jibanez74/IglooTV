package com.igloo.blindpenguincoder.playback.hls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertTrue(shouldRebaseHlsSeek(targetSec = 99.0, actualStartSec = 100.0, currentSec = 200.0))
    }

    @Test
    fun `the actual start itself is inside the session`() {
        assertFalse(shouldRebaseHlsSeek(targetSec = 100.0, actualStartSec = 100.0, currentSec = 200.0))
    }

    @Test
    fun `a seek within two minutes ahead rides the session`() {
        assertFalse(shouldRebaseHlsSeek(targetSec = 320.0, actualStartSec = 100.0, currentSec = 200.0))
    }

    @Test
    fun `the forward threshold is exclusive at exactly two minutes`() {
        assertFalse(shouldRebaseHlsSeek(targetSec = 200.0 + 120.0, actualStartSec = 0.0, currentSec = 200.0))
        assertTrue(shouldRebaseHlsSeek(targetSec = 200.0 + 120.1, actualStartSec = 0.0, currentSec = 200.0))
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
        assertTrue(HLS_START_TOTAL_BUDGET_MS < HLS_CAPACITY_RETRY_MAX_ATTEMPTS * 50_000L)
    }

    // --- Media3 load-error delays ---

    @Test
    fun `503 honors Retry-After and outlasts the Media3 default delay`() {
        // Media3's own delay is ignored for a 503: "not encoded yet" is worth the server's word.
        assertEquals(
            9_000L,
            hlsLoadRetryDelayMs(503, 9, errorCount = 1, defaultRetryCount = 3, defaultRetryDelayMs = 0L),
        )
        assertEquals(
            5_000L,
            hlsLoadRetryDelayMs(503, null, errorCount = 6, defaultRetryCount = 3, defaultRetryDelayMs = 5_000L),
        )
        assertNull(
            hlsLoadRetryDelayMs(503, null, errorCount = 7, defaultRetryCount = 3, defaultRetryDelayMs = 5_000L),
        )
    }

    @Test
    fun `503 gets six retries while non-503 stops at the Media3 default threshold`() {
        assertEquals(
            9_000L,
            hlsLoadRetryDelayMs(503, 9, 6, defaultRetryCount = 3, defaultRetryDelayMs = 5_000L),
        )
        assertNull(
            hlsLoadRetryDelayMs(503, 9, 7, defaultRetryCount = 3, defaultRetryDelayMs = 5_000L),
        )

        listOf<Int?>(401, 404, 500, null).forEach { responseCode ->
            assertEquals(
                2_000L,
                hlsLoadRetryDelayMs(
                    responseCode,
                    null,
                    errorCount = 3,
                    defaultRetryCount = 3,
                    defaultRetryDelayMs = 2_000L,
                ),
            )
            assertNull(
                hlsLoadRetryDelayMs(
                    responseCode,
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
                retryAfterSec = null,
                errorCount = 1,
                defaultRetryCount = 3,
                defaultRetryDelayMs = null,
            ),
        )
    }

    // --- mid-play lost-session recovery ---

    @Test
    fun `only a segment 404 recovers in place`() {
        assertTrue(shouldRecoverLostHlsSession(responseCode = 404, recoveries = 0))
        assertFalse(shouldRecoverLostHlsSession(responseCode = 503, recoveries = 0))
        assertFalse(shouldRecoverLostHlsSession(responseCode = 500, recoveries = 0))
        assertFalse(shouldRecoverLostHlsSession(responseCode = null, recoveries = 0))
    }

    @Test
    fun `recovery stops once the budget is spent`() {
        assertTrue(shouldRecoverLostHlsSession(404, recoveries = HLS_SESSION_LOST_MAX_ATTEMPTS - 1))
        assertFalse(shouldRecoverLostHlsSession(404, recoveries = HLS_SESSION_LOST_MAX_ATTEMPTS))
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
}
