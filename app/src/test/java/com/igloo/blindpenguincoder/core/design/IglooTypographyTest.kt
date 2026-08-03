package com.igloo.blindpenguincoder.core.design

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the Standard type table in docs/design-system.md section 4. */
class IglooTypographyTest {

    private val compact = iglooTypography(UiScale.Compact.factor)
    private val standard = iglooTypography(UiScale.Standard.factor)
    private val large = iglooTypography(UiScale.Large.factor)

    private fun styles(t: IglooTypography) = listOf(
        t.displayCode, t.titleLarge, t.titleMedium, t.bodyLarge, t.bodyMedium, t.label,
    )

    @Test
    fun `standard sizes match the documented scale`() {
        assertEquals(64.sp, standard.displayCode.fontSize)
        assertEquals(76.sp, standard.displayCode.lineHeight)
        assertEquals(10.sp, standard.displayCode.letterSpacing)
        assertEquals(34.sp, standard.titleLarge.fontSize)
        assertEquals(40.sp, standard.titleLarge.lineHeight)
        assertEquals(24.sp, standard.titleMedium.fontSize)
        assertEquals(30.sp, standard.titleMedium.lineHeight)
        assertEquals(18.sp, standard.bodyLarge.fontSize)
        assertEquals(24.sp, standard.bodyLarge.lineHeight)
        assertEquals(16.sp, standard.bodyMedium.fontSize)
        assertEquals(22.sp, standard.bodyMedium.lineHeight)
        assertEquals(15.sp, standard.label.fontSize)
        assertEquals(20.sp, standard.label.lineHeight)
    }

    @Test
    fun `body copy never drops below the sixteen sp floor`() {
        assertEquals(16.sp, standard.bodyMedium.fontSize)
    }

    @Test
    fun `line height always leaves room for the glyphs`() {
        listOf(compact, standard, large).forEach { set ->
            styles(set).forEach { style ->
                assertTrue(
                    "lineHeight ${style.lineHeight} < fontSize ${style.fontSize}",
                    style.lineHeight.value >= style.fontSize.value,
                )
            }
        }
    }

    @Test
    fun `sizes grow monotonically with ui scale`() {
        styles(compact).indices.forEach { i ->
            val c = styles(compact)[i].fontSize.value
            val s = styles(standard)[i].fontSize.value
            val l = styles(large)[i].fontSize.value
            assertTrue("expected $c < $s < $l", c < s && s < l)
        }
    }

    @Test
    fun `letter spacing scales only when it is specified`() {
        assertEquals(10.sp * UiScale.Large.factor, large.displayCode.letterSpacing)
        // The unspecified default must survive scaling rather than becoming a real value.
        assertEquals(TextStyle.Default.letterSpacing, large.bodyMedium.letterSpacing)
    }

    @Test
    fun `weights and families are preserved across scales`() {
        listOf(compact, standard, large).forEach { set ->
            assertEquals(standard.displayCode.fontFamily, set.displayCode.fontFamily)
            assertEquals(standard.titleLarge.fontWeight, set.titleLarge.fontWeight)
            assertEquals(standard.bodyMedium.fontWeight, set.bodyMedium.fontWeight)
        }
    }
}
