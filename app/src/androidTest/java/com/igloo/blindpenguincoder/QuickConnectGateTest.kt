package com.igloo.blindpenguincoder

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * With a stored server but no device token, restore() lands on NeedsLogin
 * without any network call, so the default sign-in screen — Quick Connect —
 * shows deterministically. The seeded address is TEST-NET (never routable):
 * the background initiate can only sit pending or fail, and no assertion
 * here depends on which.
 */
@RunWith(AndroidJUnit4::class)
class QuickConnectGateTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun signInDefaultsToQuickConnectWithFocusOnTheSwitchButton() {
        seedServerWithoutToken()

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.awaitScreen("Sign in to Igloo")
            composeRule.onNodeWithText("Sign in to Igloo").assertIsDisplayed()
            composeRule.onNodeWithText("http://192.0.2.1:8080").assertIsDisplayed()
            composeRule.onNodeWithText(
                "Scan the QR code to open Account settings. Sign in through your " +
                    "browser if asked, then enter the six-character TV code.",
            ).assertIsDisplayed()
            composeRule
                .onNodeWithText("http://192.0.2.1:8080/settings/account")
                .assertIsDisplayed()
            composeRule
                .onNodeWithContentDescription("Requesting pairing code")
                .assertIsDisplayed()

            val passwordMode = composeRule
                .onNodeWithContentDescription("Use email and password instead")
            val changeServer = composeRule
                .onNodeWithContentDescription("Change server address")

            passwordMode
                .assertIsDisplayed()
                .assertIsFocused()
            changeServer.assertIsDisplayed()

            passwordMode.performKeyInput { pressKey(Key.DirectionRight) }
            changeServer.assertIsFocused()
            changeServer.performKeyInput { pressKey(Key.DirectionLeft) }
            passwordMode.assertIsFocused()
        }
    }

    @Test
    fun switchingToPasswordModeFocusesTheEmailField() {
        seedServerWithoutToken()

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.awaitScreen("Sign in to Igloo")
            composeRule
                .onNodeWithContentDescription("Use email and password instead")
                .performClick()

            val email = composeRule.onNodeWithContentDescription("Email")
            val password = composeRule.onNodeWithContentDescription("Password")
            email.assertIsFocused().performTextInput("jose@example.com")
            email.performKeyInput { pressKey(Key.DirectionDown) }
            password.assertIsFocused().performTextInput("not-a-real-password")

            composeRule
                .onNodeWithContentDescription("Use pairing code instead")
                .performClick()

            composeRule
                .onNodeWithContentDescription("Use email and password instead")
                .assertIsFocused()
                .performClick()

            email.assertTextEquals("jose@example.com")
            password.assertTextEquals("")
        }
    }

    @Test
    fun passwordFormScrollsEveryFocusedControlFullyOnscreenAtTvViewport() {
        seedServerWithUnreachableToken()

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.awaitScreen("Sign in to Igloo")
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule
                    .onAllNodesWithContentDescription("Use email and password instead")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            composeRule
                .onNodeWithContentDescription("Use email and password instead")
                .performClick()

            val recovery = composeRule.onNodeWithContentDescription("Retry connecting to server")
            val email = composeRule.onNodeWithContentDescription("Email")
            val password = composeRule.onNodeWithContentDescription("Password")
            val signIn = composeRule.onNodeWithContentDescription("Sign in")
            val pairing = composeRule.onNodeWithContentDescription("Use pairing code instead")
            val changeServer = composeRule.onNodeWithContentDescription("Change server address")

            email.assertIsFocused().assertFullyOnscreen()
            email.performKeyInput { pressKey(Key.DirectionUp) }
            recovery.assertIsFocused().assertFullyOnscreen()

            recovery.performKeyInput { pressKey(Key.DirectionDown) }
            email.assertIsFocused().assertFullyOnscreen()
            email.performKeyInput { pressKey(Key.DirectionDown) }
            password.assertIsFocused().assertFullyOnscreen()
            password.performKeyInput { pressKey(Key.DirectionDown) }
            signIn.assertIsFocused().assertFullyOnscreen()
            signIn.performKeyInput { pressKey(Key.DirectionDown) }
            pairing.assertIsFocused().assertFullyOnscreen()
            pairing.performKeyInput { pressKey(Key.DirectionDown) }
            changeServer.assertIsFocused().assertFullyOnscreen()
        }
    }

    private fun seedServerWithoutToken() {
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as IglooApplication
        runBlocking {
            app.container.serverSettingsStore.save("http://192.0.2.1:8080/api")
            app.container.profileRepository.clearAll()
        }
        app.container.serverUrlProvider.set(null)
    }

    private fun seedServerWithUnreachableToken() {
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as IglooApplication
        runBlocking {
            app.container.serverSettingsStore.save("http://127.0.0.1:1/api")
            app.container.profileRepository.clearAll()
            // Pending, not committed: pairing resumes against a server that never answers.
            app.container.profileRepository.setPending("igd_unreachable")
        }
        app.container.serverUrlProvider.set(null)
    }

    private fun SemanticsNodeInteraction.assertFullyOnscreen(): SemanticsNodeInteraction {
        assertIsDisplayed()
        val root = composeRule.onRoot().getUnclippedBoundsInRoot()
        val bounds = getUnclippedBoundsInRoot()
        assertTrue("Focused control starts above the viewport: $bounds", bounds.top >= root.top)
        assertTrue("Focused control ends below the viewport: $bounds", bounds.bottom <= root.bottom)
        return this
    }
}
