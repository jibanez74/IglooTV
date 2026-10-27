package com.igloo.blindpenguincoder.feature.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The recogniser's answers as the pane reads them: a hypothesis is the query trimmed, nothing
 * usable is no match, each error code is its own failure, and every outcome that produced no
 * query has the one line the header says — the error codes spelled out, as `SpeechRecognizer`
 * defines them.
 */
class VoiceSearchOutcomeTest {

    @Test
    fun `a hypothesis is recognised trimmed`() {
        assertEquals(VoiceSearchOutcome.Recognized("Casino Royale"), voiceSearchResult(listOf(" Casino Royale ")))
    }

    @Test
    fun `the first usable hypothesis wins over blank ones`() {
        assertEquals(VoiceSearchOutcome.Recognized("x"), voiceSearchResult(listOf("", "  ", "x")))
    }

    @Test
    fun `results with nothing usable are no match`() {
        assertEquals(VoiceSearchOutcome.NoMatch, voiceSearchResult(null))
        assertEquals(VoiceSearchOutcome.NoMatch, voiceSearchResult(emptyList()))
        assertEquals(VoiceSearchOutcome.NoMatch, voiceSearchResult(listOf("", " ")))
    }

    @Test
    fun `silence and no match are no match`() {
        assertEquals(VoiceSearchOutcome.NoMatch, voiceSearchError(ERROR_SPEECH_TIMEOUT))
        assertEquals(VoiceSearchOutcome.NoMatch, voiceSearchError(ERROR_NO_MATCH))
    }

    @Test
    fun `the recogniser's codes map to their failures`() {
        assertEquals(VoiceSearchOutcome.Failed(VoiceSearchFailure.Network), voiceSearchError(ERROR_NETWORK_TIMEOUT))
        assertEquals(VoiceSearchOutcome.Failed(VoiceSearchFailure.Network), voiceSearchError(ERROR_NETWORK))
        assertEquals(VoiceSearchOutcome.Failed(VoiceSearchFailure.Audio), voiceSearchError(ERROR_AUDIO))
        assertEquals(VoiceSearchOutcome.Failed(VoiceSearchFailure.Server), voiceSearchError(ERROR_SERVER))
        assertEquals(VoiceSearchOutcome.Failed(VoiceSearchFailure.Server), voiceSearchError(ERROR_TOO_MANY_REQUESTS))
        assertEquals(VoiceSearchOutcome.Failed(VoiceSearchFailure.Server), voiceSearchError(ERROR_SERVER_DISCONNECTED))
        assertEquals(VoiceSearchOutcome.Failed(VoiceSearchFailure.Permission), voiceSearchError(ERROR_INSUFFICIENT_PERMISSIONS))
        assertEquals(VoiceSearchOutcome.Failed(VoiceSearchFailure.Client), voiceSearchError(ERROR_CLIENT))
        assertEquals(VoiceSearchOutcome.Failed(VoiceSearchFailure.Client), voiceSearchError(ERROR_RECOGNIZER_BUSY))
        assertEquals(VoiceSearchOutcome.Failed(VoiceSearchFailure.Language), voiceSearchError(ERROR_LANGUAGE_NOT_SUPPORTED))
        assertEquals(VoiceSearchOutcome.Failed(VoiceSearchFailure.Language), voiceSearchError(ERROR_LANGUAGE_UNAVAILABLE))
    }

    @Test
    fun `an unknown code is a client failure`() {
        assertEquals(VoiceSearchOutcome.Failed(VoiceSearchFailure.Client), voiceSearchError(42))
    }

    @Test
    fun `a recognised phrase and a cancel say nothing`() {
        assertNull(VoiceSearchOutcome.Recognized("x").toNotice())
        assertNull(VoiceSearchOutcome.Cancelled.toNotice())
    }

    @Test
    fun `every failure has its line`() {
        assertEquals("Didn't catch that. Try again.", VoiceSearchOutcome.NoMatch.toNotice())
        assertEquals(
            "Couldn't search by voice: no network connection.",
            VoiceSearchOutcome.Failed(VoiceSearchFailure.Network).toNotice(),
        )
        assertEquals(
            "Couldn't search by voice: the speech service didn't respond.",
            VoiceSearchOutcome.Failed(VoiceSearchFailure.Server).toNotice(),
        )
        assertEquals(
            "Couldn't search by voice: the microphone didn't work.",
            VoiceSearchOutcome.Failed(VoiceSearchFailure.Audio).toNotice(),
        )
        assertEquals("Couldn't search by voice. Try again.", VoiceSearchOutcome.Failed(VoiceSearchFailure.Client).toNotice())
        assertEquals(
            "Couldn't search by voice: the speech service doesn't support this device's language.",
            VoiceSearchOutcome.Failed(VoiceSearchFailure.Language).toNotice(),
        )
        assertEquals(
            "Voice search needs the microphone. Allow it when asked, or in the device's app settings.",
            VoiceSearchOutcome.Failed(VoiceSearchFailure.Permission).toNotice(),
        )
    }

}
