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
                    "Use Right or Down to move forward, Left or Up to move back. " +
                    "Character 1 of 6. Letter A, as in Alpha.",
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
            .assertContentDescriptionEquals("Character 2 of 6. Letter B, as in Bravo.")

        composeRule.onNodeWithTag(characterTag(1)).performKeyInput {
            pressKey(Key.DirectionLeft)
        }
        composeRule.onNodeWithTag(characterTag(0))
            .assertIsFocused()
            .assertContentDescriptionEquals("Character 1 of 6. Letter A, as in Alpha.")
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

        awaitFirstCharacterFocus()
        composeRule.runOnIdle { elsewhereFocus.requestFocus() }
        composeRule.onNodeWithContentDescription("Elsewhere").assertIsFocused()

        composeRule.runOnIdle { code.value = "XYZ789" }
        awaitFirstCharacterFocus()
        composeRule.onNodeWithTag(characterTag(0))
            .assertIsFocused()
            .assertContentDescriptionEquals(
                "Pairing code changed. Focus moved to the first character. Character 1 of 6. Letter X, as in X-ray.",
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

        awaitFirstCharacterFocus()
        composeRule.runOnIdle { phase.value = QuickConnectPhase.RequestingCode }
        composeRule.runOnIdle { phase.value = QuickConnectPhase.CodeReady("XYZ789") }
        awaitFirstCharacterFocus()

        composeRule.onNodeWithTag(characterTag(0))
            .assertIsFocused()
            .assertContentDescriptionEquals(
                "Pairing code changed. Focus moved to the first character. Character 1 of 6. Letter X, as in X-ray.",
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

        listOf(0, 1, 4, 5).forEach { index ->
            composeRule.onNodeWithTag(characterTag(index)).assertContentDescriptionEquals(
                "Character ${index + 1} of 6. Letter A, as in Alpha.",
            )
        }
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

        awaitFirstCharacterFocus()
        composeRule.onNodeWithTag(characterTag(0)).performKeyInput {
            pressKey(Key.DirectionLeft)
        }
        composeRule.onNodeWithTag("before_code").assertIsFocused()

        composeRule.runOnIdle { code.value = "XYZ789" }
        awaitFirstCharacterFocus()
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

    @Test
    fun signInControlsFollowCodeInBothDirectionsWithoutSpatialJumps() {
        composeRule.setContent {
            IglooTheme {
                QuickConnectContent(
                    phase = QuickConnectPhase.CodeReady("O0I1B8"),
                    serverOrigin = "https://example.test",
                    restoreError = null,
                    canCancel = false,
                    onRetryRestore = {},
                    onRetryPairing = {},
                    onSwitchToPassword = {},
                    onLeave = {},
                    spokenAccessibilityEnabled = true,
                )
            }
        }
        awaitFirstCharacterFocus()
        val stops = (0..5).map { composeRule.onNodeWithTag(characterTag(it)) } + listOf(
            composeRule.onNodeWithContentDescription("Use email and password instead"),
            composeRule.onNodeWithContentDescription("Change server address"),
        )
        // Both arrow pairs traverse the same sequence, including the code/button boundary.
        for ((forward, backward) in listOf(
            Key.DirectionRight to Key.DirectionLeft,
            Key.DirectionDown to Key.DirectionUp,
        )) {
            stops.first().performKeyInput { pressKey(backward) }
            stops.first().assertIsFocused()
            stops.zipWithNext().forEach { (from, to) ->
                from.assertIsFocused().performKeyInput { pressKey(forward) }
                to.assertIsFocused()
            }
            stops.last().performKeyInput { pressKey(forward) }
            stops.last().assertIsFocused()
            stops.asReversed().zipWithNext().forEach { (from, to) ->
                from.assertIsFocused().performKeyInput { pressKey(backward) }
                to.assertIsFocused()
            }
        }
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
        if (spokenAccessibilityEnabled) awaitFirstCharacterFocus()
    }

    private fun awaitFirstCharacterFocus() {
        // The arrival deliberately waits for the platform accessibility snapshot. An idle
        // composition alone does not mean the delayed focus handoff has completed.
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(characterTag(0)).fetchSemanticsNodes().any {
                it.config[SemanticsProperties.Focused]
            }
        }
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
