package com.igloo.blindpenguincoder.feature.music

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.AnimationScaleRule
import com.igloo.blindpenguincoder.TestIglooApp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.feature.shared.DetailsState
import com.igloo.blindpenguincoder.testMusicianDetails
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the musician overlay says to TalkBack (design-system.md sections 11.5.2 and 12): the
 * pane names the artist, the shell leaves traversal, the chips are one announcement, each
 * row's Play speaks the mapping's sentence, More names the album it can open or says it has
 * none, and the hero reading stop exists only under a spoken screen reader.
 */
@RunWith(AndroidJUnit4::class)
class MusicianDetailsAccessibilityTest {

    @get:Rule(order = 0)
    val animationScale = AnimationScaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private var musicianState by mutableStateOf(loadedState())

    private fun setContent(
        initial: MusicianDetailsUiState = loadedState(),
        spokenAccessibilityEnabled: Boolean = false,
    ) {
        musicianState = initial
        composeRule.setContent {
            IglooTheme {
                TestIglooApp(
                    spokenAccessibilityEnabled = spokenAccessibilityEnabled,
                    musicianDetails = musicianState,
                    onMusicianSelected = { },
                    onAlbumSelected = { },
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun loadedState(musician: MusicianDetailsUi = testMusicianDetails()) = MusicianDetailsUiState(
        openMusicianId = musician.id,
        details = DetailsState.Loaded(musician),
    )

    @Test
    fun thePaneAnnouncesTheArtistOnceLoaded() {
        setContent(MusicianDetailsUiState(openMusicianId = 4, details = DetailsState.Loading))

        val pane = composeRule.onNodeWithTag("musician_details")
        pane.assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Artist details"))

        musicianState = loadedState()
        composeRule.waitForIdle()
        pane.assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "The Beatles"))
    }

    @Test
    fun theShellLeavesTraversalWhileTheOverlayIsUp() {
        setContent()

        composeRule.onNodeWithTag("shell_content")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
    }

    @Test
    fun theChipsAreOneAnnouncementAndTheActionsNameTheArtist() {
        setContent()

        composeRule.onNodeWithTag("musician_play_all").assertContentDescriptionEquals("Play all tracks by The Beatles")
        composeRule.onNodeWithTag("musician_shuffle").assertContentDescriptionEquals("Shuffle all tracks by The Beatles")
        composeRule.onNodeWithText("2 albums").assertDoesNotExist()
    }

    @Test
    fun eachRowsPlaySpeaksTheMappingSentenceAndMoreNamesItsAlbum() {
        setContent()

        composeRule.onNodeWithTag("track_play_951")
            .assertContentDescriptionEquals("Yesterday. Help!. 2 minutes and 5 seconds.")
        composeRule.onNodeWithTag("track_more_951").assertContentDescriptionEquals("More actions for Yesterday")
        // A row with no album on the wire has nowhere for More to go.
        composeRule.onNodeWithTag("track_more_953")
            .assertContentDescriptionEquals("More actions for Untagged Demo. None available.")
            .assertHasNoClickAction()
        composeRule.onNodeWithText("Yesterday").assertDoesNotExist()
    }

    @Test
    fun theRailCardsAnnounceTheAlbumAndItsYear() {
        setContent()

        composeRule.onNodeWithTag("musician_album_11").assertContentDescriptionEquals("Help!, 1965")
    }

    @Test
    fun theFactsPanelIsOneNodeCarryingEveryRowAndItsHeading() {
        setContent()

        composeRule.onNodeWithTag("musician_details_facts").assertContentDescriptionEquals(
            "Artist details. Albums: 2. Tracks: 3. Total duration: 7m 5s. Genres: Rock, Pop. " +
                "Spotify popularity: 88 / 100. Spotify followers: 25,000,000. About: Liverpool, 1960.",
        )
    }

    @Test
    fun theHeroReadingStopSpeaksTheFullSentenceOnlyWhileASpokenReaderRuns() {
        setContent(spokenAccessibilityEnabled = true)

        val play = composeRule.onNodeWithTag("musician_play_all")
        play.requestFocus()
        play.performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("musician_hero_info")
            .assertIsFocused()
            .assertContentDescriptionEquals(
                "The Beatles. 2 albums, 3 tracks. Total duration: 7 minutes and 5 seconds. " +
                    "Genres: Rock, Pop. Spotify popularity 88 out of 100.",
            )
    }

    @Test
    fun theHeroReadingStopStaysOutOfTheTreeWithoutASpokenReader() {
        setContent(spokenAccessibilityEnabled = false)

        composeRule.onNodeWithTag("musician_hero_info").assertDoesNotExist()
    }
}
