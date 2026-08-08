package com.igloo.blindpenguincoder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the launch-splash gate in docs/design-system.md section 11.1.-1. */
class SplashGateTest {

    @Test
    fun `the splash stays up until the session has resolved`() {
        assertTrue(splashVisible(booted = false, holdElapsed = false))
        assertTrue(splashVisible(booted = false, holdElapsed = true))
    }

    @Test
    fun `a resolved session still waits out the hold`() {
        // The whole point of the hold: restore is a DataStore read and often beats the first
        // frames, which would reduce the brand moment to a flash.
        assertTrue(splashVisible(booted = true, holdElapsed = false))
    }

    @Test
    fun `both conditions together end the splash`() {
        assertFalse(splashVisible(booted = true, holdElapsed = true))
    }
}
