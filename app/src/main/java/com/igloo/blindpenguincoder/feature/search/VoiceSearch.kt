package com.igloo.blindpenguincoder.feature.search

import androidx.compose.runtime.Composable

/**
 * What the Search pane needs from the platform's speech recogniser (docs/design-system.md
 * section 11.6): whether one answers at all, and a session that listens and reports. The pane
 * never sees a `SpeechRecognizer`; the view model never sees Android. [AndroidVoiceSearch] is
 * the one implementation, and the instrumented suites substitute a fake that delivers events by
 * hand.
 */
interface VoiceSearch {
    /**
     * A recogniser is installed on this device. False composes no button: a Fire TV has none,
     * and a control that announces an action it cannot perform is the section 12 failure. Read
     * once per process — nothing in-app changes it.
     */
    val available: Boolean

    /**
     * A session bound to this composition while its host is started: leaving either cancels it.
     * [onEvent] runs on the main thread, with the latest lambda the caller passed, as the
     * recogniser reports.
     */
    @Composable
    fun rememberSession(onEvent: (VoiceSearchEvent) -> Unit): VoiceSearchSession
}

interface VoiceSearchSession {
    /** Asks for the microphone the first time, then listens; reports through the session's events. */
    fun start()

    /** Stops listening and reports [VoiceSearchOutcome.Cancelled]. */
    fun cancel()
}

/** What a listening session reports, in order: listening, any partial phrases, then one outcome. */
sealed interface VoiceSearchEvent {
    /** The microphone is open: the user may speak. */
    data object Listening : VoiceSearchEvent

    /** The recogniser's current guess while the user is still speaking. */
    data class Partial(val text: String) : VoiceSearchEvent

    data class Done(val outcome: VoiceSearchOutcome) : VoiceSearchEvent
}

/** How a listening session ended, as the pane understands it. */
sealed interface VoiceSearchOutcome {
    /** The best hypothesis, trimmed: the query. */
    data class Recognized(val text: String) : VoiceSearchOutcome {
        init {
            require(text.isNotBlank()) { "A recognised phrase has words; nothing heard is NoMatch." }
        }
    }

    /** The user stopped it (the button again, or Back): nothing is said. */
    data object Cancelled : VoiceSearchOutcome

    /** The recogniser listened and understood nothing, or heard nothing at all. */
    data object NoMatch : VoiceSearchOutcome

    data class Failed(val reason: VoiceSearchFailure) : VoiceSearchOutcome
}

enum class VoiceSearchFailure { Audio, Client, Network, Server, Permission }

/**
 * `SpeechRecognizer`'s results list as an outcome: the first non-blank hypothesis, or no match.
 * Pure, so a JVM test covers it.
 */
fun voiceSearchResult(results: List<String>?): VoiceSearchOutcome =
    results.orEmpty().firstOrNull { it.isNotBlank() }
        ?.let { VoiceSearchOutcome.Recognized(it.trim()) }
        ?: VoiceSearchOutcome.NoMatch

/**
 * A `SpeechRecognizer.ERROR_*` code as an outcome. Pure, with the platform's constants spelled
 * out, because the JVM tests must not load `android.*` (docs/testing.md).
 */
fun voiceSearchError(code: Int): VoiceSearchOutcome = when (code) {
    ERROR_SPEECH_TIMEOUT, ERROR_NO_MATCH -> VoiceSearchOutcome.NoMatch
    ERROR_NETWORK_TIMEOUT, ERROR_NETWORK -> VoiceSearchOutcome.Failed(VoiceSearchFailure.Network)
    ERROR_AUDIO -> VoiceSearchOutcome.Failed(VoiceSearchFailure.Audio)
    ERROR_SERVER, ERROR_TOO_MANY_REQUESTS, ERROR_SERVER_DISCONNECTED ->
        VoiceSearchOutcome.Failed(VoiceSearchFailure.Server)
    ERROR_INSUFFICIENT_PERMISSIONS -> VoiceSearchOutcome.Failed(VoiceSearchFailure.Permission)
    // ERROR_CLIENT, ERROR_RECOGNIZER_BUSY and anything newer than this table.
    else -> VoiceSearchOutcome.Failed(VoiceSearchFailure.Client)
}

/**
 * The one-line header notice for a session that produced no query, in the library screens'
 * "Couldn't <action>: <why>" shape (feature/auth/ErrorMessages.kt); null when nothing needs
 * saying. A cancel is the user's own doing, and a recognised phrase is answered by the results.
 */
fun VoiceSearchOutcome.toNotice(): String? = when (this) {
    is VoiceSearchOutcome.Recognized, VoiceSearchOutcome.Cancelled -> null
    VoiceSearchOutcome.NoMatch -> "Didn't catch that. Try again."
    is VoiceSearchOutcome.Failed -> when (reason) {
        VoiceSearchFailure.Network -> "Couldn't search by voice: no network connection."
        VoiceSearchFailure.Server -> "Couldn't search by voice: the speech service didn't respond."
        VoiceSearchFailure.Audio -> "Couldn't search by voice: the microphone didn't work."
        VoiceSearchFailure.Client -> "Couldn't search by voice. Try again."
        VoiceSearchFailure.Permission -> "Voice search needs the microphone. Allow it when asked, or in the device's app settings."
    }
}

// SpeechRecognizer.ERROR_* by value; internal so the JVM test reads the same table.
internal const val ERROR_NETWORK_TIMEOUT = 1
internal const val ERROR_NETWORK = 2
internal const val ERROR_AUDIO = 3
internal const val ERROR_SERVER = 4
internal const val ERROR_CLIENT = 5
internal const val ERROR_SPEECH_TIMEOUT = 6
internal const val ERROR_NO_MATCH = 7
internal const val ERROR_RECOGNIZER_BUSY = 8
internal const val ERROR_INSUFFICIENT_PERMISSIONS = 9
internal const val ERROR_TOO_MANY_REQUESTS = 10
internal const val ERROR_SERVER_DISCONNECTED = 11
