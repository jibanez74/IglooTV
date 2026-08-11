package com.igloo.blindpenguincoder.data.api

import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse

class MusicApi(
    private val client: HttpClient,
    private val serverUrl: ServerUrlProvider,
) {
    /**
     * Newest additions to the music library. The route takes no parameters at all — the backend
     * owns both the order and the cap of 12, so there is nothing to page or sort from here.
     */
    suspend fun latestAlbums(): HttpResponse =
        client.get("${serverUrl.require().apiBaseUrl}/music/albums/latest")
}
