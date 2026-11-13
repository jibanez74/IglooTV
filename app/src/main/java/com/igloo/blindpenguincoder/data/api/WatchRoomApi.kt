package com.igloo.blindpenguincoder.data.api

import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import com.igloo.blindpenguincoder.data.model.CreateWatchRoomRequest
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse

class WatchRoomApi(
    private val client: HttpClient,
    private val serverUrl: ServerUrlProvider,
) {
    /** The rooms the caller hosts or was invited to, newest first; the list is not paged. */
    suspend fun watchRooms(): HttpResponse =
        client.get(serverUrl.api("/watch-rooms"))

    /** One room; 403 both for a room that does not exist and for one the caller is not in. */
    suspend fun watchRoom(id: Long): HttpResponse =
        client.get(serverUrl.api("/watch-rooms/$id"))

    /**
     * Creates a room the caller owns and joins. The server starts an HLS room's stream before it
     * answers, so the request waits for media ([awaitMediaPreparation]).
     */
    suspend fun create(body: CreateWatchRoomRequest): HttpResponse =
        client.post(serverUrl.api("/watch-rooms")) {
            jsonBody(body)
            awaitMediaPreparation()
        }

    /**
     * Confirms the caller is still a member before each connection to the room's socket. It adds
     * no one — membership is fixed when the room is created — and its body says nothing more.
     */
    suspend fun join(id: Long): HttpResponse =
        client.post(serverUrl.api("/watch-rooms/$id/join"))

    /** Closes a room for everyone in it; only its owner may. */
    suspend fun delete(id: Long): HttpResponse =
        client.delete(serverUrl.api("/watch-rooms/$id"))

    /**
     * The room's socket, open while [session] runs. The server never sends a Close frame — not
     * even after `room_deleted` — so its hanging up surfaces as an `EOFException`, the same as a
     * refused upgrade or a link whose pings went unanswered.
     */
    suspend fun socket(id: Long, session: suspend DefaultClientWebSocketSession.() -> Unit) =
        client.webSocket(serverUrl.webSocket("/watch-rooms/$id/ws"), block = session)

    /** Everyone a new room can invite: every user but the caller, by name. */
    suspend fun inviteUsers(): HttpResponse =
        client.get(serverUrl.api("/users"))
}
