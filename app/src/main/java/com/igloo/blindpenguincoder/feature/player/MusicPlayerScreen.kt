package com.igloo.blindpenguincoder.feature.player

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import com.igloo.blindpenguincoder.core.ui.rememberSpokenAccessibilityEnabled
import com.igloo.blindpenguincoder.core.ui.requestFocusSafely
import com.igloo.blindpenguincoder.playback.media3.MusicPlayerEngine
import com.igloo.blindpenguincoder.playback.model.MusicPlayRequest
import com.igloo.blindpenguincoder.playback.model.MusicPlayerEvent
import com.igloo.blindpenguincoder.playback.model.MusicPlayerPhase
import com.igloo.blindpenguincoder.playback.model.MusicPlayerState
import com.igloo.blindpenguincoder.playback.model.musicPlayerAnnouncement
import com.igloo.blindpenguincoder.playback.model.onEvent

/**
 * The music player: a full-screen in-tree overlay over the album art, the movie player's shape
 * with everything video-only removed. The chrome never hides — section 11.8 lets chrome rest
 * hidden only over a moving picture, and a cover is not one — so there is no auto-hide clock,
 * no reveal step, and Back always means leave. The host owns close and focus restoration;
 * playback stops with the screen (no background service in this pass).
 *
 * [engineFactory] has no default because the real engine needs the stream URLs and the
 * bearer-authenticated data-source factory, which are the host's to know.
 */
