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
data class QuickConnectRedeemData(
    val status: QuickConnectStatus,
    val token: String? = null,
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
)
