package com.igloo.blindpenguincoder.feature.search

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.AnimationScaleRule
import com.igloo.blindpenguincoder.TestIglooApp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.feature.home.findActivity
import com.igloo.blindpenguincoder.feature.movies.MovieDetailsUiState
import com.igloo.blindpenguincoder.feature.music.AlbumDetailsUiState
import com.igloo.blindpenguincoder.feature.shared.DetailsState
import com.igloo.blindpenguincoder.feature.shared.PagedState
import com.igloo.blindpenguincoder.playback.media3.FakeMusicPlayerEngine
import com.igloo.blindpenguincoder.playback.model.MusicPlayRequest
import com.igloo.blindpenguincoder.playback.model.MusicQueueSource
import com.igloo.blindpenguincoder.playback.queue.toMusicPlayTrack
import com.igloo.blindpenguincoder.testAlbumDetails
import com.igloo.blindpenguincoder.testLibraryTracks
import com.igloo.blindpenguincoder.testMovieDetails
import com.igloo.blindpenguincoder.testSearchState
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Search pane's focus contract (design-system.md sections 6.3 and 11.6): with nothing typed
 * the field is the pane's one target; with results the entry rides the selected tab's remembered
 * item with the strip and the field above it; the left column exits to the spine and the right
 * edge is pinned; a tab selects on focus without stealing it; See-all lands on the category's
 * results; Clear hands focus back to the field; an overlay's Back and a player's close land on
 * the item that led away; and a Home round trip keeps the tab and the item.
 */
@RunWith(AndroidJUnit4::class)
class SearchPaneBehaviorTest {

    @get:Rule(order = 0)
    val animationScale = AnimationScaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private var searchState by mutableStateOf(testSearchState())
    private var detailsState by mutableStateOf(MovieDetailsUiState())
    private var albumDetailsState by mutableStateOf(AlbumDetailsUiState())
    private val queries = mutableListOf<String>()
    private val selectedTabs = mutableListOf<SearchTab>()
    private val pressedTabs = mutableListOf<SearchTab>()
    private val playedTracks = mutableListOf<Long>()
    private var searchNowCalls = 0
    private var clearCalls = 0
    private var retries = 0
    private val playRequests = MutableSharedFlow<MusicPlayRequest>(extraBufferCapacity = 1)
    private var hostActivity: Activity? = null

    private fun setContent(initial: SearchUiState = testSearchState()) {
        searchState = initial
        detailsState = MovieDetailsUiState()
        albumDetailsState = AlbumDetailsUiState()
        queries.clear()
        selectedTabs.clear()
        pressedTabs.clear()
        playedTracks.clear()
        searchNowCalls = 0
        clearCalls = 0
        retries = 0
        composeRule.setContent {
            val context = LocalContext.current
            SideEffect { hostActivity = context.findActivity() }
            IglooTheme {
                TestIglooApp(
                    search = searchState,
                    searchActions = SearchActions(
                        onQueryChange = { queries += it },
                        onSearchNow = { searchNowCalls += 1 },
                        onClear = {
                            clearCalls += 1
                            searchState = SearchUiState()
                        },
                        onSelectTab = { tab ->
                            selectedTabs += tab
                            searchState = searchState.copy(tab = tab)
                        },
                        onPressTab = { tab ->
                            pressedTabs += tab
                            searchState = searchState.copy(tab = tab)
                        },
                        onRetryFirstPage = { retries += 1 },
                        onRetryAppend = {},
                        onLoadMore = {},
                        onPlayTrack = { id ->
                            playedTracks += id
                            playRequests.tryEmit(
                                MusicPlayRequest(
                                    source = MusicQueueSource.TrackList,
                                    startIndex = 0,
                                    tracks = testLibraryTracks.map { it.toMusicPlayTrack() },
                                ),
                            )
                        },
                        onToggleLike = {},
                    ),
                    searchPlayRequests = playRequests,
                    details = detailsState,
                    onMovieSelected = { movieId ->
                        detailsState = MovieDetailsUiState(
                            openMovieId = movieId,
                            details = DetailsState.Loaded(testMovieDetails(id = movieId)),
                        )
                    },
                    albumDetails = albumDetailsState,
                    onAlbumSelected = { albumId ->
                        albumDetailsState = AlbumDetailsUiState(
                            openAlbumId = albumId,
                            details = DetailsState.Loaded(testAlbumDetails(id = albumId)),
                        )
                    },
                    onMusicianSelected = {},
                    onShowSelected = {},
                    onCloseDetails = {
                        detailsState = MovieDetailsUiState()
                        albumDetailsState = AlbumDetailsUiState()
                    },
                    musicPlayerEngineFactory = { _, _ -> FakeMusicPlayerEngine() },
                )
            }
        }
        composeRule.waitForIdle()
        openSearch()
    }

