package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.core.network.safeApiCall
import com.igloo.blindpenguincoder.core.network.toTransportError
import com.igloo.blindpenguincoder.data.api.VideoPlaybackApi
import com.igloo.blindpenguincoder.data.model.UpdateWatchProgressRequest
import com.igloo.blindpenguincoder.data.model.WatchProgressUpdateData
import com.igloo.blindpenguincoder.playback.hls.HlsManifestResult
import com.igloo.blindpenguincoder.playback.hls.HlsSessionApi
import com.igloo.blindpenguincoder.playback.hls.HlsSessionSpec
import com.igloo.blindpenguincoder.playback.hls.hlsQueryParams
import com.igloo.blindpenguincoder.playback.hls.parseHlsManifestResponse
import com.igloo.blindpenguincoder.playback.model.PLAYBACK_SERVER_UNREACHABLE_MESSAGE
import com.igloo.blindpenguincoder.playback.model.PlaybackMediaRef
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.discard

/** Everything the video player asks of the backend, for a movie or a TV episode alike. */
class VideoPlaybackRepository(
    private val api: VideoPlaybackApi,
) : HlsSessionApi {
    /** Absolute direct-stream URL for Media3; not an API call, so no [ApiResult]. */
    fun streamUrl(media: PlaybackMediaRef): String = api.streamUrl(media)

    /**
     * The manifest fetch that creates/refreshes an HLS session. Not [safeApiCall]: 503 and 404
     * are protocol states the session controller retries through, not failures. Established
     * request/socket timeouts count as "busy" because the server may legitimately hold a remux
     * manifest while FFmpeg warms up; a connection timeout means the server was never reached.
     * Only transport failures are absorbed, and programming errors must surface.
     */
    override suspend fun fetchHlsManifest(spec: HlsSessionSpec): HlsManifestResult = try {
        val response = api.hlsPlaylist(spec.media, spec.profileId, hlsQueryParams(spec))
        val status = response.status.value
        val headers = response.headers.entries().associate { (name, values) ->
            name.lowercase() to values.firstOrNull()
        }
        response.bodyAsChannel().discard()
        parseHlsManifestResponse(status, spec) { name -> headers[name.lowercase()] }
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        if (failure.hasConnectionTimeoutCause()) {
            HlsManifestResult.Failed(PLAYBACK_SERVER_UNREACHABLE_MESSAGE)
        } else {
            when (failure.toTransportError()) {
                AppError.Timeout -> HlsManifestResult.Busy(retryAfterSec = null)
                is AppError.Unexpected -> throw failure
                else -> HlsManifestResult.Failed(PLAYBACK_SERVER_UNREACHABLE_MESSAGE)
            }
        }
    }

    private fun Throwable.hasConnectionTimeoutCause(): Boolean =
        generateSequence<Throwable>(this) { it.cause }
            .take(HLS_CAUSE_CHAIN_LIMIT)
            .any { it is ConnectTimeoutException }

    /** Best-effort session teardown; the server's idle TTL is the real backstop. */
    override suspend fun stopHlsSession(media: PlaybackMediaRef, sessionUuid: String) {
        api.stopHlsSession(media, sessionUuid)
    }

    override fun hlsPlaylistUrl(spec: HlsSessionSpec): String =
        api.hlsPlaylistUrl(spec.media, spec.profileId, hlsQueryParams(spec))

    override fun subtitleUrl(media: PlaybackMediaRef, trackIndex: Int, startSec: Double): String =
        api.subtitleUrl(media, trackIndex, startSec)

    suspend fun updateWatchProgress(
        media: PlaybackMediaRef,
        body: UpdateWatchProgressRequest,
    ): ApiResult<WatchProgressUpdateData> =
        envelopeData("watch progress update") { api.updateWatchProgress(media, body) }

    private companion object {
        const val HLS_CAUSE_CHAIN_LIMIT = 8
    }
}
