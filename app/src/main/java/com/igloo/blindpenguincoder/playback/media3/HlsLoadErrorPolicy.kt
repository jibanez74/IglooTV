// LoadErrorHandlingPolicy is part of Media3's unstable surface; kept below the engine seam.
@file:androidx.annotation.OptIn(UnstableApi::class)

package com.igloo.blindpenguincoder.playback.media3

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.igloo.blindpenguincoder.playback.hls.HLS_CAPACITY_RETRY_MAX_ATTEMPTS
import com.igloo.blindpenguincoder.playback.hls.hlsLoadRetryDelayMs

/**
 * The default policy gives a 503 three quick retries — right for a broken server, wrong for
 * this one, where 503 is the documented "segment not encoded yet / no capacity" answer that
 * deserves patient retries honoring `Retry-After`. Everything else falls through to the
 * defaults. The delay rule itself lives in `HlsSessionPolicy` where it is unit-tested.
 */
internal class HlsLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val http = generateSequence<Throwable>(loadErrorInfo.exception) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()
        // HttpURLConnection's header map is case-preserving and holds the status line under a
        // null key, so a tolerant scan beats a direct get.
        val retryAfterSec = http?.headerFields?.entries
            ?.firstOrNull { it.key?.equals("Retry-After", ignoreCase = true) == true }
            ?.value?.firstOrNull()?.toIntOrNull()
        val defaultDelayMs = super.getRetryDelayMsFor(loadErrorInfo)
        val delayMs = hlsLoadRetryDelayMs(
            responseCode = http?.responseCode,
            retryAfterSec = retryAfterSec,
            errorCount = loadErrorInfo.errorCount,
            defaultRetryCount = super.getMinimumLoadableRetryCount(loadErrorInfo.mediaLoadData.dataType),
            defaultRetryDelayMs = defaultDelayMs.takeUnless { it == C.TIME_UNSET },
        )
        return delayMs ?: C.TIME_UNSET
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int =
        maxOf(super.getMinimumLoadableRetryCount(dataType), HLS_CAPACITY_RETRY_MAX_ATTEMPTS)
}
