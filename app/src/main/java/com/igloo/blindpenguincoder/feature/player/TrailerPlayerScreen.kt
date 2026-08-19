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
    val togglePlayPause = {
        if (state.phase == TrailerPhase.Playing) engine.pause() else engine.play()
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
                    phase = state.phase,
                    showChrome = showChrome,
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
            TrailerPhase.Error -> PlayerError(
                message = state.errorMessage ?: "The trailer could not be played.",
                retryRequester = retryRequester,
                onRetry = { reloadKey++ },
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
    val playing = state.phase == TrailerPhase.Playing

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
            SeekBar(state = state)
        }
    }
}

/**
 * An icon-only transport control on the over-media black ground (section 3.2); one cleared
 * TalkBack node whose label is also its action.
 */
@Composable
private fun TransportButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(IglooTheme.sizes.controlHeight)
            .focusRing(
                focused = focused,
                radius = IglooTheme.radius.pill,
                fill = OVER_MEDIA_CONTROL_FILL,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                onClick(label = label) {
                    onClick()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            imageVector = icon,
            contentDescription = null,
            colorFilter = ColorFilter.tint(Color.White),
            modifier = Modifier.size(IglooTheme.icons.md),
        )
    }
}

/**
 * The progress strip and timecodes: presentation plus one cleared, non-focusable summary node.
 * Seeking is done with Left/Right on the transport, so the bar itself carries no action, and it
 * has no live region — a timer narrating every tick is section 12 noise.
 */
@Composable
private fun SeekBar(state: TrailerPlayerState) {
    val colors = IglooTheme.colors
    val fraction = progressFraction(state.currentTimeSec, state.durationSec)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription =
                    "${formatSpokenTime(state.currentTimeSec)} of ${formatSpokenTime(state.durationSec)}"
            },
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
    ) {
        // The resume strip's recipe: 4dp track, over-media literal ground, primary fill.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp.scaled())
                .background(Color.Black.copy(alpha = 0.40f))
                .testTag("trailer_seek_track"),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(colors.primary),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IglooText(
                text = formatTimecode(state.currentTimeSec),
                style = IglooTheme.typography.label.overMedia(true),
                color = Color.White,
            )
            IglooText(
                text = formatTimecode(state.durationSec),
                style = IglooTheme.typography.label.overMedia(true),
                color = OVER_MEDIA_TERTIARY,
            )
        }
    }
}

/** The details screen's error recipe: one pinned Retry, Assertive, Back handled by the host. */
@Composable
private fun PlayerError(
    message: String,
    retryRequester: FocusRequester,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(IglooTheme.layout.safeAreaHorizontal),
        contentAlignment = Alignment.Center,
    ) {
        IglooInlineError(
            message = message,
            actionText = "Retry",
            actionSemanticLabel = "Retry playing trailer",
            onAction = onRetry,
            actionModifier = Modifier
                .focusRequester(retryRequester)
                .pinnedToScreen(),
            modifier = Modifier.width(IglooTheme.layout.dialogWidth),
        )
    }
}

/**
 * The global key map (section 11.8.1). While the chrome is hidden every handled key is swallowed
 * — the invisible focused control must not activate — and reveals the chrome; media transport
 * keys act regardless of chrome state. Everything else falls through to the focused control.
 */
private fun handlePlayerKey(
    event: KeyEvent,
    chromeVisible: Boolean,
    phase: TrailerPhase,
    showChrome: () -> Unit,
    togglePlayPause: () -> Unit,
    seekBy: (Double) -> Unit,
    focusPlayPause: () -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (phase == TrailerPhase.Error) return false

    when (event.key) {
        Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
            togglePlayPause()
            showChrome()
            return true
        }

        Key.MediaRewind, Key.MediaSkipBackward -> {
            seekBy(-SEEK_STEP_SEC)
            showChrome()
            return true
        }

        Key.MediaFastForward, Key.MediaSkipForward -> {
            seekBy(SEEK_STEP_SEC)
            showChrome()
            return true
        }

        else -> Unit
    }

    if (!chromeVisible) {
        return when (event.key) {
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                togglePlayPause()
                showChrome()
                true
            }

            Key.DirectionLeft -> {
                seekBy(-SEEK_STEP_SEC)
                showChrome()
                true
            }

            Key.DirectionRight -> {
                seekBy(SEEK_STEP_SEC)
                showChrome()
                true
            }

            Key.DirectionUp, Key.DirectionDown -> {
                showChrome()
                focusPlayPause()
                true
            }

            else -> false
        }
    }

    // Chrome visible: the key falls through to the focused control, but still counts as
    // interaction so the auto-hide clock restarts.
    showChrome()
    return false
}

// The section 3.2 over-media literals, which deliberately do not track the theme: the chrome sits
// on video, not on a surface. The seek track keeps the progress-strip ground (0.40f) instead.
private val OVER_MEDIA_CONTROL_FILL = Color.Black.copy(alpha = 0.45f)
private val OVER_MEDIA_SECONDARY = Color.White.copy(alpha = 0.85f)
private val OVER_MEDIA_TERTIARY = Color.White.copy(alpha = 0.75f)

private const val SEEK_STEP_SEC = 10.0
private const val CHROME_HIDE_MS = 4_000L

// The outer net, deliberately later than the engine's in-page API-load guard: that one names the
// narrower cause and must get to report first, this one catches everything else that stalls.
private const val READY_WATCHDOG_MS = 12_000L
private const val SCRIM_STRENGTH = 0.70f
