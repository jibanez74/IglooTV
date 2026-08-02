package com.igloo.blindpenguincoder

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import kotlinx.coroutines.runBlocking
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
            composeRule
                .onNodeWithContentDescription("Use email and password instead")
                .performClick()

            composeRule.onNodeWithContentDescription("Email").assertIsFocused()

            composeRule
                .onNodeWithContentDescription("Use pairing code instead")
                .performClick()

            composeRule
                .onNodeWithContentDescription("Use email and password instead")
                .assertIsFocused()
        }
    }

    private fun seedServerWithoutToken() {
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as IglooApplication
        runBlocking {
            app.container.serverSettingsStore.save("http://192.0.2.1:8080/api")
            app.container.deviceTokenProvider.clear()
        }
        app.container.serverUrlProvider.set(null)
    }
}
