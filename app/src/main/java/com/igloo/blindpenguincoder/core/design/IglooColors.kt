package com.igloo.blindpenguincoder.core.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

@Immutable
data class IglooColors(
    val background: Color,
    val foreground: Color,
    val card: Color,
    val cardForeground: Color,
    val primary: Color,
    val primaryForeground: Color,
    val muted: Color,
    val mutedForeground: Color,
    val border: Color,
    val ring: Color,
    val aurora: Color,
    val auroraForeground: Color,
    val sidebar: Color,
    val sidebarPrimary: Color,
    val destructive: Color,
    val destructiveForeground: Color,
)

val IglooDarkColors = IglooColors(
    background = Color(0xFF0A1322),
    foreground = Color(0xFFF8FAFC),
    card = Color(0xFF15233A),
    cardForeground = Color(0xFFF8FAFC),
    primary = Color(0xFF38BDF8),
    primaryForeground = Color(0xFF08131F),
    muted = Color(0xFF0F1A2E),
    mutedForeground = Color(0xFF8094AE),
    border = Color(0xFF2A3C57),
    ring = Color(0xFF38BDF8),
    aurora = Color(0xFFF59E0B),
    auroraForeground = Color(0xFF08131F),
    sidebar = Color(0xFF0F1A2E),
    sidebarPrimary = Color(0xFF38BDF8),
    destructive = Color(0xFFF87171),
    destructiveForeground = Color(0xFF08131F),
)

val IglooLightColors = IglooColors(
    background = Color(0xFFF2F7FC),
    foreground = Color(0xFF0A1322),
    card = Color(0xFFFFFFFF),
    cardForeground = Color(0xFF0A1322),
    primary = Color(0xFF0369A1),
    primaryForeground = Color(0xFFFFFFFF),
    muted = Color(0xFFE3EDF7),
    mutedForeground = Color(0xFF475569),
    border = Color(0xFFCBD9E8),
    ring = Color(0xFF0EA5E9),
    aurora = Color(0xFFF59E0B),
    auroraForeground = Color(0xFF08131F),
    sidebar = Color(0xFFE8F1FA),
    sidebarPrimary = Color(0xFF0369A1),
    destructive = Color(0xFFDC2626),
    destructiveForeground = Color(0xFFFFFFFF),
)

/**
 * How much canvas is mixed into a `Primary` fill that has stepped back for a focused sibling.
 * Not an alpha: `primary @ 0.40` is section 3.1's *disabled* control and composites to #1C5778,
 * so recession has to stay well clear of it. 0.30 lands on #2A8AB8 in dark — 1.81:1 against the
 * full fill, enough to hand the eye to the focused control, and nowhere near disabled.
 */
private const val RECESSED_PRIMARY_MIX = 0.30f

/**
 * A `Primary` fill recessed because a sibling control holds focus.
 *
 * `ring` and `primary` are the same value in dark, so a resting Play button carries several times
 * more glacier than the 3dp ring on whatever is actually focused, and the eye lands on the wrong
 * control. Mixing the canvas in drops that without touching the focus treatment itself.
 *
 * Opaque, and mixed component-wise in sRGB rather than through `androidx.compose.ui.graphics.lerp`
 * — that interpolates in Oklab, while the contrast figures this was chosen against are sRGB. An
 * alpha would also let a backdrop show through the fill.
 */
fun IglooColors.recessedPrimary(): Color = Color(
    red = primary.red + (background.red - primary.red) * RECESSED_PRIMARY_MIX,
    green = primary.green + (background.green - primary.green) * RECESSED_PRIMARY_MIX,
    blue = primary.blue + (background.blue - primary.blue) * RECESSED_PRIMARY_MIX,
)

/**
 * The label on [recessedPrimary]. Recession moves the fill toward the canvas, which in light
 * lightens it under a white `primaryForeground` and drops that pair to 3.35:1. Picking by measured
 * contrast keeps both themes past section 12's 4.5:1 bar for a control label: `primaryForeground`
 * still wins in dark at 4.83:1, `foreground` takes over in light at 5.55:1.
 */
fun IglooColors.recessedPrimaryContent(): Color {
    val fill = recessedPrimary()
    return if (contrastRatio(primaryForeground, fill) >= contrastRatio(foreground, fill)) {
        primaryForeground
    } else {
        foreground
    }
}

private fun contrastRatio(a: Color, b: Color): Float {
    val first = a.luminance()
    val second = b.luminance()
    val lighter = maxOf(first, second)
    val darker = minOf(first, second)
    return (lighter + 0.05f) / (darker + 0.05f)
}

