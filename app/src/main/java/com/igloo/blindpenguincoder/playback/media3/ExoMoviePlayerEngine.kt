// ExoPlayer's media-source and subtitle surfaces are marked unstable; the engine seam keeps the
// instability from spreading above this package.
@file:androidx.annotation.OptIn(UnstableApi::class)

package com.igloo.blindpenguincoder.playback.media3

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
import androidx.media3.common.ForwardingPlayer
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
import com.igloo.blindpenguincoder.data.model.hlsProfileId
import com.igloo.blindpenguincoder.playback.hls.HlsSessionController
import com.igloo.blindpenguincoder.playback.hls.HlsSessionStart
import com.igloo.blindpenguincoder.playback.hls.HlsStartException
import com.igloo.blindpenguincoder.playback.hls.effectivePlaybackMode
import com.igloo.blindpenguincoder.playback.hls.hlsResumeStartSec
import com.igloo.blindpenguincoder.playback.hls.isMovieHlsRequestPath
import com.igloo.blindpenguincoder.playback.hls.shouldRebaseHlsSeek
import com.igloo.blindpenguincoder.playback.hls.shouldRecoverLostHlsSession
import com.igloo.blindpenguincoder.playback.model.HlsAudioProfile
import com.igloo.blindpenguincoder.playback.model.MoviePlayRequest
import com.igloo.blindpenguincoder.playback.model.MoviePlayerEvent
import com.igloo.blindpenguincoder.playback.model.PlayableAudioTrack
import com.igloo.blindpenguincoder.playback.model.PlaybackGateResult
import com.igloo.blindpenguincoder.playback.model.TrackOption
import com.igloo.blindpenguincoder.playback.model.availablePlaybackModes
import com.igloo.blindpenguincoder.playback.model.evaluatePlaybackGate
import com.igloo.blindpenguincoder.playback.model.hlsAudioConversionFor
import com.igloo.blindpenguincoder.playback.model.playbackModeLabel
import kotlin.math.floor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
internal class ExoMoviePlayerEngine(
    context: Context,
    private val request: MoviePlayRequest,
    private val services: MoviePlaybackServices,
) : MoviePlayerEngine {

    private val _events = MutableSharedFlow<MoviePlayerEvent>(replay = 64)
    override val events: SharedFlow<MoviePlayerEvent> = _events.asSharedFlow()

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val controller =
        HlsSessionController(request.movieId, services.hlsSessionApi, scope, services.stopScope)

    /**
     * User intent; every seek, audio swap, and recovery asks for this mode again. Resolved
     * through [resolveModeForAudio] already at construction: a Direct request whose track needs
     * the audio conversion starts (and reports itself upstream) as Remux from the first frame.
     */
    private var requestedMode = resolveModeForAudio(request.mode, request.audioTypeIndex)
    /** A switch whose manifest has not resolved yet; keeps repeated OK presses idempotent. */
    private var pendingMode: PlaybackMode? = null
    /** What the prepared source actually uses; the Quality menu reports this truth. */
    private var effectiveMode = requestedMode
    override var currentAudioTypeIndex: Int? = request.audioTypeIndex
        private set
    override var currentSubtitleTypeIndex: Int? = request.subtitleTypeIndex
        private set
    /** Absolute movie second where the prepared source's own zero sits; 0 for direct play. */
    private var timelineOffsetSec = 0.0
    private var restartJob: Job? = null
    private var restartGeneration = 0L
    private var sessionLostRecoveries = 0
    private var initialSelectionApplied = false
    private val playbackIntent = PlaybackIntent()

    private val player: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(moviePlaybackAudioAttributes, /* handleAudioFocus= */ true)
        .build()

    private val session = buildMovieMediaSession(
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
                Player.STATE_READY -> {
                    sessionLostRecoveries = 0
                    emit(MoviePlayerEvent.Ready(durationSec()))
                }
                Player.STATE_BUFFERING -> emit(MoviePlayerEvent.Buffering)
                Player.STATE_ENDED -> emit(MoviePlayerEvent.Ended)
                else -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            emit(MoviePlayerEvent.IsPlayingChanged(isPlaying))
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
                MoviePlayerEvent.TracksChanged(
                    audio = when {
                        isHls -> hlsAudioTrackOptions(request.audioTracks, effectiveAudioOrdinal())
                        else -> audioTrackOptions(tracks)
                    },
                    subtitles = subtitleTrackOptions(tracks),
                ),
            )
        }

        override fun onCues(cueGroup: CueGroup) {
            subtitleView.setCues(cueGroup.cues)
        }

        override fun onPlayerError(error: PlaybackException) {
            if (isHls && recoverFromLostHlsSession(error)) return
            transitionToTerminal(errorEvent(error))
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!playbackIntent.acceptsCommands) return
            // A pending switch leaves the outgoing source frozen at a position that is no longer
            // where the viewer is going; reporting it snaps the seek bar back and feeds the
            // progress cadence a stale second.
            if (pendingMode == null &&
                (
                    player.playbackState == Player.STATE_READY ||
                        player.playbackState == Player.STATE_BUFFERING
                    )
            ) {
                emit(MoviePlayerEvent.Time(currentAbsoluteSec(), durationSec()))
            }
            handler.postDelayed(this, TICK_INTERVAL_MS)
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
        handler.post(ticker)
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
        restartInPlace(requestedMode, currentAudioTypeIndex, targetSec)
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
        if (!isHls) {
            player.seekTo((seconds * 1000).toLong())
            return
        }
        if (shouldRebaseHlsSeek(seconds, timelineOffsetSec, currentAbsoluteSec())) {
            restartInPlace(requestedMode, currentAudioTypeIndex, seconds)
        } else {
            player.seekTo(((seconds - timelineOffsetSec) * 1000).toLong())
        }
    }

    override fun selectAudioTrack(optionId: String) {
        if (!playbackIntent.acceptsCommands) return
        val hlsOrdinal = parseHlsAudioOptionId(optionId)
        if (hlsOrdinal != null) {
            // Only one audio track exists in an HLS mux; another track is another session.
            if (hlsOrdinal == effectiveAudioOrdinal()) return
            restartInPlace(requestedMode, hlsOrdinal, currentAbsoluteSec())
            return
        }
        val override = overrideFor(optionId) ?: return
        // Remembered as the type ordinal so the choice survives a later switch into HLS.
        val typeIndex = typeIndexForOptionId(player.currentTracks, C.TRACK_TYPE_AUDIO, optionId)
        typeIndex?.let { currentAudioTypeIndex = it }
        // A track that needs the audio conversion cannot stay under Direct — the restart
        // resolves to Remux with the converted soundtrack instead of an in-place override.
        if (typeIndex != null && conversionFor(typeIndex) != null) {
            restartInPlace(requestedMode, typeIndex, currentAbsoluteSec())
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
            val override = overrideFor(optionId) ?: return
            currentSubtitleTypeIndex = subtitleTypeIndexForOption(player.currentTracks, optionId)
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
            emit(MoviePlayerEvent.ModeRefused(message))
            return
        }
        restartInPlace(mode, currentAudioTypeIndex, currentAbsoluteSec())
    }

    /**
     * Why this mode cannot start, or null to proceed. The same pre-play gate, over the audio
     * track this session would actually use: switching to Direct is the one in-player choice
     * that can land on a track this TV has no decoder and no passthrough for, and silent video
     * is worse than the refusal that names the codec. The mode is never substituted.
     */
    private fun refusalFor(mode: PlaybackMode): String? {
        val track = effectiveAudioOrdinal()?.let(request.audioTracks::getOrNull)
        val gate = evaluatePlaybackGate(
            mode = mode,
            audioCodec = track?.codec,
            audioCodecProfile = track?.codecProfile,
            audioChannels = track?.channels,
            audioLabel = track?.label,
            canPlayMime = { mime -> services.canPlayAudioMime(mime, track?.channels) },
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
        handler.removeCallbacks(ticker)
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
        val generation = ++restartGeneration
        requestedMode = mode
        pendingMode = mode
        currentAudioTypeIndex = audioTypeIndex
        initialSelectionApplied = false
        controller.cancelKeepalive()
        // Freeze the old source while preflight runs without changing transport intent.
        player.pause()
        emit(MoviePlayerEvent.Buffering)
        if (mode == PlaybackMode.Direct) {
            // Leaving HLS ends and rotates that generation before Direct can later start HLS.
            controller.releaseAndStop()
            effectiveMode = PlaybackMode.Direct
            pendingMode = null
            timelineOffsetSec = 0.0
            prepareSource(directMediaSource(), (targetAbsoluteSec * 1000).toLong())
            emitQualityOptions()
            return
        }
        val profileId = requireNotNull(mode.hlsProfileId)
        // Reserve synchronously so selecting Direct again can rotate even before launch runs.
        controller.reserveGeneration()
        restartJob = scope.launch {
            try {
                val start = controller.start(
                    profileId = profileId,
                    audioTypeIndex = audioTypeIndex ?: request.effectiveAudioTypeIndex,
                    audioProfile = conversionFor(audioTypeIndex),
                    startSec = floor(targetAbsoluteSec).toInt().coerceAtLeast(0),
                ) { message ->
                    if (isRestartCurrent(generation)) {
                        emit(MoviePlayerEvent.StatusMessage(message))
                    }
                }
                if (!isRestartCurrent(generation)) return@launch
                effectiveMode = effectivePlaybackMode(mode, start.effectiveProfileId)
                timelineOffsetSec = start.actualStartSec
                val relativeMs = ((targetAbsoluteSec - start.actualStartSec).coerceAtLeast(0.0) * 1000).toLong()
                prepareSource(hlsMediaSource(start), relativeMs)
                controller.startKeepalive(::onKeepaliveSessionLost)
                emitQualityOptions()
            } catch (failure: HlsStartException) {
                if (isRestartCurrent(generation)) transitionToTerminal(
                    MoviePlayerEvent.Error(
                        failure.message ?: "Playback failed.",
                        failure.unauthorized,
                    ),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                // The repository deliberately rethrows programming errors rather than dressing
                // them as transport failures. They still belong on the error surface: this scope
                // has no exception handler, so an escape would kill the app mid-movie.
                if (isRestartCurrent(generation)) transitionToTerminal(
                    MoviePlayerEvent.Error(
                        failure.message?.let { "Playback failed ($it)." } ?: "Playback failed.",
                    ),
                )
            } finally {
                if (restartGeneration == generation) {
                    pendingMode = null
                    restartJob = null
                }
            }
        }
    }

    private fun prepareSource(source: MediaSource, positionMs: Long) {
        if (!playbackIntent.acceptsCommands) return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, currentSubtitleTypeIndex == null)
            .build()
        player.setMediaSource(source, positionMs)
        player.playWhenReady = playbackIntent.shouldPlay
        player.prepare()
    }

    /** Selecting the active row is an explicit cancellation of a different pending switch. */
    private fun cancelPendingModeSwitch() {
        restartJob?.cancel()
        restartJob = null
        restartGeneration++
        val canceledWhileDirect = effectiveMode == PlaybackMode.Direct
        requestedMode = effectiveMode
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
        emit(MoviePlayerEvent.StatusMessage(null))
        emitQualityOptions()
    }

    private fun isRestartCurrent(generation: Long): Boolean =
        playbackIntent.acceptsCommands && restartGeneration == generation

    private fun directMediaSource(): MediaSource =
        ProgressiveMediaSource.Factory(services.progressiveDataSourceFactory)
            .createMediaSource(
                MediaItem.Builder()
                    .setUri(services.directStreamUrl(request.movieId))
                    .setMimeType(request.mimeType)
                    .setMediaMetadata(movieMediaMetadata(request))
                    .build(),
            )

    private fun hlsMediaSource(start: HlsSessionStart): MediaSource =
        hlsMediaSourceFactory.createMediaSource(
            MediaItem.Builder()
                .setUri(start.playlistUrl)
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .setMediaMetadata(movieMediaMetadata(request))
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
                    services.hlsSessionApi.movieSubtitleUrl(request.movieId, index, actualStartSec),
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
        emit(MoviePlayerEvent.QualityOptionsChanged(options, requestedMode))
    }

    private fun onKeepaliveSessionLost() {
        if (!playbackIntent.acceptsCommands) return
        controller.noteSessionLost()
        restartInPlace(requestedMode, currentAudioTypeIndex, currentAbsoluteSec())
    }

    /**
     * A recoverable load failure restarts the session in place at the current position via the
     * manifest preflight in [restartInPlace]; the decision itself lives in
     * [shouldRecoverLostHlsSession]. A successful READY resets the budget.
     */
    private fun recoverFromLostHlsSession(error: PlaybackException): Boolean {
        val http = httpErrorCause(error)
        if (
            !shouldRecoverLostHlsSession(
                responseCode = http?.responseCode,
                requestPath = http?.dataSpec?.uri?.path,
                recoveries = sessionLostRecoveries,
            )
        ) return false
        sessionLostRecoveries++
        controller.noteSessionLost()
        restartInPlace(requestedMode, currentAudioTypeIndex, currentAbsoluteSec())
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

    private fun errorEvent(error: PlaybackException): MoviePlayerEvent.Error {
        val http = httpErrorCause(error)
        return playerErrorEvent(
            errorCode = error.errorCode,
            errorCodeName = error.errorCodeName,
            httpResponseCode = http?.responseCode,
            isHls = isHls,
            httpRequestPath = http?.dataSpec?.uri?.path,
        )
    }

    private fun emit(event: MoviePlayerEvent) {
        if (playbackIntent.released) return
        _events.tryEmit(event)
    }

    private fun emitDesiredPlayWhenReady() {
        emit(MoviePlayerEvent.PlayWhenReadyChanged(playbackIntent.shouldPlay))
    }

    /**
     * One terminal boundary for player and HLS-start failures. The surface and session object
     * stay alive for the error screen, but all loading, transport, keepalive, and backend work
     * stops until the screen creates a fresh engine for an explicit Retry.
     */
    private fun transitionToTerminal(error: MoviePlayerEvent.Error) {
        if (!playbackIntent.failTerminal()) return
        restartGeneration++
        pendingMode = null
        handler.removeCallbacks(ticker)
        restartJob?.cancel()
        restartJob = null
        controller.releaseAndStop()
        player.stop()
        player.clearMediaItems()
        emitDesiredPlayWhenReady()
        emit(error)
    }

    /**
     * The MediaSession's view of the player, shifted onto the absolute movie timeline so the
     * system's now-playing surface and Assistant seeks agree with the on-screen seek bar, and
     * every seek routes through the engine's rebase logic.
     */
    private inner class AbsoluteTimelinePlayer(player: Player) : ForwardingPlayer(player) {
        private val offsetMs: Long
            get() = (timelineOffsetSec * 1000).toLong()

        override fun getCurrentPosition(): Long = super.getCurrentPosition() + offsetMs
        override fun getContentPosition(): Long = super.getContentPosition() + offsetMs
        override fun getBufferedPosition(): Long = super.getBufferedPosition() + offsetMs

        override fun getDuration(): Long =
            durationSec().takeIf { it > 0.0 }?.let { (it * 1000).toLong() } ?: super.getDuration()

        override fun getContentDuration(): Long = getDuration()

        override fun getPlayWhenReady(): Boolean =
            playbackIntent.shouldPlay

        override fun play() {
            this@ExoMoviePlayerEngine.play()
        }

        override fun pause() {
            this@ExoMoviePlayerEngine.pause()
        }

        override fun setPlayWhenReady(playWhenReady: Boolean) {
            if (playWhenReady) {
                this@ExoMoviePlayerEngine.play()
            } else {
                this@ExoMoviePlayerEngine.pause()
            }
        }

        override fun seekTo(positionMs: Long) {
            this@ExoMoviePlayerEngine.seekTo(positionMs / 1000.0)
        }

        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            this@ExoMoviePlayerEngine.seekTo(positionMs / 1000.0)
        }

        override fun seekBack() {
            this@ExoMoviePlayerEngine.seekTo(currentAbsoluteSec() - seekBackIncrement / 1000.0)
        }

        override fun seekForward() {
            this@ExoMoviePlayerEngine.seekTo(currentAbsoluteSec() + seekForwardIncrement / 1000.0)
        }
    }

    private companion object {
        const val TICK_INTERVAL_MS = 500L
        const val SIDELOADED_SUBTITLE_ID_PREFIX = "sub:"
    }
}

fun exoMoviePlayerEngine(
    context: Context,
    request: MoviePlayRequest,
    services: MoviePlaybackServices,
): MoviePlayerEngine = ExoMoviePlayerEngine(context, request, services)
