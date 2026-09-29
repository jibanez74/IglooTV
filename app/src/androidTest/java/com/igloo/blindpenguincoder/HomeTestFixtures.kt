package com.igloo.blindpenguincoder

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.core.ui.ratingBadgeSpec
import com.igloo.blindpenguincoder.data.model.AudioStream
import com.igloo.blindpenguincoder.data.model.WatchProgressUpdateData
import com.igloo.blindpenguincoder.data.model.SortOrder
import com.igloo.blindpenguincoder.data.model.SqlNullString
import com.igloo.blindpenguincoder.data.model.Subtitle
import com.igloo.blindpenguincoder.feature.home.HomeAlbum
import com.igloo.blindpenguincoder.feature.home.HomeContinueItem
import com.igloo.blindpenguincoder.feature.home.HomeHero
import com.igloo.blindpenguincoder.feature.home.HomeTheaterMovie
import com.igloo.blindpenguincoder.feature.library.LibraryActions
import com.igloo.blindpenguincoder.feature.library.LibraryFilter
import com.igloo.blindpenguincoder.feature.library.LibraryGenre
import com.igloo.blindpenguincoder.feature.library.LibraryKind
import com.igloo.blindpenguincoder.feature.library.LibraryTab
import com.igloo.blindpenguincoder.feature.library.LibraryUiState
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
import com.igloo.blindpenguincoder.data.model.MusicStats
import com.igloo.blindpenguincoder.data.model.SqlNullInt64
import com.igloo.blindpenguincoder.data.model.TrackListItem
import com.igloo.blindpenguincoder.feature.music.AlbumArtistUi
import com.igloo.blindpenguincoder.feature.music.AlbumCardUi
import com.igloo.blindpenguincoder.feature.music.MusicActions
import com.igloo.blindpenguincoder.feature.music.MusicTab
import com.igloo.blindpenguincoder.feature.music.MusicUiState
import com.igloo.blindpenguincoder.feature.music.MusicianCardUi
import com.igloo.blindpenguincoder.feature.music.MusicianDetailsUi
import com.igloo.blindpenguincoder.feature.music.PagedState
import com.igloo.blindpenguincoder.feature.music.TracksEntry
import com.igloo.blindpenguincoder.feature.music.tracksEntries
import com.igloo.blindpenguincoder.feature.music.AlbumDetailsUi
import com.igloo.blindpenguincoder.feature.music.AlbumDiscUi
import com.igloo.blindpenguincoder.feature.music.AlbumFactUi
import com.igloo.blindpenguincoder.feature.player.VideoPlayerViewModel
import com.igloo.blindpenguincoder.feature.shared.AppendState
import com.igloo.blindpenguincoder.feature.shared.PosterItem
import com.igloo.blindpenguincoder.feature.shared.TrackRowUi
import com.igloo.blindpenguincoder.playback.media3.FakeVideoPlayerEngine
import com.igloo.blindpenguincoder.playback.media3.FakeMusicPlayerEngine
import com.igloo.blindpenguincoder.playback.media3.VideoPlayerEngine
import com.igloo.blindpenguincoder.playback.media3.MusicPlayerEngine
import com.igloo.blindpenguincoder.playback.model.VideoPlayRequest
import com.igloo.blindpenguincoder.playback.model.MusicPlayRequest

/**
 * Poster-less movies for shell-level tests: the placeholder path renders deterministically
 * with no network or image decoding involved.
 */
internal val testHomeMovies = listOf(
    PosterItem(id = 1, title = "Heat", year = 1995, posterUrl = null),
    PosterItem(id = 2, title = "Arrival", year = 2016, posterUrl = null),
    PosterItem(id = 3, title = "Ran", year = 1985, posterUrl = null),
)

/**
 * Poster-less like the rail fixtures, and long enough to fill several rows at every
 * `gridColumns` value so the grid's paging and focus contracts have somewhere to travel.
 */
internal val testMovieGridItems = (1L..40L).map { id ->
    PosterItem(id = id, title = "Movie $id", year = 1980 + id, posterUrl = null)
}

/** Two genres cover selected-vs-not and give the chip row a d-pad path to travel. */
internal val testGenres = listOf(
    LibraryGenre(id = 7, tag = "Action", count = 26),
    LibraryGenre(id = 9, tag = "Drama", count = 14),
)

/** No-op actions for either library pane. */
internal val inertLibraryActions = LibraryActions(
    onRefresh = {},
    onRetryFirstPage = {},
    onRetryAppend = {},
    onLoadMore = {},
    onSelectTab = {},
    onPressTab = {},
    onSelectGenre = {},
    onToggleSort = {},
)

/** Poster-less shows, long enough to fill several rows at every `gridColumns` value. */
internal val testShowGridItems = (1L..40L).map { id ->
    PosterItem(id = id, title = "Show $id", year = 2000 + id, posterUrl = null)
}

