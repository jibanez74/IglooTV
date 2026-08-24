package com.igloo.blindpenguincoder.feature.movies

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
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
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.text.TextLayoutResult
import com.igloo.blindpenguincoder.AnimationScaleRule
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.requestFocusSafely
import com.igloo.blindpenguincoder.testMovieDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    private var moreMenuOpen by mutableStateOf(false)
    private val moreRequester = FocusRequester()
    private var watchedToggles = 0
    private var likeToggles = 0
    private var retries = 0
    private var plays = 0
    private val playedExtras = mutableListOf<Long>()
    private val menuSelections = mutableListOf<String>()

    private fun setContent(
        initial: MovieDetailsState = MovieDetailsState.Loaded(testMovieDetails()),
        mutationNotice: String? = null,
        isAdmin: Boolean = false,
        // Explicit, never the ambient default: the Shield test device runs TalkBack, and these
        // cases pin the semantics that exist with and without the reading-stop chain.
        spokenAccessibilityEnabled: Boolean = false,
    ) {
        state = initial
        moreMenuOpen = false
        watchedToggles = 0
        likeToggles = 0
        retries = 0
        plays = 0
        playedExtras.clear()
        menuSelections.clear()
        composeRule.setContent {
            IglooTheme {
                MovieDetailsScreen(
                    state = state,
                    mutationNotice = mutationNotice,
                    actions = MovieDetailsActions.Library(
                        onToggleWatched = { watchedToggles += 1 },
                        onToggleLike = { likeToggles += 1 },
                        onWatchTogether = { menuSelections += "Watch Together" },
                        onTechnicalDetails = { menuSelections += "Technical Details" },
                        onIdentifyMovie = { menuSelections += "Identify Movie" },
                        onDeleteMovie = { menuSelections += "Delete Movie" },
                        onSelectPlaybackMode = {},
                        onSelectAudioTrack = {},
                        onSelectSubtitle = {},
                        onRetry = { retries += 1 },
                    ),
                    isAdmin = isAdmin,
                    onPlay = { plays += 1 },
                    playReturnRequester = remember { FocusRequester() },
                    onPlayVideo = { video, _ -> playedExtras += video.id },
                    extrasReturnRequester = remember { FocusRequester() },
                    heroTrailerReturnRequester = remember { FocusRequester() },
                    moreMenuOpen = moreMenuOpen,
                    onOpenMoreMenu = { moreMenuOpen = true },
                    onDismissMoreMenu = {
                        moreMenuOpen = false
                        moreRequester.requestFocusSafely()
                    },
                    moreRequester = moreRequester,
                    // The dialog stays closed here — its open/dismiss behavior has its own
                    // suite; this host only proves the menu item still announces and fires.
                    playbackSettingsOpen = false,
                    onOpenPlaybackSettings = { menuSelections += "Playback Settings" },
                    onDismissPlaybackSettings = {},
                    spokenAccessibilityEnabled = spokenAccessibilityEnabled,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun openMenu() {
        composeRule.onNodeWithTag("details_more").performSemanticsAction(SemanticsActions.OnClick)
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
                    "2 hours and 50 minutes, released December 15, 1995",
            )
            .assertExists()
        // The chips' own texts are not separate nodes.
        composeRule.onAllNodesWithText("4K").assertCountEquals(0)
    }

    @Test
    fun theWatchedToggleCarriesItsStateAndTheActionItWouldPerform() {
        setContent()

        // The announced label is the visible one — a constant "Watched" against a "Not watched"
        // state read as a contradiction under TalkBack.
        val watched = composeRule.onNodeWithTag("details_watched")
        watched
            .assertContentDescriptionEquals("Watch")
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

        watched
            .assertContentDescriptionEquals("Watched")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Marked as watched",
                ),
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
    fun likeHasNoActionUntilItsStatusIsKnown() {
        setContent(MovieDetailsState.Loaded(testMovieDetails(liked = null)))

        val like = composeRule.onNodeWithTag("details_like")
        like
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
            .assertHasNoClickAction()

        state = MovieDetailsState.Loaded(testMovieDetails(liked = false))
        composeRule.waitForIdle()

        like
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Disabled))
            .assertHasClickAction()
    }

    @Test
    fun mutationNoticeIsPoliteNonActionableAndDoesNotTakeFocus() {
        setContent(mutationNotice = "Couldn't update like status: backend refused it")

        composeRule.onNodeWithTag("details_mutation_notice")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
            .assertHasNoClickAction()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Focused))
        composeRule.onNodeWithTag("details_play").assertIsFocused()
    }

    @Test
    fun castCardsAnnounceNameAndCharacterWithNoAction() {
        setContent()

        composeRule.onNodeWithTag("cast_card_101")
            .assertContentDescriptionEquals("Al Pacino, Vincent Hanna")
            .assertHasNoClickAction()
    }

    /**
     * The card keeps its one merged announcement, and its action says what pressing does: it
     * plays a video, so the label is "Play …" rather than the poster default "Open …" — and
     * performing the announced action, not a synthetic click, proves label and behaviour agree.
     */
    @Test
    fun extraVideoCardsAnnouncePlayAndPerformIt() {
        setContent()

        val card = composeRule.onNodeWithTag("extra_card_201")
        card
            .assertContentDescriptionEquals("Official Trailer, Trailer")
            .assertHasClickAction()
        assertEquals("Play Official Trailer", card.clickActionLabel())

        card.performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(listOf(201L), playedExtras)
    }

    @Test
    fun theAboutBlockIsOneNodeCarryingEveryRow() {
        setContent()

        // The heading is folded in: its own text node sits above for the eye, but TV TalkBack
        // follows input focus and never lands on plain text.
        composeRule.onNodeWithTag("details_about")
            .assertContentDescriptionEquals(
                "About Heat. Production: Regency Enterprises. Original language: English. " +
                    "Budget: $60,000,000. Revenue: $187,436,818",
            )
            .assertHasNoClickAction()
    }

    @Test
    fun sectionTitlesAreHeadings() {
        setContent()

        composeRule.onNode(hasText("Overview") and isHeading()).assertExists()
        composeRule.onNode(hasText("Key Crew") and isHeading()).assertExists()
        composeRule.onNode(hasText("Cast") and isHeading()).assertExists()
        composeRule.onNode(hasText("Extra Videos") and isHeading()).assertExists()
        // Sits above the focusable panel, whose cleared semantics would otherwise erase it.
        composeRule.onNode(hasText("About Heat") and isHeading()).assertExists()
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

    /**
     * The clamp's fade draws off `hasVisualOverflow`, and drawing creates no semantics nodes —
     * so the trigger is what a test can pin: armed by an overview six lines cannot hold, and
     * not by one that fits (a fade over complete prose would claim text that is not there).
     */
    @Test
    fun theOverviewFadeArmsOnlyWhenTheTextOverflows() {
        val long = "A ".repeat(400) + "end."
        setContent(MovieDetailsState.Loaded(testMovieDetails().copy(overview = long)))

        assertTrue("six lines cannot hold this overview", overviewLayout(long).hasVisualOverflow)

        state = MovieDetailsState.Loaded(testMovieDetails())
        composeRule.waitForIdle()

        val short = testMovieDetails().overview!!
        assertFalse("a fitting overview must not clamp", overviewLayout(short).hasVisualOverflow)
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

    @Test
    fun theMoreTriggerAnnouncesMoreOptionsAsAButton() {
        setContent()

        val more = composeRule.onNodeWithTag("details_more")
        more
            .assertContentDescriptionEquals("More options")
            .assertHasClickAction()
        assertEquals("More options", more.clickActionLabel())
    }

    @Test
    fun theMenuCarriesAPaneTitleAndOneNodePerItem() {
        setContent()
        openMenu()

        composeRule.onNodeWithTag("more_menu")
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "More options"),
            )
        listOf("Playback Settings", "Watch Together", "Technical Details")
            .forEachIndexed { index, label ->
                val item = composeRule.onNodeWithTag("more_menu_item_$index")
                item.assertContentDescriptionEquals(label).assertHasClickAction()
                // The announced action is the label itself: pressing does what it says.
                assertEquals(label, item.clickActionLabel())
            }
    }

    @Test
    fun theAnnouncedMenuActionPerformsItsWorkAndClosesTheMenu() {
        setContent()
        openMenu()

        composeRule.onNodeWithTag("more_menu_item_0")
            .performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(listOf("Playback Settings"), menuSelections)
        composeRule.onNodeWithTag("more_menu").assertDoesNotExist()
    }

    @Test
    fun theScreenBehindTheMenuLeavesTalkBackTraversal() {
        setContent()
        val body = composeRule.onNodeWithTag("details_body")
        body.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.HideFromAccessibility))

        openMenu()

        // Hidden from traversal but still in the tree, so the assertion is meaningful — and the
        // menu's own items are outside the hidden subtree.
        body.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
        composeRule.onNodeWithTag("more_menu_item_0")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.HideFromAccessibility))
    }

    @Test
    fun theSeparatorIsSilent() {
        setContent(isAdmin = true)
        openMenu()

        // Five announcements and nothing else: the hairline before Delete has no semantics of
        // its own, so TalkBack walks Technical Details straight into Identify Movie.
        composeRule.onNodeWithTag("more_menu_item_4")
            .assertContentDescriptionEquals("Delete Movie")
        composeRule
            .onAllNodes(
                hasAnyAncestor(hasTestTag("more_menu")) and
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription),
            )
            .assertCountEquals(5)
    }

    /**
     * TV TalkBack follows input focus and never traverses plain text, so with a spoken screen
     * reader running the hero prose, the Overview, and Key Crew become reading stops — one
     * cleared announcement each, with the section heading folded in, and no action to promise.
     */
    @Test
    fun theReadingStopsSpeakTheProseAScreenReaderCannotOtherwiseReach() {
        setContent(spokenAccessibilityEnabled = true)

        composeRule.onNodeWithTag("details_hero_info")
            .assertContentDescriptionEquals(
                "Heat. A Los Angeles crime saga. Rated 8.2 out of 10, R, 4K, HDR10, " +
                    "5.1 surround sound, subtitles available, 2 hours and 50 minutes, " +
                    "released December 15, 1995. Crime, Drama",
            )
            .assertHasNoClickAction()
        composeRule.onNodeWithTag("details_overview_stop")
            .assertContentDescriptionEquals(
                "Overview. Obsessive master thief Neil McCauley leads a top-notch crew.",
            )
            .assertHasNoClickAction()
        composeRule.onNodeWithTag("details_key_crew_stop")
            .assertContentDescriptionEquals("Key Crew. Director: Michael Mann")
            .assertHasNoClickAction()
    }

    @Test
    fun theReadingStopsStayOutOfTheTreeWithoutASpokenScreenReader() {
        setContent()

        composeRule.onNodeWithTag("details_hero_info").assertDoesNotExist()
        composeRule.onNodeWithTag("details_overview_stop").assertDoesNotExist()
        composeRule.onNodeWithTag("details_key_crew_stop").assertDoesNotExist()
    }

    /**
     * The resume caption under Play is plain text a TV screen reader can never reach, so Play's
     * own label says where it resumes from — pressing it is what resumes. No state description
     * rides along, or the position would be announced twice in two unrelated sentences.
     */
    @Test
    fun playSaysWhereItResumesFromWhileThereIsProgress() {
        setContent()

        composeRule.onNodeWithTag("details_play")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf("Play from 42 minutes"),
                ),
            )
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))

        state = MovieDetailsState.Loaded(testMovieDetails(progress = null))
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("details_play")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf("Play Heat"),
                ),
            )
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    /** A pane-title change is spoken, so the loaded page names the film, not a generic frame. */
    @Test
    fun thePaneAnnouncesTheMovieOnceLoaded() {
        setContent(MovieDetailsState.Loading)

        val pane = composeRule.onNodeWithTag("movie_details")
        pane.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Movie details"),
        )

        state = MovieDetailsState.Loaded(testMovieDetails())
        composeRule.waitForIdle()

        pane.assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Heat"))
    }

    private fun isHeading() = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    private fun overviewLayout(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(text)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.first()
    }

    /** What TalkBack offers as "double tap to …" — it must match what the press actually does. */
    private fun SemanticsNodeInteraction.clickActionLabel(): String? =
        fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.label
}
