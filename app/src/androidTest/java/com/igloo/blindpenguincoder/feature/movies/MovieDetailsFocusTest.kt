package com.igloo.blindpenguincoder.feature.movies

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.AnimationScaleRule
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.data.model.AuthUser
import com.igloo.blindpenguincoder.feature.home.HomeHeroState
import com.igloo.blindpenguincoder.feature.home.HomeUiState
import com.igloo.blindpenguincoder.feature.home.IglooApp
import com.igloo.blindpenguincoder.feature.home.SignOutUiState
import com.igloo.blindpenguincoder.feature.home.findActivity
import com.igloo.blindpenguincoder.inertDetailsActions
import com.igloo.blindpenguincoder.testContinueMovies
import com.igloo.blindpenguincoder.testHero
import com.igloo.blindpenguincoder.testHomeMovies
import com.igloo.blindpenguincoder.testMovieDetails
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The details overlay's focus contract (design-system.md sections 6.3 and 11.4): opening it
 * from a card lands focus on Play, Back closes it and puts focus back on the card that led
 * away, and no d-pad direction escapes into the shell still composed underneath.
 */
@RunWith(AndroidJUnit4::class)
class MovieDetailsFocusTest {

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
    private val opened = mutableListOf<Long>()
    private var hostActivity: Activity? = null

