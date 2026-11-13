package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.data.api.WatchRoomApi
import com.igloo.blindpenguincoder.playback.hls.HlsManifestResult
import com.igloo.blindpenguincoder.playback.hls.HlsSessionApi
import com.igloo.blindpenguincoder.playback.hls.HlsSessionSpec
import com.igloo.blindpenguincoder.playback.hls.parseHlsManifestResponse
import com.igloo.blindpenguincoder.playback.model.PlaybackMediaRef
import com.igloo.blindpenguincoder.playback.model.WATCH_ROOM_TRACKS_CHANGED_MESSAGE

/**
 * A watch room's one shared HLS session, behind the interface the session controller drives for a
 * viewer's own — so its capacity waits, lost-session recovery and keepalive all apply unchanged.
 * What differs is the server's: the room fixed its profile, audio and a start at 0 when it was
 * created, so nothing in the controller's [HlsSessionSpec] is sent.
 */
internal class WatchRoomHlsSession(
    private val roomId: Long,
    private val api: WatchRoomApi,
    /** The movie's own playback routes: a room has no subtitle route of its own. */
    private val movies: HlsSessionApi,
) : HlsSessionApi {

    /**
     * The room's playlist. A keepalive is the same plain request — the room's route has no
     * refresh-only form — and recreates an idled-out session, from 0 as ever.
     */
    override suspend fun fetchHlsManifest(spec: HlsSessionSpec, keepalive: Boolean): HlsManifestResult =
        hlsManifestResult(request = { api.hlsPlaylist(roomId) }) { status, header ->
            if (status == TRACKS_CHANGED_STATUS) {
                HlsManifestResult.Failed(WATCH_ROOM_TRACKS_CHANGED_MESSAGE)
            } else {
                // The session's media starts at 0 whatever start this player asked for.
                parseHlsManifestResponse(status, spec.copy(startSec = 0), header)
            }
        }

    /** Never stopped from here: the session is everyone's, and one member leaving ends nothing. */
    override suspend fun stopHlsSession(media: PlaybackMediaRef, sessionUuid: String) = Unit

    override fun hlsPlaylistUrl(spec: HlsSessionSpec): String = api.hlsPlaylistUrl(roomId)

    override fun subtitleStreamUrl(media: PlaybackMediaRef, trackIndex: Int, startSec: Double): String =
        movies.subtitleStreamUrl(media, trackIndex, startSec)
}

private const val TRACKS_CHANGED_STATUS = 409
