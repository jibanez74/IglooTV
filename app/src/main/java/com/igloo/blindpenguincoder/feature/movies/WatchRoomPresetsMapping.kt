package com.igloo.blindpenguincoder.feature.movies

import com.igloo.blindpenguincoder.data.model.AudioStream
import com.igloo.blindpenguincoder.data.model.MovieTechnicalDetailsData
import com.igloo.blindpenguincoder.data.model.PlaybackMode
import com.igloo.blindpenguincoder.data.model.VideoStream
import com.igloo.blindpenguincoder.playback.model.playbackModeLabel

/**
 * What a room made from this movie's page fixes for everyone in it (design-system section
 * 11.10.3): the page's current Playback Settings, as the server will take them — the two tracks as
 * positions in the movie's lists, and a mode the server accepts. [settingsLine] says it in the
 * dialog's words.
 */
data class WatchRoomPresets(
    val mode: PlaybackMode,
    val audioTrack: Long,
    val subtitleTrack: Long?,
    val settingsLine: String,
)

/**
 * The room's settings from the page's [settings], which already resolved the defaults. Direct is
 * kept only where the server allows a direct room ([roomDirectAllowed]); anywhere else it becomes
 * Remux, which keeps the original picture — the web client's same turn, so a room made on either
 * client from the same settings is the same room.
 */
internal fun watchRoomPresets(
    technical: MovieTechnicalDetailsData,
    settings: PlaybackSettingsUi,
): WatchRoomPresets {
    // Positions in stream_index order, which is what the server counts.
    val audio = technical.audioStreams.sortedBy { it.streamIndex }
    val subtitles = technical.subtitles.sortedBy { it.streamIndex }
    val audioIndex = audio.indexOfFirst { it.id == settings.selectedAudioId }.takeIf { it >= 0 } ?: 0
    val subtitleIndex = subtitles.indexOfFirst { it.id == settings.selectedSubtitleId }.takeIf { it >= 0 }
    val mode = settings.selectedMode.takeUnless {
        it == PlaybackMode.Direct && !roomDirectAllowed(technical.movie.mimeType, technical.videoStreams, audio, audioIndex)
    } ?: PlaybackMode.Remux
    return WatchRoomPresets(
        mode = mode,
        audioTrack = audioIndex.toLong(),
        subtitleTrack = subtitleIndex?.toLong(),
        settingsLine = roomSettingsLine(
            mode = mode,
            audio = audio.getOrNull(audioIndex)?.let { audioTrackLabel(it, audioIndex) },
            subtitle = subtitleIndex?.let { subtitleTrackLabel(subtitles[it], it) },
        ),
    )
}

/**
 * The server's rule for a direct room, which every member's browser or TV plays as the original
 * file (`watch_room_handler.go`): an MP4, its first audio track, chosen unambiguously and in a
 * codec browsers decode, and 8-bit 4:2:0 progressive H.264. Mirrored so the TV never asks for a
 * room the server would refuse.
 */
internal fun roomDirectAllowed(
    mimeType: String,
    videoStreams: List<VideoStream>,
    orderedAudio: List<AudioStream>,
    audioIndex: Int,
): Boolean {
    if (audioIndex != 0 || mimeType != MP4_MIME_TYPE) return false
    if (!audioChoiceUnambiguous(orderedAudio)) return false
    if (orderedAudio.firstOrNull()?.codec?.normalized()?.let { it !in DIRECT_AUDIO_CODECS } == true) return false
    val video = primaryVideoStream(videoStreams) ?: return false
    return video.codec.normalized() in H264_CODECS &&
        (video.bitDepth?.orNull() ?: 0) <= MAX_DIRECT_BIT_DEPTH &&
        video.pixelFormat?.orNull()?.normalized()?.let { it.isEmpty() || it in DIRECT_PIXEL_FORMATS } != false &&
        video.codecProfile?.orNull()?.normalized()?.let { profile ->
            NON_BROWSER_PROFILE_MARKERS.none { it in profile }
        } != false &&
        video.fieldOrder?.orNull()?.normalized() !in INTERLACED_FIELD_ORDERS
}

/**
 * The room's fixed choices in one line, the words the Playback Settings dialog uses. Shared by the
 * Watch Together dialog and the room's own screen, so a room reads the same before and after.
 */
internal fun roomSettingsLine(mode: PlaybackMode?, audio: String?, subtitle: String?): String =
    listOfNotNull(
        mode?.let(::playbackModeLabel),
        audio ?: AUDIO_DEFAULT_LABEL,
        subtitle?.let { "Subtitles: $it" } ?: "Subtitles off",
    ).joinToString(" · ")

/**
 * Whether a player can only mean the first track: no more than one, or no default flags (the
 * container's order decides), or exactly one flag and it is on the first.
 */
private fun audioChoiceUnambiguous(orderedAudio: List<AudioStream>): Boolean {
    if (orderedAudio.size <= 1) return true
    val defaults = orderedAudio.count { it.isDefault }
    return defaults == 0 || (defaults == 1 && orderedAudio.first().isDefault)
}

private fun String.normalized() = trim().lowercase()

private const val MP4_MIME_TYPE = "video/mp4"
private const val MAX_DIRECT_BIT_DEPTH = 8L
private val H264_CODECS = setOf("h264", "h.264", "avc", "avc1")
private val DIRECT_AUDIO_CODECS = setOf("", "aac", "mp3", "opus", "vorbis", "flac")
private val DIRECT_PIXEL_FORMATS = setOf("yuv420p", "yuvj420p", "nv12", "nv21")
private val NON_BROWSER_PROFILE_MARKERS = listOf("10", "4:2:2", "422", "4:4:4", "444")
private val INTERLACED_FIELD_ORDERS = setOf("tt", "bb", "tb", "bt")
