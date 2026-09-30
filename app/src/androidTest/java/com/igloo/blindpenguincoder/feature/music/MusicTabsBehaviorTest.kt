package com.igloo.blindpenguincoder.feature.music

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.AnimationScaleRule
import com.igloo.blindpenguincoder.TestIglooApp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.feature.shared.PagedState
import com.igloo.blindpenguincoder.testMusicState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Music pane's tab and anchoring contract (design-system.md sections 6.3 and 11.5): every
 * tab state anchors the pane, landing on a tab selects it without stealing focus, the left
 * column exits to the spine, and each tab remembers its own card across a round trip.
 */
@RunWith(AndroidJUnit4::class)
class MusicTabsBehaviorTest {

    @get:Rule(order = 0)
    val animationScale = AnimationScaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private var musicState by mutableStateOf(testMusicState())
    private val selectedTabs = mutableListOf<MusicTab>()
    private val pressedTabs = mutableListOf<MusicTab>()
    private var retries = 0
    private var columns = 0

    private fun setContent(initial: MusicUiState = testMusicState()) {
        musicState = initial
        selectedTabs.clear()
        pressedTabs.clear()
        retries = 0
        composeRule.setContent {
            IglooTheme {
                columns = IglooTheme.layout.gridColumns
                TestIglooApp(
                    music = musicState,
                    musicActions = MusicActions(
                        onRefresh = {},
                        onRetryFirstPage = { retries += 1 },
                        onRetryAppend = {},
                        onLoadMore = {},
                        onSelectTab = { selectedTabs += it },
                        onPressTab = { pressedTabs += it },
                        onPlayTrack = {},
                        onPlayAll = {},
                        onShuffleAll = {},
                        onToggleLike = {},
                    ),
                    onAlbumSelected = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Music").performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun enteringThePaneLandsOnTheSelectedTabsFirstCard() {
        setContent()

        composeRule.onNodeWithTag("musician_card_1").assertIsFocused()
    }

    @Test
    fun landingOnATabSelectsItAndTheTabKeepsFocusWhenItsContentAppears() {
        setContent()

        val albums = composeRule.onNodeWithTag("music_tab_albums")
        albums.requestFocus()
        albums.assertIsFocused()
        assertEquals(listOf(MusicTab.Albums), selectedTabs)

        // The host answers the selection: the Albums tab's own pages appear underneath.
        musicState = testMusicState(tab = MusicTab.Albums)
        composeRule.waitForIdle()

        albums.assertIsFocused()
        composeRule.onNodeWithTag("albums_grid").assertExists()
        albums.performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("album_card_101").assertIsFocused()
    }

    @Test
    fun pressingATabReportsThePress() {
        setContent()

        val tracks = composeRule.onNodeWithTag("music_tab_tracks")
        tracks.requestFocus()
        tracks.performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        assertEquals(listOf(MusicTab.Tracks), pressedTabs)
    }

    @Test
    fun theLeftColumnExitsToTheSpineAndTheRightEdgeIsPinned() {
        setContent()

        val first = composeRule.onNodeWithTag("musician_card_1")
        first.performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.onNodeWithContentDescription("Music").assertIsFocused()

        val lastInRow = composeRule.onNodeWithTag("musician_card_$columns")
        lastInRow.requestFocus()
        lastInRow.performKeyInput { pressKey(Key.DirectionRight) }
        lastInRow.assertIsFocused()
    }

    @Test
    fun eachTabRemembersItsOwnCardAcrossAHomeRoundTrip() {
        setContent()

        composeRule.onNodeWithTag("musician_card_3").requestFocus()
        composeRule.onNodeWithTag("musician_card_3").assertIsFocused()

        composeRule.onNodeWithContentDescription("Home").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Music").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("musician_card_3").assertIsFocused()
    }

    @Test
    fun theTracksTabAnchorsOnTheEntryRowsPlayWithTheActionsAbove() {
        setContent(testMusicState(tab = MusicTab.Tracks))

        composeRule.onNodeWithTag("track_play_901").assertIsFocused()
        composeRule.onNodeWithTag("music_play_all").assertExists()
        composeRule.onNodeWithTag("music_shuffle_all").assertExists()
    }

    @Test
    fun everyCardlessStateAnchorsThePane() {
        setContent(testMusicState(musicians = PagedState(IglooRailState.Loading)))
        composeRule.onNodeWithContentDescription("Loading musicians").assertIsFocused()

        musicState = testMusicState(musicians = PagedState(IglooRailState.Error("Server down.")))
        composeRule.waitForIdle()
        val retry = composeRule.onNodeWithContentDescription("Retry loading musicians")
        retry.assertIsFocused()
        retry.performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()
        assertEquals(1, retries)

        // The retry's page lands as a replacement, generation bumped, the way the view model
        // publishes it; that is what carries focus off the disposed Retry.
        musicState = testMusicState(
            musicians = PagedState(IglooRailState.Loaded(emptyList()), contentGeneration = 1),
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(
            "No musicians in your library yet. Add a music folder on the server and run a scan.",
        ).assertIsFocused()
    }

    @Test
    fun upFromTheFirstRowReachesTheTabsAndDownFromTheTabsReturnsToTheEntryCard() {
        setContent()

        composeRule.onNodeWithTag("musician_card_2").requestFocus()
        composeRule.onNodeWithTag("musician_card_2").performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("music_tab_musicians").assertIsFocused()

        composeRule.onNodeWithTag("music_tab_musicians").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("musician_card_2").assertIsFocused()
    }
}
