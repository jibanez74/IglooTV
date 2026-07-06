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

@Serializable
data class CreateNotificationRequest(
    val title: NotificationTitle,
    val message: String,
    // The backend expects camelCase for this one field.
    @SerialName("isAdmin") val isAdmin: Boolean? = null,
)

@Serializable
data class Notification(
    val id: Long,
    @SerialName("created_by_user_id") val createdByUserId: Long,
    @SerialName("user_id") val userId: SqlNullInt64,
    val title: NotificationTitle,
    val message: String,
    @SerialName("is_admin") val isAdmin: Boolean,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

/** Payload of `CreateNotificationEnvelope.data`. */
@Serializable
data class CreateNotificationData(
    val notification: Notification,
)

@Serializable
data class NotificationListItem(
    val id: Long,
    val title: NotificationTitle,
    val message: String,
    @SerialName("is_admin") val isAdmin: Boolean,
    @SerialName("is_read") val isRead: Boolean,
    @SerialName("created_by_name") val createdByName: String,
    @SerialName("user_id") val userId: Long?,
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
