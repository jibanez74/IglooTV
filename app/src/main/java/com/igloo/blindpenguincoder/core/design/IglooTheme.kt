package com.igloo.blindpenguincoder.core.design

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

val LocalIglooColors = staticCompositionLocalOf { IglooDarkColors }
val LocalIglooDimens = staticCompositionLocalOf { iglooDimens(UiScale.Standard) }
val LocalIglooTypography = staticCompositionLocalOf { iglooTypography(UiScale.Standard.factor) }

/**
 * System font scale is honored but bounded. See docs/design-system.md section 12.1 for why
 * this clamp exists and when it should be revisited.
 */
private const val MIN_FONT_SCALE = 0.85f
private const val MAX_FONT_SCALE = 1.30f

object IglooTheme {
    val colors: IglooColors
        @Composable @ReadOnlyComposable get() = LocalIglooColors.current

    val typography: IglooTypography
        @Composable @ReadOnlyComposable get() = LocalIglooTypography.current

    val spacing: IglooSpacing
        @Composable @ReadOnlyComposable get() = LocalIglooDimens.current.spacing

    val radius: IglooRadius
        @Composable @ReadOnlyComposable get() = LocalIglooDimens.current.radius

    val sizes: IglooSizes
        @Composable @ReadOnlyComposable get() = LocalIglooDimens.current.sizes

    val icons: IglooIcons
        @Composable @ReadOnlyComposable get() = LocalIglooDimens.current.icons

    val focus: IglooFocus
        @Composable @ReadOnlyComposable get() = LocalIglooDimens.current.focus

    val layout: IglooLayout
        @Composable @ReadOnlyComposable get() = LocalIglooDimens.current.layout

    val reducedMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalIglooReducedMotion.current
}

@Composable
fun IglooTheme(
    darkTheme: Boolean = true,
    uiScale: UiScale = UiScale.Standard,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val containerWidthPx = LocalWindowInfo.current.containerSize.width
    val viewportFactor = viewportFactor(containerWidthPx / density.density)
    val scale = uiScale.factor * viewportFactor

    val dimens = remember(uiScale, viewportFactor) { iglooDimens(uiScale, viewportFactor) }
    val typography = remember(scale) { iglooTypography(scale) }
    val boundedDensity = remember(density) {
        val bounded = density.fontScale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
        if (bounded == density.fontScale) density else Density(density.density, bounded)
    }

    CompositionLocalProvider(
        LocalDensity provides boundedDensity,
        LocalIglooColors provides if (darkTheme) IglooDarkColors else IglooLightColors,
        LocalIglooDimens provides dimens,
        LocalIglooTypography provides typography,
        LocalIglooReducedMotion provides rememberReducedMotion(),
        content = content,
    )
}

/**
 * Overscan inset for chrome and text. Full-bleed content (backdrops, the player surface)
 * deliberately does not use this — see docs/design-system.md section 2.5.
 */
@Composable
fun Modifier.iglooSafeArea(): Modifier {
    val layout = IglooTheme.layout
    return padding(
        PaddingValues(
            horizontal = layout.safeAreaHorizontal,
            vertical = layout.safeAreaVertical,
        ),
    )
}

/**
 * Scales a genuinely one-off dimension. A dimension used in two or more files belongs in
 * [IglooDimens] and in docs/design-system.md instead.
 */
@Composable
@ReadOnlyComposable
fun Dp.scaled(): Dp = (value * LocalIglooDimens.current.scale).roundToInt().dp
