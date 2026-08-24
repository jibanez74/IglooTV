package com.igloo.blindpenguincoder.feature.movies

import com.igloo.blindpenguincoder.data.model.AudioStream
import com.igloo.blindpenguincoder.data.model.Chapter
import com.igloo.blindpenguincoder.data.model.Movie
import com.igloo.blindpenguincoder.data.model.MovieTechnicalDetailsData
import com.igloo.blindpenguincoder.data.model.MovieWatchProgress
import com.igloo.blindpenguincoder.data.model.PlaybackMode
import com.igloo.blindpenguincoder.data.model.SqlNullFloat64
import com.igloo.blindpenguincoder.data.model.SqlNullString
import com.igloo.blindpenguincoder.data.model.Subtitle
import com.igloo.blindpenguincoder.playback.model.PlaybackChapter
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoviePlayRequestMappingTest {

    @Test
    fun `carries the movie identity and the selected mode`() {
        val request = buildMoviePlayRequest(
            movie = movie(id = 7, title = "Heat", mimeType = "video/x-matroska"),
            posterUrl = "https://server/api/tmdb/images/w500/heat.jpg",
            technical = technical(),
            progress = null,
            selection = PlaybackSelection(mode = PlaybackMode.Direct),
        )

        assertEquals(7L, request.movieId)
        assertEquals("Heat", request.title)
        assertEquals("https://server/api/tmdb/images/w500/heat.jpg", request.posterUrl)
        assertEquals("video/x-matroska", request.mimeType)
        assertEquals(PlaybackMode.Direct, request.mode)
    }

    @Test
    fun `a movie without a poster carries no artwork`() {
        val request = buildMoviePlayRequest(
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

        val request = buildMoviePlayRequest(
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

        val request = buildMoviePlayRequest(
            movie = movie(),
            posterUrl = null,
            technical = tech,
            progress = null,
            selection = PlaybackSelection(),
        )

        assertEquals(1, request.audioTypeIndex)
        assertEquals("eac3", request.audioCodec)
    }

    @Test
    fun `a selection whose id vanished degrades to the default track`() {
        val tech = technical(
            audio = listOf(audioStream(id = 10, streamIndex = 1, isDefault = true)),
        )

        val request = buildMoviePlayRequest(
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
        val request = buildMoviePlayRequest(
            movie = movie(),
            posterUrl = null,
            technical = technical(subtitles = listOf(subtitle(id = 50, streamIndex = 5))),
            progress = null,
            selection = PlaybackSelection(),
        )

        assertNull(request.subtitleTypeIndex)
    }

    @Test
    fun `missing technical details start with container defaults and no codec claims`() {
        val request = buildMoviePlayRequest(
            movie = movie(),
            posterUrl = null,
            technical = null,
            progress = null,
            selection = PlaybackSelection(),
        )

        assertNull(request.audioTypeIndex)
        assertNull(request.subtitleTypeIndex)
        assertNull(request.audioCodec)
        assertNull(request.audioChannels)
        assertNull(request.audioLabel)
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

        val request = buildMoviePlayRequest(
            movie = movie(),
            posterUrl = null,
            technical = tech,
            progress = null,
            selection = PlaybackSelection(audioStreamId = 20),
        )

        assertEquals("truehd", request.audioCodec)
        assertEquals("TrueHD + Atmos", request.audioCodecProfile)
        assertEquals(8, request.audioChannels)
        assertEquals("English · 7.1 surround", request.audioLabel)
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

        val request = buildMoviePlayRequest(
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
    fun `no technical details or no chapters means an empty chapter list`() {
        val withoutTechnical = buildMoviePlayRequest(
            movie = movie(),
            posterUrl = null,
            technical = null,
            progress = null,
            selection = PlaybackSelection(),
        )
        assertEquals(emptyList<PlaybackChapter>(), withoutTechnical.chapters)

        val withoutChapters = buildMoviePlayRequest(
            movie = movie(),
            posterUrl = null,
            technical = technical(),
            progress = null,
            selection = PlaybackSelection(),
        )
        assertEquals(emptyList<PlaybackChapter>(), withoutChapters.chapters)
    }

    @Test
    fun `resume position needs thirty seconds and a position under 98 percent`() {
        assertNull(resumePositionSec(null))
        assertNull(resumePositionSec(progress(progressSec = null, durationSec = null)))
        assertNull(resumePositionSec(progress(progressSec = 29.0, durationSec = 7200.0)))
        assertEquals(
            30.0,
            resumePositionSec(progress(progressSec = 30.0, durationSec = 7200.0)),
        )
        assertNull(resumePositionSec(progress(progressSec = 7100.0, durationSec = 7200.0)))
        assertNull(resumePositionSec(progress(progressSec = 100.0, durationSec = 0.0)))
    }

    @Test
    fun `duration prefers the saved progress and falls back to the movie file`() {
        val withProgress = buildMoviePlayRequest(
            movie = movie(durationSec = 6000.0),
            posterUrl = null,
            technical = technical(),
            progress = progress(progressSec = 60.0, durationSec = 7200.0),
            selection = PlaybackSelection(),
        )
        assertEquals(7200.0, withProgress.durationSec)
        assertEquals(60.0, withProgress.resumeAtSec)

        val withoutProgress = buildMoviePlayRequest(
            movie = movie(durationSec = 6000.0),
            posterUrl = null,
            technical = technical(),
            progress = null,
            selection = PlaybackSelection(),
        )
        assertEquals(6000.0, withoutProgress.durationSec)
        assertNull(withoutProgress.resumeAtSec)
    }

    private fun movie(
        id: Long = 1,
        title: String = "Heat",
        mimeType: String = "video/x-matroska",
        durationSec: Double? = null,
    ) = Movie(
        id = id,
        title = title,
        filePath = "/media/heat.mkv",
        fileName = "heat.mkv",
        size = 4_000_000_000,
        container = "mkv",
        mimeType = mimeType,
        adult = false,
        duration = durationSec?.let { SqlNullFloat64(it, valid = true) },
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
    )

    private fun technical(
        audio: List<AudioStream> = listOf(audioStream(id = 1, streamIndex = 1, isDefault = true)),
        subtitles: List<Subtitle> = emptyList(),
        chapters: List<Chapter> = emptyList(),
    ) = MovieTechnicalDetailsData(
        movie = JsonObject(emptyMap()),
        videoStreams = emptyList(),
        audioStreams = audio,
        subtitles = subtitles,
        chapters = chapters,
    )

    private fun chapter(id: Long, title: String, startTime: Long) = Chapter(
        id = id,
        title = title,
        startTime = startTime,
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
        movieId = 1,
        streamIndex = streamIndex,
        codec = codec,
        codecProfile = codecProfile?.let { SqlNullString(it, valid = true) },
        bitRate = 0,
        channels = channels,
        channelLayout = channelLayout?.let { SqlNullString(it, valid = true) },
        language = language?.let { SqlNullString(it, valid = true) },
        isDefault = isDefault,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
    )

    private fun subtitle(
        id: Long,
        streamIndex: Long,
        codec: String = "subrip",
    ) = Subtitle(
        id = id,
        movieId = 1,
        streamIndex = streamIndex,
        codec = codec,
        language = SqlNullString("eng", valid = true),
        isForced = false,
        isDefault = false,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
    )

    private fun progress(progressSec: Double?, durationSec: Double?) = MovieWatchProgress(
        progressSec = progressSec,
        durationSec = durationSec,
        watched = false,
        updatedAt = null,
    )
}
