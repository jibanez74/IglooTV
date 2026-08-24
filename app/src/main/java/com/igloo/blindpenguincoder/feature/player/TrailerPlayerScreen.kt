package com.igloo.blindpenguincoder.feature.player

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.igloo.blindpenguincoder.core.design.IglooMotion
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.iglooTween
import com.igloo.blindpenguincoder.core.design.overMedia
import com.igloo.blindpenguincoder.core.design.scaled
import com.igloo.blindpenguincoder.core.ui.IglooButton
import com.igloo.blindpenguincoder.core.ui.IglooButtonVariant
import com.igloo.blindpenguincoder.core.ui.IglooIcons
import com.igloo.blindpenguincoder.core.ui.IglooInlineError
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.focusRing
import com.igloo.blindpenguincoder.core.ui.formatSpokenTime
import com.igloo.blindpenguincoder.core.ui.formatTimecode
import com.igloo.blindpenguincoder.core.ui.pinnedToScreen
import com.igloo.blindpenguincoder.core.ui.progressFraction
import com.igloo.blindpenguincoder.playback.youtube.TrailerPhase
import com.igloo.blindpenguincoder.playback.youtube.TrailerPlayerEngine
import com.igloo.blindpenguincoder.playback.youtube.TrailerPlayerState
import com.igloo.blindpenguincoder.playback.youtube.onEvent
import kotlinx.coroutines.delay

/**
 * The trailer player (docs/design-system.md section 11.8.1): a full-screen in-tree overlay whose
 * video surface is the engine's WebView, full-bleed per section 2.5, with all input and chrome
 * in Compose — the surface itself can never take focus. No progress is saved and nothing talks
 * to the Igloo backend; Ended closes the player the same way Back does.
 *
 * The host owns close and focus restoration; this screen's own [BackHandler] handles only
 * chrome dismissal and otherwise calls [onClose]. [engineFactory] has no default because the
 * real engine needs the server origin, which is the host's to know.
 */
