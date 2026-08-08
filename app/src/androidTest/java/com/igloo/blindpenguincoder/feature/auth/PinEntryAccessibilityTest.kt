package com.igloo.blindpenguincoder.feature.auth

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.igloo.blindpenguincoder.IglooApplication
import com.igloo.blindpenguincoder.MainActivity
import com.igloo.blindpenguincoder.awaitScreen
import com.igloo.blindpenguincoder.data.model.AuthUser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A single PIN-protected profile lands straight on the gate with no network call, so
 * these assertions never depend on the seeded TEST-NET address answering.
 */
@RunWith(AndroidJUnit4::class)
class PinEntryAccessibilityTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun theGateOpensOnTheKeypadWithAnEmptyIndicator() {
        seedPinProtectedProfile()

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.awaitScreen("Enter Jose's PIN")
            composeRule.onNodeWithText("Enter Jose's PIN").assertIsDisplayed()
            composeRule.onNodeWithContentDescription("1").assertIsFocused()
            composeRule
                .onNodeWithContentDescription("PIN, 0 of 4 digits entered")
                .assertIsDisplayed()
        }
    }

    @Test
    fun theIndicatorAnnouncesProgressPolitelyWithoutTheDigits() {
        seedPinProtectedProfile()

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.awaitScreen("Enter Jose's PIN")
            composeRule.onNodeWithContentDescription("7").performClick()
            composeRule.onNodeWithContentDescription("7").performClick()

            composeRule
                .onNodeWithContentDescription("PIN, 2 of 4 digits entered")
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.LiveRegion,
                        LiveRegionMode.Polite,
                    ),
                )
        }
    }

    @Test
    fun noEnteredDigitEverReachesTheAccessibilityTree() {
        seedPinProtectedProfile()

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.awaitScreen("Enter Jose's PIN")
            // 8 and 9 are not on any key label used here, so finding them would mean the
            // entered PIN leaked into the tree.
            composeRule.onNodeWithContentDescription("8").performClick()
            composeRule.onNodeWithContentDescription("9").performClick()

            val tree = composeRule.onRoot().printToString(maxDepth = Int.MAX_VALUE)
            // Only what a screen reader would speak. printToString also prints node ids and pixel
            // geometry, and matching against those makes the assertion fire on any unrelated
            // composition change that shifts a node id onto "89".
            val spoken = tree.lineSequence()
                .filter { it.contains("Text = ") || it.contains("ContentDescription = ") }
                .joinToString("\n")
            assertFalse(
                "The entered PIN leaked into the semantics tree:\n$spoken",
                spoken.contains("89") || spoken.contains("PIN: 8"),
            )
            composeRule
                .onNodeWithContentDescription("PIN, 2 of 4 digits entered")
                .assertIsDisplayed()
        }
    }

    @Test
    fun deleteRemovesTheLastDigitOnly() {
        seedPinProtectedProfile()

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.awaitScreen("Enter Jose's PIN")
            composeRule.onNodeWithContentDescription("1").performClick()
            composeRule.onNodeWithContentDescription("2").performClick()

            composeRule.onNodeWithContentDescription("Delete last digit").performClick()

            composeRule
                .onNodeWithContentDescription("PIN, 1 of 4 digits entered")
                .assertIsDisplayed()
        }
    }

    @Test
    fun theKeypadIsTraversedRowMajorAndExitsToTheFooter() {
        seedPinProtectedProfile()

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.awaitScreen("Enter Jose's PIN")
            composeRule.onNodeWithContentDescription("1").assertIsFocused()
                .performKeyInput { pressKey(Key.DirectionRight) }
            composeRule.onNodeWithContentDescription("2").assertIsFocused()
                .performKeyInput { pressKey(Key.DirectionRight) }
            composeRule.onNodeWithContentDescription("3").assertIsFocused()
                .performKeyInput { pressKey(Key.DirectionDown) }
            composeRule.onNodeWithContentDescription("6").assertIsFocused()
        }
    }

    @Test
    fun theLastRowLeadsToBackToProfiles() {
        seedPinProtectedProfile()

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.awaitScreen("Enter Jose's PIN")

            // 1 -> 4 -> 7 -> Delete, then out of the keypad entirely.
            composeRule.onNodeWithContentDescription("1").assertIsFocused()
                .performKeyInput { pressKey(Key.DirectionDown) }
            composeRule.onNodeWithContentDescription("4").assertIsFocused()
                .performKeyInput { pressKey(Key.DirectionDown) }
            composeRule.onNodeWithContentDescription("7").assertIsFocused()
                .performKeyInput { pressKey(Key.DirectionDown) }
            composeRule.onNodeWithContentDescription("Delete last digit").assertIsFocused()
                .performKeyInput { pressKey(Key.DirectionDown) }

            composeRule.onNodeWithContentDescription("Back to profiles").assertIsFocused()
        }
    }

    /**
     * Coming back up from the footer used to land on the `1` key at the top-left, three rows away
     * from wherever the user had actually left the pad.
     */
    @Test
    fun leavingTheKeypadAndReturningLandsOnTheKeyTheUserLeft() {
        seedPinProtectedProfile()

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.awaitScreen("Enter Jose's PIN")

            composeRule.onNodeWithContentDescription("1").assertIsFocused()
                .performKeyInput { pressKey(Key.DirectionRight) }
            composeRule.onNodeWithContentDescription("2").assertIsFocused()
                .performKeyInput { pressKey(Key.DirectionDown) }
            composeRule.onNodeWithContentDescription("5").assertIsFocused()
                .performKeyInput { pressKey(Key.DirectionDown) }
            composeRule.onNodeWithContentDescription("8").assertIsFocused()
                .performKeyInput { pressKey(Key.DirectionDown) }
            composeRule.onNodeWithContentDescription("0").assertIsFocused()
                .performKeyInput { pressKey(Key.DirectionDown) }

            composeRule.onNodeWithContentDescription("Back to profiles").assertIsFocused()
                .performKeyInput { pressKey(Key.DirectionUp) }

            composeRule.onNodeWithContentDescription("0").assertIsFocused()
        }
    }

    /**
     * Verifying disables all eleven keys, so the focused key stops being focusable and focus is
     * cleared for the duration of the request. The restore used to be keyed on `Unit` — it ran
     * once at composition and never again — so a rejected PIN left the screen with nothing
     * focused and no way back onto the pad.
     *
     * The seeded address is TEST-NET, so the verification fails on connect rather than on a
     * wrong PIN. Either way it is the `isVerifying` false edge that has to hand focus back, which
     * is the thing under test.
     */
    @Test
    fun aRejectedPinHandsFocusBackToTheKeypad() {
        seedPinProtectedProfile()

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.awaitScreen("Enter Jose's PIN")
            repeat(PIN_LENGTH) {
                composeRule.onNodeWithContentDescription("1").performClick()
            }

            composeRule.waitUntil(VERIFY_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithContentDescription("PIN, 0 of 4 digits entered")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithContentDescription("1").assertIsFocused()
        }
    }

    private fun seedPinProtectedProfile() {
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as IglooApplication
        runBlocking {
            app.container.serverSettingsStore.save("http://192.0.2.1:8080/api")
            app.container.profileRepository.clearAll()
            app.container.profileRepository.setPending("igd_jose")
            app.container.profileRepository.commitSignIn(
                AuthUser(
                    id = 1,
                    name = "Jose",
                    email = "jose@example.com",
                    isAdmin = false,
                    avatar = null,
                    hasPin = true,
                    createdAt = "2026-01-01T00:00:00Z",
                    updatedAt = "2026-01-01T00:00:00Z",
                ),
            )
            app.container.profileRepository.deactivate()
        }
        app.container.serverUrlProvider.set(null)
    }
}

private const val VERIFY_TIMEOUT_MILLIS = 25_000L
