package com.igloo.blindpenguincoder

import com.igloo.blindpenguincoder.feature.home.HomeAlbum
import com.igloo.blindpenguincoder.feature.home.HomeContinueMovie
import com.igloo.blindpenguincoder.feature.home.HomeHero
import com.igloo.blindpenguincoder.feature.home.HomeMovie
import com.igloo.blindpenguincoder.feature.home.HomeTheaterMovie
import com.igloo.blindpenguincoder.feature.movies.AboutUi
import com.igloo.blindpenguincoder.feature.movies.CastMemberUi
import com.igloo.blindpenguincoder.feature.movies.CrewEntry
import com.igloo.blindpenguincoder.feature.movies.ExtraVideoUi
import com.igloo.blindpenguincoder.feature.movies.MovieDetailsActions
import com.igloo.blindpenguincoder.feature.movies.MovieDetailsUi
import com.igloo.blindpenguincoder.feature.movies.PlaybackSelection
import com.igloo.blindpenguincoder.feature.movies.PlaybackSettingsUi
import com.igloo.blindpenguincoder.feature.movies.ProgressUi
import com.igloo.blindpenguincoder.feature.movies.playbackSettingsUi
import com.igloo.blindpenguincoder.core.ui.ratingBadgeSpec
import com.igloo.blindpenguincoder.data.model.AudioStream
import com.igloo.blindpenguincoder.data.model.SqlNullString
import com.igloo.blindpenguincoder.data.model.Subtitle

/**
 * Poster-less movies for shell-level tests: the placeholder path renders deterministically
 * with no network or image decoding involved.
 */
internal val testHomeMovies = listOf(
    HomeMovie(id = 1, title = "Heat", year = 1995, posterUrl = null),
    HomeMovie(id = 2, title = "Arrival", year = 2016, posterUrl = null),
    HomeMovie(id = 3, title = "Ran", year = 1985, posterUrl = null),
)

internal val testContinueMovies = listOf(
    HomeContinueMovie(testHomeMovies[0], progressFraction = 0.25f, progressLabel = "127 min left"),
    HomeContinueMovie(testHomeMovies[1], progressFraction = 0.5f, progressLabel = "58 min left"),
    HomeContinueMovie(testHomeMovies[2], progressFraction = 0.9f, progressLabel = "16 min left"),
)

/** Cover-less for the same reason: the Music glyph fallback needs no network. */
internal val testAlbums = listOf(
    HomeAlbum(id = 11, title = "Help!", musician = "The Beatles", coverUrl = null),
    HomeAlbum(id = 12, title = "1984", musician = "Van Halen", coverUrl = null),
    HomeAlbum(id = 13, title = "Tribalistas", musician = "Tribalistas", coverUrl = null),
)

/** Poster-less for the same reason; ratings span the badge's three tiers. */
internal val testTheaterMovies = listOf(
    HomeTheaterMovie(id = 21, title = "Heat 2", year = "2026", posterUrl = null, rating = 7.9),
    HomeTheaterMovie(id = 22, title = "The Odyssey", year = "2026", posterUrl = null, rating = 5.1),
    HomeTheaterMovie(id = 23, title = "Unrated", year = null, posterUrl = null, rating = null),
)

/** Backdrop-less on purpose: the card-surface fallback renders with no image loading. */
internal val testHero = HomeHero(
    id = 1,
    title = "Heat",
    backdropUrl = null,
    overview = "Obsessive master thief Neil McCauley leads a top-notch crew.",
    metadataLine = "1995 · R · 2h 50m · 8.2",
)

/** No-op actions for shells whose details overlay is closed. */
internal val inertDetailsActions = MovieDetailsActions.Library(
    onPlay = {},
    onToggleWatched = {},
    onToggleLike = {},
    onWatchTogether = {},
    onTechnicalDetails = {},
    onIdentifyMovie = {},
    onDeleteMovie = {},
    onSelectPlaybackMode = {},
    onSelectAudioTrack = {},
    onSelectSubtitle = {},
    onRetry = {},
)

/**
 * The Playback Settings dialog through the real mapping, so fixture labels and resolution rules
 * cannot drift from production. Two audio tracks (the default first), a text subtitle and an
 * image-based one — every row kind the dialog renders.
 */
internal fun testPlaybackSettings(
    selection: PlaybackSelection = PlaybackSelection(),
): PlaybackSettingsUi = playbackSettingsUi(
    audioStreams = testAudioStreams,
    subtitles = testSubtitles,
    selection = selection,
)

internal val testAudioStreams = listOf(
    testAudioStream(id = 301, language = "eng", isDefault = true),
    testAudioStream(id = 302, language = "spa", channels = 2, channelLayout = "stereo"),
)

