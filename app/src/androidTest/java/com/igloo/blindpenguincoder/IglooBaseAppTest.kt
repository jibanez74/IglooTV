package com.igloo.blindpenguincoder

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.UiScale
import com.igloo.blindpenguincoder.data.model.AuthUser
import com.igloo.blindpenguincoder.feature.home.IglooApp
import org.junit.Assert.assertTrue
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
        hasPin = false,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
    )

    private fun setShellContent(
        uiScale: UiScale = UiScale.Standard,
        onSwitchProfile: () -> Unit = {},
        onLogout: () -> Unit = {},
    ) {
        composeRule.setContent {
            IglooTheme(uiScale = uiScale) {
                IglooApp(
                    user = user,
                    onSwitchProfile = onSwitchProfile,
                    onLogout = onLogout,
                )
            }
        }
    }

    @Test
    fun shellShowsNavigationAndSignedInUser() {
        setShellContent()

        composeRule.onNodeWithTag("navigation_rail").assertIsDisplayed()
        composeRule.onNodeWithText("Your library, ready").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Home").assertIsSelected()
        composeRule.onAllNodes(isSelected()).assertCountEquals(1)
        composeRule.onNodeWithContentDescription("Signed in as Jose").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Switch profile").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Sign out").assertIsDisplayed()
    }

    @Test
    fun railHidesDecorativeTextFromTalkBack() {
        setShellContent()

        // The brand lockup and the bare profile name fade to alpha 0 when the rail is
        // collapsed; they must not exist as announceable nodes in either state.
        composeRule.onAllNodesWithText("Igloo").assertCountEquals(0)
        composeRule.onAllNodesWithText("TV").assertCountEquals(0)
        composeRule.onAllNodesWithText("Jose").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Signed in as Jose").assertExists()
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

    @Test
    fun switchProfileInvokesItsOwnCallbackAndSitsAboveSignOut() {
        var switched = false
        var loggedOut = false
        setShellContent(onSwitchProfile = { switched = true }, onLogout = { loggedOut = true })

        val switch = composeRule.onNodeWithContentDescription("Switch profile")
        val signOut = composeRule.onNodeWithContentDescription("Sign out")
        assertTrue(
            "Switch profile must sit above Sign out",
            switch.getUnclippedBoundsInRoot().bottom <= signOut.getUnclippedBoundsInRoot().top,
        )

        switch.performClick()

        composeRule.runOnIdle {
            check(switched) { "Switch profile click did not invoke onSwitchProfile" }
            check(!loggedOut) { "Switch profile must not sign the user out" }
        }
    }

    @Test
    fun accountActionsStayOnscreenAtLargeScale() {
        setShellContent(uiScale = UiScale.Large)

        val rootBottom = composeRule.onRoot().getUnclippedBoundsInRoot().bottom
        listOf("Switch profile", "Sign out").forEach { label ->
            val bounds = composeRule.onNodeWithContentDescription(label)
                .assertIsDisplayed()
                .getUnclippedBoundsInRoot()
            assertTrue("$label falls below the viewport: $bounds", bounds.bottom <= rootBottom)
        }
    }
}
