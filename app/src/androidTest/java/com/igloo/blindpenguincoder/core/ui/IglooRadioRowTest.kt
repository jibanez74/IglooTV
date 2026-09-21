package com.igloo.blindpenguincoder.core.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.core.design.IglooTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The radio row's semantics contract: one merged RadioButton node per row that announces label
 * and selected state, and an inert variant that stays focusable — so a hand-wired up/down chain
 * keeps its link — while announcing disabled with no action.
 */
@RunWith(AndroidJUnit4::class)
class IglooRadioRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun anInteractiveRowIsOneRadioButtonNodeThatSelects() {
        var selections = 0
        composeRule.setContent {
            IglooTheme {
                IglooRadioRow(
                    label = "1080p — best quality",
                    selected = false,
                    onSelect = { selections += 1 },
                    modifier = Modifier.testTag("row"),
                )
            }
        }

        val row = composeRule.onNodeWithTag("row")
        row.assertContentDescriptionEquals("1080p — best quality")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertIsNotSelected()
            .assertHasClickAction()

        row.performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, selections)
    }

    @Test
    fun aSelectedRowAnnouncesItsState() {
        composeRule.setContent {
            IglooTheme {
                IglooRadioRow(
                    label = "English · Stereo",
                    selected = true,
                    onSelect = {},
                    modifier = Modifier.testTag("row"),
                )
            }
        }

        composeRule.onNodeWithTag("row").assertIsSelected()
    }

    @Test
    fun anInertRowStaysFocusableButAnnouncesDisabledWithNoAction() {
        composeRule.setContent {
            IglooTheme {
                IglooRadioRow(
                    label = "Spanish (image-based)",
                    selected = false,
                    onSelect = null,
                    modifier = Modifier.testTag("row"),
                )
            }
        }

        val row = composeRule.onNodeWithTag("row")
        row.assertIsNotEnabled().assertHasNoClickAction()
        row.requestFocus()
        row.assertIsFocused()
    }
}
