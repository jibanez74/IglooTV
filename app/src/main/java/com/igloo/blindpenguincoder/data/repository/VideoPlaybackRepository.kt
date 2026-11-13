package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.network.safeApiCall
import com.igloo.blindpenguincoder.data.api.VideoPlaybackApi
import com.igloo.blindpenguincoder.data.model.UpdateWatchProgressRequest
import com.igloo.blindpenguincoder.data.model.WatchedData
import com.igloo.blindpenguincoder.playback.hls.HlsManifestResult
import com.igloo.blindpenguincoder.playback.hls.HlsSessionApi
import com.igloo.blindpenguincoder.playback.hls.HlsSessionSpec
import com.igloo.blindpenguincoder.playback.hls.hlsQueryParams
import com.igloo.blindpenguincoder.playback.hls.parseHlsManifestResponse
import com.igloo.blindpenguincoder.playback.model.PlaybackMediaRef

/** Everything the video player asks of the backend, for a movie or a TV episode alike. */
class VideoPlaybackRepository(
    private val api: VideoPlaybackApi,
) : HlsSessionApi {
    /** Absolute direct-stream URL for Media3; not an API call, so no [ApiResult]. */
    fun streamUrl(media: PlaybackMediaRef): String = api.streamUrl(media)

    /** The manifest fetch that creates or refreshes the viewer's own HLS session. */
    override suspend fun fetchHlsManifest(
        spec: HlsSessionSpec,
        keepalive: Boolean,
    ): HlsManifestResult = hlsManifestResult(
        request = { api.hlsPlaylist(spec.media, spec.profileId, hlsQueryParams(spec, keepalive)) },
        parse = { status, header -> parseHlsManifestResponse(status, spec, header) },
    )

    /** Best-effort session teardown; the server's idle TTL is the real backstop. */
    override suspend fun stopHlsSession(media: PlaybackMediaRef, sessionUuid: String) {
        api.stopHlsSession(media, sessionUuid)
    }

    override fun hlsPlaylistUrl(spec: HlsSessionSpec): String =
        api.hlsPlaylistUrl(spec.media, spec.profileId, hlsQueryParams(spec))

    override fun subtitleStreamUrl(media: PlaybackMediaRef, trackIndex: Int, startSec: Double): String =
        api.subtitleStreamUrl(media, trackIndex, startSec)

    suspend fun updateWatchProgress(
        media: PlaybackMediaRef,
        body: UpdateWatchProgressRequest,
    ): ApiResult<WatchedData> =
        envelopeData("watch progress update") { api.updateWatchProgress(media, body) }
}
