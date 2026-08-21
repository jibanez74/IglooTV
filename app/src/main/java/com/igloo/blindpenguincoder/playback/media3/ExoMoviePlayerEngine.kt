package com.igloo.blindpenguincoder.playback.media3

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.SubtitleView
import com.igloo.blindpenguincoder.playback.model.MoviePlayRequest
import com.igloo.blindpenguincoder.playback.model.MoviePlayerEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The real engine: one ExoPlayer over one progressive stream. The renderers keep their default
 * audio configuration — `DefaultAudioSink` negotiates passthrough with the current output route
 * on its own, which is how TrueHD/DTS-HD/Atmos reach a receiver untouched; nothing here may
 * narrow that. The surface pair (video + [SubtitleView]) is engine-owned so the Compose chrome
 * stays purely declarative; [SubtitleView] renders text and PGS bitmap cues alike, in the
 * viewer's system caption style.
 */
internal class ExoMoviePlayerEngine(
    context: Context,
    private val request: MoviePlayRequest,
    dataSourceFactory: DataSource.Factory,
    streamUrl: String,
) : MoviePlayerEngine {

    private val _events = MutableSharedFlow<MoviePlayerEvent>(replay = 64)
    override val events: SharedFlow<MoviePlayerEvent> = _events.asSharedFlow()

    private val handler = Handler(Looper.getMainLooper())
    private var initialSelectionApplied = false
    private var released = false

    private val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(ProgressiveMediaSource.Factory(dataSourceFactory))
        .setAudioAttributes(moviePlaybackAudioAttributes, /* handleAudioFocus= */ true)
        .build()

    private val surfaceView = SurfaceView(context)
    private val subtitleView = SubtitleView(context).apply {
        setUserDefaultStyle()
        setUserDefaultTextSize()
    }
    private val container = FrameLayout(context).apply {
        keepScreenOn = true
        addView(
            surfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        addView(
            subtitleView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> emit(MoviePlayerEvent.Ready(durationSec()))
                Player.STATE_BUFFERING -> emit(MoviePlayerEvent.Buffering)
                Player.STATE_ENDED -> emit(MoviePlayerEvent.Ended)
                else -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            emit(MoviePlayerEvent.IsPlayingChanged(isPlaying))
        }

        override fun onTracksChanged(tracks: Tracks) {
            // The pre-play choice can only be applied once real track groups exist; doing it
            // here re-triggers this callback, which then reports the updated selected flags.
            if (!initialSelectionApplied && tracks.groups.isNotEmpty()) {
                initialSelectionApplied = true
                applyInitialSelection(tracks)
            }
            emit(
                MoviePlayerEvent.TracksChanged(
                    audio = audioTrackOptions(tracks),
                    subtitles = subtitleTrackOptions(tracks),
                ),
            )
        }

        override fun onCues(cueGroup: CueGroup) {
            subtitleView.setCues(cueGroup.cues)
        }

        override fun onPlayerError(error: PlaybackException) {
            emit(errorEvent(error))
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (released) return
            if (player.playbackState == Player.STATE_READY ||
                player.playbackState == Player.STATE_BUFFERING
            ) {
                emit(MoviePlayerEvent.Time(player.currentPosition / 1000.0, durationSec()))
            }
            handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    init {
        player.addListener(listener)
        player.setVideoSurfaceView(surfaceView)
        player.setMediaItem(
            MediaItem.Builder()
                .setUri(streamUrl)
                .setMimeType(request.mimeType)
                .build(),
        )
    }

    override fun surface(): View = container

    override fun startPlayback(startPositionSec: Double?) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, request.subtitleTypeIndex == null)
            .build()
        if (startPositionSec != null) {
            player.seekTo((startPositionSec * 1000).toLong())
        }
        player.playWhenReady = true
        player.prepare()
        handler.post(ticker)
    }

    override fun play() {
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun seekTo(seconds: Double) {
        player.seekTo((seconds * 1000).toLong())
    }

    override fun selectAudioTrack(optionId: String) {
        val override = overrideFor(optionId) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(override)
            .build()
    }

    override fun selectSubtitleTrack(optionId: String?) {
        val builder = player.trackSelectionParameters.buildUpon()
        if (optionId == null) {
            builder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            val override = overrideFor(optionId) ?: return
            builder.setOverrideForType(override)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        }
        player.trackSelectionParameters = builder.build()
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
        player.release()
    }

    private fun applyInitialSelection(tracks: Tracks) {
        val builder = player.trackSelectionParameters.buildUpon()
        request.audioTypeIndex
            ?.let { trackOptionId(tracks, C.TRACK_TYPE_AUDIO, it) }
            ?.let { overrideFor(it, tracks) }
            ?.let(builder::setOverrideForType)
        request.subtitleTypeIndex
            ?.let { trackOptionId(tracks, C.TRACK_TYPE_TEXT, it) }
            ?.let { overrideFor(it, tracks) }
            ?.let {
                builder.setOverrideForType(it)
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            }
        player.trackSelectionParameters = builder.build()
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

    private fun durationSec(): Double =
        player.duration.takeIf { it != C.TIME_UNSET }?.div(1000.0) ?: 0.0

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
                "The movie's file could not be read as ${request.mimeType} " +
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

    private companion object {
        const val TICK_INTERVAL_MS = 500L

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
    dataSourceFactory: DataSource.Factory,
    streamUrl: String,
): MoviePlayerEngine = ExoMoviePlayerEngine(context, request, dataSourceFactory, streamUrl)
