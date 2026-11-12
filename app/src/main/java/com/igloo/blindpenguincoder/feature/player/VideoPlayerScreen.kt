package com.igloo.blindpenguincoder.feature.player

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.igloo.blindpenguincoder.core.design.IglooMotion
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.iglooSafeArea
import com.igloo.blindpenguincoder.core.design.iglooTween
import com.igloo.blindpenguincoder.core.design.overMedia
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.ui.IglooButton
import com.igloo.blindpenguincoder.core.ui.IglooButtonVariant
import com.igloo.blindpenguincoder.core.ui.IglooInlineError
import com.igloo.blindpenguincoder.core.ui.IglooRadioListDialog
import com.igloo.blindpenguincoder.core.ui.IglooRadioRow
import com.igloo.blindpenguincoder.core.ui.IglooDialogCard
import com.igloo.blindpenguincoder.core.ui.clearedSemanticsIf
import com.igloo.blindpenguincoder.core.ui.formatSpokenTime
import com.igloo.blindpenguincoder.core.ui.formatTimecode
import com.igloo.blindpenguincoder.core.ui.pinnedSideways
import com.igloo.blindpenguincoder.core.ui.rememberRefocusAfterSwap
import com.igloo.blindpenguincoder.core.ui.requestFocusSafely
import com.igloo.blindpenguincoder.data.model.PlaybackMode
import com.igloo.blindpenguincoder.feature.auth.toFailureNotice
import com.igloo.blindpenguincoder.feature.shared.ProgressSyncRetry
import com.igloo.blindpenguincoder.playback.media3.VideoPlayerEngine
import com.igloo.blindpenguincoder.playback.model.PlaybackChapter
import com.igloo.blindpenguincoder.playback.model.TrackOption
import com.igloo.blindpenguincoder.playback.model.UpNextEpisode
import com.igloo.blindpenguincoder.playback.model.UpNextOffer
import com.igloo.blindpenguincoder.playback.model.VideoPlayRequest
import com.igloo.blindpenguincoder.playback.model.VideoPlayerEvent
import com.igloo.blindpenguincoder.playback.model.VideoPlayerPhase
import com.igloo.blindpenguincoder.playback.model.VideoPlayerState
import com.igloo.blindpenguincoder.playback.model.endedPositionSec
import com.igloo.blindpenguincoder.playback.model.noun
import com.igloo.blindpenguincoder.playback.model.onEvent
import com.igloo.blindpenguincoder.playback.model.upNextOffer
import com.igloo.blindpenguincoder.playback.model.upNextOutsideLead
import com.igloo.blindpenguincoder.playback.model.videoPlayerAnnouncement

/**
 * The movie player (docs/design-system.md section 11.8): a full-screen in-tree overlay whose
 * video surface is rendered by the engine, with all input and chrome in Compose. The trailer
 * player's shape throughout; what this screen adds is the
 * resume prompt, the in-player chapter and track menus, the progress session on [viewModel], and
 * for an episode the Up Next card and the hand-off to the episode that follows.
 *
 * The host owns close and focus restoration; this screen's own [BackHandler] handles chrome
 * dismissal and otherwise calls [onClose]. [engineFactory] has no default because the real
 * engine needs the stream URL and data-source factory, which are the host's to know. The
 * hand-off is the host's too: [prepareUpNext] builds the next episode's request and [onAdvance]
 * asks the host to replace this screen with one for it, the way a launch mounts this one.
 */
