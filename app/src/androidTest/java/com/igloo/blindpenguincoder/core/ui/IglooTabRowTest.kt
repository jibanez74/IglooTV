package com.igloo.blindpenguincoder.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.core.design.IglooTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The tab strip's own contract, independent of any screen that uses it (docs/design-system.md
 * section 9.1): selection follows focus, a press is its own signal, and one cleared node per tab
 * carries [Role.Tab] with `selected` set only when it is true.
 */
@RunWith(AndroidJUnit4::class)
class IglooTabRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val selected = mutableListOf<String>()
    private val pressed = mutableListOf<String>()

    @Composable
    private fun Strip(current: String) {
        IglooTheme {
            IglooTabRow(modifier = Modifier.testTag("strip")) {
                listOf("All", "Liked").forEach { name ->
                    IglooTab(
                        text = name,
                        selected = name == current,
                        onSelect = { selected += name },
                        onPress = { pressed += name },
                        semanticLabel = "$name movies",
                        actionLabel = "Show $name movies",
                        modifier = Modifier.testTag(name),
                    )
                }
            }
        }
    }

    @Test
    fun aTabIsOneClearedNodeWithATabRole() {
        composeRule.setContent { Strip(current = "All") }

        composeRule.onNodeWithTag("All")
            .assertContentDescriptionEquals("All movies")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            .assertHasClickAction()
    }

    /** Never `selected = false`: TalkBack would append "not selected" to every other tab. */
    @Test
    fun onlyTheSelectedTabCarriesTheSelectedState() {
        composeRule.setContent { Strip(current = "All") }

        composeRule.onNodeWithTag("All")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        composeRule.onNodeWithTag("Liked")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
    }

    /** The d-pad move is the switch — the strip is traversed, not confirmed. */
    @Test
    fun focusLandingOnAnUnselectedTabSelectsIt() {
        composeRule.setContent { Strip(current = "All") }
        composeRule.onNodeWithTag("All").requestFocus()

        composeRule.onNodeWithTag("All").performKeyInput { pressKey(Key.DirectionRight) }

        composeRule.onNodeWithTag("Liked").assertIsFocused()
        assertEquals(listOf("Liked"), selected)
    }

    /** The selected tab taking focus is not a switch; re-reporting it would be a spurious fetch. */
    @Test
    fun focusLandingOnTheSelectedTabReportsNothing() {
        composeRule.setContent { Strip(current = "All") }

        composeRule.onNodeWithTag("All").requestFocus()

        composeRule.onNodeWithTag("All").assertIsFocused()
        assertEquals(emptyList<String>(), selected)
    }

    /** TalkBack's click action, and the retry after a failed switch reverted the selection. */
    @Test
    fun theClickActionReportsAPressRatherThanASelection() {
        composeRule.setContent { Strip(current = "All") }

        composeRule.onNodeWithTag("Liked").performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(listOf("Liked"), pressed)
    }

    @Test
    fun theClickActionCarriesTheCallersLabel() {
        composeRule.setContent { Strip(current = "All") }

        composeRule.onNodeWithTag("Liked").assert(
            SemanticsMatcher("has the caller's click label") { node ->
                node.config.getOrNull(SemanticsActions.OnClick)?.label == "Show Liked movies"
            },
        )
    }
}
