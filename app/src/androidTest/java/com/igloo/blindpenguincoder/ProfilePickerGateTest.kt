package com.igloo.blindpenguincoder

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.igloo.blindpenguincoder.data.model.AuthUser
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two stored profiles put the picker up with no network call at all, so the seeded
 * TEST-NET address never has to answer for any assertion here.
 */
@RunWith(AndroidJUnit4::class)
class ProfilePickerGateTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun pickerListsProfilesMostRecentFirstWithoutNavChrome() {
        seedTwoProfiles()

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.awaitScreen("Who's watching?")
            composeRule.onNodeWithText("Who's watching?").assertIsDisplayed()
            composeRule.onNodeWithContentDescription("Ana").assertIsDisplayed()
            composeRule.onNodeWithContentDescription("Jose, PIN required").assertIsDisplayed()
            composeRule.onNodeWithContentDescription("Add profile").assertIsDisplayed()
            composeRule.onNodeWithContentDescription("Change server address").assertIsDisplayed()

            // The auth canvas never shows the shell.
            composeRule.onAllNodesWithTag("navigation_rail").assertCountEquals(0)
        }
    }

    @Test
    fun theLastUsedProfileIsFocusedFirst() {
        seedTwoProfiles()

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.awaitScreen("Who's watching?")
            composeRule.onNodeWithContentDescription("Ana").assertIsFocused().assertFullyOnscreen()
        }
    }

    @Test
    fun dpadReachesEveryControlAndComesBack() {
        seedTwoProfiles()

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.awaitScreen("Who's watching?")
            val ana = composeRule.onNodeWithContentDescription("Ana")
            val jose = composeRule.onNodeWithContentDescription("Jose, PIN required")
            val addProfile = composeRule.onNodeWithContentDescription("Add profile")
            val changeServer = composeRule.onNodeWithContentDescription("Change server address")

            ana.assertIsFocused()
            ana.performKeyInput { pressKey(Key.DirectionRight) }
            jose.assertIsFocused().assertFullyOnscreen()
            jose.performKeyInput { pressKey(Key.DirectionRight) }
            addProfile.assertIsFocused().assertFullyOnscreen()

            addProfile.performKeyInput { pressKey(Key.DirectionDown) }
            changeServer.assertIsFocused().assertFullyOnscreen()
            changeServer.performKeyInput { pressKey(Key.DirectionUp) }
            addProfile.assertIsFocused()
        }
    }

    @Test
    fun theRowDoesNotWrapAtItsEnds() {
        seedTwoProfiles()

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.awaitScreen("Who's watching?")
            val ana = composeRule.onNodeWithContentDescription("Ana")

            ana.assertIsFocused()
            ana.performKeyInput { pressKey(Key.DirectionLeft) }

            ana.assertIsFocused()
        }
    }

    /**
     * Committing a sign-in is pure vault work, so this seeds real profiles without
     * a server. Ana is committed last and is therefore the most recently used.
     */
    private fun seedTwoProfiles() {
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as IglooApplication
        runBlocking {
            app.container.serverSettingsStore.save("http://192.0.2.1:8080/api")
            app.container.profileRepository.clearAll()
            app.container.profileRepository.setPending("igd_jose")
            app.container.profileRepository.commitSignIn(testUser(1, "Jose", hasPin = true))
            // Distinct wall-clock stamps, so "most recently used" is unambiguous.
            delay(2)
            app.container.profileRepository.setPending("igd_ana")
            app.container.profileRepository.commitSignIn(testUser(2, "Ana", hasPin = false))
            app.container.profileRepository.deactivate()
        }
        app.container.serverUrlProvider.set(null)
    }

    private fun testUser(id: Long, name: String, hasPin: Boolean) = AuthUser(
        id = id,
        name = name,
        isAdmin = false,
        avatar = null,
        hasPin = hasPin,
    )

    private fun SemanticsNodeInteraction.assertFullyOnscreen(): SemanticsNodeInteraction {
        assertIsDisplayed()
        val root = composeRule.onRoot().getUnclippedBoundsInRoot()
        val bounds = getUnclippedBoundsInRoot()
        assertTrue("Focused control starts above the viewport: $bounds", bounds.top >= root.top)
        assertTrue("Focused control ends below the viewport: $bounds", bounds.bottom <= root.bottom)
        return this
    }
}
