package com.igloo.blindpenguincoder.playback.hls

import com.igloo.blindpenguincoder.playback.model.HlsAudioProfile
import com.igloo.blindpenguincoder.playback.model.PlaybackMediaRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsSessionTest {

    private fun spec(
        audioTypeIndex: Int? = 1,
        startSec: Int = 90,
        reload: Int = 0,
        audioProfile: HlsAudioProfile? = null,
    ) = HlsSessionSpec(
        media = PlaybackMediaRef.Movie(7),
        profileId = "remux",
        audioTypeIndex = audioTypeIndex,
        startSec = startSec,
        sessionUuid = "5e0f8f2a-9df1-4f2f-8a53-0d9f8f2a9df1",
        reload = reload,
        audioProfile = audioProfile,
    )

    // --- query construction ---

    @Test
    fun `the query carries the session, the start, and the audio ordinal`() {
        assertEquals(
            listOf(
                "playback_session" to "5e0f8f2a-9df1-4f2f-8a53-0d9f8f2a9df1",
                "start" to "90",
                "audio_track" to "1",
            ),
            hlsQueryParams(spec()),
        )
    }

    @Test
    fun `a video-only movie omits the audio ordinal`() {
        assertTrue(hlsQueryParams(spec(audioTypeIndex = null)).none { it.first == "audio_track" })
    }

    @Test
    fun `start is always sent, zero included`() {
        assertTrue(hlsQueryParams(spec(startSec = 0)).contains("start" to "0"))
    }

    @Test
    fun `reload appears only once recovery has bumped it`() {
        assertTrue(hlsQueryParams(spec(reload = 0)).none { it.first == "reload" })
        assertTrue(hlsQueryParams(spec(reload = 2)).contains("reload" to "2"))
    }

    @Test
    fun `an audio profile adds the codec-channels pair after the audio ordinal`() {
        assertEquals(
            listOf(
                "playback_session" to "5e0f8f2a-9df1-4f2f-8a53-0d9f8f2a9df1",
                "start" to "90",
                "audio_track" to "1",
                "audio_codec" to "eac3",
                "audio_channels" to "6",
            ),
            hlsQueryParams(spec(audioProfile = HlsAudioProfile.DolbyDigitalPlus)),
        )
        assertTrue(
            hlsQueryParams(spec(audioProfile = HlsAudioProfile.DolbyDigital))
                .containsAll(listOf("audio_codec" to "ac3", "audio_channels" to "6")),
        )
    }

    /** The server 400s a lone half of the pair; no spec can ever emit one without the other. */
    @Test
    fun `the pair is all-or-nothing by construction`() {
        for (profile in listOf(null, HlsAudioProfile.DolbyDigital, HlsAudioProfile.DolbyDigitalPlus)) {
            val params = hlsQueryParams(spec(audioProfile = profile))
            assertEquals(
                params.any { it.first == "audio_codec" },
                params.any { it.first == "audio_channels" },
            )
        }
    }

    // --- manifest response classification ---

    private fun headers(vararg pairs: Pair<String, String>): (String) -> String? =
        { name -> pairs.toMap()[name] }

    @Test
    fun `a 200 reads the effective profile and actual start from the headers`() {
        val result = parseHlsManifestResponse(
            status = 200,
            spec = spec(),
            header = headers(
                "X-Igloo-Effective-Profile" to "1080p_8mbps",
                "X-Igloo-Actual-Start" to "87.417",
            ),
        )
        assertEquals(HlsManifestResult.Ready("1080p_8mbps", 87.417), result)
    }

    @Test
    fun `missing headers fall back to what was requested`() {
        val result = parseHlsManifestResponse(status = 200, spec = spec(), header = headers())
        assertEquals(HlsManifestResult.Ready("remux", 90.0), result)
    }

    @Test
    fun `a 503 is busy, with and without Retry-After`() {
        assertEquals(
            HlsManifestResult.Busy(retryAfterSec = 5),
            parseHlsManifestResponse(503, spec(), headers("Retry-After" to "5")),
        )
        assertEquals(
            HlsManifestResult.Busy(retryAfterSec = null),
            parseHlsManifestResponse(503, spec(), headers()),
        )
    }

    @Test
    fun `a 404 is a lost session and a 401 is unauthorized`() {
        assertEquals(HlsManifestResult.Lost, parseHlsManifestResponse(404, spec(), headers()))
        val unauthorized = parseHlsManifestResponse(401, spec(), headers()) as HlsManifestResult.Failed
        assertTrue(unauthorized.unauthorized)
    }

    @Test
    fun `any other status fails naming the code`() {
        val failed = parseHlsManifestResponse(500, spec(), headers()) as HlsManifestResult.Failed
        assertTrue(failed.message.contains("500"))
        assertTrue(!failed.unauthorized)
        assertFalse(failed.rejectedRequest)
    }

    /** 400/422 mean the server refused what was asked — the audio-profile fallback's trigger. */
    @Test
    fun `a 400 or 422 is marked as a rejected request`() {
        for (status in listOf(400, 422)) {
            val failed =
                parseHlsManifestResponse(status, spec(), headers()) as HlsManifestResult.Failed
            assertTrue(failed.rejectedRequest)
            assertTrue(failed.message.contains(status.toString()))
        }
    }
}
