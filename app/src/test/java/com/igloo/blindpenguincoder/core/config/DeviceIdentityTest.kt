package com.igloo.blindpenguincoder.core.config

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceIdentityTest {

    @Test
    fun `the user's tv name is sent trimmed`() {
        assertEquals("Living Room", serverDeviceName("  Living Room \n", "SHIELD Android TV"))
    }

    @Test
    fun `a missing or blank tv name falls back to the model`() {
        assertEquals("SHIELD Android TV", serverDeviceName(null, "SHIELD Android TV"))
        assertEquals("SHIELD Android TV", serverDeviceName(" \t ", "SHIELD Android TV"))
    }

    @Test
    fun `a long name is cut to the server's 100 utf-8 bytes`() {
        assertEquals("a".repeat(100), serverDeviceName("a".repeat(140), "model"))
    }

    @Test
    fun `a name of exactly 100 bytes survives whole`() {
        val hundredBytes = "é".repeat(50) // two bytes each

        assertEquals(hundredBytes, serverDeviceName(hundredBytes, "model"))
    }

    @Test
    fun `a character that straddles the limit is dropped, never split`() {
        assertEquals("a".repeat(99), serverDeviceName("a".repeat(99) + "é", "model"))
        // A surrogate pair is four bytes: half of it would be an unencodable lone surrogate.
        assertEquals("a".repeat(98), serverDeviceName("a".repeat(98) + "😀", "model"))
    }

    @Test
    fun `whitespace the cut leaves at the end is trimmed`() {
        assertEquals("a".repeat(99), serverDeviceName("a".repeat(99) + " b", "model"))
    }
}
