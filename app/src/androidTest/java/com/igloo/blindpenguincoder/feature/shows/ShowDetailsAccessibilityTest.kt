package com.igloo.blindpenguincoder.feature.shows

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.AnimationScaleRule
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.feature.shared.DetailsState
import com.igloo.blindpenguincoder.testSeason
import com.igloo.blindpenguincoder.testShowDetails
import com.igloo.blindpenguincoder.testShowDetailsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the show page says to TalkBack (design-system.md sections 11.4.3 and 12): the metadata
 * chips are one sentence, Play/Resume and the episode cards name their episode and offer the press
 * as a short action, the extra-video cards — which cannot act yet — announce none, the season
 * chips carry their selection and what pressing does, the hero's notice and Retry card speak
 * politely, and the prose a d-pad passes by is reachable as reading stops while a screen reader
 * runs.
 */
@RunWith(AndroidJUnit4::class)
class ShowDetailsAccessibilityTest {

    @get:Rule(order = 0)
    val animationScale = AnimationScaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private var state by mutableStateOf(testShowDetailsState())
    private var retries = 0
    private var seasonRetries = 0
    private val selectedSeasons = mutableListOf<Long>()
    private val pressed = mutableListOf<Pair<Long, EpisodeLaunchSite>>()
    private val watchedSets = mutableListOf<Pair<Long, Boolean>>()

    private fun setContent(
        initial: ShowDetailsUiState = testShowDetailsState(),
        progressSyncError: String? = null,
        // Explicit, never the ambient default: the Shield test device runs TalkBack, and these
        // cases pin the semantics that exist with and without the reading-stop chain.
        spokenAccessibilityEnabled: Boolean = false,
    ) {
        state = initial
        retries = 0
        seasonRetries = 0
        selectedSeasons.clear()
        pressed.clear()
        watchedSets.clear()
        composeRule.setContent {
            IglooTheme {
                ShowDetailsScreen(
                    state = state,
                    actions = ShowDetailsActions(
                        onRetry = { retries += 1 },
                        onSelectSeason = { selectedSeasons += it },
                        onRetrySeason = { seasonRetries += 1 },
                        onSetEpisodeWatched = { id, watched -> watchedSets += id to watched },
                    ),
                    onPlayEpisode = { episodeId, site -> pressed += episodeId to site },
                    playReturnRequester = remember { FocusRequester() },
                    episodeReturnRequester = remember { FocusRequester() },
                    progressSyncError = progressSyncError,
                    spokenAccessibilityEnabled = spokenAccessibilityEnabled,
                )
            }
        }
        composeRule.waitForIdle()
    }

    /** A pane-title change is spoken, so the loaded page names the show, not a generic frame. */
    @Test
    fun thePaneAnnouncesTheShowOnceLoaded() {
        setContent(testShowDetailsState(details = DetailsState.Loading))

        val pane = composeRule.onNodeWithTag("show_details")
        pane.assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Show details"))

        state = testShowDetailsState()
        composeRule.waitForIdle()

        pane.assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Severance"))
    }

