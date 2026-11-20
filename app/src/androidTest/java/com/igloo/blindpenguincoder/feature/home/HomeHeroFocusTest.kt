package com.igloo.blindpenguincoder.feature.home

import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.AnimationScaleRule
import com.igloo.blindpenguincoder.TestIglooApp
import com.igloo.blindpenguincoder.assertPlatformTree
import com.igloo.blindpenguincoder.clickActionLabelled
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.pressBack
import com.igloo.blindpenguincoder.testContinueHero
import com.igloo.blindpenguincoder.testHomeShows
import com.igloo.blindpenguincoder.testContinueMovies
import com.igloo.blindpenguincoder.testHero
import com.igloo.blindpenguincoder.testHomeMovies
import com.igloo.blindpenguincoder.testShowHero
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The hero's focus contract (design-system.md section 11.3.1): one focus target, whichever page
 * shows. It owns the pane's entry anchor whenever it is visible; the skeleton is the same node, so
 * loading never moves focus; right and left turn the page in place, changing what the focused
 * target says; and each page names its feature and its place, with an action only where
 * something can be opened.
 */
@RunWith(AndroidJUnit4::class)
class HomeHeroFocusTest {

    @get:Rule(order = 0)
    val animationScale = AnimationScaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private var heroState by mutableStateOf<HomeHeroState>(HomeHeroState.Loading)
    private val opened = mutableListOf<Long>()
    private val showsOpened = mutableListOf<Long>()
    private val resumed = mutableListOf<Long>()

    private val bothPages = HomeHeroState.Loaded(listOf(testHero, testShowHero))

    private val moviePageDescription = "Recently added movie, 1 of 2. Heat. " +
        "Rated 8.2 out of 10, R, 1995, 2 hours and 50 minutes. " +
        "Obsessive master thief Neil McCauley leads a top-notch crew."
    private val showPageDescription = "Recently added TV show, 2 of 2. Severance. " +
        "Rated 8.4 out of 10, TV-MA, 2022, 2 seasons. " +
        "Mark leads a team of office workers whose memories have been split."

