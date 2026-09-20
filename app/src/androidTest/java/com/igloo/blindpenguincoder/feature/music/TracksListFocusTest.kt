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
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.feature.home.findActivity
import com.igloo.blindpenguincoder.feature.shared.AppendState
import com.igloo.blindpenguincoder.playback.model.MusicPlayRequest
import com.igloo.blindpenguincoder.playback.model.MusicPlayTrack
import com.igloo.blindpenguincoder.playback.model.MusicQueueSource
import com.igloo.blindpenguincoder.testAlbumDetails
import com.igloo.blindpenguincoder.testMusicState
import com.igloo.blindpenguincoder.testTrackEntries
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Tracks tab's focus contract (design-system.md sections 6.3 and 11.5): the spine enters
 * on Play, the three actions chain right with the edge pinned, vertical moves keep their
 * column, the first row's up returns to the last-focused action button, the tail pins the
 * bottom edge, and every way out — the player, an album opened through More, the menu itself —
 * comes back to the exact control that led away.
 */
@RunWith(AndroidJUnit4::class)
class TracksListFocusTest {

    @get:Rule(order = 0)
    val animationScale = AnimationScaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private var musicState by mutableStateOf(testMusicState(tab = MusicTab.Tracks))
    private var albumDetailsState by mutableStateOf(AlbumDetailsUiState())
    private val played = mutableListOf<Long>()
    private val openedAlbums = mutableListOf<Long>()
    private var loadMoreCalls = 0
    private var playAllCalls = 0
    private val playRequests = MutableSharedFlow<MusicPlayRequest>(extraBufferCapacity = 1)
    private var hostActivity: Activity? = null

