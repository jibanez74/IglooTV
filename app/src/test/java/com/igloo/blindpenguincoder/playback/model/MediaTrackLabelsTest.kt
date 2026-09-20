package com.igloo.blindpenguincoder.playback.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaTrackLabelsTest {

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
}
