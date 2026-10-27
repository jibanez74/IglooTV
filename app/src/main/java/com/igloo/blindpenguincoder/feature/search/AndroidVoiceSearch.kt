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
        val listener = remember { RecognizerSession(appContext) { current.value(it) } }
        DisposableEffect(listener) { onDispose { listener.destroy() } }
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) listener.listen() else current.value(VoiceSearchEvent.Done(VoiceSearchOutcome.Failed(VoiceSearchFailure.Permission)))
        }
        return remember(listener, permission) {
            object : VoiceSearchSession {
                override fun start() {
                    val granted = appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    if (granted) listener.listen() else permission.launch(Manifest.permission.RECORD_AUDIO)
                }

                override fun cancel() = listener.cancel()
            }
        }
    }
}

/**
 * One recogniser, created on the first listen and destroyed with the composition. Listening is
 * reported from [listen] itself, not from `onReadyForSpeech`: the recogniser is live from the
 * press, so the pane must own Back and refuse a second press from that moment, not from the
 * callback that lands later. A cancel reports [VoiceSearchOutcome.Cancelled] itself —
 * `SpeechRecognizer.cancel` calls nothing back — and drops any late callback from the session
 * it ended; leaving the composition mid-session is a cancel too, so the pane never keeps a
 * listening state no recogniser backs.
 */
private class RecognizerSession(
    private val context: Context,
    private val onEvent: (VoiceSearchEvent) -> Unit,
) : RecognitionListener {
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    fun listen() {
        if (listening) return
        val recognizer = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(this)
            recognizer = it
        }
        listening = true
        onEvent(VoiceSearchEvent.Listening)
        recognizer.startListening(listenIntent(context.packageName))
    }

    fun cancel() {
        if (!listening) return
        listening = false
        recognizer?.cancel()
        onEvent(VoiceSearchEvent.Done(VoiceSearchOutcome.Cancelled))
    }

    fun destroy() {
        cancel()
        recognizer?.destroy()
        recognizer = null
    }

    private fun finish(outcome: VoiceSearchOutcome) {
        if (!listening) return
        listening = false
        onEvent(VoiceSearchEvent.Done(outcome))
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull { it.isNotBlank() }
        if (listening && text != null) onEvent(VoiceSearchEvent.Partial(text))
    }

    override fun onResults(results: Bundle?) =
        finish(voiceSearchResult(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)))

    override fun onError(error: Int) = finish(voiceSearchError(error))

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit
}

/** Free-form speech in the device's language, partials as they come, one final hypothesis. */
internal fun listenIntent(callingPackage: String): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, callingPackage)
