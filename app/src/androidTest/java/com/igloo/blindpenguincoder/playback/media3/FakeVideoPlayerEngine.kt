package com.igloo.blindpenguincoder.playback.media3

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.igloo.blindpenguincoder.playback.model.VideoPlayerEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * The test-side [VideoPlayerEngine]: records the commands the chrome sends and lets a test emit
 * the events a real ExoPlayer would, so player suites run without touching a decoder or the
 * network. The [FakeTrailerPlayerEngine] recipe, extended with the movie seam's start and
 * track-selection members.
 */
class FakeVideoPlayerEngine : VideoPlayerEngine {

    private val _events = MutableSharedFlow<VideoPlayerEvent>(replay = 64)
    override val events: SharedFlow<VideoPlayerEvent> = _events
    override var currentAudioTypeIndex: Int? = null
        private set
    override var currentSubtitleTypeIndex: Int? = null
        private set

    val audioTypeIndices = mutableMapOf<String, Int>()
    val subtitleTypeIndices = mutableMapOf<String, Int>()
    var surfaceCreateCount = 0
        private set

    val commands = mutableListOf<String>()

    /**
     * The transport traffic alone. Lifecycle notifications are excluded because registering a
     * lifecycle observer replays the current state — every screen mount logs a "hostResumed"
     * that transport assertions have no business being coupled to.
     */
    val playbackCommands: List<String>
        get() = commands.filterNot { it == "hostPaused" || it == "hostResumed" }

    var released = false
        private set
    var releaseCount = 0
        private set
    private var hostActive = true

    @Composable
    override fun VideoSurface(modifier: Modifier) {
        AndroidView(
            factory = { context ->
                surfaceCreateCount++
                View(context)
            },
            modifier = modifier,
        )
    }

    /**
     * Whether each [startPlayback] was told its position was a genuine resume point. Kept off
     * [commands] so the many tests that only care *that* playback started stay readable.
     */
    val startRewinds = mutableListOf<Boolean>()

    override fun startPlayback(
        startPositionSec: Double?,
        initialPlayWhenReady: Boolean,
        rewindOnResume: Boolean,
    ) {
        if (released) return
        startRewinds += rewindOnResume
        commands += "start:$startPositionSec:$initialPlayWhenReady"
        emit(VideoPlayerEvent.PlayWhenReadyChanged(initialPlayWhenReady))
    }

    override fun play() {
        if (released || !hostActive) return
        commands += "play"
        emit(VideoPlayerEvent.PlayWhenReadyChanged(true))
    }

    override fun pause() {
        if (released) return
        commands += "pause"
        emit(VideoPlayerEvent.PlayWhenReadyChanged(false))
    }

    override fun seekTo(seconds: Double) {
        if (released) return
        commands += "seek:$seconds"
    }

    override fun selectAudioTrack(optionId: String) {
        if (released) return
        audioTypeIndices[optionId]?.let { currentAudioTypeIndex = it }
        commands += "audio:$optionId"
    }

    override fun selectSubtitleTrack(optionId: String?) {
        if (released) return
        // Mirrors the real engine: null is an explicit "off"; an id that resolves to no wire
        // ordinal never corrupts the remembered choice.
        if (optionId == null) {
            currentSubtitleTypeIndex = null
        } else {
            subtitleTypeIndices[optionId]?.let { currentSubtitleTypeIndex = it }
        }
        commands += "subtitle:$optionId"
    }

    fun setCurrentTrackSelection(audioTypeIndex: Int?, subtitleTypeIndex: Int?) {
        currentAudioTypeIndex = audioTypeIndex
        currentSubtitleTypeIndex = subtitleTypeIndex
    }

    override fun selectPlaybackMode(optionId: String) {
        if (released) return
        commands += "quality:$optionId"
    }

    override fun onHostPaused() {
        if (released) return
        hostActive = false
        commands += "hostPaused"
        emit(VideoPlayerEvent.PlayWhenReadyChanged(false))
    }

    override fun onHostResumed() {
        if (released) return
        hostActive = true
        commands += "hostResumed"
    }

    override fun release() {
        if (released) return
        releaseCount++
        released = true
    }

    fun emit(event: VideoPlayerEvent) {
        check(_events.tryEmit(event)) { "event buffer full" }
    }
}