internal val testSubtitles = listOf(
    testSubtitle(id = 401, codec = "subrip", language = "eng"),
    testSubtitle(id = 402, codec = "hdmv_pgs_subtitle", language = "spa"),
)

private fun testAudioStream(
    id: Long,
    language: String,
    channels: Long = 6,
    channelLayout: String = "5.1(side)",
    isDefault: Boolean = false,
) = AudioStream(
    id = id,
    movieId = 1,
    streamIndex = id,
    codec = "dts",
    bitRate = 0,
    channels = channels,
    channelLayout = SqlNullString(channelLayout, valid = true),
    language = SqlNullString(language, valid = true),
    title = null,
    isDefault = isDefault,
    createdAt = "2026-01-01 00:00:00",
    updatedAt = "2026-01-01 00:00:00",
)

private fun testSubtitle(
    id: Long,
    codec: String,
    language: String,
) = Subtitle(
    id = id,
    movieId = 1,
    streamIndex = id,
    codec = codec,
    language = SqlNullString(language, valid = true),
    title = null,
    isForced = false,
    isDefault = false,
    createdAt = "2026-01-01 00:00:00",
    updatedAt = "2026-01-01 00:00:00",
)

/** Image-less again: every artwork path falls back to a glyph, so nothing hits the network. */
internal fun testMovieDetails(
    id: Long = 1,
    title: String = "Heat",
    watched: Boolean? = false,
    liked: Boolean? = false,
    progress: ProgressUi? = ProgressUi(fraction = 0.25f, minutesLeftLabel = "127 min left"),
    cast: List<CastMemberUi> = testCast,
    extraVideos: List<ExtraVideoUi> = testExtraVideos,
    playbackSettings: PlaybackSettingsUi? = testPlaybackSettings(),
) = MovieDetailsUi(
    id = id,
    title = title,
    tagline = "A Los Angeles crime saga.",
    backdropUrl = null,
    posterUrl = null,
    ratingBadge = ratingBadgeSpec(8.2),
    certification = "R",
    mediaBadges = listOf("4K", "HDR10", "5.1", "CC"),
    runtimeText = "2h 50m",
    releaseDateText = "December 15, 1995",
    genresLine = "Crime · Drama",
    overview = "Obsessive master thief Neil McCauley leads a top-notch crew.",
    keyCrew = listOf(CrewEntry("Director", "Michael Mann")),
    cast = cast,
    extraVideos = extraVideos,
    about = AboutUi(
        production = "Regency Enterprises",
        language = "English",
        budget = "$60,000,000",
        revenue = "$187,436,818",
    ),
    progress = progress,
    watched = watched,
    liked = liked,
    metadataDescription = "Rated 8.2 out of 10, R, 4K, HDR10, 5.1 surround sound, " +
        "subtitles available, 2 hours 50 minutes, released December 15, 1995",
    playbackSettings = playbackSettings,
)

/**
 * The in-theaters page's render model (section 11.4.2): the same shape from a TMDB record, so
 * everything the library fills from its own reads is absent and the hero's action is a trailer.
 */
internal fun testTheaterMovieDetails(
    id: Long = 21,
    title: String = "Heat 2",
    heroTrailer: ExtraVideoUi? = testExtraVideos.first(),
    cast: List<CastMemberUi> = testCast,
    extraVideos: List<ExtraVideoUi> = testExtraVideos,
) = testMovieDetails(
    id = id,
    title = title,
    watched = null,
    liked = null,
    progress = null,
    cast = cast,
    extraVideos = extraVideos,
).copy(
    mediaBadges = emptyList(),
    about = AboutUi(
        production = "Regency Enterprises",
        language = "English",
        budget = "$60,000,000",
        revenue = "$187,436,818",
        status = "Released",
    ),
    metadataDescription = "Rated 8.2 out of 10, R, 2 hours 50 minutes, " +
        "released December 15, 1995",
    heroTrailer = heroTrailer,
    playbackSettings = null,
)

internal val testExtraVideos = listOf(
    ExtraVideoUi(
        id = 201,
        title = "Official Trailer",
        typeLabel = "Trailer",
        thumbnailUrl = null,
        key = "0xbkYZbdIVw",
    ),
    ExtraVideoUi(
        id = 202,
        title = "Making Heat",
        typeLabel = "Special feature",
        thumbnailUrl = null,
        key = "hV6ZBSD6VBw",
    ),
)

internal val testCast = listOf(
    CastMemberUi(id = 101, name = "Al Pacino", character = "Vincent Hanna", photoUrl = null),
    CastMemberUi(id = 102, name = "Robert De Niro", character = "Neil McCauley", photoUrl = null),
    CastMemberUi(id = 103, name = "Val Kilmer", character = "Chris Shiherlis", photoUrl = null),
)
