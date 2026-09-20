package com.igloo.blindpenguincoder.playback.hls

import com.igloo.blindpenguincoder.data.model.PlaybackMode
import org.junit.Assert.assertEquals
import org.junit.Test

class HlsPlaybackModeTest {

    @Test
    fun `a known effective profile resolves to the mode the server actually used`() {
        assertEquals(
            PlaybackMode.P1080Mbps8,
            effectivePlaybackMode(PlaybackMode.Remux, "1080p_8mbps"),
        )
    }

    @Test
    fun `an unknown effective profile preserves the requested recovery mode`() {
        assertEquals(
            PlaybackMode.Remux,
            effectivePlaybackMode(PlaybackMode.Remux, "future_server_profile"),
        )
    }
}
