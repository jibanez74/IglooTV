package com.igloo.blindpenguincoder

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
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
            composeRule.onNodeWithContentDescription("Ana").assertIsFocused().assertFullyOnscreen(composeRule)
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
            jose.assertIsFocused().assertFullyOnscreen(composeRule)
            jose.performKeyInput { pressKey(Key.DirectionRight) }
            addProfile.assertIsFocused().assertFullyOnscreen(composeRule)

            addProfile.performKeyInput { pressKey(Key.DirectionDown) }
            changeServer.assertIsFocused().assertFullyOnscreen(composeRule)
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

    /** Ana is committed last and is therefore the most recently used. */
    private fun seedTwoProfiles() = seedInactiveProfiles(
        "http://192.0.2.1:8080/api",
        testUser(1, "Jose", hasPin = true),
        testUser(2, "Ana"),
    )
}
