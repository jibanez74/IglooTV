package com.igloo.blindpenguincoder.feature.player

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.overMedia
import com.igloo.blindpenguincoder.core.ui.IglooButton
import com.igloo.blindpenguincoder.core.ui.IglooButtonVariant
import com.igloo.blindpenguincoder.core.ui.IglooIcons
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.iglooSurface
import com.igloo.blindpenguincoder.core.ui.rememberSpokenAccessibilityEnabled
import com.igloo.blindpenguincoder.core.ui.requestFocusSafely
import com.igloo.blindpenguincoder.feature.shared.readingStopTarget
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
    var reloadKey by remember { mutableIntStateOf(0) }
    val engine = remember(reloadKey) { engineFactory(context, request) }

    // The overlay owns wakefulness, not the engine: loading, paused, and error states still need
    // to remain visible. A real host background trip is handled separately by the lifecycle
    // observer below and continues to release playback.
    DisposableEffect(hostView) {
        val previousKeepScreenOn = hostView.keepScreenOn
        hostView.keepScreenOn = true
        onDispose { hostView.keepScreenOn = previousKeepScreenOn }
    }

    // The queue position and transport intent survive recreation; the intent starts armed
    // because Play Album is itself the play press, and a replacement engine after a background
    // trip must not autoplay music the standby had silenced.
    var lastTrackIndex by rememberSaveable { mutableIntStateOf(0) }
    var lastPositionSec by rememberSaveable { mutableStateOf(0.0) }
    var playWhenReadyIntent by rememberSaveable { mutableStateOf(true) }
    var lifecycleSilenced by remember(engine) { mutableStateOf(false) }
    var hostPausePending by remember(engine) { mutableStateOf(false) }
    var releasedForBackground by remember { mutableStateOf(false) }

    var state by remember(engine) {
        mutableStateOf(
            MusicPlayerState(
                playWhenReady = playWhenReadyIntent,
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
        playPauseRequester.requestFocusSafely()
        engine.events.collect { event ->
            state = state.onEvent(event)
            when (event) {
                is MusicPlayerEvent.Error -> if (event.unauthorized) unauthorized = true
                is MusicPlayerEvent.PlayWhenReadyChanged -> {
                    if (!lifecycleSilenced) {
                        playWhenReadyIntent = event.playWhenReady
                    }
                }
                is MusicPlayerEvent.TrackChanged -> {
                    lastTrackIndex = event.index
                    lastPositionSec = 0.0
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
            initialPlayWhenReady = playWhenReadyIntent,
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
    LaunchedEffect(controlsDisabled) {
        if (controlsDisabled) {
            retryRequester.requestFocus()
        } else {
            playPauseRequester.requestFocus()
        }
    }

    // No chrome-dismissal step: the chrome never hides, so Back always leaves.
    BackHandler { closeAndRelease() }

    // Lifecycle silence is not transport intent (the movie screen's contract): configuration
    // teardown preserves the saved choice for the replacement engine; a real background or
    // standby trip stays paused until an explicit Play.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(engine, lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    lifecycleSilenced = true
                    hostPausePending = true
                    engine.onHostPaused()
                }
                Lifecycle.Event.ON_STOP -> {
                    if (context.findHostActivity()?.isChangingConfigurations != true) {
                        playWhenReadyIntent = false
                        releasedForBackground = true
                        engine.release()
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (releasedForBackground) {
                        releasedForBackground = false
                        playWhenReadyIntent = false
                        lifecycleSilenced = false
                        hostPausePending = false
                        reloadKey += 1
                    } else {
                        if (hostPausePending) playWhenReadyIntent = false
                        hostPausePending = false
                        lifecycleSilenced = false
                        engine.onHostResumed()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
                    focusPlayPause = { playPauseRequester.requestFocus() },
                )
            }
            .semantics {
                paneTitle = "Music player"
                isTraversalGroup = true
            }
            .testTag("music_player"),
    ) {
        if (controlsDisabled) {
            PlayerErrorSurface(
                message = state.errorMessage ?: "The album could not be played.",
                actionText = if (unauthorized) "Close" else "Retry",
                actionSemanticLabel = if (unauthorized) {
                    "Close player"
                } else {
                    "Retry playing album"
                },
                actionRequester = retryRequester,
                // A revoked session cannot be retried into working — the host is already
                // revalidating; Close is the only honest action it has.
                onAction = if (unauthorized) {
                    closeAndRelease
                } else {
                    {
                        // Retry is a fresh, explicit Play intent after the failed engine's
                        // terminal boundary cleared every pending transport command.
                        playWhenReadyIntent = true
                        reloadKey += 1
                    }
                },
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

        // The transport announcement for a TalkBack focus parked anywhere: play state flips
        // driven by media keys are otherwise silent, and an auto-advance re-announces because
        // the sentence carries the new title. Polite — it narrates, it never interrupts.
        val playStateAnnouncement = musicPlayerAnnouncement(
            phase = state.phase,
            trackTitle = currentTrack?.title ?: "",
        )
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

/**
 * The album player's addition to the global key map, checked before [handlePlayerKey] because
 * that map spends SkipForward/SkipBackward on ±10s seeks — on an album the skip keys mean
 * tracks. Rewind/FastForward (and d-pad on the transport) keep the in-track seek. While the
 * error surface is up the keys are swallowed without acting, the shared map's own rule.
 */
private fun handleMusicSkipKey(
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

/** The always-visible chrome: top title bar, centered cover, bottom track block + transport. */
@Composable
private fun MusicPlayerChrome(
    request: MusicPlayRequest,
    state: MusicPlayerState,
    trackTitle: String,
    playPauseRequester: FocusRequester,
    backRequester: FocusRequester,
    metadataRequester: FocusRequester,
    spokenAccessibilityEnabled: Boolean,
    onBack: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekBy: (Double) -> Unit,
    onSkipToNext: () -> Unit,
    onSkipToPrevious: () -> Unit,
) {
    val layout = IglooTheme.layout
    val playing = state.playWhenReady
    val transportUpRequester = if (spokenAccessibilityEnabled) metadataRequester else backRequester
    val backDownRequester = if (spokenAccessibilityEnabled) metadataRequester else playPauseRequester

    Column(modifier = Modifier.fillMaxSize()) {
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
                semanticLabel = "Close player",
                restingFill = OVER_MEDIA_CONTROL_FILL,
                contentColor = Color.White,
                modifier = Modifier
                    .focusRequester(backRequester)
                    .focusProperties {
                        left = FocusRequester.Cancel
                        right = FocusRequester.Cancel
                        up = FocusRequester.Cancel
                        down = backDownRequester
                    }
                    .testTag("music_back"),
            )
            IglooText(
                text = request.albumTitle,
                style = IglooTheme.typography.titleMedium.overMedia(true),
                color = Color.White,
                maxLines = 1,
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = IglooTheme.spacing.md),
            contentAlignment = Alignment.Center,
        ) {
            MusicPlayerCover(coverUrl = request.coverUrl)
            val holdMessage = when (state.phase) {
                MusicPlayerPhase.Loading -> "Loading album…"
                MusicPlayerPhase.Buffering -> "Buffering…"
                else -> null
            }
            if (holdMessage != null) {
                IglooText(
                    text = holdMessage,
                    style = IglooTheme.typography.bodyLarge.overMedia(true),
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .testTag("music_loading"),
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
            // The visible pair carries one spoken sentence. TV TalkBack can reach it through a
            // focus-only reading stop; sighted users keep the direct transport-to-Back route.
            val positionLine = listOfNotNull(
                "Track ${state.currentTrackIndex + 1} of ${request.tracks.size}",
                request.artistName,
            ).joinToString(" · ")
            var metadataFocused by remember { mutableStateOf(false) }
            Column(
                modifier = if (spokenAccessibilityEnabled) {
                    Modifier.readingStopTarget(
                        tag = "music_track_metadata",
                        focused = metadataFocused,
                        requester = metadataRequester,
                        upRequester = backRequester,
                        downRequester = playPauseRequester,
                        onFocusChanged = { metadataFocused = it },
                        description = "$trackTitle. $positionLine.",
                        radius = IglooTheme.radius.lg,
                    )
                } else {
                    Modifier.clearAndSetSemantics {
                        contentDescription = "$trackTitle. $positionLine."
                    }
                },
            ) {
                IglooText(
                    text = trackTitle,
                    style = IglooTheme.typography.titleLarge.overMedia(true),
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier.testTag("music_track_title"),
                )
                IglooText(
                    text = positionLine,
                    style = IglooTheme.typography.bodyLarge.overMedia(true),
                    color = OVER_MEDIA_SECONDARY,
                    maxLines = 1,
                    modifier = Modifier.testTag("music_track_position"),
                )
            }
            PlayerSeekBar(
                currentTimeSec = state.currentTimeSec,
                durationSec = state.durationSec,
                seekTrackTag = "music_seek_track",
            )
            // Every control pins up through the conditional accessibility route and down to
            // Cancel; only the row's outer edges cancel sideways.
            fun Modifier.transportFocus(isFirst: Boolean = false, isLast: Boolean = false) = this
                .focusProperties {
                    if (isFirst) left = FocusRequester.Cancel
                    if (isLast) right = FocusRequester.Cancel
                    up = transportUpRequester
                    down = FocusRequester.Cancel
                }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(
                    IglooTheme.spacing.lg,
                    Alignment.CenterHorizontally,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TransportButton(
                    icon = IglooIcons.SkipPrevious,
                    label = "Previous track",
                    onClick = onSkipToPrevious,
                    modifier = Modifier
                        .transportFocus(isFirst = true)
                        .testTag("music_previous"),
                )
                TransportButton(
                    icon = IglooIcons.Rewind,
                    label = "Rewind 10 seconds",
                    onClick = { onSeekBy(-SEEK_STEP_SEC) },
                    modifier = Modifier
                        .transportFocus()
                        .testTag("music_rewind"),
                )
                TransportButton(
                    icon = if (playing) IglooIcons.Pause else IglooIcons.Play,
                    label = if (playing) "Pause" else "Play",
                    onClick = onTogglePlayPause,
                    modifier = Modifier
                        .focusRequester(playPauseRequester)
                        .transportFocus()
                        .testTag("music_play_pause"),
                )
                TransportButton(
                    icon = IglooIcons.FastForward,
                    label = "Forward 10 seconds",
                    onClick = { onSeekBy(SEEK_STEP_SEC) },
                    modifier = Modifier
                        .transportFocus()
                        .testTag("music_forward"),
                )
                TransportButton(
                    icon = IglooIcons.SkipNext,
                    label = "Next track",
                    onClick = onSkipToNext,
                    modifier = Modifier
                        .transportFocus(isLast = true)
                        .testTag("music_next"),
                )
            }
        }
    }
}

/** Decorative — the artwork repeats nothing the track block does not say, so TalkBack skips it. */
@Composable
private fun MusicPlayerCover(coverUrl: String?) {
    var imageFailed by remember(coverUrl) { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .aspectRatio(IglooTheme.layout.albumAspect)
            // The muted token pair would vanish over the player's literal black; the over-media
            // control fill is the section 3.2 ground for chrome on media.
            .iglooSurface(radius = IglooTheme.radius.lg, fill = OVER_MEDIA_CONTROL_FILL),
        contentAlignment = Alignment.Center,
    ) {
        if (coverUrl != null && !imageFailed) {
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onState = { state ->
                    if (state is AsyncImagePainter.State.Error) imageFailed = true
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Image(
                imageVector = IglooIcons.Music,
                contentDescription = null,
                colorFilter = ColorFilter.tint(OVER_MEDIA_TERTIARY),
                modifier = Modifier.size(IglooTheme.icons.lg),
            )
        }
    }
}
