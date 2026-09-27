package com.igloo.blindpenguincoder.playback.model

import com.igloo.blindpenguincoder.data.model.PlaybackMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlayRequestTest {

    private fun request(
        audioTypeIndex: Int?,
        audioTracks: List<PlayableAudioTrack>,
    ) = VideoPlayRequest(
        media = PlaybackMediaRef.Movie(1),
        title = "Heat",
        posterUrl = null,
        mimeType = "video/x-matroska",
        mode = PlaybackMode.Remux,
        audioTypeIndex = audioTypeIndex,
        subtitleTypeIndex = null,
        audioTracks = audioTracks,
        subtitleTracks = emptyList(),
        resumeAtSec = null,
        durationSec = null,
    )

    private fun track(label: String, isDefault: Boolean = false) =
        PlayableAudioTrack(label = label, codec = "aac", isDefault = isDefault)

    /** HLS requires a concrete `audio_track` ordinal whenever the movie has audio. */
    @Test
    fun `no explicit choice resolves to the default track's ordinal`() {
        val request = request(
            audioTypeIndex = null,
            audioTracks = listOf(track("English"), track("French", isDefault = true)),
        )
        assertEquals(1, request.effectiveAudioTypeIndex)
        assertEquals("French", request.selectedAudioTrack?.label)
    }

    @Test
    fun `no default flag falls back to the first track`() {
        val request = request(audioTypeIndex = null, audioTracks = listOf(track("English"), track("French")))
        assertEquals(0, request.effectiveAudioTypeIndex)
    }

    @Test
    fun `an explicit choice wins over the default flag`() {
        val request = request(
            audioTypeIndex = 0,
            audioTracks = listOf(track("English"), track("French", isDefault = true)),
        )
        assertEquals(0, request.effectiveAudioTypeIndex)
        assertEquals("English", request.selectedAudioTrack?.label)
    }

    @Test
    fun `a video-only movie has no audio ordinal at all`() {
        val request = request(audioTypeIndex = null, audioTracks = emptyList())
        assertNull(request.effectiveAudioTypeIndex)
        assertNull(request.selectedAudioTrack)
    }

    // --- subtitleRenderableInMode: whether the chosen ordinal can render on the source ---

    private fun subtitleRequest(vararg tracks: PlayableSubtitleTrack) =
        request(audioTypeIndex = null, audioTracks = emptyList())
            .copy(subtitleTracks = tracks.toList())

    private val mixed = subtitleRequest(
        PlayableSubtitleTrack(label = "English"),
        PlayableSubtitleTrack(label = "English · PGS", imageBased = true),
        PlayableSubtitleTrack(label = "Spanish"),
    )

    @Test
    fun `subtitles off never renders in any mode`() {
        assertFalse(mixed.subtitleRenderableInMode(typeIndex = null, hls = false))
        assertFalse(mixed.subtitleRenderableInMode(typeIndex = null, hls = true))
    }

    /** Direct trusts the container: the wire list can be degraded or empty. */
    @Test
    fun `direct renders any chosen ordinal even one the wire list does not know`() {
        assertTrue(mixed.subtitleRenderableInMode(typeIndex = 1, hls = false))
        assertTrue(mixed.subtitleRenderableInMode(typeIndex = 7, hls = false))
        assertTrue(subtitleRequest().subtitleRenderableInMode(typeIndex = 0, hls = false))
    }

    @Test
    fun `hls renders a text ordinal`() {
        assertTrue(mixed.subtitleRenderableInMode(typeIndex = 0, hls = true))
        assertTrue(mixed.subtitleRenderableInMode(typeIndex = 2, hls = true))
    }

    @Test
    fun `hls cannot render an image-based ordinal`() {
        assertFalse(mixed.subtitleRenderableInMode(typeIndex = 1, hls = true))
    }

    /** Same failure class as a bitmap ordinal: no matching sideloaded track exists. */
    @Test
    fun `hls cannot render an out-of-range ordinal`() {
        assertFalse(mixed.subtitleRenderableInMode(typeIndex = 7, hls = true))
    }
}
