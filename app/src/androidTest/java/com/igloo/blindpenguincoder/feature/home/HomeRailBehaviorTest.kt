package com.igloo.blindpenguincoder.feature.home

import android.app.Activity
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.igloo.blindpenguincoder.AnimationScaleRule
import com.igloo.blindpenguincoder.TestIglooApp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.ANNOUNCED_FOCUS_WAIT_MS
import com.igloo.blindpenguincoder.core.ui.ANNOUNCED_LINE_WAIT_MS
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.feature.music.AlbumCardUi
import com.igloo.blindpenguincoder.feature.shared.PosterItem
import com.igloo.blindpenguincoder.testAlbums
import com.igloo.blindpenguincoder.testContinueEpisode
import com.igloo.blindpenguincoder.testContinueItems
import com.igloo.blindpenguincoder.testContinueMovies
import com.igloo.blindpenguincoder.testHomeMovies
import com.igloo.blindpenguincoder.testHomeShows
import com.igloo.blindpenguincoder.testTheaterMovies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The home rails' focus contract: every state of the first rail anchors the content pane,
 * focus survives state swaps, each rail keeps its own focus memory, and the last-focused card
 * is restored on re-entry (design-system.md sections 6.3 and 11.3).
 */
@RunWith(AndroidJUnit4::class)
class HomeRailBehaviorTest {

    @get:Rule(order = 0)
    val animationScale = AnimationScaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private val continueMovies = testContinueMovies
    private val movies = testHomeMovies
    private val shows = testHomeShows
    private val albums = testAlbums
    private val theaterMovies = testTheaterMovies

    private var continueState by
        mutableStateOf<IglooRailState<HomeContinueItem>>(IglooRailState.Loading)
    private var latestState by
        mutableStateOf<IglooRailState<PosterItem>>(IglooRailState.Loading)
    private var showsState by mutableStateOf<IglooRailState<PosterItem>>(IglooRailState.Loading)
    private var albumsState by mutableStateOf<IglooRailState<AlbumCardUi>>(IglooRailState.Loading)
    private var theatersState by
        mutableStateOf<IglooRailState<HomeTheaterMovie>>(IglooRailState.Loading)
    private var continueRetries = 0
    private var latestRetries = 0
    private var showRetries = 0
    private var albumRetries = 0
    private var theaterRetries = 0
    private val opened = mutableListOf<Long>()
    private val resumedEpisodes = mutableListOf<Long>()
    private val theatersOpened = mutableListOf<Long>()
    private val albumsOpened = mutableListOf<Long>()
    private val showsOpened = mutableListOf<Long>()
    private var expandedWidth: Dp = Dp.Unspecified
    private var hostActivity: Activity? = null

    private fun setShellContent(
        initialContinue: IglooRailState<HomeContinueItem>,
        initialLatest: IglooRailState<PosterItem> = IglooRailState.Loaded(movies),
        initialShows: IglooRailState<PosterItem> = IglooRailState.Loaded(shows),
        initialAlbums: IglooRailState<AlbumCardUi> = IglooRailState.Loaded(albums),
        initialTheaters: IglooRailState<HomeTheaterMovie> = IglooRailState.Loaded(theaterMovies),
        onMovieSelected: ((Long) -> Unit)? = { opened += it },
        onTheaterMovieSelected: ((Long) -> Unit)? = { theatersOpened += it },
        onAlbumSelected: ((Long) -> Unit)? = { albumsOpened += it },
        onShowSelected: ((Long) -> Unit)? = { showsOpened += it },
        spokenAccessibilityEnabled: Boolean = false,
    ) {
        continueState = initialContinue
        latestState = initialLatest
        showsState = initialShows
        albumsState = initialAlbums
        theatersState = initialTheaters
        continueRetries = 0
        latestRetries = 0
        showRetries = 0
        albumRetries = 0
        theaterRetries = 0
        opened.clear()
        theatersOpened.clear()
        albumsOpened.clear()
        showsOpened.clear()
        resumedEpisodes.clear()
        composeRule.setContent {
            val context = LocalContext.current
            SideEffect { hostActivity = context.findActivity() }
            IglooTheme {
                expandedWidth = IglooTheme.layout.navRailExpandedWidth
                TestIglooApp(
                    // Hero hidden: this suite pins the rails' own focus contract, where the
                    // first rail legitimately owns the pane's entry anchor (section 11.3.1).
                    // The hero's focus behavior is HomeHeroFocusTest's subject.
                    home = HomeUiState(
                        hero = HomeHeroState.Hidden,
                        continueWatching = continueState,
                        latestMovies = latestState,
                        latestShows = showsState,
                        latestAlbums = albumsState,
                        inTheaters = theatersState,
                    ),
                    onRetryRail = { rail ->
                        when (rail) {
                            HomeRail.ContinueWatching -> continueRetries += 1
                            HomeRail.LatestMovies -> latestRetries += 1
                            HomeRail.LatestShows -> showRetries += 1
                            HomeRail.LatestAlbums -> albumRetries += 1
                            HomeRail.InTheaters -> theaterRetries += 1
                        }
                    },
                    onMovieSelected = onMovieSelected,
                    onTheaterMovieSelected = onTheaterMovieSelected,
                    onAlbumSelected = onAlbumSelected,
                    onShowSelected = onShowSelected,
                    onResumeEpisode = { resumedEpisodes += it },
                    spokenAccessibilityEnabled = spokenAccessibilityEnabled,
                )
            }
        }
        composeRule.waitForIdle()
        // Under a screen reader the shell's first focus request waits until it can be announced,
        // and waitForIdle does not fast-forward that delay.
        if (spokenAccessibilityEnabled) {
            composeRule.mainClock.advanceTimeBy(ANNOUNCED_FOCUS_WAIT_MS + FRAME_MS)
            composeRule.waitForIdle()
        }
    }