@Composable
fun TrailerPlayerScreen(
    videoKey: String,
    title: String,
    typeLabel: String,
    onClose: () -> Unit,
    engineFactory: (Context, String) -> TrailerPlayerEngine,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var reloadKey by remember { mutableIntStateOf(0) }
    val engine = remember(reloadKey) { engineFactory(context, videoKey) }
    var state by remember(engine) { mutableStateOf(TrailerPlayerState()) }
    var chromeVisible by remember { mutableStateOf(true) }
    // Bumped by any key press or chrome focus move; each bump restarts the auto-hide clock.
    var interactionTick by remember { mutableIntStateOf(0) }

    val playPauseRequester = remember { FocusRequester() }
    val backRequester = remember { FocusRequester() }
    val retryRequester = remember { FocusRequester() }

    DisposableEffect(engine) {
        onDispose { engine.release() }
    }

    LaunchedEffect(engine) {
        engine.events.collect { event -> state = state.onEvent(event) }
    }

    // The ready watchdog (section 11.8.1): a player that never reaches ready must resolve into
    // an error the user can act on, not an indefinite spinner. onWatchdogExpired no-ops once
    // ready, so the expiry needs no cancellation bookkeeping.
    LaunchedEffect(engine) {
        delay(READY_WATCHDOG_MS)
        state = state.onWatchdogExpired()
    }

    LaunchedEffect(state.phase) {
        when (state.phase) {
            TrailerPhase.Ended -> onClose()
            // Chrome may only rest hidden over a moving picture; any other phase surfaces it.
            TrailerPhase.Playing -> Unit
            else -> chromeVisible = true
        }
    }

    LaunchedEffect(chromeVisible, state.phase, interactionTick) {
        if (chromeVisible && state.phase == TrailerPhase.Playing) {
            delay(CHROME_HIDE_MS)
            chromeVisible = false
        }
    }

    val showChrome: () -> Unit = {
        chromeVisible = true
        interactionTick++
    }
    val play = {
        state = state.onPlayRequested()
        engine.play()
    }
    val pause = {
        state = state.onPauseRequested()
        engine.pause()
    }
    val togglePlayPause = {
        if (state.playWhenReady) pause() else play()
    }
    val seekBy = { deltaSec: Double ->
        val target = state.seekTarget(deltaSec)
        engine.seekTo(target)
        state = state.onSeekApplied(target)
    }

    // Entry focus: Play/Pause anchors the screen (section 11.4's primary-action pattern); the
    // error state moves it to Retry, the only control that state has.
    LaunchedEffect(state.phase == TrailerPhase.Error) {
        if (state.phase == TrailerPhase.Error) {
            retryRequester.requestFocus()
        } else {
            playPauseRequester.requestFocus()
        }
    }

    BackHandler {
        if (chromeVisible && state.phase == TrailerPhase.Playing) chromeVisible = false else onClose()
    }

    // Standby must silence playback; on return the user resumes deliberately, so only the
    // WebView's pipeline is resumed, not the video.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(engine, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> engine.onHostPaused()
                Lifecycle.Event.ON_RESUME -> engine.onHostResumed()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                handlePlayerKey(
                    event = event,
                    chromeVisible = chromeVisible,
                    controlsDisabled = state.phase == TrailerPhase.Error,
                    showChrome = showChrome,
                    play = play,
                    pause = pause,
                    togglePlayPause = togglePlayPause,
                    seekBy = seekBy,
                    focusPlayPause = { playPauseRequester.requestFocus() },
                )
            }
            .semantics {
                paneTitle = "Trailer player"
                isTraversalGroup = true
            }
            .testTag("trailer_player"),
    ) {
        // The video surface, full-bleed. Fakes have no surface; the black ground stands in.
        key(engine) {
            engine.surface()?.let { surfaceView ->
                AndroidView(
                    factory = { surfaceView },
                    modifier = Modifier
                        .fillMaxSize()
                        .focusProperties { canFocus = false },
                )
            }
        }

        when (state.phase) {
            TrailerPhase.Error -> PlayerErrorSurface(
                message = state.errorMessage ?: "The trailer could not be played.",
                actionText = "Retry",
                actionSemanticLabel = "Retry playing trailer",
                actionRequester = retryRequester,
                onAction = { reloadKey++ },
            )

            else -> PlayerChrome(
                title = title,
                typeLabel = typeLabel,
                state = state,
                visible = chromeVisible,
                playPauseRequester = playPauseRequester,
                backRequester = backRequester,
                onAnyControlFocused = { interactionTick++ },
                onBack = onClose,
                onTogglePlayPause = togglePlayPause,
                onSeekBy = seekBy,
            )
        }

        // The transport announcement for a TalkBack focus parked anywhere: play state flips are
        // otherwise silent when driven by media keys. Polite — it narrates, it never interrupts.
        val playStateAnnouncement = when (state.phase) {
            TrailerPhase.Playing -> "Playing: $title"
            TrailerPhase.Paused -> "Paused: $title"
            TrailerPhase.Loading -> "Loading trailer"
            else -> null
        }
        if (playStateAnnouncement != null) {
            Box(
                modifier = Modifier
                    .size(1.dp)
                    .clearAndSetSemantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = playStateAnnouncement
                    },
            )
        }
    }
}

