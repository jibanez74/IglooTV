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
    @SerialName("hardware_acceleration_device") val hardwareAccelerationDevice: HardwareAccelerationDevice,
    @SerialName("enable_watcher") val enableWatcher: Boolean,
    @SerialName("download_images") val downloadImages: Boolean,
    @SerialName("static_dir") val staticDir: String,
    @SerialName("transcode_dir") val transcodeDir: String,
    @SerialName("server_upload_mbps") val serverUploadMbps: Double?,
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
    @SerialName("hardware_acceleration_device") val hardwareAccelerationDevice: HardwareAccelerationDevice,
    @SerialName("enable_watcher") val enableWatcher: Boolean,
    @SerialName("download_images") val downloadImages: Boolean,
    @SerialName("static_dir") val staticDir: String,
    @SerialName("transcode_dir") val transcodeDir: String,
    @SerialName("server_upload_mbps") val serverUploadMbps: Double?,
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

@Serializable
data class PlaybackSettings(
    val profiles: List<PlaybackProfile>,
    @SerialName("preferred_profile") val preferredProfile: String?,
    @SerialName("download_mbps") val downloadMbps: Double?,
    @SerialName("server_upload_mbps") val serverUploadMbps: Double?,
    @SerialName("is_admin") val isAdmin: Boolean,
    @SerialName("preferred_audio_language") val preferredAudioLanguage: String?,
    @SerialName("preferred_subtitle_language") val preferredSubtitleLanguage: String?,
)

@Serializable
data class UpdatePlaybackSettingsRequest(
    @SerialName("preferred_profile") val preferredProfile: String? = null,
    @SerialName("download_mbps") val downloadMbps: Double? = null,
    @SerialName("preferred_audio_language") val preferredAudioLanguage: String? = null,
    @SerialName("preferred_subtitle_language") val preferredSubtitleLanguage: String? = null,
    @SerialName("server_upload_mbps") val serverUploadMbps: Double? = null,
)

/** Playback settings as returned after an update. */
@Serializable
data class UpdatedPlaybackSettings(
    @SerialName("preferred_profile") val preferredProfile: String?,
    @SerialName("download_mbps") val downloadMbps: Double?,
    @SerialName("preferred_audio_language") val preferredAudioLanguage: String?,
    @SerialName("preferred_subtitle_language") val preferredSubtitleLanguage: String?,
)

/** Payload of `PlaybackSettingsEnvelope.data`. */
@Serializable
data class PlaybackSettingsData(
    val settings: PlaybackSettings,
)

/** Payload of `UpdatePlaybackSettingsEnvelope.data`. */
@Serializable
data class UpdatePlaybackSettingsData(
    val settings: UpdatedPlaybackSettings,
)
