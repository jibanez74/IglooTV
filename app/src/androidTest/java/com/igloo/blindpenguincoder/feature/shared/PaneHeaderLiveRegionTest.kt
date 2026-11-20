package com.igloo.blindpenguincoder.feature.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The spoken count rides a 1dp live-region node, and TalkBack re-reads a live region whenever
 * its node changes — its description or its layout. The description is the caller's to keep
 * steady (a pane holds the last count through a request); this pins the other half, that
 * nothing the header draws can move the node. With the count beside the title, the header is
 * shorter than its actions, and a centred header shifted with every change of its own height.
 */
@RunWith(AndroidJUnit4::class)
class PaneHeaderLiveRegionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var countText by mutableStateOf(WRAPPING_COUNT)
    private var notice by mutableStateOf<String?>(null)

    private fun setHeader() {
        composeRule.setContent {
            IglooTheme {
                // Narrow enough that the long caption wraps under itself beside the title.
                Box(Modifier.width(HEADER_WIDTH).testTag(HEADER_TAG)) {
                    PaneHeader(
                        title = "Search",
                        countText = countText,
                        countDescription = "12 results",
                        countTag = COUNT_TAG,
                        notice = notice,
                        contentInset = PaddingValues(),
                    ) {
                        IglooButton(text = "Clear", onClick = {})
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun countNodeBounds() = composeRule.onNodeWithTag(COUNT_TAG).getUnclippedBoundsInRoot()

    private fun headerHeight() =
        composeRule.onNodeWithTag(HEADER_TAG).getUnclippedBoundsInRoot().let { it.bottom - it.top }

    @Test
    fun theSpokenCountNodeStaysPutWhenAWrappedCaptionGivesWayToAShortOne() {
        setHeader()
        val wrapped = countNodeBounds()
        val tall = headerHeight()

        countText = "Searching…"
        composeRule.waitForIdle()

        // The caption did wrap, or the move this guards against could not have happened.
        assertTrue("the long caption never wrapped", headerHeight() < tall)
        assertEquals(wrapped, countNodeBounds())
    }

    @Test
    fun theSpokenCountNodeStaysPutWhenANoticeArrives() {
        countText = "12 results"
        setHeader()
        val plain = countNodeBounds()

        notice = "Couldn't refresh. Showing what loaded before."
        composeRule.waitForIdle()

        assertEquals(plain, countNodeBounds())
    }

    private companion object {
        const val COUNT_TAG = "pane_count"
        const val HEADER_TAG = "pane_header"
        val HEADER_WIDTH = 520.dp
        const val WRAPPING_COUNT =
            "12 movies, 4 shows, 31 albums, 9 musicians and 118 tracks match what you typed"
    }
}
