package com.igloo.blindpenguincoder.feature.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.igloo.blindpenguincoder.core.design.IglooMotion
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.iglooTween
import com.igloo.blindpenguincoder.core.design.overMedia
import com.igloo.blindpenguincoder.core.ui.IglooButton
import com.igloo.blindpenguincoder.core.ui.IglooButtonVariant
import com.igloo.blindpenguincoder.core.ui.IglooIcons
import com.igloo.blindpenguincoder.core.ui.IglooInlineError
import com.igloo.blindpenguincoder.core.ui.IglooRadioRow
import com.igloo.blindpenguincoder.core.ui.IglooScrim
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.formatSpokenTime
import com.igloo.blindpenguincoder.core.ui.formatTimecode
import com.igloo.blindpenguincoder.core.ui.iglooSurface
import com.igloo.blindpenguincoder.core.ui.requestFocusSafely
import com.igloo.blindpenguincoder.data.model.PlaybackMode
import com.igloo.blindpenguincoder.playback.media3.MoviePlayerEngine
import com.igloo.blindpenguincoder.playback.model.MoviePlayRequest
import com.igloo.blindpenguincoder.playback.model.MoviePlayerEvent
import com.igloo.blindpenguincoder.playback.model.MoviePlayerPhase
import com.igloo.blindpenguincoder.playback.model.MoviePlayerState
import com.igloo.blindpenguincoder.playback.model.PlaybackChapter
import com.igloo.blindpenguincoder.playback.model.TrackOption
import com.igloo.blindpenguincoder.playback.model.moviePlayerAnnouncement
import com.igloo.blindpenguincoder.playback.model.onEvent
import kotlinx.coroutines.delay

