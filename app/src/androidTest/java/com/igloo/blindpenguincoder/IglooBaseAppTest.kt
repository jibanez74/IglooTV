package com.igloo.blindpenguincoder

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.data.model.AuthUser
import com.igloo.blindpenguincoder.feature.home.IglooApp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IglooBaseAppTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val user = AuthUser(
        id = 1,
        name = "Jose",
        email = "jose@example.com",
        isAdmin = false,
        avatar = null,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
    )

    private fun setShellContent(onLogout: () -> Unit = {}) {
        composeRule.setContent {
            IglooTheme {
                IglooApp(user = user, onLogout = onLogout)
            }
        }
    }

    @Test
    fun shellShowsNavigationAndSignedInUser() {
        setShellContent()

        composeRule.onNodeWithText("Igloo").assertIsDisplayed()
        composeRule.onNodeWithText("Welcome to Igloo").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Home, selected").assertIsDisplayed()
        composeRule.onNodeWithText("Jose").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Sign out").assertIsDisplayed()
    }

    @Test
    fun navigationItemsChangeContentPane() {
        setShellContent()

        composeRule.onNodeWithContentDescription("Movies").performClick()
        composeRule.onNodeWithText("Movie library scaffolding is ready for API-backed content.").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Music").performClick()
        composeRule.onNodeWithText("Music playback dependencies are available for the next feature pass.").assertIsDisplayed()
    }

    @Test
    fun signOutInvokesLogout() {
        var loggedOut = false
        setShellContent(onLogout = { loggedOut = true })

        composeRule.onNodeWithContentDescription("Sign out").performClick()

        composeRule.runOnIdle {
            check(loggedOut) { "Sign out click did not invoke onLogout" }
        }
    }
}
