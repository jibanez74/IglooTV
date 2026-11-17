package com.igloo.blindpenguincoder.core.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.clickActionLabelled
import com.igloo.blindpenguincoder.core.design.IglooTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The button's three semantics contracts (design-system section 9.1): an actionable button is
 * one Button node whose action label is what it says; a button drawn before its action exists
 * stays a focus target that announces neither role nor action (the inert poster-card contract);
 * a disabled one announces "disabled" and leaves the focus tree.
 */
@RunWith(AndroidJUnit4::class)
class IglooButtonSemanticsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun anActionableButtonIsOneButtonNodeWithItsAction() {
        var presses = 0
        composeRule.setContent {
            IglooTheme {
                IglooButton(
                    text = "Play",
                    onClick = { presses += 1 },
                    semanticLabel = "Play Heat",
                    modifier = Modifier.testTag("button"),
                )
            }
        }

        val button = composeRule.onNodeWithTag("button")
        button.assertContentDescriptionEquals("Play Heat")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(clickActionLabelled("Play Heat"))
            .assertIsEnabled()
        button.performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, presses)
    }

    @Test
    fun aButtonWithNoActionStaysFocusableAndAnnouncesNoRoleOrAction() {
        composeRule.setContent {
            IglooTheme {
                IglooButton(
                    text = "Play S1 E1",
                    onClick = null,
                    semanticLabel = "Play season 1, episode 1, Pilot",
                    modifier = Modifier.testTag("button"),
                )
            }
        }

        val button = composeRule.onNodeWithTag("button")
        button.assertContentDescriptionEquals("Play season 1, episode 1, Pilot")
            .assertHasNoClickAction()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Role))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Disabled))
        button.requestFocus()
        button.assertIsFocused()
    }

    @Test
    fun aDisabledButtonLeavesTheFocusTree() {
        composeRule.setContent {
            IglooTheme {
                IglooButton(
                    text = "Play",
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.testTag("button"),
                )
            }
        }

        composeRule.onNodeWithTag("button")
            .assertIsNotEnabled()
            .assertHasNoClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.RequestFocus))
    }

    // A watch room's Watch is inert until the room's state arrives. When that came after the page,
    // focus sat on the inert button, and the swap to a live one dropped it: focus on nothing.
    @Test
    fun aFocusedButtonKeepsFocusWhenItsActionArrivesOrGoes() {
        var live by mutableStateOf(false)
        composeRule.setContent {
            IglooTheme {
                Row {
                    IglooButton(
                        text = "Watch",
                        onClick = if (live) ({}) else null,
                        modifier = Modifier.testTag("button"),
                    )
                    IglooIconButton(
                        icon = IglooIcons.Play,
                        semanticLabel = "Play",
                        onClick = if (live) ({}) else null,
                        modifier = Modifier.testTag("icon"),
                    )
                }
            }
        }

        for (tag in listOf("button", "icon")) {
            val node = composeRule.onNodeWithTag(tag)
            node.requestFocus()
            node.assertIsFocused()
            live = !live
            composeRule.waitForIdle()
            node.assertIsFocused().assertHasClickActionIf(live)
            live = !live
            composeRule.waitForIdle()
            node.assertIsFocused()
        }
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertHasClickActionIf(live: Boolean) =
        if (live) assertHasClickAction() else assertHasNoClickAction()
}

