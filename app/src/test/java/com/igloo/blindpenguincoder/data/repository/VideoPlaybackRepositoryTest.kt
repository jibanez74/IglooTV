package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.data.model.UpdateWatchProgressRequest
import com.igloo.blindpenguincoder.playback.hls.HlsManifestResult
import com.igloo.blindpenguincoder.playback.hls.HlsSessionSpec
import com.igloo.blindpenguincoder.playback.model.HlsAudioProfile
import com.igloo.blindpenguincoder.playback.model.PLAYBACK_SERVER_UNREACHABLE_MESSAGE
import com.igloo.blindpenguincoder.playback.model.PLAYBACK_UNAUTHORIZED_MESSAGE
import com.igloo.blindpenguincoder.playback.model.PlaybackMediaRef
import com.igloo.blindpenguincoder.playback.model.playbackServerRefusedMessage
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * The player's routes, once per media kind: a movie under `/movies/{id}` and a TV episode under
 * `/shows/episodes/{id}` share every path suffix, query and header verbatim.
 */
@RunWith(Parameterized::class)
class VideoPlaybackRepositoryTest(
    private val media: PlaybackMediaRef,
    private val prefix: String,
) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{1}")
        fun media(): List<Array<Any>> = listOf(
            arrayOf(PlaybackMediaRef.Movie(9), "movies/9"),
            arrayOf(PlaybackMediaRef.Episode(900), "shows/episodes/900"),
        )
    }

    @Test
    fun `the stream url is the contract path on the api base`() = runTest {
        val http = TestHttp { error("the stream url is built, never fetched") }

        assertEquals("$TEST_SERVER/$prefix/stream", http.videoPlaybackRepository.streamUrl(media))
    }

    private fun hlsSpec(reload: Int = 0, audioProfile: HlsAudioProfile? = null) = HlsSessionSpec(
        media = media,
        profileId = "remux",
        audioTypeIndex = 1,
        startSec = 90,
        sessionUuid = "5e0f8f2a-9df1-4f2f-8a53-0d9f8f2a9df1",
        reload = reload,
        audioProfile = audioProfile,
    )

    @Test
    fun `the hls playlist url carries the profile path and the session query`() = runTest {
        val http = TestHttp { error("the playlist url is built, never fetched") }

        assertEquals(
            "$TEST_SERVER/$prefix/hls/remux/playlist.m3u8" +
                "?playback_session=5e0f8f2a-9df1-4f2f-8a53-0d9f8f2a9df1&start=90&audio_track=1",
            http.videoPlaybackRepository.hlsPlaylistUrl(hlsSpec()),
        )
    }

    @Test
    fun `an audio conversion rides the playlist url as the codec-channels pair`() = runTest {
        val http = TestHttp { error("the playlist url is built, never fetched") }

        assertEquals(
            "$TEST_SERVER/$prefix/hls/remux/playlist.m3u8" +
                "?playback_session=5e0f8f2a-9df1-4f2f-8a53-0d9f8f2a9df1&start=90&audio_track=1" +
                "&audio_codec=eac3&audio_channels=6",
            http.videoPlaybackRepository.hlsPlaylistUrl(
                hlsSpec(audioProfile = HlsAudioProfile.DolbyDigitalPlus),
            ),
        )
    }

    @Test
    fun `the subtitle url shifts by the session start and omits a zero start`() = runTest {
        val http = TestHttp { error("the subtitle url is built, never fetched") }

        assertEquals(
            "$TEST_SERVER/$prefix/subtitles/2/web.vtt?start=87.417",
            http.videoPlaybackRepository.subtitleUrl(media, 2, 87.417),
        )
        assertEquals(
            "$TEST_SERVER/$prefix/subtitles/2/web.vtt",
            http.videoPlaybackRepository.subtitleUrl(media, 2, 0.0),
        )
    }

    @Test
    fun `fetching the manifest sends the contract query and reads the igloo headers`() = runTest {
        var request: HttpRequestData? = null
        val body = ByteReadChannel("#EXTM3U")
        val http = TestHttp {
            request = it
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    "X-Igloo-Effective-Profile" to listOf("1080p_8mbps"),
                    "X-Igloo-Actual-Start" to listOf("87.417"),
                ),
            )
        }
        http.profiles.setPending("igd_test")

        val result = http.videoPlaybackRepository.fetchHlsManifest(hlsSpec())

        val captured = requireNotNull(request)
        assertEquals("/api/$prefix/hls/remux/playlist.m3u8", captured.url.encodedPath)
        assertEquals("5e0f8f2a-9df1-4f2f-8a53-0d9f8f2a9df1", captured.url.parameters["playback_session"])
        assertEquals("90", captured.url.parameters["start"])
        assertEquals("1", captured.url.parameters["audio_track"])
        // A legacy spec must not leak half a conversion pair onto the wire.
        assertEquals(null, captured.url.parameters["audio_codec"])
        assertEquals(null, captured.url.parameters["audio_channels"])
        assertEquals("Bearer igd_test", captured.headers[HttpHeaders.Authorization])
        val timeouts = requireNotNull(captured.getCapabilityOrNull(HttpTimeoutCapability))
        assertEquals(45_000L, timeouts.requestTimeoutMillis)
        assertEquals(45_000L, timeouts.socketTimeoutMillis)
        assertEquals(HlsManifestResult.Ready("1080p_8mbps", 87.417), result)
        assertTrue("a successful manifest body must be drained", body.isClosedForRead)
    }

    @Test
    fun `a busy manifest surfaces the retry hint instead of failing`() = runTest {
        val body = ByteReadChannel("conversion capacity exhausted")
        val http = TestHttp {
            respond(
                content = body,
                status = HttpStatusCode.ServiceUnavailable,
                headers = headersOf("Retry-After" to listOf("5")),
            )
        }

        assertEquals(
            HlsManifestResult.Busy(retryAfterSec = 5),
            http.videoPlaybackRepository.fetchHlsManifest(hlsSpec()),
        )
        assertTrue("a non-success manifest body must be drained", body.isClosedForRead)
    }

    @Test
    fun `a lost session is a protocol state the controller can retry, not a failure`() = runTest {
        val http = TestHttp {
            respond(
                content = ByteReadChannel("""{"error":true,"message":"not found"}"""),
                status = HttpStatusCode.NotFound,
            )
        }

        assertEquals(HlsManifestResult.Lost, http.videoPlaybackRepository.fetchHlsManifest(hlsSpec()))
    }

    @Test
    fun `a rejected credential fails the manifest as unauthorized`() = runTest {
        val http = TestHttp {
            respond(
                content = ByteReadChannel("""{"error":true,"message":"unauthorized"}"""),
                status = HttpStatusCode.Unauthorized,
            )
        }

        val result = http.videoPlaybackRepository.fetchHlsManifest(hlsSpec())

        val failed = result as HlsManifestResult.Failed
        assertTrue(failed.unauthorized)
        assertEquals(PLAYBACK_UNAUTHORIZED_MESSAGE, failed.message)
    }

    @Test
    fun `any other refusal carries its status into the message`() = runTest {
        val http = TestHttp {
            respond(
                content = ByteReadChannel("""{"error":true,"message":"boom"}"""),
                status = HttpStatusCode.InternalServerError,
            )
        }

        assertEquals(
            HlsManifestResult.Failed(playbackServerRefusedMessage(500)),
            http.videoPlaybackRepository.fetchHlsManifest(hlsSpec()),
        )
    }

    @Test
    fun `a transport failure reads as unreachable rather than crashing the player`() = runTest {
        val http = TestHttp { throw IOException("connection reset") }

        assertEquals(
            HlsManifestResult.Failed(PLAYBACK_SERVER_UNREACHABLE_MESSAGE),
            http.videoPlaybackRepository.fetchHlsManifest(hlsSpec()),
        )
    }

    @Test
    fun `a connection timeout fails immediately as unreachable`() = runTest {
        val http = TestHttp { throw ConnectTimeoutException(TEST_SERVER) }

        assertEquals(
            HlsManifestResult.Failed(PLAYBACK_SERVER_UNREACHABLE_MESSAGE),
            http.videoPlaybackRepository.fetchHlsManifest(hlsSpec()),
        )
    }

    @Test
    fun `a wrapped connection timeout fails immediately as unreachable`() = runTest {
        val http = TestHttp {
            throw RuntimeException("engine failed", ConnectTimeoutException(TEST_SERVER))
        }

        assertEquals(
            HlsManifestResult.Failed(PLAYBACK_SERVER_UNREACHABLE_MESSAGE),
            http.videoPlaybackRepository.fetchHlsManifest(hlsSpec()),
        )
    }

    @Test
    fun `a request timeout remains busy for capacity retry`() = runTest {
        val http = TestHttp {
            throw HttpRequestTimeoutException(TEST_SERVER, 45_000L)
        }

        assertEquals(
            HlsManifestResult.Busy(retryAfterSec = null),
            http.videoPlaybackRepository.fetchHlsManifest(hlsSpec()),
        )
    }

    @Test
    fun `a programming error surfaces instead of masquerading as a transport failure`() = runTest {
        val http = TestHttp { error("server address requested before setup completed") }

        try {
            http.videoPlaybackRepository.fetchHlsManifest(hlsSpec())
            fail("expected the programming error to surface")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("server address"))
        }
    }

    @Test
    fun `stopping a session posts the uuid to the contract path`() = runTest {
        var request: HttpRequestData? = null
        val http = TestHttp {
            request = it
            jsonResponse("""{"error":false,"message":"stopped"}""")
        }

        http.videoPlaybackRepository.stopHlsSession(media, "5e0f8f2a-9df1-4f2f-8a53-0d9f8f2a9df1")

        val captured = requireNotNull(request)
        assertEquals("/api/$prefix/hls/session/stop", captured.url.encodedPath)
        assertEquals(
            "5e0f8f2a-9df1-4f2f-8a53-0d9f8f2a9df1",
            captured.url.parameters["playback_session"],
        )
    }

    @Test
    fun `a progress write PUTs the session-stamped body to the contract path`() = runTest {
        var request: HttpRequestData? = null
        var body: String? = null
        val http = TestHttp {
            request = it
            body = (it.body as io.ktor.http.content.TextContent).text
            jsonResponse("""{"error":false,"message":"saved","data":{"watched":true}}""")
        }
        http.profiles.setPending("igd_test")

        val result = http.videoPlaybackRepository.updateWatchProgress(
            media,
            UpdateWatchProgressRequest(
                progressSec = 1800.0,
                durationSec = 3300.0,
                saveSessionId = "5e0f8f2a-9df1-4f2f-8a53-0d9f8f2a9df1",
                saveSequence = 2,
            ),
        )

        val captured = requireNotNull(request)
        assertEquals(HttpMethod.Put, captured.method)
        assertEquals("/api/$prefix/watch-progress", captured.url.encodedPath)
        assertEquals("Bearer igd_test", captured.headers[HttpHeaders.Authorization])
        assertEquals(
            """{"progress_sec":1800.0,"duration_sec":3300.0,""" +
                """"save_session_id":"5e0f8f2a-9df1-4f2f-8a53-0d9f8f2a9df1","save_sequence":2}""",
            body,
        )
        assertTrue((result as ApiResult.Success).value.watched)
    }
}
