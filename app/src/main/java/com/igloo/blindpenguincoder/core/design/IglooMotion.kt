package com.igloo.blindpenguincoder.core.design

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/** Durations are time, not size — they never scale with [UiScale]. */
object IglooMotion {
    const val MICRO_MS = 150
    const val STANDARD_MS = 200
    const val PAGE_MS = 300
}

object IglooEasing {
    val standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val exit = CubicBezierEasing(0.4f, 0f, 1f, 1f)
}

val LocalIglooReducedMotion = staticCompositionLocalOf { false }

/**
 * The only animation spec feature code should use. Snaps instead of animating when the system
 * asks for reduced motion, so no call site can forget the check.
 */
@Composable
fun <T> iglooTween(
    durationMillis: Int,
    easing: Easing = IglooEasing.standard,
): FiniteAnimationSpec<T> =
    if (LocalIglooReducedMotion.current) {
        snap()
    } else {
        tween(durationMillis = durationMillis, easing = easing)
    }

/** Observes the system animator scale live, so toggling it takes effect without a restart. */
@Composable
internal fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    val resolver = remember(context) { context.contentResolver }
    var reduced by remember(resolver) { mutableStateOf(animatorScaleDisabled(resolver)) }

    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = animatorScaleDisabled(resolver)
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }

    return reduced
}

private fun animatorScaleDisabled(resolver: android.content.ContentResolver): Boolean =
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
