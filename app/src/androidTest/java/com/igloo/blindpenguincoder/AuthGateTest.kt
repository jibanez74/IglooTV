package com.igloo.blindpenguincoder

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * With no stored server URL, a fresh launch must land on the server setup
 * screen with no nav chrome. Deterministic: reaching NeedsServer requires
 * no network. State is cleared before the activity launches, so the launch
 * is done manually instead of via createAndroidComposeRule.
 */
@RunWith(AndroidJUnit4::class)
class AuthGateTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun freshLaunchShowsServerSetupWithoutNavChrome() {
        clearStoredSetup()

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.onNodeWithText("Connect to your Igloo server").assertIsDisplayed()
            composeRule.onNodeWithText("Server address").assertIsDisplayed()
            composeRule.onNodeWithText("http://192.168.1.5:8080").assertIsDisplayed()
            composeRule.onNodeWithContentDescription("Server address").assertIsFocused()

            composeRule.onAllNodesWithContentDescription("Home, selected")
                .assertCountEquals(0)
        }
    }

    @Test
    fun dpadMovesFromAddressToConnectAndValidationReturnsFocus() {
        clearStoredSetup()

        ActivityScenario.launch(MainActivity::class.java).use {
            val address = composeRule.onNodeWithContentDescription("Server address")
            address.performTextInput("http://igloo.local/media")
            address.performKeyInput { pressKey(Key.DirectionDown) }

            val connect = composeRule.onNodeWithContentDescription("Connect to server")
            connect.assertIsFocused()
            connect.performClick()

            val message = "Enter only the server address, optionally ending in /api."
            val banner = composeRule.onNodeWithText(message)
            banner.assertIsDisplayed()
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.LiveRegion,
                        LiveRegionMode.Assertive,
                    ),
                )
            // The banner must sit above the input, where an open IME cannot cover it.
            assertTrue(
                banner.getUnclippedBoundsInRoot().bottom <
                    address.getUnclippedBoundsInRoot().top,
            )
            address.assertIsFocused()
            address.assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, message))
        }
    }

    private fun clearStoredSetup() {
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as IglooApplication
        runBlocking {
            app.container.serverSettingsStore.clear()
            app.container.profileRepository.clearAll()
        }
        app.container.serverUrlProvider.set(null)
    }
}