/**
 * The movie player (docs/design-system.md section 11.8): a full-screen in-tree overlay whose
 * video surface is rendered by the engine, with all input and chrome in Compose. The trailer
 * player's shape throughout; what this screen adds is the
 * resume prompt, the in-player chapter and track menus, and the progress session on [viewModel].
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
    onPlaybackModeRequested: (PlaybackMode) -> Unit,
    engineFactory: (Context, MoviePlayRequest) -> MoviePlayerEngine,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var reloadKey by remember { mutableIntStateOf(0) }
    val engine = remember(reloadKey) { engineFactory(context, request) }
    val progressSync by viewModel.progressSyncUiState.collectAsStateWithLifecycle()
    val progressSyncError = (progressSync as? ProgressSyncUiState.Failed)?.message

    // The resume decision, playback position, and transport intent survive recreation. The
    // position prevents an hour-long movie from restarting at zero; the independent intent
    // prevents a replacement engine from autoplaying a movie the user had paused.
    var resumeDecided by rememberSaveable { mutableStateOf(request.resumeAtSec == null) }
    var chosenStartSec by rememberSaveable { mutableStateOf(request.resumeAtSec ?: 0.0) }
    var lastPositionSec by rememberSaveable { mutableStateOf(0.0) }
    var lastDurationSec by rememberSaveable { mutableStateOf(request.durationSec ?: 0.0) }
    var playWhenReadyIntent by rememberSaveable {
        mutableStateOf(request.resumeAtSec == null)
    }
    var lifecycleSilenced by remember(engine) { mutableStateOf(false) }
    var hostPausePending by remember(engine) { mutableStateOf(false) }
    var releasedForBackground by remember { mutableStateOf(false) }

    var state by remember(engine) {
        mutableStateOf(
            MoviePlayerState(
                phase = if (resumeDecided) MoviePlayerPhase.Loading else MoviePlayerPhase.AwaitingResume,
                playWhenReady = playWhenReadyIntent,
                currentTimeSec = lastPositionSec.takeIf { it > 0.0 }
                    ?: chosenStartSec.takeIf { resumeDecided }
                    ?: 0.0,
                durationSec = lastDurationSec,
            ),
        )
    }
    var unauthorized by remember(engine) { mutableStateOf(false) }
    var chromeVisible by remember { mutableStateOf(true) }
    var playerMenu by remember { mutableStateOf<PlayerMenu?>(null) }
    // Bumped by any key press or chrome focus move; each bump restarts the auto-hide clock.
    var interactionTick by remember { mutableIntStateOf(0) }

    val playPauseRequester = remember { FocusRequester() }
    val backRequester = remember { FocusRequester() }
    val retryRequester = remember { FocusRequester() }
    val progressRetryRequester = remember { FocusRequester() }
    val chaptersButtonRequester = remember { FocusRequester() }
    val audioButtonRequester = remember { FocusRequester() }
    val subtitlesButtonRequester = remember { FocusRequester() }
    val qualityButtonRequester = remember { FocusRequester() }

    DisposableEffect(engine) {
        onDispose { engine.release() }
    }

    val closeAndRelease: () -> Unit = {
        engine.release()
        onClose()
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
                // The engine, not the keypress, is the authority on what mode is in force: a
                // refused or failed switch must not leave a replacement engine rebuilding into
                // a mode that already proved it cannot start.
                is MoviePlayerEvent.QualityOptionsChanged ->
                    if (event.requestedMode != request.mode) {
                        onPlaybackModeRequested(event.requestedMode)
                    }
                is MoviePlayerEvent.PlayWhenReadyChanged -> {
                    if (!lifecycleSilenced) {
                        playWhenReadyIntent = event.playWhenReady
                    }
                }
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
    // resumes from the last real position; before any tick that falls back to the decision —
    // and only that decision is a resume point a mode may rewind before.
    LaunchedEffect(engine, resumeDecided) {
        if (resumeDecided) {
            val watched = lastPositionSec.takeIf { it > 0.0 }
            engine.startPlayback(
                watched ?: chosenStartSec.takeIf { it > 0.0 },
                initialPlayWhenReady = playWhenReadyIntent,
                rewindOnResume = watched == null,
            )
        }
    }

    LaunchedEffect(state.phase, progressSyncError) {
        // A menu must not survive into the error surface: it would swallow the screen while
        // entry focus lands on the Retry button hidden underneath it.
        if (state.phase == MoviePlayerPhase.Error) playerMenu = null
        when (state.phase) {
            MoviePlayerPhase.Ended -> closeAndRelease()
            // Chrome may only rest hidden over a moving picture; any other phase surfaces it.
            MoviePlayerPhase.Playing -> if (progressSyncError != null) chromeVisible = true
            else -> chromeVisible = true
        }
    }

    LaunchedEffect(chromeVisible, state.phase, interactionTick, playerMenu, progressSyncError) {
        if (
            chromeVisible && state.phase == MoviePlayerPhase.Playing && playerMenu == null &&
            progressSyncError == null
        ) {
            delay(CHROME_HIDE_MS)
            chromeVisible = false
        }
    }

    val showChrome: () -> Unit = {
        chromeVisible = true
        interactionTick++
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
    var progressRetryFocused by remember { mutableStateOf(false) }
    val progressRetryHadFocus = remember(progressSyncError) { progressRetryFocused }
    LaunchedEffect(progressSyncError) {
        if (progressSyncError == null && progressRetryHadFocus) {
            playPauseRequester.requestFocusSafely()
        }
    }

    // The track menus and the resume prompt register their own handlers below this one, so this
    // fires only with the plain chrome up.
    BackHandler {
        if (
            chromeVisible && state.phase == MoviePlayerPhase.Playing && progressSyncError == null
        ) {
            chromeVisible = false
        } else {
            closeAndRelease()
        }
    }

    // Lifecycle silence is not transport intent. Configuration teardown preserves the user's
    // saved choice for the replacement engine; a real background/standby trip stays paused.
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

    val modalUp = state.phase == MoviePlayerPhase.AwaitingResume || playerMenu != null
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
                    play = play,
                    pause = pause,
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
        engine.VideoSurface(
            modifier = Modifier
                .fillMaxSize()
                .focusProperties { canFocus = false },
        )

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
            MoviePlayerChrome(
                title = request.title,
                state = state,
                visible = chromeVisible,
                chapterCount = request.chapters.size,
                playPauseRequester = playPauseRequester,
                backRequester = backRequester,
                chaptersButtonRequester = chaptersButtonRequester,
                audioButtonRequester = audioButtonRequester,
                subtitlesButtonRequester = subtitlesButtonRequester,
                qualityButtonRequester = qualityButtonRequester,
                progressRetryRequester = progressRetryRequester,
                progressSyncError = progressSyncError,
                onRetryProgressSync = viewModel::retryFailedSave,
                onProgressRetryFocusChanged = { progressRetryFocused = it },
                onAnyControlFocused = { interactionTick++ },
                onBack = closeAndRelease,
                onTogglePlayPause = togglePlayPause,
                onSeekBy = seekBy,
                onOpenMenu = { playerMenu = it },
            )
        }

        when (state.phase) {
            MoviePlayerPhase.AwaitingResume -> ResumePrompt(
                resumeAtSec = requireNotNull(request.resumeAtSec),
                onResume = {
                    playWhenReadyIntent = true
                    resumeDecided = true
                    state = state.onResumeChosen()
                },
                onStartOver = {
                    chosenStartSec = 0.0
                    playWhenReadyIntent = true
                    resumeDecided = true
                    state = state.onResumeChosen()
                },
                onClose = closeAndRelease,
            )
            else -> Unit
        }

        playerMenu?.let { menu ->
            val closeMenu: () -> Unit = {
                playerMenu = null
                state = state.onModeRefusalDismissed()
                when (menu) {
                    PlayerMenu.Chapters -> chaptersButtonRequester.requestFocus()
                    PlayerMenu.Audio -> audioButtonRequester.requestFocus()
                    PlayerMenu.Subtitles -> subtitlesButtonRequester.requestFocus()
                    PlayerMenu.Quality -> qualityButtonRequester.requestFocus()
                }
            }
            when (menu) {
                PlayerMenu.Chapters -> ChapterMenuDialog(
                    chapters = request.chapters,
                    currentTimeSec = state.currentTimeSec,
                    // A chapter pick dismisses, unlike the track menus: the jump's result is
                    // the picture itself, hidden behind this scrim until the menu is gone.
                    onSelectChapter = { startSec ->
                        seekToSec(startSec)
                        closeMenu()
                    },
                    onDismiss = closeMenu,
                )
                PlayerMenu.Audio -> TrackMenuDialog(
                    title = "Audio",
                    options = state.audioOptions,
                    noneRow = null,
                    onSelect = { id -> engine.selectAudioTrack(requireNotNull(id)) },
                    onDismiss = closeMenu,
                )
                PlayerMenu.Subtitles -> TrackMenuDialog(
                    title = "Subtitles",
                    options = state.subtitleOptions,
                    noneRow = "None",
                    onSelect = { id -> engine.selectSubtitleTrack(id) },
                    onDismiss = closeMenu,
                )
                // Like the track menus, selection keeps the dialog up: a quality switch
                // rebuffers behind the scrim and the selected mark follows the engine's
                // re-emitted options once the new session starts.
                PlayerMenu.Quality -> TrackMenuDialog(
                    title = "Quality",
                    options = state.qualityOptions,
                    noneRow = null,
                    onSelect = { id -> engine.selectPlaybackMode(requireNotNull(id)) },
                    onDismiss = closeMenu,
                    footerMessage = state.modeRefusalMessage,
                )
            }
        }

        // The transport announcement for a TalkBack focus parked anywhere: play state flips are
        // otherwise silent when driven by media keys. Polite — it narrates, it never interrupts.
        val playStateAnnouncement = moviePlayerAnnouncement(
            phase = state.phase,
            statusMessage = state.statusMessage,
            title = request.title,
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

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}

/** The in-player menus; which one is up is plain screen state. */
private enum class PlayerMenu { Chapters, Audio, Subtitles, Quality }

