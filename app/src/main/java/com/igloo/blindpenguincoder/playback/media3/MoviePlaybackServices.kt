package com.igloo.blindpenguincoder.playback.media3

import androidx.media3.datasource.DataSource
import com.igloo.blindpenguincoder.playback.hls.HlsSessionApi
import com.igloo.blindpenguincoder.playback.model.PlaybackMediaRef
import kotlinx.coroutines.CoroutineScope

/**
 * Everything one movie player engine needs from the app, bundled so the engine factory's
 * wiring stays one expression. Two HTTP factories exist because their read timeouts differ:
 * HLS segment requests long-poll the server for up to two minutes while FFmpeg encodes, a
 * patience that would only mask real stalls on a progressive stream.
 */
class MoviePlaybackServices(
    val progressiveDataSourceFactory: DataSource.Factory,
    val hlsDataSourceFactory: DataSource.Factory,
    val directStreamUrl: (media: PlaybackMediaRef) -> String,
    val hlsSessionApi: HlsSessionApi,
    val canPlayVideoMime: (mimeType: String) -> Boolean,
    val canPlayAudioMime: (mimeType: String, channels: Int?) -> Boolean,
    /** App-lifetime scope: the HLS stop request must survive the engine's release. */
    val stopScope: CoroutineScope,
)
