package com.igloo.blindpenguincoder.feature.player

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import com.igloo.blindpenguincoder.feature.home.findActivity
import com.igloo.blindpenguincoder.feature.music.AlbumDetailsState
import com.igloo.blindpenguincoder.feature.music.AlbumDetailsUiState
import com.igloo.blindpenguincoder.playback.media3.FakeMusicPlayerEngine
import com.igloo.blindpenguincoder.playback.model.MusicPlayRequest
import com.igloo.blindpenguincoder.testAlbumDetails
import com.igloo.blindpenguincoder.testAlbums
import com.igloo.blindpenguincoder.testContinueMovies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The music player in the album overlay's one player slot (design-system.md sections 6.3 and
 * 11.8): Play Album maps the loaded details into the player synchronously, the album screen
 * underneath leaves TalkBack traversal for the player's lifetime, Back belongs to the player
 * while it is up and closing restores focus to Play Album, and an overlay close underneath —
 * a profile switch, a session revalidation — takes the player and its audio with it.
 */
@RunWith(AndroidJUnit4::class)
class MusicPlayerOverlayFocusTest {

    @get:Rule(order = 0)
    val animationScale = AnimationScaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private var albumDetailsState by mutableStateOf(AlbumDetailsUiState())
    private val engines = mutableListOf<FakeMusicPlayerEngine>()
    private val engineRequests = mutableListOf<MusicPlayRequest>()
    private var hostActivity: Activity? = null

    private fun setShellContent() {
        albumDetailsState = AlbumDetailsUiState()
        engines.clear()
        engineRequests.clear()
        composeRule.setContent {
            val context = LocalContext.current
            SideEffect { hostActivity = context.findActivity() }
            IglooTheme {
                TestIglooApp(
                    // Hero hidden so the rails own the pane's entry anchor: this suite's
                    // subject is the round trip between the album overlay and its player.
                    home = HomeUiState(
                        hero = HomeHeroState.Hidden,
                        continueWatching = IglooRailState.Loaded(testContinueMovies),
                        latestAlbums = IglooRailState.Loaded(testAlbums),
                    ),
                    albumDetails = albumDetailsState,
                    onAlbumSelected = { albumId ->
                        albumDetailsState = AlbumDetailsUiState(
                            openAlbumId = albumId,
                            details = AlbumDetailsState.Loaded(testAlbumDetails(id = albumId)),
                        )
                    },
                    onCloseDetails = {
                        albumDetailsState = AlbumDetailsUiState()
                    },
                    musicPlayerEngineFactory = { _, request ->
                        engineRequests += request
                        FakeMusicPlayerEngine().also { engines += it }
                    },
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun pressBack() {
        composeRule.runOnUiThread {
            (checkNotNull(hostActivity) as ComponentActivity).onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
    }

    private fun openAlbumCard(id: Long = 11) {
        composeRule.onNodeWithTag("album_card_$id").requestFocus()
        composeRule.onNodeWithTag("album_card_$id")
            .assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()
    }

    private fun pressPlayAlbum() {
        // Entry focus already anchors on Play Album (section 11.5.1).
        val play = composeRule.onNodeWithTag("album_play")
        play.assertIsFocused()
        play.performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()
    }

    @Test
    fun pressingPlayAlbumOpensThePlayerWithTheFlattenedQueue() {
        setShellContent()
        openAlbumCard()

        pressPlayAlbum()

        composeRule.onNodeWithTag("music_player").assertExists()
        composeRule.onNodeWithTag("music_play_pause").assertIsFocused()
        assertEquals(1, engines.size)
        assertEquals(listOf("start:0:0.0:true"), engines.single().playbackCommands)
        // The press mapped the details already on screen: disc-then-track order, no fetch.
        val request = engineRequests.single()
        assertEquals("Help!", request.albumTitle)
        assertEquals(
            listOf("Yesterday", "Ticket to Ride", "Act Naturally"),
            request.tracks.map { it.title },
        )
    }

    @Test
    fun theAlbumOverlayLeavesTalkBackTraversalWhileThePlayerIsUp() {
        setShellContent()
        openAlbumCard()
        val detailsLayer = composeRule.onNodeWithTag("details_layer")
        detailsLayer.assert(
            SemanticsMatcher.keyNotDefined(SemanticsProperties.HideFromAccessibility),
        )

        pressPlayAlbum()

        // Hidden from traversal, but still in the tree — so the assertion is meaningful.
        detailsLayer.assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility),
        )

        pressBack()

        detailsLayer.assert(
            SemanticsMatcher.keyNotDefined(SemanticsProperties.HideFromAccessibility),
        )
    }

    @Test
    fun backClosesOneLayerAtATimeAndRestoresFocusToWhatLedAway() {
        setShellContent()
        openAlbumCard()
        pressPlayAlbum()

        pressBack()

        // The host's own Back handlers stayed silent while the player was up: one press closed
        // only the player, its engine is torn down, and focus is back on Play Album.
        composeRule.onNodeWithTag("music_player").assertDoesNotExist()
        assertTrue(engines.single().released)
        composeRule.onNodeWithTag("album_details").assertExists()
        composeRule.onNodeWithTag("album_play").assertIsFocused()

        pressBack()

        // The next press is the album overlay's, landing back on the card that opened it.
        composeRule.onNodeWithTag("album_details").assertDoesNotExist()
        composeRule.onNodeWithTag("album_card_11").assertIsFocused()
    }

    @Test
    fun closingTheAlbumOverlayUnderneathTakesThePlayerWithIt() {
        setShellContent()
        openAlbumCard()
        pressPlayAlbum()

        // A profile switch or session revalidation closes the overlay out from under the
        // player; stop-on-exit means the audio goes too.
        composeRule.runOnUiThread { albumDetailsState = AlbumDetailsUiState() }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("music_player").assertDoesNotExist()
        composeRule.onNodeWithTag("album_details").assertDoesNotExist()
        assertTrue(engines.single().released)
    }
}
