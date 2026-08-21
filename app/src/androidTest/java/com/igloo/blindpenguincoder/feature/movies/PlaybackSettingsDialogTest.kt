package com.igloo.blindpenguincoder.feature.movies

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.AnimationScaleRule
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.data.model.AuthUser
import com.igloo.blindpenguincoder.data.model.PlaybackMode
import com.igloo.blindpenguincoder.fakeMoviePlayerEngineFactory
import com.igloo.blindpenguincoder.feature.home.HomeHeroState
import com.igloo.blindpenguincoder.feature.home.HomeUiState
import com.igloo.blindpenguincoder.feature.home.IglooApp
import com.igloo.blindpenguincoder.feature.home.SignOutUiState
import com.igloo.blindpenguincoder.feature.home.findActivity
import com.igloo.blindpenguincoder.rememberInertMoviePlayerViewModel
import com.igloo.blindpenguincoder.testContinueMovies
import com.igloo.blindpenguincoder.testHomeMovies
import com.igloo.blindpenguincoder.testMovieDetails
import com.igloo.blindpenguincoder.testPlaybackSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Playback Settings dialog's contract through the real host wiring (section 11.4.1): the
 * More menu's item opens it with focus on the selected mode row, OK selects without dismissing
 * and the explanation follows, image-based subtitle rows are selectable under Direct but inert
 * for every other mode, and Back or Done dismisses with focus restored to the More trigger —
 * never escaping the card while it is up.
 *
 * Assertions are semantics-based, not announcement-based, so a Shield with TalkBack on cannot
 * flake them.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackSettingsDialogTest {

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

    private var detailsState by mutableStateOf(MovieDetailsUiState())
    private var selection = PlaybackSelection()
    private var hostActivity: Activity? = null

    /** Selections repaint through the real mapping, exactly as the view model republishes. */
    private fun repaint() {
        detailsState = loadedState()
    }

    private fun loadedState() = MovieDetailsUiState(
        openMovieId = 1,
        details = MovieDetailsState.Loaded(
            testMovieDetails(playbackSettings = testPlaybackSettings(selection)),
        ),
    )

    private fun setShellContent() {
        selection = PlaybackSelection()
        detailsState = loadedState()
        composeRule.setContent {
            val context = LocalContext.current
            SideEffect { hostActivity = context.findActivity() }
            IglooTheme {
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
                        latestMovies = IglooRailState.Loaded(testHomeMovies),
                    ),
                    details = detailsState,
                    detailsActions = MovieDetailsActions.Library(
                        onToggleWatched = {},
                        onToggleLike = {},
                        onWatchTogether = {},
                        onTechnicalDetails = {},
                        onIdentifyMovie = {},
                        onDeleteMovie = {},
                        onSelectPlaybackMode = {
                            selection = selection.copy(mode = it)
                            repaint()
                        },
                        onSelectAudioTrack = {
                            selection = selection.copy(audioStreamId = it)
                            repaint()
                        },
                        onSelectSubtitle = {
                            selection = selection.copy(subtitleStreamId = it)
                            repaint()
                        },
                        onRetry = {},
                    ),
                    onRequestPlayback = { null },
                    moviePlayerViewModel = rememberInertMoviePlayerViewModel(),
                    moviePlayerEngineFactory = fakeMoviePlayerEngineFactory,
                    onRetryRail = {},
                    onMovieSelected = {},
                    onTheaterMovieSelected = null,
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
                )
            }
        }
        composeRule.waitForIdle()
    }

    /** More trigger → OK opens the menu → OK on its first item opens the dialog. */
    private fun openDialog() {
        composeRule.onNodeWithTag("details_more").requestFocus()
        composeRule.onNodeWithTag("details_more")
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.onNodeWithTag("more_menu_item_0").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()
    }

    /** Key events route through the focus system; the dialog node is just the injection root. */
    private fun press(key: Key, times: Int = 1) {
        composeRule.onNodeWithTag("playback_settings_dialog")
            .performKeyInput { repeat(times) { pressKey(key) } }
        composeRule.waitForIdle()
    }

    private fun pressBack() {
        composeRule.runOnUiThread {
            (checkNotNull(hostActivity) as ComponentActivity).onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
    }

    // Row order: 7 modes (Direct first, focused on entry), 2 audio tracks, None + 2 subtitles.

    @Test
    fun theMenuItemOpensTheDialogFocusedOnTheSelectedMode() {
        setShellContent()
        openDialog()

        composeRule.onNodeWithTag("more_menu").assertDoesNotExist()
        composeRule.onNodeWithTag("playback_settings_dialog").assertExists()
        composeRule.onNodeWithTag("playback_mode_direct").assertIsFocused().assertIsSelected()
        // The covered page leaves TalkBack traversal, same as it does under the menu.
        composeRule.onNodeWithTag("details_body")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
    }

    @Test
    fun selectingATranscodeRowMovesTheSelectionAndTheExplanation() {
        setShellContent()
        openDialog()

        press(Key.DirectionDown, times = 2)
        composeRule.onNodeWithTag("playback_mode_2160p_16mbps").assertIsFocused()
        press(Key.DirectionCenter)

        composeRule.onNodeWithTag("playback_mode_2160p_16mbps").assertIsFocused().assertIsSelected()
        composeRule
            .onNodeWithText(
                "Video is converted and streamed for smooth playback (4K — highest quality).",
                substring = true,
            )
            .assertExists()
    }

    @Test
    fun aNonFirstAudioTrackKeepsDirectPlay() {
        // ExoPlayer selects any embedded track itself, so no remux upgrade happens on the TV.
        setShellContent()
        openDialog()

        press(Key.DirectionDown, times = 8)
        composeRule.onNodeWithTag("audio_track_302").assertIsFocused()
        press(Key.DirectionCenter)

        composeRule.onNodeWithTag("audio_track_302").assertIsSelected()
        composeRule.onNodeWithTag("playback_mode_direct").assertIsSelected()
        composeRule
            .onNodeWithText("Direct play always uses the first audio track", substring = true)
            .assertDoesNotExist()
    }

    @Test
    fun anImageBasedSubtitleRowIsSelectableUnderDirectPlay() {
        setShellContent()
        openDialog()

        press(Key.DirectionDown, times = 11)
        composeRule.onNodeWithTag("subtitle_402").assertIsFocused()
        press(Key.DirectionCenter)

        composeRule.onNodeWithTag("subtitle_402").assertIsSelected()
    }

    @Test
    fun anImageBasedSubtitleRowIsFocusableButInertOutsideDirectPlay() {
        setShellContent()
        openDialog()

        // Move the mode to Remux first; the PGS row then loses its click action.
        press(Key.DirectionDown)
        composeRule.onNodeWithTag("playback_mode_remux").assertIsFocused()
        press(Key.DirectionCenter)

        press(Key.DirectionDown, times = 10)
        val row = composeRule.onNodeWithTag("subtitle_402")
        row.assertIsFocused().assertIsNotEnabled().assertHasNoClickAction()

        press(Key.DirectionCenter)

        // The selection did not move: subtitles stay off.
        composeRule.onNodeWithTag("subtitle_none").assertIsSelected()
    }

    @Test
    fun downPastTheLastRowLandsOnDoneAndOkDismissesToTheTrigger() {
        setShellContent()
        openDialog()

        press(Key.DirectionDown, times = 12)
        composeRule.onNodeWithTag("playback_settings_done").assertIsFocused()
        press(Key.DirectionCenter)

        composeRule.onNodeWithTag("playback_settings_dialog").assertDoesNotExist()
        composeRule.onNodeWithTag("movie_details").assertExists()
        composeRule.onNodeWithTag("details_more").assertIsFocused()
    }

    @Test
    fun backDismissesTheDialogAndRestoresTheTriggerWithoutClosingDetails() {
        setShellContent()
        openDialog()

        pressBack()

        composeRule.onNodeWithTag("playback_settings_dialog").assertDoesNotExist()
        composeRule.onNodeWithTag("movie_details").assertExists()
        composeRule.onNodeWithTag("details_more").assertIsFocused()
    }

    @Test
    fun horizontalPressesCannotEscapeTheCard() {
        setShellContent()
        openDialog()

        press(Key.DirectionLeft)
        composeRule.onNodeWithTag("playback_mode_direct").assertIsFocused()
        press(Key.DirectionRight)
        composeRule.onNodeWithTag("playback_mode_direct").assertIsFocused()
        press(Key.DirectionUp)
        composeRule.onNodeWithTag("playback_mode_direct").assertIsFocused()
    }

    @Test
    fun aSelectionSurvivesReopeningTheDialogWithinTheSession() {
        setShellContent()
        openDialog()

        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        composeRule.onNodeWithTag("playback_mode_remux").assertIsSelected()
        pressBack()

        openDialog()

        // Session-only lives in the (simulated) view model, so the dialog reopens on the kept
        // choice — and entry focus follows the selection, not the first row.
        composeRule.onNodeWithTag("playback_mode_remux").assertIsFocused().assertIsSelected()
    }

    @Test
    fun theExplanationIsAPoliteLiveRegion() {
        setShellContent()
        openDialog()

        composeRule.onNodeWithTag("playback_settings_explanation")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
    }
}
