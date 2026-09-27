package com.igloo.blindpenguincoder.feature.movies

import com.igloo.blindpenguincoder.data.model.AudioStream
import com.igloo.blindpenguincoder.data.model.Chapter
import com.igloo.blindpenguincoder.data.model.Movie
import com.igloo.blindpenguincoder.data.model.MovieTechnicalDetailsData
import com.igloo.blindpenguincoder.data.model.MovieTechnicalFile
import com.igloo.blindpenguincoder.data.model.PlaybackMode
import com.igloo.blindpenguincoder.data.model.SqlNullFloat64
import com.igloo.blindpenguincoder.data.model.SqlNullString
import com.igloo.blindpenguincoder.data.model.Subtitle
import com.igloo.blindpenguincoder.data.model.VideoStream
import com.igloo.blindpenguincoder.data.model.WatchProgress
import com.igloo.blindpenguincoder.playback.model.PlayableAudioTrack
import com.igloo.blindpenguincoder.playback.model.PlayableSubtitleTrack
import com.igloo.blindpenguincoder.playback.model.PlaybackChapter
import com.igloo.blindpenguincoder.playback.model.PlaybackMediaRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoviePlayRequestMappingTest {

    @Test
    fun `carries the movie identity and the selected mode`() {
        val request = build(
            movie = movie(id = 7, title = "Heat"),
            posterUrl = "https://server/api/tmdb/images/w500/heat.jpg",
            technical = technical(mimeType = "video/x-matroska"),
            progress = null,
            selection = PlaybackSelection(mode = PlaybackMode.Direct),
        )

        assertEquals(PlaybackMediaRef.Movie(7), request.media)
        assertEquals("Heat", request.title)
        assertEquals("https://server/api/tmdb/images/w500/heat.jpg", request.posterUrl)
        assertEquals("video/x-matroska", request.mimeType)
        assertEquals(PlaybackMode.Direct, request.mode)
    }

    @Test
    fun `an episode carries its ref and composed title untouched`() {
        val request = buildVideoPlayRequest(
            media = PlaybackMediaRef.Episode(900),
            title = "Severance · S1 E3 · In Perpetuity",
            posterUrl = null,
            mimeType = "video/x-matroska",
            videoStreams = emptyList(),
            audioStreams = emptyList(),
            subtitles = emptyList(),
            chapters = emptyList(),
            progress = null,
            fileDurationSec = 3300.0,
            selection = PlaybackSelection(),
        )

        assertEquals(PlaybackMediaRef.Episode(900), request.media)
        assertEquals("Severance · S1 E3 · In Perpetuity", request.title)
        assertEquals(3300.0, request.durationSec)
    }

    @Test
    fun `a movie without a poster carries no artwork`() {
        val request = build(
            movie = movie(),
            posterUrl = null,
            technical = technical(),
            progress = null,
            selection = PlaybackSelection(),
        )

        assertNull(request.posterUrl)
    }

    /**
     * The wire list is not trusted to arrive sorted: the type index counts streams in
     * `stream_index` order, the ordering ExoPlayer sees after demuxing.
     */
    @Test
    fun `type indexes count in stream_index order, not wire order`() {
        val tech = technical(
            audio = listOf(
                audioStream(id = 30, streamIndex = 3, language = "fre"),
                audioStream(id = 10, streamIndex = 1, isDefault = true),
                audioStream(id = 20, streamIndex = 2, language = "spa"),
            ),
            subtitles = listOf(
                subtitle(id = 60, streamIndex = 6),
                subtitle(id = 50, streamIndex = 5),
            ),
        )

        val request = build(
            movie = movie(),
            posterUrl = null,
            technical = tech,
            progress = null,
            selection = PlaybackSelection(audioStreamId = 20, subtitleStreamId = 60),
        )

        // id 20 is second in stream_index order (1, 2, 3) though last-but-one on the wire.
        assertEquals(1, request.audioTypeIndex)
        // id 60 is second in stream_index order (5, 6) though first on the wire.
        assertEquals(1, request.subtitleTypeIndex)
    }

    @Test
    fun `no explicit audio choice resolves to the default track like the settings dialog`() {
        val tech = technical(
            audio = listOf(
                audioStream(id = 10, streamIndex = 1),
                audioStream(id = 20, streamIndex = 2, isDefault = true, codec = "eac3"),
            ),
        )

        val request = build(
            movie = movie(),
            posterUrl = null,
            technical = tech,
            progress = null,
            selection = PlaybackSelection(),
        )

        assertEquals(1, request.audioTypeIndex)
        assertEquals("eac3", request.selectedAudioTrack?.codec)
    }

    @Test
    fun `a selection whose id vanished degrades to the default track`() {
        val tech = technical(
            audio = listOf(audioStream(id = 10, streamIndex = 1, isDefault = true)),
        )

        val request = build(
            movie = movie(),
            posterUrl = null,
            technical = tech,
            progress = null,
            selection = PlaybackSelection(audioStreamId = 999),
        )

        assertEquals(0, request.audioTypeIndex)
    }

    @Test
    fun `no subtitle selection means subtitles off`() {
        val request = build(
            movie = movie(),
            posterUrl = null,
            technical = technical(subtitles = listOf(subtitle(id = 50, streamIndex = 5))),
            progress = null,
            selection = PlaybackSelection(),
        )

        assertNull(request.subtitleTypeIndex)
    }

    @Test
    fun `a file with no probed streams starts with container defaults and no codec claims`() {
        val request = build(
            movie = movie(),
            posterUrl = null,
            technical = technical(audio = emptyList()),
            progress = null,
            selection = PlaybackSelection(),
        )

        assertNull(request.audioTypeIndex)
        assertNull(request.subtitleTypeIndex)
        assertNull(request.selectedAudioTrack)
        assertNull(request.videoCodec)
        assertEquals(emptyList<PlayableAudioTrack>(), request.audioTracks)
        assertEquals(emptyList<PlayableSubtitleTrack>(), request.subtitleTracks)
    }

    @Test
    fun `the gate's facts come from the effective audio stream`() {
        val tech = technical(
            audio = listOf(
                audioStream(id = 10, streamIndex = 1, isDefault = true),
                audioStream(
                    id = 20,
                    streamIndex = 2,
                    codec = "truehd",
                    codecProfile = "TrueHD + Atmos",
                    channels = 8,
                    channelLayout = "7.1",
                    language = "eng",
                ),
            ),
        )

        val request = build(
            movie = movie(),
            posterUrl = null,
            technical = tech,
            progress = null,
            selection = PlaybackSelection(audioStreamId = 20),
        )

        val selected = requireNotNull(request.selectedAudioTrack)
        assertEquals("truehd", selected.codec)
        assertEquals("TrueHD + Atmos", selected.codecProfile)
        assertEquals(8, selected.channels)
        assertEquals("English · 7.1 surround", selected.label)
    }

    /** An embedded cover-art thumbnail is a video stream too; the gate must judge the picture. */
    @Test
    fun `the gate's video codec comes from the widest video stream`() {
        val request = build(
            movie = movie(),
            posterUrl = null,
            technical = technical(
                video = listOf(
                    videoStream(streamIndex = 0, codec = "msmpeg4v3", width = 640, height = 480),
                    videoStream(streamIndex = 3, codec = "mjpeg", width = 320, height = 240),
                ),
            ),
            progress = null,
            selection = PlaybackSelection(),
        )

        assertEquals("msmpeg4v3", request.videoCodec)
    }

    /**
     * The player's HLS menus and session parameters index into these lists, so their order
     * must be `stream_index` order regardless of how the wire delivered them, and image-based
     * subtitle rows must stay in place — their position is the backend's `trackIndex` ordinal.
     */
    @Test
    fun `track summaries ride in stream_index order with labels and flags`() {
        val tech = technical(
            audio = listOf(
                audioStream(id = 30, streamIndex = 3, language = "fre", channels = 2, channelLayout = "stereo"),
                audioStream(id = 10, streamIndex = 1, isDefault = true),
            ),
            subtitles = listOf(
                subtitle(id = 60, streamIndex = 6, codec = "hdmv_pgs_subtitle"),
                subtitle(id = 50, streamIndex = 5),
            ),
        )

        val request = build(
            movie = movie(),
            posterUrl = null,
            technical = tech,
            progress = null,
            selection = PlaybackSelection(),
        )

        assertEquals(listOf("English · 5.1 surround", "French · Stereo"), request.audioTracks.map { it.label })
        assertEquals(listOf(true, false), request.audioTracks.map { it.isDefault })
        assertEquals(listOf(false, true), request.subtitleTracks.map { it.imageBased })
    }

    @Test
    fun `chapters ride sorted by start time with raw titles and seconds as doubles`() {
        val tech = technical(
            chapters = listOf(
                chapter(id = 3, title = "", startTime = 1800),
                chapter(id = 1, title = "Opening Credits", startTime = 0),
                chapter(id = 2, title = "The Heist", startTime = 600),
            ),
        )

        val request = build(
            movie = movie(),
            posterUrl = null,
            technical = tech,
            progress = null,
            selection = PlaybackSelection(),
        )

        assertEquals(
            listOf(
                PlaybackChapter(title = "Opening Credits", startTimeSec = 0.0),
                PlaybackChapter(title = "The Heist", startTimeSec = 600.0),
                PlaybackChapter(title = "", startTimeSec = 1800.0),
            ),
            request.chapters,
        )
    }

    @Test
    fun `no chapters means an empty chapter list`() {
        val request = build(
            movie = movie(),
            posterUrl = null,
            technical = technical(chapters = emptyList()),
            progress = null,
            selection = PlaybackSelection(),
        )
        assertEquals(emptyList<PlaybackChapter>(), request.chapters)
    }

    @Test
    fun `resume position needs thirty seconds and a position under 95 percent`() {
        assertNull(resumePositionSec(null))
        assertNull(resumePositionSec(progress(progressSec = null, durationSec = null)))
        assertNull(resumePositionSec(progress(progressSec = 29.0, durationSec = 7200.0)))
        assertEquals(
            30.0,
            resumePositionSec(progress(progressSec = 30.0, durationSec = 7200.0)),
        )
        assertEquals(
            6839.999,
            resumePositionSec(progress(progressSec = 6839.999, durationSec = 7200.0)),
        )
        assertNull(resumePositionSec(progress(progressSec = 6840.0, durationSec = 7200.0)))
        assertNull(resumePositionSec(progress(progressSec = 100.0, durationSec = 0.0)))
        assertNull(resumePositionSec(progress(progressSec = 100.0, durationSec = -1.0)))
    }

    @Test
    fun `resume position rejects non-finite progress and duration`() {
        assertNull(resumePositionSec(progress(progressSec = Double.NaN, durationSec = 7200.0)))
        assertNull(
            resumePositionSec(
                progress(progressSec = Double.POSITIVE_INFINITY, durationSec = 7200.0),
            ),
        )
        assertNull(
            resumePositionSec(
                progress(progressSec = Double.NEGATIVE_INFINITY, durationSec = 7200.0),
            ),
        )
        assertNull(resumePositionSec(progress(progressSec = 100.0, durationSec = Double.NaN)))
        assertNull(
            resumePositionSec(
                progress(progressSec = 100.0, durationSec = Double.POSITIVE_INFINITY),
            ),
        )
        assertNull(
            resumePositionSec(
                progress(progressSec = 100.0, durationSec = Double.NEGATIVE_INFINITY),
            ),
        )
    }

    @Test
    fun `duration prefers the saved progress and falls back to the movie file`() {
        val withProgress = build(
            movie = movie(durationSec = 6000.0),
            posterUrl = null,
            technical = technical(),
            progress = progress(progressSec = 60.0, durationSec = 7200.0),
            selection = PlaybackSelection(),
        )
        assertEquals(7200.0, withProgress.durationSec)
        assertEquals(60.0, withProgress.resumeAtSec)

        val withoutProgress = build(
            movie = movie(durationSec = 6000.0),
            posterUrl = null,
            technical = technical(),
            progress = null,
            selection = PlaybackSelection(),
        )
        assertEquals(6000.0, withoutProgress.durationSec)
        assertNull(withoutProgress.resumeAtSec)
    }

    /** The movie details page's call shape, so each case names only the fragment it varies. */
    private fun build(
        movie: Movie,
        posterUrl: String?,
        technical: MovieTechnicalDetailsData,
        progress: WatchProgress?,
        selection: PlaybackSelection,
    ) = buildVideoPlayRequest(
        media = PlaybackMediaRef.Movie(movie.id),
        title = movie.title,
        posterUrl = posterUrl,
        mimeType = technical.movie.mimeType,
        videoStreams = technical.videoStreams,
        audioStreams = technical.audioStreams,
        subtitles = technical.subtitles,
        chapters = technical.chapters,
        progress = progress,
        fileDurationSec = movie.duration?.orNull(),
        selection = selection,
    )

    private fun movie(
        id: Long = 1,
        title: String = "Heat",
        durationSec: Double? = null,
    ) = Movie(
        id = id,
        title = title,
        adult = false,
        duration = durationSec?.let { SqlNullFloat64(it, valid = true) },
    )

    private fun technical(
        mimeType: String = "video/x-matroska",
        video: List<VideoStream> = emptyList(),
        audio: List<AudioStream> = listOf(audioStream(id = 1, streamIndex = 1, isDefault = true)),
        subtitles: List<Subtitle> = emptyList(),
        chapters: List<Chapter> = emptyList(),
    ) = MovieTechnicalDetailsData(
        movie = MovieTechnicalFile(mimeType = mimeType),
        videoStreams = video,
        audioStreams = audio,
        subtitles = subtitles,
        chapters = chapters,
    )

    private fun chapter(id: Long, title: String, startTime: Long) = Chapter(
        id = id,
        title = title,
        startTime = startTime,
    )

    private fun videoStream(streamIndex: Long, codec: String, width: Long, height: Long) =
        VideoStream(
            id = streamIndex,
            streamIndex = streamIndex,
            codec = codec,
            bitRate = 0,
            width = width,
            height = height,
            frameRate = 23.976,
        )

    private fun audioStream(
        id: Long,
        streamIndex: Long,
        codec: String = "dts",
        codecProfile: String? = null,
        channels: Long = 6,
        channelLayout: String? = "5.1(side)",
        language: String? = "eng",
        isDefault: Boolean = false,
    ) = AudioStream(
        id = id,
        streamIndex = streamIndex,
        codec = codec,
        codecProfile = codecProfile?.let { SqlNullString(it, valid = true) },
        bitRate = 0,
        channels = channels,
        channelLayout = channelLayout?.let { SqlNullString(it, valid = true) },
        language = language?.let { SqlNullString(it, valid = true) },
        isDefault = isDefault,
    )

    private fun subtitle(
        id: Long,
        streamIndex: Long,
        codec: String = "subrip",
    ) = Subtitle(
        id = id,
        streamIndex = streamIndex,
        codec = codec,
        language = SqlNullString("eng", valid = true),
        isForced = false,
        isDefault = false,
    )

    private fun progress(progressSec: Double?, durationSec: Double?) = WatchProgress(
        progressSec = progressSec,
        durationSec = durationSec,
        watched = false,
        updatedAt = null,
    )
}
