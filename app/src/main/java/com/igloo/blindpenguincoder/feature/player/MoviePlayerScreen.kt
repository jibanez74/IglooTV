package com.igloo.blindpenguincoder.feature.player

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
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
import com.igloo.blindpenguincoder.core.ui.IglooButton
import com.igloo.blindpenguincoder.core.ui.IglooButtonVariant
import com.igloo.blindpenguincoder.core.ui.IglooIcons
import com.igloo.blindpenguincoder.core.ui.IglooRadioRow
import com.igloo.blindpenguincoder.core.ui.IglooScrim
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.formatSpokenTime
import com.igloo.blindpenguincoder.core.ui.formatTimecode
import com.igloo.blindpenguincoder.core.ui.iglooSurface
import com.igloo.blindpenguincoder.playback.media3.MoviePlayerEngine
import com.igloo.blindpenguincoder.playback.model.MoviePlayRequest
import com.igloo.blindpenguincoder.playback.model.MoviePlayerEvent
import com.igloo.blindpenguincoder.playback.model.MoviePlayerPhase
import com.igloo.blindpenguincoder.playback.model.MoviePlayerState
import com.igloo.blindpenguincoder.playback.model.TrackOption
import com.igloo.blindpenguincoder.playback.model.onEvent
import kotlinx.coroutines.delay

/**
 * The movie player (docs/design-system.md section 11.8): a full-screen in-tree overlay whose
 * video surface is the engine's view, with all input and chrome in Compose — the surface itself
 * can never take focus. The trailer player's shape throughout; what this screen adds is the
 * resume prompt, the in-player track menus, and the progress session on [viewModel].
 *
 * The host owns close and focus restoration; this screen's own [BackHandler] handles chrome
 * dismissal and otherwise calls [onClose]. [engineFactory] has no default because the real
 * engine needs the stream URL and data-source factory, which are the host's to know.
 */