    @Test
    fun theLoadingStateAnnouncesItselfAndHoldsFocus() {
        setContent(testShowDetailsState(details = DetailsState.Loading))

        composeRule.onNodeWithContentDescription("Loading show details")
            .assertIsFocused()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun theErrorStateOffersAFocusedRetry() {
        setContent(testShowDetailsState(details = DetailsState.Error("The server took too long.")))

        val retry = composeRule.onNodeWithContentDescription("Retry loading show details")
        retry.assertIsFocused().assertHasClickAction()
        retry.performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(1, retries)
    }

    @Test
    fun theMetadataChipsAreOneAnnouncement() {
        setContent()

        // A run of two-character announcements would be noise; the row speaks the one sentence
        // the view model composed.
        composeRule
            .onNodeWithContentDescription(
                "Rated 8.4 out of 10, TV-MA, Returning Series, 2 seasons, 5 episodes, " +
                    "aired 2022 to 2025",
            )
            .assertExists()
        composeRule.onAllNodesWithText("TV-MA").assertCountEquals(0)
    }

    /**
     * Play/Resume takes the entry focus, names its episode the way a screen reader can say it, and
     * offers the verb alone as its action — "double-tap to Resume", not the sentence again. The
     * press plays the hero's target, from the hero.
     */
    @Test
    fun theHeroActionNamesItsEpisodeAndOffersItsAction() {
        setContent()

        val play = composeRule.onNodeWithTag("details_play")
        play
            .assertIsFocused()
            .assertContentDescriptionEquals("Resume season 1, episode 2, Half Loop")
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        assertEquals("Resume", play.clickActionLabel())

        play.performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(listOf(902L to EpisodeLaunchSite.Hero), pressed)
    }

    /** A press that went nowhere is explained under the hero, politely, with no focus stop. */
    @Test
    fun aPlaybackNoticeIsAPoliteLiveRegionUnderTheHero() {
        val notice = "Couldn't prepare playback: no connection."
        setContent(testShowDetailsState().copy(playbackNotice = notice))

        composeRule.onNodeWithTag("details_playback_notice")
            .assert(hasText(notice))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Focused))
    }

    /** A failed save's card says why, politely, and its Retry names what it retries. */
    @Test
    fun theProgressRetryCardSpeaksPolitelyAndNamesItsAction() {
        setContent(progressSyncError = "Couldn't save playback progress: no connection.")

        // The message is the card's own text node, under the tagged card (the player's test
        // reads its chrome card the same way).
        composeRule
            .onAllNodesWithText("Couldn't save playback progress: no connection.", substring = true)
            .filterToOne(hasAnyAncestor(hasTestTag("details_progress_error")))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        composeRule.onNodeWithTag("details_progress_retry")
            .assertContentDescriptionEquals("Retry saving playback progress")
            .assertHasClickAction()
    }

    @Test
    fun theActionSlotHoldsAStubWhileItsSeasonLoadsAndNothingWhenItHasNothingToPlay() {
        setContent(testShowDetailsState(episodes = IglooRailState.Loading, playTarget = null))

        composeRule.onNodeWithTag("details_play").assertDoesNotExist()
        composeRule.onNodeWithTag("details_play_stub")
            .assertExists()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Focused))

        state = testShowDetailsState(episodes = IglooRailState.Loaded(emptyList()), playTarget = null)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("details_play").assertDoesNotExist()
        composeRule.onNodeWithTag("details_play_stub").assertDoesNotExist()
    }

    @Test
    fun seasonChipsAnnounceNameCountSelectionAndWhatPressingDoes() {
        setContent()

        val selected = composeRule.onNodeWithTag("show_season_1")
        selected
            .assertContentDescriptionEquals("Season 1, 3 episodes")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected"))
        assertEquals("Show Season 1", selected.clickActionLabel())

        val specials = composeRule.onNodeWithTag("show_season_0")
        specials
            .assertContentDescriptionEquals("Specials, 1 episode")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        assertEquals("Show Specials", specials.clickActionLabel())

        composeRule.onNodeWithTag("show_season_2").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(2L), selectedSeasons)
    }

    @Test
    fun aSingleSeasonShowHasNoChipRow() {
        setContent(
            testShowDetailsState(
                details = DetailsState.Loaded(testShowDetails(seasons = listOf(testSeason(1, 3)))),
            ),
        )

        composeRule.onNodeWithTag("show_season_row").assertDoesNotExist()
        composeRule.onNode(hasText("Seasons")).assertDoesNotExist()
        composeRule.onNode(hasText("Season 1") and isHeading()).assertExists()
    }

    /**
     * One node per card, speaking everything the card draws — and, entered from outside, the
     * rail's season first — with the press offered as "Play 2. Half Loop", which plays that
     * episode from the rail.
     */
    @Test
    fun episodeCardsSpeakOneSentenceAndPlayOnPress() {
        setContent()

        val card = composeRule.onNodeWithTag("episode_card_902")
        card
            .assertContentDescriptionEquals(
                "Season 1. Episode 2, Half Loop. 57 minutes, aired February 17, 2022. " +
                    "45 minutes remaining. Half Loop, the episode",
            )
            .assertHasClickAction()
        assertEquals("Play 2. Half Loop", card.clickActionLabel())
        card.performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(902L to EpisodeLaunchSite.EpisodesRail), pressed)
        composeRule.onNodeWithTag("episode_card_901")
            .assertContentDescriptionEquals(
                "Season 1. Episode 1, Good News About Hell. 57 minutes, aired February 17, 2022. " +
                    "Watched. Good News About Hell, the episode",
            )
        // The texts drawn under the still are inside the card's one node.
        composeRule.onAllNodesWithText("2. Half Loop").assertCountEquals(0)
    }

    @Test
    fun aLoadingFailedOrEmptySeasonNamesItsSeason() {
        setContent(testShowDetailsState(episodes = IglooRailState.Loading, playTarget = null))
        composeRule.onNodeWithContentDescription("Loading Season 1").assertExists()

        state = testShowDetailsState(episodes = IglooRailState.Error("Couldn't load episodes."), playTarget = null)
        composeRule.waitForIdle()
        val retry = composeRule.onNodeWithContentDescription("Retry loading Season 1")
        retry.assertHasClickAction()
        retry.performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, seasonRetries)

        state = testShowDetailsState(episodes = IglooRailState.Loaded(emptyList()), playTarget = null)
        composeRule.waitForIdle()
        composeRule.onNode(
            hasText("No episodes of Season 1 are in this library."),
            useUnmergedTree = true,
        ).assertExists()
    }

    @Test
    fun castCardsAnnounceNameCharacterAndEpisodesWithNoAction() {
        setContent()

        composeRule.onNodeWithTag("cast_card_101")
            .assertContentDescriptionEquals("Adam Scott, Mark Scout, 19 episodes")
            .assertHasNoClickAction()
    }

    /** The movie page's extras play; here the player is not wired yet, so they announce none. */
    @Test
    fun extraVideoCardsAnnounceNoActionOnTheShowPage() {
        setContent()

        composeRule.onNodeWithTag("extra_card_201")
            .assertContentDescriptionEquals("Official Trailer, Trailer")
            .assertHasNoClickAction()
    }

    @Test
    fun theKeyCrewStopListsTheCreators() {
        setContent(spokenAccessibilityEnabled = true)

        composeRule.onNodeWithTag("details_key_crew_stop")
            .assertContentDescriptionEquals("Key Crew. Creator: Dan Erickson")
    }

    @Test
    fun aShowWithoutCreatorsHasNoKeyCrew() {
        setContent(
            testShowDetailsState(details = DetailsState.Loaded(testShowDetails(keyCrew = emptyList()))),
            spokenAccessibilityEnabled = true,
        )

        composeRule.onNode(hasText("Key Crew")).assertDoesNotExist()
        composeRule.onNodeWithTag("details_key_crew_stop").assertDoesNotExist()
    }

    @Test
    fun theAboutBlockIsOneNodeCarryingEveryRow() {
        setContent()

        composeRule.onNodeWithTag("details_about")
            .assertContentDescriptionEquals(
                "About Severance. Network: Apple TV+. Status: Returning Series. " +
                    "First aired: February 17, 2022",
            )
            .assertHasNoClickAction()
    }

    @Test
    fun theReadingStopsSpeakTheProseAScreenReaderCannotOtherwiseReach() {
        setContent(spokenAccessibilityEnabled = true)

        composeRule.onNodeWithTag("details_hero_info")
            .assertContentDescriptionEquals(
                "Severance. Please enjoy each fact equally. Rated 8.4 out of 10, TV-MA, " +
                    "Returning Series, 2 seasons, 5 episodes, aired 2022 to 2025. Drama, Mystery",
            )
        composeRule.onNodeWithTag("details_overview_stop")
            .assertContentDescriptionEquals(
                "Overview. Mark leads a team of office workers whose memories have been split.",
            )
    }

    @Test
    fun theReadingStopsStayOutOfTheTreeWithoutASpokenScreenReader() {
        setContent(spokenAccessibilityEnabled = false)

        composeRule.onNodeWithTag("details_hero_info").assertDoesNotExist()
        composeRule.onNodeWithTag("details_overview_stop").assertDoesNotExist()
        composeRule.onNodeWithTag("details_key_crew_stop").assertDoesNotExist()
    }

    @Test
    fun sectionTitlesAreHeadings() {
        setContent()

        listOf(
            "Severance",
            "Overview",
            "Seasons",
            "Season 1",
            "Key Crew",
            "Cast",
            "Extra Videos",
            // Sits above the focusable panel, whose cleared semantics would otherwise erase it.
            "About Severance",
        ).forEach { title -> composeRule.onNode(hasText(title) and isHeading()).assertExists() }
    }

    /**
     * Section 6.3: a live region on a focus target makes TalkBack re-read it whenever focus comes
     * or goes. On a loaded page nothing carries one.
     */
    @Test
    fun noLoadedNodeCarriesALiveRegion() {
        setContent(spokenAccessibilityEnabled = true)

        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
            .assertCountEquals(0)
    }

    private fun isHeading() = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    /** What TalkBack offers as "double tap to …" — it must match what the press actually does. */
    private fun SemanticsNodeInteraction.clickActionLabel(): String? =
        fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.label

    /** Entry focus on Play/Resume must not scroll the hero away (section 11.4.1, Focus). */
    @Test
    fun theHeroStaysWholeWhenPlayTakesEntryFocus() {
        setContent()

        composeRule.onNodeWithTag("details_play").assertIsFocused()
        composeRule.onNodeWithTag("details_hero").assertTopPositionInRootIsEqualTo(0.dp)
    }

    // --- mark watched ---

    private fun SemanticsNodeInteraction.customActionLabels(): List<String> =
        fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }

    /** Runs the card's one custom action, as TalkBack's actions menu would. */
    private fun SemanticsNodeInteraction.performCustomAction(label: String) {
        val action = fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == label }
        composeRule.runOnUiThread { action.action() }
        composeRule.waitForIdle()
    }

    @Test
    fun episodeCardsOfferMarkWatchedAsACustomAction() {
        setContent()

        val unwatched = composeRule.onNodeWithTag("episode_card_902")
        val watched = composeRule.onNodeWithTag("episode_card_901")
        assertEquals(listOf("Mark watched"), unwatched.customActionLabels())
        assertEquals(listOf("Mark unwatched"), watched.customActionLabels())
        // The press still plays; the custom action is the second choice, not a replacement.
        assertEquals("Play 2. Half Loop", unwatched.clickActionLabel())

        unwatched.performCustomAction("Mark watched")
        watched.performCustomAction("Mark unwatched")

        assertEquals(listOf(902L to true, 901L to false), watchedSets)
        assertTrue(pressed.isEmpty())
    }

    @Test
    fun theEpisodeMenuNamesItselfAndClearsThePageBehindIt() {
        setContent()
        val card = composeRule.onNodeWithTag("episode_card_903")
        card.requestFocus()

        card.performKeyInput { keyDown(Key.DirectionCenter) }
        composeRule.waitUntil(timeoutMillis = 3_000) {
            composeRule.onAllNodesWithTag("more_menu").fetchSemanticsNodes().isNotEmpty()
        }
        // The card has left the tree under the menu; the release lands on the focused row.
        composeRule.onRoot().performKeyInput { keyUp(Key.DirectionCenter) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("more_menu")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Episode options"))
        composeRule.onNodeWithTag("more_menu_item_0").assertIsFocused().assertContentDescriptionEquals("Mark watched")
        // The body is cleared under the menu; the page's own pane stays named.
        composeRule.onNodeWithTag("episode_card_903").assertDoesNotExist()
        composeRule.onNodeWithTag("show_details")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Severance"))

        composeRule.onNodeWithTag("more_menu_item_0").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        assertEquals(listOf(903L to true), watchedSets)
        composeRule.onNodeWithTag("more_menu").assertDoesNotExist()
        composeRule.onNodeWithTag("episode_card_903").assertIsFocused()
        assertTrue(pressed.isEmpty())
    }

    @Test
    fun theWatchedNoticeSpeaksPolitelyUnderTheRail() {
        setContent(testShowDetailsState(watchedNotice = "Couldn't update watched status: Offline."))

        composeRule.onNodeWithTag("episode_watched_notice")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .assert(hasText("Couldn't update watched status: Offline."))
    }
}