/** A count of one exercises the singular noun the chip speaks. */
internal val testShowGenres = listOf(
    LibraryGenre(id = 7, tag = "Comedy", count = 2),
    LibraryGenre(id = 9, tag = "Drama", count = 1),
)

/**
 * A library pane that has loaded its first page and has more to come. [kind] picks the fixture
 * items and genres, and the strip the pane's source offers: Movies has Liked, TV Shows does not.
 */
internal fun testLibraryState(
    kind: LibraryKind = LibraryKind.Movies,
    grid: IglooRailState<PosterItem> = IglooRailState.Loaded(
        if (kind == LibraryKind.Shows) testShowGridItems else testMovieGridItems,
    ),
    append: AppendState = AppendState.Idle,
    total: Long? = 96,
    tab: LibraryTab = LibraryTab.All,
    genre: LibraryFilter.Genre? = null,
    genresLoaded: Boolean = true,
    sort: SortOrder = SortOrder.Ascending,
    genres: List<LibraryGenre> = if (kind == LibraryKind.Shows) testShowGenres else testGenres,
    refreshing: Boolean = false,
    notice: String? = null,
    appendGeneration: Int = 0,
    contentGeneration: Int = 0,
    silentReconcileGeneration: Int = 0,
) = LibraryUiState(
    kind = kind,
    tabs = if (kind == LibraryKind.Shows) {
        listOf(LibraryTab.All, LibraryTab.Genres)
    } else {
        LibraryTab.entries
    },
    total = total,
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

internal val testContinueMovies: List<HomeContinueItem> = listOf(
    HomeContinueItem.Movie(
        testHomeMovies[0],
        progressFraction = 0.25f,
        progressDescription = "2 hours and 7 minutes remaining",
    ),
    HomeContinueItem.Movie(
        testHomeMovies[1],
        progressFraction = 0.5f,
        progressDescription = "58 minutes remaining",
    ),
    HomeContinueItem.Movie(
        testHomeMovies[2],
        progressFraction = 0.9f,
        progressDescription = "16 minutes remaining",
    ),
)

/** Poster-less like the movies: the TV glyph fallback needs no network. */
internal val testContinueEpisode = HomeContinueItem.Episode(
    episodeId = 900,
    showTitle = "Severance",
    episodeCode = "S1 E3",
    episodeName = "In Perpetuity",
    posterUrl = null,
    progressFraction = 0.18f,
    progressDescription = "42 minutes remaining",
)

/** The rail as the server would send it with an episode in progress: most recent first. */
internal val testContinueItems: List<HomeContinueItem> = listOf(testContinueEpisode) + testContinueMovies

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
internal fun rememberInertVideoPlayerViewModel(): VideoPlayerViewModel = remember {
    VideoPlayerViewModel(
        saveProgress = { _, _ -> ApiResult.Success(WatchProgressUpdateData(watched = false)) },
        onWatchedStateCommitted = {},
    )
}

internal val fakeVideoPlayerEngineFactory: (Context, VideoPlayRequest) -> VideoPlayerEngine =
    { _, _ -> FakeVideoPlayerEngine() }

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
    streamIndex = id,
    codec = "dts",
    bitRate = 0,
    channels = channels,
    channelLayout = SqlNullString(channelLayout, valid = true),
    language = SqlNullString(language, valid = true),
    isDefault = isDefault,
)