/** Where entry focus belongs for the current phase; modals place their own. */
private enum class FocusAnchor { Transport, ErrorAction, Modal }

/** The transport row's last visible control — the one whose right edge cancels. */
private enum class LastControl { Forward, Chapters, Audio, Subtitles, Quality }

/** The chrome: a top title bar and a bottom transport, each on its own section 3.2 scrim. */
@Composable
private fun MoviePlayerChrome(
    title: String,
    state: MoviePlayerState,
    visible: Boolean,
    chapterCount: Int,
    playPauseRequester: FocusRequester,
    backRequester: FocusRequester,
    chaptersButtonRequester: FocusRequester,
    audioButtonRequester: FocusRequester,
    subtitlesButtonRequester: FocusRequester,
    qualityButtonRequester: FocusRequester,
    progressRetryRequester: FocusRequester,
    progressSyncError: String?,
    onRetryProgressSync: () -> Unit,
    onProgressRetryFocusChanged: (Boolean) -> Unit,
    onAnyControlFocused: () -> Unit,
    onBack: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekBy: (Double) -> Unit,
    onOpenMenu: (PlayerMenu) -> Unit,
) {
    val layout = IglooTheme.layout
    // Always composed, alpha-hidden: dismissal must not detach the focused control or reshuffle
    // TalkBack traversal. Under reduced motion iglooTween snaps.
    val chromeAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = iglooTween(IglooMotion.STANDARD_MS),
        label = "moviePlayerChrome",
    )
    val playing = state.playWhenReady
    // A one-track menu is a choice with no alternatives; the subtitle menu earns its place with
    // a single track because "None" is its second option. Chapters follow the same rule — one
    // chapter spans the whole movie. Unlike the track buttons, the chapter button is request-
    // driven, so it exists before the engine reports anything.
    val showChapters = chapterCount >= 2
    val showAudio = state.audioOptions.size >= 2
    val showSubtitles = state.subtitleOptions.isNotEmpty()
    val showQuality = state.qualityOptions.size >= 2
    // Right cancels only on the row's last visible control; derived once so the per-button
    // expressions stop compounding as optional controls are added.
    val lastControl = when {
        showQuality -> LastControl.Quality
        showSubtitles -> LastControl.Subtitles
        showAudio -> LastControl.Audio
        showChapters -> LastControl.Chapters
        else -> LastControl.Forward
    }

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
                        down = if (progressSyncError != null) {
                            progressRetryRequester
                        } else {
                            playPauseRequester
                        }
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
            // The engine's own narration (capacity waits, reconnects) outranks the generic word.
            val holdMessage = when (state.phase) {
                MoviePlayerPhase.Loading -> state.statusMessage ?: "Loading movie…"
                MoviePlayerPhase.Buffering -> state.statusMessage ?: "Buffering…"
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
            if (progressSyncError != null) {
                IglooInlineError(
                    message = progressSyncError,
                    actionText = "Retry",
                    actionSemanticLabel = "Retry saving playback progress",
                    onAction = onRetryProgressSync,
                    actionModifier = Modifier
                        .focusRequester(progressRetryRequester)
                        .focusProperties {
                            up = backRequester
                            down = playPauseRequester
                            left = FocusRequester.Cancel
                            right = FocusRequester.Cancel
                        }
                        .onFocusChanged {
                            onProgressRetryFocusChanged(it.isFocused)
                            if (it.isFocused) onAnyControlFocused()
                        }
                        .testTag("movie_progress_retry"),
                    liveRegionMode = LiveRegionMode.Polite,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .width(IglooTheme.layout.dialogWidth)
                        .testTag("movie_progress_error"),
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
                    up = if (progressSyncError != null) {
                        progressRetryRequester
                    } else {
                        backRequester
                    }
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
                        .transportFocus(isLast = lastControl == LastControl.Forward)
                        .testTag("movie_forward"),
                )
                // Text, not icons: IglooIcons has no chapter/audio/CC glyphs, and section 5.4
                // prefers a readable word over a novel symbol at TV distance. Chapters sits
                // with the seek controls — a chapter jump is a seek — ahead of the track pair,
                // matching section 11.8's transport order.
                if (showChapters) {
                    IglooButton(
                        text = "Chapters",
                        onClick = { onOpenMenu(PlayerMenu.Chapters) },
                        variant = IglooButtonVariant.Ghost,
                        semanticLabel = "Chapters",
                        restingFill = OVER_MEDIA_CONTROL_FILL,
                        contentColor = Color.White,
                        modifier = Modifier
                            .focusRequester(chaptersButtonRequester)
                            .transportFocus(isLast = lastControl == LastControl.Chapters)
                            .testTag("movie_chapters"),
                    )
                }
                if (showAudio) {
                    IglooButton(
                        text = "Audio",
                        onClick = { onOpenMenu(PlayerMenu.Audio) },
                        variant = IglooButtonVariant.Ghost,
                        semanticLabel = "Audio track",
                        restingFill = OVER_MEDIA_CONTROL_FILL,
                        contentColor = Color.White,
                        modifier = Modifier
                            .focusRequester(audioButtonRequester)
                            .transportFocus(isLast = lastControl == LastControl.Audio)
                            .testTag("movie_audio"),
                    )
                }
                if (showSubtitles) {
                    IglooButton(
                        text = "Subtitles",
                        onClick = { onOpenMenu(PlayerMenu.Subtitles) },
                        variant = IglooButtonVariant.Ghost,
                        semanticLabel = "Subtitles",
                        restingFill = OVER_MEDIA_CONTROL_FILL,
                        contentColor = Color.White,
                        modifier = Modifier
                            .focusRequester(subtitlesButtonRequester)
                            .transportFocus(isLast = lastControl == LastControl.Subtitles)
                            .testTag("movie_subtitles"),
                    )
                }
                if (showQuality) {
                    IglooButton(
                        text = "Quality",
                        onClick = { onOpenMenu(PlayerMenu.Quality) },
                        variant = IglooButtonVariant.Ghost,
                        semanticLabel = "Playback quality",
                        restingFill = OVER_MEDIA_CONTROL_FILL,
                        contentColor = Color.White,
                        modifier = Modifier
                            .focusRequester(qualityButtonRequester)
                            .transportFocus(isLast = lastControl == LastControl.Quality)
                            .testTag("movie_quality"),
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
 *
 * [footerMessage] is a refusal the engine returned for the last pick — shown in place, below the
 * rows, because the row that caused it is still on screen and still the user's to change. It
 * carries no action: the choice stays theirs, and what is playing never changed.
 */
@Composable
private fun TrackMenuDialog(
    title: String,
    options: List<TrackOption>,
    noneRow: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
    footerMessage: String? = null,
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

                if (footerMessage != null) {
                    IglooInlineError(
                        message = footerMessage,
                        // Polite: the rows keep focus, and nothing the user did has failed —
                        // the choice was declined with a reason, mid-adjustment.
                        liveRegionMode = LiveRegionMode.Polite,
                        modifier = Modifier.testTag("movie_track_refusal"),
                    )
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

/**
 * The chapter menu, on the [TrackMenuDialog] shell. Unlike a track menu, picking a row here is
 * dismissal — a chapter pick is a jump, and its result is the picture hidden behind this scrim,
 * not something to keep adjusting. The `selected` mark tracks the playhead live; entry focus
 * lands on the chapter playing when the menu opened and stays put.
 */
@Composable
private fun ChapterMenuDialog(
    chapters: List<PlaybackChapter>,
    currentTimeSec: Double,
    onSelectChapter: (Double) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)

    var visible by remember { mutableStateOf(false) }
    val reveal by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = iglooTween(IglooMotion.STANDARD_MS),
        label = "chapterMenuReveal",
    )
    LaunchedEffect(Unit) { visible = true }

    val rowRequesters = remember(chapters.size) { List(chapters.size) { FocusRequester() } }
    val doneRequester = remember { FocusRequester() }
    val activeIndex = activeChapterIndex(chapters, currentTimeSec)

    // Before the first chapter begins, no row is marked and focus falls to the first.
    LaunchedEffect(Unit) {
        rowRequesters.getOrNull(activeIndex.coerceAtLeast(0))?.requestFocus()
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
                        paneTitle = "Chapters"
                        isTraversalGroup = true
                    }
                    .testTag("movie_chapter_menu"),
                verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.lg),
            ) {
                IglooText(
                    text = "Chapters",
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
                    chapters.forEachIndexed { index, chapter ->
                        IglooRadioRow(
                            label = chapterLabel(chapter, index),
                            detail = formatTimecode(chapter.startTimeSec),
                            selected = index == activeIndex,
                            semanticLabel = chapterSpokenLabel(chapter, index, chapters.size),
                            onSelect = { onSelectChapter(chapter.startTimeSec) },
                            modifier = Modifier
                                .rowFocus(index)
                                .testTag("movie_chapter_$index"),
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
                        .testTag("movie_chapter_done"),
                )
            }
        }
    }
}
