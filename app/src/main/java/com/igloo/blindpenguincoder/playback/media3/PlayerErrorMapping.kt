// HttpDataSource is part of Media3's unstable surface; kept below the engine seam.
@file:androidx.annotation.OptIn(UnstableApi::class)

package com.igloo.blindpenguincoder.playback.media3

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import com.igloo.blindpenguincoder.playback.model.MoviePlayerEvent
import com.igloo.blindpenguincoder.playback.model.PLAYBACK_SERVER_BUSY_MESSAGE
import com.igloo.blindpenguincoder.playback.model.PLAYBACK_SERVER_UNREACHABLE_MESSAGE
import com.igloo.blindpenguincoder.playback.model.PLAYBACK_SESSION_LOST_MESSAGE
import com.igloo.blindpenguincoder.playback.model.PLAYBACK_UNAUTHORIZED_MESSAGE
import com.igloo.blindpenguincoder.playback.model.playbackServerRefusedMessage

/** The first HTTP status failure in [error]'s cause chain; null for non-HTTP failures. */
internal fun httpErrorCause(error: Throwable): HttpDataSource.InvalidResponseCodeException? =
    generateSequence(error) { it.cause }
        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
        .firstOrNull()

/**
 * A player failure as the error surface's plain sentences, with the codec/container/network
 * detail AGENTS.md asks for. Pure over the exception's already-extracted facts so the whole
 * mapping table is JVM-testable. The 401 message rides `unauthorized = true` so the screen can
 * treat a revoked session distinctly; the session state machine was already signalled by the
 * data source.
 */
internal fun playerErrorEvent(
    errorCode: Int,
    errorCodeName: String,
    httpResponseCode: Int?,
    isHls: Boolean,
): MoviePlayerEvent.Error = when {
    httpResponseCode == 401 -> MoviePlayerEvent.Error(
        message = PLAYBACK_UNAUTHORIZED_MESSAGE,
        unauthorized = true,
    )
    httpResponseCode == 404 && isHls -> MoviePlayerEvent.Error(PLAYBACK_SESSION_LOST_MESSAGE)
    httpResponseCode == 503 && isHls -> MoviePlayerEvent.Error(PLAYBACK_SERVER_BUSY_MESSAGE)
    httpResponseCode != null -> MoviePlayerEvent.Error(
        playbackServerRefusedMessage(httpResponseCode),
    )
    errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
        MoviePlayerEvent.Error(PLAYBACK_SERVER_UNREACHABLE_MESSAGE)
    errorCode in DECODING_ERROR_CODES -> MoviePlayerEvent.Error(
        "This TV couldn't decode the movie ($errorCodeName). " +
            "The file may use a codec this device doesn't support.",
    )
    errorCode in PARSING_ERROR_CODES -> MoviePlayerEvent.Error(
        "The movie's ${if (isHls) "stream" else "file"} could not be read ($errorCodeName).",
    )
    else -> MoviePlayerEvent.Error("Playback failed ($errorCodeName).")
}

private val DECODING_ERROR_CODES = setOf(
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
)

private val PARSING_ERROR_CODES = setOf(
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
)
