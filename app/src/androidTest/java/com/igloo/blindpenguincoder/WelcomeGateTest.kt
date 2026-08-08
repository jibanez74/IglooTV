package com.igloo.blindpenguincoder

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.igloo.blindpenguincoder.data.model.AuthUser
import com.igloo.blindpenguincoder.feature.auth.WelcomeSteps
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The welcome screen is shown for a genuine first run only. [AnimationScaleRule] is not a nicety
 * here: the ambient backdrop loops forever, and an infinite animation never lets the Compose test
 * clock go idle, so every assertion below would otherwise spin until it timed out.
 */
@RunWith(AndroidJUnit4::class)
class WelcomeGateTest {

    // Explicit order: lower is outermost, so animations are off before the compose rule sets up.
    @get:Rule(order = 0)
    val animationScale = AnimationScaleRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Test
    fun freshLaunchShowsWelcomeWithGetStartedFocused() {
        clearStoredSetup()

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.awaitScreen("Welcome to Igloo")
            composeRule.onNodeWithText("Welcome to Igloo").assertIsDisplayed()

            WelcomeSteps.forEach { step ->
                composeRule.onNodeWithContentDescription(step.accessibilityLabel)
                    .assertIsDisplayed()
            }

            composeRule.onNodeWithContentDescription("Get started").assertIsFocused()

            // The server prompt has not been reached yet.
            composeRule.onAllNodesWithText("Server address").assertCountEquals(0)
            // And the auth canvas never shows the shell.
            composeRule.onAllNodesWithContentDescription("Home, selected").assertCountEquals(0)
        }
    }

    @Test
    fun getStartedLeadsToServerSetupWithTheFieldFocused() {
        clearStoredSetup()

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.awaitScreen("Welcome to Igloo")
            composeRule.onNodeWithContentDescription("Get started").performClick()

            // The only focusable is removed in the same frame the field is added.
            composeRule.awaitScreen("Connect to your Igloo server")
            composeRule.onNodeWithContentDescription("Server address").assertIsFocused()
            composeRule.onAllNodesWithText("Welcome to Igloo").assertCountEquals(0)
        }
    }

    @Test
    fun changingTheServerSkipsTheWelcome() {
        seedProfile()

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.awaitScreen("Who's watching?")
            composeRule.onNodeWithContentDescription("Change server address").performClick()

            // A user who already had a server is reconnecting, not arriving.
            composeRule.awaitScreen("Connect to your Igloo server")
            composeRule.onAllNodesWithText("Welcome to Igloo").assertCountEquals(0)
        }
    }

    @Test
    fun theBrandMarkAndStepNumeralsAreNotAnnounced() {
        clearStoredSetup()

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.awaitScreen("Welcome to Igloo")

            // Exact match, so "Igloo" does not collide with the mark.
            composeRule.onAllNodesWithText("I").assertCountEquals(0)
            listOf("1", "2", "3").forEach { numeral ->
                composeRule.onAllNodesWithText(numeral).assertCountEquals(0)
            }
        }
    }

    private fun clearStoredSetup() {
        val app = application()
        runBlocking {
            app.container.serverSettingsStore.clear()
            app.container.profileRepository.clearAll()
        }
        app.container.serverUrlProvider.set(null)
    }

    /** Two profiles, so the picker appears and offers "Change server address". */
    private fun seedProfile() {
        val app = application()
        runBlocking {
            app.container.serverSettingsStore.save("http://192.0.2.1:8080/api")
            app.container.profileRepository.clearAll()
            app.container.profileRepository.setPending("igd_jose")
            app.container.profileRepository.commitSignIn(testUser(1, "Jose"))
            app.container.profileRepository.setPending("igd_ana")
            app.container.profileRepository.commitSignIn(testUser(2, "Ana"))
            app.container.profileRepository.deactivate()
        }
        app.container.serverUrlProvider.set(null)
    }

    private fun application() = InstrumentationRegistry.getInstrumentation()
        .targetContext.applicationContext as IglooApplication

    private fun testUser(id: Long, name: String) = AuthUser(
        id = id,
        name = name,
        email = "${name.lowercase()}@example.com",
        isAdmin = false,
        avatar = null,
        hasPin = false,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
    )
}
