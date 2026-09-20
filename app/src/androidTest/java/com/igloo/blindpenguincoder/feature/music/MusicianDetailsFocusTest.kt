package com.igloo.blindpenguincoder.feature.music

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
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
import com.igloo.blindpenguincoder.feature.home.findActivity
import com.igloo.blindpenguincoder.playback.media3.FakeMusicPlayerEngine
import com.igloo.blindpenguincoder.playback.model.MusicPlayRequest
import com.igloo.blindpenguincoder.playback.model.MusicQueueSource
import com.igloo.blindpenguincoder.testAlbumDetails
import com.igloo.blindpenguincoder.testMusicState
import com.igloo.blindpenguincoder.testMusicianDetails
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The musician overlay's focus contract (design-system.md sections 6.3 and 11.5.2): opening it
 * from a musician card lands focus on Play all — held by the skeleton's stub through the load —
 * the chain walks actions → discography rail → track rows → facts panel, no direction escapes
 * into the shell, Back restores the card that led away, and an album opened from the rail or a
 * row's More replaces the overlay in the one details slot.
 */
@RunWith(AndroidJUnit4::class)
class MusicianDetailsFocusTest {

    @get:Rule(order = 0)
    val animationScale = AnimationScaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private var musicianState by mutableStateOf(MusicianDetailsUiState())
    private var albumState by mutableStateOf(AlbumDetailsUiState())
    private val openedMusicians = mutableListOf<Long>()
    private val openedAlbums = mutableListOf<Long>()
    private val engineRequests = mutableListOf<MusicPlayRequest>()
    private var hostActivity: Activity? = null

