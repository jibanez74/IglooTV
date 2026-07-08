package com.igloo.blindpenguincoder.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class QuickConnectInitiateRequest(
    @SerialName("device_name") val deviceName: String,
    val platform: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
)

@Serializable
data class QuickConnectInitiateData(
    val code: String,
    val secret: String,
    @SerialName("expires_in_seconds") val expiresInSeconds: Int,
    @SerialName("poll_interval_seconds") val pollIntervalSeconds: Int,
)

@Serializable
data class QuickConnectRedeemRequest(
    val code: String,
    val secret: String,
)

@Serializable
enum class QuickConnectStatus {
    @SerialName("pending")
    Pending,

    @SerialName("approved")
    Approved,
}

@Serializable
data class Device(
    val id: Long,
    val name: String,
    val platform: String,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("last_used_at") val lastUsedAt: String,
    @SerialName("is_current") val isCurrent: Boolean,
)

@Serializable
data class QuickConnectRedeemData(
    val status: QuickConnectStatus,
    val token: String? = null,
    val device: Device? = null,
)

@Serializable
data class QuickConnectApproveRequest(
    val code: String,
)

@Serializable
data class DeviceLoginRequest(
    val email: String,
    val password: String,
    @SerialName("device_name") val deviceName: String,
    val platform: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
)

@Serializable
data class DeviceTokenData(
    val token: String,
    val device: Device,
)

@Serializable
data class DevicesListData(
    val devices: List<Device>,
)

@Serializable
data class RenameDeviceRequest(
    val name: String,
)
