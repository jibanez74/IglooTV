package com.igloo.blindpenguincoder

import android.content.Context
import androidx.compose.runtime.Composable
import com.igloo.blindpenguincoder.data.model.AuthUser
import com.igloo.blindpenguincoder.feature.home.HomeRail
import com.igloo.blindpenguincoder.feature.home.HomeUiState
import com.igloo.blindpenguincoder.feature.home.IglooApp
import com.igloo.blindpenguincoder.feature.home.SignOutUiState
import com.igloo.blindpenguincoder.feature.library.LibraryActions
import com.igloo.blindpenguincoder.feature.library.LibraryKind
import com.igloo.blindpenguincoder.feature.library.LibraryUiState
import com.igloo.blindpenguincoder.feature.movies.MovieDetailsActions
import com.igloo.blindpenguincoder.feature.movies.MovieDetailsUiState
import com.igloo.blindpenguincoder.feature.music.AlbumDetailsUiState
import com.igloo.blindpenguincoder.feature.music.MusicActions
import com.igloo.blindpenguincoder.feature.music.MusicUiState
import com.igloo.blindpenguincoder.feature.music.MusicianDetailsUiState
import com.igloo.blindpenguincoder.feature.music.TrackLikesUiState
import com.igloo.blindpenguincoder.feature.player.VideoPlayerViewModel
import com.igloo.blindpenguincoder.playback.media3.VideoPlayerEngine
import com.igloo.blindpenguincoder.playback.media3.MusicPlayerEngine
import com.igloo.blindpenguincoder.playback.model.VideoPlayRequest
import com.igloo.blindpenguincoder.playback.model.MusicPlayRequest
import com.igloo.blindpenguincoder.playback.queue.InertMusicQueueFetcher
import com.igloo.blindpenguincoder.data.repository.MusicQueueFetcher
import com.igloo.blindpenguincoder.playback.youtube.TrailerPlayerEngine
import com.igloo.blindpenguincoder.playback.youtube.youTubeIFrameEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** The one signed-in user every shell-level suite hosts. */
internal val testAuthUser = AuthUser(
    id = 1,
    name = "Jose",
    isAdmin = false,
    avatar = null,
    hasPin = false,
)

internal const val TEST_SERVER_ORIGIN = "http://igloo.test:8080"

/**
 * [IglooApp] with every wiring a test does not care about defaulted to an inert fixture, so a
 * suite spells only the states and spies it asserts. Callers own the [IglooTheme] wrapper —
 * several read theme values or vary the scale, and a theme inside this function would hide that.
 *
 * [spokenAccessibilityEnabled] is pinned off so the suites assert focus chains without the
 * reading stops; a suite that asserts the reading stops turns it back on explicitly. It is a
 * parameter rather than a device read because an instrumented run never observes a screen
 * reader — `UiAutomation` suppresses TalkBack for as long as it is connected.
 */
@Composable
internal fun TestIglooApp(
    user: AuthUser = testAuthUser,
    serverOrigin: String = TEST_SERVER_ORIGIN,
    signOut: SignOutUiState = SignOutUiState(),
    home: HomeUiState = HomeUiState(),
    movies: LibraryUiState = testLibraryState(),
    moviesActions: LibraryActions = inertLibraryActions,
    shows: LibraryUiState = testLibraryState(LibraryKind.Shows),
    showsActions: LibraryActions = inertLibraryActions,
    music: MusicUiState = testMusicState(),
    musicActions: MusicActions = inertMusicActions,
    details: MovieDetailsUiState = MovieDetailsUiState(),
    detailsActions: MovieDetailsActions = inertDetailsActions,
    albumDetails: AlbumDetailsUiState = AlbumDetailsUiState(),
    onRetryAlbumDetails: () -> Unit = {},
    onAlbumSelected: ((Long) -> Unit)? = null,
    musicianDetails: MusicianDetailsUiState = MusicianDetailsUiState(),
    onRetryMusicianDetails: () -> Unit = {},
    onMusicianSelected: ((Long) -> Unit)? = null,
    // Seeded and empty: rows are live but nothing is liked, so a suite asserts the resting
    // state unless it says otherwise.
    trackLikes: TrackLikesUiState = TrackLikesUiState(likedIds = emptySet()),
    onToggleTrackLike: (Long) -> Unit = {},
    onRequestPlayback: () -> Unit = {},
    videoPlayerViewModel: VideoPlayerViewModel = rememberInertVideoPlayerViewModel(),
    videoPlayerEngineFactory: (Context, VideoPlayRequest) -> VideoPlayerEngine =
        fakeVideoPlayerEngineFactory,
    musicPlayerEngineFactory: (Context, MusicPlayRequest) -> MusicPlayerEngine =
        fakeMusicPlayerEngineFactory,
    musicQueueFetcher: MusicQueueFetcher = InertMusicQueueFetcher,
    playRequests: Flow<VideoPlayRequest> = emptyFlow(),
    musicPlayRequests: Flow<MusicPlayRequest> = emptyFlow(),
    homePlayRequests: Flow<VideoPlayRequest> = emptyFlow(),
    onResumeEpisode: (Long) -> Unit = {},
    onRetryRail: (HomeRail) -> Unit = {},
    onMovieSelected: ((Long) -> Unit)? = null,
    onTheaterMovieSelected: ((Long) -> Unit)? = null,
    onCloseDetails: () -> Unit = {},
    onSwitchProfile: () -> Unit = {},
    onSignOut: () -> Unit = {},
    onSignOutConfirm: () -> Unit = {},
    onSignOutDismiss: () -> Unit = {},
    trailerEngineFactory: (Context, String) -> TrailerPlayerEngine = { context, key ->
        youTubeIFrameEngine(context, key, serverOrigin)
    },
    spokenAccessibilityEnabled: Boolean = false,
) {
    IglooApp(
        user = user,
        serverOrigin = serverOrigin,
        signOut = signOut,
        home = home,
        movies = movies,
        moviesActions = moviesActions,
        shows = shows,
        showsActions = showsActions,
        music = music,
        musicActions = musicActions,
        details = details,
        detailsActions = detailsActions,
        albumDetails = albumDetails,
        onRetryAlbumDetails = onRetryAlbumDetails,
        onAlbumSelected = onAlbumSelected,
        musicianDetails = musicianDetails,
        onRetryMusicianDetails = onRetryMusicianDetails,
        onMusicianSelected = onMusicianSelected,
        trackLikes = trackLikes,
        onToggleTrackLike = onToggleTrackLike,
        onRequestPlayback = onRequestPlayback,
        videoPlayerViewModel = videoPlayerViewModel,
        videoPlayerEngineFactory = videoPlayerEngineFactory,
        musicPlayerEngineFactory = musicPlayerEngineFactory,
        musicQueueFetcher = musicQueueFetcher,
        playRequests = playRequests,
        musicPlayRequests = musicPlayRequests,
        homePlayRequests = homePlayRequests,
        onResumeEpisode = onResumeEpisode,
        onRetryRail = onRetryRail,
        onMovieSelected = onMovieSelected,
        onTheaterMovieSelected = onTheaterMovieSelected,
        onCloseDetails = onCloseDetails,
        onSwitchProfile = onSwitchProfile,
        onSignOut = onSignOut,
        onSignOutConfirm = onSignOutConfirm,
        onSignOutDismiss = onSignOutDismiss,
        trailerEngineFactory = trailerEngineFactory,
        spokenAccessibilityEnabled = spokenAccessibilityEnabled,
    )
}
