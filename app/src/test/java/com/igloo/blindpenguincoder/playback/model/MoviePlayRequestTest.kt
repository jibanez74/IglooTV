package com.igloo.blindpenguincoder.playback.model

import com.igloo.blindpenguincoder.data.model.PlaybackMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoviePlayRequestTest {

    private fun request(
        audioTypeIndex: Int?,
        audioTracks: List<PlayableAudioTrack>,
    ) = MoviePlayRequest(
        movieId = 1,
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
}
