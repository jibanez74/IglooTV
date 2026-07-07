package com.igloo.blindpenguincoder

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
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
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as IglooApplication
        runBlocking {
            app.container.serverSettingsStore.clear()
            app.container.cookiesStorage.clear()
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.onNodeWithText("Connect to your Igloo server").assertIsDisplayed()
            composeRule.onNodeWithText("Server address").assertIsDisplayed()

            composeRule.onAllNodesWithContentDescription("Home, selected")
                .assertCountEquals(0)
        }
    }
}