private fun testSubtitle(
    id: Long,
    codec: String,
    language: String,
) = Subtitle(
    id = id,
    streamIndex = id,
    codec = codec,
    language = SqlNullString(language, valid = true),
    title = null,
    isForced = false,
    isDefault = false,
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

/** Thumb-less musicians, enough to fill several rows at every `gridColumns` value. */
internal val testMusicians = (1L..30L).map { id ->
    MusicianCardUi(
        id = id,
        name = "Musician $id",
        thumbUrl = null,
        countsLine = "$id albums · ${id * 10} tracks",
        spoken = "Musician $id. $id albums, ${id * 10} tracks.",
    )
}

/** Cover-less albums for the Albums tab; ids offset so they never collide with the rail's. */
internal val testMusicAlbums = (1L..30L).map { id ->
    AlbumCardUi(id = 100 + id, title = "Album $id", subtitle = "Musician $id", coverUrl = null)
}

/**
 * Five library rows through the real mapping, in server order: a `#` bucket first, then an
 * A bucket with two rows (so the fold lands on exactly one), then B and C.
 */
internal val testLibraryTracks = listOf(
    testLibraryTrack(id = 901, title = "1999"),
    testLibraryTrack(id = 902, title = "Abbey Road"),
    testLibraryTrack(id = 903, title = "All You Need Is Love"),
    testLibraryTrack(id = 904, title = "Blackbird"),
    testLibraryTrack(id = 905, title = "Come Together", albumId = null, musicianId = null),
)

internal val testTrackEntries: List<TracksEntry> = tracksEntries(testLibraryTracks)

internal fun testLibraryTrack(
    id: Long,
    title: String,
    albumId: Long? = 11,
    musicianId: Long? = 4,
) = TrackListItem(
    id = id,
    title = title,
    duration = 125_000,
    albumId = SqlNullInt64(albumId ?: 0, valid = albumId != null),
    albumTitle = SqlNullString(if (albumId != null) "Help!" else "", valid = albumId != null),
    albumCover = SqlNullString("", valid = false),
    musicianId = SqlNullInt64(musicianId ?: 0, valid = musicianId != null),
    musicianName = SqlNullString(if (musicianId != null) "The Beatles" else "", valid = musicianId != null),
)

/** Every tab loaded with more to come, the Musicians tab selected. */
internal fun testMusicState(
    tab: MusicTab = MusicTab.Musicians,
    musicians: PagedState<MusicianCardUi> = PagedState(IglooRailState.Loaded(testMusicians), total = 60),
    albums: PagedState<AlbumCardUi> = PagedState(IglooRailState.Loaded(testMusicAlbums), total = 90),
    tracks: PagedState<TracksEntry> = PagedState(IglooRailState.Loaded(testTrackEntries), total = 5, append = AppendState.End),
    stats: MusicStats? = MusicStats(totalAlbums = 90, totalTracks = 5, totalMusicians = 60),
    refreshing: Boolean = false,
    notice: String? = null,
    shufflePending: Boolean = false,
) = MusicUiState(
    tab = tab,
    musicians = musicians,
    albums = albums,
    tracks = tracks,
    stats = stats,
    refreshing = refreshing,
    notice = notice,
    shufflePending = shufflePending,
)

internal val inertMusicActions = MusicActions(
    onRefresh = {},
    onRetryFirstPage = {},
    onRetryAppend = {},
    onLoadMore = {},
    onSelectTab = {},
    onPressTab = {},
    onPlayTrack = {},
    onPlayAll = {},
    onShuffleAll = {},
    onToggleLike = {},
)

/**
 * Thumb-less for the glyph fallback; two albums and three tracks so the chain has a rail and
 * rows to walk; the spoken strings are pinned literals of the mapping contract.
 */
internal fun testMusicianDetails(
    id: Long = 4,
    name: String = "The Beatles",
) = MusicianDetailsUi(
    id = id,
    name = name,
    thumbUrl = null,
    albumCountText = "2 albums",
    trackCountText = "3 tracks",
    totalDurationText = "7m 5s",
    genresLine = "Rock · Pop",
    popularity = 88,
    albums = listOf(
        AlbumCardUi(id = 11, title = "Help!", subtitle = "1965", coverUrl = null),
        AlbumCardUi(id = 12, title = "Revolver", subtitle = "1966", coverUrl = null),
    ),
    tracks = listOf(
        TrackRowUi(
            id = 951,
            title = "Yesterday",
            subtitle = "Help!",
            indexText = null,
            durationText = "2:05",
            durationSec = 125.0,
            albumId = 11,
            musicianId = null,
            spokenInfo = "Yesterday. Help!. 2 minutes and 5 seconds.",
        ),
        TrackRowUi(
            id = 952,
            title = "Taxman",
            subtitle = "Revolver",
            indexText = null,
            durationText = "2:39",
            durationSec = 159.0,
            albumId = 12,
            musicianId = null,
            spokenInfo = "Taxman. Revolver. 2 minutes and 39 seconds.",
        ),
        TrackRowUi(
            id = 953,
            title = "Untagged Demo",
            subtitle = null,
            indexText = null,
            durationText = "1:50",
            durationSec = 110.0,
            albumId = null,
            musicianId = null,
            spokenInfo = "Untagged Demo. 1 minute and 50 seconds.",
        ),
    ),
    facts = listOf(
        AlbumFactUi("Albums", "2"),
        AlbumFactUi("Tracks", "3"),
        AlbumFactUi("Total duration", "7m 5s"),
        AlbumFactUi("Genres", "Rock, Pop"),
        AlbumFactUi("Spotify popularity", "88 / 100"),
        AlbumFactUi("Spotify followers", "25,000,000"),
        AlbumFactUi("About", "Liverpool, 1960."),
    ),
    factsDescription = "Artist details. Albums: 2. Tracks: 3. Total duration: 7m 5s. " +
        "Genres: Rock, Pop. Spotify popularity: 88 / 100. Spotify followers: 25,000,000. " +
        "About: Liverpool, 1960.",
    heroInfoDescription = "The Beatles. 2 albums, 3 tracks. Total duration: 7 minutes and 5 seconds. " +
        "Genres: Rock, Pop. Spotify popularity 88 out of 100.",
)