/** The chrome: a top title bar and a bottom transport, each on its own section 3.2 scrim. */
@Composable
private fun PlayerChrome(
    title: String,
    typeLabel: String,
    state: TrailerPlayerState,
    visible: Boolean,
    playPauseRequester: FocusRequester,
    backRequester: FocusRequester,
    onAnyControlFocused: () -> Unit,
    onBack: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekBy: (Double) -> Unit,
) {
    val layout = IglooTheme.layout
    // Always composed, alpha-hidden: dismissal must not detach the focused control or reshuffle
    // TalkBack traversal. Under reduced motion iglooTween snaps.
    val chromeAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = iglooTween(IglooMotion.STANDARD_MS),
        label = "trailerChrome",
    )
    val playing = state.playWhenReady

    Column(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = chromeAlpha },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = SCRIM_STRENGTH),
                        1f to Color.Black.copy(alpha = 0f),
                    ),
                )
                .padding(
                    horizontal = layout.safeAreaHorizontal,
                    vertical = layout.safeAreaVertical,
                ),
            horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IglooButton(
                text = "Back",
                icon = IglooIcons.ArrowBack,
                onClick = onBack,
                variant = IglooButtonVariant.Ghost,
                semanticLabel = "Close trailer",
                restingFill = OVER_MEDIA_CONTROL_FILL,
                contentColor = Color.White,
                modifier = Modifier
                    .focusRequester(backRequester)
                    .onFocusChanged { if (it.isFocused) onAnyControlFocused() }
                    .focusProperties {
                        left = FocusRequester.Cancel
                        right = FocusRequester.Cancel
                        up = FocusRequester.Cancel
                        down = playPauseRequester
                    }
                    .testTag("trailer_back"),
            )
            Column {
                IglooText(
                    text = title,
                    style = IglooTheme.typography.titleMedium.overMedia(true),
                    color = Color.White,
                    maxLines = 1,
                )
                IglooText(
                    text = typeLabel,
                    style = IglooTheme.typography.label.overMedia(true),
                    color = OVER_MEDIA_SECONDARY,
                    maxLines = 1,
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            if (state.phase == TrailerPhase.Loading) {
                IglooText(
                    text = "Loading trailer…",
                    style = IglooTheme.typography.bodyLarge.overMedia(true),
                    color = OVER_MEDIA_SECONDARY,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .testTag("trailer_loading"),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0f),
                        1f to Color.Black.copy(alpha = SCRIM_STRENGTH),
                    ),
                )
                .padding(
                    horizontal = layout.safeAreaHorizontal,
                    vertical = layout.safeAreaVertical,
                ),
            verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.lg, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TransportButton(
                    icon = IglooIcons.Rewind,
                    label = "Rewind 10 seconds",
                    onClick = { onSeekBy(-SEEK_STEP_SEC) },
                    modifier = Modifier
                        .onFocusChanged { if (it.isFocused) onAnyControlFocused() }
                        .focusProperties {
                            left = FocusRequester.Cancel
                            up = backRequester
                            down = FocusRequester.Cancel
                        }
                        .testTag("trailer_rewind"),
                )
                TransportButton(
                    icon = if (playing) IglooIcons.Pause else IglooIcons.Play,
                    label = if (playing) "Pause" else "Play",
                    onClick = onTogglePlayPause,
                    modifier = Modifier
                        .focusRequester(playPauseRequester)
                        .onFocusChanged { if (it.isFocused) onAnyControlFocused() }
                        .focusProperties {
                            up = backRequester
                            down = FocusRequester.Cancel
                        }
                        .testTag("trailer_play_pause"),
                )
                TransportButton(
                    icon = IglooIcons.FastForward,
                    label = "Forward 10 seconds",
                    onClick = { onSeekBy(SEEK_STEP_SEC) },
                    modifier = Modifier
                        .onFocusChanged { if (it.isFocused) onAnyControlFocused() }
                        .focusProperties {
                            right = FocusRequester.Cancel
                            up = backRequester
                            down = FocusRequester.Cancel
                        }
                        .testTag("trailer_forward"),
                )
            }
            PlayerSeekBar(
                currentTimeSec = state.currentTimeSec,
                durationSec = state.durationSec,
                seekTrackTag = "trailer_seek_track",
            )
        }
    }
}

// The outer net, deliberately later than the engine's in-page API-load guard: that one names the
// narrower cause and must get to report first, this one catches everything else that stalls.
private const val READY_WATCHDOG_MS = 12_000L
