package com.igloo.blindpenguincoder.feature.player

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.AnimationScaleRule
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.data.model.AuthUser
import com.igloo.blindpenguincoder.data.model.MovieWatchProgressUpdateData
import com.igloo.blindpenguincoder.data.model.PlaybackMode
import com.igloo.blindpenguincoder.feature.home.HomeHeroState
import com.igloo.blindpenguincoder.feature.home.HomeUiState
import com.igloo.blindpenguincoder.feature.home.IglooApp
import com.igloo.blindpenguincoder.feature.home.SignOutUiState
import com.igloo.blindpenguincoder.feature.home.findActivity
import com.igloo.blindpenguincoder.feature.movies.MovieDetailsState
import com.igloo.blindpenguincoder.feature.movies.MovieDetailsUiState
import com.igloo.blindpenguincoder.inertDetailsActions
import com.igloo.blindpenguincoder.playback.media3.FakeMoviePlayerEngine
import com.igloo.blindpenguincoder.playback.model.MoviePlayRequest
import com.igloo.blindpenguincoder.playback.model.MoviePlayerEvent
import com.igloo.blindpenguincoder.playback.youtube.FakeTrailerPlayerEngine
import com.igloo.blindpenguincoder.testContinueMovies
import com.igloo.blindpenguincoder.testMovieDetails
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The movie player in the details page's one player-overlay slot (design-system.md sections 6.3
 * and 11.8): the hero's Play opens it through the gated request, newer overlays supersede a
 * deferred Play, the details screen underneath leaves TalkBack traversal for its lifetime, and
 * closing restores focus to the control that led away.
 */
@RunWith(AndroidJUnit4::class)
class MoviePlayerOverlayFocusTest {

    @get:Rule(order = 0)
    val animationScale = AnimationScaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private val user = AuthUser(
        id = 1,
        name = "Jose",
        email = "jose@example.com",
        isAdmin = false,
        avatar = null,
        hasPin = false,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
    )

    private val playRequest = MoviePlayRequest(
        movieId = 1,
        title = "Heat",
        posterUrl = null,
        mimeType = "video/x-matroska",
        mode = PlaybackMode.Direct,
        audioTypeIndex = 0,
        subtitleTypeIndex = null,
        audioCodec = "dts",
        audioCodecProfile = null,
        audioChannels = 6,
        audioLabel = "English · 5.1 surround",
        resumeAtSec = null,
        durationSec = 7200.0,
    )

    private var detailsState by mutableStateOf(MovieDetailsUiState())
    private val engines = mutableListOf<FakeMoviePlayerEngine>()
    private val trailerEngines = mutableListOf<FakeTrailerPlayerEngine>()
    private lateinit var playRequests: MutableSharedFlow<MoviePlayRequest>
    private var failProgressSaves = false
    private var hostActivity: Activity? = null

