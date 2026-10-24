package com.igloo.blindpenguincoder.feature.shows

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.AnimationScaleRule
import com.igloo.blindpenguincoder.TestIglooApp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.feature.home.HomeHeroState
import com.igloo.blindpenguincoder.feature.home.HomeUiState
import com.igloo.blindpenguincoder.feature.home.findActivity
import com.igloo.blindpenguincoder.feature.shared.DetailsState
import com.igloo.blindpenguincoder.inertShowDetailsActions
import com.igloo.blindpenguincoder.testEpisode
import com.igloo.blindpenguincoder.testEpisodes
import com.igloo.blindpenguincoder.testHomeShows
import com.igloo.blindpenguincoder.testPlayTarget
import com.igloo.blindpenguincoder.testSeason
import com.igloo.blindpenguincoder.testSeasonTwoEpisodes
import com.igloo.blindpenguincoder.testShowDetails
import com.igloo.blindpenguincoder.testShowDetailsState
import com.igloo.blindpenguincoder.testShowHero
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The show page's focus contract (design-system.md sections 6.3 and 11.4.3), through the real
 * shell: every entry point lands on Play/Resume and Back returns to the card or hero that led
 * away, the vertical chain is hand-wired top to bottom with nothing escaping into the shell
 * composed underneath, a season chip changes the rail only when pressed — and then hands focus
 * down to that season's first episode — and a failed save's Retry card (section 11.8) joins the
 * chain between Play and the sections below it.
 */
@RunWith(AndroidJUnit4::class)
class ShowDetailsFocusTest {

    @get:Rule(order = 0)
    val animationScale = AnimationScaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private var showState by mutableStateOf(ShowDetailsUiState())
    private val opened = mutableListOf<Long>()
    private val watchedSets = mutableListOf<Pair<Long, Boolean>>()
    private val played = mutableListOf<Long>()
    private val selectedSeasons = mutableListOf<Long>()
    private var hostActivity: Activity? = null

    /** What a chip press loads into the rail, by season; Loading holds the skeleton. */
    private var seasonOnSelect: (Long) -> IglooRailState<EpisodeUi> = { number ->
        when (number) {
            1L -> IglooRailState.Loaded(testEpisodes)
            2L -> IglooRailState.Loaded(testSeasonTwoEpisodes)
            else -> IglooRailState.Loaded(listOf(testEpisode(id = 921, number = 1, name = "Inside Lumon")))
        }
    }

