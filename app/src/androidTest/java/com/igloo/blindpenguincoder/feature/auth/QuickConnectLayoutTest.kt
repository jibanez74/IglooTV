package com.igloo.blindpenguincoder.feature.auth

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.LocalIglooReducedMotion
import com.igloo.blindpenguincoder.core.design.UiScale
import com.igloo.blindpenguincoder.core.design.viewportFactor
import com.igloo.blindpenguincoder.core.error.AppError
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val SERVER_ORIGIN = "http://192.0.2.1:8080"

/** The 960×540 box the card is measured against — see `assertFullyOnscreen`. */
private const val VIEWPORT_TAG = "tv-viewport"

private const val NOTICE =
    "Signed out on this TV. The server couldn't be reached, so it may still list " +
        "this TV as signed in."

private const val INSTRUCTIONS =
    "Scan the QR code to open Account settings. Sign in through your " +
        "browser if asked, then enter the six-character TV code."

/**
 * The quick-connect card must fit 960×540 without scrolling in its resting state. Focus lands on a
 * bottom control the moment the screen composes, so any overflow immediately scrolls the header out
 * of sight — see docs/design-system.md section 11.1.3.
 */
@RunWith(AndroidJUnit4::class)
class QuickConnectLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun theRestingCardFitsTheTvViewport() {
        setContent(phase = QuickConnectPhase.RequestingCode, restoreError = null)

        // Focus is already on the switch button, so anything below still onscreen means nothing
        // above it was scrolled away to get there.
        composeRule.onNodeWithContentDescription("Use email and password instead")
            .assertIsFocused()
            .assertFullyOnscreen("switch button")

        composeRule.onNodeWithText("Sign in to Igloo").assertFullyOnscreen("title")
        composeRule.onNodeWithText(SERVER_ORIGIN).assertFullyOnscreen("server address")
        composeRule.onNodeWithContentDescription("Requesting pairing code")
            .assertFullyOnscreen("pairing box")
        composeRule.onNodeWithText(INSTRUCTIONS).assertFullyOnscreen("instructions")
        composeRule.onNodeWithText("$SERVER_ORIGIN/settings/account")
            .assertFullyOnscreen("approval url")
        composeRule.onNodeWithContentDescription("Change server address")
            .assertFullyOnscreen("leave button")
    }

    /**
     * An inline error is a degraded state and may push the card past the viewport; section 11.1
     * gives the card form an internal scroll for exactly this. What must not degrade is reaching
     * the controls.
     */
    @Test
    fun anInlineErrorMayScrollButLeavesEveryControlReachable() {
        setContent(
            phase = QuickConnectPhase.Failed("Couldn't reach the server."),
            restoreError = AppError.Network,
        )

        composeRule.onNodeWithContentDescription("Use email and password instead")
            .assertIsFocused()
            .assertFullyOnscreen("switch button with errors")
        composeRule.onNodeWithContentDescription("Change server address")
            .assertFullyOnscreen("leave button with errors")
        composeRule.onNodeWithContentDescription("Request a new pairing code").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Retry connecting to server").assertIsDisplayed()
    }

    /**
     * The sign-out notice: signing out the last profile lands here rather than on the picker, so
     * this screen carries the announced-message slot too (section 11.1.3). Like an inline error it
     * is a degraded state and may scroll; what must hold is that it is announced and every control
     * stays reachable.
     */
    @Test
    fun aNoticeIsAnnouncedAndLeavesEveryControlReachable() {
        setContent(
            phase = QuickConnectPhase.RequestingCode,
            restoreError = null,
            notice = NOTICE,
        )

        composeRule.onNodeWithText(NOTICE)
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
        composeRule.onNodeWithContentDescription("Use email and password instead")
            .assertIsFocused()
            .assertFullyOnscreen("switch button with a notice")
        composeRule.onNodeWithContentDescription("Change server address")
            .assertFullyOnscreen("leave button with a notice")
    }

    private fun setContent(
        phase: QuickConnectPhase,
        restoreError: AppError?,
        notice: String? = null,
    ) {
        composeRule.setContent {
            assertReferenceViewport()
            IglooTheme(uiScale = UiScale.Standard) {
                CompositionLocalProvider(LocalIglooReducedMotion provides true) {
                    Box(
                        Modifier
                            .size(width = 960.dp, height = 540.dp)
                            .testTag(VIEWPORT_TAG),
                    ) {
                        QuickConnectContent(
                            phase = phase,
                            serverOrigin = SERVER_ORIGIN,
                            restoreError = restoreError,
                            canCancel = false,
                            onRetryRestore = {},
                            onRetryPairing = {},
                            onSwitchToPassword = {},
                            onLeave = {},
                            notice = notice,
                        )
                    }
                }
            }
        }
    }

    /** IglooTheme derives its viewport factor from the window, not from the box above. */
    @Composable
    private fun assertReferenceViewport() {
        val widthDp = LocalWindowInfo.current.containerSize.width / LocalDensity.current.density
        assertTrue(
            "test device reports ${widthDp}dp wide; the budget assumes the 960dp reference viewport",
            viewportFactor(widthDp) == 1f,
        )
    }

    /**
     * Measured against the 960×540 box, not `onRoot()`. The root is the whole test-activity
     * window, which on a taller device leaves room for content that overflows a real TV panel to
     * still sit inside it — the assertion would pass on a card that does not fit.
     */
    private fun SemanticsNodeInteraction.assertFullyOnscreen(
        case: String,
    ): SemanticsNodeInteraction {
        assertIsDisplayed()
        val viewport = composeRule.onNodeWithTag(VIEWPORT_TAG).getUnclippedBoundsInRoot()
        val bounds = getUnclippedBoundsInRoot()
        assertTrue("$case: starts above the viewport: $bounds", bounds.top >= viewport.top)
        assertTrue("$case: ends below the viewport: $bounds", bounds.bottom <= viewport.bottom)
        return this
    }
}