    private fun setShellContent(
        initialHero: HomeHeroState,
    ) {
        heroState = initialHero
        opened.clear()
        showsOpened.clear()
        resumed.clear()
        composeRule.setContent {
            IglooTheme {
                TestIglooApp(
                    home = HomeUiState(
                        hero = heroState,
                        continueWatching = IglooRailState.Loaded(testContinueMovies),
                        latestMovies = IglooRailState.Loaded(testHomeMovies),
                        latestShows = IglooRailState.Loaded(testHomeShows),
                    ),
                    onMovieSelected = { id -> opened += id },
                    onShowSelected = { id -> showsOpened += id },
                    onResumeEpisode = { id -> resumed += id },
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun hero() = composeRule.onNodeWithTag("home_hero")
    private fun press(key: Key) = hero().performKeyInput { pressKey(key) }
    /**
     * TalkBack reads a page turn, and a load under focus, because the description of the node its
     * cursor is on changed. A live region on top made it re-read the hero every time focus came
     * or went — measured on a Shield — since each focus change reports a content change.
     */
    private fun assertNoLiveRegion() =
        hero().assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))

    @Test
    fun initialFocusLandsOnTheHeroSkeleton() {
        setShellContent(HomeHeroState.Loading)

        hero().assertIsFocused().assertContentDescriptionEquals("Loading featured titles")
        assertNoLiveRegion()
    }

    @Test
    fun theLoadedHeroIsTheSkeletonsOwnNodeSoFocusNeverMoves() {
        setShellContent(HomeHeroState.Loading)
        val skeletonNode = hero().assertIsFocused().fetchSemanticsNode().id

        heroState = bothPages
        composeRule.waitForIdle()

        // Same node, still focused, now saying what loaded — read out as a change to the node
        // TalkBack is on, not through a focus handoff it would not hear.
        hero().assertIsFocused().assertContentDescriptionEquals(moviePageDescription)
        assertEquals(skeletonNode, hero().fetchSemanticsNode().id)
        assertNoLiveRegion()
    }

    /**
     * The platform's own tree, as TalkBack receives it — not Compose's semantics tree, which every
     * other case here reads. Two stacked page targets once passed every semantics assertion while
     * the platform tree held only the top one: Compose leaves a node out of it when another covers
     * it completely, so the page beneath could take focus without TalkBack ever hearing of it.
     */
    @Test
    fun thePlatformTreeHoldsOneFocusedHeroNodeOnEitherPage() {
        setShellContent(bothPages)
        assertHeroNodesInPlatformTree(listOf(moviePageDescription to true))

        press(Key.DirectionRight)

        assertHeroNodesInPlatformTree(listOf(showPageDescription to true))
    }

    private fun assertHeroNodesInPlatformTree(expected: List<Pair<String, Boolean>>) =
        composeRule.assertPlatformTree(expected, PLATFORM_TREE_TIMEOUT_MS, ::collectHeroNodes)

    /** Every node announcing a hero page, with whether it holds input focus. */
    private fun collectHeroNodes(node: AccessibilityNodeInfo, into: MutableList<Pair<String, Boolean>>) {
        node.contentDescription?.toString()?.takeIf { it.startsWith("Recently added") }
            ?.let { into += it to node.isFocused }
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectHeroNodes(it, into) }
    }

    @Test
    fun aHeroThatHidesHandsFocusToTheFirstRail() {
        setShellContent(HomeHeroState.Loading)
        hero().assertIsFocused()

        heroState = HomeHeroState.Hidden
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("continue_card_1").assertIsFocused()
    }

    @Test
    fun focusNotOnTheHeroIsNotStolenWhenItLoads() {
        setShellContent(HomeHeroState.Loading)
        press(Key.DirectionLeft)
        composeRule.onNodeWithContentDescription("Home").assertIsFocused()

        heroState = bothPages
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Home").assertIsFocused()
    }

    @Test
    fun aHeroThatComesBackDoesNotStealFocusFromTheRails() {
        // The hero owned focus before it hid, so whatever remembers that must not still be armed
        // when it returns: by then the user has moved on and the rail owns focus for real.
        setShellContent(HomeHeroState.Loading)
        hero().assertIsFocused()

        heroState = HomeHeroState.Hidden
        composeRule.waitForIdle()
        val firstCard = composeRule.onNodeWithTag("continue_card_1")
        firstCard.assertIsFocused()
        firstCard.performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithTag("continue_card_2").assertIsFocused()

        heroState = bothPages
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("continue_card_2").assertIsFocused()
    }

    @Test
    fun dpadLeftFromTheFirstPageOpensTheSpine() {
        setShellContent(bothPages)

        press(Key.DirectionLeft)

        composeRule.onNodeWithContentDescription("Home").assertIsFocused()
    }

    @Test
    fun dpadRightTurnsToTheShowPageInPlaceAndLeftTurnsBack() {
        setShellContent(bothPages)
        val node = hero().assertIsFocused().fetchSemanticsNode().id

        press(Key.DirectionRight)

        // Focus has not moved; what the focused node says has.
        hero().assertIsFocused().assertContentDescriptionEquals(showPageDescription)
        assertEquals(node, hero().fetchSemanticsNode().id)

        press(Key.DirectionLeft)

        hero().assertIsFocused().assertContentDescriptionEquals(moviePageDescription)
    }

    @Test
    fun dpadRightFromTheLastPageStaysPut() {
        setShellContent(bothPages)
        press(Key.DirectionRight)

        press(Key.DirectionRight)

        hero().assertIsFocused().assertContentDescriptionEquals(showPageDescription)
    }

    @Test
    fun dpadLeftFromTheSecondPageTurnsBackRatherThanLeaving() {
        setShellContent(bothPages)
        press(Key.DirectionRight)

        press(Key.DirectionLeft)
        hero().assertIsFocused()
        press(Key.DirectionLeft)

        composeRule.onNodeWithContentDescription("Home").assertIsFocused()
    }

    @Test
    fun aLonePageCancelsRight() {
        setShellContent(HomeHeroState.Loaded(listOf(testHero)))

        press(Key.DirectionRight)

        hero().assertIsFocused()
    }

    @Test
    fun dpadDownFromEitherPageLandsInTheContinueRail() {
        setShellContent(bothPages)

        press(Key.DirectionDown)
        composeRule.onNodeWithTag("continue_card_1").assertIsFocused()

        composeRule.onNodeWithTag("continue_card_1").performKeyInput { pressKey(Key.DirectionUp) }
        press(Key.DirectionRight)
        press(Key.DirectionDown)
        composeRule.onNodeWithTag("continue_card_1").assertIsFocused()
    }

    @Test
    fun upFromTheRailReturnsToThePageTheUserLeft() {
        setShellContent(bothPages)
        press(Key.DirectionRight)
        press(Key.DirectionDown)

        composeRule.onNodeWithTag("continue_card_1").performKeyInput { pressKey(Key.DirectionUp) }

        hero().assertIsFocused().assertContentDescriptionEquals(showPageDescription)
    }

    @Test
    fun theSpineReturnsToThePageTheUserLeft() {
        setShellContent(bothPages)
        press(Key.DirectionRight)

        // Back opens the spine on Home; right comes back in through the pane's entry anchor.
        composeRule.pressBack()
        val home = composeRule.onNodeWithContentDescription("Home").assertIsFocused()
        home.performKeyInput { pressKey(Key.DirectionRight) }

        hero().assertIsFocused().assertContentDescriptionEquals(showPageDescription)
    }

    @Test
    fun eachPageAnnouncesItsFeatureAndPlaceAsOneNode() {
        setShellContent(bothPages)

        hero().assertContentDescriptionEquals(moviePageDescription).assertHasClickAction()
        // The words on the plate are the target's to speak, not nodes of their own.
        composeRule.onAllNodesWithText("Heat").assertCountEquals(0)

        press(Key.DirectionRight)

        hero().assertContentDescriptionEquals(showPageDescription).assertHasClickAction()
        composeRule.onAllNodesWithText("Severance").assertCountEquals(0)
    }

    /** Each page opens its own details page: the movie's, then the show's. */
    @Test
    fun selectOpensWhicheverPageIsShowing() {
        setShellContent(bothPages)
        press(Key.DirectionRight)

        press(Key.DirectionCenter)
        assertEquals(listOf(testShowHero.id), showsOpened)
        assertEquals(emptyList<Long>(), opened)

        press(Key.DirectionLeft)
        press(Key.Enter)
        assertEquals(listOf(testHero.id), opened)
        assertEquals(listOf(testShowHero.id), showsOpened)
    }

    /**
     * What is in progress leads (section 11.3.1): the page says so, counts itself among three,
     * and names its press as the episode's Continue Watching card does.
     */
    @Test
    fun theLeadPageAnnouncesWhatIsInProgressAndNamesItsPressResume() {
        setShellContent(HomeHeroState.Loaded(listOf(testContinueHero, testHero, testShowHero)))

        hero()
            .assertContentDescriptionEquals(
                "Continue watching, 1 of 3. Slow Horses. " +
                    "S1 E3, Bad Tradecraft, 45 minutes remaining. " +
                    "River Cartwright follows a lead he was told to drop.",
            )
            .assert(clickActionLabelled("Resume Slow Horses S1 E3"))
        assertNoLiveRegion()

        press(Key.DirectionRight)
        hero().assert(clickActionLabelled("Open Heat"))
    }

    /** The lead page's press resumes its episode; the pages after it still open their titles. */
    @Test
    fun selectOnTheLeadPageResumesItsEpisodeAndOpensNothing() {
        setShellContent(HomeHeroState.Loaded(listOf(testContinueHero, testHero, testShowHero)))

        press(Key.DirectionCenter)
        assertEquals(listOf(testContinueHero.id), resumed)
        assertEquals(emptyList<Long>(), opened)

        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertEquals(listOf(testHero.id), opened)
        assertEquals(listOf(testContinueHero.id), resumed)
    }

    /**
     * Finishing the title takes the lead page away while the hero may hold focus on it: the
     * hero stays one focused node and shows the page that now stands first.
     */
    @Test
    fun aLeadPageThatLeavesUnderFocusHandsTheHeroToTheNextPage() {
        setShellContent(HomeHeroState.Loaded(listOf(testContinueHero, testHero, testShowHero)))
        hero().assertIsFocused()

        heroState = bothPages
        composeRule.waitForIdle()

        hero().assertIsFocused().assertContentDescriptionEquals(moviePageDescription)
    }

    /** A lead page arriving in front keeps the user on the page they were reading. */
    @Test
    fun aLeadPageArrivingInFrontLeavesTheUserOnTheirPage() {
        setShellContent(bothPages)
        press(Key.DirectionRight)

        heroState = HomeHeroState.Loaded(listOf(testContinueHero, testHero, testShowHero))
        composeRule.waitForIdle()

        hero().assertIsFocused().assertContentDescriptionEquals(
            showPageDescription.replace("2 of 2", "3 of 3"),
        )
    }

    @Test
    fun aLonePageHasNoPlaceToGiveAndNoDots() {
        setShellContent(HomeHeroState.Loaded(listOf(testShowHero)))

        hero().assertContentDescriptionEquals(
            "Recently added TV show. Severance. " +
                "Rated 8.4 out of 10, TV-MA, 2022, 2 seasons. " +
                "Mark leads a team of office workers whose memories have been split.",
        )
        composeRule.onAllNodesWithTag("home_hero_dot_active", useUnmergedTree = true)
            .assertCountEquals(0)
        composeRule.onAllNodesWithTag("home_hero_dot", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun theActiveDotFollowsThePageOnScreen() {
        setShellContent(bothPages)
        fun activeDotIsRightOfTheOther(): Boolean {
            val active = composeRule.onNodeWithTag("home_hero_dot_active", useUnmergedTree = true)
                .fetchSemanticsNode()
            val other = composeRule.onNodeWithTag("home_hero_dot", useUnmergedTree = true)
                .fetchSemanticsNode()
            return active.boundsInRoot.left > other.boundsInRoot.left
        }
        assertTrue("the first page's dot leads", !activeDotIsRightOfTheOther())

        press(Key.DirectionRight)

        assertTrue("the second page's dot leads", activeDotIsRightOfTheOther())
    }

    private companion object {
        const val PLATFORM_TREE_TIMEOUT_MS = 5_000L
    }
}