    private fun setShellContent(
        initialShow: ShowDetailsUiState = ShowDetailsUiState(),
        hero: HomeHeroState = HomeHeroState.Hidden,
        // Explicit, never the ambient default: the Shield test device runs TalkBack, and this
        // suite pins the chain both with and without the reading stops.
        spokenAccessibilityEnabled: Boolean = false,
    ) {
        showState = initialShow
        opened.clear()
        watchedSets.clear()
        played.clear()
        selectedSeasons.clear()
        composeRule.setContent {
            val context = LocalContext.current
            SideEffect { hostActivity = context.findActivity() }
            IglooTheme {
                TestIglooApp(
                    spokenAccessibilityEnabled = spokenAccessibilityEnabled,
                    home = HomeUiState(
                        hero = hero,
                        latestShows = IglooRailState.Loaded(testHomeShows),
                    ),
                    showDetails = showState,
                    showDetailsActions = ShowDetailsActions(
                        onRetry = {},
                        onSelectSeason = { number ->
                            selectedSeasons += number
                            val episodes = seasonOnSelect(number)
                            showState = showState.copy(
                                selectedSeason = number,
                                episodes = episodes,
                                playTarget = if (episodes is IglooRailState.Loaded) {
                                    PlayTargetUi(
                                        episodeId = episodes.items.first().id,
                                        label = "Play S$number E1",
                                        spokenLabel = "Play season $number, episode 1",
                                        actionLabel = "Play",
                                    )
                                } else {
                                    null
                                },
                            )
                        },
                        onRetrySeason = {},
                        onSetEpisodeWatched = { id, watched -> watchedSets += id to watched },
                    ),
                    onShowSelected = { id ->
                        opened += id
                        showState = testShowDetailsState(
                            details = DetailsState.Loaded(testShowDetails(id = id)),
                        )
                    },
                    onPlayShowEpisode = { played += it },
                    onCloseDetails = { showState = ShowDetailsUiState() },
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

    private fun node(tag: String) = composeRule.onNodeWithTag(tag)
    private fun play() = node("details_play")
    private fun chip(number: Long) = node("show_season_$number")
    private fun episode(id: Long) = node("episode_card_$id")

    private fun SemanticsNodeInteraction.press(key: Key): SemanticsNodeInteraction {
        performKeyInput { pressKey(key) }
        composeRule.waitForIdle()
        return this
    }

    /** Opens the page loaded on its first season, as the view model would publish it. */
    private fun openShow(state: ShowDetailsUiState = testShowDetailsState()) =
        setShellContent(initialShow = state)

    @Test
    fun theRecentlyAddedShowsCardOpensTheShowAndBackRestoresIt() {
        setShellContent()
        val card = node("show_card_12")
        card.performScrollTo().requestFocus()
        card.press(Key.DirectionCenter)

        assertEquals(listOf(12L), opened)
        play().assertIsFocused()

        pressBack()

        node("show_details").assertDoesNotExist()
        card.assertIsFocused()
    }

    @Test
    fun theHeroShowPageOpensTheShowAndBackReturnsToTheHero() {
        setShellContent(hero = HomeHeroState.Loaded(listOf(testShowHero)))
        val hero = node("home_hero")
        hero.assertIsFocused()

        hero.press(Key.DirectionCenter)

        assertEquals(listOf(testShowHero.id), opened)
        play().assertIsFocused()

        pressBack()

        hero.assertIsFocused()
    }

    @Test
    fun theSkeletonAnchorHandsFocusToPlayWhenTheShowLoads() {
        setShellContent(ShowDetailsUiState(openShowId = 40, details = DetailsState.Loading))
        composeRule.onNodeWithContentDescription("Loading show details").assertIsFocused()

        showState = testShowDetailsState()
        composeRule.waitForIdle()

        play().assertIsFocused()
    }

    /**
     * The loading and error states each have exactly one focusable and the shell is composed
     * behind them: an unpinned edge would walk focus onto a card nobody can see.
     */
    @Test
    fun theLoadingAnchorAndTheErrorRetryNeverEscapeIntoTheShell() {
        setShellContent(ShowDetailsUiState(openShowId = 40, details = DetailsState.Loading))
        val anchor = composeRule.onNodeWithContentDescription("Loading show details")
        allDirections { anchor.press(it).assertIsFocused() }

        showState = ShowDetailsUiState(openShowId = 40, details = DetailsState.Error("Offline."))
        composeRule.waitForIdle()
        val retry = composeRule.onNodeWithContentDescription("Retry loading show details")
        retry.assertIsFocused()
        allDirections { retry.press(it).assertIsFocused() }
    }

    @Test
    fun downFromPlayWalksChipsEpisodesCastExtrasAboutAndStopsThenUpReturns() {
        openShow()
        play().assertIsFocused()

        play().press(Key.DirectionDown)
        chip(1).assertIsFocused()
        chip(1).press(Key.DirectionDown)
        episode(901).assertIsFocused()
        episode(901).press(Key.DirectionDown)
        node("cast_card_101").assertIsFocused()
        node("cast_card_101").press(Key.DirectionDown)
        node("extra_card_201").assertIsFocused()
        node("extra_card_201").press(Key.DirectionDown)
        node("details_about").assertIsFocused()
        node("details_about").press(Key.DirectionDown)
        node("details_about").assertIsFocused()

        node("details_about").press(Key.DirectionUp)
        node("extra_card_201").assertIsFocused()
        node("extra_card_201").press(Key.DirectionUp)
        node("cast_card_101").assertIsFocused()
        node("cast_card_101").press(Key.DirectionUp)
        episode(901).assertIsFocused()
        episode(901).press(Key.DirectionUp)
        chip(1).assertIsFocused()
        chip(1).press(Key.DirectionUp)
        play().assertIsFocused()
    }

    @Test
    fun playPinsEveryEdgeButDown() {
        openShow()
        listOf(Key.DirectionUp, Key.DirectionLeft, Key.DirectionRight).forEach { key ->
            play().press(key).assertIsFocused()
        }
    }

    /** The chips select on press: moving across them leaves the season and its rail alone. */
    @Test
    fun movingAcrossChipsChangesNothing() {
        openShow()
        play().press(Key.DirectionDown)

        chip(1).press(Key.DirectionRight)
        chip(2).assertIsFocused()
        chip(2).press(Key.DirectionRight)
        chip(0).assertIsFocused()
        chip(0).press(Key.DirectionRight)
        chip(0).assertIsFocused()

        assertEquals(emptyList<Long>(), selectedSeasons)
        episode(901).assertExists()
    }

    @Test
    fun pressingAChipLandsOnItsSeasonsFirstEpisode() {
        openShow()
        play().press(Key.DirectionDown)
        chip(1).press(Key.DirectionRight)

        chip(2).press(Key.DirectionCenter)

        assertEquals(listOf(2L), selectedSeasons)
        episode(911).assertIsFocused()
        node("episode_card_901").assertDoesNotExist()
    }

    /** A season that has to load lands on its skeleton, then on the first card when it lands. */
    @Test
    fun pressingAChipWhoseSeasonIsLoadingLandsOnTheSkeletonThenTheFirstCard() {
        seasonOnSelect = { IglooRailState.Loading }
        openShow()
        play().press(Key.DirectionDown)
        chip(1).press(Key.DirectionRight)

        chip(2).press(Key.DirectionCenter)

        composeRule.onNodeWithContentDescription("Loading Season 2").assertIsFocused()

        showState = showState.copy(episodes = IglooRailState.Loaded(testSeasonTwoEpisodes))
        composeRule.waitForIdle()

        episode(911).assertIsFocused()
    }

    @Test
    fun pressingTheSelectedChipMovesIntoTheRailWithoutReselecting() {
        openShow()
        play().press(Key.DirectionDown)

        chip(1).press(Key.DirectionCenter)

        assertEquals(emptyList<Long>(), selectedSeasons)
        episode(901).assertIsFocused()
    }

    @Test
    fun upFromTheRailReturnsToTheSelectedChip() {
        openShow(testShowDetailsState(selectedSeason = 2, episodes = IglooRailState.Loaded(testSeasonTwoEpisodes)))
        play().press(Key.DirectionDown)
        chip(2).assertIsFocused()
        chip(2).press(Key.DirectionDown)
        episode(911).press(Key.DirectionRight)
        episode(912).assertIsFocused()

        episode(912).press(Key.DirectionUp)

        chip(2).assertIsFocused()
    }

    @Test
    fun downFromAnyEpisodeReachesTheNextSection() {
        openShow()
        play().press(Key.DirectionDown)
        chip(1).press(Key.DirectionDown)
        episode(901).press(Key.DirectionRight)
        episode(902).press(Key.DirectionRight)
        episode(903).assertIsFocused()
        // The rail's last card pins its right edge.
        episode(903).press(Key.DirectionRight)
        episode(903).assertIsFocused()

        episode(903).press(Key.DirectionDown)

        node("cast_card_101").assertIsFocused()
    }

    @Test
    fun aSingleSeasonShowHasNoChipsAndTheRailFollowsPlay() {
        openShow(
            testShowDetailsState(
                details = DetailsState.Loaded(testShowDetails(seasons = listOf(testSeason(1, 3)))),
            ),
        )

        node("show_season_row").assertDoesNotExist()
        play().press(Key.DirectionDown)
        episode(901).assertIsFocused()
        episode(901).press(Key.DirectionUp)
        play().assertIsFocused()
    }

    /** No seasons, no action: the entry anchor passes to the first section (section 11.4.2). */
    @Test
    fun aShowWithNoSeasonsHandsTheEntryAnchorToTheFirstSection() {
        openShow(
            testShowDetailsState(
                details = DetailsState.Loaded(testShowDetails(seasons = emptyList())),
                selectedSeason = null,
                episodes = IglooRailState.Loaded(emptyList()),
                playTarget = null,
            ),
        )

        play().assertDoesNotExist()
        node("cast_card_101").assertIsFocused()
        // Nothing above the first section: up stays put.
        node("cast_card_101").press(Key.DirectionUp).assertIsFocused()
    }

    @Test
    fun aFailedSeasonsRetryIsReachableAndPinned() {
        openShow(
            testShowDetailsState(
                episodes = IglooRailState.Error("Couldn't load the episodes."),
                playTarget = null,
            ),
        )
        // With no target the slot is empty: the chips take the entry anchor.
        chip(1).assertIsFocused()

        chip(1).press(Key.DirectionDown)
        val retry = composeRule.onNodeWithContentDescription("Retry loading Season 1")
        retry.assertIsFocused()
        retry.press(Key.DirectionLeft).assertIsFocused()
        retry.press(Key.DirectionRight).assertIsFocused()
        retry.press(Key.DirectionDown)
        node("cast_card_101").assertIsFocused()
        node("cast_card_101").press(Key.DirectionUp)
        retry.assertIsFocused()
        retry.press(Key.DirectionUp)
        chip(1).assertIsFocused()
    }

    /**
     * The slot keeps one footprint whatever the season holds, so retargeting the hero — a
     * season loading, then landing with a target of a different width — never changes the
     * hero's height. Measured from the title to the Overview heading below the hero: focus
     * leaving a disposed Play may scroll the page, which moves both together.
     */
    @Test
    fun theHeroDoesNotChangeHeightWhenTheSelectedSeasonRetargetsIt() {
        openShow()
        val title = composeRule.onNode(hasText("Severance") and isHeading())
        val overview = composeRule.onNode(hasText("Overview") and isHeading())
        fun heroSpan() = overview.getUnclippedBoundsInRoot().top.value -
            title.getUnclippedBoundsInRoot().top.value
        val before = heroSpan()

        showState = showState.copy(selectedSeason = 2, episodes = IglooRailState.Loading, playTarget = null)
        composeRule.waitForIdle()
        assertEquals(before, heroSpan(), 0.5f)

        showState = showState.copy(
            episodes = IglooRailState.Loaded(testSeasonTwoEpisodes),
            playTarget = PlayTargetUi(
                episodeId = 911,
                label = "Play S2 E1",
                spokenLabel = "Play season 2, episode 1, Hello, Ms. Cobel",
                actionLabel = "Play",
            ),
        )
        composeRule.waitForIdle()
        assertEquals(before, heroSpan(), 0.5f)

        showState = showState.copy(playTarget = testPlayTarget)
        composeRule.waitForIdle()
        assertEquals(before, heroSpan(), 0.5f)
    }

    /**
     * Under a screen reader the page lands on the hero summary: the skeleton giving way hands
     * focus to the first focusable, and TalkBack settles there whatever follows, so the summary
     * is the entry on purpose — Play is one press down, and the reading stops join the chain.
     */
    @Test
    fun underAScreenReaderThePageLandsOnTheHeroSummaryAndTheStopsJoinTheChain() {
        setShellContent(initialShow = testShowDetailsState(), spokenAccessibilityEnabled = true)
        val summary = node("details_hero_info")
        summary.assertIsFocused()
        summary.press(Key.DirectionUp).assertIsFocused()

        summary.press(Key.DirectionDown)
        play().assertIsFocused()
        play().press(Key.DirectionDown)
        settleSectionMove()
        node("details_overview_stop").assertIsFocused()
        node("details_overview_stop").press(Key.DirectionDown)
        settleSectionMove()
        chip(1).assertIsFocused()
        chip(1).press(Key.DirectionDown)
        settleSectionMove()
        episode(901).assertIsFocused()
        episode(901).press(Key.DirectionDown)
        settleSectionMove()
        node("details_key_crew_stop").assertIsFocused()
        node("details_key_crew_stop").press(Key.DirectionDown)
        settleSectionMove()
        node("cast_card_101").assertIsFocused()

        node("cast_card_101").press(Key.DirectionUp)
        settleSectionMove()
        node("details_key_crew_stop").assertIsFocused()
    }

    /** A chip press under a screen reader still lands on the season's first episode. */
    @Test
    fun underAScreenReaderAChipPressLandsOnItsFirstEpisode() {
        setShellContent(initialShow = testShowDetailsState(), spokenAccessibilityEnabled = true)
        chip(2).requestFocus()
        chip(2).press(Key.DirectionCenter)
        settleSectionMove()

        assertEquals(listOf(2L), selectedSeasons)
        episode(911).assertIsFocused()
    }

    @Test
    fun theShellLeavesTalkBackTraversalWhileTheOverlayIsOpen() {
        setShellContent()
        val shell = node("shell_content")
        shell.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.HideFromAccessibility))

        val card = node("show_card_12")
        card.performScrollTo().requestFocus()
        card.press(Key.DirectionCenter)

        shell.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
    }

    /** The page alone, as the host composes it, with a failed save's Retry card under the hero. */
    private fun setScreenContent(
        state: ShowDetailsUiState,
        progressSyncError: String,
        spokenAccessibilityEnabled: Boolean = false,
    ) {
        composeRule.setContent {
            IglooTheme {
                ShowDetailsScreen(
                    state = state,
                    actions = inertShowDetailsActions,
                    onPlayEpisode = { _, _ -> },
                    playReturnRequester = remember { FocusRequester() },
                    episodeReturnRequester = remember { FocusRequester() },
                    progressSyncError = progressSyncError,
                    spokenAccessibilityEnabled = spokenAccessibilityEnabled,
                )
            }
        }
        composeRule.waitForIdle()
    }

    /**
     * The Retry card sits under Play: down from Play reaches it, down again the chips, and up walks
     * back the same way; its sides are pinned like every edge of the page.
     */
    @Test
    fun theProgressRetrySitsBetweenPlayAndTheChipsAndPinsItsSides() {
        setScreenContent(testShowDetailsState(), progressSyncError = SAVE_FAILED)
        play().assertIsFocused()

        play().press(Key.DirectionDown)
        val retry = node("details_progress_retry")
        retry.assertIsFocused()
        retry.press(Key.DirectionLeft).assertIsFocused()
        retry.press(Key.DirectionRight).assertIsFocused()
        retry.press(Key.DirectionDown)
        chip(1).assertIsFocused()
        chip(1).press(Key.DirectionUp)
        retry.assertIsFocused()
        retry.press(Key.DirectionUp)
        play().assertIsFocused()
    }

    /** With nothing to play, the Retry card is the chain's top and the page's entry. */
    @Test
    fun withoutPlayTheProgressRetryIsTheEntryAndTheTopOfTheChain() {
        setScreenContent(
            testShowDetailsState(episodes = IglooRailState.Loaded(emptyList()), playTarget = null),
            progressSyncError = SAVE_FAILED,
        )

        val retry = node("details_progress_retry")
        retry.assertIsFocused()
        retry.press(Key.DirectionUp).assertIsFocused()
        retry.press(Key.DirectionDown)
        chip(1).assertIsFocused()
    }

    /** Under a screen reader the card sits between Play and the Overview stop, both ways. */
    @Test
    fun underAScreenReaderTheProgressRetryPrecedesTheOverviewStop() {
        setScreenContent(
            testShowDetailsState(),
            progressSyncError = SAVE_FAILED,
            spokenAccessibilityEnabled = true,
        )
        node("details_hero_info").assertIsFocused()

        node("details_hero_info").press(Key.DirectionDown)
        play().assertIsFocused()
        play().press(Key.DirectionDown)
        settleSectionMove()
        node("details_progress_retry").assertIsFocused()
        node("details_progress_retry").press(Key.DirectionDown)
        settleSectionMove()
        node("details_overview_stop").assertIsFocused()
        node("details_overview_stop").press(Key.DirectionUp)
        settleSectionMove()
        node("details_progress_retry").assertIsFocused()
    }

    private fun allDirections(check: (Key) -> Unit) =
        listOf(Key.DirectionLeft, Key.DirectionDown, Key.DirectionUp, Key.DirectionRight)
            .forEach(check)

    /** Lets a screen-reader section move — reveal, snapshot wait, held focus, settle — finish. */
    private fun settleSectionMove() {
        composeRule.mainClock.advanceTimeBy(SECTION_MOVE_MS)
        composeRule.waitForIdle()
    }

    private fun isHeading() = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    // --- the episode menu ---

    /**
     * Holds Center on a card: foundation's long-press timer runs on real time, so the menu is
     * awaited, not idled for. The release goes through the root — the card has left the
     * semantics tree under the menu — and lands on the focused menu row, which must not take a
     * release it never saw pressed as a click.
     */
    private fun SemanticsNodeInteraction.hold() {
        performKeyInput { keyDown(Key.DirectionCenter) }
        composeRule.waitUntil(timeoutMillis = 3_000) {
            composeRule.onAllNodesWithTag("more_menu").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onRoot().performKeyInput { keyUp(Key.DirectionCenter) }
        composeRule.waitForIdle()
    }

    private fun menuRow() = node("more_menu_item_0")

    @Test
    fun holdingCenterOnAnEpisodeOpensItsMenuOnMarkWatchedAndDoesNotPlay() {
        openShow()
        episode(903).requestFocus()

        episode(903).hold()

        menuRow().assertIsFocused()
        menuRow().assertContentDescriptionEquals("Mark watched")
        assertTrue(played.isEmpty())
        assertTrue(watchedSets.isEmpty())
    }

    @Test
    fun aShortPressOnAnEpisodeStillPlaysIt() {
        openShow()
        episode(903).requestFocus()

        episode(903).press(Key.DirectionCenter)

        assertEquals(listOf(903L), played)
        node("more_menu").assertDoesNotExist()
    }

    @Test
    fun theEpisodeMenuTrapsEveryDpadDirection() {
        openShow()
        episode(903).requestFocus()
        episode(903).hold()

        allDirections { menuRow().press(it).assertIsFocused() }
    }

    @Test
    fun backClosesTheEpisodeMenuAloneAndRestoresFocusToTheCard() {
        openShow()
        episode(903).requestFocus()
        episode(903).hold()

        pressBack()

        node("more_menu").assertDoesNotExist()
        node("show_details").assertExists()
        episode(903).assertIsFocused()
        assertTrue(watchedSets.isEmpty())
    }

    @Test
    fun selectingMarkWatchedClosesTheMenuAndRestoresFocusToTheCard() {
        openShow()
        episode(903).requestFocus()
        episode(903).hold()

        menuRow().press(Key.DirectionCenter)

        assertEquals(listOf(903L to true), watchedSets)
        node("more_menu").assertDoesNotExist()
        episode(903).assertIsFocused()
        assertTrue(played.isEmpty())
    }

    @Test
    fun aWatchedCardOffersMarkUnwatched() {
        openShow()
        episode(901).requestFocus()

        episode(901).hold()

        menuRow().assertContentDescriptionEquals("Mark unwatched")
        menuRow().press(Key.DirectionCenter)
        assertEquals(listOf(901L to false), watchedSets)
    }

    // --- landing after the player's hand-off ---

    private fun land(seasonNumber: Long, episodeId: Long, site: EpisodeLaunchSite, sequence: Int = 1) =
        EpisodeLanding(seasonNumber, episodeId, site, sequence)

    private val seasonTwoTarget = PlayTargetUi(
        episodeId = 911,
        label = "Play S2 E1",
        spokenLabel = "Play season 2, episode 1, Hello, Ms. Cobel",
        actionLabel = "Play",
    )

    @Test
    fun aLandingOnTheShownSeasonMovesFocusToTheLandedCard() {
        openShow()
        play().assertIsFocused()

        composeRule.runOnIdle { showState = showState.copy(landing = land(1, 903, EpisodeLaunchSite.EpisodesRail)) }
        composeRule.waitForIdle()

        episode(903).assertIsFocused()
    }

    @Test
    fun aLandingOnAnotherSeasonSelectsItsChipAndLandsOnItsCard() {
        openShow()

        composeRule.runOnIdle {
            showState = showState.copy(
                selectedSeason = 2,
                episodes = IglooRailState.Loaded(testSeasonTwoEpisodes),
                playTarget = seasonTwoTarget,
                landing = land(2, 912, EpisodeLaunchSite.EpisodesRail),
            )
        }
        composeRule.waitForIdle()

        episode(912).assertIsFocused()
        chip(2).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected"))
        node("episode_card_901").assertDoesNotExist()
    }

    @Test
    fun aLandingOnASeasonStillLoadingHoldsTheSkeletonThenLandsOnTheCard() {
        openShow()

        composeRule.runOnIdle {
            showState = showState.copy(
                selectedSeason = 2,
                episodes = IglooRailState.Loading,
                playTarget = null,
                landing = land(2, 912, EpisodeLaunchSite.EpisodesRail),
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Loading Season 2").assertExists()
        node("episode_card_912").assertDoesNotExist()

        composeRule.runOnIdle {
            showState = showState.copy(
                episodes = IglooRailState.Loaded(testSeasonTwoEpisodes),
                playTarget = seasonTwoTarget,
            )
        }
        composeRule.waitForIdle()

        episode(912).assertIsFocused()
    }

    @Test
    fun aHeroLandingKeepsPlayAndRelabelsIt() {
        openShow()
        play().assertIsFocused()

        composeRule.runOnIdle {
            showState = showState.copy(
                playTarget = PlayTargetUi(
                    episodeId = 903,
                    label = "Play S1 E3",
                    spokenLabel = "Play season 1, episode 3, In Perpetuity",
                    actionLabel = "Play",
                ),
                landing = land(1, 902, EpisodeLaunchSite.Hero),
            )
        }
        composeRule.waitForIdle()

        play().assertIsFocused()
        play().assertContentDescriptionEquals("Play season 1, episode 3, In Perpetuity")
    }

    @Test
    fun aHeroLandingOnALoadingSeasonReturnsFocusToPlayWhenItLands() {
        openShow()

        composeRule.runOnIdle {
            showState = showState.copy(
                selectedSeason = 2,
                episodes = IglooRailState.Loading,
                playTarget = null,
                landing = land(2, 911, EpisodeLaunchSite.Hero),
            )
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            showState = showState.copy(
                episodes = IglooRailState.Loaded(testSeasonTwoEpisodes),
                playTarget = seasonTwoTarget,
            )
        }
        composeRule.waitForIdle()

        play().assertIsFocused()
        play().assertContentDescriptionEquals("Play season 2, episode 1, Hello, Ms. Cobel")
    }

    @Test
    fun aLandingOnAnEpisodeTheSeasonNoLongerListsLandsOnItsFirstCard() {
        openShow()

        composeRule.runOnIdle { showState = showState.copy(landing = land(1, 999, EpisodeLaunchSite.EpisodesRail)) }
        composeRule.waitForIdle()

        episode(901).assertIsFocused()
    }

    @Test
    fun aSecondLandingOnTheSameEpisodeLandsAgain() {
        openShow()
        composeRule.runOnIdle { showState = showState.copy(landing = land(1, 903, EpisodeLaunchSite.EpisodesRail)) }
        composeRule.waitForIdle()
        episode(903).assertIsFocused()
        play().requestFocus()
        play().assertIsFocused()

        composeRule.runOnIdle {
            showState = showState.copy(landing = land(1, 903, EpisodeLaunchSite.EpisodesRail, sequence = 2))
        }
        composeRule.waitForIdle()

        episode(903).assertIsFocused()
    }

    @Test
    fun aLandingOnAnotherSeasonIsHeardThroughTheSectionMoveUnderAScreenReader() {
        setShellContent(initialShow = testShowDetailsState(), spokenAccessibilityEnabled = true)

        composeRule.runOnIdle {
            showState = showState.copy(
                selectedSeason = 2,
                episodes = IglooRailState.Loaded(testSeasonTwoEpisodes),
                playTarget = seasonTwoTarget,
                landing = land(2, 912, EpisodeLaunchSite.EpisodesRail),
            )
        }
        composeRule.waitUntil(timeoutMillis = SECTION_MOVE_MS) {
            composeRule.onAllNodesWithTag("episode_card_912").fetchSemanticsNodes()
                .any { it.config.getOrNull(SemanticsProperties.Focused) == true }
        }

        episode(912).assertIsFocused()
    }
}

private const val SECTION_MOVE_MS = 3_000L
private const val SAVE_FAILED = "Couldn't save playback progress: no connection."
