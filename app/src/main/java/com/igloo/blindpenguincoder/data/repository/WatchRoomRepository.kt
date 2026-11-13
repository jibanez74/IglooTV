package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.map
import com.igloo.blindpenguincoder.core.network.safeApiCall
import com.igloo.blindpenguincoder.data.api.WatchRoomApi
import com.igloo.blindpenguincoder.data.model.CreateWatchRoomData
import com.igloo.blindpenguincoder.data.model.CreateWatchRoomRequest
import com.igloo.blindpenguincoder.data.model.InviteUsersData
import com.igloo.blindpenguincoder.data.model.UserSummary
import com.igloo.blindpenguincoder.data.model.WatchRoomData
import com.igloo.blindpenguincoder.data.model.WatchRoomDetail
import com.igloo.blindpenguincoder.data.model.WatchRoomListItem
import com.igloo.blindpenguincoder.data.model.WatchRoomsData

class WatchRoomRepository(
    private val api: WatchRoomApi,
) {
    suspend fun watchRooms(): ApiResult<List<WatchRoomListItem>> =
        envelopeData<WatchRoomsData>("watch rooms") { api.watchRooms() }.map { it.rooms }

    suspend fun watchRoom(id: Long): ApiResult<WatchRoomDetail> =
        envelopeData<WatchRoomData>("watch room") { api.watchRoom(id) }.map { it.room }

    /** The new room's id. */
    suspend fun create(request: CreateWatchRoomRequest): ApiResult<Long> =
        envelopeData<CreateWatchRoomData>("new watch room") { api.create(request) }.map { it.roomId }

    suspend fun join(id: Long): ApiResult<Unit> = safeApiCall(request = { api.join(id) }, decode = { })

    suspend fun delete(id: Long): ApiResult<Unit> = safeApiCall(request = { api.delete(id) }, decode = { })

    suspend fun inviteUsers(): ApiResult<List<UserSummary>> =
        envelopeData<InviteUsersData>("invite users") { api.inviteUsers() }.map { it.users }
}
