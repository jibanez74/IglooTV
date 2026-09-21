package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.map
import com.igloo.blindpenguincoder.core.network.safeApiCall
import com.igloo.blindpenguincoder.data.api.MusicApi
import com.igloo.blindpenguincoder.data.model.AlbumDetailsData
import com.igloo.blindpenguincoder.data.model.AlbumsData
import com.igloo.blindpenguincoder.data.model.ApiEnvelope
import com.igloo.blindpenguincoder.data.model.LatestAlbumsData
import com.igloo.blindpenguincoder.data.model.LikedTrackIdsData
import com.igloo.blindpenguincoder.data.model.MusicStats
import com.igloo.blindpenguincoder.data.model.MusicianDetailsData
import com.igloo.blindpenguincoder.data.model.MusiciansData
import com.igloo.blindpenguincoder.data.model.ShuffleTracksData
import com.igloo.blindpenguincoder.data.model.SimpleAlbum
import com.igloo.blindpenguincoder.data.model.TrackLikeToggleData
import com.igloo.blindpenguincoder.data.model.TracksData
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse

class MusicRepository(
    private val api: MusicApi,
) : MusicQueueFetcher {
    suspend fun latestAlbums(): ApiResult<List<SimpleAlbum>> = safeApiCall(
        request = { api.latestAlbums() },
        decode = { response ->
            response.body<ApiEnvelope<LatestAlbumsData>>().data?.albums
                ?: error("Missing albums in latest albums response")
        },
    )

    /** One page of the album library. The envelope's paging counts are part of the result. */
    suspend fun albums(page: Long, perPage: Long): ApiResult<AlbumsData> =
        envelopeData("albums") { api.albums(page, perPage) }

    suspend fun albumDetails(id: Long): ApiResult<AlbumDetailsData> =
        envelopeData("album details") { api.albumDetails(id) }

    suspend fun musicians(page: Long, perPage: Long): ApiResult<MusiciansData> =
        envelopeData("musicians") { api.musicians(page, perPage) }

    suspend fun musicianDetails(id: Long): ApiResult<MusicianDetailsData> =
        envelopeData("musician details") { api.musicianDetails(id) }

    /** One `limit`/`offset` window of the track list; `has_more` says whether another follows. */
    override suspend fun tracks(limit: Long, offset: Long): ApiResult<TracksData> =
        envelopeData("tracks") { api.tracks(limit, offset) }

    override suspend fun shuffleTracks(limit: Long, exclude: List<Long>): ApiResult<ShuffleTracksData> =
        envelopeData("shuffle tracks") { api.shuffleTracks(limit, exclude) }

    suspend fun likedTrackIds(): ApiResult<Set<Long>> =
        envelopeData<LikedTrackIdsData>("liked track ids") { api.likedTrackIds() }
            .map { it.likedTrackIds.toSet() }

    suspend fun toggleTrackLike(id: Long): ApiResult<TrackLikeToggleData> =
        envelopeData("track like toggle") { api.toggleTrackLike(id) }

    suspend fun musicStats(): ApiResult<MusicStats> =
        envelopeData("music stats") { api.musicStats() }

    fun trackStreamUrl(id: Long): String = api.trackStreamUrl(id)

    /** Every music route answers with the shared envelope; a success without `data` is a defect. */
    private suspend inline fun <reified T> envelopeData(
        noun: String,
        noinline request: suspend () -> HttpResponse,
    ): ApiResult<T> = safeApiCall(
        request = request,
        decode = { response ->
            response.body<ApiEnvelope<T>>().data ?: error("Missing data in $noun response")
        },
    )
}
