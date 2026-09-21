package com.igloo.blindpenguincoder.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class HardwareAccelerationDevice {
    @SerialName("cpu") Cpu,
    @SerialName("apple") Apple,
    @SerialName("nvidia") Nvidia,
    @SerialName("intel") Intel,
}

/** Library directory settings; also the payload of `SettingsEnvelope.data`. */
@Serializable
data class SettingsData(
    @SerialName("music_dir") val musicDir: String?,
    @SerialName("movies_dir") val moviesDir: String?,
    @SerialName("shows_dir") val showsDir: String?,
)

@Serializable
data class UpdateLibrarySettingsRequest(
    @SerialName("music_dir") val musicDir: String?,
    @SerialName("movies_dir") val moviesDir: String?,
    @SerialName("shows_dir") val showsDir: String?,
)

/** Payload of `UpdateLibrarySettingsEnvelope.data`. */
@Serializable
data class UpdateLibrarySettingsData(
    val settings: SettingsData,
)

@Serializable
data class GeneralSettings(
    @SerialName("tmdb_key") val tmdbKey: String?,
    @SerialName("immich_base_url") val immichBaseUrl: String?,
    @SerialName("immich_api_key") val immichApiKey: String?,
    @SerialName("jellyfin_base_url") val jellyfinBaseUrl: String?,
    @SerialName("jellyfin_api_key") val jellyfinApiKey: String?,
    @SerialName("spotify_client_id") val spotifyClientId: String?,
    @SerialName("spotify_client_secret") val spotifyClientSecret: String?,
    @SerialName("enable_watcher") val enableWatcher: Boolean,
    @SerialName("download_images") val downloadImages: Boolean,
    @SerialName("static_dir") val staticDir: String,
    @SerialName("transcode_dir") val transcodeDir: String,
    @SerialName("restart_required") val restartRequired: Boolean? = null,
)

@Serializable
data class UpdateGeneralSettingsRequest(
    @SerialName("tmdb_key") val tmdbKey: String,
    @SerialName("immich_base_url") val immichBaseUrl: String,
    @SerialName("immich_api_key") val immichApiKey: String,
    @SerialName("jellyfin_base_url") val jellyfinBaseUrl: String,
    @SerialName("jellyfin_api_key") val jellyfinApiKey: String,
    @SerialName("spotify_client_id") val spotifyClientId: String,
    @SerialName("spotify_client_secret") val spotifyClientSecret: String,
    @SerialName("enable_watcher") val enableWatcher: Boolean,
    @SerialName("download_images") val downloadImages: Boolean,
    @SerialName("static_dir") val staticDir: String,
    @SerialName("transcode_dir") val transcodeDir: String,
)

/** Payload of `GeneralSettingsEnvelope.data`. */
@Serializable
data class GeneralSettingsData(
    val settings: GeneralSettings,
)

/** Payload of `UpdateGeneralSettingsEnvelope.data`. */
@Serializable
data class UpdateGeneralSettingsData(
    val settings: GeneralSettings,
    @SerialName("restart_required") val restartRequired: Boolean,
)

@Serializable
data class PlaybackProfile(
    val id: String,
    val label: String,
    val height: Int,
    @SerialName("video_mbps") val videoMbps: Int,
)

/** Server-wide playback settings; both admin-only, which is why they are not on the general routes. */
@Serializable
data class PlaybackSettings(
    val profiles: List<PlaybackProfile>,
    @SerialName("server_upload_mbps") val serverUploadMbps: Double?,
    @SerialName("hardware_acceleration_device") val hardwareAccelerationDevice: HardwareAccelerationDevice,
)

@Serializable
data class UpdatePlaybackSettingsRequest(
    @SerialName("server_upload_mbps") val serverUploadMbps: Double? = null,
    @SerialName("hardware_acceleration_device") val hardwareAccelerationDevice: HardwareAccelerationDevice? = null,
)

/** Payload of `PlaybackSettingsEnvelope.data`, returned by both the read and the update. */
@Serializable
data class PlaybackSettingsData(
    val settings: PlaybackSettings,
)
