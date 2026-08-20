package com.igloo.blindpenguincoder.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.AnimationScaleRule
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.LocalIglooReducedMotion
import com.igloo.blindpenguincoder.core.design.viewportFactor
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.data.model.AuthUser
import com.igloo.blindpenguincoder.feature.movies.MovieDetailsUiState
import com.igloo.blindpenguincoder.inertDetailsActions
import com.igloo.blindpenguincoder.testContinueMovies
import com.igloo.blindpenguincoder.testHero
import com.igloo.blindpenguincoder.testHomeMovies
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The shell reaches the panel's edges.
 *
 * This is the test the original defect got past: every shell test asserted focus, semantics and
 * rail widths, and none of them asserted where anything *was*. The content pane boxed itself into
 * 752x486dp of a 960x540dp panel and nothing noticed, because "boxed" is invisible to a semantics
 * query and obvious to a person sitting in front of a television.
 *
 * Measured against a literal 960x540dp box, not `onRoot()`: the root is the whole test-activity
 * window, which on a device taller than a TV panel would leave room for content that overflows a
 * real one. The same pattern `WelcomeScreenLayoutTest` uses.
 */
@RunWith(AndroidJUnit4::class)
class ShellBleedTest {

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

    private var railWidth: Dp = Dp.Unspecified
    private var gutter: Dp = Dp.Unspecified
    private var safeAreaHorizontal: Dp = Dp.Unspecified

    private fun setShellContent() {
        composeRule.setContent {
            IglooTheme {
                assertReferenceViewport()
                railWidth = IglooTheme.layout.navRailCollapsedWidth
                gutter = IglooTheme.spacing.xl
                safeAreaHorizontal = IglooTheme.layout.safeAreaHorizontal
                // The ambient backdrop loops forever, so with motion on the Compose clock never
                // goes idle and every assertion below would spin until it timed out.
                CompositionLocalProvider(LocalIglooReducedMotion provides true) {
                    Box(
                        Modifier
                            .size(width = VIEWPORT_WIDTH, height = VIEWPORT_HEIGHT)
                            .testTag(VIEWPORT_TAG),
                    ) {
                        IglooApp(
                            // Pinned: the Shield test device runs TalkBack, and this suite
                            // asserts the focus chain without the reading stops.
                            spokenAccessibilityEnabled = false,
                            user = user,
                            serverOrigin = "http://igloo.test:8080",
                            signOut = SignOutUiState(),
                            home = HomeUiState(
                                hero = HomeHeroState.Loaded(testHero),
                                continueWatching = IglooRailState.Loaded(testContinueMovies),
                                latestMovies = IglooRailState.Loaded(testHomeMovies),
                            ),
                            onRetryRail = {},
                            onMovieSelected = null,
                            onTheaterMovieSelected = null,
                            onCloseDetails = {},
                            details = MovieDetailsUiState(),
                            detailsActions = inertDetailsActions,
                            onSwitchProfile = {},
                            onSignOut = {},
                            onSignOutConfirm = {},
                            onSignOutDismiss = {},
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    /**
     * IglooTheme derives its viewport factor from the window rather than from the box below, so a
     * device reporting 1200dp or wider would double every dimension and fail this suite for the
     * wrong reason.
     */
    @Composable
    private fun assertReferenceViewport() {
        val widthDp = LocalWindowInfo.current.containerSize.width / LocalDensity.current.density
        assertTrue(
            "test device reports ${widthDp}dp wide; this suite assumes the 960dp reference viewport",
            viewportFactor(widthDp) == 1f,
        )
    }

    private fun viewport() = composeRule.onNodeWithTag(VIEWPORT_TAG).fetchSemanticsNode().boundsInRoot

    @Test
    fun theHeroBackdropReachesEveryPhysicalEdgeItCan() {
        setShellContent()

        val panel = viewport()
        val hero = composeRule.onNodeWithTag("home_hero").fetchSemanticsNode().boundsInRoot

        assertTrue("hero left ${hero.left} should sit on the panel edge ${panel.left}", hero.left == panel.left)
        assertTrue("hero top ${hero.top} should sit on the panel edge ${panel.top}", hero.top == panel.top)
        assertTrue("hero right ${hero.right} should sit on the panel edge ${panel.right}", hero.right == panel.right)
    }

    @Test
    fun theRailScrollSurfaceSpansThePanelWhileItsCardsStayInset() {
        setShellContent()

        val panel = viewport()
        val density = composeRule.density
        val rail = composeRule.onNodeWithTag("rail_scroll_Continue Watching")
            .fetchSemanticsNode().boundsInRoot

        // The surface bleeds: a card scrolled to the end runs off the physical edge rather than
        // stopping 48dp short of it, which is the affordance section 8.3 asks for.
        assertTrue("rail right ${rail.right} should reach the panel edge ${panel.right}", rail.right == panel.right)

        // The content inside it does not: the first card still rests at the rail + gutter.
        val firstCard = composeRule.onNodeWithTag("continue_card_${testContinueMovies.first().movie.id}")
            .fetchSemanticsNode().boundsInRoot
        val expected = with(density) { (railWidth + gutter).toPx() }
        assertTrue(
            "first card left ${firstCard.left} should sit at the content inset $expected",
            kotlin.math.abs(firstCard.left - expected) < 2f,
        )
    }

    @Test
    fun homeCarriesNoPaneHeaderAndNoDevelopmentBadge() {
        setShellContent()

        composeRule.onAllNodesWithText("Your media center starts here.").assertCountEquals(0)
        composeRule.onAllNodesWithText("Base app").assertCountEquals(0)
    }

    /**
     * The header's title used to be the only live region telling TalkBack the destination had
     * changed. It is gone, so the pane title has to carry that or activating a nav row becomes
     * silent — a regression no layout assertion would catch.
     */
    @Test
    fun theDestinationChangeIsStillAnnouncedWithoutAHeader() {
        setShellContent()

        composeRule.onNodeWithTag("content_pane")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Home"))

        composeRule.onNodeWithContentDescription("Movies").performClick()

        composeRule.onNodeWithTag("content_pane")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Movies"))
    }

    private companion object {
        const val VIEWPORT_TAG = "tv-viewport"
        val VIEWPORT_WIDTH = 960.dp
        val VIEWPORT_HEIGHT = 540.dp
    }
}
