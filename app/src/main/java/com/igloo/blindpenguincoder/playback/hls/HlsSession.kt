package com.igloo.blindpenguincoder.playback.hls

/**
 * One HLS playback session as the backend keys it: movie + profile + audio ordinal + client
 * UUID + start second. Any change to these is a new server session (and a new FFmpeg run);
 * `reload` is an opaque cache-buster echoed into rewritten asset URLs, deliberately outside
 * the session key.
 */
data class HlsSessionSpec(
    val movieId: Long,
    val profileId: String,
    /** Ordinal into the movie's audio streams in `stream_index` order; null = video-only movie. */
    val audioTypeIndex: Int?,
    val startSec: Int,
    val sessionUuid: String,
    val reload: Int = 0,
)

/**
 * What a successful manifest fetch establishes. [actualStartSec] is where the session's media
 * really begins — copy-video sessions cut at the source keyframe at or before the requested
 * start — and is the offset that maps session time to absolute movie time.
 */
data class HlsSessionStart(
    val spec: HlsSessionSpec,
    val effectiveProfileId: String,
    val actualStartSec: Double,
    val playlistUrl: String,
)

/** The manifest endpoint's protocol states; only [Failed] is a dead end. */
sealed interface HlsManifestResult {
    data class Ready(val effectiveProfileId: String, val actualStartSec: Double) : HlsManifestResult

    /** 503: no transcode capacity right now. Retry after [retryAfterSec] (null = unspecified). */
    data class Busy(val retryAfterSec: Int?) : HlsManifestResult

    /** 404: the session (or movie) is gone; a fresh manifest request may recreate it. */
    data object Lost : HlsManifestResult

    data class Failed(val message: String, val unauthorized: Boolean = false) : HlsManifestResult
}

/**
 * The one place the manifest query parameters are spelled. `start` is always sent (the server
 * requires it), `audio_track` only when the movie has audio, `reload` only once recovery has
 * bumped it.
 */
fun hlsQueryParams(spec: HlsSessionSpec): List<Pair<String, String>> = buildList {
    add("playback_session" to spec.sessionUuid)
    add("start" to spec.startSec.toString())
    spec.audioTypeIndex?.let { add("audio_track" to it.toString()) }
    if (spec.reload > 0) add("reload" to spec.reload.toString())
}

/**
 * Classifies a manifest response. On 200 the two `X-Igloo-*` headers carry the truth about
 * what FFmpeg actually ran and where the media really begins; when absent, the requested
 * values are the best available answer.
 */
fun parseHlsManifestResponse(
    status: Int,
    spec: HlsSessionSpec,
    header: (String) -> String?,
): HlsManifestResult = when (status) {
    200 -> HlsManifestResult.Ready(
        effectiveProfileId = header("X-Igloo-Effective-Profile")?.takeIf { it.isNotBlank() }
            ?: spec.profileId,
        actualStartSec = header("X-Igloo-Actual-Start")?.toDoubleOrNull()
            ?: spec.startSec.toDouble(),
    )
    503 -> HlsManifestResult.Busy(retryAfterSec = header("Retry-After")?.toIntOrNull())
    404 -> HlsManifestResult.Lost
    401 -> HlsManifestResult.Failed(
        message = "Your session is no longer valid. Sign in again to keep watching.",
        unauthorized = true,
    )
    else -> HlsManifestResult.Failed("The server refused the stream (HTTP $status).")
}
