package com.igloo.blindpenguincoder.playback.model

import com.igloo.blindpenguincoder.data.model.PlaybackMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackGateTest {

    private fun gate(
        mode: PlaybackMode = PlaybackMode.Direct,
        codec: String? = "truehd",
        profile: String? = null,
        channels: Int? = null,
        label: String? = "English · 7.1 surround",
        canPlay: Boolean = true,
    ) = evaluatePlaybackGate(
        mode = mode,
        audioCodec = codec,
        audioCodecProfile = profile,
        audioChannels = channels,
        audioLabel = label,
        canPlayMime = { canPlay },
    )

    // --- mode gating ---

    @Test
    fun `direct with a playable codec proceeds`() {
        assertEquals(PlaybackGateResult.Proceed, gate())
    }

    /** The backend guarantees an HLS mux is playable, so only Direct can ever be refused. */
    @Test
    fun `every non-direct mode proceeds even with an unplayable source codec`() {
        for (mode in PlaybackMode.entries.filter { it != PlaybackMode.Direct }) {
            assertEquals(PlaybackGateResult.Proceed, gate(mode = mode, canPlay = false))
        }
    }

    // --- capability gating ---

    @Test
    fun `an unplayable codec is blocked naming the codec, the track, and the remux way out`() {
        val result = gate(canPlay = false) as PlaybackGateResult.Blocked
        assertTrue(result.message.contains("Dolby TrueHD"))
        assertTrue(result.message.contains("English · 7.1 surround"))
        assertTrue(result.message.contains("Playback Settings"))
        assertTrue(result.message.contains(playbackModeLabel(PlaybackMode.Remux)))
    }

    @Test
    fun `a missing track label leaves the message whole`() {
        val result = gate(canPlay = false, label = null) as PlaybackGateResult.Blocked
        assertTrue(result.message.contains("Dolby TrueHD audio track —"))
    }

    @Test
    fun `unknown or missing codecs proceed rather than block on ignorance`() {
        assertEquals(PlaybackGateResult.Proceed, gate(codec = null, canPlay = false))
        assertEquals(PlaybackGateResult.Proceed, gate(codec = "exotic_new_codec", canPlay = false))
    }

    /** Tracks the engine converts via Remux are never refused, capability notwithstanding. */
    @Test
    fun `codecs covered by the automatic audio conversion proceed even when unplayable`() {
        assertEquals(PlaybackGateResult.Proceed, gate(codec = "dts", canPlay = false))
        assertEquals(
            PlaybackGateResult.Proceed,
            gate(codec = "dts", profile = "DTS-HD MA", channels = 6, canPlay = false),
        )
        assertEquals(
            PlaybackGateResult.Proceed,
            gate(codec = "aac", channels = 6, canPlay = false),
        )
    }

    /** Stereo AAC is outside the conversion's scope; its gate outcome is capability's as ever. */
    @Test
    fun `stereo aac still answers to capability alone`() {
        assertEquals(PlaybackGateResult.Proceed, gate(codec = "aac", channels = 2))
        assertTrue(
            gate(codec = "aac", channels = 2, canPlay = false) is PlaybackGateResult.Blocked,
        )
    }

    // --- codec → MIME table ---

    @Test
    fun `codec names map to media3 mime types`() {
        assertEquals("audio/true-hd", audioCodecToMimeType("truehd", null))
        assertEquals("audio/ac3", audioCodecToMimeType("ac3", null))
        assertEquals("audio/eac3", audioCodecToMimeType("eac3", null))
        assertEquals("audio/eac3-joc", audioCodecToMimeType("eac3", "Dolby Digital Plus + JOC"))
        assertEquals("audio/vnd.dts", audioCodecToMimeType("dts", "DTS"))
        assertEquals("audio/vnd.dts.hd", audioCodecToMimeType("dts", "DTS-HD MA"))
        assertEquals(
            "audio/vnd.dts.uhd;profile=p2",
            audioCodecToMimeType("dts", "DTS-HD MA + DTS:X"),
        )
        assertEquals("audio/mp4a-latm", audioCodecToMimeType("aac", "LC"))
        assertEquals("audio/mpeg", audioCodecToMimeType("mp3", null))
        assertEquals("audio/flac", audioCodecToMimeType("flac", null))
        assertEquals("audio/opus", audioCodecToMimeType("opus", null))
        assertEquals("audio/vorbis", audioCodecToMimeType("vorbis", null))
        assertEquals("audio/raw", audioCodecToMimeType("pcm_s16le", null))
        assertEquals(null, audioCodecToMimeType("exotic_new_codec", null))
    }

    @Test
    fun `codec display names read like a person would say them`() {
        assertEquals("Dolby TrueHD", audioCodecDisplayName("truehd", null))
        assertEquals("Dolby Digital", audioCodecDisplayName("ac3", null))
        assertEquals("Dolby Digital Plus", audioCodecDisplayName("eac3", null))
        assertEquals(
            "Dolby Digital Plus with Atmos",
            audioCodecDisplayName("eac3", "Dolby Digital Plus + JOC"),
        )
        assertEquals("DTS", audioCodecDisplayName("dts", "DTS"))
        assertEquals("DTS-HD", audioCodecDisplayName("dts", "DTS-HD MA"))
        assertEquals("DTS:X", audioCodecDisplayName("dts", "DTS-HD MA + DTS:X"))
        assertEquals("AAC", audioCodecDisplayName("aac", null))
    }
}
