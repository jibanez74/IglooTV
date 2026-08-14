package com.igloo.blindpenguincoder

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import com.igloo.blindpenguincoder.core.config.ServerAddress
import com.igloo.blindpenguincoder.data.repository.ProfileRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * With a stored server but no device token, restore() lands on NeedsLogin without any network
 * call, so the default sign-in screen — Quick Connect — shows deterministically.
 *
 * The pairing phase, though, is the server's answer to `initiate`, so these run against
 * [LocalApiServer] with the outcome pinned per test rather than racing a real network failure.
 * A failed initiate is not cosmetic: its banner overflows the card and scrolls the title out of
 * the viewport (design system section 11.1.3), which is asserted below as behavior instead of
 * being left to sabotage the title waits.
 */
@RunWith(AndroidJUnit4::class)
class QuickConnectGateTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val server = LocalApiServer(hasPin = false)

    @After
    fun tearDown() = server.close()

    @Test
    fun signInDefaultsToQuickConnectWithFocusOnTheSwitchButton() {
        seedServerWithoutToken()
        val origin = server.apiBaseUrl.removeSuffix("/api")

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.awaitScreen("Sign in to Igloo")
            composeRule.onNodeWithText("Sign in to Igloo").assertIsDisplayed()
            composeRule.onNodeWithText(origin).assertIsDisplayed()
            composeRule.onNodeWithText(
                "Scan the QR code to open Account settings. Sign in through your " +
                    "browser if asked, then enter the six-character TV code.",
            ).assertIsDisplayed()
            composeRule
                .onNodeWithText("$origin/settings/account")
                .assertIsDisplayed()
            composeRule
                .onNodeWithContentDescription("Requesting pairing code")
                .assertIsDisplayed()

            val passwordMode = composeRule
                .onNodeWithContentDescription("Use email and password instead")
            val changeServer = composeRule
                .onNodeWithContentDescription("Change server address")

            passwordMode
                .assertIsDisplayed()
                .assertIsFocused()
            changeServer.assertIsDisplayed()

            passwordMode.performKeyInput { pressKey(Key.DirectionRight) }
            changeServer.assertIsFocused()
            changeServer.performKeyInput { pressKey(Key.DirectionLeft) }
            passwordMode.assertIsFocused()
        }
    }

    @Test
    fun switchingToPasswordModeFocusesTheEmailField() {
        seedServerWithoutToken()

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.awaitScreen("Sign in to Igloo")
            composeRule
                .onNodeWithContentDescription("Use email and password instead")
                .performClick()

            val email = composeRule.onNodeWithContentDescription("Email")
            val password = composeRule.onNodeWithContentDescription("Password")
            email.assertIsFocused().performTextInput("jose@example.com")
            email.performKeyInput { pressKey(Key.DirectionDown) }
            password.assertIsFocused().performTextInput("not-a-real-password")

            composeRule
                .onNodeWithContentDescription("Use pairing code instead")
                .performClick()

            composeRule
                .onNodeWithContentDescription("Use email and password instead")
                .assertIsFocused()
                .performClick()

            email.assertTextEquals("jose@example.com")
            password.assertTextEquals("")
        }
    }

    @Test
    fun passwordFormScrollsEveryFocusedControlFullyOnscreenAtTvViewport() {
        seedServerWithUnreachableToken()

        ActivityScenario.launch(MainActivity::class.java).use {
            // Not the title: the restore-error banner may already have scrolled it out of the
            // viewport, which is this screen's designed degraded state. The switch button is
            // focused, so the scroll keeps it onscreen.
            composeRule.awaitContentDescription("Use email and password instead")
            composeRule
                .onNodeWithContentDescription("Use email and password instead")
                .performClick()

            val recovery = composeRule.onNodeWithContentDescription("Retry connecting to server")
            val email = composeRule.onNodeWithContentDescription("Email")
            val password = composeRule.onNodeWithContentDescription("Password")
            val signIn = composeRule.onNodeWithContentDescription("Sign in")
            val pairing = composeRule.onNodeWithContentDescription("Use pairing code instead")
            val changeServer = composeRule.onNodeWithContentDescription("Change server address")

            email.assertIsFocused().assertFullyOnscreen()
            email.performKeyInput { pressKey(Key.DirectionUp) }
            recovery.assertIsFocused().assertFullyOnscreen()

            recovery.performKeyInput { pressKey(Key.DirectionDown) }
            email.assertIsFocused().assertFullyOnscreen()
            email.performKeyInput { pressKey(Key.DirectionDown) }
            password.assertIsFocused().assertFullyOnscreen()
            password.performKeyInput { pressKey(Key.DirectionDown) }
            signIn.assertIsFocused().assertFullyOnscreen()
            signIn.performKeyInput { pressKey(Key.DirectionDown) }
            pairing.assertIsFocused().assertFullyOnscreen()
            pairing.performKeyInput { pressKey(Key.DirectionDown) }
            changeServer.assertIsFocused().assertFullyOnscreen()
        }
    }

    /**
     * The failure banner is the state that used to break this class's waits: it overflows the
     * card and the title leaves the viewport, by design. What must hold instead: the retry
     * action is reachable, focus stays where the user left it, and retrying actually starts a
     * fresh request.
     */
    @Test
    fun failedInitiateOffersARetryThatRequestsANewCode() {
        server.quickConnectInitiate = QuickConnectInitiate.Fail
        seedServerWithoutToken()

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.awaitContentDescription("Request a new pairing code")
            composeRule
                .onNodeWithContentDescription("Use email and password instead")
                .assertIsFocused()

            server.quickConnectInitiate = QuickConnectInitiate.Hang
            composeRule
                .onNodeWithContentDescription("Request a new pairing code")
                .performClick()

            composeRule.awaitContentDescription("Requesting pairing code")
            composeRule
                .onAllNodesWithContentDescription("Request a new pairing code")
                .fetchSemanticsNodes()
                .let { assertTrue("Failure banner should be gone after retry", it.isEmpty()) }
        }
    }

    /**
     * An issued code renders as one of two different trees, and which one is the device's
     * business, not the test's: with a screen reader listening the characters become
     * individually focusable and the first deliberately takes focus, and without one the code
     * is a single node that must not disturb the focused button. Asserting only the latter is
     * what made this fail on the Shield, which runs with TalkBack on — the exact device
     * AGENTS.md asks us to validate against. PairingCodeAccessibilityTest pins the spoken
     * variant's character-by-character behaviour; here it is only the arrival that matters.
     */
    @Test
    fun issuedPairingCodeIsPresentedForTheDevicesAccessibilityState() {
        server.quickConnectInitiate = QuickConnectInitiate.Code("WXYZ42")
        seedServerWithoutToken()

        ActivityScenario.launch(MainActivity::class.java).use {
            if (spokenFeedbackEnabled()) {
                composeRule.awaitTestTag("pairing_code_character_0")
                composeRule.onNodeWithTag("pairing_code_character_0").assertIsFocused()
                composeRule.onAllNodesWithTag("pairing_code_character_5")
                    .fetchSemanticsNodes()
                    .let { assertTrue("Every character should be its own node", it.isNotEmpty()) }
            } else {
                composeRule.awaitContentDescription("Pairing code: W X Y Z 4 2")
                composeRule.onNodeWithText("WXYZ42").assertIsDisplayed()
                composeRule
                    .onNodeWithContentDescription("Use email and password instead")
                    .assertIsFocused()
            }
        }
    }

    /** The whole pairing loop end-to-end: initiate, redeem approval, token exchange, home. */
    @Test
    fun approvedPairingSignsInToTheHomeShell() {
        server.quickConnectInitiate = QuickConnectInitiate.Code("ABC123")
        server.quickConnectRedeemApproved = true
        seedServerWithoutToken()

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.awaitContentDescription("Signed in as Jose")
            composeRule.onNodeWithContentDescription("Signed in as Jose").assertIsDisplayed()
        }
    }

    private fun seedServerWithoutToken() = seed(server.apiBaseUrl)

    private fun seedServerWithUnreachableToken() = seed("http://127.0.0.1:1/api") { profiles ->
        // Port 1 refuses instantly and deterministically, which is exactly the restore error
        // this test's recovery banner needs. Pending, not committed: pairing resumes against
        // a server that never answers.
        profiles.setPending("igd_unreachable")
    }

    /**
     * The provider is set to the seeded address, not null: the session singleton still holds the
     * previous test's state, so a relaunch can compose the sign-in screen — and start its pairing
     * loop — before restore() has re-read the vault. With a null provider that early initiate
     * fails on the spot and the loop dies in `Failed`, wedging the whole test behind a state no
     * seeded server ever produced.
     */
    private fun seed(
        apiBaseUrl: String,
        also: suspend (ProfileRepository) -> Unit = {},
    ) {
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as IglooApplication
        runBlocking {
            app.container.serverSettingsStore.save(apiBaseUrl)
            app.container.profileRepository.clearAll()
            also(app.container.profileRepository)
        }
        app.container.serverUrlProvider.set(ServerAddress.fromApiBaseUrl(apiBaseUrl))
    }

    private fun SemanticsNodeInteraction.assertFullyOnscreen(): SemanticsNodeInteraction {
        assertIsDisplayed()
        val root = composeRule.onRoot().getUnclippedBoundsInRoot()
        val bounds = getUnclippedBoundsInRoot()
        assertTrue("Focused control starts above the viewport: $bounds", bounds.top >= root.top)
        assertTrue("Focused control ends below the viewport: $bounds", bounds.bottom <= root.bottom)
        return this
    }
}
