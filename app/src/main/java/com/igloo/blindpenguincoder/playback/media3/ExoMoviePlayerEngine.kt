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
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import com.igloo.blindpenguincoder.data.model.PlaybackMode
import com.igloo.blindpenguincoder.data.model.hlsProfileId
import com.igloo.blindpenguincoder.playback.hls.HLS_SESSION_LOST_MAX_ATTEMPTS
import com.igloo.blindpenguincoder.playback.hls.HlsSessionController
import com.igloo.blindpenguincoder.playback.hls.HlsSessionStart
import com.igloo.blindpenguincoder.playback.hls.HlsStartException
import com.igloo.blindpenguincoder.playback.hls.hlsResumeStartSec
import com.igloo.blindpenguincoder.playback.hls.shouldRebaseHlsSeek
import com.igloo.blindpenguincoder.playback.model.MoviePlayRequest
import com.igloo.blindpenguincoder.playback.model.MoviePlayerEvent
import com.igloo.blindpenguincoder.playback.model.PlaybackGateResult
import com.igloo.blindpenguincoder.playback.model.TrackOption
import com.igloo.blindpenguincoder.playback.model.availablePlaybackModes
import com.igloo.blindpenguincoder.playback.model.evaluatePlaybackGate
import com.igloo.blindpenguincoder.playback.model.playbackModeLabel
import kotlin.math.floor
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

    private var currentMode = request.mode
    private var currentAudioTypeIndex = request.audioTypeIndex
    private var currentSubtitleTypeIndex = request.subtitleTypeIndex
    /** Absolute movie second where the prepared source's own zero sits; 0 for direct play. */
    private var timelineOffsetSec = 0.0
    private var restartJob: Job? = null
    private var sessionLostRecoveries = 0
    private var initialSelectionApplied = false
    private var released = false

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
            emit(MoviePlayerEvent.PlayWhenReadyChanged(playWhenReady))
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
            emit(errorEvent(error))
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (released) return
            if (player.playbackState == Player.STATE_READY ||
                player.playbackState == Player.STATE_BUFFERING
            ) {
                emit(MoviePlayerEvent.Time(currentAbsoluteSec(), durationSec()))
            }
            handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    private val isHls: Boolean
        get() = currentMode != PlaybackMode.Direct

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

    override fun startPlayback(startPositionSec: Double?, initialPlayWhenReady: Boolean) {
        handler.post(ticker)
        val targetSec = when {
            !isHls -> startPositionSec ?: 0.0
            // Resuming over HLS rewinds a little; the session then starts right at the target.
            startPositionSec != null -> hlsResumeStartSec(startPositionSec).toDouble()
            else -> 0.0
        }
        restartInPlace(currentMode, currentAudioTypeIndex, targetSec, initialPlayWhenReady)
    }

    override fun play() {
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun seekTo(seconds: Double) {
        if (!isHls) {
            player.seekTo((seconds * 1000).toLong())
            return
        }
        if (shouldRebaseHlsSeek(seconds, timelineOffsetSec, currentAbsoluteSec())) {
            restartInPlace(currentMode, currentAudioTypeIndex, seconds, player.playWhenReady)
        } else {
            player.seekTo(((seconds - timelineOffsetSec) * 1000).toLong())
        }
    }

    override fun selectAudioTrack(optionId: String) {
        val hlsOrdinal = parseHlsAudioOptionId(optionId)
        if (hlsOrdinal != null) {
            // Only one audio track exists in an HLS mux; another track is another session.
            if (hlsOrdinal == effectiveAudioOrdinal()) return
            restartInPlace(currentMode, hlsOrdinal, currentAbsoluteSec(), player.playWhenReady)
            return
        }
        val override = overrideFor(optionId) ?: return
        // Remembered as the type ordinal so the choice survives a later switch into HLS.
        typeIndexForOptionId(player.currentTracks, C.TRACK_TYPE_AUDIO, optionId)
            ?.let { currentAudioTypeIndex = it }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(override)
            .build()
    }

    override fun selectSubtitleTrack(optionId: String?) {
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
        val mode = PlaybackMode.entries.firstOrNull { it.name == optionId } ?: return
        if (mode == currentMode) return
        restartInPlace(mode, currentAudioTypeIndex, currentAbsoluteSec(), player.playWhenReady)
    }

    override fun onHostPaused() {
        player.pause()
    }

    override fun onHostResumed() {
        // Stays paused; resuming a movie that went to standby is the user's call.
    }

    override fun release() {
        released = true
        handler.removeCallbacks(ticker)
        restartJob?.cancel()
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
        mode: PlaybackMode,
        audioTypeIndex: Int?,
        targetAbsoluteSec: Double,
        playWhenReady: Boolean,
    ) {
        restartJob?.cancel()
        currentMode = mode
        currentAudioTypeIndex = audioTypeIndex
        initialSelectionApplied = false
        player.pause()
        emit(MoviePlayerEvent.Buffering)
        if (mode == PlaybackMode.Direct) {
            // Leaving HLS: the session is no longer needed; the UUID stays valid for a return.
            controller.releaseAndStop()
            timelineOffsetSec = 0.0
            prepareSource(directMediaSource(), (targetAbsoluteSec * 1000).toLong(), playWhenReady)
            emitQualityOptions()
            return
        }
        val profileId = requireNotNull(mode.hlsProfileId)
        restartJob = scope.launch {
            try {
                val start = controller.start(
                    profileId = profileId,
                    audioTypeIndex = audioTypeIndex ?: request.effectiveAudioTypeIndex,
                    startSec = floor(targetAbsoluteSec).toInt().coerceAtLeast(0),
                ) { message -> emit(MoviePlayerEvent.StatusMessage(message)) }
                timelineOffsetSec = start.actualStartSec
                val relativeMs = ((targetAbsoluteSec - start.actualStartSec).coerceAtLeast(0.0) * 1000).toLong()
                prepareSource(hlsMediaSource(start), relativeMs, playWhenReady)
                controller.startKeepalive(::onKeepaliveSessionLost)
                emitQualityOptions()
            } catch (failure: HlsStartException) {
                emit(
                    MoviePlayerEvent.Error(
                        failure.message ?: "Playback failed.",
                        failure.unauthorized,
                    ),
                )
            }
        }
    }

    private fun prepareSource(source: MediaSource, positionMs: Long, playWhenReady: Boolean) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, currentSubtitleTypeIndex == null)
            .build()
        player.setMediaSource(source, positionMs)
        player.playWhenReady = playWhenReady
        player.prepare()
    }

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

    /**
     * The quality menu's rows. Direct is offered only when the current audio track can prove
     * itself playable — listing a mode that would play silent is not a choice, it's a trap;
     * the pre-play dialog still lists Direct and explains, because there the gate's refusal
     * can be read.
     */
    private fun emitQualityOptions() {
        val audio = effectiveAudioOrdinal()?.let(request.audioTracks::getOrNull)
        val directPlayable = evaluatePlaybackGate(
            mode = PlaybackMode.Direct,
            audioCodec = audio?.codec,
            audioCodecProfile = audio?.codecProfile,
            audioLabel = audio?.label,
            canPlayMime = { mime -> services.canPlayAudioMime(mime, audio?.channels) },
        ) is PlaybackGateResult.Proceed
        val options = availablePlaybackModes(request.videoHeight, includeDirect = directPlayable)
            .map { TrackOption(id = it.name, label = playbackModeLabel(it), selected = it == currentMode) }
        emit(MoviePlayerEvent.QualityOptionsChanged(options))
    }

    private fun onKeepaliveSessionLost() {
        if (released) return
        controller.noteSessionLost()
        restartInPlace(currentMode, currentAudioTypeIndex, currentAbsoluteSec(), player.playWhenReady)
    }

    /**
     * A segment 404 means the server-side session evaporated (idle eviction, restart); the
     * manifest preflight in [restartInPlace] recreates it at the current position. Budgeted so
     * a genuinely missing movie cannot loop forever; a successful READY resets the count.
     */
    private fun recoverFromLostHlsSession(error: PlaybackException): Boolean {
        val http = generateSequence<Throwable>(error) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()
        if (http?.responseCode != 404) return false
        if (sessionLostRecoveries >= HLS_SESSION_LOST_MAX_ATTEMPTS) return false
        sessionLostRecoveries++
        controller.noteSessionLost()
        restartInPlace(currentMode, currentAudioTypeIndex, currentAbsoluteSec(), player.playWhenReady)
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

    /**
     * Plain sentences, with the codec/container/network detail AGENTS.md asks for. The 401
     * message rides `unauthorized = true` so the screen can treat a revoked session
     * distinctly; the session state machine was already signalled by the data source.
     */
    private fun errorEvent(error: PlaybackException): MoviePlayerEvent.Error {
        val http = generateSequence<Throwable>(error) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()
        return when {
            http?.responseCode == 401 -> MoviePlayerEvent.Error(
                message = "Your session is no longer valid. Sign in again to keep watching.",
                unauthorized = true,
            )
            http?.responseCode == 404 && isHls -> MoviePlayerEvent.Error(
                "The playback session was lost and could not be recreated.",
            )
            http?.responseCode == 503 && isHls -> MoviePlayerEvent.Error(
                "The server is busy converting other streams. Try again shortly.",
            )
            http != null -> MoviePlayerEvent.Error(
                "The server refused the stream (HTTP ${http.responseCode}).",
            )
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                MoviePlayerEvent.Error(
                    "The server could not be reached. Check the connection and try again.",
                )
            error.errorCode in DECODING_ERROR_CODES -> MoviePlayerEvent.Error(
                "This TV couldn't decode the movie (${error.errorCodeName}). " +
                    "The file may use a codec this device doesn't support.",
            )
            error.errorCode in PARSING_ERROR_CODES -> MoviePlayerEvent.Error(
                "The movie's ${if (isHls) "stream" else "file"} could not be read " +
                    "(${error.errorCodeName}).",
            )
            else -> MoviePlayerEvent.Error(
                "Playback failed (${error.errorCodeName}).",
            )
        }
    }

    private fun emit(event: MoviePlayerEvent) {
        _events.tryEmit(event)
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

        val DECODING_ERROR_CODES = setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        )

        val PARSING_ERROR_CODES = setOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        )
    }
}

fun exoMoviePlayerEngine(
    context: Context,
    request: MoviePlayRequest,
    services: MoviePlaybackServices,
): MoviePlayerEngine = ExoMoviePlayerEngine(context, request, services)
