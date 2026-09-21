package com.igloo.blindpenguincoder

import android.content.Context
import androidx.compose.runtime.Composable
import com.igloo.blindpenguincoder.data.model.AuthUser
import com.igloo.blindpenguincoder.feature.home.HomeRail
import com.igloo.blindpenguincoder.feature.home.HomeUiState
import com.igloo.blindpenguincoder.feature.home.IglooApp
import com.igloo.blindpenguincoder.feature.home.SignOutUiState
import com.igloo.blindpenguincoder.feature.movies.MovieDetailsActions
import com.igloo.blindpenguincoder.feature.movies.MovieDetailsUiState
import com.igloo.blindpenguincoder.feature.movies.MoviesActions
import com.igloo.blindpenguincoder.feature.movies.MoviesUiState
import com.igloo.blindpenguincoder.feature.music.AlbumDetailsUiState
import com.igloo.blindpenguincoder.feature.music.MusicActions
import com.igloo.blindpenguincoder.feature.music.MusicUiState
import com.igloo.blindpenguincoder.feature.music.MusicianDetailsUiState
import com.igloo.blindpenguincoder.feature.music.TrackLikesUiState
import com.igloo.blindpenguincoder.feature.player.MoviePlayerViewModel
import com.igloo.blindpenguincoder.playback.media3.MoviePlayerEngine
import com.igloo.blindpenguincoder.playback.media3.MusicPlayerEngine
import com.igloo.blindpenguincoder.playback.model.MoviePlayRequest
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
    email = "jose@example.com",
    isAdmin = false,
    avatar = null,
    hasPin = false,
    createdAt = "2026-01-01T00:00:00Z",
    updatedAt = "2026-01-01T00:00:00Z",
)

internal const val TEST_SERVER_ORIGIN = "http://igloo.test:8080"

/**
 * [IglooApp] with every wiring a test does not care about defaulted to an inert fixture, so a
 * suite spells only the states and spies it asserts. Callers own the [IglooTheme] wrapper —
 * several read theme values or vary the scale, and a theme inside this function would hide that.
 *
 * [spokenAccessibilityEnabled] is pinned off: the Shield test device runs TalkBack, and the
 * suites assert focus chains without the reading stops. A suite that asserts the reading stops
 * turns it back on explicitly.
 */
@Composable
internal fun TestIglooApp(
    user: AuthUser = testAuthUser,
    serverOrigin: String = TEST_SERVER_ORIGIN,
    signOut: SignOutUiState = SignOutUiState(),
    home: HomeUiState = HomeUiState(),
    movies: MoviesUiState = testMoviesState(),
    moviesActions: MoviesActions = inertMoviesActions,
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
    moviePlayerViewModel: MoviePlayerViewModel = rememberInertMoviePlayerViewModel(),
    moviePlayerEngineFactory: (Context, MoviePlayRequest) -> MoviePlayerEngine =
        fakeMoviePlayerEngineFactory,
    musicPlayerEngineFactory: (Context, MusicPlayRequest) -> MusicPlayerEngine =
        fakeMusicPlayerEngineFactory,
    musicQueueFetcher: MusicQueueFetcher = InertMusicQueueFetcher,
    playRequests: Flow<MoviePlayRequest> = emptyFlow(),
    musicPlayRequests: Flow<MusicPlayRequest> = emptyFlow(),
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
        moviePlayerViewModel = moviePlayerViewModel,
        moviePlayerEngineFactory = moviePlayerEngineFactory,
        musicPlayerEngineFactory = musicPlayerEngineFactory,
        musicQueueFetcher = musicQueueFetcher,
        playRequests = playRequests,
        musicPlayRequests = musicPlayRequests,
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