    private fun setShellContent(
        initialDetails: MovieDetailsUiState = MovieDetailsUiState(),
        hero: HomeHeroState = HomeHeroState.Hidden,
    ) {
        detailsState = initialDetails
        opened.clear()
        composeRule.setContent {
            val context = LocalContext.current
            SideEffect { hostActivity = context.findActivity() }
            IglooTheme {
                IglooApp(
                    user = user,
                    serverOrigin = "http://igloo.test:8080",
                    signOut = SignOutUiState(),
                    // Hero hidden by default so the rails own the pane's entry anchor: this
                    // suite's subject is the round trip between a card and the overlay.
                    home = HomeUiState(
                        hero = hero,
                        continueWatching = IglooRailState.Loaded(testContinueMovies),
                        latestMovies = IglooRailState.Loaded(testHomeMovies),
                    ),
                    details = detailsState,
                    detailsActions = inertDetailsActions,
                    onRetryRail = {},
                    onMovieSelected = { movieId ->
                        opened += movieId
                        detailsState = MovieDetailsUiState(
                            openMovieId = movieId,
                            details = MovieDetailsState.Loaded(testMovieDetails(id = movieId)),
                        )
                    },
                    onCloseDetails = { detailsState = MovieDetailsUiState() },
                    onSwitchProfile = {},
                    onSignOut = {},
                    onSignOutConfirm = {},
                    onSignOutDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    // Back goes through the dispatcher BackHandler listens to; its fallback, when no handler
    // is enabled, is what finishes the activity.
    private fun pressBack() {
        composeRule.runOnUiThread {
            (checkNotNull(hostActivity) as ComponentActivity).onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
    }

    private fun loadedState(movie: MovieDetailsUi = testMovieDetails()) = MovieDetailsUiState(
        openMovieId = movie.id,
        details = MovieDetailsState.Loaded(movie),
    )

    @Test
    fun openingFromACardLandsFocusOnPlay() {
        setShellContent()

        composeRule.onNodeWithTag("continue_card_1")
            .performKeyInput { pressKey(Key.DirectionCenter) }

        assertEquals(listOf(1L), opened)
        composeRule.onNodeWithTag("details_play").assertIsFocused()
    }

    @Test
    fun backClosesTheOverlayAndRestoresTheOriginatingCard() {
        setShellContent()

        // A card that is neither the rail's first nor the pane's entry anchor: restoring the
        // anchor instead of the card that led away would still pass a weaker assertion.
        composeRule.onNodeWithTag("continue_card_1")
            .performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithTag("continue_card_2").assertIsFocused()
        composeRule.onNodeWithTag("continue_card_2")
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.onNodeWithTag("details_play").assertIsFocused()

        pressBack()

        composeRule.onNodeWithTag("movie_details").assertDoesNotExist()
        composeRule.onNodeWithTag("continue_card_2").assertIsFocused()
    }

    /**
     * The hero is its own call site with its own semantics, so it gets its own round trip: it
     * announces "Open …" only once something can be opened (section 11.3.1), and Back comes
     * home to it rather than to the first rail card.
     */
    @Test
    fun theHeroOpensDetailsAndBackReturnsToIt() {
        setShellContent(hero = HomeHeroState.Loaded(testHero))

        val hero = composeRule.onNodeWithTag("home_hero")
        hero.assertIsFocused().assertHasClickAction()
        hero.performKeyInput { pressKey(Key.DirectionCenter) }

        assertEquals(listOf(testHero.id), opened)
        composeRule.onNodeWithTag("details_play").assertIsFocused()

        pressBack()

        composeRule.onNodeWithTag("movie_details").assertDoesNotExist()
        hero.assertIsFocused()
    }

    @Test
    fun theSkeletonAnchorHandsFocusToPlayWhenTheMovieLoads() {
        setShellContent(MovieDetailsUiState(openMovieId = 1, details = MovieDetailsState.Loading))

        composeRule.onNodeWithTag("movie_details").assertExists()

        detailsState = loadedState()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("details_play").assertIsFocused()
    }

    /**
     * The loading and error states each have exactly one focusable, and the shell is composed
     * behind them: an unpinned edge would walk focus onto a card nobody can see, with the ring
     * vanishing and the remote driving invisible UI.
     */
    @Test
    fun theLoadingAnchorNeverEscapesIntoTheShell() {
        setShellContent(MovieDetailsUiState(openMovieId = 1, details = MovieDetailsState.Loading))

        val anchor = composeRule.onNodeWithContentDescription("Loading movie details")
        anchor.assertIsFocused()
        listOf(Key.DirectionLeft, Key.DirectionDown, Key.DirectionUp, Key.DirectionRight)
            .forEach { key ->
                anchor.performKeyInput { pressKey(key) }
                anchor.assertIsFocused()
            }
    }

    @Test
    fun theErrorRetryNeverEscapesIntoTheShell() {
        setShellContent(
            MovieDetailsUiState(
                openMovieId = 1,
                details = MovieDetailsState.Error("Could not reach the server."),
            ),
        )

        val retry = composeRule.onNodeWithContentDescription("Retry loading movie details")
        retry.assertIsFocused()
        listOf(Key.DirectionLeft, Key.DirectionDown, Key.DirectionUp, Key.DirectionRight)
            .forEach { key ->
                retry.performKeyInput { pressKey(key) }
                retry.assertIsFocused()
            }
    }

    @Test
    fun theActionRowNeverEscapesIntoTheShell() {
        setShellContent(loadedState())

        val play = composeRule.onNodeWithTag("details_play")
        play.assertIsFocused()
        // Left and up off the row are pinned: the rails underneath are still composed, and a
        // spatial search that found one would move focus to a card nobody can see.
        play.performKeyInput { pressKey(Key.DirectionLeft) }
        play.assertIsFocused()
        play.performKeyInput { pressKey(Key.DirectionUp) }
        play.assertIsFocused()

        // Right walks the row to its end, and stops there.
        play.performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithTag("details_watched").assertIsFocused()
        composeRule.onNodeWithTag("details_watched").performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithTag("details_like").assertIsFocused()
        composeRule.onNodeWithTag("details_like").performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithTag("details_more").assertIsFocused()
        composeRule.onNodeWithTag("details_more").performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithTag("details_more").assertIsFocused()
    }

    @Test
    fun downFromTheActionsWalksTheSectionsAndStops() {
        setShellContent(loadedState())

        val play = composeRule.onNodeWithTag("details_play")
        play.performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("cast_card_101").assertIsFocused()

        composeRule.onNodeWithTag("cast_card_101").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("details_about").assertIsFocused()

        // The last focusable on the screen: down stays put rather than falling through.
        composeRule.onNodeWithTag("details_about").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("details_about").assertIsFocused()

        // And back up the same chain.
        composeRule.onNodeWithTag("details_about").performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("cast_card_101").assertIsFocused()
        composeRule.onNodeWithTag("cast_card_101").performKeyInput { pressKey(Key.DirectionUp) }
        play.assertIsFocused()
    }

    @Test
    fun aMovieWithNothingBelowTheHeroPinsTheActionRow() {
        setShellContent(
            loadedState(
                testMovieDetails(cast = emptyList())
                    .copy(about = AboutUi(null, null, null, null)),
            ),
        )

        val play = composeRule.onNodeWithTag("details_play")
        play.assertIsFocused()
        play.performKeyInput { pressKey(Key.DirectionDown) }

        play.assertIsFocused()
    }

    @Test
    fun theShellLeavesTalkBackTraversalWhileTheOverlayIsOpen() {
        setShellContent()
        val shell = composeRule.onNodeWithTag("shell_content")
        shell.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.HideFromAccessibility))

        composeRule.onNodeWithTag("continue_card_1")
            .performKeyInput { pressKey(Key.DirectionCenter) }

        // Hidden from traversal, but still in the tree — so the assertion is meaningful.
        shell.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
    }
}
