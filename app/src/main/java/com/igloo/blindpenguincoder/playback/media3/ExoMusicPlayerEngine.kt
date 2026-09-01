// DefaultMediaSourceFactory is part of ExoPlayer's unstable surface; the engine seam keeps the
// instability from spreading above this package.
@file:androidx.annotation.OptIn(UnstableApi::class)

package com.igloo.blindpenguincoder.playback.media3

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.igloo.blindpenguincoder.playback.model.MusicPlayRequest
import com.igloo.blindpenguincoder.playback.model.MusicPlayerEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The real engine: one ExoPlayer holding the whole album as its playlist, so auto-advance is
 * the player's own item transition and skip is [Player.seekToNext]/[Player.seekToPrevious]'s
 * standard semantics. Direct progressive streams only — the backend serves audio files as-is —
 * so none of the movie engine's HLS machinery exists here. The container is never told its
 * MIME type; Media3's extractors sniff FLAC/MP3/M4A/OGG reliably, and the wire doesn't carry
 * one for tracks anyway.
 */
internal class ExoMusicPlayerEngine(
    context: Context,
    private val request: MusicPlayRequest,
    dataSourceFactory: DataSource.Factory,
    trackStreamUrl: (Long) -> String,
) : MusicPlayerEngine {

    private val _events = MutableSharedFlow<MusicPlayerEvent>(replay = 64)
    override val events: SharedFlow<MusicPlayerEvent> = _events.asSharedFlow()

    private val handler = Handler(Looper.getMainLooper())
    private val playbackIntent = PlaybackIntent()

    private val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
        .setAudioAttributes(musicPlaybackAudioAttributes, /* handleAudioFocus= */ true)
        .build()

    private val session = buildMusicMediaSession(
        context,
        IntentRoutingPlayer(player),
        request.albumId,
    )

    private val mediaItems = request.tracks.map { track ->
        MediaItem.Builder()
            .setUri(trackStreamUrl(track.id))
            .setMediaId(track.id.toString())
            .setMediaMetadata(musicMediaMetadata(track, request))
            .build()
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> emit(MusicPlayerEvent.Ready(durationSec()))
                Player.STATE_BUFFERING -> emit(MusicPlayerEvent.Buffering)
                // Only fires past the last playlist item, so this is the whole-album end.
                Player.STATE_ENDED -> emit(MusicPlayerEvent.Ended)
                else -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            emit(MusicPlayerEvent.IsPlayingChanged(isPlaying))
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            emitDesiredPlayWhenReady()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            emit(
                MusicPlayerEvent.TrackChanged(
                    index = player.currentMediaItemIndex,
                    durationSec = durationSec(),
                ),
            )
        }

        override fun onPlayerError(error: PlaybackException) {
            transitionToTerminal(errorEvent(error))
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!playbackIntent.acceptsCommands) return
            if (player.playbackState == Player.STATE_READY ||
                player.playbackState == Player.STATE_BUFFERING
            ) {
                emit(MusicPlayerEvent.Time(player.currentPosition / 1000.0, durationSec()))
            }
            handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    init {
        player.addListener(listener)
    }

    override fun startPlayback(
        startTrackIndex: Int,
        startPositionSec: Double,
        initialPlayWhenReady: Boolean,
    ) {
        if (!playbackIntent.start(initialPlayWhenReady)) return
        emitDesiredPlayWhenReady()
        handler.post(ticker)
        player.setMediaItems(
            mediaItems,
            startTrackIndex.coerceIn(0, (mediaItems.size - 1).coerceAtLeast(0)),
            (startPositionSec * 1000).toLong().coerceAtLeast(0L),
        )
        player.playWhenReady = playbackIntent.shouldPlay
        player.prepare()
    }

    override fun play() {
        if (!playbackIntent.play()) return
        emitDesiredPlayWhenReady()
        player.play()
    }

    override fun pause() {
        if (!playbackIntent.pause()) return
        emitDesiredPlayWhenReady()
        player.pause()
    }

    override fun seekTo(seconds: Double) {
        if (!playbackIntent.acceptsCommands) return
        player.seekTo((seconds * 1000).toLong())
    }

    override fun skipToNext() {
        if (!playbackIntent.acceptsCommands) return
        player.seekToNext()
    }

    override fun skipToPrevious() {
        if (!playbackIntent.acceptsCommands) return
        player.seekToPrevious()
    }

    override fun onHostPaused() {
        if (!playbackIntent.hostPaused()) return
        emitDesiredPlayWhenReady()
        player.pause()
    }

    override fun onHostResumed() {
        if (!playbackIntent.hostResumed()) return
        // Stays paused; resuming music that went to standby is the user's call.
    }

    override fun release() {
        if (!playbackIntent.release()) return
        handler.removeCallbacks(ticker)
        // Media3 requires the session gone before its player.
        session.release()
        player.release()
    }

    /**
     * The current track's length. ExoPlayer reports TIME_UNSET until the new item's container
     * is parsed — through every transition — so the wire duration bridges the gap and the seek
     * bar never collapses between tracks.
     */
    private fun durationSec(): Double =
        player.duration.takeIf { it != C.TIME_UNSET }?.div(1000.0)
            ?: request.tracks.getOrNull(player.currentMediaItemIndex)?.durationSec
            ?: 0.0

    private fun errorEvent(error: PlaybackException): MusicPlayerEvent.Error {
        val http = httpErrorCause(error)
        val mapped = playerErrorEvent(
            errorCode = error.errorCode,
            errorCodeName = error.errorCodeName,
            httpResponseCode = http?.responseCode,
            isHls = false,
            httpRequestPath = http?.dataSpec?.uri?.path,
            mediaNoun = "track",
        )
        return MusicPlayerEvent.Error(mapped.message, mapped.unauthorized)
    }

    private fun emit(event: MusicPlayerEvent) {
        if (playbackIntent.released) return
        _events.tryEmit(event)
    }

    private fun emitDesiredPlayWhenReady() {
        emit(MusicPlayerEvent.PlayWhenReadyChanged(playbackIntent.shouldPlay))
    }

    /**
     * One terminal boundary. The session object stays alive for the error screen, but loading
     * and transport stop until the screen creates a fresh engine for an explicit Retry.
     */
    private fun transitionToTerminal(error: MusicPlayerEvent.Error) {
        if (!playbackIntent.failTerminal()) return
        player.removeListener(listener)
        handler.removeCallbacks(ticker)
        emitDesiredPlayWhenReady()
        emit(error)
        player.stop()
        player.clearMediaItems()
    }

    /**
     * The MediaSession's view of the player. Play/pause route through the engine so
     * [PlaybackIntent] stays authoritative — an Assistant "play" while the host is paused must
     * not restart audio behind a covered screen — and playWhenReady reports that intent, the
     * same §11.8 rule the on-screen toggle renders. Queue and seek commands pass through:
     * they carry no transport intent.
     */
    private inner class IntentRoutingPlayer(player: Player) : ForwardingPlayer(player) {
        override fun getPlayWhenReady(): Boolean = playbackIntent.shouldPlay

        override fun play() {
            this@ExoMusicPlayerEngine.play()
        }

        override fun pause() {
            this@ExoMusicPlayerEngine.pause()
        }

        override fun setPlayWhenReady(playWhenReady: Boolean) {
            if (playWhenReady) {
                this@ExoMusicPlayerEngine.play()
            } else {
                this@ExoMusicPlayerEngine.pause()
            }
        }
    }

    private companion object {
        const val TICK_INTERVAL_MS = 500L
    }
}

fun exoMusicPlayerEngine(
    context: Context,
    request: MusicPlayRequest,
    dataSourceFactory: DataSource.Factory,
    trackStreamUrl: (Long) -> String,
): MusicPlayerEngine = ExoMusicPlayerEngine(context, request, dataSourceFactory, trackStreamUrl)
