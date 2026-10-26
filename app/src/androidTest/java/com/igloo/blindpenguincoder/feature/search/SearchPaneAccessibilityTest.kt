package com.igloo.blindpenguincoder.feature.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.AnimationScaleRule
import com.igloo.blindpenguincoder.TestIglooApp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.feature.music.TrackLikesUiState
import com.igloo.blindpenguincoder.feature.shared.AppendState
import com.igloo.blindpenguincoder.feature.shared.PagedState
import com.igloo.blindpenguincoder.testSearchState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the Search pane says out loud (design-system.md sections 11.6 and 12): the pane is
 * named, the field carries the whole prompt, the count line reports each search's shape once
 * as a polite live region, a rail's title is spoken on the card focus enters it on and on no
 * other, See-all names what it opens, the tracks heading folds into the first row, the
 * decorative hint is silent, and a failed like reaches the header's notice.
 */
@RunWith(AndroidJUnit4::class)
class SearchPaneAccessibilityTest {

    @get:Rule(order = 0)
    val animationScale = AnimationScaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private var searchState by mutableStateOf(testSearchState())
    private var likes by mutableStateOf(TrackLikesUiState(likedIds = emptySet()))

    private fun setContent(initial: SearchUiState = testSearchState()) {
        searchState = initial
        likes = TrackLikesUiState(likedIds = emptySet())
        composeRule.setContent {
            IglooTheme {
                TestIglooApp(
                    search = searchState,
                    trackLikes = likes,
                    onAlbumSelected = {},
                    onMusicianSelected = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Search").performClick()
        composeRule.waitForIdle()
    }

    private fun node(tag: String) = composeRule.onNodeWithTag(tag)

    @Test
    fun thePaneIsNamedSearch() {
        setContent()

        node("content_pane")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Search"))
    }

    @Test
    fun theFieldCarriesTheWholePromptAndTheHintIsSilent() {
        setContent(SearchUiState())

        node("search_field")
            .assertContentDescriptionEquals("Search movies, TV shows, albums, musicians and tracks")
        node("search_hint")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
        node("search_count").assertContentDescriptionEquals("Find anything in your library")
    }

    @Test
    fun theCountReportsEachSearchsShapeOnceAsAPoliteLiveRegion() {
        setContent(testSearchState(all = PagedState(IglooRailState.Loading)))

        // The wait keeps the line it replaced, so a search is announced once, when it settles.
        node("search_count")
            .assertContentDescriptionEquals("Find anything in your library")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))

        searchState = testSearchState()
        composeRule.waitForIdle()
        node("search_count").assertContentDescriptionEquals(
            "29 results for help: 12 movies, 2 TV shows, 5 albums, 3 musicians, 7 tracks.",
        )

        searchState = testSearchState(tab = SearchTab.Movies)
        composeRule.waitForIdle()
        node("search_count").assertContentDescriptionEquals("Showing 40 of 40 movies")

        searchState = testSearchState(
            tab = SearchTab.Shows,
            shows = PagedState(IglooRailState.Loaded(emptyList()), total = 0, append = AppendState.End),
        )
        composeRule.waitForIdle()
        // Not the empty box's own sentence, which is the node focus lands on.
        node("search_count").assertContentDescriptionEquals("0 TV shows")

        searchState = testSearchState(tab = SearchTab.Movies, movies = PagedState(IglooRailState.Loading))
        composeRule.waitForIdle()
        node("search_count").assertContentDescriptionEquals("0 TV shows")
        // The visible "Searching…" is another node: TalkBack re-reads a live region whose text
        // or layout changes, which on the Shield spoke the previous count on every tab switch.
        node("search_count").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Text))
        composeRule.onNodeWithText("Searching…").assertExists()
    }

    @Test
    fun aRailsTitleIsSpokenOnTheCardFocusEntersItOnAndNoOther() {
        setContent()

        node("poster_card_1").assertIsFocused()
        node("poster_card_1").assertContentDescriptionEquals("Movies (12). Movie 1, 1981")
        node("poster_card_1").performKeyInput { pressKey(Key.DirectionRight) }
        node("poster_card_2").assertIsFocused()
        node("poster_card_2").assertContentDescriptionEquals("Movie 2, 1982")

        node("show_card_1").requestFocus()
        node("show_card_1").assertContentDescriptionEquals("TV Shows (2). Show 1, 2001")
    }

    @Test
    fun seeAllNamesWhatItOpens() {
        setContent()

        node("search_see_all_movies").requestFocus()
        node("search_see_all_movies")
            .assertContentDescriptionEquals("See all 12 movies")
            .assertHasClickAction()
        node("search_see_all_tracks").assertContentDescriptionEquals("See all 7 tracks")
    }

    @Test
    fun theTracksHeadingFoldsIntoTheFirstRowOnly() {
        setContent()

        node("track_play_901").assertContentDescriptionEquals(
            "Tracks (7). 1999. The Beatles · Help!. 2 minutes and 5 seconds.",
        )
        node("track_play_902").assertContentDescriptionEquals(
            "Abbey Road. The Beatles · Help!. 2 minutes and 5 seconds.",
        )
    }

    @Test
    fun theTracksTabRowsHaveNoLetterToFold() {
        setContent(testSearchState(tab = SearchTab.Tracks))

        node("track_play_901").assertContentDescriptionEquals(
            "1999. The Beatles · Help!. 2 minutes and 5 seconds.",
        )
    }

    @Test
    fun theCardlessStatesSpeakTheQuery() {
        setContent(testSearchState(all = PagedState(IglooRailState.Error("Server down."))))

        composeRule.onNodeWithContentDescription("Retry the search").assertIsFocused()
        node("search_count").assertContentDescriptionEquals("Search for help failed")
    }

    @Test
    fun aFailedLikeReachesTheHeadersNotice() {
        setContent()

        likes = TrackLikesUiState(likedIds = emptySet(), notice = "Couldn't update like status: Server down.")
        composeRule.waitForIdle()

        node("search_notice").assertExists()
    }

    @Test
    fun clearAndTheTabsNameThemselves() {
        setContent()

        node("search_clear").assertContentDescriptionEquals("Clear the search")
        node("search_tab_all").assertContentDescriptionEquals("All results")
        node("search_tab_shows").assertContentDescriptionEquals("TV shows")
    }
}