    /** Null mimics the gate refusing: the message lands on the page and nothing opens. */
    private fun setShellContent(requestPlayback: () -> MoviePlayRequest?) {
        detailsState = MovieDetailsUiState(
            openMovieId = 1,
            details = MovieDetailsState.Loaded(testMovieDetails(id = 1)),
        )
        engines.clear()
        trailerEngines.clear()
        failProgressSaves = false
        playRequests = MutableSharedFlow(extraBufferCapacity = 1)
        composeRule.setContent {
            val context = LocalContext.current
            SideEffect { hostActivity = context.findActivity() }
            IglooTheme {
                val progressViewModel = remember {
                    MoviePlayerViewModel(
                        saveProgress = { _, _ ->
                            if (failProgressSaves) {
                                ApiResult.Failure(AppError.Network)
                            } else {
                                ApiResult.Success(MovieWatchProgressUpdateData(watched = false))
                            }
                        },
                        onWatchedStateCommitted = {},
                    )
                }
                IglooApp(
                    // Pinned: the Shield test device runs TalkBack, and this suite
                    // asserts the focus chain without the reading stops.
                    spokenAccessibilityEnabled = false,
                    user = user,
                    serverOrigin = "http://igloo.test:8080",
                    signOut = SignOutUiState(),
                    home = HomeUiState(
                        hero = HomeHeroState.Hidden,
                        continueWatching = IglooRailState.Loaded(testContinueMovies),
                    ),
                    details = detailsState,
                    detailsActions = inertDetailsActions,
                    onRequestPlayback = {
                        requestPlayback()?.let(playRequests::tryEmit)
                    },
                    playRequests = playRequests,
                    moviePlayerViewModel = progressViewModel,
                    moviePlayerEngineFactory = { _, _ ->
                        FakeMoviePlayerEngine().also { engines += it }
                    },
                    onRetryRail = {},
                    onMovieSelected = {},
                    onTheaterMovieSelected = {},
                    onCloseDetails = {
                        detailsState = detailsState.copy(
                            openMovieId = null,
                            details = MovieDetailsState.Loading,
                        )
                    },
                    onSwitchProfile = {},
                    onSignOut = {},
                    onSignOutConfirm = {},
                    onSignOutDismiss = {},
                    trailerEngineFactory = { _, _ ->
                        FakeTrailerPlayerEngine().also { trailerEngines += it }
                    },
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun pressBack() {
        composeRule.runOnUiThread {
            (checkNotNull(hostActivity) as ComponentActivity).onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
    }

    private fun pressPlay() {
        // Entry focus already anchors on the hero's Play button (section 11.4).
        val play = composeRule.onNodeWithTag("details_play")
        play.assertIsFocused()
        play.performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()
    }

    private fun emitDelayedPlayRequest() {
        composeRule.runOnIdle { playRequests.tryEmit(playRequest) }
        composeRule.waitForIdle()
    }

    private fun openPlaybackSettings() {
        composeRule.onNodeWithTag("details_more").requestFocus()
        composeRule.onNodeWithTag("details_more")
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.onNodeWithTag("more_menu_item_0")
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()
    }

    @Test
    fun pressingPlayOpensThePlayerOverlay() {
        setShellContent { playRequest }

        pressPlay()

        composeRule.onNodeWithTag("movie_player").assertExists()
        composeRule.onNodeWithTag("movie_play_pause").assertIsFocused()
        assertEquals(1, engines.size)
        assertEquals(listOf("start:null:true"), engines.single().playbackCommands)
    }

    @Test
    fun failedProgressSaveFollowsBackToDetailsAndRetryRestoresPlayFocus() {
        setShellContent { playRequest }
        pressPlay()
        val engine = engines.single()
        engine.emit(MoviePlayerEvent.IsPlayingChanged(true))
        failProgressSaves = true
        var position = 30.0
        while (position <= 46.0) {
            engine.emit(MoviePlayerEvent.Time(position, 7200.0))
            position += 0.5
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("movie_progress_error").assertExists()
        composeRule
            .onAllNodesWithText("Couldn't save playback progress:", substring = true)
            .filterToOne(hasAnyAncestor(hasTestTag("movie_progress_error")))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )

        pressBack()

        composeRule.onNodeWithTag("movie_player").assertDoesNotExist()
        composeRule.onNodeWithTag("details_progress_error").assertExists()
        val play = composeRule.onNodeWithTag("details_play")
        play.assertIsFocused()
        play.performKeyInput { pressKey(Key.DirectionDown) }
        val retry = composeRule.onNodeWithTag("details_progress_retry")
        retry.assertIsFocused()

        failProgressSaves = false
        retry.performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("details_progress_error").assertDoesNotExist()
        play.assertIsFocused()
    }

    @Test
    fun theDetailsOverlayLeavesTalkBackTraversalWhileThePlayerIsUp() {
        setShellContent { playRequest }
        val detailsLayer = composeRule.onNodeWithTag("details_layer")
        detailsLayer.assert(
            SemanticsMatcher.keyNotDefined(SemanticsProperties.HideFromAccessibility),
        )

        pressPlay()

        // Hidden from traversal, but still in the tree — so the assertion is meaningful.
        detailsLayer.assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility),
        )
    }

    @Test
    fun backClosesThePlayerAndRestoresFocusToThePlayButton() {
        setShellContent { playRequest }
        pressPlay()

        pressBack()

        // The player is gone and its engine torn down, the details overlay survived the Back,
        // and focus is back on the button that launched playback.
        composeRule.onNodeWithTag("movie_player").assertDoesNotExist()
        assertEquals(true, engines.single().released)
        composeRule.onNodeWithTag("movie_details").assertExists()
        composeRule.onNodeWithTag("details_play").assertIsFocused()
    }

    @Test
    fun blockedRequestShowsTheNoticeAndOpensNothing() {
        // The view model contract in miniature: a refusal publishes the message and returns null.
        setShellContent {
            detailsState = detailsState.copy(
                mutationNotice = "This TV can't play this movie's DTS audio track.",
            )
            null
        }

        pressPlay()

        composeRule.onNodeWithTag("movie_player").assertDoesNotExist()
        assertEquals(0, engines.size)
        composeRule.onNodeWithTag("details_mutation_notice").assertExists()
        composeRule
            .onNodeWithText("This TV can't play this movie's DTS audio track.")
            .assertExists()
        // Focus never left the page the answer landed on.
        composeRule.onNodeWithTag("details_play").assertIsFocused()
    }

    @Test
    fun delayedPlayCannotReplacePlaybackSettingsAndDismissRestoresMoreFocus() {
        setShellContent { null }
        pressPlay()
        openPlaybackSettings()

        emitDelayedPlayRequest()

        composeRule.onNodeWithTag("playback_settings_dialog").assertExists()
        composeRule.onNodeWithTag("movie_player").assertDoesNotExist()
        pressBack()
        composeRule.onNodeWithTag("playback_settings_dialog").assertDoesNotExist()
        composeRule.onNodeWithTag("details_more").assertIsFocused()
    }

    @Test
    fun trailerSupersedesDelayedPlayAndANewPlayStillOpensAndRestoresFocus() {
        var playPresses = 0
        setShellContent {
            playPresses += 1
            playRequest.takeIf { playPresses > 1 }
        }
        pressPlay()

        val secondExtra = composeRule.onNodeWithTag("extra_card_202")
        secondExtra.requestFocus()
        secondExtra.performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("trailer_player").assertExists()
        composeRule.onNodeWithTag("movie_player").assertDoesNotExist()

        pressBack()
        composeRule.onNodeWithTag("trailer_player").assertDoesNotExist()
        secondExtra.assertIsFocused()

        emitDelayedPlayRequest()
        composeRule.onNodeWithTag("movie_player").assertDoesNotExist()
        secondExtra.assertIsFocused()

        composeRule.onNodeWithTag("details_play").requestFocus()
        pressPlay()
        composeRule.onNodeWithTag("movie_player").assertExists()
        pressBack()
        composeRule.onNodeWithTag("movie_player").assertDoesNotExist()
        composeRule.onNodeWithTag("details_play").assertIsFocused()
    }
}