@Composable
fun MoviePlayerScreen(
    request: MoviePlayRequest,
    viewModel: MoviePlayerViewModel,
    onClose: () -> Unit,
    engineFactory: (Context, MoviePlayRequest) -> MoviePlayerEngine,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var reloadKey by remember { mutableIntStateOf(0) }
    val engine = remember(reloadKey) { engineFactory(context, request) }

    // The resume decision and playback position survive recreation: a movie restarting at zero
    // because the activity recreated is an hour of the user's place lost, a trade the trailer
    // accepts but this screen must not. `chosenStartSec` is where the decision said to start;
    // `lastPositionSec` overrides it once real ticks arrive (recreation, error retry). Zero
    // stands in for "the beginning" so the saved state never holds a null.
    var resumeDecided by rememberSaveable { mutableStateOf(request.resumeAtSec == null) }
    var chosenStartSec by rememberSaveable { mutableStateOf(request.resumeAtSec ?: 0.0) }
    var lastPositionSec by rememberSaveable { mutableStateOf(0.0) }
    var lastDurationSec by rememberSaveable { mutableStateOf(request.durationSec ?: 0.0) }

    var state by remember(engine) {
        mutableStateOf(
            MoviePlayerState(
                phase = if (resumeDecided) MoviePlayerPhase.Loading else MoviePlayerPhase.AwaitingResume,
            ),
        )
    }
    var unauthorized by remember(engine) { mutableStateOf(false) }
    var chromeVisible by remember { mutableStateOf(true) }
    var trackMenu by remember { mutableStateOf<TrackMenu?>(null) }
    // Bumped by any key press or chrome focus move; each bump restarts the auto-hide clock.
    var interactionTick by remember { mutableIntStateOf(0) }

    val playPauseRequester = remember { FocusRequester() }
    val backRequester = remember { FocusRequester() }
    val retryRequester = remember { FocusRequester() }
    val audioButtonRequester = remember { FocusRequester() }
    val subtitlesButtonRequester = remember { FocusRequester() }

    DisposableEffect(engine) {
        onDispose { engine.release() }
    }

    // The progress session: one per screen visit, and the exit save fires on *any* unmount —
    // Back, Ended, error Close, or the host tearing the overlay down — because the ViewModel
    // outlives this composable and gives the bounded final write somewhere to land.
    LaunchedEffect(Unit) { viewModel.startSession(request.movieId) }
    DisposableEffect(Unit) {
        onDispose { viewModel.endSession(lastPositionSec, lastDurationSec) }
    }

    LaunchedEffect(engine) {
        engine.events.collect { event ->
            val next = state.onEvent(event)
            state = next
            when (event) {
                is MoviePlayerEvent.Error -> if (event.unauthorized) unauthorized = true
                is MoviePlayerEvent.Time -> {
                    lastPositionSec = event.currentSec
                    if (event.durationSec > 0.0) lastDurationSec = event.durationSec
                    viewModel.onTick(
                        positionSec = event.currentSec,
                        durationSec = lastDurationSec,
                        isPlaying = next.phase == MoviePlayerPhase.Playing,
                    )
                }
                // Finishing means full progress: the exit save must record the end, not the
                // last tick before it.
                is MoviePlayerEvent.Ended -> lastPositionSec = next.currentTimeSec
                else -> Unit
            }
        }
    }

    // Started once per engine, the moment the resume decision exists. A retry or recreation
    // resumes from the last real position; before any tick that falls back to the decision.
    LaunchedEffect(engine, resumeDecided) {
        if (resumeDecided) {
            engine.startPlayback(
                lastPositionSec.takeIf { it > 0.0 } ?: chosenStartSec.takeIf { it > 0.0 },
            )
        }
    }

    LaunchedEffect(state.phase) {
        when (state.phase) {
            MoviePlayerPhase.Ended -> onClose()
            // Chrome may only rest hidden over a moving picture; any other phase surfaces it.
            MoviePlayerPhase.Playing -> Unit
            else -> chromeVisible = true
        }
    }

    LaunchedEffect(chromeVisible, state.phase, interactionTick, trackMenu) {
        if (chromeVisible && state.phase == MoviePlayerPhase.Playing && trackMenu == null) {
            delay(CHROME_HIDE_MS)
            chromeVisible = false
        }
    }

    val showChrome: () -> Unit = {
        chromeVisible = true
        interactionTick++
    }
    val togglePlayPause = {
        if (state.phase == MoviePlayerPhase.Playing) engine.pause() else engine.play()
    }
    val seekBy = { deltaSec: Double ->
        val target = state.seekTarget(deltaSec)
        engine.seekTo(target)
        state = state.onSeekApplied(target)
    }

    // Entry focus: Play/Pause anchors the screen; the error surface moves it to its one action,
    // and the resume prompt and track menus request their own on composition.
    val focusAnchor = when (state.phase) {
        MoviePlayerPhase.Error -> FocusAnchor.ErrorAction
        MoviePlayerPhase.AwaitingResume -> FocusAnchor.Modal
        else -> FocusAnchor.Transport
    }
    LaunchedEffect(focusAnchor) {
        when (focusAnchor) {
            FocusAnchor.ErrorAction -> retryRequester.requestFocus()
            FocusAnchor.Transport -> playPauseRequester.requestFocus()
            FocusAnchor.Modal -> Unit
        }
    }

    // The track menus and the resume prompt register their own handlers below this one, so this
    // fires only with the plain chrome up.
    BackHandler {
        if (chromeVisible && state.phase == MoviePlayerPhase.Playing) chromeVisible = false else onClose()
    }

    // Standby must silence playback; on return the user resumes deliberately.
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

    val modalUp = state.phase == MoviePlayerPhase.AwaitingResume || trackMenu != null
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                handlePlayerKey(
                    event = event,
                    chromeVisible = chromeVisible,
                    controlsDisabled = state.phase == MoviePlayerPhase.Error || modalUp,
                    showChrome = showChrome,
                    togglePlayPause = togglePlayPause,
                    seekBy = seekBy,
                    focusPlayPause = { playPauseRequester.requestFocus() },
                )
            }
            .semantics {
                paneTitle = "Movie player"
                isTraversalGroup = true
            }
            .testTag("movie_player"),
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

        if (state.phase == MoviePlayerPhase.Error) {
            PlayerErrorSurface(
                message = state.errorMessage ?: "The movie could not be played.",
                actionText = if (unauthorized) "Close" else "Retry",
                actionSemanticLabel = if (unauthorized) {
                    "Close player"
                } else {
                    "Retry playing movie"
                },
                actionRequester = retryRequester,
                // A revoked session cannot be retried into working — the host is already
                // revalidating; Close is the only honest action it has.
                onAction = if (unauthorized) onClose else ({ reloadKey += 1 }),
            )
        } else {
            MoviePlayerChrome(
                title = request.title,
                state = state,
                visible = chromeVisible,
                playPauseRequester = playPauseRequester,
                backRequester = backRequester,
                audioButtonRequester = audioButtonRequester,
                subtitlesButtonRequester = subtitlesButtonRequester,
                onAnyControlFocused = { interactionTick++ },
                onBack = onClose,
                onTogglePlayPause = togglePlayPause,
                onSeekBy = seekBy,
                onOpenTrackMenu = { trackMenu = it },
            )
        }

        when (state.phase) {
            MoviePlayerPhase.AwaitingResume -> ResumePrompt(
                resumeAtSec = requireNotNull(request.resumeAtSec),
                onResume = {
                    resumeDecided = true
                    state = state.onResumeChosen()
                },
                onStartOver = {
                    chosenStartSec = 0.0
                    resumeDecided = true
                    state = state.onResumeChosen()
                },
                onClose = onClose,
            )
            else -> Unit
        }

        trackMenu?.let { menu ->
            val closeMenu: () -> Unit = {
                trackMenu = null
                when (menu) {
                    TrackMenu.Audio -> audioButtonRequester.requestFocus()
                    TrackMenu.Subtitles -> subtitlesButtonRequester.requestFocus()
                }
            }
            when (menu) {
                TrackMenu.Audio -> TrackMenuDialog(
                    title = "Audio",
                    options = state.audioOptions,
                    noneRow = null,
                    onSelect = { id -> engine.selectAudioTrack(requireNotNull(id)) },
                    onDismiss = closeMenu,
                )
                TrackMenu.Subtitles -> TrackMenuDialog(
                    title = "Subtitles",
                    options = state.subtitleOptions,
                    noneRow = "None",
                    onSelect = { id -> engine.selectSubtitleTrack(id) },
                    onDismiss = closeMenu,
                )
            }
        }

        // The transport announcement for a TalkBack focus parked anywhere: play state flips are
        // otherwise silent when driven by media keys. Polite — it narrates, it never interrupts.
        val playStateAnnouncement = when (state.phase) {
            MoviePlayerPhase.Playing -> "Playing: ${request.title}"
            MoviePlayerPhase.Paused -> "Paused: ${request.title}"
            MoviePlayerPhase.Loading -> "Loading movie"
            MoviePlayerPhase.Buffering -> "Buffering"
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

/** The two in-player track menus; which one is up is plain screen state. */
private enum class TrackMenu { Audio, Subtitles }

/** Where entry focus belongs for the current phase; modals place their own. */
private enum class FocusAnchor { Transport, ErrorAction, Modal }

/** The chrome: a top title bar and a bottom transport, each on its own section 3.2 scrim. */
@Composable
private fun MoviePlayerChrome(
    title: String,
    state: MoviePlayerState,
    visible: Boolean,
    playPauseRequester: FocusRequester,
    backRequester: FocusRequester,
    audioButtonRequester: FocusRequester,
    subtitlesButtonRequester: FocusRequester,
    onAnyControlFocused: () -> Unit,
    onBack: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekBy: (Double) -> Unit,
    onOpenTrackMenu: (TrackMenu) -> Unit,
) {
    val layout = IglooTheme.layout
    // Always composed, alpha-hidden: dismissal must not detach the focused control or reshuffle
    // TalkBack traversal. Under reduced motion iglooTween snaps.
    val chromeAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = iglooTween(IglooMotion.STANDARD_MS),
        label = "moviePlayerChrome",
    )
    val playing = state.phase == MoviePlayerPhase.Playing
    // A one-track menu is a choice with no alternatives; the subtitle menu earns its place with
    // a single track because "None" is its second option.
    val showAudio = state.audioOptions.size >= 2
    val showSubtitles = state.subtitleOptions.isNotEmpty()

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
                semanticLabel = "Close player",
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
                    .testTag("movie_back"),
            )
            IglooText(
                text = title,
                style = IglooTheme.typography.titleMedium.overMedia(true),
                color = Color.White,
                maxLines = 1,
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            val holdMessage = when (state.phase) {
                MoviePlayerPhase.Loading -> "Loading movie…"
                MoviePlayerPhase.Buffering -> "Buffering…"
                else -> null
            }
            if (holdMessage != null) {
                IglooText(
                    text = holdMessage,
                    style = IglooTheme.typography.bodyLarge.overMedia(true),
                    color = OVER_MEDIA_SECONDARY,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .testTag("movie_loading"),
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
            // Every control pins up to Back and down to Cancel; only the row's outer edges
            // cancel sideways, so the track-menu buttons stay one Right press away.
            fun Modifier.transportFocus(isFirst: Boolean = false, isLast: Boolean = false) = this
                .onFocusChanged { if (it.isFocused) onAnyControlFocused() }
                .focusProperties {
                    if (isFirst) left = FocusRequester.Cancel
                    if (isLast) right = FocusRequester.Cancel
                    up = backRequester
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
                    icon = IglooIcons.Rewind,
                    label = "Rewind 10 seconds",
                    onClick = { onSeekBy(-SEEK_STEP_SEC) },
                    modifier = Modifier
                        .transportFocus(isFirst = true)
                        .testTag("movie_rewind"),
                )
                TransportButton(
                    icon = if (playing) IglooIcons.Pause else IglooIcons.Play,
                    label = if (playing) "Pause" else "Play",
                    onClick = onTogglePlayPause,
                    modifier = Modifier
                        .focusRequester(playPauseRequester)
                        .transportFocus()
                        .testTag("movie_play_pause"),
                )
                TransportButton(
                    icon = IglooIcons.FastForward,
                    label = "Forward 10 seconds",
                    onClick = { onSeekBy(SEEK_STEP_SEC) },
                    modifier = Modifier
                        .transportFocus(isLast = !showAudio && !showSubtitles)
                        .testTag("movie_forward"),
                )
                // Text, not icons: IglooIcons has no audio/CC glyphs, and section 5.4 prefers a
                // readable word over a novel symbol at TV distance.
                if (showAudio) {
                    IglooButton(
                        text = "Audio",
                        onClick = { onOpenTrackMenu(TrackMenu.Audio) },
                        variant = IglooButtonVariant.Ghost,
                        semanticLabel = "Audio track",
                        restingFill = OVER_MEDIA_CONTROL_FILL,
                        contentColor = Color.White,
                        modifier = Modifier
                            .focusRequester(audioButtonRequester)
                            .transportFocus(isLast = !showSubtitles)
                            .testTag("movie_audio"),
                    )
                }
                if (showSubtitles) {
                    IglooButton(
                        text = "Subtitles",
                        onClick = { onOpenTrackMenu(TrackMenu.Subtitles) },
                        variant = IglooButtonVariant.Ghost,
                        semanticLabel = "Subtitles",
                        restingFill = OVER_MEDIA_CONTROL_FILL,
                        contentColor = Color.White,
                        modifier = Modifier
                            .focusRequester(subtitlesButtonRequester)
                            .transportFocus(isLast = true)
                            .testTag("movie_subtitles"),
                    )
                }
            }
            PlayerSeekBar(
                currentTimeSec = state.currentTimeSec,
                durationSec = state.durationSec,
                seekTrackTag = "movie_seek_track",
            )
        }
    }
}

