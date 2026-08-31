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
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.AnimationScaleRule
import com.igloo.blindpenguincoder.TestIglooApp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.feature.home.HomeHeroState
import com.igloo.blindpenguincoder.feature.home.HomeUiState
import com.igloo.blindpenguincoder.testAlbumDetails
import com.igloo.blindpenguincoder.testAlbums
import com.igloo.blindpenguincoder.testContinueMovies
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the album detail overlay says to TalkBack (design-system.md sections 11.5.1 and 12):
 * the pane names the album, the shell leaves traversal while the overlay is up, each track row
 * is exactly one node speaking the mapping's sentence, and the hero reading stop exists only
 * while a spoken screen reader runs.
 */
@RunWith(AndroidJUnit4::class)
class AlbumDetailsAccessibilityTest {

    @get:Rule(order = 0)
    val animationScale = AnimationScaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private var albumDetailsState by mutableStateOf(AlbumDetailsUiState())

    private fun setContent(
        initialAlbumDetails: AlbumDetailsUiState = loadedState(),
        spokenAccessibilityEnabled: Boolean = false,
    ) {
        albumDetailsState = initialAlbumDetails
        composeRule.setContent {
            IglooTheme {
                TestIglooApp(
                    spokenAccessibilityEnabled = spokenAccessibilityEnabled,
                    home = HomeUiState(
                        hero = HomeHeroState.Hidden,
                        continueWatching = IglooRailState.Loaded(testContinueMovies),
                        latestAlbums = IglooRailState.Loaded(testAlbums),
                    ),
                    albumDetails = albumDetailsState,
                    onAlbumSelected = { },
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun loadedState(album: AlbumDetailsUi = testAlbumDetails()) = AlbumDetailsUiState(
        openAlbumId = album.id,
        details = AlbumDetailsState.Loaded(album),
    )

    @Test
    fun thePaneAnnouncesTheAlbumOnceLoaded() {
        setContent(
            AlbumDetailsUiState(openAlbumId = 11, details = AlbumDetailsState.Loading),
        )

        val pane = composeRule.onNodeWithTag("album_details")
        pane.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Album details"),
        )

        albumDetailsState = loadedState()
        composeRule.waitForIdle()
        pane.assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Help!"))
    }

    @Test
    fun theShellLeavesTraversalWhileTheOverlayIsUp() {
        setContent()

        composeRule.onNodeWithTag("shell_content").assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility),
        )
    }

    @Test
    fun theLoadingStateAnnouncesItselfPolitely() {
        setContent(
            AlbumDetailsUiState(openAlbumId = 11, details = AlbumDetailsState.Loading),
        )

        composeRule.onNodeWithContentDescription("Loading album details")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
            .assertIsFocused()
    }

    @Test
    fun theMetadataChipsAreOneAnnouncement() {
        setContent()

        // Three chips, one sentence: consecutive two-word chip announcements would be noise.
        composeRule.onNodeWithContentDescription("August 6, 1965, 3 tracks, 7m 5s")
            .assertExists()
    }

    @Test
    fun eachTrackRowIsOneNodeSpeakingTheMappingSentence() {
        setContent()

        composeRule.onNodeWithTag("album_track_901").assertContentDescriptionEquals(
            "Disc 1. Track 1. Yesterday. Rock, Pop. 2 minutes and 5 seconds.",
        )
        // Only a disc's first row folds the disc in; the second row starts at the track.
        composeRule.onNodeWithTag("album_track_902").assertContentDescriptionEquals(
            "Track 2. Ticket to Ride. 3 minutes and 10 seconds.",
        )
        composeRule.onNodeWithTag("album_track_903").assertContentDescriptionEquals(
            "Disc 2. Track 1. Act Naturally. 1 minute and 50 seconds.",
        )
        // The row's inner text nodes are cleared into the one sentence above.
        composeRule.onNodeWithText("Ticket to Ride").assertDoesNotExist()
    }

    @Test
    fun theFactsPanelIsOneNodeCarryingEveryRowAndItsHeading() {
        setContent()

        composeRule.onNodeWithTag("album_details_facts").assertContentDescriptionEquals(
            "Album details. Release date: August 6, 1965. Total tracks: 3. " +
                "Total duration: 7m 5s. Artist: The Beatles. Genres: Rock, Pop. Discs: 2. " +
                "Audio quality: FLAC · 900 kbps · stereo. Spotify popularity: 73 / 100.",
        )
    }

    @Test
    fun thePopularityMeterIsSilent() {
        setContent()

        // The score is spoken by the hero reading stop's sentence; the meter itself carries
        // no announcement of its own.
        composeRule.onNodeWithTag("album_popularity_meter").assert(
            SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription),
        )
    }

    @Test
    fun theHeroReadingStopSpeaksTheFullSentenceWhileASpokenReaderRuns() {
        setContent(spokenAccessibilityEnabled = true)

        val play = composeRule.onNodeWithTag("album_play")
        play.requestFocus()
        play.performKeyInput { pressKey(Key.DirectionUp) }

        composeRule.onNodeWithTag("album_hero_info")
            .assertIsFocused()
            .assertContentDescriptionEquals(
                "Help! by The Beatles. 3 tracks. Total duration: 7 minutes and 5 seconds. " +
                    "Genres: Rock, Pop. Spotify popularity 73 out of 100.",
            )
    }

    @Test
    fun theHeroReadingStopStaysOutOfTheTreeWithoutASpokenReader() {
        setContent(spokenAccessibilityEnabled = false)

        composeRule.onNodeWithTag("album_hero_info").assertDoesNotExist()
    }

    @Test
    fun sectionTitlesAreHeadings() {
        setContent()

        composeRule.onNodeWithText("Track List")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        composeRule.onNodeWithText("Album Details")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }
}
