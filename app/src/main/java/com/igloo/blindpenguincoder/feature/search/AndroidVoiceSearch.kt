package com.igloo.blindpenguincoder.feature.search

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.LifecycleStartEffect
import java.util.Locale

/**
 * The platform's recogniser behind [VoiceSearch]: a `SpeechRecognizer` bound from the app's own
 * process, the way Leanback's search did. Not `ACTION_RECOGNIZE_SPEECH`'s overlay: on the
 * Shield's Android 11 build that overlay refuses to record for anyone who launches it (its
 * service logs "without RECORD_AUDIO permissions" even for the shell), while a recogniser bound
 * from the app is checked against the app's own grant and listens (2026-10-26). So the app
 * declares `RECORD_AUDIO` and asks for it on the first press; the pane draws the listening
 * state itself.
 */
class AndroidVoiceSearch(context: Context) : VoiceSearch {
    private val appContext: Context = context.applicationContext

    override val available: Boolean by lazy { SpeechRecognizer.isRecognitionAvailable(appContext) }

    @Composable
    override fun rememberSession(onEvent: (VoiceSearchEvent) -> Unit): VoiceSearchSession {
        val current = rememberUpdatedState(onEvent)
        val recognizer = remember { RecognizerSession(appContext) { current.value(it) } }
        // Leaving the composition ends the attempt, a microphone prompt still up included.
        DisposableEffect(recognizer) { onDispose { recognizer.cancel() } }
        // Home or another app stops the activity without disposing the pane: a phrase heard
        // then would rewrite the query, and pull focus, behind the user's back. The prompt is
        // spared: it may stop the activity itself, and its answer arrives once the app is back.
        LifecycleStartEffect(recognizer) { onStopOrDispose { recognizer.stopListening() } }
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), recognizer::onPermissionResult)
        return remember(recognizer, permission) {
            object : VoiceSearchSession {
                override fun start() {
                    val granted = appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    recognizer.start(granted) { permission.launch(Manifest.permission.RECORD_AUDIO) }
                }

                override fun cancel() = recognizer.cancel()
            }
        }
    }
}

/**
 * One recogniser per attempt, destroyed when the attempt ends however it ends. A reused
 * recogniser hands callbacks it had already queued for a cancelled attempt to whatever listener
 * is current when they dispatch, so a quick cancel-and-press-again could end the new attempt
 * with the old one's result or error; a destroyed recogniser drops them. Starting is reported
 * from [start], before any microphone prompt, so the pane owns Back, refuses another start and
 * cancels the attempt when something covers it, prompt or not. Only the active attempt's
 * `onReadyForSpeech` reports Listening, when the user can begin speaking. A cancel reports
 * [VoiceSearchOutcome.Cancelled] itself — `SpeechRecognizer.cancel` calls nothing back; leaving
 * the composition, or the app going to the background with the microphone open, is a cancel too,
 * so the pane never keeps an active phase no recogniser or prompt backs.
 */
private class RecognizerSession(
    private val context: Context,
    private val onEvent: (VoiceSearchEvent) -> Unit,
) {
    private var active: Attempt? = null

    /** Starting was reported and the microphone prompt is up: its answer decides what follows. */
    private var awaitingPermission = false

    fun start(granted: Boolean, askPermission: () -> Unit) {
        if (active != null || awaitingPermission) return
        onEvent(VoiceSearchEvent.Starting)
        if (granted) {
            listen()
        } else {
            awaitingPermission = true
            askPermission()
        }
    }

    /** The prompt's answer, dropped when the attempt was cancelled while it was up. */
    fun onPermissionResult(granted: Boolean) {
        if (!awaitingPermission) return
        awaitingPermission = false
        if (granted) listen() else onEvent(VoiceSearchEvent.Done(VoiceSearchOutcome.Failed(VoiceSearchFailure.Permission)))
    }

    private fun listen() {
        val attempt = Attempt(SpeechRecognizer.createSpeechRecognizer(context))
        active = attempt
        attempt.recognizer.startListening(listenIntent(context.packageName))
    }

    fun cancel() {
        if (active == null && !awaitingPermission) return
        awaitingPermission = false
        active?.let(::end)
        onEvent(VoiceSearchEvent.Done(VoiceSearchOutcome.Cancelled))
    }

    /** The app went to the background: an open microphone closes; a prompt keeps its answer coming. */
    fun stopListening() {
        if (active != null) cancel()
    }

    private fun end(attempt: Attempt) {
        active = null
        attempt.recognizer.destroy()
    }

    /** One press's recogniser and its listener; reports only while it is still the [active] one. */
    private inner class Attempt(val recognizer: SpeechRecognizer) : RecognitionListener {
        init {
            recognizer.setRecognitionListener(this)
        }

        private val current: Boolean get() = active === this

        private fun finish(outcome: VoiceSearchOutcome) {
            if (!current) return
            end(this)
            onEvent(VoiceSearchEvent.Done(outcome))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull { it.isNotBlank() }
            if (current && text != null) onEvent(VoiceSearchEvent.Partial(text))
        }

        override fun onResults(results: Bundle?) =
            finish(voiceSearchResult(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)))

        override fun onError(error: Int) = finish(voiceSearchError(error))

        override fun onReadyForSpeech(params: Bundle?) {
            if (current) onEvent(VoiceSearchEvent.Listening)
        }
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}

/** Free-form speech in the device's language, partials as they come, one final hypothesis. */
internal fun listenIntent(callingPackage: String): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, callingPackage)
