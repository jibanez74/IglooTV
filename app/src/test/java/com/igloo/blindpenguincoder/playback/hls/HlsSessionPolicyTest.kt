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

    // --- Media3 load-error delays ---

    @Test
    fun `only 503 gets the patient load retry`() {
        assertEquals(9_000L, hlsLoadRetryDelayMs(responseCode = 503, retryAfterSec = 9, errorCount = 1))
        assertEquals(5_000L, hlsLoadRetryDelayMs(responseCode = 503, retryAfterSec = null, errorCount = 6))
        assertNull(hlsLoadRetryDelayMs(responseCode = 503, retryAfterSec = null, errorCount = 7))
        assertNull(hlsLoadRetryDelayMs(responseCode = 404, retryAfterSec = null, errorCount = 1))
        assertNull(hlsLoadRetryDelayMs(responseCode = null, retryAfterSec = null, errorCount = 1))
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
