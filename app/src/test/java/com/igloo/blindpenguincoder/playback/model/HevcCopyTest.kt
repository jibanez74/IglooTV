package com.igloo.blindpenguincoder.playback.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HevcCopyTest {

    private val capable = HevcDecoderInfo(hardware = true, main10 = true, uhd = true)

    @Test
    fun `a hardware main 10 uhd decoder on an hdr10 display declares hevc`() {
        assertTrue(canDeclareHevcCopy(listOf(capable), displaySupportsHdr10 = true))
    }

    @Test
    fun `one capable decoder is enough among weaker ones`() {
        val decoders = listOf(
            capable.copy(hardware = false),
            capable,
            capable.copy(uhd = false),
        )
        assertTrue(canDeclareHevcCopy(decoders, displaySupportsHdr10 = true))
    }

    @Test
    fun `a software-only decoder does not declare`() {
        assertFalse(canDeclareHevcCopy(listOf(capable.copy(hardware = false)), true))
    }

    @Test
    fun `a decoder without main 10 does not declare`() {
        assertFalse(canDeclareHevcCopy(listOf(capable.copy(main10 = false)), true))
    }

    @Test
    fun `a decoder that cannot take 4k frames does not declare`() {
        assertFalse(canDeclareHevcCopy(listOf(capable.copy(uhd = false)), true))
    }

    /** No single decoder holds all three; a split across decoders is not a capable decoder. */
    @Test
    fun `capabilities spread across decoders do not add up`() {
        val decoders = listOf(
            HevcDecoderInfo(hardware = true, main10 = false, uhd = true),
            HevcDecoderInfo(hardware = false, main10 = true, uhd = true),
        )
        assertFalse(canDeclareHevcCopy(decoders, true))
    }

    @Test
    fun `a display without hdr10 does not declare`() {
        assertFalse(canDeclareHevcCopy(listOf(capable), displaySupportsHdr10 = false))
    }

    @Test
    fun `no hevc decoder does not declare`() {
        assertFalse(canDeclareHevcCopy(emptyList(), displaySupportsHdr10 = true))
    }
}
