package com.igloo.blindpenguincoder.feature.music

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasNoClickAction
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
import com.igloo.blindpenguincoder.feature.shared.AppendState
import com.igloo.blindpenguincoder.testMusicState
import com.igloo.blindpenguincoder.testTrackEntries
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the Music pane says out loud (design-system.md sections 11.5 and 12): the pane is
 * named, the count reports scale without reading the list back, each row's Play speaks the
 * mapping's sentence with the letter folded in only on a bucket's first row, the letter
 * headers are silent, More says where it can go, and the menu takes the body out of the tree.
 */
@RunWith(AndroidJUnit4::class)
class TracksListAccessibilityTest {

    @get:Rule(order = 0)
    val animationScale = AnimationScaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private var musicState by mutableStateOf(testMusicState(tab = MusicTab.Tracks))

    private fun setContent(
        initial: MusicUiState = testMusicState(tab = MusicTab.Tracks),
        canOpenAlbum: Boolean = true,
    ) {
        musicState = initial
        composeRule.setContent {
            IglooTheme {
                TestIglooApp(
                    music = musicState,
                    onAlbumSelected = if (canOpenAlbum) { _ -> } else null,
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Music").performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun thePaneIsNamedMusic() {
        setContent()

        composeRule.onNodeWithTag("content_pane")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Music"))
    }

    @Test
    fun theCountReportsScaleWithoutReadingTheListBack() {
        setContent()

        composeRule.onNodeWithTag("music_count")
            .assertContentDescriptionEquals("Showing 5 of 5 tracks")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))

        musicState = testMusicState(
            tab = MusicTab.Tracks,
            tracks = PagedState(IglooRailState.Loaded(testTrackEntries), total = 500, append = AppendState.Loading),
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("music_count")
            .assertContentDescriptionEquals("Showing 5 of 500 tracks. Loading more tracks.")
    }

    @Test
    fun playSpeaksTheSentenceWithTheLetterFoldedOnlyOnABucketsFirstRow() {
        setContent()

        composeRule.onNodeWithTag("track_play_901").assertContentDescriptionEquals(
            "Tracks starting with a number or symbol. 1999. The Beatles · Help!. 2 minutes and 5 seconds.",
        )
        composeRule.onNodeWithTag("track_play_902").assertContentDescriptionEquals(
            "Tracks starting with A. Abbey Road. The Beatles · Help!. 2 minutes and 5 seconds.",
        )
        composeRule.onNodeWithTag("track_play_903").assertContentDescriptionEquals(
            "All You Need Is Love. The Beatles · Help!. 2 minutes and 5 seconds.",
        )
        // The visible letter and the row's text nodes never reach the reader on their own.
        composeRule.onNodeWithTag("tracks_letter_A")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
        composeRule.onNodeWithText("Abbey Road").assertDoesNotExist()
    }

    @Test
    fun moreNamesTheTrackAndSaysWhenItHasNowhereToGo() {
        setContent()

        composeRule.onNodeWithTag("track_more_902")
            .assertContentDescriptionEquals("More actions for Abbey Road")
        composeRule.onNodeWithTag("track_more_905")
            .assertContentDescriptionEquals("More actions for Come Together. None available.")
            .assertHasNoClickAction()
    }

    @Test
    fun withNoAlbumScreenEveryMoreIsInert() {
        setContent(canOpenAlbum = false)

        composeRule.onNodeWithTag("track_more_902")
            .assertContentDescriptionEquals("More actions for Abbey Road. None available.")
            .assertHasNoClickAction()
    }

    @Test
    fun theMenuTakesTheBodyOutOfTheTreeWhileItIsOpen() {
        setContent()

        composeRule.onNodeWithTag("track_more_902").requestFocus()
        composeRule.onNodeWithTag("track_more_902").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("more_menu").assertExists()
        composeRule.onNodeWithTag("music_body")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
        composeRule.onNodeWithTag("track_play_902").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Go to album").assertExists()
        composeRule.onNodeWithContentDescription("Go to artist").assertDoesNotExist()
    }

    @Test
    fun theSkeletonAnchorAnnouncesTheWaitPolitely() {
        setContent(testMusicState(tab = MusicTab.Tracks, tracks = PagedState(IglooRailState.Loading)))

        composeRule.onNodeWithContentDescription("Loading tracks")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }
}
