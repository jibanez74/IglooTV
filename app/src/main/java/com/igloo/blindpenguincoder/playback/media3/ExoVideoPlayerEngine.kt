// ExoPlayer's media-source and subtitle surfaces are marked unstable; the engine seam keeps the
// instability from spreading above this package.
@file:androidx.annotation.OptIn(UnstableApi::class)

package com.igloo.blindpenguincoder.playback.media3

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import com.igloo.blindpenguincoder.data.model.PlaybackMode
import com.igloo.blindpenguincoder.playback.hls.HLS_SEEK_SETTLE_MS
import com.igloo.blindpenguincoder.playback.hls.HlsSessionController
import com.igloo.blindpenguincoder.playback.hls.HlsSessionStart
import com.igloo.blindpenguincoder.playback.hls.HlsStartException
import com.igloo.blindpenguincoder.playback.hls.effectivePlaybackMode
import com.igloo.blindpenguincoder.playback.hls.hlsResumeStartSec
import com.igloo.blindpenguincoder.playback.hls.isPastEndHlsSegment
import com.igloo.blindpenguincoder.playback.hls.sessionLostRecoveriesAt
import com.igloo.blindpenguincoder.playback.hls.shouldRebaseHlsSeek
import com.igloo.blindpenguincoder.playback.hls.shouldRebasePendingHlsSeek
import com.igloo.blindpenguincoder.playback.hls.shouldRecoverLostHlsSession
import com.igloo.blindpenguincoder.playback.model.HlsAudioProfile
import com.igloo.blindpenguincoder.playback.model.VideoPlayRequest
import com.igloo.blindpenguincoder.playback.model.VideoPlayerEvent
import com.igloo.blindpenguincoder.playback.model.PlayableAudioTrack
import com.igloo.blindpenguincoder.playback.model.PlaybackGateResult
import com.igloo.blindpenguincoder.playback.model.TrackOption
import com.igloo.blindpenguincoder.playback.model.availablePlaybackModes
import com.igloo.blindpenguincoder.playback.model.evaluatePlaybackGate
import com.igloo.blindpenguincoder.playback.model.hlsAudioConversionFor
import com.igloo.blindpenguincoder.playback.model.noun
import com.igloo.blindpenguincoder.playback.model.playbackModeLabel
import com.igloo.blindpenguincoder.playback.model.subtitleRenderableInMode
import kotlin.math.floor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * The real engine: one ExoPlayer whose source is swapped in place between the direct
 * progressive stream and backend HLS sessions — one player instance means the surface, the
 * MediaSession, and audio focus all survive a quality or audio switch. The renderers keep
 * their default audio configuration — `DefaultAudioSink` negotiates passthrough with the
 * current output route on its own, which is how TrueHD/DTS-HD/Atmos reach a receiver
 * untouched under direct play; nothing here may narrow that.
 *
 * Every position that crosses the engine seam is in absolute movie seconds. An HLS session's
 * media starts at the server-reported actual start; [timelineOffsetSec] holds that offset and
 * this class is the only place it exists.
 */
