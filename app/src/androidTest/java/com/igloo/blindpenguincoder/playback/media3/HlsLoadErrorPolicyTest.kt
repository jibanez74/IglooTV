// The policy is built on Media3's unstable upstream surface, as the class under test is.
@file:androidx.annotation.OptIn(UnstableApi::class)

package com.igloo.blindpenguincoder.playback.media3

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.playback.hls.HLS_CAPACITY_RETRY_MAX_ATTEMPTS
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The adapter between Media3's [LoadErrorHandlingPolicy] callbacks and the pure rule in
 * `HlsSessionPolicy`. On the device because [DataSpec] and Media3's exception types are Android
 * classes; what it asserts is the wiring, not the rule.
 */
@RunWith(AndroidJUnit4::class)
class HlsLoadErrorPolicyTest {

    private val policy = HlsLoadErrorPolicy()

    private fun httpError(
        responseCode: Int,
        retryAfter: String? = null,
        path: String = "/api/movies/7/hls/remux/segment_1.m4s",
    ): HttpDataSource.InvalidResponseCodeException = HttpDataSource.InvalidResponseCodeException(
        responseCode,
        /* responseMessage= */ null,
        /* cause= */ null,
        retryAfter?.let { mapOf("Retry-After" to listOf(it)) } ?: emptyMap(),
        DataSpec(android.net.Uri.parse("https://server$path")),
        ByteArray(0),
    )

    private fun errorInfo(exception: IOException, errorCount: Int) = LoadErrorHandlingPolicy
        .LoadErrorInfo(
            LoadEventInfo(
                /* loadTaskId= */ 1L,
                DataSpec(android.net.Uri.parse("https://server/segment_1.m4s")),
                android.net.Uri.parse("https://server/segment_1.m4s"),
                emptyMap(),
                /* elapsedRealtimeMs= */ 0L,
                /* loadDurationMs= */ 0L,
                /* bytesLoaded= */ 0L,
            ),
            MediaLoadData(C.DATA_TYPE_MEDIA),
            exception,
            errorCount,
        )

    @Test
    fun a503WaitsTheServersRetryAfterRatherThanMedia3sOwnBackoff() {
        // 503 is the documented "segment not encoded yet / no capacity" answer, and the header
        // is the server telling us how long FFmpeg needs.
        assertEquals(9_000L, policy.getRetryDelayMsFor(errorInfo(httpError(503, "9"), errorCount = 1)))
    }

    @Test
    fun everyMovieAndEpisodeHlsAssetGetsPatient503Handling() {
        listOf(
            "/api/movies/7/hls/remux/playlist.m3u8",
            "/api/movies/7/hls/remux/init.mp4",
            "/api/movies/7/hls/remux/segment_1.m4s",
            "/api/shows/episodes/900/hls/remux/playlist.m3u8",
            "/api/shows/episodes/900/hls/remux/init.mp4",
            "/api/shows/episodes/900/hls/remux/segment_1.m4s",
        ).forEach { path ->
            assertEquals(
                5_000L,
                policy.getRetryDelayMsFor(errorInfo(httpError(503, path = path), errorCount = 1)),
            )
        }
    }

    @Test
    fun a503WithNoHeaderStillGetsThePatientDefaultThroughTheWholeBudget() {
        assertEquals(5_000L, policy.getRetryDelayMsFor(errorInfo(httpError(503), errorCount = 1)))
        assertEquals(
            5_000L,
            policy.getRetryDelayMsFor(
                errorInfo(httpError(503), errorCount = HLS_CAPACITY_RETRY_MAX_ATTEMPTS),
            ),
        )
        assertEquals(
            C.TIME_UNSET,
            policy.getRetryDelayMsFor(
                errorInfo(httpError(503), errorCount = HLS_CAPACITY_RETRY_MAX_ATTEMPTS + 1),
            ),
        )
    }

    @Test
    fun everyOtherFailureKeepsMedia3sOwnDelayAndItsOwnGiveUpPoint() {
        val default = DefaultLoadErrorHandlingPolicyProbe()
        listOf(404, 500).forEach { code ->
            val within = errorInfo(httpError(code), errorCount = 1)
            assertEquals(default.retryDelayMsFor(within), policy.getRetryDelayMsFor(within))
        }
        // Past Media3's own retry count the load fails, even though the budget for 503 is larger.
        assertEquals(
            C.TIME_UNSET,
            policy.getRetryDelayMsFor(errorInfo(httpError(404), errorCount = 99)),
        )
    }

    @Test
    fun aWebVtt503KeepsMedia3sOrdinaryHandling() {
        val failure = errorInfo(
            httpError(503, path = "/api/movies/7/subtitles/0/web.vtt"),
            errorCount = 1,
        )
        val default = DefaultLoadErrorHandlingPolicyProbe()
        assertEquals(default.retryDelayMsFor(failure), policy.getRetryDelayMsFor(failure))
    }

    @Test
    fun media3sFailFastClassificationsStayImmediate() {
        // A malformed container is not worth one retry, let alone six.
        val parse = androidx.media3.common.ParserException
            .createForMalformedContainer("bad box", null)
        assertEquals(
            C.TIME_UNSET,
            policy.getRetryDelayMsFor(errorInfo(parse, errorCount = 1)),
        )
    }

    @Test
    fun theRetryCountIsRaisedToCoverTheCapacityBudget() {
        // Media3 throws the load error upstream once errorCount passes this; it must outlast the
        // patient 503 retries or the player fails while the policy is still waiting.
        listOf(C.DATA_TYPE_MEDIA, C.DATA_TYPE_MANIFEST).forEach { dataType ->
            assertTrue(policy.getMinimumLoadableRetryCount(dataType) >= HLS_CAPACITY_RETRY_MAX_ATTEMPTS)
        }
    }

    /** Media3's untouched defaults, to assert the policy defers rather than to restate them. */
    private class DefaultLoadErrorHandlingPolicyProbe :
        androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
        fun retryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
            getRetryDelayMsFor(info)
    }
}