    private fun continueCard(id: Long) = composeRule.onNodeWithTag("continue_card_$id")

    private fun continueEpisodeCard(id: Long) =
        composeRule.onNodeWithTag("continue_episode_card_$id")

    private fun latestCard(id: Long) = composeRule.onNodeWithTag("poster_card_$id")

    private fun showCard(id: Long) = composeRule.onNodeWithTag("show_card_$id")

    private fun albumCard(id: Long) = composeRule.onNodeWithTag("album_card_$id")

    private fun theaterCard(id: Long) = composeRule.onNodeWithTag("theater_card_$id")

    private fun pressBack() {
        composeRule.runOnUiThread {
            (checkNotNull(hostActivity) as ComponentActivity).onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
    }

    @Test
    fun initialFocusLandsOnTheLoadingSkeleton() {
        setShellContent(IglooRailState.Loading)

        val skeleton = composeRule.onNodeWithContentDescription("Loading continue watching")
        skeleton.assertIsFocused()
        // Focused, so TalkBack reads it; a live region on top would re-read it on every focus
        // change (section 6.3).
        skeleton.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))

        // The skeleton anchor still opens the spine, so a slow network never traps the d-pad.
        skeleton.performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.onNodeWithContentDescription("Home").assertIsFocused()
        composeRule.onNodeWithTag("navigation_rail").assertWidthIsEqualTo(expandedWidth)
    }

    @Test
    fun skeletonHandsFocusToTheFirstCardWhenContentArrives() {
        setShellContent(IglooRailState.Loading)
        composeRule.onNodeWithContentDescription("Loading continue watching").assertIsFocused()

        continueState = IglooRailState.Loaded(continueMovies)
        composeRule.waitForIdle()

        continueCard(1).assertIsFocused()
    }

    @Test
    fun focusNotInTheRailIsNotStolenWhenContentArrives() {
        setShellContent(IglooRailState.Loading)
        composeRule.onNodeWithContentDescription("Loading continue watching")
            .performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.onNodeWithContentDescription("Home").assertIsFocused()

        continueState = IglooRailState.Loaded(continueMovies)
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Home").assertIsFocused()
    }

    @Test
    fun spineReentryRestoresTheLastFocusedCard() {
        setShellContent(IglooRailState.Loaded(continueMovies))
        continueCard(1).performKeyInput { pressKey(Key.DirectionRight) }
        continueCard(2).performKeyInput { pressKey(Key.DirectionRight) }
        continueCard(3).assertIsFocused()

        // Back opens the spine without walking focus through the earlier cards.
        pressBack()
        composeRule.onNodeWithContentDescription("Home").assertIsFocused()

        composeRule.onNodeWithContentDescription("Home")
            .performKeyInput { pressKey(Key.DirectionRight) }
        continueCard(3).assertIsFocused()
    }

    @Test
    fun focusMemorySurvivesADestinationSwitch() {
        setShellContent(IglooRailState.Loaded(continueMovies))
        continueCard(1).performKeyInput { pressKey(Key.DirectionRight) }
        continueCard(2).assertIsFocused()

        composeRule.onNodeWithContentDescription("Movies").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Home").performClick()
        composeRule.waitForIdle()

        continueCard(2).assertIsFocused()
    }

    @Test
    fun lastCardPinsTheRightEdge() {
        setShellContent(IglooRailState.Loaded(continueMovies))
        continueCard(1).performKeyInput { pressKey(Key.DirectionRight) }
        continueCard(2).performKeyInput { pressKey(Key.DirectionRight) }
        continueCard(3).assertIsFocused()

        continueCard(3).performKeyInput { pressKey(Key.DirectionRight) }

        continueCard(3).assertIsFocused()
    }

    @Test
    fun dpadDownReachesTheLatestRailAndContinueMemorySurvivesTheTrip() {
        setShellContent(IglooRailState.Loaded(continueMovies))
        continueCard(1).performKeyInput { pressKey(Key.DirectionRight) }
        continueCard(2).assertIsFocused()

        continueCard(2).performKeyInput { pressKey(Key.DirectionDown) }
        latestCard(2).assertIsFocused()
        latestCard(2).performKeyInput { pressKey(Key.DirectionRight) }
        latestCard(3).assertIsFocused()

        // Spine re-entry lands on the Continue Watching rail's remembered card, untouched by
        // the excursion through the latest rail — the two rails keep separate memories.
        pressBack()
        composeRule.onNodeWithContentDescription("Home").assertIsFocused()
        composeRule.onNodeWithContentDescription("Home")
            .performKeyInput { pressKey(Key.DirectionRight) }
        continueCard(2).assertIsFocused()
    }

    @Test
    fun errorStateAnchorsFocusOnRetry() {
        setShellContent(IglooRailState.Error("scan in progress"))

        val retry = composeRule.onNodeWithContentDescription("Retry loading Continue Watching")
        retry.assertIsFocused()

        retry.performClick()
        assertEquals(1, continueRetries)
        assertEquals(0, showRetries)
        assertEquals(0, latestRetries)

        retry.performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.onNodeWithContentDescription("Home").assertIsFocused()
    }

    @Test
    fun latestRailRetryLeavesTheContinueRailAlone() {
        setShellContent(
            initialContinue = IglooRailState.Loaded(continueMovies),
            initialLatest = IglooRailState.Error("scan in progress"),
        )

        // The second rail can start below the fold, so bring its Retry into view before tapping.
        composeRule.onNodeWithContentDescription("Retry loading Recently Added Movies")
            .performScrollTo()
            .performClick()

        assertEquals(1, latestRetries)
        assertEquals(0, continueRetries)
    }

    @Test
    fun emptyStateIsFocusableAndAnnounced() {
        setShellContent(IglooRailState.Loaded(emptyList()))

        composeRule.onNodeWithContentDescription(
            "Continue Watching. Nothing in progress yet. Movies and episodes you start watching appear here.",
        )
            .assertIsFocused()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
    }

    /**
     * The card that replaces a focused skeleton is in its first frames, and TalkBack for TV
     * drops a focus event that lands on it too soon (see requestFocusAnnounced): under a screen
     * reader the handoff waits, and nothing has focus until then.
     */
    @Test
    fun underAScreenReaderTheSwapHandoffWaitsUntilItCanBeHeard() {
        setShellContent(IglooRailState.Loading, spokenAccessibilityEnabled = true)
        composeRule.onNodeWithContentDescription("Loading continue watching").assertIsFocused()

        composeRule.mainClock.autoAdvance = false
        continueState = IglooRailState.Loaded(continueMovies)
        repeat(2) { composeRule.mainClock.advanceTimeByFrame() }
        continueCard(1).assertExists().assertIsNotFocused()

        composeRule.mainClock.advanceTimeBy(ANNOUNCED_FOCUS_WAIT_MS + FRAME_MS)
        composeRule.mainClock.autoAdvance = true
        continueCard(1).assertIsFocused()
            .assertContentDescriptionEquals("Continue Watching. Heat, 1995, 2 hours and 7 minutes remaining")
    }

    @Test
    fun cardsAnnounceTitleAndYearAsOneButton() {
        setShellContent(IglooRailState.Loaded(continueMovies))

        // One cleared node per card: TalkBack hears the title and year once, and neither the
        // poster image nor the caption texts exist as separate announceable nodes — in either
        // rail, even though the same movie appears in both.
        latestCard(1)
            .assertContentDescriptionEquals("Recently Added Movies. Heat, 1995")
            .assert(hasClickAction())
        composeRule.onAllNodesWithText("Heat").assertCountEquals(0)
    }

    @Test
    fun continueCardsAnnounceTitleYearAndProgressAsOneButton() {
        setShellContent(IglooRailState.Loaded(continueMovies))

        // Design-system section 12: a continue-watching card announces title, year, and
        // progress in its one cleared node.
        continueCard(1)
            .assertContentDescriptionEquals("Continue Watching. Heat, 1995, 2 hours and 7 minutes remaining")
            .assert(hasClickAction())
        composeRule.onAllNodesWithText("2 hours and 7 minutes remaining").assertCountEquals(0)
    }

    @Test
    fun episodeCardsAnnounceShowCodeNameAndProgressWithAResumeAction() {
        setShellContent(IglooRailState.Loaded(testContinueItems))

        // The show's name leads, the episode is named in the subtitle, and the only action an
        // episode has is to resume — there is no details page to open.
        continueEpisodeCard(900)
            .assertContentDescriptionEquals("Continue Watching. Severance, S1 E3 · In Perpetuity, 42 minutes remaining")
            .assert(hasClickAction())
        composeRule.onAllNodesWithText("42 minutes remaining").assertCountEquals(0)
        assertEquals(
            "Resume Severance S1 E3",
            continueEpisodeCard(900).fetchSemanticsNode()
                .config[SemanticsActions.OnClick].label,
        )
    }

    @Test
    fun okOnAnEpisodeCardAsksHomeToResumeIt() {
        setShellContent(IglooRailState.Loaded(testContinueItems))

        // Server order puts the episode first, so it owns the rail's entry anchor.
        continueEpisodeCard(testContinueEpisode.episodeId).assertIsFocused()
        continueEpisodeCard(testContinueEpisode.episodeId)
            .performKeyInput { pressKey(Key.DirectionCenter) }

        assertEquals(listOf(900L), resumedEpisodes)
        assertEquals(emptyList<Long>(), opened)
    }

    @Test
    fun okOnACardOpensIt() {
        setShellContent(IglooRailState.Loaded(continueMovies))

        continueCard(1).assertIsFocused()
        continueCard(1).performKeyInput { pressKey(Key.DirectionCenter) }

        assertEquals(listOf(movies[0].id), opened)
    }

    @Test
    fun cardsWithNothingToOpenAnnounceNoAction() {
        setShellContent(IglooRailState.Loaded(continueMovies), onMovieSelected = null)

        // Until the details screen lands there is nothing to open, and a card that announced
        // "double tap to Open Heat" would promise a screen reader an action nobody implements.
        // It stays focusable, because the rails' focus model needs every card to be a landing site.
        continueCard(1)
            .assertContentDescriptionEquals("Continue Watching. Heat, 1995, 2 hours and 7 minutes remaining")
            .assertHasNoClickAction()
        continueCard(1).assertIsFocused()
        continueCard(1).performKeyInput { pressKey(Key.DirectionRight) }
        continueCard(2).assertIsFocused()
    }

    @Test
    fun dpadDownReachesTheAlbumsRailAndItKeepsItsOwnFocusMemory() {
        setShellContent(IglooRailState.Loaded(continueMovies))

        continueCard(1).performKeyInput { pressKey(Key.DirectionDown) }
        latestCard(1).assertIsFocused()
        latestCard(1).performKeyInput { pressKey(Key.DirectionDown) }
        showCard(40).assertIsFocused()
        showCard(40).performKeyInput { pressKey(Key.DirectionDown) }
        albumCard(11).assertIsFocused()

        albumCard(11).performKeyInput { pressKey(Key.DirectionRight) }
        albumCard(12).assertIsFocused()

        // A reload swaps the focused rail's state twice; focus must come back to the albums
        // rail's *own* remembered card. A memory cross-wired to another rail (whose ids these
        // requesters don't know) would fall back to the first album instead.
        albumsState = IglooRailState.Loading
        composeRule.waitForIdle()
        albumsState = IglooRailState.Loaded(albums)
        composeRule.waitForIdle()
        albumCard(12).assertIsFocused()

        // Left walks back through the rail and only the first card opens the spine; re-entry then
        // lands on the *first* rail's memory — the albums rail keeps its own, untouched by the trip.
        albumCard(12).performKeyInput { pressKey(Key.DirectionLeft) }
        albumCard(11).performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.onNodeWithContentDescription("Home").assertIsFocused()
        composeRule.onNodeWithContentDescription("Home")
            .performKeyInput { pressKey(Key.DirectionRight) }
        continueCard(1).assertIsFocused()
    }

    @Test
    fun theLastAlbumPinsTheRightEdge() {
        setShellContent(IglooRailState.Loaded(continueMovies))
        continueCard(1).performKeyInput { pressKey(Key.DirectionDown) }
        latestCard(1).performKeyInput { pressKey(Key.DirectionDown) }
        showCard(40).assertIsFocused()
        showCard(40).performKeyInput { pressKey(Key.DirectionDown) }
        albumCard(11).performKeyInput { pressKey(Key.DirectionRight) }
        albumCard(12).performKeyInput { pressKey(Key.DirectionRight) }
        albumCard(13).assertIsFocused()

        albumCard(13).performKeyInput { pressKey(Key.DirectionRight) }

        albumCard(13).assertIsFocused()
    }

    @Test
    fun albumsRailRetryLeavesTheOtherRailsAlone() {
        setShellContent(
            initialContinue = IglooRailState.Loaded(continueMovies),
            initialAlbums = IglooRailState.Error("music scan in progress"),
        )

        composeRule.onNodeWithContentDescription("Retry loading Recently Added Albums")
            .performScrollTo()
            .performClick()

        assertEquals(1, albumRetries)
        assertEquals(0, showRetries)
        assertEquals(0, latestRetries)
        assertEquals(0, continueRetries)
    }

    @Test
    fun anEmptyMusicLibraryIsFocusableAndAnnounced() {
        setShellContent(
            initialContinue = IglooRailState.Loaded(continueMovies),
            initialAlbums = IglooRailState.Loaded(emptyList()),
        )

        // The empty rail must still be a landing site, or d-pad down would stop at the rail above.
        continueCard(1).performKeyInput { pressKey(Key.DirectionDown) }
        latestCard(1).performKeyInput { pressKey(Key.DirectionDown) }
        showCard(40).assertIsFocused()
        showCard(40).performKeyInput { pressKey(Key.DirectionDown) }

        composeRule.onNodeWithContentDescription(
            "Recently Added Albums. No albums in your library yet. Add a music folder on the server and run a scan.",
        ).assertIsFocused()
    }

    @Test
    fun albumCardsAnnounceTitleAndMusicianAndOpenTheirPage() {
        setShellContent(IglooRailState.Loaded(continueMovies))

        // One cleared node: the cover image and the two captions are not separate announceable
        // nodes. The action is "Open", because pressing opens the album detail overlay.
        albumCard(11)
            .performScrollTo()
            .assertContentDescriptionEquals("Recently Added Albums. Help!, The Beatles")
            .assertHasClickAction()
            .requestFocus()
        albumCard(11).performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.onAllNodesWithText("The Beatles").assertCountEquals(0)
        assertEquals(listOf(11L), albumsOpened)
        // The album id goes to its own callback: a movie with the same id must not open.
        assertEquals(emptyList<Long>(), opened)
    }

    @Test
    fun albumCardsWithNothingToOpenAnnounceNoAction() {
        setShellContent(IglooRailState.Loaded(continueMovies), onAlbumSelected = null)

        // With no destination wired the card must not promise one — the same contract the
        // movie cards keep — while staying focusable as a rail landing site.
        albumCard(11)
            .performScrollTo()
            .assertContentDescriptionEquals("Recently Added Albums. Help!, The Beatles")
            .assertHasNoClickAction()
    }

    @Test
    fun dpadDownReachesTheTheatersRailAndItKeepsItsOwnFocusMemory() {
        setShellContent(IglooRailState.Loaded(continueMovies))

        continueCard(1).performKeyInput { pressKey(Key.DirectionDown) }
        latestCard(1).performKeyInput { pressKey(Key.DirectionDown) }
        showCard(40).assertIsFocused()
        showCard(40).performKeyInput { pressKey(Key.DirectionDown) }
        albumCard(11).performKeyInput { pressKey(Key.DirectionDown) }
        theaterCard(21).assertIsFocused()

        theaterCard(21).performKeyInput { pressKey(Key.DirectionRight) }
        theaterCard(22).assertIsFocused()

        // A reload swaps the focused rail's state twice; focus must come back to the theaters
        // rail's *own* remembered card, not another rail's.
        theatersState = IglooRailState.Loading
        composeRule.waitForIdle()
        theatersState = IglooRailState.Loaded(theaterMovies)
        composeRule.waitForIdle()
        theaterCard(22).assertIsFocused()
    }

    @Test
    fun theLastTheaterCardPinsTheRightEdge() {
        setShellContent(IglooRailState.Loaded(continueMovies))
        continueCard(1).performKeyInput { pressKey(Key.DirectionDown) }
        latestCard(1).performKeyInput { pressKey(Key.DirectionDown) }
        showCard(40).assertIsFocused()
        showCard(40).performKeyInput { pressKey(Key.DirectionDown) }
        albumCard(11).performKeyInput { pressKey(Key.DirectionDown) }
        theaterCard(21).performKeyInput { pressKey(Key.DirectionRight) }
        theaterCard(22).performKeyInput { pressKey(Key.DirectionRight) }
        theaterCard(23).assertIsFocused()

        theaterCard(23).performKeyInput { pressKey(Key.DirectionRight) }

        theaterCard(23).assertIsFocused()
    }

    @Test
    fun theatersRailRetryLeavesTheOtherRailsAlone() {
        setShellContent(
            initialContinue = IglooRailState.Loaded(continueMovies),
            initialTheaters = IglooRailState.Error("tmdb unavailable"),
        )

        composeRule.onNodeWithContentDescription("Retry loading Now Playing in Theaters")
            .performScrollTo()
            .performClick()

        assertEquals(1, theaterRetries)
        assertEquals(0, albumRetries)
        assertEquals(0, showRetries)
        assertEquals(0, latestRetries)
        assertEquals(0, continueRetries)
    }

    @Test
    fun noTheaterMoviesIsFocusableAndAnnounced() {
        setShellContent(
            initialContinue = IglooRailState.Loaded(continueMovies),
            initialTheaters = IglooRailState.Loaded(emptyList()),
        )

        // The empty rail must still be a landing site, or d-pad down would stop at the rail above.
        continueCard(1).performKeyInput { pressKey(Key.DirectionDown) }
        latestCard(1).performKeyInput { pressKey(Key.DirectionDown) }
        showCard(40).assertIsFocused()
        showCard(40).performKeyInput { pressKey(Key.DirectionDown) }
        albumCard(11).performKeyInput { pressKey(Key.DirectionDown) }

        composeRule.onNodeWithContentDescription(
            "Now Playing in Theaters. No movies are playing in theaters right now. Check back later.",
        ).assertIsFocused()
    }

    @Test
    fun theaterCardsAnnounceTitleYearAndRatingAndOpenTheirPage() {
        setShellContent(IglooRailState.Loaded(continueMovies))

        // One cleared node per card: poster, badge, and overlay texts are not separate
        // announceable nodes. The action is "Open", because pressing opens the in-theaters
        // detail page for that TMDB movie (section 11.4.2).
        theaterCard(21)
            .performScrollTo()
            .assertContentDescriptionEquals("Now Playing in Theaters. Heat 2, 2026, Rated 7.9 out of 10")
            .assertHasClickAction()
            .requestFocus()
        theaterCard(21).performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.onAllNodesWithText("Heat 2").assertCountEquals(0)
        assertEquals(listOf(21L), theatersOpened)
        // The TMDB id goes to its own callback: a library movie with the same id must not open.
        assertEquals(emptyList<Long>(), opened)
    }

    @Test
    fun anUnratedTheaterCardAnnouncesNoRating() {
        setShellContent(IglooRailState.Loaded(continueMovies))

        theaterCard(23)
            .performScrollTo()
            .assertContentDescriptionEquals("Now Playing in Theaters. Unrated")
    }

    @Test
    fun theaterCardsWithNoDestinationAnnounceNoAction() {
        setShellContent(IglooRailState.Loaded(continueMovies), onTheaterMovieSelected = null)

        theaterCard(21)
            .performScrollTo()
            .assertHasNoClickAction()
    }

    @Test
    fun showsKeepFocusMemoryAcrossStateSwapsAndRespectBothHorizontalEdges() {
        setShellContent(IglooRailState.Loaded(continueMovies))
        continueCard(1).performKeyInput { pressKey(Key.DirectionDown) }
        latestCard(1).performKeyInput { pressKey(Key.DirectionDown) }
        showCard(40).assertIsFocused()
        showCard(40).performKeyInput { pressKey(Key.DirectionRight) }
        showCard(12).assertIsFocused()

        showsState = IglooRailState.Loading
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Loading recently added shows").assertIsFocused()
        showsState = IglooRailState.Loaded(shows)
        composeRule.waitForIdle()
        showCard(12).assertIsFocused()
        showCard(12).performKeyInput { pressKey(Key.DirectionRight) }
        showCard(7).performKeyInput { pressKey(Key.DirectionRight) }
        showCard(7).assertIsFocused()
        showCard(7).performKeyInput { pressKey(Key.DirectionLeft) }
        showCard(12).performKeyInput { pressKey(Key.DirectionLeft) }
        showCard(40).performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.onNodeWithContentDescription("Home").assertIsFocused()
        composeRule.onNodeWithContentDescription("Home")
            .performKeyInput { pressKey(Key.DirectionRight) }
        continueCard(1).assertIsFocused()
    }

    @Test
    fun showLoadingErrorEmptyAndLoadedStatesKeepAnAnchorWithoutStealingFocus() {
        setShellContent(
            initialContinue = IglooRailState.Loaded(continueMovies),
            initialShows = IglooRailState.Loading,
        )
        continueCard(1).performKeyInput { pressKey(Key.DirectionDown) }
        latestCard(1).performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithContentDescription("Loading recently added shows").assertIsFocused()

        showsState = IglooRailState.Error("shows scan in progress")
        composeRule.waitForIdle()
        val retry = composeRule.onNodeWithContentDescription("Retry loading Recently Added Shows")
        retry.assertIsFocused().performClick()
        assertEquals(1, showRetries)
        assertEquals(0, continueRetries)
        assertEquals(0, latestRetries)
        assertEquals(0, albumRetries)
        assertEquals(0, theaterRetries)

        showsState = IglooRailState.Loaded(emptyList())
        composeRule.waitForIdle()
        val empty = composeRule.onNodeWithContentDescription(
            "Recently Added Shows. No shows in your library yet. Add a shows folder on the server and run a scan.",
        )
        empty.assertIsFocused().assertHasNoClickAction()
        empty.performKeyInput { pressKey(Key.DirectionDown) }
        // The wide empty anchor lands on the nearest album column, like every empty rail.
        composeRule.onNode(
            isFocused() and hasAnyAncestor(hasTestTag("rail_scroll_Recently Added Albums")),
        ).performKeyInput { pressKey(Key.DirectionUp) }
        empty.assertIsFocused()
        empty.performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.onNodeWithContentDescription("Home").assertIsFocused()

        showsState = IglooRailState.Loaded(shows)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Home").assertIsFocused()
    }

    @Test
    fun showCardsSpeakNameAndOptionalYearAndOpenTheShow() {
        setShellContent(IglooRailState.Loaded(continueMovies))
        showCard(40).performScrollTo().requestFocus()
        showCard(40).assertIsFocused()
            .assertContentDescriptionEquals("Recently Added Shows. Severance, 2022")
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        assertEquals(
            "Open Severance",
            showCard(40).fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.label,
        )
        composeRule.onAllNodesWithText("Severance").assertCountEquals(0)
        composeRule.onAllNodesWithText("2022").assertCountEquals(0)
        assertShowCardInPlatformTree()
        showCard(40).performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(listOf(40L), showsOpened)
        assertEquals(emptyList<Long>(), opened)
        assertEquals(emptyList<Long>(), resumedEpisodes)
        assertEquals(emptyList<Long>(), albumsOpened)
        assertEquals(emptyList<Long>(), theatersOpened)
        // Focus is inside the rail and did not enter on this card: it speaks itself alone.
        showCard(7).assertContentDescriptionEquals("Untitled Show").assertHasClickAction()
    }

    /** With no show page to open, a card is a landing site that announces no action. */
    @Test
    fun withNothingToOpenAShowCardAnnouncesNoAction() {
        setShellContent(IglooRailState.Loaded(continueMovies), onShowSelected = null)
        showCard(40).performScrollTo().requestFocus()
        showCard(40).assertIsFocused().assertHasNoClickAction()
        assertFalse(showCard(40).fetchSemanticsNode().config.contains(SemanticsProperties.Role))
    }

    /** The exported Android tree must contain one focused, clickable card, without caption stops. */
    private fun assertShowCardInPlatformTree() = assertPlatformTreeHolds(
        expected = listOf(Triple("Recently Added Shows. Severance, 2022", true, true)),
    ) { spoken -> spoken.contains("Severance") || spoken == "2022" }

    /**
     * What TalkBack reads is the platform tree, not Compose's semantics: every node whose spoken
     * text [matches] must be exactly [expected] (text, clickable, focused), within a short wait
     * for the accessibility batch that exports a change.
     */
    private fun assertPlatformTreeHolds(
        expected: List<Triple<String, Boolean, Boolean>>,
        matches: (String) -> Boolean,
    ) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + 5_000
        var found: List<Triple<String, Boolean, Boolean>>
        do {
            composeRule.waitForIdle()
            found = buildList {
                automation.rootInActiveWindow?.let { collectNodes(it, this, matches) }
            }
            if (found == expected) return
            Thread.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        assertEquals(expected, found)
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo,
        into: MutableList<Triple<String, Boolean, Boolean>>,
        matches: (String) -> Boolean,
    ) {
        val spoken = node.contentDescription?.toString() ?: node.text?.toString()
        if (spoken != null && matches(spoken)) {
            into += Triple(spoken, node.isClickable, node.isFocused)
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectNodes(it, into, matches) }
    }

    @Test
    fun theSectionIsSpokenOnEntryAndNotOnSidewaysMoves() {
        setShellContent(IglooRailState.Loaded(continueMovies))

        // Section 11.3.2: the card focus entered on carries the rail's title; its siblings never
        // did, so a sideways move speaks the card alone — and the entry card sheds the title
        // while nobody is listening to it.
        continueCard(1).assertIsFocused()
            .assertContentDescriptionEquals("Continue Watching. Heat, 1995, 2 hours and 7 minutes remaining")
        continueCard(2).assertContentDescriptionEquals("Arrival, 2016, 58 minutes remaining")

        continueCard(1).performKeyInput { pressKey(Key.DirectionRight) }
        continueCard(2).assertIsFocused()
            .assertContentDescriptionEquals("Arrival, 2016, 58 minutes remaining")
        assertPlatformTreeHolds(
            expected = listOf(Triple("Arrival, 2016, 58 minutes remaining", true, true)),
        ) { spoken -> spoken.contains("58 minutes remaining") }
        // The card just left keeps the description it was read with until TalkBack's cursor has
        // certainly moved on: a change before that is spoken as if the card were new.
        continueCard(1)
            .assertContentDescriptionEquals("Continue Watching. Heat, 1995, 2 hours and 7 minutes remaining")
        settleLeftCard()
        continueCard(1).assertContentDescriptionEquals("Heat, 1995, 2 hours and 7 minutes remaining")

        continueCard(2).performKeyInput { pressKey(Key.DirectionLeft) }
        continueCard(1).assertIsFocused()
            .assertContentDescriptionEquals("Heat, 1995, 2 hours and 7 minutes remaining")
        settleLeftCard()

        // Leaving the rail puts the title back on every card, so the next entry — wherever it
        // lands — is announced; the rail entered next announces itself the same way.
        continueCard(1).performKeyInput { pressKey(Key.DirectionDown) }
        latestCard(1).assertIsFocused()
            .assertContentDescriptionEquals("Recently Added Movies. Heat, 1995")
        continueCard(2).assertContentDescriptionEquals("Continue Watching. Arrival, 2016, 58 minutes remaining")
        continueCard(1).assertContentDescriptionEquals("Heat, 1995, 2 hours and 7 minutes remaining")
        settleLeftCard()
        continueCard(1)
            .assertContentDescriptionEquals("Continue Watching. Heat, 1995, 2 hours and 7 minutes remaining")

        latestCard(1).performKeyInput { pressKey(Key.DirectionUp) }
        continueCard(1).assertIsFocused()
            .assertContentDescriptionEquals("Continue Watching. Heat, 1995, 2 hours and 7 minutes remaining")
        settleLeftCard()
        latestCard(1).assertContentDescriptionEquals("Recently Added Movies. Heat, 1995")
    }

    /**
     * A rail below the fold is composed but not on screen, so Compose would focus its card
     * unannounced (see AnnouncedSectionMove): under a screen reader the move reveals the rail,
     * waits for a snapshot, and only then focuses into it.
     */
    @Test
    fun underAScreenReaderAMoveIntoARailBelowTheFoldWaitsUntilItCanBeHeard() {
        setShellContent(IglooRailState.Loaded(continueMovies), spokenAccessibilityEnabled = true)
        continueCard(1).performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.mainClock.advanceTimeBy(SECTION_MOVE_MS)
        latestCard(1).assertIsFocused()

        composeRule.mainClock.autoAdvance = false
        latestCard(1).performKeyInput { pressKey(Key.DirectionDown) }
        repeat(2) { composeRule.mainClock.advanceTimeByFrame() }
        latestCard(1).assertIsFocused()
        showCard(40).assertIsNotFocused()

        composeRule.mainClock.advanceTimeBy(SECTION_MOVE_MS)
        composeRule.mainClock.autoAdvance = true
        showCard(40).assertIsFocused()
            .assertContentDescriptionEquals("Recently Added Shows. Severance, 2022")
    }

    /** Lets the card focus most recently left take the description the rule now gives it. */
    private fun settleLeftCard() {
        composeRule.mainClock.advanceTimeBy(ANNOUNCED_LINE_WAIT_MS + FRAME_MS)
        composeRule.waitForIdle()
    }

    @Test
    fun theSectionIsSpokenAgainWhenTheSpineHandsBackTheRememberedCard() {
        setShellContent(IglooRailState.Loaded(continueMovies))
        continueCard(1).performKeyInput { pressKey(Key.DirectionRight) }
        continueCard(2).assertIsFocused()
            .assertContentDescriptionEquals("Arrival, 2016, 58 minutes remaining")

        pressBack()
        composeRule.onNodeWithContentDescription("Home").assertIsFocused()
        settleLeftCard()
        composeRule.onNodeWithContentDescription("Home")
            .performKeyInput { pressKey(Key.DirectionRight) }

        // Restored focus is an entry like any other: the remembered card names its section.
        continueCard(2).assertIsFocused()
            .assertContentDescriptionEquals("Continue Watching. Arrival, 2016, 58 minutes remaining")
    }

    @Test
    fun movieShowAlbumNavigationWorksInBothDirectionsAndRestoresShowMemory() {
        setShellContent(IglooRailState.Loaded(continueMovies))
        continueCard(1).performKeyInput { pressKey(Key.DirectionDown) }
        latestCard(1).performKeyInput { pressKey(Key.DirectionDown) }
        showCard(40).performKeyInput { pressKey(Key.DirectionRight) }
        showCard(12).performKeyInput { pressKey(Key.DirectionDown) }
        albumCard(12).assertIsFocused()
        albumCard(12).performKeyInput { pressKey(Key.DirectionUp) }
        showCard(12).assertIsFocused()
        showCard(12).performKeyInput { pressKey(Key.DirectionUp) }
        latestCard(2).assertIsFocused()

        composeRule.onNodeWithContentDescription("Movies").performClick()
        composeRule.onNodeWithContentDescription("Home").performClick()
        composeRule.waitForIdle()
        // A restored rail hands a subsequent state swap back to its saved show id.
        showCard(12).performScrollTo().requestFocus()
        showsState = IglooRailState.Loading
        composeRule.waitForIdle()
        showsState = IglooRailState.Loaded(shows)
        composeRule.waitForIdle()
        showCard(12).assertIsFocused()
    }
}

/** One frame at the test clock's 60Hz: the wait starts after the swapped node's first frame. */
private const val FRAME_MS = 16L

/** Reveal scroll, snapshot wait, held focus and settle scroll: generous, so timing never flakes. */
private const val SECTION_MOVE_MS = 3_000L