internal class ExoVideoPlayerEngine(
    context: Context,
    private val request: VideoPlayRequest,
    private val services: VideoPlaybackServices,
) : VideoPlayerEngine {

    private val _events = MutableSharedFlow<VideoPlayerEvent>(replay = 64)
    override val events: SharedFlow<VideoPlayerEvent> = _events.asSharedFlow()

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val controller =
        HlsSessionController(request.media, services.hlsSessionApi, scope, services.stopScope)

    /**
     * Last successfully committed user intent; every seek, audio swap, and recovery asks for it
     * again. Resolved through [resolveModeForAudio] at construction: a Direct request whose track
     * needs audio conversion starts (and reports itself upstream) as Remux from the first frame.
     */
    private var committedRequestedMode = resolveModeForAudio(request.mode, request.audioTypeIndex)
    /** A switch whose manifest has not resolved yet; keeps repeated OK presses idempotent. */
    private var pendingMode: PlaybackMode? = null
    /** What the prepared source actually uses; the Quality menu reports this truth. */
    private var effectiveMode = committedRequestedMode
    override var currentAudioTypeIndex: Int? = request.audioTypeIndex
        private set
    override var currentSubtitleTypeIndex: Int? = request.subtitleTypeIndex
        private set
    /** Absolute movie second where the prepared source's own zero sits; 0 for direct play. */
    private var timelineOffsetSec = 0.0
    private var restartJob: Job? = null
    private var restartGeneration = 0L
    /** The HLS session being established, if any; cleared once its source is prepared. */
    private var pendingRestart: PendingRestart? = null
    /** Absolute movie second where playback last came to rest; seeks are measured from here. */
    private var settledAbsoluteSec = 0.0
    /** A rebase waiting out [HLS_SEEK_SETTLE_MS]; the current source plays on meanwhile. */
    private var heldRebaseTargetSec: Double? = null
    private var heldRebaseJob: Job? = null
    private var sessionLostRecoveries = 0
    private var lastSessionLostRecoveryAtMs = 0L
    private var initialSelectionApplied = false
    private val playbackIntent = PlaybackIntent()

    private val player: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(videoPlaybackAudioAttributes, /* handleAudioFocus= */ true)
        .build()

    private val session = buildVideoMediaSession(
        context,
        AbsoluteTimelinePlayer(player),
        request,
        services.progressiveDataSourceFactory,
    )

    private val hlsMediaSourceFactory = DefaultMediaSourceFactory(services.hlsDataSourceFactory)
        .setLoadErrorHandlingPolicy(HlsLoadErrorPolicy())

    private val subtitleView = SubtitleView(context).apply {
        setUserDefaultStyle()
        setUserDefaultTextSize()
        keepScreenOn = true
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> emit(VideoPlayerEvent.Ready(durationSec()))
                Player.STATE_BUFFERING -> emit(VideoPlayerEvent.Buffering)
                Player.STATE_ENDED -> emit(VideoPlayerEvent.Ended)
                else -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            emit(VideoPlayerEvent.IsPlayingChanged(isPlaying))
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            emitDesiredPlayWhenReady()
        }

        override fun onTracksChanged(tracks: Tracks) {
            // The pre-swap choice can only be applied once real track groups exist; doing it
            // here re-triggers this callback, which then reports the updated selected flags.
            if (!initialSelectionApplied && tracks.groups.isNotEmpty()) {
                initialSelectionApplied = true
                applySelectionAfterSourceSwap(tracks)
            }
            emit(
                VideoPlayerEvent.TracksChanged(
                    audio = when {
                        isHls -> hlsAudioTrackOptions(request.audioTracks, effectiveAudioOrdinal())
                        else -> audioTrackOptions(tracks)
                    },
                    subtitles = when {
                        isHls -> hlsSubtitleTrackOptions(
                            tracks,
                            request.subtitleTracks,
                            currentSubtitleTypeIndex,
                        )
                        else -> subtitleTrackOptions(tracks)
                    },
                ),
            )
        }

        override fun onCues(cueGroup: CueGroup) {
            subtitleView.setCues(cueGroup.cues)
        }

        override fun onPlayerError(error: PlaybackException) {
            if (isHls && endAtPastEndSegment(error)) return
            if (isHls && recoverFromLostHlsSession(error)) return
            transitionToTerminal(errorEvent(error))
        }
    }

    private val ticker = PlaybackTicker(handler) {
        if (!playbackIntent.acceptsCommands) {
            false
        } else {
            // A pending switch leaves the outgoing source frozen, and a held rebase leaves it
            // playing, at a position that is no longer where the viewer is going; reporting it
            // snaps the seek bar back and feeds the progress cadence a stale second.
            if (pendingMode == null && heldRebaseTargetSec == null) {
                // A seek turns the player BUFFERING at once, so READY means it has come to rest.
                if (player.playbackState == Player.STATE_READY) {
                    settledAbsoluteSec = currentAbsoluteSec()
                }
                if (
                    player.playbackState == Player.STATE_READY ||
                    player.playbackState == Player.STATE_BUFFERING
                ) {
                    emit(VideoPlayerEvent.Time(currentAbsoluteSec(), durationSec()))
                }
            }
            true
        }
    }

    private val isHls: Boolean
        get() = effectiveMode != PlaybackMode.Direct

    init {
        player.addListener(listener)
    }

    @Composable
    override fun VideoSurface(modifier: Modifier) {
        Box(modifier = modifier) {
            ContentFrame(
                player = player,
                modifier = Modifier.fillMaxSize(),
                surfaceType = SURFACE_TYPE_SURFACE_VIEW,
                contentScale = ContentScale.Fit,
            )
            AndroidView(
                factory = { subtitleView },
                modifier = Modifier
                    .fillMaxSize()
                    .focusProperties { canFocus = false },
            )
        }
    }

    override fun startPlayback(
        startPositionSec: Double?,
        initialPlayWhenReady: Boolean,
        rewindOnResume: Boolean,
    ) {
        if (!playbackIntent.start(initialPlayWhenReady)) return
        emitDesiredPlayWhenReady()
        ticker.start()
        val targetSec = when {
            !isHls -> startPositionSec ?: 0.0
            // Resuming over HLS rewinds a little; the session then starts right at the target.
            // Only a genuine resume earns it — a reconstruction already knows this visit's own
            // playhead, and rewinding again on every one walks the movie backwards.
            startPositionSec != null && rewindOnResume ->
                hlsResumeStartSec(startPositionSec).toDouble()
            startPositionSec != null -> startPositionSec
            else -> 0.0
        }
        restartInPlace(committedRequestedMode, currentAudioTypeIndex, targetSec)
    }

    override fun play() {
        if (!playbackIntent.play()) return
        emitDesiredPlayWhenReady()
        // A pending switch deliberately holds the outgoing source frozen on screen; resuming it
        // would play media the viewer is already leaving. `prepareSource` applies the intent.
        if (pendingMode != null) return
        player.play()
    }

    override fun pause() {
        if (!playbackIntent.pause()) return
        emitDesiredPlayWhenReady()
        player.pause()
    }

    override fun seekTo(seconds: Double) {
        if (!playbackIntent.acceptsCommands) return
        val pending = pendingRestart
        when {
            // The frozen source is about to be replaced, so it is no measure: a seek the new
            // session covers only moves where its source will be prepared.
            pending != null -> if (shouldRebasePendingHlsSeek(seconds, pending.startSec)) {
                holdRebase(seconds)
            } else {
                dropHeldRebase()
                pendingRestart = pending.copy(targetSec = seconds)
            }
            !isHls -> player.seekTo((seconds * 1000).toLong())
            shouldRebaseHlsSeek(seconds, timelineOffsetSec, settledAbsoluteSec) -> holdRebase(seconds)
            else -> {
                // Back in range: a far target the same run passed through is dropped.
                dropHeldRebase()
                player.seekTo(((seconds - timelineOffsetSec) * 1000).toLong())
            }
        }
    }

    /**
     * Rebases to [targetSec] once seeking has been quiet for [HLS_SEEK_SETTLE_MS]; a later seek
     * re-arms it. A switch still pending when it fires keeps its mode.
     */
    private fun holdRebase(targetSec: Double) {
        heldRebaseTargetSec = targetSec
        heldRebaseJob?.cancel()
        heldRebaseJob = scope.launch {
            delay(HLS_SEEK_SETTLE_MS)
            heldRebaseJob = null
            heldRebaseTargetSec = null
            restartInPlace(pendingMode ?: committedRequestedMode, currentAudioTypeIndex, targetSec)
        }
    }

    private fun dropHeldRebase() {
        heldRebaseJob?.cancel()
        heldRebaseJob = null
        heldRebaseTargetSec = null
    }

    override fun selectAudioTrack(optionId: String) {
        if (!playbackIntent.acceptsCommands) return
        val hlsOrdinal = parseHlsAudioOptionId(optionId)
        if (hlsOrdinal != null) {
            // Only one audio track exists in an HLS mux; another track is another session.
            if (hlsOrdinal == effectiveAudioOrdinal()) return
            restartInPlace(committedRequestedMode, hlsOrdinal, intendedAbsoluteSec())
            return
        }
        val override = overrideFor(optionId) ?: return
        // Remembered as the type ordinal so the choice survives a later switch into HLS.
        val typeIndex = typeIndexForOptionId(player.currentTracks, C.TRACK_TYPE_AUDIO, optionId)
        typeIndex?.let { currentAudioTypeIndex = it }
        // A track that needs the audio conversion cannot stay under Direct — the restart
        // resolves to Remux with the converted soundtrack instead of an in-place override.
        if (typeIndex != null && conversionFor(typeIndex) != null) {
            restartInPlace(committedRequestedMode, typeIndex, intendedAbsoluteSec())
            return
        }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(override)
            .build()
    }

    override fun selectSubtitleTrack(optionId: String?) {
        if (!playbackIntent.acceptsCommands) return
        val builder = player.trackSelectionParameters.buildUpon()
        if (optionId == null) {
            currentSubtitleTypeIndex = null
            builder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            // Resolve the ordinal before committing anything: an id that maps to no wire
            // ordinal must not half-apply, or the remembered choice gets corrupted to null
            // while a track still renders.
            val typeIndex = subtitleTypeIndexForOption(player.currentTracks, optionId) ?: return
            val override = overrideFor(optionId) ?: return
            currentSubtitleTypeIndex = typeIndex
            builder.setOverrideForType(override)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        }
        player.trackSelectionParameters = builder.build()
    }

    override fun selectPlaybackMode(optionId: String) {
        if (!playbackIntent.acceptsCommands) return
        val picked = PlaybackMode.entries.firstOrNull { it.name == optionId } ?: return
        // Picking Direct with an unreliable track resolves straight to Remux — usually the
        // active mode already, making the press a no-op instead of a wasted session restart.
        val mode = resolveModeForAudio(picked, currentAudioTypeIndex)
        if (mode == pendingMode) return
        if (mode == effectiveMode) {
            if (pendingMode != null) cancelPendingModeSwitch()
            return
        }
        refusalFor(mode)?.let { message ->
            emit(VideoPlayerEvent.ModeRefused(message))
            return
        }
        restartInPlace(mode, currentAudioTypeIndex, intendedAbsoluteSec())
    }

    /**
     * Why this mode cannot start, or null to proceed. The same pre-play gate, over the video and
     * the audio track this session would actually use: switching to Direct is the one in-player
     * choice that can land on a video codec this TV cannot decode, or a track it has no decoder
     * and no passthrough for, and a black screen or silent video is worse than the refusal that
     * names the codec. The mode is never substituted.
     */
    private fun refusalFor(mode: PlaybackMode): String? {
        val gate = evaluatePlaybackGate(
            request = request,
            canPlayVideoMime = services.canPlayVideoMime,
            canPlayAudioMime = services.canPlayAudioMime,
            mode = mode,
            track = effectiveAudioOrdinal()?.let(request.audioTracks::getOrNull),
        )
        return (gate as? PlaybackGateResult.Blocked)?.message
    }

    override fun onHostPaused() {
        if (!playbackIntent.hostPaused()) return
        emitDesiredPlayWhenReady()
        player.pause()
    }

    override fun onHostResumed() {
        if (!playbackIntent.hostResumed()) return
        // Stays paused; resuming a movie that went to standby is the user's call.
    }

    override fun release() {
        if (!playbackIntent.release()) return
        restartGeneration++
        pendingMode = null
        ticker.stop()
        restartJob?.cancel()
        restartJob = null
        controller.releaseAndStop()
        scope.cancel()
        // Media3 requires the session gone before its player.
        session.release()
        player.release()
    }

    /**
     * The one path that swaps what the player is playing: initial start, quality switch, HLS
     * audio switch, out-of-window seek, and lost-session recovery all land here. For HLS the
     * swap happens only after the manifest preflight succeeds, so the old media keeps the
     * screen (frozen) through capacity waits instead of going black.
     */
    private fun restartInPlace(
        requestedModeArg: PlaybackMode,
        audioTypeIndex: Int?,
        targetAbsoluteSec: Double,
    ) {
        if (!playbackIntent.acceptsCommands) return
        // Recovery, keepalive, and seeks re-enter here with the stored intent; resolving again
        // means no entry point can regress into Direct with a track that needs conversion.
        val mode = resolveModeForAudio(requestedModeArg, audioTypeIndex)
        restartJob?.cancel()
        restartJob = null
        pendingRestart = null
        // Every caller passes where the viewer is headed, so a held rebase is answered here.
        dropHeldRebase()
        val generation = ++restartGeneration
        pendingMode = mode
        currentAudioTypeIndex = audioTypeIndex
        // currentSubtitleTypeIndex is deliberately not reassigned: it is the cross-mode memory
        // of the user's choice, and prepareSource decides per source whether it can render.
        initialSelectionApplied = false
        controller.cancelKeepalive()
        // Freeze the old source while preflight runs without changing transport intent.
        player.pause()
        emit(VideoPlayerEvent.Buffering)
        emitQualityOptions()
        if (mode == PlaybackMode.Direct) {
            // Leaving HLS ends and rotates that generation before Direct can later start HLS.
            controller.releaseAndStop()
            effectiveMode = PlaybackMode.Direct
            timelineOffsetSec = 0.0
            prepareSource(directMediaSource(), (targetAbsoluteSec * 1000).toLong())
            committedRequestedMode = mode
            pendingMode = null
            emitQualityOptions()
            return
        }
        val profileId = requireNotNull(mode.hlsProfileId)
        val startSec = floor(targetAbsoluteSec).toInt().coerceAtLeast(0)
        pendingRestart = PendingRestart(startSec, targetAbsoluteSec)
        // Reserve synchronously so selecting Direct again can rotate even before launch runs.
        controller.reserveGeneration()
        restartJob = scope.launch {
            try {
                val start = controller.start(
                    profileId = profileId,
                    audioTypeIndex = audioTypeIndex ?: request.effectiveAudioTypeIndex,
                    audioProfile = conversionFor(audioTypeIndex),
                    startSec = startSec,
                ) { message ->
                    if (isRestartCurrent(generation)) {
                        emit(VideoPlayerEvent.StatusMessage(message))
                    }
                }
                if (!isRestartCurrent(generation)) return@launch
                effectiveMode = effectivePlaybackMode(mode, start.effectiveProfileId)
                timelineOffsetSec = start.actualStartSec
                // Seeks made during the preflight may have moved the target within the session.
                val targetSec = pendingRestart?.targetSec ?: targetAbsoluteSec
                val relativeMs = ((targetSec - start.actualStartSec).coerceAtLeast(0.0) * 1000).toLong()
                prepareSource(hlsMediaSource(start), relativeMs)
                committedRequestedMode = mode
                pendingMode = null
                controller.startKeepalive(::onKeepaliveSessionLost)
                emitQualityOptions()
            } catch (failure: HlsStartException) {
                if (isRestartCurrent(generation)) {
                    rollbackPendingMode()
                    transitionToTerminal(
                        VideoPlayerEvent.Error(
                            failure.message ?: "Playback failed.",
                            failure.unauthorized,
                        ),
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                // The repository deliberately rethrows programming errors rather than dressing
                // them as transport failures. They still belong on the error surface: this scope
                // has no exception handler, so an escape would kill the app mid-movie.
                if (isRestartCurrent(generation)) {
                    rollbackPendingMode()
                    transitionToTerminal(
                        VideoPlayerEvent.Error(
                            failure.message?.let { "Playback failed ($it)." } ?: "Playback failed.",
                        ),
                    )
                }
            } finally {
                if (restartGeneration == generation) {
                    pendingMode = null
                    pendingRestart = null
                    restartJob = null
                }
            }
        }
    }

    private fun prepareSource(source: MediaSource, positionMs: Long) {
        if (!playbackIntent.acceptsCommands) return
        // The chosen ordinal may name a stream this source cannot serve (an image-based
        // subtitle under HLS). Then the text renderer goes off outright and the stale override
        // is dropped: leaving the type enabled with no resolvable override would let Media3
        // auto-select an unrelated sideloaded track.
        val subtitleOn = request.subtitleRenderableInMode(currentSubtitleTypeIndex, isHls)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitleOn)
            .apply { if (!subtitleOn) clearOverridesOfType(C.TRACK_TYPE_TEXT) }
            .build()
        player.setMediaSource(source, positionMs)
        player.playWhenReady = playbackIntent.shouldPlay
        player.prepare()
        settledAbsoluteSec = timelineOffsetSec + positionMs / 1000.0
    }

    /** Selecting the active row is an explicit cancellation of a different pending switch. */
    private fun cancelPendingModeSwitch() {
        restartJob?.cancel()
        restartJob = null
        // Seeks made during the switch went with it; playback stays where the old source is.
        pendingRestart = null
        dropHeldRebase()
        restartGeneration++
        val canceledWhileDirect = effectiveMode == PlaybackMode.Direct
        pendingMode = null
        if (canceledWhileDirect) {
            // The unused HLS preflight may already have reached the backend; stop and rotate it.
            controller.releaseAndStop()
        } else {
            // The abandoned preflight may also have started a new-profile session server-side.
            // It cannot be stopped from here — stop is keyed by the shared session UUID, so it
            // would take the live session with it; the server's idle TTL is what reaps it.
            controller.startKeepalive(::onKeepaliveSessionLost)
        }
        player.playWhenReady = playbackIntent.shouldPlay
        emit(VideoPlayerEvent.StatusMessage(null))
        emitQualityOptions()
    }

    private fun rollbackPendingMode() {
        pendingMode = null
        emitQualityOptions()
    }

    private fun isRestartCurrent(generation: Long): Boolean =
        playbackIntent.acceptsCommands && restartGeneration == generation

    private fun directMediaSource(): MediaSource =
        ProgressiveMediaSource.Factory(services.progressiveDataSourceFactory)
            .createMediaSource(
                MediaItem.Builder()
                    .setUri(services.directStreamUrl(request.media))
                    .setMimeType(request.mimeType)
                    .setMediaMetadata(videoMediaMetadata(request))
                    .build(),
            )

    private fun hlsMediaSource(start: HlsSessionStart): MediaSource =
        hlsMediaSourceFactory.createMediaSource(
            MediaItem.Builder()
                .setUri(start.playlistUrl)
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .setMediaMetadata(videoMediaMetadata(request))
                .setSubtitleConfigurations(textSubtitleConfigs(start.actualStartSec))
                // A remux session is a live EVENT playlist until FFmpeg finishes; pinning the
                // speed stops ExoPlayer's live catch-up from subtly fast-forwarding the movie.
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setMinPlaybackSpeed(1f)
                        .setMaxPlaybackSpeed(1f)
                        .build(),
                )
                .build(),
        )

    /**
     * Every text subtitle rides as a sideloaded WebVTT extraction (the playlist itself never
     * carries subtitle renditions), stamped with its wire ordinal so selection survives the
     * image-based rows that are absent here. The URL's `start` matches the session's actual
     * start, which is what puts the cues on this session's rebased timeline.
     */
    private fun textSubtitleConfigs(actualStartSec: Double): List<MediaItem.SubtitleConfiguration> =
        request.subtitleTracks.mapIndexedNotNull { index, track ->
            if (track.imageBased) return@mapIndexedNotNull null
            MediaItem.SubtitleConfiguration.Builder(
                Uri.parse(
                    services.hlsSessionApi.subtitleUrl(request.media, index, actualStartSec),
                ),
            )
                .setMimeType(MimeTypes.TEXT_VTT)
                .setId("$SIDELOADED_SUBTITLE_ID_PREFIX$index")
                .setLabel(track.label)
                .build()
        }

    private fun applySelectionAfterSourceSwap(tracks: Tracks) {
        val builder = player.trackSelectionParameters.buildUpon()
        if (!isHls) {
            currentAudioTypeIndex
                ?.let { trackOptionId(tracks, C.TRACK_TYPE_AUDIO, it) }
                ?.let { overrideFor(it, tracks) }
                ?.let(builder::setOverrideForType)
        }
        // A null option id here is the deterministic-off path, not a gap: under HLS a
        // remembered image-based ordinal matches no sideloaded group, and prepareSource has
        // already disabled the text type for it. Back under Direct the container's Nth text
        // group re-resolves and the override below restores the choice.
        currentSubtitleTypeIndex
            ?.let { subtitleOptionIdFor(tracks, it) }
            ?.let { overrideFor(it, tracks) }
            ?.let {
                builder.setOverrideForType(it)
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            }
        player.trackSelectionParameters = builder.build()
    }

    /** Direct exposes the container's Nth text group; HLS matches the stamped format id. */
    private fun subtitleOptionIdFor(tracks: Tracks, typeIndex: Int): String? = when {
        isHls -> trackOptionIdForFormatId(
            tracks,
            C.TRACK_TYPE_TEXT,
            "$SIDELOADED_SUBTITLE_ID_PREFIX$typeIndex",
        )
        else -> trackOptionId(tracks, C.TRACK_TYPE_TEXT, typeIndex)
    }

    private fun subtitleTypeIndexForOption(tracks: Tracks, optionId: String): Int? = when {
        isHls -> formatIdForOptionId(tracks, optionId)
            ?.removePrefix(SIDELOADED_SUBTITLE_ID_PREFIX)
            ?.toIntOrNull()
        else -> typeIndexForOptionId(tracks, C.TRACK_TYPE_TEXT, optionId)
    }

    /** The concrete audio ordinal an HLS session would use right now. */
    private fun effectiveAudioOrdinal(): Int? =
        currentAudioTypeIndex ?: request.effectiveAudioTypeIndex

    /** The track an HLS session with this ordinal would use; null = video-only movie. */
    private fun trackFor(audioTypeIndex: Int?): PlayableAudioTrack? =
        (audioTypeIndex ?: request.effectiveAudioTypeIndex)?.let(request.audioTracks::getOrNull)

    /** The server-side conversion this track needs, or null for legacy audio handling. */
    private fun conversionFor(audioTypeIndex: Int?): HlsAudioProfile? =
        hlsAudioConversionFor(trackFor(audioTypeIndex))

    /**
     * Direct with a track that needs conversion becomes Remux — the video plays as-is while
     * the server converts the soundtrack; every other combination is the user's ask untouched.
     */
    private fun resolveModeForAudio(mode: PlaybackMode, audioTypeIndex: Int?): PlaybackMode =
        if (mode == PlaybackMode.Direct && conversionFor(audioTypeIndex) != null) {
            PlaybackMode.Remux
        } else {
            mode
        }

    /** The in-player menu exposes the same normative seven-mode contract as pre-play settings. */
    private fun emitQualityOptions() {
        val options = availablePlaybackModes()
            .map { TrackOption(id = it.name, label = playbackModeLabel(it), selected = it == effectiveMode) }
        emit(
            VideoPlayerEvent.QualityOptionsChanged(
                options,
                pendingMode ?: committedRequestedMode,
            ),
        )
    }

    private fun onKeepaliveSessionLost() {
        if (!playbackIntent.acceptsCommands) return
        controller.noteSessionLost()
        restartInPlace(committedRequestedMode, currentAudioTypeIndex, intendedAbsoluteSec())
    }

    /**
     * The server's end-of-media 404 ends playback like STATE_ENDED would. Media3 raises a load
     * error only once the buffer before it has played out, so nothing the viewer could still
     * watch is cut off.
     */
    private fun endAtPastEndSegment(error: PlaybackException): Boolean {
        val http = httpErrorCause(error)
        if (!isPastEndHlsSegment(http?.responseCode, http?.dataSpec?.uri?.path, http?.headerFields)) {
            return false
        }
        emit(VideoPlayerEvent.Ended)
        return true
    }

    /**
     * A recoverable load failure restarts the session in place at the current position via the
     * manifest preflight in [restartInPlace]; the decision itself lives in
     * [shouldRecoverLostHlsSession], and the budget per incident in [sessionLostRecoveriesAt].
     */
    private fun recoverFromLostHlsSession(error: PlaybackException): Boolean {
        val http = httpErrorCause(error)
        val nowMs = SystemClock.elapsedRealtime()
        sessionLostRecoveries =
            sessionLostRecoveriesAt(sessionLostRecoveries, lastSessionLostRecoveryAtMs, nowMs)
        if (
            !shouldRecoverLostHlsSession(
                responseCode = http?.responseCode,
                requestPath = http?.dataSpec?.uri?.path,
                recoveries = sessionLostRecoveries,
            )
        ) return false
        sessionLostRecoveries++
        lastSessionLostRecoveryAtMs = nowMs
        controller.noteSessionLost()
        restartInPlace(pendingMode ?: committedRequestedMode, currentAudioTypeIndex, intendedAbsoluteSec())
        return true
    }

    private fun overrideFor(
        optionId: String,
        tracks: Tracks = player.currentTracks,
    ): TrackSelectionOverride? {
        val (groupIndex, trackIndex) = parseTrackOptionId(optionId) ?: return null
        val group = tracks.groups.getOrNull(groupIndex) ?: return null
        if (trackIndex >= group.length) return null
        return TrackSelectionOverride(group.mediaTrackGroup, trackIndex)
    }

    private fun currentAbsoluteSec(): Double =
        player.currentPosition / 1000.0 + timelineOffsetSec

    /**
     * Where the viewer is headed: a held rebase's target, then a pending session's, then the
     * playhead. A switch or recovery made mid-seek, or a relative seek built on one, starts there
     * rather than from a source that is about to be replaced.
     */
    private fun intendedAbsoluteSec(): Double =
        heldRebaseTargetSec ?: pendingRestart?.targetSec ?: currentAbsoluteSec()

    /**
     * Under HLS the player only ever sees one session's window, so the movie's real duration
     * comes from the request; the playlist-derived value is a fallback for the rare movie row
     * without one.
     */
    private fun durationSec(): Double {
        val playerDuration = player.duration.takeIf { it != C.TIME_UNSET }?.div(1000.0)
        return when {
            isHls -> request.durationSec ?: playerDuration?.plus(timelineOffsetSec) ?: 0.0
            else -> playerDuration ?: 0.0
        }
    }

    private fun errorEvent(error: PlaybackException): VideoPlayerEvent.Error {
        val http = httpErrorCause(error)
        val failure = playerFailure(
            errorCode = error.errorCode,
            errorCodeName = error.errorCodeName,
            httpResponseCode = http?.responseCode,
            isHls = isHls,
            httpRequestPath = http?.dataSpec?.uri?.path,
            mediaNoun = request.media.noun,
        )
        return VideoPlayerEvent.Error(failure.message, failure.unauthorized)
    }

    private fun emit(event: VideoPlayerEvent) {
        if (playbackIntent.released) return
        _events.tryEmit(event)
    }

    private fun emitDesiredPlayWhenReady() {
        emit(VideoPlayerEvent.PlayWhenReadyChanged(playbackIntent.shouldPlay))
    }

    /**
     * One terminal boundary for player and HLS-start failures. The surface and session object
     * stay alive for the error screen, but all loading, transport, keepalive, and backend work
     * stops until the screen creates a fresh engine for an explicit Retry.
     */
    private fun transitionToTerminal(error: VideoPlayerEvent.Error) {
        if (!playbackIntent.failTerminal()) return
        restartGeneration++
        pendingMode = null
        pendingRestart = null
        ticker.stop()
        restartJob?.cancel()
        restartJob = null
        dropHeldRebase()
        controller.releaseAndStop()
        player.stop()
        player.clearMediaItems()
        emitDesiredPlayWhenReady()
        emit(error)
    }

    /**
     * [IntentRoutingPlayer] shifted onto the absolute movie timeline, so the system's
     * now-playing surface and Assistant seeks agree with the on-screen seek bar and every seek
     * routes through the engine's rebase logic.
     */
    private inner class AbsoluteTimelinePlayer(player: Player) : IntentRoutingPlayer(
        player,
        playbackIntent,
        onPlay = { this@ExoVideoPlayerEngine.play() },
        onPause = { this@ExoVideoPlayerEngine.pause() },
    ) {
        private val offsetMs: Long
            get() = (timelineOffsetSec * 1000).toLong()

        override fun getCurrentPosition(): Long = super.getCurrentPosition() + offsetMs
        override fun getContentPosition(): Long = super.getContentPosition() + offsetMs
        override fun getBufferedPosition(): Long = super.getBufferedPosition() + offsetMs

        override fun getDuration(): Long =
            durationSec().takeIf { it > 0.0 }?.let { (it * 1000).toLong() } ?: super.getDuration()

        override fun getContentDuration(): Long = getDuration()

        override fun seekTo(positionMs: Long) {
            this@ExoVideoPlayerEngine.seekTo(positionMs / 1000.0)
        }

        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            this@ExoVideoPlayerEngine.seekTo(positionMs / 1000.0)
        }

        override fun seekBack() {
            this@ExoVideoPlayerEngine.seekTo(intendedAbsoluteSec() - seekBackIncrement / 1000.0)
        }

        override fun seekForward() {
            this@ExoVideoPlayerEngine.seekTo(intendedAbsoluteSec() + seekForwardIncrement / 1000.0)
        }
    }

    /** Instrumentation seam: the deterministic subtitle off/on contract is asserted on these. */
    internal val currentTrackSelectionParameters get() = player.trackSelectionParameters

    /** A session being established from [startSec] whose source will be prepared at [targetSec]. */
    private data class PendingRestart(val startSec: Int, val targetSec: Double)
}

fun exoVideoPlayerEngine(
    context: Context,
    request: VideoPlayRequest,
    services: VideoPlaybackServices,
): VideoPlayerEngine = ExoVideoPlayerEngine(context, request, services)
