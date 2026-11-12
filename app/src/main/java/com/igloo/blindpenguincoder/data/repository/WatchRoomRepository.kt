package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.map
import com.igloo.blindpenguincoder.data.api.WatchRoomApi
import com.igloo.blindpenguincoder.data.model.WatchRoomListItem
import com.igloo.blindpenguincoder.data.model.WatchRoomsData

class WatchRoomRepository(
    private val api: WatchRoomApi,
) {
    suspend fun watchRooms(): ApiResult<List<WatchRoomListItem>> =
        envelopeData<WatchRoomsData>("watch rooms") { api.watchRooms() }.map { it.rooms }
}
