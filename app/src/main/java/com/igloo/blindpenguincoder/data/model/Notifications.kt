package com.igloo.blindpenguincoder.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class NotificationTitle {
    @SerialName("movie_request") MovieRequest,
    @SerialName("album_request") AlbumRequest,
    @SerialName("track_request") TrackRequest,
    @SerialName("other") Other,
}

/** Body of `POST /notifications`; the reply is a bare [MessageResponse] with no payload. */
@Serializable
data class CreateNotificationRequest(
    val title: NotificationTitle,
    val message: String,
    // The backend expects camelCase for this one field.
    @SerialName("isAdmin") val isAdmin: Boolean? = null,
)

@Serializable
data class NotificationListItem(
    val id: Long,
    val title: NotificationTitle,
    val message: String,
    @SerialName("is_admin") val isAdmin: Boolean,
    @SerialName("is_read") val isRead: Boolean,
    @SerialName("created_by_name") val createdByName: String,
    @SerialName("created_at") val createdAt: String,
)

/** Payload of `NotificationsListEnvelope.data`. */
@Serializable
data class NotificationsListData(
    val notifications: List<NotificationListItem>,
    @SerialName("unread_count") val unreadCount: Long,
)

/** Payload of `UnreadNotificationCountEnvelope.data`. */
@Serializable
data class UnreadNotificationCountData(
    @SerialName("unread_count") val unreadCount: Long,
)