/**
 * The resume decision (section 11.8): three outcomes — Resume, Start over, and Back leaving the
 * player — which is why this is not the two-outcome [IglooConfirmDialog] recipe. In-tree,
 * scrimmed, focus trapped between the two buttons, entry on Resume.
 */
@Composable
private fun ResumePrompt(
    resumeAtSec: Double,
    onResume: () -> Unit,
    onStartOver: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)

    var visible by remember { mutableStateOf(false) }
    val reveal by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = iglooTween(IglooMotion.STANDARD_MS),
        label = "resumePromptReveal",
    )
    LaunchedEffect(Unit) { visible = true }

    val resumeRequester = remember { FocusRequester() }
    val startOverRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { resumeRequester.requestFocus() }

    IglooScrim(
        modifier = Modifier.graphicsLayer { alpha = reveal },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(IglooTheme.layout.dialogWidth)
                .iglooSurface(radius = IglooTheme.radius.xl, fill = IglooTheme.colors.card)
                .padding(IglooTheme.spacing.xl)
                .semantics {
                    paneTitle = "Resume playback"
                    isTraversalGroup = true
                }
                .testTag("movie_resume_prompt"),
            verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.lg),
        ) {
            IglooText(
                text = "Resume from ${formatTimecode(resumeAtSec)}?",
                style = IglooTheme.typography.titleMedium,
                color = IglooTheme.colors.cardForeground,
                modifier = Modifier.semantics { heading() },
            )
            IglooButton(
                text = "Resume",
                onClick = onResume,
                semanticLabel = "Resume from ${formatSpokenTime(resumeAtSec)}",
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(resumeRequester)
                    .focusProperties {
                        left = FocusRequester.Cancel
                        right = FocusRequester.Cancel
                        up = FocusRequester.Cancel
                        down = startOverRequester
                    }
                    .testTag("movie_resume"),
            )
            IglooButton(
                text = "Start over",
                onClick = onStartOver,
                variant = IglooButtonVariant.Ghost,
                semanticLabel = "Start over from the beginning",
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(startOverRequester)
                    .focusProperties {
                        left = FocusRequester.Cancel
                        right = FocusRequester.Cancel
                        up = resumeRequester
                        down = FocusRequester.Cancel
                    }
                    .testTag("movie_start_over"),
            )
        }
    }
}

