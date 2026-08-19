package com.igloo.blindpenguincoder.feature.movies

import com.igloo.blindpenguincoder.data.model.AudioStream
import com.igloo.blindpenguincoder.data.model.PlaybackMode
import com.igloo.blindpenguincoder.data.model.SqlNullString
import com.igloo.blindpenguincoder.data.model.Subtitle
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
    ) = AudioStream(
        id = id,
        movieId = 1,
        streamIndex = streamIndex,
        codec = "dts",
        bitRate = 0,
        channels = channels,
        channelLayout = sqlString(channelLayout),
        language = sqlString(language),
        title = sqlString(title),
        isDefault = isDefault,
        createdAt = "2026-01-01 00:00:00",
        updatedAt = "2026-01-01 00:00:00",
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
        createdAt = "2026-01-01 00:00:00",
        updatedAt = "2026-01-01 00:00:00",
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

    // --- channel layout ---

    @Test
    fun `channel layout names known layouts and counts`() {
        assertEquals("Mono", describeChannelLayout("mono", 1))
        assertEquals("Mono", describeChannelLayout(null, 1))
        assertEquals("Stereo", describeChannelLayout("stereo", 2))
        assertEquals("Stereo", describeChannelLayout(null, 2))
        assertEquals("5.1 surround", describeChannelLayout("5.1(side)", 6))
        assertEquals("7.1 surround", describeChannelLayout("7.1", 8))
        assertEquals("Quad", describeChannelLayout("quad", 4))
        assertEquals("Quad", describeChannelLayout("4.0", 4))
        assertEquals("Surround", describeChannelLayout(null, 6))
        assertEquals("3 channels", describeChannelLayout(null, 3))
    }

    @Test
    fun `channel layout wins over channel count`() {
        // An 8-channel stream whose layout says 5.1 is described by its layout.
        assertEquals("5.1 surround", describeChannelLayout("5.1", 8))
    }

    // --- language names ---

    @Test
    fun `language names resolve two and three letter codes`() {
        assertEquals("English", languageDisplayName("eng"))
        assertEquals("English", languageDisplayName("en"))
        assertEquals("Spanish", languageDisplayName("spa"))
        assertEquals("German", languageDisplayName("deu"))
        assertEquals("Chinese", languageDisplayName("zho"))
    }

    @Test
    fun `language names surface unknown codes instead of vanishing`() {
        assertEquals("XYZ", languageDisplayName("xyz"))
        assertEquals("Klingon", languageDisplayName("klingon"))
        assertNull(languageDisplayName(null))
        assertNull(languageDisplayName("  "))
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
        // The default audio is the second stream, which direct play cannot sound — the mode
        // resolves to Remux before the user has touched anything (see the forced-remux test).
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
    fun `image based subtitle rows are inert and cannot be the selection`() {
        val ui = playbackSettingsUi(
            audioStreams = listOf(audioStream()),
            subtitles = listOf(subtitle(id = 20, codec = "hdmv_pgs_subtitle")),
            selection = PlaybackSelection(subtitleStreamId = 20),
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
    fun `direct with a non-first audio track resolves to remux with the note`() {
        val ui = playbackSettingsUi(
            audioStreams = listOf(audioStream(id = 10, isDefault = true), audioStream(id = 11)),
            subtitles = emptyList(),
            selection = PlaybackSelection(mode = PlaybackMode.Direct, audioStreamId = 11),
        )
        assertEquals(PlaybackMode.Remux, ui.selectedMode)
        assertEquals(11L, ui.selectedAudioId)
        assertTrue(
            ui.explanation.contains(
                "Direct play always uses the first audio track, so playback switched to " +
                    "\"Original quality — audio adjusted.\"",
            ),
        )
    }

    @Test
    fun `direct with the first audio track stays direct without the note`() {
        val ui = playbackSettingsUi(
            audioStreams = listOf(audioStream(id = 10), audioStream(id = 11)),
            subtitles = emptyList(),
            selection = PlaybackSelection(mode = PlaybackMode.Direct, audioStreamId = 10),
        )
        assertEquals(PlaybackMode.Direct, ui.selectedMode)
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
}
