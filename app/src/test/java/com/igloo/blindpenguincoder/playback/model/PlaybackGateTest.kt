package com.igloo.blindpenguincoder.playback.model

import com.igloo.blindpenguincoder.data.model.PlaybackMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackGateTest {

    private fun gate(
        mode: PlaybackMode = PlaybackMode.Direct,
        videoCodec: String? = "hevc",
        codec: String? = "truehd",
        profile: String? = null,
        channels: Int? = null,
        label: String? = "English · 7.1 surround",
        canPlayVideo: (String) -> Boolean = { true },
        canPlay: Boolean = true,
    ) = evaluatePlaybackGate(
        mode = mode,
        videoCodec = videoCodec,
        audioCodec = codec,
        audioCodecProfile = profile,
        audioChannels = channels,
        audioLabel = label,
        canPlayVideoMime = canPlayVideo,
        canPlayAudioMime = { canPlay },
    )

    // --- over a built request ---

    private val request = MoviePlayRequest(
        media = PlaybackMediaRef.Episode(900),
        title = "Severance · S1 E3 · In Perpetuity",
        posterUrl = null,
        mimeType = "video/x-matroska",
        mode = PlaybackMode.Direct,
        videoCodec = "h264",
        audioTypeIndex = null,
        subtitleTypeIndex = null,
        audioTracks = listOf(
            PlayableAudioTrack(label = "English · AAC", codec = "aac", channels = 2),
            PlayableAudioTrack(label = "English · TrueHD", codec = "truehd", channels = 8, isDefault = true),
        ),
        resumeAtSec = null,
        durationSec = null,
    )

    @Test
    fun `a request is gated on its selected track, asking capability with that track's channels`() {
        var askedChannels: Int? = null
        val result = evaluatePlaybackGate(
            request = request,
            canPlayVideoMime = { true },
            canPlayAudioMime = { _, channels ->
                askedChannels = channels
                false
            },
        ) as PlaybackGateResult.Blocked

        assertTrue(result.message.contains("English · TrueHD"))
        assertEquals(8, askedChannels)
    }

    @Test
    fun `the engine's mode and track override the request's own`() {
        val refuseTrueHd: (String, Int?) -> Boolean = { mime, _ -> mime != "audio/true-hd" }

        assertEquals(
            PlaybackGateResult.Proceed,
            evaluatePlaybackGate(request, { true }, refuseTrueHd, track = request.audioTracks[0]),
        )
        assertEquals(
            PlaybackGateResult.Proceed,
            evaluatePlaybackGate(request, { true }, refuseTrueHd, mode = PlaybackMode.Remux),
        )
    }

    // --- mode gating ---

    @Test
    fun `direct with a playable codec proceeds`() {
        assertEquals(PlaybackGateResult.Proceed, gate())
    }

    /** The backend guarantees an HLS mux is playable, so only Direct can ever be refused. */
    @Test
    fun `every non-direct mode proceeds even with an unplayable source codec`() {
        for (mode in PlaybackMode.entries.filter { it != PlaybackMode.Direct }) {
            assertEquals(PlaybackGateResult.Proceed, gate(mode = mode, canPlay = false))
        }
    }

    // --- capability gating ---

    @Test
    fun `an unplayable codec is blocked naming the codec, the track, and the remux way out`() {
        val result = gate(canPlay = false) as PlaybackGateResult.Blocked
        assertTrue(result.message.contains("Dolby TrueHD"))
        assertTrue(result.message.contains("English · 7.1 surround"))
        assertTrue(result.message.contains("Playback Settings"))
        assertTrue(result.message.contains(playbackModeLabel(PlaybackMode.Remux)))
    }

    @Test
    fun `a missing track label leaves the message whole`() {
        val result = gate(canPlay = false, label = null) as PlaybackGateResult.Blocked
        assertTrue(result.message.contains("Dolby TrueHD audio track —"))
    }

    @Test
    fun `unknown or missing codecs proceed rather than block on ignorance`() {
        assertEquals(PlaybackGateResult.Proceed, gate(codec = null, canPlay = false))
        assertEquals(PlaybackGateResult.Proceed, gate(codec = "exotic_new_codec", canPlay = false))
    }

    /** Tracks the engine converts via Remux are never refused, capability notwithstanding. */
    @Test
    fun `codecs covered by the automatic audio conversion proceed even when unplayable`() {
        assertEquals(PlaybackGateResult.Proceed, gate(codec = "dts", canPlay = false))
        assertEquals(
            PlaybackGateResult.Proceed,
            gate(codec = "dts", profile = "DTS-HD MA", channels = 6, canPlay = false),
        )
        assertEquals(
            PlaybackGateResult.Proceed,
            gate(codec = "aac", channels = 6, canPlay = false),
        )
    }

    /** Stereo AAC is outside the conversion's scope; its gate outcome is capability's as ever. */
    @Test
    fun `stereo aac still answers to capability alone`() {
        assertEquals(PlaybackGateResult.Proceed, gate(codec = "aac", channels = 2))
        assertTrue(
            gate(codec = "aac", channels = 2, canPlay = false) is PlaybackGateResult.Blocked,
        )
    }

    // --- video capability gating ---

    /** Media3 plays a DivX 3 AVI's sound over a black screen; the gate must stop it first. */
    @Test
    fun `undecodable video is blocked naming the codec and the converted way out`() {
        val result = gate(
            videoCodec = "msmpeg4v3",
            codec = "mp3",
            canPlayVideo = { false },
        ) as PlaybackGateResult.Blocked
        assertTrue(result.message.contains("DivX 3 (MS-MPEG-4) video"))
        assertTrue(result.message.contains("Playback Settings"))
        assertTrue(result.message.contains("converted qualities"))
    }

    @Test
    fun `the video decoder is asked about the codec's media3 mime type`() {
        val asked = mutableListOf<String>()
        gate(videoCodec = "msmpeg4v3", canPlayVideo = { asked += it; true })
        assertEquals(listOf("video/mp43"), asked)
    }

    @Test
    fun `decodable video proceeds`() {
        assertEquals(PlaybackGateResult.Proceed, gate(videoCodec = "mpeg4", canPlayVideo = { true }))
    }

    @Test
    fun `unmapped or missing video codecs proceed without consulting capability`() {
        val neverAsked: (String) -> Boolean = { error("asked about $it") }
        assertEquals(PlaybackGateResult.Proceed, gate(videoCodec = "h264", canPlayVideo = neverAsked))
        assertEquals(PlaybackGateResult.Proceed, gate(videoCodec = null, canPlayVideo = neverAsked))
    }

    @Test
    fun `every non-direct mode proceeds even with undecodable video`() {
        for (mode in PlaybackMode.entries.filter { it != PlaybackMode.Direct }) {
            assertEquals(
                PlaybackGateResult.Proceed,
                gate(mode = mode, videoCodec = "msmpeg4v3", canPlayVideo = { false }),
            )
        }
    }

    /** The audio conversion cannot bring back a picture, so it never excuses the video. */
    @Test
    fun `undecodable video is blocked even over a track the audio conversion covers`() {
        assertTrue(
            gate(videoCodec = "msmpeg4v3", codec = "dts", canPlayVideo = { false })
                is PlaybackGateResult.Blocked,
        )
    }

    // --- codec → MIME table ---

    @Test
    fun `codec names map to media3 mime types`() {
        assertEquals("audio/true-hd", audioCodecToMimeType("truehd", null))
        assertEquals("audio/ac3", audioCodecToMimeType("ac3", null))
        assertEquals("audio/eac3", audioCodecToMimeType("eac3", null))
        assertEquals("audio/eac3-joc", audioCodecToMimeType("eac3", "Dolby Digital Plus + JOC"))
        assertEquals("audio/vnd.dts", audioCodecToMimeType("dts", "DTS"))
        assertEquals("audio/vnd.dts.hd", audioCodecToMimeType("dts", "DTS-HD MA"))
        assertEquals(
            "audio/vnd.dts.uhd;profile=p2",
            audioCodecToMimeType("dts", "DTS-HD MA + DTS:X"),
        )
        assertEquals("audio/mp4a-latm", audioCodecToMimeType("aac", "LC"))
        assertEquals("audio/mpeg", audioCodecToMimeType("mp3", null))
        assertEquals("audio/flac", audioCodecToMimeType("flac", null))
        assertEquals("audio/opus", audioCodecToMimeType("opus", null))
        assertEquals("audio/vorbis", audioCodecToMimeType("vorbis", null))
        assertEquals("audio/raw", audioCodecToMimeType("pcm_s16le", null))
        assertEquals(null, audioCodecToMimeType("exotic_new_codec", null))
    }

    @Test
    fun `video codec names map to media3 mime types`() {
        assertEquals("video/hevc", videoCodecToMimeType("hevc"))
        assertEquals("video/x-vnd.on2.vp9", videoCodecToMimeType("vp9"))
        assertEquals("video/av01", videoCodecToMimeType("av1"))
        assertEquals("video/mpeg2", videoCodecToMimeType("mpeg2video"))
        assertEquals("video/mp4v-es", videoCodecToMimeType("mpeg4"))
        assertEquals("video/mp42", videoCodecToMimeType("msmpeg4v2"))
        assertEquals("video/mp43", videoCodecToMimeType("MSMPEG4V3"))
        assertEquals("video/wvc1", videoCodecToMimeType("vc1"))
        assertEquals(null, videoCodecToMimeType("h264"))
    }

    @Test
    fun `video display names read like a person would say them`() {
        assertEquals("HEVC", videoCodecDisplayName("hevc"))
        assertEquals("MPEG-4 Part 2", videoCodecDisplayName("mpeg4"))
        assertEquals("DivX 3 (MS-MPEG-4)", videoCodecDisplayName("msmpeg4v3"))
        assertEquals("PRORES", videoCodecDisplayName("prores"))
    }

    @Test
    fun `codec display names read like a person would say them`() {
        assertEquals("Dolby TrueHD", audioCodecDisplayName("truehd", null))
        assertEquals("Dolby Digital", audioCodecDisplayName("ac3", null))
        assertEquals("Dolby Digital Plus", audioCodecDisplayName("eac3", null))
        assertEquals(
            "Dolby Digital Plus with Atmos",
            audioCodecDisplayName("eac3", "Dolby Digital Plus + JOC"),
        )
        assertEquals("DTS", audioCodecDisplayName("dts", "DTS"))
        assertEquals("DTS-HD", audioCodecDisplayName("dts", "DTS-HD MA"))
        assertEquals("DTS:X", audioCodecDisplayName("dts", "DTS-HD MA + DTS:X"))
        assertEquals("AAC", audioCodecDisplayName("aac", null))
    }
}
