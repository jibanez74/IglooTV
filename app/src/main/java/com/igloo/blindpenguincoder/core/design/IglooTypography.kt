package com.igloo.blindpenguincoder.core.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

@Immutable
data class IglooTypography(
    val displayCode: TextStyle,
    /**
     * Hero headline on a full-bleed canvas. Wraps like any other style — budget it at the height
     * it takes in the column it actually gets, not at one line. See docs/design-system.md
     * section 4.
     */
    val display: TextStyle,
    val titleLarge: TextStyle,
    val titleMedium: TextStyle,
    val bodyLarge: TextStyle,
    val bodyMedium: TextStyle,
    /** Short non-prose strings only — nav labels, chips, metadata. Never a sentence. */
    val label: TextStyle,
)

private fun TextStyle.at(scale: Float) = copy(
    fontSize = fontSize * scale,
    lineHeight = lineHeight * scale,
    letterSpacing = if (letterSpacing.isSpecified) letterSpacing * scale else letterSpacing,
)

/**
 * Builds the type set. Standard values are authored in docs/design-system.md section 4; unit
 * tests assert this function reproduces them exactly. 16sp (bodyMedium) is the floor for body
 * copy — adding a smaller style requires editing that document first.
 */
fun iglooTypography(scale: Float): IglooTypography = IglooTypography(
    displayCode = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 64.sp,
        lineHeight = 76.sp,
        letterSpacing = 10.sp,
    ).at(scale),
    display = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 44.sp,
        lineHeight = 52.sp,
    ).at(scale),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
    ).at(scale),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ).at(scale),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ).at(scale),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ).at(scale),
    label = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ).at(scale),
)
