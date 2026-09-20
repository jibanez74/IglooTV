package com.igloo.blindpenguincoder

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.data.model.MovieWatchProgressUpdateData
import com.igloo.blindpenguincoder.feature.home.HomeAlbum
import com.igloo.blindpenguincoder.feature.home.HomeContinueMovie
import com.igloo.blindpenguincoder.feature.home.HomeHero
import com.igloo.blindpenguincoder.feature.home.HomeTheaterMovie
import com.igloo.blindpenguincoder.feature.movies.AboutUi
import com.igloo.blindpenguincoder.feature.movies.CastMemberUi
import com.igloo.blindpenguincoder.feature.movies.CrewEntry
import com.igloo.blindpenguincoder.feature.movies.ExtraVideoUi
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.feature.movies.MovieDetailsActions
import com.igloo.blindpenguincoder.feature.movies.MoviesActions
import com.igloo.blindpenguincoder.feature.movies.MoviesAppendState
import com.igloo.blindpenguincoder.feature.movies.MoviesFilter
import com.igloo.blindpenguincoder.feature.movies.MoviesTab
import com.igloo.blindpenguincoder.feature.movies.MoviesUiState
import com.igloo.blindpenguincoder.feature.shared.MoviePosterItem
import com.igloo.blindpenguincoder.feature.movies.MovieDetailsUi
import com.igloo.blindpenguincoder.feature.movies.PlaybackSelection
import com.igloo.blindpenguincoder.feature.movies.PlaybackSettingsUi
import com.igloo.blindpenguincoder.feature.movies.ProgressUi
import com.igloo.blindpenguincoder.feature.movies.playbackSettingsUi
import com.igloo.blindpenguincoder.feature.music.AlbumDetailsUi
import com.igloo.blindpenguincoder.feature.music.AlbumDiscUi
import com.igloo.blindpenguincoder.feature.music.AlbumFactUi
import com.igloo.blindpenguincoder.feature.music.AlbumArtistUi
import com.igloo.blindpenguincoder.feature.shared.TrackRowUi
import com.igloo.blindpenguincoder.feature.player.MoviePlayerViewModel
import com.igloo.blindpenguincoder.playback.media3.FakeMoviePlayerEngine
import com.igloo.blindpenguincoder.playback.media3.FakeMusicPlayerEngine
import com.igloo.blindpenguincoder.playback.media3.MoviePlayerEngine
import com.igloo.blindpenguincoder.playback.media3.MusicPlayerEngine
import com.igloo.blindpenguincoder.playback.model.MoviePlayRequest
import com.igloo.blindpenguincoder.playback.model.MusicPlayRequest
import com.igloo.blindpenguincoder.core.ui.ratingBadgeSpec
import com.igloo.blindpenguincoder.data.model.AudioStream
import com.igloo.blindpenguincoder.data.model.MovieGenreWithCount
import com.igloo.blindpenguincoder.data.model.SortOrder
import com.igloo.blindpenguincoder.data.model.SqlNullString
import com.igloo.blindpenguincoder.data.model.Subtitle

/**
 * Poster-less movies for shell-level tests: the placeholder path renders deterministically
 * with no network or image decoding involved.
 */
internal val testHomeMovies = listOf(
    MoviePosterItem(id = 1, title = "Heat", year = 1995, posterUrl = null),
    MoviePosterItem(id = 2, title = "Arrival", year = 2016, posterUrl = null),
    MoviePosterItem(id = 3, title = "Ran", year = 1985, posterUrl = null),
)

/**
 * Poster-less like the rail fixtures, and long enough to fill several rows at every
 * `gridColumns` value so the grid's paging and focus contracts have somewhere to travel.
 */
internal val testMovieGridItems = (1L..40L).map { id ->
    MoviePosterItem(id = id, title = "Movie $id", year = 1980 + id, posterUrl = null)
}

/** Two genres cover selected-vs-not and give the chip row a d-pad path to travel. */
internal val testGenres = listOf(
    MovieGenreWithCount(genreId = 7, genreTag = "Action", movieCount = 26),
    MovieGenreWithCount(genreId = 9, genreTag = "Drama", movieCount = 14),
)

/** A grid that has loaded its first page and has more to come. */
internal fun testMoviesState(
    grid: IglooRailState<MoviePosterItem> = IglooRailState.Loaded(testMovieGridItems),
    append: MoviesAppendState = MoviesAppendState.Idle,
    totalMovies: Long? = 96,
    tab: MoviesTab = MoviesTab.All,
    genre: MoviesFilter.Genre? = null,
    genresLoaded: Boolean = true,
    sort: SortOrder = SortOrder.Ascending,
    genres: List<MovieGenreWithCount> = testGenres,
    refreshing: Boolean = false,
    notice: String? = null,
    appendGeneration: Int = 0,
    contentGeneration: Int = 0,
    silentReconcileGeneration: Int = 0,
) = MoviesUiState(
    totalMovies = totalMovies,
    tab = tab,
    genre = genre,
    genresLoaded = genresLoaded,
    sort = sort,
    genres = genres,
    grid = grid,
    append = append,
    refreshing = refreshing,
    notice = notice,
    appendGeneration = appendGeneration,
    contentGeneration = contentGeneration,
    silentReconcileGeneration = silentReconcileGeneration,
)

internal val inertMoviesActions = MoviesActions(
    onRefresh = {},
    onRetryFirstPage = {},
    onRetryAppend = {},
    onLoadMore = {},
    onSelectTab = {},
    onPressTab = {},
    onSelectGenre = {},
    onToggleSort = {},
)