    private fun setContent(initial: MusicUiState = testMusicState(tab = MusicTab.Tracks)) {
        musicState = initial
        albumDetailsState = AlbumDetailsUiState()
        played.clear()
        openedAlbums.clear()
        loadMoreCalls = 0
        playAllCalls = 0
        composeRule.setContent {
            val context = LocalContext.current
            SideEffect { hostActivity = context.findActivity() }
            IglooTheme {
                TestIglooApp(
                    music = musicState,
                    musicActions = MusicActions(
                        onRefresh = {},
                        onRetryFirstPage = {},
                        onRetryAppend = {},
                        onLoadMore = { loadMoreCalls += 1 },
                        onSelectTab = {},
                        onPressTab = {},
                        onPlayTrack = { played += it },
                        onPlayAll = { playAllCalls += 1 },
                        onShuffleAll = {},
                        onToggleLike = {},
                    ),
                    musicPlayRequests = playRequests,
                    albumDetails = albumDetailsState,
                    onAlbumSelected = { albumId ->
                        openedAlbums += albumId
                        albumDetailsState = AlbumDetailsUiState(
                            openAlbumId = albumId,
                            details = AlbumDetailsState.Loaded(testAlbumDetails(id = albumId)),
                        )
                    },
                    onCloseDetails = { albumDetailsState = AlbumDetailsUiState() },
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

    @Test
    fun theSpineEntersOnPlayAndTheThreeActionsChainRightWithThePinnedEdge() {
        setContent()

        val play = composeRule.onNodeWithTag("track_play_901")
        play.assertIsFocused()
        play.performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.onNodeWithContentDescription("Music").assertIsFocused()
        composeRule.onNodeWithContentDescription("Music").performKeyInput { pressKey(Key.DirectionRight) }
        play.assertIsFocused()

        play.performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithTag("track_like_901").assertIsFocused()
        composeRule.onNodeWithTag("track_like_901").performKeyInput { pressKey(Key.DirectionRight) }
        val more = composeRule.onNodeWithTag("track_more_901")
        more.assertIsFocused()
        more.performKeyInput { pressKey(Key.DirectionRight) }
        more.assertIsFocused()
    }

    @Test
    fun verticalMovesKeepTheirColumnAcrossALetterHeader() {
        setContent()

        composeRule.onNodeWithTag("track_like_901").requestFocus()
        composeRule.onNodeWithTag("track_like_901").performKeyInput { pressKey(Key.DirectionDown) }
        // 901 is the `#` bucket's only row; 902 opens the A bucket under a header the d-pad
        // never lands on.
        composeRule.onNodeWithTag("track_like_902").assertIsFocused()
        composeRule.onNodeWithTag("track_like_902").performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("track_like_901").assertIsFocused()
    }

    @Test
    fun upFromTheFirstRowReturnsToTheLastFocusedActionButton() {
        setContent()

        composeRule.onNodeWithTag("track_play_901").performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("music_play_all").assertIsFocused()
        composeRule.onNodeWithTag("music_play_all").performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithTag("music_shuffle_all").assertIsFocused()
        composeRule.onNodeWithTag("music_shuffle_all").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("track_play_901").assertIsFocused()

        composeRule.onNodeWithTag("track_play_901").performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("music_shuffle_all").assertIsFocused()
        composeRule.onNodeWithTag("music_shuffle_all").performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("music_tab_tracks").assertIsFocused()
    }

    @Test
    fun theLastRowIsPinnedUnderASkeletonTailAndThePrefetchFires() {
        setContent(
            testMusicState(
                tab = MusicTab.Tracks,
                tracks = PagedState(IglooRailState.Loaded(testTrackEntries), total = 500, append = AppendState.Idle),
            ),
        )

        val last = composeRule.onNodeWithTag("track_play_905")
        last.requestFocus()
        last.performKeyInput { pressKey(Key.DirectionDown) }
        last.assertIsFocused()
        assertTrue("the prefetch should fire within six rows of the end", loadMoreCalls > 0)
    }

    @Test
    fun aRowsPlayReportsTheTrackAndThePlayerReturnsFocusToThatPlay() {
        setContent()

        val play = composeRule.onNodeWithTag("track_play_903")
        play.requestFocus()
        play.performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()
        assertEquals(listOf(903L), played)

        // The view model answers through the play-request flow; the host opens the player.
        composeRule.runOnUiThread {
            playRequests.tryEmit(
                MusicPlayRequest(
                    source = MusicQueueSource.TrackList,
                    startIndex = 2,
                    tracks = listOf(MusicPlayTrack(903, "All You Need Is Love", 125.0)),
                ),
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("music_player").assertExists()
        composeRule.onNodeWithTag("music_play_pause").assertIsFocused()

        pressBack()

        composeRule.onNodeWithTag("music_player").assertDoesNotExist()
        composeRule.onNodeWithTag("track_play_903").assertIsFocused()
    }

    @Test
    fun goToAlbumFromMoreOpensTheAlbumAndBackLandsOnThatMore() {
        setContent()

        val more = composeRule.onNodeWithTag("track_more_902")
        more.requestFocus()
        more.performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        val goToAlbum = composeRule.onNodeWithContentDescription("Go to album")
        goToAlbum.assertIsFocused()
        goToAlbum.performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        assertEquals(listOf(11L), openedAlbums)
        composeRule.onNodeWithTag("album_details").assertExists()
        composeRule.onNodeWithTag("album_play").assertIsFocused()

        pressBack()

        composeRule.onNodeWithTag("album_details").assertDoesNotExist()
        composeRule.onNodeWithTag("track_more_902").assertIsFocused()
    }

    @Test
    fun backOnTheMenuClosesTheMenuNotThePaneAndRestoresMore() {
        setContent()

        val more = composeRule.onNodeWithTag("track_more_902")
        more.requestFocus()
        more.performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("more_menu").assertExists()

        pressBack()

        composeRule.onNodeWithTag("more_menu").assertDoesNotExist()
        composeRule.onNodeWithTag("tracks_list").assertExists()
        composeRule.onNodeWithTag("track_more_902").assertIsFocused()
    }

    @Test
    fun aRowWithNowhereToGoHasAnInertMore() {
        setContent()

        // 905 has neither album nor musician on the wire.
        composeRule.onNodeWithTag("track_more_905").requestFocus()
        composeRule.onNodeWithTag("track_more_905").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("more_menu").assertDoesNotExist()
        composeRule.onNodeWithTag("track_more_905").assertIsFocused()
    }
}
