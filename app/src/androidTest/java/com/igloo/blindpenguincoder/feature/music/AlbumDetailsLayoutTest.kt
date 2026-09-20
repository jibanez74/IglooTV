package com.igloo.blindpenguincoder.feature.music

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.UiScale
import com.igloo.blindpenguincoder.core.design.viewportFactor
import com.igloo.blindpenguincoder.feature.shared.TrackRowRequesters
import com.igloo.blindpenguincoder.testAlbumDetails
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Artist credits must wrap rather than leave the 960x540 TV safe content width. */
@RunWith(AndroidJUnit4::class)
class AlbumDetailsLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var album by mutableStateOf(testAlbumDetails())
    private var uiScale by mutableStateOf(UiScale.Standard)
    private var fontScale by mutableStateOf(1f)
    private var safeAreaHorizontal: Dp = Dp.Unspecified

    @Test
    fun artistChipsWrapInsideTheSafeAreaAtEveryScale() {
        setSectionsContent()

        UiScale.entries.forEach { scale ->
            listOf(1f, 1.30f).forEach { font ->
                composeRule.runOnUiThread {
                    uiScale = scale
                    fontScale = font
                    album = testAlbumDetails().copy(artists = listOf(AlbumArtistUi(4, "The Beatles")))
                }
                composeRule.waitForIdle()
                val oneRow = artistBounds()

                composeRule.runOnUiThread {
                    album = testAlbumDetails().copy(
                        artists = WRAPPING_ARTISTS.mapIndexed { index, name -> AlbumArtistUi(index.toLong(), name) },
                    )
                }
                composeRule.waitForIdle()
                val wrapped = artistBounds()
                val viewport = viewportBounds()
                val inset = with(composeRule.density) { safeAreaHorizontal.toPx() }
                val case = "$scale at font scale $font"

                assertTrue(
                    "$case should add rows instead of clipping credits: $oneRow -> $wrapped",
                    wrapped.height > oneRow.height,
                )
                assertTrue(
                    "$case should start at the safe inset: ${wrapped.left} vs ${viewport.left + inset}",
                    abs(wrapped.left - (viewport.left + inset)) < 2f,
                )
                assertTrue(
                    "$case should end at the safe inset: ${wrapped.right} vs ${viewport.right - inset}",
                    abs(wrapped.right - (viewport.right - inset)) < 2f,
                )
            }
        }
    }

    private fun setSectionsContent() {
        composeRule.setContent {
            assertReferenceViewport()
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
            ) {
                IglooTheme(uiScale = uiScale) {
                    safeAreaHorizontal = IglooTheme.layout.safeAreaHorizontal
                    Box(
                        Modifier
                            .size(VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
                            .testTag(VIEWPORT_TAG),
                    ) {
                        val factsRequester = remember { FocusRequester() }
                        val playReturnRequester = remember { FocusRequester() }
                        val trackRequesters = remember(album.discs) {
                            List(album.discs.sumOf { it.tracks.size }) { TrackRowRequesters() }
                        }
                        AlbumDetailsSections(
                            album = album,
                            likes = TrackLikesUiState(likedIds = emptySet()),
                            artistRequesters = emptyList(),
                            trackRequesters = trackRequesters,
                            factsRequester = factsRequester,
                            upFromBelow = null,
                            playReturnRow = null,
                            playReturnRequester = playReturnRequester,
                            onOpenMusician = null,
                            onPlayTrack = {},
                            onToggleLike = {},
                            onOpenMore = { _, _ -> },
                            contentInset = PaddingValues(horizontal = safeAreaHorizontal),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun artistBounds() = composeRule.onNodeWithTag("album_artists")
        .fetchSemanticsNode().boundsInRoot

    private fun viewportBounds() = composeRule.onNodeWithTag(VIEWPORT_TAG)
        .fetchSemanticsNode().boundsInRoot

    @Composable
    private fun assertReferenceViewport() {
        val widthDp = LocalWindowInfo.current.containerSize.width / LocalDensity.current.density
        assertTrue(
            "test device reports ${widthDp}dp wide; this suite assumes the 960dp reference viewport",
            viewportFactor(widthDp) == 1f,
        )
    }

    private companion object {
        const val VIEWPORT_TAG = "album-layout-viewport"
        val VIEWPORT_WIDTH = 960.dp
        val VIEWPORT_HEIGHT = 540.dp
        val WRAPPING_ARTISTS = listOf(
            "Alexandria Northern Ensemble",
            "Benjamin Evergreen Collective",
            "Cassandra Harbor Orchestra",
            "Dominic Winter Quartet",
            "Eleanor Glacier Chorus",
            "Frederick Aurora Project",
        )
    }
}
