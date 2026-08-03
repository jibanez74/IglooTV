package com.igloo.blindpenguincoder.core.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Immutable
data class IglooSpacing(
    val xs: Dp,
    val sm: Dp,
    val md: Dp,
    val lg: Dp,
    val xl: Dp,
    val xxl: Dp,
)

@Immutable
data class IglooRadius(
    val sm: Dp,
    val md: Dp,
    val lg: Dp,
    val xl: Dp,
    /** Fully-rounded sentinel, not a dimension — never scaled. */
    val pill: Dp,
)

/** Component dimensions. Every height here is a minimum, applied with `heightIn(min = ...)`. */
@Immutable
data class IglooSizes(
    val controlHeight: Dp,
    val fieldHeight: Dp,
    val navItemHeight: Dp,
    val brandTile: Dp,
    val dot: Dp,
)

@Immutable
data class IglooIcons(
    val md: Dp,
    val lg: Dp,
)

/** The one focus treatment. Widths stay unscaled so hairlines remain hairlines. */
@Immutable
data class IglooFocus(
    val ringWidth: Dp,
    val restWidth: Dp,
    val scale: Float,
    val glowAlpha: Float,
    val glowElevation: Dp,
)

@Immutable
data class IglooLayout(
    /** Overscan inset — a fixed fraction of the panel, so it ignores the user's [UiScale]. */
    val safeAreaHorizontal: Dp,
    val safeAreaVertical: Dp,
    val navSpineWidth: Dp,
    val posterWidth: Dp,
    val wideCardWidth: Dp,
    val posterAspect: Float,
    val wideAspect: Float,
    /** Unscaled and inverse: a larger UI shows fewer columns. */
    val gridColumns: Int,
)

@Immutable
data class IglooDimens(
    val scale: Float,
    val spacing: IglooSpacing,
    val radius: IglooRadius,
    val sizes: IglooSizes,
    val icons: IglooIcons,
    val focus: IglooFocus,
    val layout: IglooLayout,
)

private fun Dp.at(scale: Float): Dp = (value * scale).roundToInt().dp

/**
 * Builds the dimension set. Standard values are authored in docs/design-system.md sections 5,
 * 6 and 8; unit tests assert this function reproduces them exactly.
 *
 * The two multipliers are deliberately separate. [uiScale] is the user's apparent-size
 * preference; [viewportFactor] corrects a device that misreports its density. The safe area
 * tracks only the latter, because overscan is a fixed fraction of the panel — a user asking
 * for smaller text must not shrink the margin that keeps content on screen.
 */
fun iglooDimens(
    uiScale: UiScale,
    viewportFactor: Float = 1f,
): IglooDimens = iglooDimens(uiScale, viewportFactor, uiScale.factor * viewportFactor)

private fun iglooDimens(
    uiScale: UiScale,
    viewportFactor: Float,
    scale: Float,
): IglooDimens = IglooDimens(
    scale = scale,
    spacing = IglooSpacing(
        xs = 4.dp.at(scale),
        sm = 8.dp.at(scale),
        md = 16.dp.at(scale),
        lg = 24.dp.at(scale),
        xl = 32.dp.at(scale),
        xxl = 48.dp.at(scale),
    ),
    radius = IglooRadius(
        sm = 6.dp.at(scale),
        md = 8.dp.at(scale),
        lg = 10.dp.at(scale),
        xl = 14.dp.at(scale),
        pill = 999.dp,
    ),
    sizes = IglooSizes(
        controlHeight = 52.dp.at(scale),
        fieldHeight = 56.dp.at(scale),
        navItemHeight = 44.dp.at(scale),
        brandTile = 48.dp.at(scale),
        dot = 10.dp.at(scale),
    ),
    icons = IglooIcons(
        md = 24.dp.at(scale),
        lg = 32.dp.at(scale),
    ),
    focus = IglooFocus(
        ringWidth = 3.dp,
        restWidth = 1.dp,
        scale = 1.05f,
        glowAlpha = 0.20f,
        glowElevation = 16.dp,
    ),
    layout = IglooLayout(
        safeAreaHorizontal = 48.dp.at(viewportFactor),
        safeAreaVertical = 27.dp.at(viewportFactor),
        navSpineWidth = 236.dp.at(scale),
        posterWidth = 148.dp.at(scale),
        wideCardWidth = 264.dp.at(scale),
        posterAspect = 2f / 3f,
        wideAspect = 16f / 9f,
        gridColumns = when (uiScale) {
            UiScale.Compact -> 6
            UiScale.Standard -> 5
            UiScale.Large -> 4
        },
    ),
)
