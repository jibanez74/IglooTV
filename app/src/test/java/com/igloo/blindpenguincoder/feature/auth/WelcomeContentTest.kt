package com.igloo.blindpenguincoder.feature.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The structure of the welcome copy — what a screen reader says and in what order.
 *
 * Whether it *fits* is not asserted here. `WelcomeScreenLayoutTest` measures the rendered layout
 * against the 960×540 viewport at every UiScale and font scale, which is the real constraint; a
 * character count is a poor proxy for width in a proportional font and only produced false
 * failures on copy edits.
 */
class WelcomeContentTest {

    @Test
    fun `there are three steps numbered in order`() {
        assertEquals(3, WelcomeSteps.size)
        assertEquals(listOf(1, 2, 3), WelcomeSteps.map { it.number })
    }

    @Test
    fun `each step announces itself as one sentence with its position`() {
        assertEquals(
            "Step 1 of 3. Connect to your server. Enter the address of your Igloo backend.",
            WelcomeSteps[0].accessibilityLabel,
        )
        assertEquals(
            "Step 2 of 3. Scan a code to sign in. Or use your email and password.",
            WelcomeSteps[1].accessibilityLabel,
        )
        assertEquals(
            "Step 3 of 3. Everyone gets a profile. " +
                "Add more people later, with their own history.",
            WelcomeSteps[2].accessibilityLabel,
        )
    }

    @Test
    fun `step bodies end in terminal punctuation so TalkBack pauses between them`() {
        WelcomeSteps.forEach { step ->
            assertTrue("step ${step.number} body: ${step.body}", step.body.endsWith("."))
            // The label joins title and body with ". ", so a trailing stop would double up.
            assertFalse("step ${step.number} title: ${step.title}", step.title.endsWith("."))
        }
    }
}