    private fun openSearch() {
        composeRule.onNodeWithContentDescription("Search").performClick()
        composeRule.waitForIdle()
    }

    private fun pressBack() {
        composeRule.runOnUiThread {
            (checkNotNull(hostActivity) as ComponentActivity).onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
    }

    private fun node(tag: String) = composeRule.onNodeWithTag(tag)

    // --- the field with nothing typed ---

    @Test
    fun withNothingTypedTheFieldIsThePanesOnlyTarget() {
        setContent(SearchUiState())

        node("search_field").assertIsFocused()
        node("search_tabs").assertDoesNotExist()
        node("search_clear").assertDoesNotExist()
        node("search_hint").assertExists()

        node("search_field").performKeyInput { pressKey(Key.DirectionDown) }
        node("search_field").assertIsFocused()
        node("search_field").performKeyInput { pressKey(Key.DirectionUp) }
        node("search_field").assertIsFocused()
    }

    @Test
    fun anEmptyFieldExitsLeftToTheSpine() {
        setContent(SearchUiState())

        node("search_field").performKeyInput { pressKey(Key.DirectionLeft) }

        composeRule.onNodeWithContentDescription("Search").assertIsFocused()
    }

    @Test
    fun typingReportsEveryKeystroke() {
        setContent(SearchUiState())

        node("search_field").performTextInput("he")

        assertEquals("he", queries.last())
    }

    // --- the chain with results ---

    @Test
    fun enteringThePaneWithResultsLandsOnTheFirstSectionsFirstCard() {
        setContent()

        node("poster_card_1").assertIsFocused()
        node("search_tabs").assertExists()
        node("search_clear").assertExists()
    }

    @Test
    fun theFieldTheStripAndTheResultsAreOneVerticalChain() {
        setContent()

        node("poster_card_1").performKeyInput { pressKey(Key.DirectionUp) }
        node("search_tab_all").assertIsFocused()
        node("search_tab_all").performKeyInput { pressKey(Key.DirectionUp) }
        node("search_field").assertIsFocused()
        node("search_field").performKeyInput { pressKey(Key.DirectionUp) }
        node("search_clear").assertIsFocused()
        node("search_clear").performKeyInput { pressKey(Key.DirectionUp) }
        node("search_clear").assertIsFocused()

        node("search_clear").performKeyInput { pressKey(Key.DirectionDown) }
        node("search_field").assertIsFocused()
        node("search_field").performKeyInput { pressKey(Key.DirectionDown) }
        node("search_tab_all").assertIsFocused()
        node("search_tab_all").performKeyInput { pressKey(Key.DirectionDown) }
        node("poster_card_1").assertIsFocused()
    }

    @Test
    fun theLeftColumnExitsToTheSpineAndTheRightEdgeIsPinned() {
        setContent()

        node("poster_card_1").performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.onNodeWithContentDescription("Search").assertIsFocused()

        // The See-all card closes the Movies rail: the rail's last node, pinned on the right.
        node("search_see_all_movies").requestFocus()
        node("search_see_all_movies").performKeyInput { pressKey(Key.DirectionRight) }
        node("search_see_all_movies").assertIsFocused()

        node("search_clear").requestFocus()
        node("search_clear").performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.onNodeWithContentDescription("Search").assertIsFocused()
    }

    @Test
    fun everySectionIsDrawnWithItsOwnCardsAndSeeAll() {
        setContent()

        node("search_section_movies").assertExists()
        node("search_section_shows").assertExists()
        node("search_section_albums").assertExists()
        node("search_section_musicians").assertExists()
        node("search_section_tracks").assertExists()
        node("show_card_1").assertExists()
        node("album_card_101").assertExists()
        node("musician_card_1").assertExists()
        node("track_play_901").assertExists()
        node("search_see_all_movies").assertExists()
        // Two shows of two: nothing more to see.
        node("search_see_all_shows").assertDoesNotExist()
        node("search_see_all_tracks").assertExists()
    }

    @Test
    fun aSectionWithNoMatchesIsNotDrawn() {
        setContent(
            testSearchState(
                all = PagedState(
                    IglooRailState.Loaded(
                        listOf(
                            SearchSectionUi.Movies(emptyList(), total = 0),
                            SearchSectionUi.Shows(emptyList(), total = 0),
                            SearchSectionUi.Albums(emptyList(), total = 0),
                            SearchSectionUi.Musicians(emptyList(), total = 0),
                            SearchSectionUi.Tracks(
                                rows = testSearchStateTracksRows(),
                                tracks = testLibraryTracks.take(2),
                                total = 2,
                            ),
                        ),
                    ),
                ),
            ),
        )

        node("search_section_movies").assertDoesNotExist()
        node("search_section_tracks").assertExists()
        // The tracks column is the first and only section: its first Play is the pane's anchor.
        node("track_play_901").assertIsFocused()
        node("track_play_901").performKeyInput { pressKey(Key.DirectionUp) }
        node("search_tab_all").assertIsFocused()
    }

    private fun testSearchStateTracksRows() =
        (testSearchState().all.content as IglooRailState.Loaded).items
            .filterIsInstance<SearchSectionUi.Tracks>().single().rows.take(2)

    // --- tabs ---

    @Test
    fun landingOnATabSelectsItAndKeepsFocusWhenItsContentAppears() {
        setContent()

        val movies = node("search_tab_movies")
        movies.requestFocus()
        movies.assertIsFocused()
        assertEquals(listOf(SearchTab.Movies), selectedTabs)
        composeRule.waitForIdle()

        movies.assertIsFocused()
        node("search_movies_grid").assertExists()
        movies.performKeyInput { pressKey(Key.DirectionDown) }
        node("poster_card_1").assertIsFocused()
    }

    @Test
    fun pressingATabReportsThePress() {
        setContent()

        val tracks = node("search_tab_tracks")
        tracks.requestFocus()
        tracks.performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        assertEquals(listOf(SearchTab.Tracks), pressedTabs)
        node("tracks_list").assertExists()
    }

    @Test
    fun seeAllOpensTheCategoryTabAndLandsOnItsFirstCard() {
        setContent()

        node("search_see_all_movies").requestFocus()
        node("search_see_all_movies").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        assertEquals(listOf(SearchTab.Movies), pressedTabs)
        node("search_movies_grid").assertExists()
        node("poster_card_1").assertIsFocused()
        node("search_tab_movies").assertIsNotFocused()
    }

    @Test
    fun theTracksSeeAllButtonOpensTheTracksTabOnItsFirstRow() {
        setContent()

        node("search_see_all_tracks").requestFocus()
        node("search_see_all_tracks").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        assertEquals(listOf(SearchTab.Tracks), pressedTabs)
        node("track_play_901").assertIsFocused()
    }

    @Test
    fun theTracksTabAnchorsOnTheEntryRowsPlayUnderTheStrip() {
        setContent(testSearchState(tab = SearchTab.Tracks))

        node("track_play_901").assertIsFocused()
        node("track_play_901").performKeyInput { pressKey(Key.DirectionUp) }
        node("search_tab_tracks").assertIsFocused()
    }

    @Test
    fun theMusiciansTabDrawsTheCircularGrid() {
        setContent(testSearchState(tab = SearchTab.Musicians))

        node("search_musicians_grid").assertExists()
        node("musician_card_1").assertIsFocused()
    }

    // --- Clear ---

    @Test
    fun clearEmptiesTheSearchAndHandsFocusToTheField() {
        setContent()

        node("search_clear").requestFocus()
        node("search_clear").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        assertEquals(1, clearCalls)
        node("search_field").assertIsFocused()
        node("search_tabs").assertDoesNotExist()
    }

    // --- card-less states ---

    @Test
    fun everyCardlessStateAnchorsThePaneUnderTheStrip() {
        setContent(testSearchState(all = PagedState(IglooRailState.Loading)))
        composeRule.onNodeWithContentDescription("Loading results").assertIsFocused()

        searchState = testSearchState(all = PagedState(IglooRailState.Error("Server down.")))
        composeRule.waitForIdle()
        val retry = composeRule.onNodeWithContentDescription("Retry the search")
        retry.assertIsFocused()
        retry.performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(1, retries)

        // The retry's answer replaces the list, as the view model's does: a new generation.
        searchState = testSearchState(
            searched = "zzz",
            all = PagedState(IglooRailState.Loaded(SearchKind.entries.map { emptySection(it) }), contentGeneration = 1),
        )
        composeRule.waitForIdle()
        val empty = composeRule.onNodeWithContentDescription("No results for “zzz”. Try different words.")
        empty.assertIsFocused()
        empty.performKeyInput { pressKey(Key.DirectionUp) }
        node("search_tab_all").assertIsFocused()
    }

    @Test
    fun aCategoryTabWithNoMatchesSaysSo() {
        setContent(
            testSearchState(
                tab = SearchTab.Shows,
                shows = PagedState(IglooRailState.Loaded(emptyList()), total = 0),
            ),
        )

        composeRule.onNodeWithContentDescription("No TV shows match “help”. Try different words.")
            .assertIsFocused()
    }

    private fun emptySection(kind: SearchKind): SearchSectionUi = when (kind) {
        SearchKind.Movies -> SearchSectionUi.Movies(emptyList(), 0)
        SearchKind.Shows -> SearchSectionUi.Shows(emptyList(), 0)
        SearchKind.Albums -> SearchSectionUi.Albums(emptyList(), 0)
        SearchKind.Musicians -> SearchSectionUi.Musicians(emptyList(), 0)
        SearchKind.Tracks -> SearchSectionUi.Tracks(emptyList(), emptyList(), 0)
    }

    // --- overlays and the player ---

    @Test
    fun backFromAnOpenedMovieLandsOnTheCardThatOpenedIt() {
        setContent()

        node("poster_card_2").requestFocus()
        node("poster_card_2").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()
        node("details_play").assertIsFocused()

        pressBack()

        node("poster_card_2").assertIsFocused()
    }

    @Test
    fun backFromAnAlbumOpenedOnTheAlbumsTabLandsOnItsCard() {
        setContent(testSearchState(tab = SearchTab.Albums))

        node("album_card_102").requestFocus()
        node("album_card_102").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()
        node("album_play").assertIsFocused()

        pressBack()

        node("album_card_102").assertIsFocused()
    }

    @Test
    fun aRowsPlayOpensThePlayerAndItsCloseLandsOnTheRow() {
        setContent(testSearchState(tab = SearchTab.Tracks))

        node("track_play_902").requestFocus()
        node("track_play_902").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        assertEquals(listOf(902L), playedTracks)
        node("music_player").assertExists()
        node("music_play_pause").assertIsFocused()

        pressBack()
        node("music_player").assertDoesNotExist()
        node("track_play_902").assertIsFocused()
    }

    @Test
    fun aRowsPlayOnTheAllViewClosesBackOntoThatRow() {
        setContent()

        node("track_play_902").requestFocus()
        node("track_play_902").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()
        node("music_player").assertExists()

        pressBack()

        node("track_play_902").assertIsFocused()
    }

    @Test
    fun walkingDownIntoTheTracksAndPlayingClosesBackOntoThatRow() {
        setContent()

        // By d-pad, not requestFocus: the row's riders must follow the section focus moved into.
        node("poster_card_1").performKeyInput { pressKey(Key.DirectionDown) }
        node("show_card_1").performKeyInput { pressKey(Key.DirectionDown) }
        node("album_card_101").performKeyInput { pressKey(Key.DirectionDown) }
        node("musician_card_1").performKeyInput { pressKey(Key.DirectionDown) }
        node("track_play_901").assertIsFocused()
        node("track_play_901").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()
        node("music_player").assertExists()

        pressBack()

        node("track_play_901").assertIsFocused()
    }

    @Test
    fun resultsLandingWhileTheFieldOrClearHoldsFocusLeaveItThere() {
        setContent(testSearchState(all = PagedState(IglooRailState.Loading)))

        node("search_field").requestFocus()
        node("search_field").assertIsFocused()
        searchState = testSearchState(all = testSearchState().all.copy(contentGeneration = 1))
        composeRule.waitForIdle()
        node("search_field").assertIsFocused()
        node("poster_card_1").assertExists()

        node("search_clear").requestFocus()
        searchState = testSearchState(all = PagedState(IglooRailState.Loading, contentGeneration = 2))
        composeRule.waitForIdle()
        searchState = testSearchState(all = testSearchState().all.copy(contentGeneration = 3))
        composeRule.waitForIdle()
        node("search_clear").assertIsFocused()
    }

    @Test
    fun aNewSearchReplacingRowsUnderAFocusedCardKeepsFocusInThePane() {
        setContent()

        node("poster_card_2").requestFocus()
        // The view model's reset: rows give way to the skeleton on a new generation.
        searchState = testSearchState(searched = "yesterday", all = PagedState(IglooRailState.Loading, contentGeneration = 1))
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Loading results").assertIsFocused()

        searchState = testSearchState(searched = "yesterday", all = testSearchState().all.copy(contentGeneration = 2))
        composeRule.waitForIdle()
        // The view re-anchors on the first section's entry. The remembered card belonged to the
        // old query, so it is the new results' first card even though they still hold card 2.
        node("poster_card_1").assertIsFocused()
    }

    @Test
    fun theKeyboardsSearchActionSendsTheQueryAndLandsOnTheResults() {
        setContent()

        node("search_field").requestFocus()
        node("search_field").performImeAction()
        composeRule.waitForIdle()

        assertEquals(1, searchNowCalls)
        node("poster_card_1").assertIsFocused()
    }

    @Test
    fun theKeyboardsSearchActionOnABlankFieldMovesNothing() {
        setContent(SearchUiState())

        node("search_field").performImeAction()
        composeRule.waitForIdle()

        assertEquals(1, searchNowCalls)
        node("search_field").assertIsFocused()
    }

    // --- memory ---

    @Test
    fun aHomeRoundTripKeepsTheTabAndTheCard() {
        setContent()

        node("search_tab_albums").requestFocus()
        composeRule.waitForIdle()
        node("album_card_103").requestFocus()
        node("album_card_103").assertIsFocused()

        composeRule.onNodeWithContentDescription("Home").performClick()
        composeRule.waitForIdle()
        openSearch()

        node("search_albums_grid").assertExists()
        node("album_card_103").assertIsFocused()
    }

    @Test
    fun aHomeRoundTripOnTheAllViewRemembersTheEntryRailsCard() {
        setContent()

        node("poster_card_3").requestFocus()
        node("poster_card_3").assertIsFocused()

        composeRule.onNodeWithContentDescription("Home").performClick()
        composeRule.waitForIdle()
        openSearch()

        // The pane's entry is the first section's remembered card; the rails below are a
        // spatial step away, column kept, as on Home.
        node("poster_card_3").assertIsFocused()
        node("poster_card_3").performKeyInput { pressKey(Key.DirectionDown) }
        node("show_card_2").assertIsFocused()
    }
}
