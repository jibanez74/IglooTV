package com.igloo.blindpenguincoder.feature.music

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
import com.igloo.blindpenguincoder.testAlbumDetails
import com.igloo.blindpenguincoder.testAlbums
import com.igloo.blindpenguincoder.testContinueMovies
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The album detail overlay's focus contract (design-system.md sections 6.3 and 11.5.1):
 * opening it from an album card lands focus on Play Album — held by the skeleton's stub
 * through the load — Back closes it and puts focus back on the card that led away, the
 * vertical chain walks actions → track rows → facts panel keeping its column across the
 * rows' three actions, and no d-pad direction escapes into the shell still composed underneath.
 */
@RunWith(AndroidJUnit4::class)
class AlbumDetailsFocusTest {

    @get:Rule(order = 0)
    val animationScale = AnimationScaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private var albumDetailsState by mutableStateOf(AlbumDetailsUiState())
    private val opened = mutableListOf<Long>()
    private var hostActivity: Activity? = null

    private fun setShellContent(
        initialAlbumDetails: AlbumDetailsUiState = AlbumDetailsUiState(),
        // Explicit, never the ambient default: the Shield test device runs TalkBack, and this
        // suite pins the chain without the reading stops.
        spokenAccessibilityEnabled: Boolean = false,
        openLoads: (Long) -> AlbumDetailsState = { id ->
            AlbumDetailsState.Loaded(testAlbumDetails(id = id))
        },
    ) {
        albumDetailsState = initialAlbumDetails
        opened.clear()
        composeRule.setContent {
            val context = LocalContext.current
            SideEffect { hostActivity = context.findActivity() }
            IglooTheme {
                TestIglooApp(
                    spokenAccessibilityEnabled = spokenAccessibilityEnabled,
                    // Hero hidden so the rails own the pane's entry anchor: this suite's
                    // subject is the round trip between an album card and the overlay.
                    home = HomeUiState(
                        hero = HomeHeroState.Hidden,
                        continueWatching = IglooRailState.Loaded(testContinueMovies),
                        latestAlbums = IglooRailState.Loaded(testAlbums),
                    ),
                    albumDetails = albumDetailsState,
                    onAlbumSelected = { albumId ->
                        opened += albumId
                        albumDetailsState = AlbumDetailsUiState(
                            openAlbumId = albumId,
                            details = openLoads(albumId),
                        )
                    },
                    onCloseDetails = {
                        albumDetailsState = AlbumDetailsUiState()
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

    private fun loadedState(album: AlbumDetailsUi = testAlbumDetails()) = AlbumDetailsUiState(
        openAlbumId = album.id,
        details = AlbumDetailsState.Loaded(album),
    )

    private fun openAlbumCard(id: Long) {
        composeRule.onNodeWithTag("album_card_$id").requestFocus()
        composeRule.onNodeWithTag("album_card_$id")
            .assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()
    }

    @Test
    fun openingFromAnAlbumCardLandsFocusOnPlayAlbum() {
        setShellContent()

        openAlbumCard(11)

        assertEquals(listOf(11L), opened)
        composeRule.onNodeWithTag("album_play").assertIsFocused()
    }

    @Test
    fun theSkeletonHoldsTheAnchorThroughTheLoadedSwap() {
        setShellContent(openLoads = { AlbumDetailsState.Loading })

        openAlbumCard(11)

        // The stub is the screen's one focusable while loading, parked where Play Album lands.
        composeRule.onNodeWithContentDescription("Loading album details").assertIsFocused()

        albumDetailsState = albumDetailsState.copy(
            details = AlbumDetailsState.Loaded(testAlbumDetails(id = 11)),
        )
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("album_play").assertIsFocused()
    }

    @Test
    fun backClosesTheOverlayAndRestoresTheOriginatingCard() {
        setShellContent()

        // A card that is not the rail's first: restoring the rail's entry anchor instead of
        // the exact card that led away would still pass a weaker assertion.
        openAlbumCard(12)
        composeRule.onNodeWithTag("album_play").assertIsFocused()

        pressBack()

        composeRule.onNodeWithTag("album_details").assertDoesNotExist()
        composeRule.onNodeWithTag("album_card_12").assertIsFocused()
    }

    @Test
    fun downWalksActionsThenEveryTrackRowThenTheFactsPanel() {
        setShellContent(loadedState())

        val play = composeRule.onNodeWithTag("album_play")
        play.requestFocus()
        play.assertIsFocused()
        play.performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("track_play_901").assertIsFocused()
        composeRule.onNodeWithTag("track_play_901")
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("track_play_902").assertIsFocused()
        composeRule.onNodeWithTag("track_play_902")
            .performKeyInput { pressKey(Key.DirectionDown) }
        // The chain crosses the disc boundary as if the header text were not there.
        composeRule.onNodeWithTag("track_play_903").assertIsFocused()
        composeRule.onNodeWithTag("track_play_903")
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("album_details_facts").assertIsFocused()

        // Up from the facts panel returns to the nearest row, not the top of the list.
        composeRule.onNodeWithTag("album_details_facts")
            .performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("track_play_903").assertIsFocused()
    }

    @Test
    fun aRowsThreeActionsChainRightAndVerticalMovesKeepTheirColumn() {
        setShellContent(loadedState())

        val play = composeRule.onNodeWithTag("track_play_901")
        play.requestFocus()
        play.performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithTag("track_like_901").assertIsFocused()
        composeRule.onNodeWithTag("track_like_901")
            .performKeyInput { pressKey(Key.DirectionRight) }
        val more = composeRule.onNodeWithTag("track_more_901")
        more.assertIsFocused()
        // The row's right edge is pinned: the shell is composed underneath.
        more.performKeyInput { pressKey(Key.DirectionRight) }
        more.assertIsFocused()

        // Down from a column lands in the same column of the next row, and up returns.
        more.performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("track_more_902").assertIsFocused()
        composeRule.onNodeWithTag("track_more_902")
            .performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.onNodeWithTag("track_like_902").assertIsFocused()
        composeRule.onNodeWithTag("track_like_902")
            .performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("track_like_901").assertIsFocused()
        // Up from any column of the first row returns to the hero's last-focused action.
        composeRule.onNodeWithTag("track_like_901")
            .performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("album_play").assertIsFocused()
    }

    @Test
    fun upFromTheFirstRowReturnsToTheLastFocusedAction() {
        setShellContent(loadedState())

        val play = composeRule.onNodeWithTag("album_play")
        play.requestFocus()
        play.performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithTag("album_shuffle").assertIsFocused()
        composeRule.onNodeWithTag("album_shuffle")
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("track_play_901").assertIsFocused()

        composeRule.onNodeWithTag("track_play_901")
            .performKeyInput { pressKey(Key.DirectionUp) }

        // The same focus memory the movie action row keeps: up lands on Shuffle, not Play.
        composeRule.onNodeWithTag("album_shuffle").assertIsFocused()
    }

    @Test
    fun noDirectionEscapesIntoTheShellUnderneath() {
        setShellContent(loadedState())

        val play = composeRule.onNodeWithTag("album_play")
        play.requestFocus()
        // Without a spoken screen reader there is no hero reading stop, so up is pinned.
        play.performKeyInput { pressKey(Key.DirectionUp) }
        play.assertIsFocused()
        play.performKeyInput { pressKey(Key.DirectionLeft) }
        play.assertIsFocused()

        play.performKeyInput { pressKey(Key.DirectionRight) }
        val shuffle = composeRule.onNodeWithTag("album_shuffle")
        shuffle.assertIsFocused()
        shuffle.performKeyInput { pressKey(Key.DirectionRight) }
        shuffle.assertIsFocused()

        val rowPlay = composeRule.onNodeWithTag("track_play_901")
        rowPlay.requestFocus()
        rowPlay.performKeyInput { pressKey(Key.DirectionLeft) }
        rowPlay.assertIsFocused()
        val rowMore = composeRule.onNodeWithTag("track_more_901")
        rowMore.requestFocus()
        rowMore.performKeyInput { pressKey(Key.DirectionRight) }
        rowMore.assertIsFocused()

        val facts = composeRule.onNodeWithTag("album_details_facts")
        facts.requestFocus()
        facts.performKeyInput { pressKey(Key.DirectionDown) }
        facts.assertIsFocused()
        facts.performKeyInput { pressKey(Key.DirectionLeft) }
        facts.assertIsFocused()
        facts.performKeyInput { pressKey(Key.DirectionRight) }
        facts.assertIsFocused()
    }

    @Test
    fun moreStaysAFocusTargetWithNoActionUntilItHasSomewhereToGo() {
        setShellContent(loadedState())

        // An album row can only go to its artist, and there is no musician screen yet: the
        // control keeps its place in the column geometry but promises nothing (section 12).
        composeRule.onNodeWithTag("track_more_901")
            .assertHasNoClickAction()
            .requestFocus()
        composeRule.onNodeWithTag("track_more_901").assertIsFocused()
    }

    @Test
    fun theErrorStateOffersAPinnedFocusedRetry() {
        setShellContent(
            AlbumDetailsUiState(
                openAlbumId = 11,
                details = AlbumDetailsState.Error("Couldn't load this album."),
            ),
        )

        val retry = composeRule.onNodeWithContentDescription("Retry loading album details")
        retry.assertIsFocused()
        // The screen's only focusable: every direction is pinned so a spatial search cannot
        // land on an invisible shell card.
        retry.performKeyInput { pressKey(Key.DirectionUp) }
        retry.assertIsFocused()
        retry.performKeyInput { pressKey(Key.DirectionDown) }
        retry.assertIsFocused()
        retry.performKeyInput { pressKey(Key.DirectionLeft) }
        retry.assertIsFocused()
        retry.performKeyInput { pressKey(Key.DirectionRight) }
        retry.assertIsFocused()
    }
}
