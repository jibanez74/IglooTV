package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.network.safeApiCall
import com.igloo.blindpenguincoder.data.api.MusicApi
import com.igloo.blindpenguincoder.data.model.AlbumDetailsData
import com.igloo.blindpenguincoder.data.model.ApiEnvelope
import com.igloo.blindpenguincoder.data.model.LatestAlbumsData
import com.igloo.blindpenguincoder.data.model.SimpleAlbum
import io.ktor.client.call.body

class MusicRepository(
    private val api: MusicApi,
) {
    suspend fun latestAlbums(): ApiResult<List<SimpleAlbum>> = safeApiCall(
        request = { api.latestAlbums() },
        decode = { response ->
            response.body<ApiEnvelope<LatestAlbumsData>>().data?.albums
                ?: error("Missing albums in latest albums response")
        },
    )

    suspend fun albumDetails(id: Long): ApiResult<AlbumDetailsData> = safeApiCall(
        request = { api.albumDetails(id) },
        decode = { response ->
            response.body<ApiEnvelope<AlbumDetailsData>>().data
                ?: error("Missing data in album details response")
        },
    )
}