@Composable
fun MusicPlayerScreen(
    request: MusicPlayRequest,
    onClose: () -> Unit,
    engineFactory: (Context, MusicPlayRequest) -> MusicPlayerEngine,
    spokenAccessibilityEnabled: Boolean = rememberSpokenAccessibilityEnabled(),
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val hostView = LocalView.current
    // The intent starts armed: Play Album is itself the play press, so there is no paused
    // first frame to click through.
    val host = rememberPlayerHostLifecycle(initialPlayWhenReady = true)
    val engine = remember(host.reloadKey) { engineFactory(context, request) }

    // The overlay owns wakefulness, not the engine: loading, paused, and error states still need
    // to remain visible. A real host background trip is handled separately by the lifecycle
    // effect below and continues to release playback.
    DisposableEffect(hostView) {
        val previousKeepScreenOn = hostView.keepScreenOn
        hostView.keepScreenOn = true
        onDispose { hostView.keepScreenOn = previousKeepScreenOn }
    }

    // The queue position survives recreation alongside the transport intent [host] keeps.
    var lastTrackIndex by rememberSaveable { mutableIntStateOf(0) }
    var lastPositionSec by rememberSaveable { mutableStateOf(0.0) }

    var state by remember(engine) {
        mutableStateOf(
            MusicPlayerState(
                playWhenReady = host.playWhenReadyIntent,
                currentTrackIndex = lastTrackIndex,
                currentTimeSec = lastPositionSec,
                durationSec = request.tracks.getOrNull(lastTrackIndex)?.durationSec ?: 0.0,
            ),
        )
    }
    var unauthorized by remember(engine) { mutableStateOf(false) }

    val playPauseRequester = remember { FocusRequester() }
    val backRequester = remember { FocusRequester() }
    val metadataRequester = remember { FocusRequester() }
    val retryRequester = remember { FocusRequester() }

    DisposableEffect(engine) {
        onDispose { engine.release() }
    }

    val closeAndRelease: () -> Unit = {
        engine.release()
        onClose()
    }

    LaunchedEffect(engine) {
        engine.events.collect { event ->
            state = state.onEvent(event)
            when (event) {
                is MusicPlayerEvent.Error -> if (event.unauthorized) unauthorized = true
                is MusicPlayerEvent.PlayWhenReadyChanged ->
                    host.onEnginePlayWhenReady(event.playWhenReady)
                is MusicPlayerEvent.TrackChanged -> {
                    // The reducer's guard, for the saved playhead: setting the playlist is
                    // itself an item transition, so the queue's own start index arrives before
                    // a frame plays. Zeroing there would lose the position a Retry — or a
                    // return from background — resumes from, and no Time tick repairs it when
                    // the track never reaches READY.
                    val index = event.index.coerceAtLeast(0)
                    if (index != lastTrackIndex) {
                        lastTrackIndex = index
                        lastPositionSec = 0.0
                    }
                }
                is MusicPlayerEvent.Time -> lastPositionSec = event.currentSec
                else -> Unit
            }
        }
    }

    // Started once per engine. There is no resume prompt — Play Album starts at the top, and a
    // retry or recreation resumes this visit's own playhead.
    LaunchedEffect(engine) {
        engine.startPlayback(
            startTrackIndex = lastTrackIndex,
            startPositionSec = lastPositionSec,
            initialPlayWhenReady = host.playWhenReadyIntent,
        )
    }

    // Finishing the album exits like finishing a movie does; the host restores focus.
    LaunchedEffect(state.phase) {
        if (state.phase == MusicPlayerPhase.Ended) closeAndRelease()
    }

    val play = { engine.play() }
    val pause = { engine.pause() }
    val togglePlayPause = {
        if (state.playWhenReady) pause() else play()
    }
    val seekToSec = { targetSec: Double ->
        engine.seekTo(targetSec)
        state = state.onSeekApplied(targetSec)
    }
    val seekBy = { deltaSec: Double -> seekToSec(state.seekTarget(deltaSec)) }
    val controlsDisabled = state.phase == MusicPlayerPhase.Error
    val skipNext = { engine.skipToNext() }
    val skipPrevious = { engine.skipToPrevious() }

    // Entry focus: Play/Pause anchors the screen; the error surface moves it to its one action.
    // Keyed on the engine too, so a replacement (Retry, or a rebuild after a background trip)
    // re-anchors focus the way the first composition did.
    LaunchedEffect(engine, controlsDisabled) {
        if (controlsDisabled) {
            retryRequester.requestFocusSafely()
        } else {
            playPauseRequester.requestFocusSafely()
        }
    }

    // No chrome-dismissal step: the chrome never hides, so Back always leaves.
    BackHandler { closeAndRelease() }

    PlayerHostLifecycleEffect(
        host = host,
        engine = engine,
        onHostPaused = { engine.onHostPaused() },
        onHostResumed = { engine.onHostResumed() },
        onBackgroundRelease = { engine.release() },
    )

    val currentTrack = request.tracks.getOrNull(state.currentTrackIndex)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                handleMusicSkipKey(
                    event = event,
                    controlsDisabled = controlsDisabled,
                    skipToNext = skipNext,
                    skipToPrevious = skipPrevious,
                ) || handlePlayerKey(
                    event = event,
                    // The chrome never hides, so every d-pad press falls through to the
                    // focused control and only the media keys act globally.
                    chromeVisible = true,
                    controlsDisabled = controlsDisabled,
                    showChrome = {},
                    play = play,
                    pause = pause,
                    togglePlayPause = togglePlayPause,
                    seekBy = seekBy,
                    focusPlayPause = { playPauseRequester.requestFocusSafely() },
                )
            }
            .semantics {
                paneTitle = "Music player"
                isTraversalGroup = true
            }
            .testTag("music_player"),
    ) {
        if (controlsDisabled) {
            PlayerFailureSurface(
                message = state.errorMessage,
                unauthorized = unauthorized,
                mediaNoun = "album",
                actionRequester = retryRequester,
                onRetry = {
                    // Retry is a fresh, explicit Play intent after the failed engine's
                    // terminal boundary cleared every pending transport command.
                    host.rebuildEngine(playWhenReady = true)
                },
                onClose = closeAndRelease,
            )
        } else {
            MusicPlayerChrome(
                request = request,
                state = state,
                trackTitle = currentTrack?.title ?: "",
                playPauseRequester = playPauseRequester,
                backRequester = backRequester,
                metadataRequester = metadataRequester,
                spokenAccessibilityEnabled = spokenAccessibilityEnabled,
                onBack = closeAndRelease,
                onTogglePlayPause = togglePlayPause,
                onSeekBy = seekBy,
                onSkipToNext = skipNext,
                onSkipToPrevious = skipPrevious,
            )
        }

        // An auto-advance re-announces because the sentence carries the new track's title.
        PoliteAnnouncement(
            musicPlayerAnnouncement(
                phase = state.phase,
                trackTitle = currentTrack?.title ?: "",
            ),
        )
    }
}

/**
 * The album player's addition to the global key map, checked before [handlePlayerKey] because
 * that map spends SkipForward/SkipBackward on ±10s seeks — on an album the skip keys mean
 * tracks. Rewind/FastForward (and d-pad on the transport) keep the in-track seek. While the
 * error surface is up the keys are swallowed without acting, the shared map's own rule.
 */
internal fun handleMusicSkipKey(
    event: KeyEvent,
    controlsDisabled: Boolean,
    skipToNext: () -> Unit,
    skipToPrevious: () -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    return when (event.key) {
        Key.MediaNext, Key.MediaSkipForward -> {
            if (!controlsDisabled) skipToNext()
            true
        }

        Key.MediaPrevious, Key.MediaSkipBackward -> {
            if (!controlsDisabled) skipToPrevious()
            true
        }

        else -> false
    }
}