internal val testContinueMovies = listOf(
    HomeContinueMovie(
        testHomeMovies[0],
        progressFraction = 0.25f,
        progressDescription = "2 hours and 7 minutes remaining",
    ),
    HomeContinueMovie(
        testHomeMovies[1],
        progressFraction = 0.5f,
        progressDescription = "58 minutes remaining",
    ),
    HomeContinueMovie(
        testHomeMovies[2],
        progressFraction = 0.9f,
        progressDescription = "16 minutes remaining",
    ),
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
 * The movie player wiring for shells that never press Play: no request ever launches, saves
 * succeed silently, and the factory hands out an inert fake so no decoder is touched.
 */
@Composable
internal fun rememberInertMoviePlayerViewModel(): MoviePlayerViewModel = remember {
    MoviePlayerViewModel(
        saveProgress = { _, _ -> ApiResult.Success(MovieWatchProgressUpdateData(watched = false)) },
        onWatchedStateCommitted = {},
    )
}

internal val fakeMoviePlayerEngineFactory: (Context, MoviePlayRequest) -> MoviePlayerEngine =
    { _, _ -> FakeMoviePlayerEngine() }

internal val fakeMusicPlayerEngineFactory: (Context, MusicPlayRequest) -> MusicPlayerEngine =
    { _, request -> FakeMusicPlayerEngine(request.tracks.map { it.durationSec }) }

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
    progress: ProgressUi? = ProgressUi(
        fraction = 0.25f,
        remainingTimeLabel = "2h 20m left",
        resumeStateDescription = "Resume from 1 hour, 3 minutes, and 17 seconds; " +
            "2 hours and 20 minutes remaining",
    ),
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
        "subtitles available, 2 hours and 50 minutes, released December 15, 1995",
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
    metadataDescription = "Rated 8.2 out of 10, R, 2 hours and 50 minutes, " +
        "released December 15, 1995",
    heroTrailer = heroTrailer,
    playbackSettings = null,
)

/**
 * Cover-less for the glyph fallback; two discs so the disc headers and the folded "Disc N."
 * sentences render; the spoken strings are pinned literals, so an a11y assertion reads exactly
 * what the mapping contract promises.
 */
internal fun testAlbumDetails(
    id: Long = 11,
    title: String = "Help!",
) = AlbumDetailsUi(
    id = id,
    title = title,
    artistName = "The Beatles",
    coverUrl = null,
    releaseDateText = "August 6, 1965",
    trackCountText = "3 tracks",
    totalDurationText = "7m 5s",
    genresLine = "Rock · Pop",
    popularity = 73,
    artists = listOf(AlbumArtistUi(id = 4, name = "The Beatles")),
    discs = listOf(
        AlbumDiscUi(
            disc = 1,
            tracks = listOf(
                testAlbumTrackRow(
                    id = 901,
                    indexText = "1",
                    title = "Yesterday",
                    subtitle = "Rock, Pop",
                    durationText = "2:05",
                    durationSec = 125.0,
                    spokenInfo = "Disc 1. Track 1. Yesterday. Rock, Pop. " +
                        "2 minutes and 5 seconds.",
                ),
                testAlbumTrackRow(
                    id = 902,
                    indexText = "2",
                    title = "Ticket to Ride",
                    subtitle = null,
                    durationText = "3:10",
                    durationSec = 190.0,
                    spokenInfo = "Track 2. Ticket to Ride. 3 minutes and 10 seconds.",
                ),
            ),
        ),
        AlbumDiscUi(
            disc = 2,
            tracks = listOf(
                testAlbumTrackRow(
                    id = 903,
                    indexText = "1",
                    title = "Act Naturally",
                    subtitle = null,
                    durationText = "1:50",
                    durationSec = 110.0,
                    spokenInfo = "Disc 2. Track 1. Act Naturally. " +
                        "1 minute and 50 seconds.",
                ),
            ),
        ),
    ),
    hasMultipleDiscs = true,
    facts = listOf(
        AlbumFactUi("Release date", "August 6, 1965"),
        AlbumFactUi("Total tracks", "3"),
        AlbumFactUi("Total duration", "7m 5s"),
        AlbumFactUi("Artist", "The Beatles"),
        AlbumFactUi("Genres", "Rock, Pop"),
        AlbumFactUi("Discs", "2"),
        AlbumFactUi("Audio quality", "FLAC · 900 kbps · stereo"),
        AlbumFactUi("Spotify popularity", "73 / 100"),
    ),
    factsDescription = "Album details. Release date: August 6, 1965. Total tracks: 3. " +
        "Total duration: 7m 5s. Artist: The Beatles. Genres: Rock, Pop. Discs: 2. " +
        "Audio quality: FLAC · 900 kbps · stereo. Spotify popularity: 73 / 100.",
    heroInfoDescription = "Help! by The Beatles. 3 tracks. " +
        "Total duration: 7 minutes and 5 seconds. Genres: Rock, Pop. " +
        "Spotify popularity 73 out of 100.",
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

/** An album-page row: index gutter, genre subtitle, no album to go to, the album's artist. */
internal fun testAlbumTrackRow(
    id: Long,
    indexText: String,
    title: String,
    subtitle: String?,
    durationText: String,
    durationSec: Double,
    spokenInfo: String,
    musicianId: Long? = 4,
) = TrackRowUi(
    id = id,
    title = title,
    subtitle = subtitle,
    indexText = indexText,
    durationText = durationText,
    durationSec = durationSec,
    albumId = null,
    musicianId = musicianId,
    spokenInfo = spokenInfo,
)
