package com.igloo.blindpenguincoder

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IglooBaseAppTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appLaunchesIntoTvShell() {
        composeRule.onNodeWithText("Igloo").assertIsDisplayed()
        composeRule.onNodeWithText("Welcome to Igloo").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Home, selected").assertIsDisplayed()
    }

    @Test
    fun navigationItemsChangeContentPane() {
        composeRule.onNodeWithContentDescription("Movies").performClick()
        composeRule.onNodeWithText("Movie library scaffolding is ready for API-backed content.").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Music").performClick()
        composeRule.onNodeWithText("Music playback dependencies are available for the next feature pass.").assertIsDisplayed()
    }
}
