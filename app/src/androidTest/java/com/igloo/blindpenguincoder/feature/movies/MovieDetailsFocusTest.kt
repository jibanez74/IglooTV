package com.igloo.blindpenguincoder.feature.movies

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.AnimationScaleRule
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.data.model.AuthUser
import com.igloo.blindpenguincoder.fakeMoviePlayerEngineFactory
import com.igloo.blindpenguincoder.feature.home.HomeHeroState
import com.igloo.blindpenguincoder.feature.home.HomeUiState
import com.igloo.blindpenguincoder.feature.home.IglooApp
import com.igloo.blindpenguincoder.feature.home.SignOutUiState
import com.igloo.blindpenguincoder.feature.home.findActivity
import com.igloo.blindpenguincoder.inertDetailsActions
import com.igloo.blindpenguincoder.rememberInertMoviePlayerViewModel
import com.igloo.blindpenguincoder.testContinueMovies
import com.igloo.blindpenguincoder.testHero
import com.igloo.blindpenguincoder.testHomeMovies
import com.igloo.blindpenguincoder.testMovieDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    private val subMinuteProgress = ProgressUi(
        fraction = 0.99f,
        remainingTimeLabel = "Less than 1m left",
        resumeStateDescription = "Resume from 1 hour, 59 minutes, and 31 seconds; " +
            "Less than 1 minute remaining",
    )

    private var detailsState by mutableStateOf(MovieDetailsUiState())
    private val opened = mutableListOf<Long>()
    private var hostActivity: Activity? = null

    private fun setShellContent(
        initialDetails: MovieDetailsUiState = MovieDetailsUiState(),
        hero: HomeHeroState = HomeHeroState.Hidden,
        user: AuthUser = this.user,
        // Explicit, never the ambient default: the Shield test device runs TalkBack, and this
        // suite pins the chain both with and without the reading stops.
        spokenAccessibilityEnabled: Boolean = false,
    ) {
        detailsState = initialDetails
        opened.clear()
        composeRule.setContent {
            val context = LocalContext.current
            SideEffect { hostActivity = context.findActivity() }
            IglooTheme {
                IglooApp(
                    spokenAccessibilityEnabled = spokenAccessibilityEnabled,
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
                    onRequestPlayback = { null },
                    moviePlayerViewModel = rememberInertMoviePlayerViewModel(),
                    moviePlayerEngineFactory = fakeMoviePlayerEngineFactory,
                    onRetryRail = {},
                    onMovieSelected = { movieId ->
                        opened += movieId
                        detailsState = MovieDetailsUiState(
                            openMovieId = movieId,
                            details = MovieDetailsState.Loaded(testMovieDetails(id = movieId)),
                        )
                    },
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

    @Test
    fun theShellDoesNotAlsoRenderTheNoticeWhileTheOverlayIsOpen() {
        setShellContent(
            loadedState().copy(
                mutationNotice = "Couldn't update watched status: backend refused it",
            ),
        )

        composeRule.onNodeWithTag("details_mutation_notice").assertExists()
        composeRule.onNodeWithTag("shell_mutation_notice").assertDoesNotExist()
    }

    @Test
    fun backCarriesAMutationFailureNoticeToHomeWithoutTakingFocus() {
        setShellContent(
            loadedState().copy(
                mutationNotice = "Couldn't update watched status: backend refused it",
            ),
        )

        pressBack()

        composeRule.onNodeWithTag("movie_details").assertDoesNotExist()
        composeRule.onNodeWithTag("shell_mutation_notice")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
            .assertHasNoClickAction()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Focused))
        composeRule.onNodeWithTag("continue_card_1").assertIsFocused()
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
    fun rightFromWatchedSkipsAnUnknownLikeToMore() {
        setShellContent(loadedState(testMovieDetails(liked = null)))

        val play = composeRule.onNodeWithTag("details_play")
        play.performKeyInput { pressKey(Key.DirectionRight) }
        val watched = composeRule.onNodeWithTag("details_watched")
        watched.assertIsFocused()

        // A Like with no status yet is disabled, so it is not focusable and a rightward search
        // would have to guess past the hole. Watched's hand-wired right skips straight to More,
        // the always-present control that pins the row's right edge.
        watched.performKeyInput { pressKey(Key.DirectionRight) }
        val more = composeRule.onNodeWithTag("details_more")
        more.assertIsFocused()
        more.performKeyInput { pressKey(Key.DirectionRight) }
        more.assertIsFocused()
        composeRule.onNodeWithTag("continue_card_1").assertIsNotFocused()
    }

    @Test
    fun downFromTheActionsWalksTheSectionsAndStops() {
        setShellContent(loadedState())

        val play = composeRule.onNodeWithTag("details_play")
        play.performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("cast_card_101").assertIsFocused()

        composeRule.onNodeWithTag("cast_card_101").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("extra_card_201").assertIsFocused()

        composeRule.onNodeWithTag("extra_card_201").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("details_about").assertIsFocused()

        // The last focusable on the screen: down stays put rather than falling through.
        composeRule.onNodeWithTag("details_about").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("details_about").assertIsFocused()

        // And back up the same chain.
        composeRule.onNodeWithTag("details_about").performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("extra_card_201").assertIsFocused()
        composeRule.onNodeWithTag("extra_card_201").performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("cast_card_101").assertIsFocused()
        composeRule.onNodeWithTag("cast_card_101").performKeyInput { pressKey(Key.DirectionUp) }
        play.assertIsFocused()
    }

    /**
     * With a spoken screen reader running the vertical chain gains the reading stops (TV
     * TalkBack follows input focus and never reaches plain text): up from the action row climbs
     * to the hero prose, and down walks Overview and Key Crew before the cast rail.
     */
    @Test
    fun theReadingStopsJoinTheChainWhileAScreenReaderRuns() {
        setShellContent(loadedState(), spokenAccessibilityEnabled = true)

        val play = composeRule.onNodeWithTag("details_play")
        play.assertIsFocused()

        // Up from the action row reads the hero prose; up from there pins.
        play.performKeyInput { pressKey(Key.DirectionUp) }
        val heroInfo = composeRule.onNodeWithTag("details_hero_info")
        heroInfo.assertIsFocused()
        heroInfo.performKeyInput { pressKey(Key.DirectionUp) }
        heroInfo.assertIsFocused()

        // Down returns to the primary action, then the chain walks every section in order.
        heroInfo.performKeyInput { pressKey(Key.DirectionDown) }
        play.assertIsFocused()
        play.performKeyInput { pressKey(Key.DirectionDown) }
        val overview = composeRule.onNodeWithTag("details_overview_stop")
        overview.assertIsFocused()
        overview.performKeyInput { pressKey(Key.DirectionDown) }
        val keyCrew = composeRule.onNodeWithTag("details_key_crew_stop")
        keyCrew.assertIsFocused()
        keyCrew.performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("cast_card_101").assertIsFocused()

        // And back up the same chain.
        composeRule.onNodeWithTag("cast_card_101").performKeyInput { pressKey(Key.DirectionUp) }
        keyCrew.assertIsFocused()
        keyCrew.performKeyInput { pressKey(Key.DirectionUp) }
        overview.assertIsFocused()
        overview.performKeyInput { pressKey(Key.DirectionUp) }
        play.assertIsFocused()
    }

    /** The stops keep the row's focus memory: up from the overview returns to the action left. */
    @Test
    fun upFromTheOverviewStopReturnsToTheLastFocusedAction() {
        setShellContent(loadedState(), spokenAccessibilityEnabled = true)

        composeRule.onNodeWithTag("details_more").requestFocus()
        composeRule.onNodeWithTag("details_more").performKeyInput { pressKey(Key.DirectionDown) }
        val overview = composeRule.onNodeWithTag("details_overview_stop")
        overview.assertIsFocused()

        overview.performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("details_more").assertIsFocused()
    }

    /** A reading stop's horizontal edges pin: the shell is still composed underneath. */
    @Test
    fun theReadingStopsPinTheirHorizontalEdges() {
        setShellContent(loadedState(), spokenAccessibilityEnabled = true)

        val overview = composeRule.onNodeWithTag("details_overview_stop")
        overview.requestFocus()
        overview.assertIsFocused()
        overview.performKeyInput { pressKey(Key.DirectionLeft) }
        overview.assertIsFocused()
        overview.performKeyInput { pressKey(Key.DirectionRight) }
        overview.assertIsFocused()
    }

    /**
     * Up from the extras lands on the cast card the user left, not the rail's first card — the
     * cast rail's entry requester rides its focus memory. Pinned on the second card because a
     * regression to first-card would still pass the straight down-up walk above.
     */
    @Test
    fun upFromTheExtrasReturnsToTheLastFocusedCastCard() {
        setShellContent(loadedState())

        composeRule.onNodeWithTag("cast_card_102").requestFocus()
        composeRule.onNodeWithTag("cast_card_102").assertIsFocused()

        composeRule.onNodeWithTag("cast_card_102").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("extra_card_201").assertIsFocused()

        composeRule.onNodeWithTag("extra_card_201").performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("cast_card_102").assertIsFocused()
    }

    /** A movie with no YouTube extras chains the cast straight to About (the fallback wiring). */
    @Test
    fun aMovieWithoutExtrasStillChainsCastToAbout() {
        setShellContent(loadedState(testMovieDetails(extraVideos = emptyList())))

        composeRule.onNodeWithTag("cast_card_101").requestFocus()
        composeRule.onNodeWithTag("cast_card_101").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("details_about").assertIsFocused()

        composeRule.onNodeWithTag("details_about").performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("cast_card_101").assertIsFocused()
    }

    /**
     * The remaining shape of the cast rail's down wiring: with no extras and an empty About it
     * is the page's last section, so its down edge pins rather than falling into the shell.
     */
    @Test
    fun aMovieWhereCastIsTheLastSectionPinsItsDownEdge() {
        setShellContent(
            loadedState(
                testMovieDetails(extraVideos = emptyList())
                    .copy(about = AboutUi(null, null, null, null)),
            ),
        )

        composeRule.onNodeWithTag("cast_card_101").requestFocus()
        composeRule.onNodeWithTag("cast_card_101").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("cast_card_101").assertIsFocused()
    }

    @Test
    fun aMovieWithNothingBelowTheHeroPinsTheActionRow() {
        setShellContent(
            loadedState(
                testMovieDetails(cast = emptyList(), extraVideos = emptyList())
                    .copy(about = AboutUi(null, null, null, null)),
            ),
        )

        val play = composeRule.onNodeWithTag("details_play")
        play.assertIsFocused()
        play.performKeyInput { pressKey(Key.DirectionDown) }

        play.assertIsFocused()
    }

    @Test
    fun playIsTheOnlyFocusedControlOnceTheRowIsWalked() {
        // Play's fill recedes while a sibling holds focus, so it stops out-shouting the ring on
        // whatever is actually focused. The fill itself is pinned by RecessedPrimaryTest; what
        // matters here is that the row never reports two focused controls, which is the state
        // the recession keys off.
        setShellContent(loadedState())

        val play = composeRule.onNodeWithTag("details_play")
        play.assertIsFocused()

        listOf("details_watched", "details_like", "details_more").forEach { tag ->
            composeRule.onNodeWithTag(tag).requestFocus()
            composeRule.onNodeWithTag(tag).assertIsFocused()
            play.assertIsNotFocused()
        }

        play.requestFocus()
        play.assertIsFocused()
        composeRule.onNodeWithTag("details_watched").assertIsNotFocused()
    }

    /**
     * The toggles reserve their wider label, so flipping one — by the user's own press or by
     * the status request landing after first paint — repaints the button instead of shoving
     * everything to its right while the eye is committed to one spot.
     */
    @Test
    fun togglingWatchedDoesNotMoveItsSiblings() {
        setShellContent(loadedState())

        val watchedBefore = composeRule.onNodeWithTag("details_watched").getUnclippedBoundsInRoot()
        val likeBefore = composeRule.onNodeWithTag("details_like").getUnclippedBoundsInRoot()

        detailsState = loadedState(testMovieDetails(watched = true, liked = true))
        composeRule.waitForIdle()

        val watchedAfter = composeRule.onNodeWithTag("details_watched").getUnclippedBoundsInRoot()
        val likeAfter = composeRule.onNodeWithTag("details_like").getUnclippedBoundsInRoot()
        assertEquals(watchedBefore.left.value, watchedAfter.left.value, 0.5f)
        assertEquals(watchedBefore.right.value, watchedAfter.right.value, 0.5f)
        assertEquals(likeBefore.left.value, likeAfter.left.value, 0.5f)
        assertEquals(likeBefore.right.value, likeAfter.right.value, 0.5f)
    }

    /**
     * The resume slot is reserved from first paint: the progress request lands after the hero
     * is on screen, and marking a movie watched removes the strip — either would reflow the
     * bottom-anchored hero under the user's eye if the slot came and went with the strip.
     */
    @Test
    fun theHeroDoesNotMoveWhenTheResumeStripArrivesOrLeaves() {
        setShellContent(loadedState(testMovieDetails(progress = null)))

        composeRule.onNodeWithTag("details_resume_track").assertDoesNotExist()
        val before = composeRule.onNodeWithTag("details_play").getUnclippedBoundsInRoot()

        detailsState = loadedState(testMovieDetails(progress = subMinuteProgress))
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("details_resume_track").assertExists()
        val with = composeRule.onNodeWithTag("details_play").getUnclippedBoundsInRoot()
        assertEquals(before.top.value, with.top.value, 0.5f)
        assertEquals(before.left.value, with.left.value, 0.5f)
        assertEquals(before.right.value, with.right.value, 0.5f)

        detailsState = loadedState(testMovieDetails(watched = true, progress = null))
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("details_resume_track").assertDoesNotExist()
        val without = composeRule.onNodeWithTag("details_play").getUnclippedBoundsInRoot()
        assertEquals(before.top.value, without.top.value, 0.5f)
        assertEquals(before.left.value, without.left.value, 0.5f)
        assertEquals(before.right.value, without.right.value, 0.5f)
    }

    /**
     * Up from the cast returns to the action the user left, not unconditionally to Play — the
     * same focus memory the rail keeps for its own cards. Pinned on Like because a regression
     * to always-Play would still pass a Play→down→up→Play walk.
     */
    @Test
    fun upFromTheCastReturnsToTheLastFocusedAction() {
        setShellContent(loadedState())

        composeRule.onNodeWithTag("details_like").requestFocus()
        composeRule.onNodeWithTag("details_like").assertIsFocused()

        composeRule.onNodeWithTag("details_like").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("cast_card_101").assertIsFocused()

        composeRule.onNodeWithTag("cast_card_101").performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("details_like").assertIsFocused()

        // The memory covers the whole row, More included.
        composeRule.onNodeWithTag("details_more").requestFocus()
        composeRule.onNodeWithTag("details_more").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("cast_card_101").assertIsFocused()
        composeRule.onNodeWithTag("cast_card_101").performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("details_more").assertIsFocused()
    }

    @Test
    fun pressingMoreOpensTheMenuOnItsFirstItem() {
        setShellContent(loadedState())

        composeRule.onNodeWithTag("details_more").requestFocus()
        composeRule.onNodeWithTag("details_more").performKeyInput { pressKey(Key.DirectionCenter) }

        composeRule.onNodeWithTag("more_menu").assertExists()
        composeRule.onNodeWithTag("more_menu_item_0").assertIsFocused()
    }

    /**
     * The screen-reader path composes a different screen — reading stops sit between the hero and
     * the sections, and the covered body leaves the semantics tree entirely while the menu is up.
     * The trigger, the entry row, the trap and the restore must be the same either way.
     */
    @Test
    fun pressingMoreOpensTheMenuOnItsFirstItemWithAScreenReaderRunning() {
        setShellContent(loadedState(), spokenAccessibilityEnabled = true)

        composeRule.onNodeWithTag("details_more").requestFocus()
        composeRule.onNodeWithTag("details_more").performKeyInput { pressKey(Key.DirectionCenter) }

        composeRule.onNodeWithTag("more_menu").assertExists()
        val first = composeRule.onNodeWithTag("more_menu_item_0")
        first.assertIsFocused()

        first.performKeyInput { pressKey(Key.DirectionUp) }
        first.assertIsFocused()
        first.performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("more_menu_item_1").assertIsFocused()

        pressBack()

        composeRule.onNodeWithTag("more_menu").assertDoesNotExist()
        composeRule.onNodeWithTag("details_more").assertIsFocused()
    }

    @Test
    fun theMenuTrapsEveryDpadDirection() {
        setShellContent(loadedState())
        composeRule.onNodeWithTag("details_more").requestFocus()
        composeRule.onNodeWithTag("details_more").performKeyInput { pressKey(Key.DirectionCenter) }

        // Up from the first row and the horizontals on an interior row are all pinned: the
        // whole details screen is still composed under the menu.
        val first = composeRule.onNodeWithTag("more_menu_item_0")
        first.assertIsFocused()
        first.performKeyInput { pressKey(Key.DirectionUp) }
        first.assertIsFocused()

        first.performKeyInput { pressKey(Key.DirectionDown) }
        val second = composeRule.onNodeWithTag("more_menu_item_1")
        second.assertIsFocused()
        second.performKeyInput { pressKey(Key.DirectionLeft) }
        second.assertIsFocused()
        second.performKeyInput { pressKey(Key.DirectionRight) }
        second.assertIsFocused()

        // Down from the last row stays put rather than falling through to the screen below.
        second.performKeyInput { pressKey(Key.DirectionDown) }
        val last = composeRule.onNodeWithTag("more_menu_item_2")
        last.assertIsFocused()
        last.performKeyInput { pressKey(Key.DirectionDown) }
        last.assertIsFocused()
    }

    @Test
    fun backClosesTheMenuAndRestoresFocusToMore() {
        setShellContent(loadedState())
        composeRule.onNodeWithTag("details_more").requestFocus()
        composeRule.onNodeWithTag("details_more").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.onNodeWithTag("more_menu_item_0").assertIsFocused()

        pressBack()

        composeRule.onNodeWithTag("more_menu").assertDoesNotExist()
        composeRule.onNodeWithTag("details_more").assertIsFocused()
    }

    @Test
    fun backWithTheMenuOpenDoesNotCloseTheDetailsOverlay() {
        setShellContent(loadedState())
        composeRule.onNodeWithTag("details_more").requestFocus()
        composeRule.onNodeWithTag("details_more").performKeyInput { pressKey(Key.DirectionCenter) }
        // Synchronizes on the open menu: a Back dispatched before the composition applies would
        // still find the host's details handler enabled.
        composeRule.onNodeWithTag("more_menu_item_0").assertIsFocused()

        pressBack()

        // One Back spends itself on the menu; the overlay under it is untouched.
        composeRule.onNodeWithTag("movie_details").assertExists()

        pressBack()

        composeRule.onNodeWithTag("movie_details").assertDoesNotExist()
    }

    @Test
    fun selectingAStubItemClosesTheMenuAndRestoresFocusToMore() {
        setShellContent(loadedState())
        composeRule.onNodeWithTag("details_more").requestFocus()
        composeRule.onNodeWithTag("details_more").performKeyInput { pressKey(Key.DirectionCenter) }

        // Item 1, Watch Together — still a stub. Item 0 opens the Playback Settings dialog,
        // which suppresses this restore on purpose; PlaybackSettingsDialogTest covers it.
        composeRule.onNodeWithTag("more_menu_item_0")
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("more_menu_item_1")
            .performKeyInput { pressKey(Key.DirectionCenter) }

        composeRule.onNodeWithTag("more_menu").assertDoesNotExist()
        composeRule.onNodeWithTag("details_more").assertIsFocused()
    }

    @Test
    fun adminItemsAreHiddenFromNonAdmins() {
        setShellContent(loadedState())
        composeRule.onNodeWithTag("details_more").requestFocus()
        composeRule.onNodeWithTag("details_more").performKeyInput { pressKey(Key.DirectionCenter) }

        // Hidden means absent — not composed, so nothing to focus and nothing to announce.
        composeRule.onNodeWithContentDescription("Playback Settings").assertExists()
        composeRule.onNodeWithContentDescription("Watch Together").assertExists()
        composeRule.onNodeWithContentDescription("Technical Details").assertExists()
        composeRule.onNodeWithContentDescription("Identify Movie").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Delete Movie").assertDoesNotExist()
        composeRule.onNodeWithTag("more_menu_item_3").assertDoesNotExist()
    }

    @Test
    fun adminItemsArePresentForAdmins() {
        setShellContent(loadedState(), user = user.copy(isAdmin = true))
        composeRule.onNodeWithTag("details_more").requestFocus()
        composeRule.onNodeWithTag("details_more").performKeyInput { pressKey(Key.DirectionCenter) }

        composeRule.onNodeWithContentDescription("Identify Movie").assertExists()
        // Delete is the last row, after the separator.
        composeRule.onNodeWithTag("more_menu_item_4")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf("Delete Movie"),
                ),
            )
        composeRule.onNodeWithTag("more_menu_item_5").assertDoesNotExist()
    }

    @Test
    fun theLongestResumeCaptionStaysInsideThePlayColumn() {
        // It reads as Play's progress only if it matches Play. It used to be a sibling of the
        // whole row capped at a fixed width, which ran it out under Watch, Like and More.
        setShellContent(loadedState(testMovieDetails(progress = subMinuteProgress)))

        // Measured with focus parked elsewhere: focus scales Play 1.05x about its centre, which
        // walks its reported left edge out by half the growth and would fail the alignment check
        // for a reason that has nothing to do with the strip.
        composeRule.onNodeWithTag("details_watched").requestFocus()
        composeRule.onNodeWithTag("details_watched").assertIsFocused()

        val play = composeRule.onNodeWithTag("details_play").getUnclippedBoundsInRoot()
        val track = composeRule.onNodeWithTag("details_resume_track").getUnclippedBoundsInRoot()
        val captionLayouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithTag("details_resume_caption")
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
                it(captionLayouts)
            }

        assertEquals(play.width.value, track.width.value, 0.5f)
        assertEquals(play.left.value, track.left.value, 0.5f)
        assertEquals(1, captionLayouts.single().lineCount)
        assertFalse(captionLayouts.single().hasVisualOverflow)

        // And it stops well short of the next control, rather than running under the whole row.
        val watched = composeRule.onNodeWithTag("details_watched").getUnclippedBoundsInRoot()
        assertTrue("the strip runs into Watch", track.right.value < watched.left.value)
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
