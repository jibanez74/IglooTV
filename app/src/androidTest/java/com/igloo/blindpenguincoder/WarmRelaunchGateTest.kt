package com.igloo.blindpenguincoder

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.igloo.blindpenguincoder.data.model.AuthUser
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A launch is a new Activity, and only that (design-system section 11.1.2).
 *
 * `SessionManager` is a process singleton, so backing out of Igloo leaves the session published
 * while the Activity dies, and the next launcher press composes a new Activity over it. That used
 * to render the signed-in shell — and fetch its user-scoped rails — for a whole round-trip before
 * the PIN gate came back. Two consecutive `ActivityScenario.launch` calls in one process are
 * exactly that relaunch; `recreate()` is the configuration change, which must not re-ask.
 */
@RunWith(AndroidJUnit4::class)
class WarmRelaunchGateTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val server = LocalApiServer(hasPin = true)

    @After
    fun tearDown() = server.close()

    /**
     * The plain outcome. It cannot catch the regression on its own — by the time the gate is up
     * the shell has already been replaced, so the negative below passed against the broken build
     * too. [theHomeRailsAreNotFetchedWhileTheGateIsUp] is what pins the ordering.
     */
    @Test
    fun aRelaunchWithTheProcessAliveOpensTheGate() {
        seedPinProtectedProfile()
        signInThroughTheGate()

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.awaitScreen("Enter Jose's PIN")
            assertTrue(
                "The signed-in shell was on screen behind the gate",
                displayedNodesWithContentDescription("Signed in as Jose").isEmpty(),
            )
        }
    }

    /**
     * The sharp one: the rails are what the user actually saw load, and a shell that composes for
     * even one frame fires all four before the gate replaces it. Verified to fail against the
     * build without the fix.
     */
    @Test
    fun theHomeRailsAreNotFetchedWhileTheGateIsUp() {
        seedPinProtectedProfile()
        signInThroughTheGate()
        server.clearRequestLog()

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.awaitScreen("Enter Jose's PIN")
            assertEquals(
                emptyList<String>(),
                server.requestedPaths.filter { path -> RAILS.any(path::endsWith) },
            )
        }
    }

    /**
     * A font scale, ui mode or locale change recreates the Activity with a saved bundle. That is
     * not a resumed token, and ejecting whoever is watching onto the keypad would be wrong.
     */
    @Test
    fun aConfigurationChangeDoesNotReAskForThePin() {
        seedPinProtectedProfile()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            enterThePin()

            scenario.recreate()

            composeRule.awaitContentDescription("Signed in as Jose")
            composeRule.onNodeWithContentDescription("Signed in as Jose").assertIsDisplayed()
            assertTrue(
                "The gate re-opened over a running session",
                displayedNodesWithText("Enter Jose's PIN").isEmpty(),
            )
        }
    }

    /** Takes a launch all the way through the keypad, then lets that Activity go. */
    private fun signInThroughTheGate() {
        ActivityScenario.launch(MainActivity::class.java).use { enterThePin() }
    }

    private fun enterThePin() {
        server.pinValid = true
        composeRule.awaitScreen("Enter Jose's PIN")
        repeat(4) { composeRule.onNodeWithContentDescription("1").performClick() }
        composeRule.awaitContentDescription("Signed in as Jose")
    }

    /**
     * Bounds-filtered for the same reason [awaitScreen] filters: a closed `ActivityScenario`
     * leaves its nodes attached for a while, still `isPlaced` but with empty window bounds, and
     * an unfiltered query would find the shell from the previous launch and fail these assertions.
     */
    private fun displayedNodesWithContentDescription(description: String) =
        composeRule.onAllNodesWithContentDescription(description)
            .fetchSemanticsNodes()
            .filter { !it.boundsInWindow.isEmpty }

    private fun displayedNodesWithText(text: String) =
        composeRule.onAllNodesWithText(text)
            .fetchSemanticsNodes()
            .filter { !it.boundsInWindow.isEmpty }

    private fun seedPinProtectedProfile() {
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as IglooApplication
        runBlocking {
            app.container.serverSettingsStore.save(server.apiBaseUrl)
            app.container.profileRepository.clearAll()
            app.container.profileRepository.setPending("igd_jose")
            app.container.profileRepository.commitSignIn(
                AuthUser(
                    id = 1,
                    name = "Jose",
                    email = "jose@example.com",
                    isAdmin = false,
                    avatar = null,
                    hasPin = true,
                    createdAt = "2026-01-01T00:00:00Z",
                    updatedAt = "2026-01-01T00:00:00Z",
                ),
            )
            app.container.profileRepository.deactivate()
        }
        app.container.serverUrlProvider.set(null)
    }
}

/** The four home rails, whose loads are the visible symptom of a shell composing too early. */
private val RAILS = listOf(
    "/continue-watching",
    "/movies/latest",
    "/music/albums/latest",
    "/tmdb/movies/in-theaters",
)