@Composable
fun VideoPlayerScreen(
    request: VideoPlayRequest,
    viewModel: VideoPlayerViewModel,
    onClose: () -> Unit,
    onPlaybackModeRequested: (PlaybackMode) -> Unit,
    onTrackSelectionChanged: (audioTypeIndex: Int?, subtitleTypeIndex: Int?) -> Unit,
    engineFactory: (Context, VideoPlayRequest) -> VideoPlayerEngine,
    prepareUpNext: suspend (UpNextEpisode) -> ApiResult<VideoPlayRequest>,
    onAdvance: (VideoPlayRequest) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // A title that resumes opens on the prompt, so the intent only arms once the user chooses —
    // unless the request already carries that choice, and then it plays at once.
    val host = rememberPlayerHostLifecycle(initialPlayWhenReady = request.resumePromptSec == null)
    val engine = remember(host.reloadKey) { engineFactory(context, request) }
    val latestRequest by rememberUpdatedState(request)
    val latestOnPlaybackModeRequested by rememberUpdatedState(onPlaybackModeRequested)
    val latestOnTrackSelectionChanged by rememberUpdatedState(onTrackSelectionChanged)
    var reconstructedMode by remember(engine) { mutableStateOf(request.mode) }
    val progressSync by viewModel.progressSyncUiState.collectAsStateWithLifecycle()
    val progressSyncError = (progressSync as? ProgressSyncUiState.Failed)?.message

    // The resume decision, playback position, and transport intent survive recreation. The
    // position prevents an hour-long movie from restarting at zero; the independent intent
    // prevents a replacement engine from autoplaying a movie the user had paused.
    var resumeDecided by rememberSaveable { mutableStateOf(request.resumePromptSec == null) }
    var chosenStartSec by rememberSaveable { mutableDoubleStateOf(request.resumeAtSec ?: 0.0) }
    var lastPositionSec by rememberSaveable { mutableDoubleStateOf(0.0) }
    var lastDurationSec by rememberSaveable { mutableDoubleStateOf(request.durationSec ?: 0.0) }

    var state by remember(engine) {
        mutableStateOf(
            VideoPlayerState(
                phase = if (resumeDecided) VideoPlayerPhase.Loading else VideoPlayerPhase.AwaitingResume,
                playWhenReady = host.playWhenReadyIntent,
                currentTimeSec = lastPositionSec.takeIf { it > 0.0 }
                    ?: chosenStartSec.takeIf { resumeDecided }
                    ?: 0.0,
                durationSec = lastDurationSec,
            ),
        )
    }
    var unauthorized by remember(engine) { mutableStateOf(false) }
    var playerMenu by remember { mutableStateOf<PlayerMenu?>(null) }
    val playing = state.phase == VideoPlayerPhase.Playing
    val chrome = rememberChromeAutoHide(
        canHide = playing && playerMenu == null && progressSyncError == null,
        mustShow = !playing || progressSyncError != null,
    )

    // The Up Next card (section 11.8): offered over an episode's last seconds and at its end.
    // A dismissal is the viewer's, and survives recreation; it lapses once they seek back past
    // the lead. The next request is prepared while the card is up, kept for the screen's life,
    // and spent exactly once — by Center on the card or by the end of the episode.
    val upNext = request.episode?.upNext
    var upNextDismissed by rememberSaveable { mutableStateOf(false) }
    var preparedNext by remember { mutableStateOf<ApiResult<VideoPlayRequest>?>(null) }
    var handedOff by remember { mutableStateOf(false) }
    var upNextFocused by remember { mutableStateOf(false) }
    val upNextRequester = remember { FocusRequester() }
    var bottomBlockHeightPx by remember { mutableIntStateOf(0) }

    val playPauseRequester = remember { FocusRequester() }
    val backRequester = remember { FocusRequester() }
    val retryRequester = remember { FocusRequester() }
    val progressRetryRequester = remember { FocusRequester() }
    val menuButtonRequesters = remember { PlayerMenu.entries.associateWith { FocusRequester() } }

    val persistTrackSelection: (VideoPlayerEngine) -> Unit = { source ->
        val audioTypeIndex = source.currentAudioTypeIndex
        val subtitleTypeIndex = source.currentSubtitleTypeIndex
        val saved = latestRequest
        if (
            audioTypeIndex != saved.audioTypeIndex ||
            subtitleTypeIndex != saved.subtitleTypeIndex
        ) {
            latestOnTrackSelectionChanged(audioTypeIndex, subtitleTypeIndex)
        }
    }

    val releaseEngine: () -> Unit = {
        persistTrackSelection(engine)
        engine.release()
    }
    DisposableEffect(engine) {
        onDispose { releaseEngine() }
    }
    val closeAndRelease: () -> Unit = {
        releaseEngine()
        onClose()
    }

    // The progress session: one per screen visit, and the exit save fires on *any* unmount —
    // Back, Ended, error Close, or the host tearing the overlay down — because the ViewModel
    // outlives this composable and gives the final write somewhere to land.
    LaunchedEffect(Unit) { viewModel.startSession(request.media) }
    DisposableEffect(Unit) {
        onDispose { viewModel.endSession(request.media, lastPositionSec, lastDurationSec) }
    }

    LaunchedEffect(engine) {
        // An engine replacement is a new player surface and focus context. In-place source
        // switches keep the same engine, so their open menu deliberately survives.
        playerMenu = null
        if (resumeDecided) playPauseRequester.requestFocusSafely()
        engine.events.collect { event ->
            val previous = state
            val next = previous.onEvent(event)
            state = next
            when (event) {
                is VideoPlayerEvent.Error -> if (event.unauthorized) unauthorized = true
                // Accepted pending requests are published before HLS preflight so background
                // reconstruction can preserve them. A terminal failure publishes the prior
                // committed request first, while refused choices never reach this event.
                is VideoPlayerEvent.QualityOptionsChanged ->
                    if (event.requestedMode != reconstructedMode) {
                        reconstructedMode = event.requestedMode
                        latestOnPlaybackModeRequested(event.requestedMode)
                    }
                is VideoPlayerEvent.TracksChanged -> persistTrackSelection(engine)
                is VideoPlayerEvent.PlayWhenReadyChanged -> {
                    host.onEnginePlayWhenReady(event.playWhenReady)
                    // A real pause — chrome, media key, media session or the host's ON_PAUSE —
                    // writes progress at once, like the web. A rebuilt engine's paused start
                    // arrives with the intent already false and writes nothing.
                    if (
                        !event.playWhenReady && previous.playWhenReady &&
                        previous.phase in PROGRESS_FLUSH_PHASES
                    ) {
                        viewModel.flushProgress(lastPositionSec, lastDurationSec)
                    }
                }
                // A tick that outruns Ended must not pull the final snapshot back below the end.
                is VideoPlayerEvent.Time -> if (next.phase != VideoPlayerPhase.Ended) {
                    lastPositionSec = event.currentSec
                    if (event.durationSec > 0.0) lastDurationSec = event.durationSec
                    viewModel.onTick(
                        positionSec = event.currentSec,
                        durationSec = lastDurationSec,
                        isPlaying = next.phase == VideoPlayerPhase.Playing,
                    )
                    // Back outside the lead, a dismissed card may be offered again at the end.
                    if (upNextOutsideLead(next)) upNextDismissed = false
                }
                // Finishing means full progress: the exit save must record the end, not the
                // last tick before it.
                is VideoPlayerEvent.Ended -> lastPositionSec = next.currentTimeSec
                else -> Unit
            }
        }
    }

    OnNetworkRecovered {
        persistTrackSelection(engine)
        host.rebuildEngine(playWhenReady = host.playWhenReadyIntent)
    }

    // Started once per engine, the moment the resume decision exists. A retry or recreation
    // resumes from the last real position; before any tick that falls back to the decision —
    // and only that decision is a resume point a mode may rewind before.
    LaunchedEffect(engine, resumeDecided) {
        if (resumeDecided) {
            val watched = lastPositionSec.takeIf { it > 0.0 }
            engine.startPlayback(
                watched ?: chosenStartSec.takeIf { it > 0.0 },
                initialPlayWhenReady = host.playWhenReadyIntent,
                rewindOnResume = watched == null,
            )
        }
    }

    LaunchedEffect(state.phase) {
        // A menu must not survive into the error surface: it would swallow the screen while
        // entry focus lands on the Retry button hidden underneath it.
        if (state.phase == VideoPlayerPhase.Error) playerMenu = null
    }

    val upNextOffer: UpNextOffer? = upNext?.let { upNextOffer(state, upNextDismissed) }
    // The next episode is wanted while its card stands, and at the end whether or not the card
    // ever showed (a duration the engine never reported); a dismissal withdraws both.
    val wantsNext = upNext != null && !upNextDismissed &&
        (upNextOffer != null || state.phase == VideoPlayerPhase.Ended)
    LaunchedEffect(wantsNext) {
        if (wantsNext && preparedNext == null) preparedNext = prepareUpNext(requireNotNull(upNext))
    }
    // Handing off means finishing this episode: its exit save records the end, as Ended does,
    // and its engine goes before the host mounts the next episode's screen. Spent once.
    val advance: () -> Unit = {
        val next = (preparedNext as? ApiResult.Success)?.value
        if (next != null && !handedOff) {
            handedOff = true
            lastPositionSec = endedPositionSec(lastPositionSec, lastDurationSec)
            viewModel.endSession(request.media, lastPositionSec, lastDurationSec)
            releaseEngine()
            onAdvance(next)
        }
    }
    // The end of the picture: an episode with a next one and no dismissal hands off — at once
    // when the next request is ready, or as soon as it lands, the ended frame held under the
    // card meanwhile; a movie, the show's last episode, a dismissal or a failed preparation
    // close the player as before.
    LaunchedEffect(state.phase, preparedNext, upNextDismissed) {
        if (state.phase != VideoPlayerPhase.Ended || handedOff) return@LaunchedEffect
        when {
            !wantsNext -> closeAndRelease()
            preparedNext is ApiResult.Success -> advance()
            preparedNext is ApiResult.Failure -> closeAndRelease()
            else -> Unit
        }
    }

    val play = { engine.play() }
    val pause = { engine.pause() }
    val togglePlayPause = {
        if (state.playWhenReady) pause() else play()
    }
    val seekToSec = { targetSec: Double ->
        engine.seekTo(targetSec)
        state = state.onSeekApplied(targetSec)
        // The optimistic seek is what the viewer sees first; a dismissal lapses with it too.
        if (upNextOutsideLead(state)) upNextDismissed = false
    }
    val seekBy = { deltaSec: Double -> seekToSec(state.seekTarget(deltaSec)) }

    // Entry focus: Play/Pause anchors the screen; the error surface moves it to its one action,
    // the Up Next card takes it the moment it appears — or the moment a menu that was up closes —
    // and the resume prompt and track menus request their own on composition.
    val focusAnchor = when {
        state.phase == VideoPlayerPhase.Error -> FocusAnchor.ErrorAction
        state.phase == VideoPlayerPhase.AwaitingResume -> FocusAnchor.Modal
        upNextOffer != null && playerMenu == null -> FocusAnchor.UpNext
        else -> FocusAnchor.Transport
    }
    LaunchedEffect(focusAnchor) {
        when (focusAnchor) {
            FocusAnchor.ErrorAction -> retryRequester.requestFocusSafely()
            FocusAnchor.Transport -> playPauseRequester.requestFocusSafely()
            FocusAnchor.UpNext -> upNextRequester.requestFocusSafely()
            FocusAnchor.Modal -> Unit
        }
    }
    val onProgressRetryFocus = rememberRefocusAfterSwap(progressSyncError) {
        if (progressSyncError == null) playPauseRequester.requestFocusSafely()
    }

    // The track menus and the resume prompt register their own handlers below this one, so this
    // fires only with the plain chrome up.
    BackHandler {
        when {
            // The card goes first: the episode plays out, and its end closes the player.
            upNextOffer != null -> upNextDismissed = true
            chrome.visible && playing && progressSyncError == null -> chrome.visible = false
            else -> closeAndRelease()
        }
    }

    PlayerHostLifecycleEffect(
        host = host,
        engine = engine,
        onHostPaused = { engine.onHostPaused() },
        onHostResumed = { engine.onHostResumed() },
        onBackgroundRelease = {
            // The trip away is the web's page hide: progress goes out before the engine does.
            viewModel.flushProgress(lastPositionSec, lastDurationSec)
            releaseEngine()
        },
    )

    val modalUp = state.phase == VideoPlayerPhase.AwaitingResume || playerMenu != null
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                handlePlayerKey(
                    event = event,
                    chromeVisible = chrome.visible,
                    controlsDisabled = state.phase == VideoPlayerPhase.Error || modalUp,
                    showChrome = chrome::show,
                    play = play,
                    pause = pause,
                    togglePlayPause = togglePlayPause,
                    seekBy = seekBy,
                    focusPlayPause = { playPauseRequester.requestFocusSafely() },
                    // Gated on the offer: a card that left composition under its focus must not
                    // keep Left and Right pinned on the chrome that took it back.
                    upNextFocused = upNextFocused && upNextOffer != null,
                )
            }
            .semantics {
                paneTitle = "${request.media.noun.replaceFirstChar(Char::uppercase)} player"
                isTraversalGroup = true
            }
            .testTag("movie_player"),
    ) {
        key(engine) {
            engine.VideoSurface(
                modifier = Modifier
                    .fillMaxSize()
                    .focusProperties { canFocus = false },
            )
        }

        if (state.phase == VideoPlayerPhase.Error) {
            PlayerFailureSurface(
                message = state.errorMessage,
                unauthorized = unauthorized,
                mediaNoun = request.media.noun,
                actionRequester = retryRequester,
                onRetry = {
                    // Retry is a fresh, explicit Play intent after the failed engine's
                    // terminal boundary cleared every pending transport command.
                    persistTrackSelection(engine)
                    host.rebuildEngine(playWhenReady = true)
                },
                onClose = closeAndRelease,
            )
        } else {
            VideoPlayerChrome(
                title = request.title,
                mediaNoun = request.media.noun,
                state = state,
                visible = chrome.visible,
                chapterCount = request.chapters.size,
                playPauseRequester = playPauseRequester,
                backRequester = backRequester,
                menuButtonRequesters = menuButtonRequesters,
                progressRetryRequester = progressRetryRequester,
                upNextRequester = upNextRequester.takeIf { upNextOffer != null },
                progressSyncError = progressSyncError,
                onRetryProgressSync = viewModel::retryFailedSave,
                onProgressRetryFocusChanged = onProgressRetryFocus,
                onAnyControlFocused = chrome::interact,
                onBottomBlockSizeChanged = { bottomBlockHeightPx = it },
                onBack = closeAndRelease,
                onTogglePlayPause = togglePlayPause,
                onSeekBy = seekBy,
                onOpenMenu = { playerMenu = it },
                // A menu is a partial overlay, so the controls it covers leave the semantics tree
                // rather than being flagged: otherwise TalkBack stays parked on the button that
                // opened it (design system section 9.3). The tag sits outside the clear.
                // They also refuse focus while it is up. A menu opened by touch opens with
                // nothing focused (touch mode), and the remote's first key then searched the
                // whole screen: it moved among the controls under the scrim, Up reached Back, and
                // Select closed the player. The group stays in place so closing the menu can hand
                // focus straight back to the button that opened it.
                modifier = Modifier
                    .testTag("movie_chrome")
                    .focusProperties { onEnter = { if (playerMenu != null) cancelFocusChange() } }
                    .focusGroup()
                    .clearedSemanticsIf(playerMenu != null),
            )
        }

        if (upNext != null && upNextOffer != null) {
            val preparationFailure = (preparedNext as? ApiResult.Failure)?.error
            val status = when {
                preparationFailure != null -> preparationFailure.toFailureNotice("prepare the next episode")
                state.phase == VideoPlayerPhase.Ended -> UP_NEXT_LOADING
                else -> upNextCountdown(upNextOffer.secondsLeft, upNext.resumable)
            }
            // In the corner over the picture; lifted above the transport block while the chrome
            // is up, so the two never overlap and the card never fades with the chrome.
            val bottomInset by animateDpAsState(
                targetValue = if (chrome.visible) {
                    with(LocalDensity.current) { bottomBlockHeightPx.toDp() }
                } else {
                    IglooTheme.layout.safeAreaVertical
                },
                animationSpec = iglooTween(IglooMotion.STANDARD_MS),
                label = "upNextLift",
            )
            // Spoken as it stood when the card appeared or changed what it is waiting for —
            // never per second (section 12). Frozen by remember on exactly those two facts.
            val upNextSpoken = remember(preparationFailure != null, state.phase == VideoPlayerPhase.Ended) {
                when {
                    preparationFailure != null || state.phase == VideoPlayerPhase.Ended ->
                        upNextSpokenStatus(upNext, status)
                    else -> upNextAnnouncement(upNext, upNextOffer.secondsLeft)
                }
            }
            UpNextCard(
                next = upNext,
                status = status,
                spoken = upNextSpoken,
                requester = upNextRequester,
                upRequester = if (progressSyncError != null) progressRetryRequester else backRequester,
                downRequester = playPauseRequester,
                onPlayNow = advance,
                onFocusChanged = { upNextFocused = it },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = IglooTheme.layout.safeAreaHorizontal, bottom = bottomInset)
                    .testTag("movie_up_next"),
            )
        }

        if (state.phase == VideoPlayerPhase.AwaitingResume) {
            val resume = {
                host.playWhenReadyIntent = true
                resumeDecided = true
                state = state.onResumeChosen()
            }
            ResumePrompt(
                resumeAtSec = requireNotNull(request.resumePromptSec),
                onResume = resume,
                onStartOver = {
                    chosenStartSec = 0.0
                    resume()
                },
                onClose = closeAndRelease,
            )
        }

        playerMenu?.let { menu ->
            val closeMenu: () -> Unit = {
                playerMenu = null
                state = state.onModeRefusalDismissed()
                menuButtonRequesters.getValue(menu).requestFocus()
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
                    onSelect = { id ->
                        engine.selectAudioTrack(requireNotNull(id))
                        persistTrackSelection(engine)
                    },
                    onDismiss = closeMenu,
                )
                PlayerMenu.Subtitles -> TrackMenuDialog(
                    title = "Subtitles",
                    options = state.subtitleOptions,
                    noneRow = "None",
                    onSelect = { id ->
                        engine.selectSubtitleTrack(id)
                        persistTrackSelection(engine)
                    },
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

        PoliteAnnouncement(
            videoPlayerAnnouncement(
                phase = state.phase,
                statusMessage = state.statusMessage,
                title = request.title,
                mediaNoun = request.media.noun,
            ),
        )
    }
}

