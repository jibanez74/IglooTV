package com.igloo.blindpenguincoder.feature.auth

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.core.design.IglooTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PairingCodeAccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun initialCodeFocusesFirstCharacterWithReadyAnnouncement() {
        showCode(code = "ABC123", spokenAccessibilityEnabled = true)

        composeRule.onNodeWithTag(characterTag(0))
            .assertIsFocused()
            .assertContentDescriptionEquals(
                "Pairing code ready. Focus moved to the first character. " +
                    "Use Left and Right to review each character. A.",
            )
    }

    @Test
    fun rightAndLeftMoveOneCharacterAndRestoreConciseSpeech() {
        showCode(code = "ABC123", spokenAccessibilityEnabled = true)

        composeRule.onNodeWithTag(characterTag(0)).performKeyInput {
            pressKey(Key.DirectionRight)
        }
        composeRule.onNodeWithTag(characterTag(1))
            .assertIsFocused()
            .assertContentDescriptionEquals("B")

        composeRule.onNodeWithTag(characterTag(1)).performKeyInput {
            pressKey(Key.DirectionLeft)
        }
        composeRule.onNodeWithTag(characterTag(0))
            .assertIsFocused()
            .assertContentDescriptionEquals("A")
    }

    @Test
    fun replacementCodeRefocusesFromElsewhereWithChangedAnnouncement() {
        lateinit var code: MutableState<String>
        lateinit var elsewhereFocus: FocusRequester

        composeRule.setContent {
            code = remember { mutableStateOf("ABC123") }
            elsewhereFocus = remember { FocusRequester() }
            IglooTheme {
                Column {
                    PairingCode(
                        phase = QuickConnectPhase.CodeReady(code.value),
                        spokenAccessibilityEnabled = true,
                    )
                    BasicText(
                        text = "Elsewhere",
                        modifier = Modifier
                            .focusRequester(elsewhereFocus)
                            .focusable()
                            .semantics { contentDescription = "Elsewhere" },
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle { elsewhereFocus.requestFocus() }
        composeRule.onNodeWithContentDescription("Elsewhere").assertIsFocused()

        composeRule.runOnIdle { code.value = "XYZ789" }
        composeRule.onNodeWithTag(characterTag(0))
            .assertIsFocused()
            .assertContentDescriptionEquals(
                "Pairing code changed. Focus moved to the first character. X.",
            )
    }

    @Test
    fun replacementAfterLoadingStateStillUsesChangedAnnouncement() {
        lateinit var phase: MutableState<QuickConnectPhase>

        composeRule.setContent {
            phase = remember { mutableStateOf(QuickConnectPhase.CodeReady("ABC123")) }
            IglooTheme {
                PairingCode(
                    phase = phase.value,
                    spokenAccessibilityEnabled = true,
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle { phase.value = QuickConnectPhase.RequestingCode }
        composeRule.runOnIdle { phase.value = QuickConnectPhase.CodeReady("XYZ789") }

        composeRule.onNodeWithTag(characterTag(0))
            .assertIsFocused()
            .assertContentDescriptionEquals(
                "Pairing code changed. Focus moved to the first character. X.",
            )
    }

    @Test
    fun repeatedCharactersRemainSeparateAndTraversalOrdered() {
        showCode(code = "AABBAA", spokenAccessibilityEnabled = true)

        composeRule.onNodeWithTag("pairing_code_group").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.IsTraversalGroup, true),
        )
        composeRule.onNodeWithTag(characterTag(0)).performKeyInput {
            pressKey(Key.DirectionRight)
        }

        composeRule.onAllNodesWithContentDescription("A").assertCountEquals(4)
        repeat(6) { index ->
            composeRule.onNodeWithTag(characterTag(index)).assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.TraversalIndex,
                    index.toFloat(),
                ),
            )
        }
    }

    @Test
    fun leftAndRightBoundariesAllowFocusToLeaveTheCode() {
        lateinit var code: MutableState<String>

        composeRule.setContent {
            code = remember { mutableStateOf("ABC123") }
            val beforeFocus = remember { FocusRequester() }
            val afterFocus = remember { FocusRequester() }
            IglooTheme {
                Row {
                    FocusableTestLabel(
                        text = "Before code",
                        tag = "before_code",
                        focusRequester = beforeFocus,
                    )
                    Box(modifier = Modifier.width(600.dp)) {
                        PairingCode(
                            phase = QuickConnectPhase.CodeReady(code.value),
                            spokenAccessibilityEnabled = true,
                            leftBoundaryFocus = beforeFocus,
                            rightBoundaryFocus = afterFocus,
                        )
                    }
                    FocusableTestLabel(
                        text = "After code",
                        tag = "after_code",
                        focusRequester = afterFocus,
                    )
                }
            }
        }

        composeRule.onNodeWithTag(characterTag(0)).performKeyInput {
            pressKey(Key.DirectionLeft)
        }
        composeRule.onNodeWithTag("before_code").assertIsFocused()

        composeRule.runOnIdle { code.value = "XYZ789" }
        composeRule.onNodeWithTag(characterTag(0)).assertIsFocused()
        repeat(5) { index ->
            composeRule.onNodeWithTag(characterTag(index))
                .assertIsFocused()
                .performKeyInput {
                    pressKey(Key.DirectionRight)
                }
            composeRule.onNodeWithTag(characterTag(index + 1)).assertIsFocused()
        }
        composeRule.onNodeWithTag(characterTag(5)).performKeyInput {
            pressKey(Key.DirectionRight)
        }
        composeRule.onNodeWithTag("after_code").assertIsFocused()
    }

    @Test
    fun charactersAreNotDpadTargetsWithoutSpokenAccessibility() {
        composeRule.setContent {
            val actionFocus = remember { FocusRequester() }
            IglooTheme {
                Column {
                    PairingCode(
                        phase = QuickConnectPhase.CodeReady("ABC123"),
                        spokenAccessibilityEnabled = false,
                    )
                    BasicText(
                        text = "Action",
                        modifier = Modifier
                            .focusRequester(actionFocus)
                            .focusable()
                            .testTag("ordinary_action")
                            .semantics { contentDescription = "Action" },
                    )
                }
            }
            LaunchedEffect(Unit) { actionFocus.requestFocus() }
        }

        composeRule.onAllNodesWithTag(characterTag(0)).assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Pairing code: A B C 1 2 3")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Focused))

        val action = composeRule.onNodeWithTag("ordinary_action").assertIsFocused()
        action.performKeyInput { pressKey(Key.DirectionUp) }
        action.assertIsFocused()
    }

    private fun showCode(
        code: String,
        spokenAccessibilityEnabled: Boolean,
    ) {
        composeRule.setContent {
            IglooTheme {
                PairingCode(
                    phase = QuickConnectPhase.CodeReady(code),
                    spokenAccessibilityEnabled = spokenAccessibilityEnabled,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun characterTag(index: Int): String = "pairing_code_character_$index"

    @Composable
    private fun FocusableTestLabel(
        text: String,
        tag: String,
        focusRequester: FocusRequester,
    ) {
        BasicText(
            text = text,
            modifier = Modifier
                .focusRequester(focusRequester)
                .focusable()
                .testTag(tag)
                .semantics { contentDescription = text },
        )
    }
}
