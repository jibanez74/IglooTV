package com.igloo.blindpenguincoder.feature.movies

import com.igloo.blindpenguincoder.data.model.AudioStream
import com.igloo.blindpenguincoder.data.model.PlaybackMode
import com.igloo.blindpenguincoder.data.model.SqlNullString
import com.igloo.blindpenguincoder.data.model.Subtitle
import com.igloo.blindpenguincoder.playback.model.playbackModeLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSettingsMappingTest {

    private fun audioStream(
        id: Long = 1,
        streamIndex: Long = 1,
        channels: Long = 6,
        channelLayout: String? = "5.1(side)",
        language: String? = "eng",
        title: String? = null,
        isDefault: Boolean = false,
        codec: String = "dts",
    ) = AudioStream(
        id = id,
        movieId = 1,
        streamIndex = streamIndex,
        codec = codec,
        bitRate = 0,
        channels = channels,
        channelLayout = sqlString(channelLayout),
        language = sqlString(language),
        title = sqlString(title),
        isDefault = isDefault,
    )

    private fun subtitle(
        id: Long = 1,
        streamIndex: Long = 2,
        codec: String = "subrip",
        language: String? = "eng",
        title: String? = null,
        isForced: Boolean = false,
        isDefault: Boolean = false,
    ) = Subtitle(
        id = id,
        movieId = 1,
        streamIndex = streamIndex,
        codec = codec,
        language = sqlString(language),
        title = sqlString(title),
        isForced = isForced,
        isDefault = isDefault,
    )

    private fun sqlString(value: String?) =
        SqlNullString(value = value.orEmpty(), valid = value != null)

    // --- mode labels ---

    @Test
    fun `mode labels name every mode`() {
        assertEquals(
            listOf(
                "Original quality — plays the file as-is",
                "Original quality — audio adjusted",
                "4K — highest quality",
                "1080p — best quality",
                "1080p — high quality",
                "1080p — balanced",
                "720p — lower bandwidth",
            ),
            PlaybackMode.entries.map(::playbackModeLabel),
        )
    }

    // --- track labels ---

    @Test
    fun `audio label prefers language and falls back to track number`() {
        assertEquals("English · 5.1 surround", audioTrackLabel(audioStream(), 0))
        assertEquals(
            "Track 2 · Stereo",
            audioTrackLabel(audioStream(language = null, channels = 2, channelLayout = "stereo"), 1),
        )
    }

    @Test
    fun `subtitle label joins parts and drops a title equal to the language`() {
        assertEquals(
            "English · SDH · Forced · Default",
            subtitleTrackLabel(subtitle(title = "SDH", isForced = true, isDefault = true), 0),
        )
        assertEquals("English", subtitleTrackLabel(subtitle(title = "English"), 0))
        assertEquals("Track 3", subtitleTrackLabel(subtitle(language = null), 2))
    }

    @Test
    fun `image based codecs are recognized case-insensitively`() {
        assertTrue(isImageBasedSubtitleCodec("hdmv_pgs_subtitle"))
        assertTrue(isImageBasedSubtitleCodec("DVD_subtitle"))
        assertTrue(isImageBasedSubtitleCodec("dvb_subtitle"))
        assertFalse(isImageBasedSubtitleCodec("subrip"))
    }

    // --- playbackSettingsUi ---

    @Test
    fun `defaults are direct, the default audio stream, and subtitles off`() {
        val ui = playbackSettingsUi(
            audioStreams = listOf(
                audioStream(id = 10, streamIndex = 1),
                audioStream(id = 11, streamIndex = 2, isDefault = true),
            ),
            subtitles = listOf(subtitle(id = 20)),
            selection = PlaybackSelection(),
        )
        assertEquals(PlaybackMode.Direct, ui.selectedMode)
        assertEquals(11L, ui.selectedAudioId)
        assertNull(ui.selectedSubtitleId)
        assertEquals(PlaybackMode.entries.toList(), ui.modes.map { it.mode })
    }

    @Test
    fun `first stream is the default when none is flagged`() {
        val ui = playbackSettingsUi(
            audioStreams = listOf(audioStream(id = 10), audioStream(id = 11)),
            subtitles = emptyList(),
            selection = PlaybackSelection(),
        )
        assertEquals(PlaybackMode.Direct, ui.selectedMode)
        assertEquals(10L, ui.selectedAudioId)
    }

    @Test
    fun `none row leads the subtitle list and is selected by default`() {
        val ui = playbackSettingsUi(
            audioStreams = listOf(audioStream()),
            subtitles = listOf(subtitle(id = 20)),
            selection = PlaybackSelection(),
        )
        assertEquals(SUBTITLES_NONE_LABEL, ui.subtitleTracks.first().label)
        assertNull(ui.subtitleTracks.first().id)
        assertNull(ui.selectedSubtitleId)
    }

    @Test
    fun `image based subtitles are selectable under direct play`() {
        val ui = playbackSettingsUi(
            audioStreams = listOf(audioStream()),
            subtitles = listOf(subtitle(id = 20, codec = "hdmv_pgs_subtitle")),
            selection = PlaybackSelection(mode = PlaybackMode.Direct, subtitleStreamId = 20),
        )
        val row = ui.subtitleTracks.single { it.id == 20L }
        assertTrue(row.enabled)
        assertFalse(row.label.contains("(image-based)"))
        assertEquals(20L, ui.selectedSubtitleId)
        assertTrue(ui.explanation.contains("Subtitles: English."))
    }

    @Test
    fun `image based subtitle rows are inert outside direct play`() {
        val ui = playbackSettingsUi(
            audioStreams = listOf(audioStream()),
            subtitles = listOf(subtitle(id = 20, codec = "hdmv_pgs_subtitle")),
            selection = PlaybackSelection(mode = PlaybackMode.Remux, subtitleStreamId = 20),
        )
        val row = ui.subtitleTracks.single { it.id == 20L }
        assertFalse(row.enabled)
        assertTrue(row.label.endsWith("(image-based)"))
        assertNull(ui.selectedSubtitleId)
        assertTrue(ui.explanation.contains("Subtitles are off."))
    }

    @Test
    fun `missing technical details degrade to inert stand-ins`() {
        val ui = playbackSettingsUi(
            audioStreams = null,
            subtitles = null,
            selection = PlaybackSelection(),
        )
        val audioRow = ui.audioTracks.single()
        assertEquals(AUDIO_DEFAULT_LABEL, audioRow.label)
        assertFalse(audioRow.enabled)
        assertNull(ui.selectedAudioId)
        assertEquals(SUBTITLES_NONE_LABEL, ui.subtitleTracks.single().label)
        assertTrue(ui.explanation.contains("Default audio is used."))
    }

    @Test
    fun `a selection whose track vanished falls back to the default`() {
        val ui = playbackSettingsUi(
            audioStreams = listOf(audioStream(id = 10, isDefault = true)),
            subtitles = listOf(subtitle(id = 20)),
            selection = PlaybackSelection(audioStreamId = 99, subtitleStreamId = 99),
        )
        assertEquals(10L, ui.selectedAudioId)
        assertNull(ui.selectedSubtitleId)
    }

    @Test
    fun `direct with a non-first audio track stays direct`() {
        // Unlike the web client, ExoPlayer selects any embedded track itself — no remux upgrade.
        val ui = playbackSettingsUi(
            audioStreams = listOf(audioStream(id = 10, isDefault = true), audioStream(id = 11)),
            subtitles = emptyList(),
            selection = PlaybackSelection(mode = PlaybackMode.Direct, audioStreamId = 11),
        )
        assertEquals(PlaybackMode.Direct, ui.selectedMode)
        assertEquals(11L, ui.selectedAudioId)
        assertFalse(ui.explanation.contains("switched"))
    }

    @Test
    fun `transcode modes are unaffected by the audio choice`() {
        val ui = playbackSettingsUi(
            audioStreams = listOf(audioStream(id = 10), audioStream(id = 11)),
            subtitles = emptyList(),
            selection = PlaybackSelection(mode = PlaybackMode.P1080Mbps8, audioStreamId = 11),
        )
        assertEquals(PlaybackMode.P1080Mbps8, ui.selectedMode)
    }

    // --- explanation ---

    @Test
    fun `explanation describes each mode family`() {
        assertTrue(
            playbackExplanation(PlaybackMode.Direct, "English · Stereo", null)
                .startsWith("Your movie plays directly with no conversion"),
        )
        assertTrue(
            playbackExplanation(PlaybackMode.Remux, "English · Stereo", null)
                .startsWith("The picture stays the same"),
        )
        assertTrue(
            playbackExplanation(PlaybackMode.P720Mbps3, "English · Stereo", null)
                .contains("smooth playback (720p — lower bandwidth)"),
        )
    }

    @Test
    fun `explanation names the audio and subtitle choices`() {
        val text = playbackExplanation(
            PlaybackMode.Direct,
            audioLabel = "English · 5.1 surround",
            subtitleLabel = "English · Forced",
        )
        assertTrue(text.contains("You'll hear: English · 5.1 surround."))
        assertTrue(text.contains("Subtitles: English · Forced."))
    }

    @Test
    fun `explanation states the silent defaults`() {
        val text = playbackExplanation(PlaybackMode.Direct, audioLabel = null, subtitleLabel = null)
        assertTrue(text.contains("Default audio is used."))
        assertTrue(text.contains("Subtitles are off."))
    }

    // --- seven-mode contract ---

    @Test
    fun `mode rows always contain the normative seven modes`() {
        val ui = playbackSettingsUi(
            audioStreams = listOf(audioStream()),
            subtitles = null,
            selection = PlaybackSelection(),
        )
        assertEquals(PlaybackMode.entries.toList(), ui.modes.map { it.mode })
    }

    // --- direct honored, never substituted ---

    @Test
    fun `an unplayable direct pick stays selected and the explanation says why`() {
        val ui = playbackSettingsUi(
            audioStreams = listOf(audioStream(codec = "truehd")),
            subtitles = null,
            selection = PlaybackSelection(mode = PlaybackMode.Direct),
            canPlayAudioMime = { _, _ -> false },
        )
        // The choice is honored — no silent fallback to Remux.
        assertEquals(PlaybackMode.Direct, ui.selectedMode)
        assertTrue(ui.modes.any { it.mode == PlaybackMode.Direct })
        assertTrue(ui.explanation.contains("Dolby TrueHD"))
        assertTrue(ui.explanation.contains(playbackModeLabel(PlaybackMode.Remux)))
    }

    @Test
    fun `a playable direct pick keeps the plain explanation`() {
        val ui = playbackSettingsUi(
            audioStreams = listOf(audioStream(codec = "truehd")),
            subtitles = null,
            selection = PlaybackSelection(mode = PlaybackMode.Direct),
            canPlayAudioMime = { _, _ -> true },
        )
        assertTrue(!ui.explanation.contains("can't play"))
    }

    @Test
    fun `an HLS mode never warns about the source codec`() {
        val ui = playbackSettingsUi(
            audioStreams = listOf(audioStream(codec = "truehd")),
            subtitles = null,
            selection = PlaybackSelection(mode = PlaybackMode.Remux),
            canPlayAudioMime = { _, _ -> false },
        )
        assertTrue(!ui.explanation.contains("can't play"))
    }

    // --- the automatic audio conversion ---

    /** Tracks the engine converts get an announcement instead of the capability caution. */
    @Test
    fun `a direct pick over a convertible track announces the automatic adjustment`() {
        for (stream in listOf(audioStream(codec = "dts"), audioStream(codec = "aac"))) {
            val ui = playbackSettingsUi(
                audioStreams = listOf(stream),
                subtitles = null,
                selection = PlaybackSelection(mode = PlaybackMode.Direct),
                canPlayAudioMime = { _, _ -> false },
            )
            assertEquals(PlaybackMode.Direct, ui.selectedMode)
            assertTrue(ui.explanation.contains("adjusted automatically"))
            assertTrue(!ui.explanation.contains("can't play"))
        }
    }

    @Test
    fun `the adjustment announcement names the codec and stays off other modes and tracks`() {
        val direct = playbackSettingsUi(
            audioStreams = listOf(audioStream(codec = "dts")),
            subtitles = null,
            selection = PlaybackSelection(mode = PlaybackMode.Direct),
        )
        assertTrue(direct.explanation.contains("This track's DTS audio"))

        val remux = playbackSettingsUi(
            audioStreams = listOf(audioStream(codec = "dts")),
            subtitles = null,
            selection = PlaybackSelection(mode = PlaybackMode.Remux),
        )
        assertTrue(!remux.explanation.contains("adjusted automatically"))

        val stereoAac = playbackSettingsUi(
            audioStreams = listOf(audioStream(codec = "aac", channels = 2, channelLayout = "stereo")),
            subtitles = null,
            selection = PlaybackSelection(mode = PlaybackMode.Direct),
        )
        assertTrue(!stereoAac.explanation.contains("adjusted automatically"))
    }
}