    private fun setShellContent(
        initial: MusicianDetailsUiState = MusicianDetailsUiState(),
        spokenAccessibilityEnabled: Boolean = false,
        openLoads: (Long) -> MusicianDetailsState = { id ->
            MusicianDetailsState.Loaded(testMusicianDetails(id = id))
        },
    ) {
        musicianState = initial
        albumState = AlbumDetailsUiState()
        openedMusicians.clear()
        openedAlbums.clear()
        engineRequests.clear()
        composeRule.setContent {
            val context = LocalContext.current
            SideEffect { hostActivity = context.findActivity() }
            IglooTheme {
                TestIglooApp(
                    spokenAccessibilityEnabled = spokenAccessibilityEnabled,
                    music = testMusicState(),
                    musicianDetails = musicianState,
                    albumDetails = albumState,
                    onMusicianSelected = { id ->
                        openedMusicians += id
                        albumState = AlbumDetailsUiState()
                        musicianState = MusicianDetailsUiState(openMusicianId = id, details = openLoads(id))
                    },
                    onAlbumSelected = { id ->
                        openedAlbums += id
                        musicianState = MusicianDetailsUiState()
                        albumState = AlbumDetailsUiState(
                            openAlbumId = id,
                            details = AlbumDetailsState.Loaded(testAlbumDetails(id = id)),
                        )
                    },
                    onCloseDetails = {
                        musicianState = MusicianDetailsUiState()
                        albumState = AlbumDetailsUiState()
                    },
                    musicPlayerEngineFactory = { _, request ->
                        engineRequests += request
                        FakeMusicPlayerEngine()
                    },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Music").performClick()
        composeRule.waitForIdle()
    }

    private fun pressBack() {
        composeRule.runOnUiThread {
            (checkNotNull(hostActivity) as ComponentActivity).onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
    }

    private fun openMusicianCard(id: Long) {
        composeRule.onNodeWithTag("musician_card_$id").requestFocus()
        composeRule.onNodeWithTag("musician_card_$id")
            .assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()
    }

    @Test
    fun openingFromAMusicianCardLandsFocusOnPlayAll() {
        setShellContent()

        openMusicianCard(2)

        assertEquals(listOf(2L), openedMusicians)
        composeRule.onNodeWithTag("musician_details").assertExists()
        composeRule.onNodeWithTag("musician_play_all").assertIsFocused()
    }

    @Test
    fun theSkeletonHoldsTheAnchorThroughTheLoadedSwap() {
        setShellContent(openLoads = { MusicianDetailsState.Loading })

        openMusicianCard(2)
        composeRule.onNodeWithContentDescription("Loading artist details").assertIsFocused()

        musicianState = musicianState.copy(details = MusicianDetailsState.Loaded(testMusicianDetails(id = 2)))
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("musician_play_all").assertIsFocused()
    }

    @Test
    fun backClosesTheOverlayAndRestoresTheOriginatingCard() {
        setShellContent()

        openMusicianCard(3)
        composeRule.onNodeWithTag("musician_play_all").assertIsFocused()

        pressBack()

        composeRule.onNodeWithTag("musician_details").assertDoesNotExist()
        composeRule.onNodeWithTag("musician_card_3").assertIsFocused()
    }

    @Test
    fun downWalksActionsThenTheRailThenEveryRowThenTheFactsPanel() {
        setShellContent()
        openMusicianCard(2)

        val play = composeRule.onNodeWithTag("musician_play_all")
        play.performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("musician_album_11").assertIsFocused()
        composeRule.onNodeWithTag("musician_album_11").performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithTag("musician_album_12").assertIsFocused()
        composeRule.onNodeWithTag("musician_album_12").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("track_play_951").assertIsFocused()
        composeRule.onNodeWithTag("track_play_951").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("track_play_952").assertIsFocused()
        composeRule.onNodeWithTag("track_play_952").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("track_play_953").assertIsFocused()
        composeRule.onNodeWithTag("track_play_953").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("musician_details_facts").assertIsFocused()

        // Up from the first row re-enters the rail on the card it remembered.
        composeRule.onNodeWithTag("track_play_951").requestFocus()
        composeRule.onNodeWithTag("track_play_951").performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("musician_album_12").assertIsFocused()
    }

    @Test
    fun noDirectionEscapesIntoTheShellUnderneath() {
        setShellContent()
        openMusicianCard(2)

        val play = composeRule.onNodeWithTag("musician_play_all")
        play.performKeyInput { pressKey(Key.DirectionUp) }
        play.assertIsFocused()
        play.performKeyInput { pressKey(Key.DirectionLeft) }
        play.assertIsFocused()
        play.performKeyInput { pressKey(Key.DirectionRight) }
        val shuffle = composeRule.onNodeWithTag("musician_shuffle")
        shuffle.assertIsFocused()
        shuffle.performKeyInput { pressKey(Key.DirectionRight) }
        shuffle.assertIsFocused()

        val rail = composeRule.onNodeWithTag("musician_album_11")
        rail.requestFocus()
        rail.performKeyInput { pressKey(Key.DirectionLeft) }
        rail.assertIsFocused()

        val more = composeRule.onNodeWithTag("track_more_951")
        more.requestFocus()
        more.performKeyInput { pressKey(Key.DirectionRight) }
        more.assertIsFocused()

        val facts = composeRule.onNodeWithTag("musician_details_facts")
        facts.requestFocus()
        facts.performKeyInput { pressKey(Key.DirectionDown) }
        facts.assertIsFocused()
    }

    @Test
    fun playAllOpensThePlayerOnTheMusicianQueueAndBackReturnsToPlayAll() {
        setShellContent()
        openMusicianCard(2)

        composeRule.onNodeWithTag("musician_play_all").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("music_player").assertExists()
        val request = engineRequests.single()
        assertEquals(MusicQueueSource.Musician(musicianId = 2, title = "The Beatles"), request.source)
        assertEquals(listOf(951L, 952L, 953L), request.tracks.map { it.id })

        pressBack()

        composeRule.onNodeWithTag("music_player").assertDoesNotExist()
        composeRule.onNodeWithTag("musician_play_all").assertIsFocused()
    }

    @Test
    fun aRailCardReplacesTheOverlayWithTheAlbumAndBackReturnsToTheMusicPane() {
        setShellContent()
        openMusicianCard(2)

        composeRule.onNodeWithTag("musician_album_12").requestFocus()
        composeRule.onNodeWithTag("musician_album_12").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        assertEquals(listOf(12L), openedAlbums)
        composeRule.onNodeWithTag("musician_details").assertDoesNotExist()
        composeRule.onNodeWithTag("album_details").assertExists()
        composeRule.onNodeWithTag("album_play").assertIsFocused()

        // The origin was never rewritten: Back lands where the musician was opened from.
        pressBack()
        composeRule.onNodeWithTag("album_details").assertDoesNotExist()
        composeRule.onNodeWithTag("musician_card_2").assertIsFocused()
    }

    @Test
    fun goToAlbumFromARowsMoreReplacesTheOverlay() {
        setShellContent()
        openMusicianCard(2)

        val more = composeRule.onNodeWithTag("track_more_951")
        more.requestFocus()
        more.performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()
        val goToAlbum = composeRule.onNodeWithContentDescription("Go to album")
        goToAlbum.assertIsFocused()
        goToAlbum.performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        assertEquals(listOf(11L), openedAlbums)
        composeRule.onNodeWithTag("album_details").assertExists()
    }

    @Test
    fun theErrorStateOffersAPinnedFocusedRetry() {
        setShellContent(
            MusicianDetailsUiState(openMusicianId = 2, details = MusicianDetailsState.Error("Couldn't load this artist.")),
        )

        val retry = composeRule.onNodeWithContentDescription("Retry loading artist details")
        retry.assertIsFocused()
        retry.performKeyInput { pressKey(Key.DirectionUp) }
        retry.assertIsFocused()
        retry.performKeyInput { pressKey(Key.DirectionLeft) }
        retry.assertIsFocused()
    }
}
