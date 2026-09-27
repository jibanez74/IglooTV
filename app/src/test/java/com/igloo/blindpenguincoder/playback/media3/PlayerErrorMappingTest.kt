package com.igloo.blindpenguincoder.playback.media3

import androidx.media3.common.PlaybackException
import com.igloo.blindpenguincoder.playback.model.PLAYBACK_SERVER_BUSY_MESSAGE
import com.igloo.blindpenguincoder.playback.model.PLAYBACK_SERVER_UNREACHABLE_MESSAGE
import com.igloo.blindpenguincoder.playback.model.PLAYBACK_SESSION_LOST_MESSAGE
import com.igloo.blindpenguincoder.playback.model.PLAYBACK_UNAUTHORIZED_MESSAGE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerErrorMappingTest {

    private fun event(
        errorCode: Int = PlaybackException.ERROR_CODE_UNSPECIFIED,
        errorCodeName: String = "ERROR_CODE_UNSPECIFIED",
        httpResponseCode: Int? = null,
        isHls: Boolean = false,
        httpRequestPath: String? = if (isHls && httpResponseCode != null) {
            "/api/movies/7/hls/remux/segment_1.m4s"
        } else {
            null
        },
        mediaNoun: String = "movie",
    ) = playerFailure(errorCode, errorCodeName, httpResponseCode, isHls, httpRequestPath, mediaNoun)

    // --- HTTP statuses outrank error codes ---

    @Test
    fun `a 401 is unauthorized in any mode`() {
        listOf(false, true).forEach { isHls ->
            val error = event(httpResponseCode = 401, isHls = isHls)
            assertEquals(PLAYBACK_UNAUTHORIZED_MESSAGE, error.message)
            assertTrue(error.unauthorized)
        }
    }

    @Test
    fun `a 404 is a lost session only under hls`() {
        assertEquals(PLAYBACK_SESSION_LOST_MESSAGE, event(httpResponseCode = 404, isHls = true).message)
        assertEquals(
            "The server refused the stream (HTTP 404).",
            event(httpResponseCode = 404, isHls = false).message,
        )
    }

    @Test
    fun `an episode's HLS 404 and 503 read like a movie's`() {
        val path = "/api/shows/episodes/900/hls/remux/segment_1.m4s"
        assertEquals(
            PLAYBACK_SESSION_LOST_MESSAGE,
            event(httpResponseCode = 404, isHls = true, httpRequestPath = path).message,
        )
        assertEquals(
            PLAYBACK_SERVER_BUSY_MESSAGE,
            event(httpResponseCode = 503, isHls = true, httpRequestPath = path).message,
        )
    }

    @Test
    fun `a 503 is the busy message only under hls`() {
        assertEquals(PLAYBACK_SERVER_BUSY_MESSAGE, event(httpResponseCode = 503, isHls = true).message)
        assertEquals(
            "The server refused the stream (HTTP 503).",
            event(httpResponseCode = 503, isHls = false).message,
        )
    }

    @Test
    fun `a WebVTT 404 keeps ordinary HTTP handling during hls playback`() {
        assertEquals(
            "The server refused the stream (HTTP 404).",
            event(
                httpResponseCode = 404,
                isHls = true,
                httpRequestPath = "/api/movies/7/subtitles/0/web.vtt",
            ).message,
        )
    }

    @Test
    fun `a WebVTT 503 keeps ordinary HTTP handling during hls playback`() {
        assertEquals(
            "The server refused the stream (HTTP 503).",
            event(
                httpResponseCode = 503,
                isHls = true,
                httpRequestPath = "/api/movies/7/subtitles/0/web.vtt",
            ).message,
        )
    }

    @Test
    fun `any other http status names its code and is not unauthorized`() {
        val error = event(httpResponseCode = 500, isHls = true)
        assertEquals("The server refused the stream (HTTP 500).", error.message)
        assertFalse(error.unauthorized)
    }

    @Test
    fun `an http status wins over a decoder-looking error code`() {
        val error = event(
            errorCode = PlaybackException.ERROR_CODE_DECODING_FAILED,
            errorCodeName = "ERROR_CODE_DECODING_FAILED",
            httpResponseCode = 500,
        )
        assertEquals("The server refused the stream (HTTP 500).", error.message)
    }

    // --- non-HTTP classifications ---

    @Test
    fun `network failures without a status read as unreachable`() {
        assertEquals(
            PLAYBACK_SERVER_UNREACHABLE_MESSAGE,
            event(
                errorCode = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                errorCodeName = "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED",
            ).message,
        )
        assertEquals(
            PLAYBACK_SERVER_UNREACHABLE_MESSAGE,
            event(
                errorCode = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                errorCodeName = "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT",
            ).message,
        )
    }

    @Test
    fun `decoder failures name the code and blame the codec`() {
        listOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        ).forEach { code ->
            assertEquals(
                "This TV couldn't decode the movie (NAME). " +
                    "The file may use a codec this device doesn't support.",
                event(errorCode = code, errorCodeName = "NAME").message,
            )
        }
    }

    @Test
    fun `parsing failures name stream under hls and file under direct`() {
        listOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        ).forEach { code ->
            assertEquals(
                "The movie's stream could not be read (NAME).",
                event(errorCode = code, errorCodeName = "NAME", isHls = true).message,
            )
            assertEquals(
                "The movie's file could not be read (NAME).",
                event(errorCode = code, errorCodeName = "NAME", isHls = false).message,
            )
        }
    }

    @Test
    fun `anything unclassified falls back to a plain failure naming the code`() {
        assertEquals(
            "Playback failed (ERROR_CODE_TIMEOUT).",
            event(
                errorCode = PlaybackException.ERROR_CODE_TIMEOUT,
                errorCodeName = "ERROR_CODE_TIMEOUT",
            ).message,
        )
    }
}
