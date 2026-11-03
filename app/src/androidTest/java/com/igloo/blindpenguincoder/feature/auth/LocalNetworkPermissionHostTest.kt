package com.igloo.blindpenguincoder.feature.auth

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import com.igloo.blindpenguincoder.AnimationScaleRule
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.network.LocalNetworkPermissionCoordinator
import com.igloo.blindpenguincoder.core.network.LocalNetworkPermissionException
import com.igloo.blindpenguincoder.core.ui.IglooText
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LocalNetworkPermissionHostTest {
    @get:Rule(order = 0) val animations = AnimationScaleRule()
    @get:Rule(order = 1) val compose = createComposeRule()

    @Test
    fun denialExplainsLANAccessAndAllowsChangingToAPublicServer() {
        var changedServer = false
        val coordinator = LocalNetworkPermissionCoordinator({ false }, { true }, { fail("no automatic re-prompt") }, { true })
        assertThrows(LocalNetworkPermissionException::class.java) { coordinator.checkAccess("https://igloo.local") }
        compose.setContent {
            LocalNetworkPermissionHost(coordinator, onChangeServer = { changedServer = true }) {
                IglooTheme { IglooText("Server setup", style = IglooTheme.typography.bodyLarge, color = IglooTheme.colors.foreground) }
            }
        }
        compose.onNodeWithText("Allow local network access").assertIsDisplayed()
        compose.onNodeWithContentDescription("Retry").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open Settings").assertIsDisplayed()
        compose.onNodeWithContentDescription("Use another server").performClick()
        compose.runOnIdle {
            assertTrue(changedServer)
            assertNull(coordinator.state.value.blockedHost)
        }
        compose.onNodeWithText("Server setup").assertIsDisplayed()
    }

    @Test
    fun settingsRecoveryDismissesTheExplanationAndPublishesOneRetryGeneration() {
        var granted = false
        val coordinator = LocalNetworkPermissionCoordinator({ granted }, { true }, { fail() }, { true })
        assertThrows(LocalNetworkPermissionException::class.java) { coordinator.checkAccess("http://192.168.1.5") }
        compose.setContent {
            LocalNetworkPermissionHost(coordinator, onChangeServer = {}) {
                val generation = LocalNetworkRecovery.current
                IglooTheme { IglooText("Recovery $generation", style = IglooTheme.typography.bodyLarge, color = IglooTheme.colors.foreground) }
            }
        }
        compose.onNodeWithText("Allow local network access").assertIsDisplayed()
        compose.runOnIdle {
            granted = true
            coordinator.refresh()
            coordinator.refresh()
        }
        compose.onNodeWithText("Recovery 1").assertIsDisplayed()
        compose.onNodeWithText("Allow local network access").assertDoesNotExist()
    }
}
