package com.igloo.blindpenguincoder.feature.movies

import com.igloo.blindpenguincoder.data.model.AudioStream
import com.igloo.blindpenguincoder.data.model.MovieTechnicalDetailsData
import com.igloo.blindpenguincoder.data.model.MovieTechnicalFile
import com.igloo.blindpenguincoder.data.model.PlaybackMode
import com.igloo.blindpenguincoder.data.model.SqlNullInt64
import com.igloo.blindpenguincoder.data.model.SqlNullString
import com.igloo.blindpenguincoder.data.model.Subtitle
import com.igloo.blindpenguincoder.data.model.VideoStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The server's direct-room rule, mirrored (`watch_room_handler.go`), and the room's ordinals. */
class WatchRoomPresetsMappingTest {

    private fun sql(value: String?) = SqlNullString(value = value.orEmpty(), valid = value != null)

    private fun video(
        codec: String = "h264",
        bitDepth: Long? = 8,
        pixelFormat: String? = "yuv420p",
        profile: String? = "High",
        fieldOrder: String? = "progressive",
    ) = VideoStream(
        codec = codec,
        width = 1920,
        height = 800,
        codecProfile = sql(profile),
        bitDepth = SqlNullInt64(value = bitDepth ?: 0, valid = bitDepth != null),
        pixelFormat = sql(pixelFormat),
        fieldOrder = sql(fieldOrder),
    )

    private fun audio(id: Long, streamIndex: Long, codec: String = "aac", isDefault: Boolean = false, language: String = "eng") =
        AudioStream(
            id = id,
            streamIndex = streamIndex,
            codec = codec,
            channels = 2,
            channelLayout = sql("stereo"),
            language = sql(language),
            isDefault = isDefault,
        )

    private fun subtitle(id: Long, streamIndex: Long) = Subtitle(
        id = id,
        streamIndex = streamIndex,
        codec = "subrip",
        language = sql("spa"),
        title = sql(null),
        isForced = false,
        isDefault = false,
    )

    private fun technical(
        mimeType: String = "video/mp4",
        videos: List<VideoStream> = listOf(video()),
        audios: List<AudioStream> = listOf(audio(10, 1, isDefault = true)),
        subtitles: List<Subtitle> = emptyList(),
    ) = MovieTechnicalDetailsData(
        movie = MovieTechnicalFile(mimeType = mimeType),
        videoStreams = videos,
        audioStreams = audios,
        subtitles = subtitles,
        chapters = emptyList(),
    )

    /** The page's settings over [technical], as the Playback Settings dialog resolves them. */
    private fun presets(
        technical: MovieTechnicalDetailsData = technical(),
        mode: PlaybackMode = PlaybackMode.Direct,
        audioId: Long? = null,
        subtitle: SubtitleChoice = SubtitleChoice.Default,
    ) = watchRoomPresets(
        technical,
        playbackSettingsUi(
            audioStreams = technical.audioStreams,
            subtitles = technical.subtitles,
            selection = PlaybackSelection(mode = mode, audioStreamId = audioId, subtitle = subtitle),
        ),
    )

    @Test
    fun `a browser-safe MP4 keeps Direct`() {
        assertEquals(PlaybackMode.Direct, presets().mode)
    }

    @Test
    fun `every server refusal turns Direct into Remux`() {
        val refused = mapOf(
            "an MKV" to technical(mimeType = "video/x-matroska"),
            "HEVC" to technical(videos = listOf(video(codec = "hevc"))),
            "10-bit" to technical(videos = listOf(video(bitDepth = 10))),
            "a 10-bit pixel format" to technical(videos = listOf(video(pixelFormat = "yuv420p10le"))),
            "4:2:2" to technical(videos = listOf(video(pixelFormat = "yuv422p"))),
            "a High 10 profile" to technical(videos = listOf(video(profile = "High 10"))),
            "interlacing" to technical(videos = listOf(video(fieldOrder = "tt"))),
            "no video" to technical(videos = emptyList()),
            "DTS first" to technical(audios = listOf(audio(10, 1, codec = "dts"))),
            "a default on the second track" to technical(
                audios = listOf(audio(10, 1), audio(11, 2, isDefault = true)),
            ),
        )
        refused.forEach { (why, file) -> assertEquals(why, PlaybackMode.Remux, presets(file).mode) }
    }

    @Test
    fun `a direct room can only play the first audio track`() {
        val file = technical(audios = listOf(audio(10, 1, isDefault = true), audio(11, 2, language = "spa")))

        val second = presets(file, audioId = 11)

        assertEquals(PlaybackMode.Remux, second.mode)
        assertEquals(1L, second.audioTrack)
    }

    @Test
    fun `an unknown pixel format and no bit depth are not refusals`() {
        val file = technical(videos = listOf(video(bitDepth = null, pixelFormat = null, profile = null, fieldOrder = null)))

        assertEquals(PlaybackMode.Direct, presets(file).mode)
    }

    @Test
    fun `a chosen transcode is kept as it is`() {
        assertEquals(PlaybackMode.P1080Mbps8, presets(technical(mimeType = "video/x-matroska"), mode = PlaybackMode.P1080Mbps8).mode)
    }

    @Test
    fun `tracks become positions in stream order and off is no subtitle`() {
        val file = technical(
            audios = listOf(audio(11, 3, language = "spa"), audio(10, 1, isDefault = true)),
            subtitles = listOf(subtitle(21, 5), subtitle(20, 4)),
        )

        val chosen = presets(file, mode = PlaybackMode.Remux, audioId = 11, subtitle = SubtitleChoice.Track(21))
        assertEquals(1L, chosen.audioTrack)
        assertEquals(1L, chosen.subtitleTrack)
        assertNull(presets(file, subtitle = SubtitleChoice.Off).subtitleTrack)
    }

    @Test
    fun `the settings line is the dialog's words`() {
        assertEquals(
            "Original quality — plays the file as-is · English · Stereo · Subtitles off",
            presets(subtitle = SubtitleChoice.Off).settingsLine,
        )
        assertEquals(
            "Original quality — plays the file as-is · Default · Subtitles: English",
            roomSettingsLine(PlaybackMode.Direct, audio = null, subtitle = "English"),
        )
    }
}
