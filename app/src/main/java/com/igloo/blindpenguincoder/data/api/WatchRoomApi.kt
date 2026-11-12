package com.igloo.blindpenguincoder.data.api

import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse

class WatchRoomApi(
    private val client: HttpClient,
    private val serverUrl: ServerUrlProvider,
) {
    /** The rooms the caller hosts or was invited to, newest first; the list is not paged. */
    suspend fun watchRooms(): HttpResponse =
        client.get(serverUrl.api("/watch-rooms"))
}
