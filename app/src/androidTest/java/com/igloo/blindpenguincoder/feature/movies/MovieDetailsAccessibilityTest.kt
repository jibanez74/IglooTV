package com.igloo.blindpenguincoder.feature.movies

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.AnimationScaleRule
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.testMovieDetails
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the details screen says to TalkBack (design-system.md section 12): the metadata chips
 * are one announcement rather than eight, the toggles carry state and an action label that
 * matches what pressing them does, and nothing announces an action it cannot perform.
 */
@RunWith(AndroidJUnit4::class)
class MovieDetailsAccessibilityTest {

    @get:Rule(order = 0)
    val animationScale = AnimationScaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private var state by mutableStateOf<MovieDetailsState>(
        MovieDetailsState.Loaded(testMovieDetails()),
    )
    private var watchedToggles = 0
    private var likeToggles = 0
    private var retries = 0
    private var plays = 0

    private fun setContent(initial: MovieDetailsState = MovieDetailsState.Loaded(testMovieDetails())) {
        state = initial
        watchedToggles = 0
        likeToggles = 0
        retries = 0
        plays = 0
        composeRule.setContent {
            IglooTheme {
                MovieDetailsScreen(
                    state = state,
                    actions = MovieDetailsActions(
                        onPlay = { plays += 1 },
                        onToggleWatched = { watchedToggles += 1 },
                        onToggleLike = { likeToggles += 1 },
                        onRetry = { retries += 1 },
                    ),
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun theMetadataChipsAreOneAnnouncement() {
        setContent()

        // Eight consecutive two-character announcements would be noise; the row speaks the one
        // sentence the view model composed, with the abbreviations spelled out.
        composeRule
            .onNodeWithContentDescription(
                "Rated 8.2 out of 10, R, 4K, HDR10, 5.1 surround sound, subtitles available, " +
                    "2 hours 50 minutes, released December 15, 1995",
            )
            .assertExists()
        // The chips' own texts are not separate nodes.
        composeRule.onAllNodesWithText("4K").assertCountEquals(0)
    }

    @Test
    fun theWatchedToggleCarriesItsStateAndTheActionItWouldPerform() {
        setContent()

        val watched = composeRule.onNodeWithTag("details_watched")
        watched
            .assertContentDescriptionEquals("Watched")
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Not watched"),
            )
            .assertHasClickAction()
        assertEquals(
            "Mark as watched",
            watched.clickActionLabel(),
        )

        state = MovieDetailsState.Loaded(testMovieDetails(watched = true))
        composeRule.waitForIdle()

        watched.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Marked as watched"),
        )
        assertEquals(
            "Remove from watched",
            watched.clickActionLabel(),
        )
    }

    @Test
    fun theLikeToggleCarriesItsStateAndTheActionItWouldPerform() {
        setContent()

        val like = composeRule.onNodeWithTag("details_like")
        like.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Not liked"))

        state = MovieDetailsState.Loaded(testMovieDetails(liked = true))
        composeRule.waitForIdle()

        like.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Liked"))
    }

    @Test
    fun moreIsLabelledButAnnouncesNoActionUntilItsMenuExists() {
        setContent()

        composeRule.onNodeWithTag("details_more")
            .assertContentDescriptionEquals("More options")
            .assertHasNoClickAction()
    }

    @Test
    fun castCardsAnnounceNameAndCharacterWithNoAction() {
        setContent()

        composeRule.onNodeWithTag("cast_card_101")
            .assertContentDescriptionEquals("Al Pacino, Vincent Hanna")
            .assertHasNoClickAction()
    }

    @Test
    fun theAboutBlockIsOneNodeCarryingEveryRow() {
        setContent()

        composeRule.onNodeWithTag("details_about")
            .assertContentDescriptionEquals(
                "About Heat. Production: Regency Enterprises. Original language: EN. " +
                    "Budget: $60,000,000. Revenue: $187,436,818",
            )
            .assertHasNoClickAction()
    }

    @Test
    fun sectionTitlesAreHeadings() {
        setContent()

        composeRule.onNode(hasText("Overview") and isHeading()).assertExists()
        composeRule.onNode(hasText("Cast") and isHeading()).assertExists()
        composeRule.onNode(hasText("Heat") and isHeading()).assertExists()
    }

    @Test
    fun theOverviewIsClampedVisuallyButNotForTalkBack() {
        val long = "A ".repeat(400) + "end."
        setContent(
            MovieDetailsState.Loaded(testMovieDetails().copy(overview = long)),
        )

        // One text node carrying the whole string: the six-line clamp is a visual limit only
        // (section 4.1), so a screen reader still reaches the last sentence.
        composeRule.onNodeWithText(long).assertExists()
    }

    @Test
    fun theLoadingStateAnnouncesItselfAndHoldsFocus() {
        setContent(MovieDetailsState.Loading)

        composeRule.onNodeWithContentDescription("Loading movie details")
            .assertIsFocused()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
    }

    @Test
    fun theErrorStateOffersAFocusedRetry() {
        setContent(MovieDetailsState.Error("Could not reach the server."))

        val retry = composeRule.onNodeWithContentDescription("Retry loading movie details")
        retry.assertIsFocused()
        composeRule.onNodeWithText("Could not reach the server.").assertExists()

        retry.performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(1, retries)
    }

    /**
     * The actions TalkBack offers have to do what they say: performing each announced action —
     * not a synthetic click on the node — is what proves the label and the behaviour agree.
     */
    @Test
    fun theAnnouncedActionsPerformTheirWork() {
        setContent()

        composeRule.onNodeWithTag("details_play").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithTag("details_watched").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithTag("details_like").performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(1, plays)
        assertEquals(1, watchedToggles)
        assertEquals(1, likeToggles)
    }

    private fun isHeading() = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    /** What TalkBack offers as "double tap to …" — it must match what the press actually does. */
    private fun SemanticsNodeInteraction.clickActionLabel(): String? =
        fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.label
}
