package com.igloo.blindpenguincoder.core.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics

/** Empties this subtree for TalkBack while [cleared]: what a menu or dialog covers must go quiet. */
internal fun Modifier.clearedSemanticsIf(cleared: Boolean): Modifier =
    if (cleared) clearAndSetSemantics { } else this

/** Takes this node out of the accessibility tree while [hidden]; its descendants stay reachable. */
internal fun Modifier.hiddenFromAccessibilityIf(hidden: Boolean): Modifier =
    if (hidden) semantics { hideFromAccessibility() } else this
