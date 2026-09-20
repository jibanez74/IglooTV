package com.igloo.blindpenguincoder.playback.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsAudioConversionTest {

    private fun track(codec: String, channels: Int? = null, profile: String? = null) =
        PlayableAudioTrack(
            label = "Test",
            codec = codec,
            codecProfile = profile,
            channels = channels,
        )

    // --- the unreliable predicate ---

    @Test
    fun `every dts variant is unreliable at any channel count`() {
        assertTrue(isUnreliableHlsAudio("dts", 6))
        assertTrue(isUnreliableHlsAudio("dts", 2))
        assertTrue(isUnreliableHlsAudio("dts", null))
        assertTrue(isUnreliableHlsAudio(" DTS ", 8))
    }

    @Test
    fun `aac is unreliable only with proven multichannel audio`() {
        assertTrue(isUnreliableHlsAudio("aac", 6))
        assertTrue(isUnreliableHlsAudio("aac", 8))
        assertTrue(isUnreliableHlsAudio("AAC", 3))
        assertFalse(isUnreliableHlsAudio("aac", 2))
        assertFalse(isUnreliableHlsAudio("aac", 1))
        assertFalse(isUnreliableHlsAudio("aac", 0))
        assertFalse(isUnreliableHlsAudio("aac", null))
    }

    /** TrueHD/Atmos and friends pass through fine on the target setup and must stay untouched. */
    @Test
    fun `everything outside dts and multichannel aac is reliable`() {
        for (codec in listOf("truehd", "ac3", "eac3", "flac", "pcm_s24le", "opus", "mp3")) {
            assertFalse(codec, isUnreliableHlsAudio(codec, 8))
        }
        assertFalse(isUnreliableHlsAudio("exotic_new_codec", 6))
        assertFalse(isUnreliableHlsAudio(null, 6))
    }

    // --- the fixed conversion mapping ---

    @Test
    fun `dts family converts to e-ac3, multichannel aac to ac3`() {
        assertEquals(
            HlsAudioProfile.DolbyDigitalPlus,
            hlsAudioConversionFor(track("dts", channels = 6)),
        )
        assertEquals(
            HlsAudioProfile.DolbyDigitalPlus,
            hlsAudioConversionFor(track("dts", channels = 8, profile = "DTS-HD MA")),
        )
        assertEquals(
            HlsAudioProfile.DolbyDigitalPlus,
            hlsAudioConversionFor(track("dts", channels = 2, profile = "DTS-HD MA + DTS:X")),
        )
        assertEquals(HlsAudioProfile.DolbyDigital, hlsAudioConversionFor(track("aac", channels = 6)))
        assertEquals(HlsAudioProfile.DolbyDigital, hlsAudioConversionFor(track("aac", channels = 8)))
    }

    @Test
    fun `reliable tracks and missing tracks request no conversion`() {
        assertNull(hlsAudioConversionFor(null))
        assertNull(hlsAudioConversionFor(track("aac", channels = 2)))
        assertNull(hlsAudioConversionFor(track("truehd", channels = 8, profile = "Atmos")))
        assertNull(hlsAudioConversionFor(track("eac3", channels = 6)))
    }

    /** The wire vocabulary is fixed: only ac3/eac3, only 6 channels, never a bitrate. */
    @Test
    fun `the profiles carry exactly the server's wire values`() {
        assertEquals("ac3", HlsAudioProfile.DolbyDigital.audioCodec)
        assertEquals("eac3", HlsAudioProfile.DolbyDigitalPlus.audioCodec)
        for (profile in HlsAudioProfile.entries) {
            assertEquals(6, profile.audioChannels)
        }
    }
}
