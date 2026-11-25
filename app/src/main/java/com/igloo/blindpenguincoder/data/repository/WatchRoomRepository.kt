package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.map
import com.igloo.blindpenguincoder.core.network.IglooJson
import com.igloo.blindpenguincoder.core.network.safeApiCall
import com.igloo.blindpenguincoder.data.api.WatchRoomApi
import com.igloo.blindpenguincoder.data.model.CreateWatchRoomData
import com.igloo.blindpenguincoder.data.model.CreateWatchRoomRequest
import com.igloo.blindpenguincoder.data.model.InviteUsersData
import com.igloo.blindpenguincoder.data.model.UpdateWatchRoomMembersRequest
import com.igloo.blindpenguincoder.data.model.UserSummary
import com.igloo.blindpenguincoder.data.model.WatchRoomClientEvent
import com.igloo.blindpenguincoder.data.model.WatchRoomData
import com.igloo.blindpenguincoder.data.model.WatchRoomDetail
import com.igloo.blindpenguincoder.data.model.WatchRoomListItem
import com.igloo.blindpenguincoder.data.model.WatchRoomServerEvent
import com.igloo.blindpenguincoder.data.model.WatchRoomsData
import com.igloo.blindpenguincoder.playback.hls.HlsSessionApi
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.mapNotNull

/**
 * What a room's screen needs from the server, owned here so JVM tests can fake it: MockEngine
 * cannot upgrade to a WebSocket.
 */
interface WatchRoomLink {
    suspend fun watchRoom(id: Long): ApiResult<WatchRoomDetail>

    /** The membership check run before each connection. */
    suspend fun join(id: Long): ApiResult<Unit>

    suspend fun delete(id: Long): ApiResult<Unit>

    /** Everyone the room can invite: every user but the caller. */
    suspend fun inviteUsers(): ApiResult<List<UserSummary>>

    /** The owner replaces the room's invitees. */
    suspend fun updateMembers(id: Long, invitedUserIds: List<Long>): ApiResult<Unit>

    /** An invitee leaves the room. */
    suspend fun leave(id: Long): ApiResult<Unit>

    /**
     * Runs [session] on the room's socket and returns when [session] does. A connection the
     * server or the network ends throws instead: the server hangs up without a Close frame, so
     * even its own goodbye arrives as an EOF.
     */
    suspend fun connect(id: Long, session: suspend (RoomSocket) -> Unit)
}

/** One open connection to a room. */
interface RoomSocket {
    /** What the server sends, in order; a message this build cannot read is skipped. */
    val events: Flow<WatchRoomServerEvent>

    suspend fun send(command: WatchRoomClientEvent)
}

class WatchRoomRepository(
    private val api: WatchRoomApi,
) : WatchRoomLink {
    suspend fun watchRooms(): ApiResult<List<WatchRoomListItem>> =
        envelopeData<WatchRoomsData>("watch rooms") { api.watchRooms() }.map { it.rooms }

    override suspend fun watchRoom(id: Long): ApiResult<WatchRoomDetail> =
        envelopeData<WatchRoomData>("watch room") { api.watchRoom(id) }.map { it.room }

    /** The new room's id. */
    suspend fun create(request: CreateWatchRoomRequest): ApiResult<Long> =
        envelopeData<CreateWatchRoomData>("new watch room") { api.create(request) }.map { it.roomId }

    override suspend fun join(id: Long): ApiResult<Unit> = safeApiCall(request = { api.join(id) }, decode = { })

    override suspend fun delete(id: Long): ApiResult<Unit> =
        safeApiCall(request = { api.delete(id) }, decode = { })

    override suspend fun updateMembers(id: Long, invitedUserIds: List<Long>): ApiResult<Unit> =
        safeApiCall(request = { api.updateMembers(id, UpdateWatchRoomMembersRequest(invitedUserIds)) }, decode = { })

    override suspend fun leave(id: Long): ApiResult<Unit> =
        safeApiCall(request = { api.leave(id) }, decode = { })

    override suspend fun connect(id: Long, session: suspend (RoomSocket) -> Unit) {
        api.socket(id) {
            val socket = this
            session(
                object : RoomSocket {
                    override val events = socket.incoming.consumeAsFlow()
                        .mapNotNull { frame -> (frame as? Frame.Text)?.let { decodeRoomEvent(it.readText()) } }

                    override suspend fun send(command: WatchRoomClientEvent) =
                        socket.send(Frame.Text(encodeRoomCommand(command)))
                },
            )
        }
    }

    /** A direct room's stream, for Media3. */
    fun streamUrl(id: Long): String = api.streamUrl(id)

    /** Room [id]'s shared HLS session; [movies] serves the subtitles, which stay the movie's. */
    fun hlsSession(id: Long, movies: HlsSessionApi): HlsSessionApi = WatchRoomHlsSession(id, api, movies)

    override suspend fun inviteUsers(): ApiResult<List<UserSummary>> =
        envelopeData<InviteUsersData>("invite users") { api.inviteUsers() }.map { it.users }
}

/** One text frame from a room, or null for a message this build cannot read (a newer server's). */
internal fun decodeRoomEvent(text: String): WatchRoomServerEvent? = try {
    IglooJson.decodeFromString<WatchRoomServerEvent>(text)
} catch (_: IllegalArgumentException) {
    // A SerializationException is one too.
    null
}

internal fun encodeRoomCommand(command: WatchRoomClientEvent): String = IglooJson.encodeToString(command)
