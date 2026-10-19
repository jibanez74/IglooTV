package com.igloo.blindpenguincoder.feature.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class PairingCharacterDescriptionTest {
    @Test
    fun similarLettersAndDigitsHaveDistinctSpokenDescriptions() {
        val code = "O0I1B8"
        val expected = listOf(
            "Letter O, as in Oscar.", "Digit zero.",
            "Letter I, as in India.", "Digit one.",
            "Letter B, as in Bravo.", "Digit eight.",
        )
        expected.forEachIndexed { index, value ->
            assertEquals(
                "Character ${index + 1} of 6. $value",
                pairingCharacterDescription(code, index),
            )
        }
    }
}
