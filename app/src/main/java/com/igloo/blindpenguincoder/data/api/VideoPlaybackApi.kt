package com.igloo.blindpenguincoder.data.api

import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import com.igloo.blindpenguincoder.data.model.UpdateWatchProgressRequest
import com.igloo.blindpenguincoder.playback.model.PlaybackMediaRef
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType

/**
 * The routes the video player drives, which movies and TV episodes share verbatim under their
 * own prefixes (`/movies/{id}` and `/shows/episodes/{id}`): the direct stream, the HLS session,
 * sideloaded subtitles, and the progress write. [mediaBase] is the one place the prefix is chosen.
 */
class VideoPlaybackApi(
    private val client: HttpClient,
    private val serverUrl: ServerUrlProvider,
) {
    /**
     * Absolute URL of the direct stream. Media3 fetches it on its own HTTP stack (Range/206),
     * not through Ktor, so this is a string rather than a request.
     */
    fun streamUrl(media: PlaybackMediaRef): String = "${mediaBase(media)}/stream"

    /**
     * The HLS manifest, which implicitly creates or refreshes the playback session. The query
     * pairs come pre-built (see `hlsQueryParams`) so their names are spelled in one place.
     * The server may hold a remux manifest up to 30s waiting for FFmpeg's first segments, so
     * this request gets a longer budget than the client default.
     */
    suspend fun hlsPlaylist(
        media: PlaybackMediaRef,
        profileId: String,
        query: List<Pair<String, String>>,
    ): HttpResponse =
        client.get("${mediaBase(media)}/hls/$profileId/playlist.m3u8") {
            query.forEach { (name, value) -> parameter(name, value) }
            timeout {
                requestTimeoutMillis = HLS_MANIFEST_TIMEOUT_MS
                socketTimeoutMillis = HLS_MANIFEST_TIMEOUT_MS
            }
        }

    /** Same manifest address as a string for Media3, which fetches on its own stack. */
    fun hlsPlaylistUrl(
        media: PlaybackMediaRef,
        profileId: String,
        query: List<Pair<String, String>>,
    ): String {
        val suffix = query.joinToString("&") { (name, value) -> "$name=$value" }
        return "${mediaBase(media)}/hls/$profileId/playlist.m3u8?$suffix"
    }

    /** Ends one personal HLS session; scoped to the client's own session UUID. */
    suspend fun stopHlsSession(media: PlaybackMediaRef, sessionUuid: String): HttpResponse =
        client.post("${mediaBase(media)}/hls/session/stop") {
            parameter("playback_session", sessionUuid)
        }

    /**
     * Sideloaded WebVTT for one text subtitle track. [startSec] must be the session's
     * effective start so cues land on the rebased timeline; zero (direct play) omits it.
     */
    fun subtitleUrl(media: PlaybackMediaRef, trackIndex: Int, startSec: Double): String {
        val base = "${mediaBase(media)}/subtitles/$trackIndex/web.vtt"
        return if (startSec > 0.0) "$base?start=$startSec" else base
    }

    suspend fun updateWatchProgress(
        media: PlaybackMediaRef,
        body: UpdateWatchProgressRequest,
    ): HttpResponse =
        client.put("${mediaBase(media)}/watch-progress") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun mediaBase(media: PlaybackMediaRef): String {
        val apiBaseUrl = serverUrl.require().apiBaseUrl
        return when (media) {
            is PlaybackMediaRef.Movie -> "$apiBaseUrl/movies/${media.id}"
            is PlaybackMediaRef.Episode -> "$apiBaseUrl/shows/episodes/${media.id}"
        }
    }

    private companion object {
        const val HLS_MANIFEST_TIMEOUT_MS = 45_000L
    }
}