/**
 * One in-player track menu, on the [PlaybackSettingsDialog] recipe: scrimmed card, one alpha
 * reveal, flat radio list, focus trapped, Back dismisses. Selection is not dismissal — OK on a
 * row switches the track and keeps focus, so the user can hear the result and keep adjusting;
 * the engine re-emits its tracks and the `selected` marks follow.
 */
@Composable
private fun TrackMenuDialog(
    title: String,
    options: List<TrackOption>,
    noneRow: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)

    var visible by remember { mutableStateOf(false) }
    val reveal by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = iglooTween(IglooMotion.STANDARD_MS),
        label = "trackMenuReveal",
    )
    LaunchedEffect(Unit) { visible = true }

    val rowCount = options.size + (if (noneRow != null) 1 else 0)
    val rowRequesters = remember(rowCount) { List(rowCount) { FocusRequester() } }
    val doneRequester = remember { FocusRequester() }
    val noneSelected = options.none { it.selected }

    // Entry focus lands on the selected row, matching the pre-play dialog.
    LaunchedEffect(Unit) {
        val selectedIndex = if (noneRow != null && noneSelected) {
            0
        } else {
            options.indexOfFirst { it.selected }
                .takeIf { it >= 0 }
                ?.plus(if (noneRow != null) 1 else 0)
                ?: 0
        }
        rowRequesters.getOrNull(selectedIndex)?.requestFocus()
    }

    fun Modifier.rowFocus(index: Int): Modifier = this
        .focusRequester(rowRequesters[index])
        .focusProperties {
            left = FocusRequester.Cancel
            right = FocusRequester.Cancel
            up = rowRequesters.getOrNull(index - 1) ?: FocusRequester.Cancel
            down = rowRequesters.getOrNull(index + 1) ?: doneRequester
        }

    IglooScrim(
        modifier = Modifier.graphicsLayer { alpha = reveal },
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints {
            val cardWidth = minOf(IglooTheme.layout.dialogWidth, maxWidth)
            val cardMaxHeight = maxHeight - IglooTheme.layout.safeAreaVertical * 2
            Column(
                modifier = Modifier
                    .width(cardWidth)
                    .heightIn(max = cardMaxHeight)
                    .iglooSurface(radius = IglooTheme.radius.xl, fill = IglooTheme.colors.card)
                    .padding(IglooTheme.spacing.xl)
                    .semantics {
                        paneTitle = title
                        isTraversalGroup = true
                    }
                    .testTag("movie_track_menu"),
                verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.lg),
            ) {
                IglooText(
                    text = title,
                    style = IglooTheme.typography.titleMedium,
                    color = IglooTheme.colors.cardForeground,
                    modifier = Modifier.semantics { heading() },
                )

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.xs),
                ) {
                    var row = 0
                    if (noneRow != null) {
                        IglooRadioRow(
                            label = noneRow,
                            selected = noneSelected,
                            onSelect = { onSelect(null) },
                            modifier = Modifier
                                .rowFocus(row++)
                                .testTag("movie_track_none"),
                        )
                    }
                    options.forEach { option ->
                        IglooRadioRow(
                            label = option.label,
                            selected = option.selected,
                            onSelect = { onSelect(option.id) },
                            modifier = Modifier
                                .rowFocus(row++)
                                .testTag("movie_track_${option.id}"),
                        )
                    }
                }

                IglooButton(
                    text = "Done",
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(doneRequester)
                        .focusProperties {
                            left = FocusRequester.Cancel
                            right = FocusRequester.Cancel
                            down = FocusRequester.Cancel
                            up = rowRequesters.lastOrNull() ?: FocusRequester.Cancel
                        }
                        .testTag("movie_track_done"),
                )
            }
        }
    }
}