/**
 * The in-player menus, in transport order, with their buttons' words; which one is up is plain
 * screen state.
 */
private enum class PlayerMenu(val text: String, val semanticLabel: String, val tag: String) {
    Chapters("Chapters", "Chapters", "movie_chapters"),
    Audio("Audio", "Audio track", "movie_audio"),
    Subtitles("Subtitles", "Subtitles", "movie_subtitles"),
    Quality("Quality", "Playback quality", "movie_quality"),
}

/** Where entry focus belongs for the current phase; modals place their own. */
private enum class FocusAnchor { Transport, ErrorAction, UpNext, Modal }

/** The chrome: a top title bar and a bottom transport, each on its own section 3.2 scrim. */
@Composable
private fun VideoPlayerChrome(
    title: String,
    mediaNoun: String,
    state: VideoPlayerState,
    visible: Boolean,
    chapterCount: Int,
    playPauseRequester: FocusRequester,
    backRequester: FocusRequester,
    menuButtonRequesters: Map<PlayerMenu, FocusRequester>,
    progressRetryRequester: FocusRequester,
    /** The Up Next card while it is up: it joins the vertical route between the transport and what sits above. */
    upNextRequester: FocusRequester?,
    progressSyncError: String?,
    onRetryProgressSync: () -> Unit,
    onProgressRetryFocusChanged: (Boolean) -> Unit,
    onAnyControlFocused: () -> Unit,
    onBottomBlockSizeChanged: (Int) -> Unit,
    onBack: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekBy: (Double) -> Unit,
    onOpenMenu: (PlayerMenu) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Always composed, alpha-hidden: dismissal must not detach the focused control or reshuffle
    // TalkBack traversal. Under reduced motion iglooTween snaps.
    val chromeAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = iglooTween(IglooMotion.STANDARD_MS),
        label = "videoPlayerChrome",
    )
    val playing = state.playWhenReady
    // A one-track menu is a choice with no alternatives; the subtitle menu earns its place with
    // a single track because "None" is its second option. Chapters follow the same rule — one
    // chapter spans the whole movie. Unlike the track buttons, the chapter button is request-
    // driven, so it exists before the engine reports anything.
    val menus = PlayerMenu.entries.filter { menu ->
        when (menu) {
            PlayerMenu.Chapters -> chapterCount >= 2
            PlayerMenu.Audio -> state.audioOptions.size >= 2
            PlayerMenu.Subtitles -> state.subtitleOptions.isNotEmpty()
            PlayerMenu.Quality -> state.qualityOptions.size >= 2
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { alpha = chromeAlpha },
    ) {
        PlayerTopBar(
            title = title,
            backRequester = backRequester,
            downRequester = when {
                progressSyncError != null -> progressRetryRequester
                upNextRequester != null -> upNextRequester
                else -> playPauseRequester
            },
            backTag = "movie_back",
            onBack = onBack,
            onFocused = onAnyControlFocused,
        )

        Box(modifier = Modifier.weight(1f)) {
            // The engine's own narration (capacity waits, reconnects) outranks the generic word.
            val holdMessage = when (state.phase) {
                VideoPlayerPhase.Loading -> state.statusMessage ?: "Loading $mediaNoun…"
                VideoPlayerPhase.Buffering -> state.statusMessage ?: "Buffering…"
                else -> null
            }
            if (holdMessage != null) {
                PlayerHoldMessage(text = holdMessage, tag = "movie_loading")
            }
            if (progressSyncError != null) {
                ProgressSyncRetry(
                    message = progressSyncError,
                    onRetry = onRetryProgressSync,
                    retryModifier = Modifier
                        .focusRequester(progressRetryRequester)
                        .pinnedSideways(
                            up = backRequester,
                            down = upNextRequester ?: playPauseRequester,
                        )
                        .onFocusChanged {
                            onProgressRetryFocusChanged(it.isFocused)
                            if (it.isFocused) onAnyControlFocused()
                        }
                        .testTag("movie_progress_retry"),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .testTag("movie_progress_error"),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { onBottomBlockSizeChanged(it.height) }
                .playerBottomScrim()
                .iglooSafeArea(),
            verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
        ) {
            // The track-menu buttons stay one Right press away, so only the row's outer
            // edges cancel sideways. Up leaves for the card when it is up, else the Retry, else Back.
            val transportUp = upNextRequester
                ?: if (progressSyncError != null) progressRetryRequester else backRequester
            fun Modifier.transportEdges(isFirst: Boolean = false, isLast: Boolean = false) =
                transportFocus(transportUp, isFirst, isLast, onAnyControlFocused)
            PlayerTransportRow {
                SeekTransportButtons(
                    tagPrefix = "movie_",
                    playing = playing,
                    playPauseRequester = playPauseRequester,
                    upRequester = transportUp,
                    onTogglePlayPause = onTogglePlayPause,
                    onSeekBy = onSeekBy,
                    isLast = menus.isEmpty(),
                    onFocused = onAnyControlFocused,
                )
                // Text, not icons: IglooIcons has no chapter/audio/CC glyphs, and section 5.4
                // prefers a readable word over a novel symbol at TV distance. Chapters sits
                // with the seek controls — a chapter jump is a seek — ahead of the track pair,
                // matching section 11.8's transport order.
                // Right cancels only on the row's last visible control.
                menus.forEach { menu ->
                    IglooButton(
                        text = menu.text,
                        onClick = { onOpenMenu(menu) },
                        variant = IglooButtonVariant.Ghost,
                        semanticLabel = menu.semanticLabel,
                        overMedia = true,
                        modifier = Modifier
                            .focusRequester(menuButtonRequesters.getValue(menu))
                            .transportEdges(isLast = menu == menus.last())
                            .testTag(menu.tag),
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

    val resumeRequester = remember { FocusRequester() }
    val startOverRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { resumeRequester.requestFocus() }

    IglooDialogCard(
        title = "Resume from ${formatTimecode(resumeAtSec)}?",
        paneTitle = "Resume playback",
        modifier = Modifier.testTag("movie_resume_prompt"),
    ) {
        IglooButton(
            text = "Resume",
            onClick = onResume,
            semanticLabel = "Resume from ${formatSpokenTime(resumeAtSec)}",
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(resumeRequester)
                .pinnedSideways(down = startOverRequester)
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
                .pinnedSideways(up = resumeRequester)
                .testTag("movie_start_over"),
        )
    }
}

/**
 * One in-player track menu, on the [IglooRadioListDialog] shell. Selection is not dismissal — OK
 * on a row switches the track and keeps focus, so the user can hear the result and keep
 * adjusting; the engine re-emits its tracks and the `selected` marks follow.
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
    val noneSelected = options.none { it.selected }
    val noneRows = if (noneRow != null) 1 else 0
    IglooRadioListDialog(
        title = title,
        rowCount = options.size + noneRows,
        // Entry focus lands on the selected row, matching the pre-play dialog.
        entryIndex = if (noneRow != null && noneSelected) {
            0
        } else {
            options.indexOfFirst { it.selected }.takeIf { it >= 0 }?.plus(noneRows) ?: 0
        },
        onDismiss = onDismiss,
        testTag = "movie_track_menu",
        doneTestTag = "movie_track_done",
        footer = {
            if (footerMessage != null) {
                IglooInlineError(
                    message = footerMessage,
                    // Polite: the rows keep focus, and nothing the user did has failed — the
                    // choice was declined with a reason, mid-adjustment.
                    liveRegionMode = LiveRegionMode.Polite,
                    modifier = Modifier.testTag("movie_track_refusal"),
                )
            }
        },
    ) { rowFocus ->
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
}

/**
 * The chapter menu, on the [IglooRadioListDialog] shell. Unlike a track menu, picking a row here
 * is dismissal — a chapter pick is a jump, and its result is the picture hidden behind this scrim,
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
    val activeIndex = activeChapterIndex(chapters, currentTimeSec)
    IglooRadioListDialog(
        title = "Chapters",
        rowCount = chapters.size,
        // Before the first chapter begins, no row is marked and focus falls to the first.
        entryIndex = activeIndex.coerceAtLeast(0),
        onDismiss = onDismiss,
        testTag = "movie_chapter_menu",
        doneTestTag = "movie_chapter_done",
    ) { rowFocus ->
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
}

/** Phases in which a dropped play intent is a pause of something the viewer was watching. */
private val PROGRESS_FLUSH_PHASES = setOf(
    VideoPlayerPhase.Playing,
    VideoPlayerPhase.Paused,
    VideoPlayerPhase.Buffering,
)
