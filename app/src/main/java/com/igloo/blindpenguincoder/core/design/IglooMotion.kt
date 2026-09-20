package com.igloo.blindpenguincoder.core.design

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import kotlin.math.PI
import kotlin.math.sin

/** Durations are time, not size — they never scale with [UiScale]. */
object IglooMotion {
    const val MICRO_MS = 150
    const val STANDARD_MS = 200
    const val PAGE_MS = 300

    /** Delay between items of one staggered section enter. */
    const val STAGGER_MS = 60

    /** One full cycle of a decorative ambient loop. See docs/design-system.md section 7.2. */
    const val AMBIENT_MS = 24_000

    /**
     * How long the launch splash stays up before it may hand off. A hold, not an animation —
     * reduced motion stills the screen but does not shorten it, or the brand moment becomes a
     * flash. See docs/design-system.md section 11.1.-1.
     */
    const val SPLASH_HOLD_MS = 900
}

object IglooEasing {
    val standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val exit = CubicBezierEasing(0.4f, 0f, 1f, 1f)
}

val LocalIglooReducedMotion = staticCompositionLocalOf { false }

/**
 * The only animation spec feature code should use. Snaps instead of animating when the system
 * asks for reduced motion, so no call site can forget the check. [snap] discards the delay, which
 * is what collapses a whole stagger to a single frame.
 */
@Composable
fun <T> iglooTween(
    durationMillis: Int,
    delayMillis: Int = 0,
    easing: Easing = IglooEasing.standard,
): FiniteAnimationSpec<T> =
    if (LocalIglooReducedMotion.current) {
        snap()
    } else {
        tween(durationMillis = durationMillis, delayMillis = delayMillis, easing = easing)
    }

/**
 * Progress of a decorative ambient loop, in [0, 1). The only way feature code may loop, and only
 * for a backdrop that meets all four conditions in docs/design-system.md section 7.2.
 *
 * Holds at 0 under reduced motion, so the still frame is the authored composition rather than an
 * arbitrary phase. The branch matters: a transition that is built and then ignored still requests
 * a frame callback sixty times a second for as long as the screen is composed.
 */
@Composable
fun rememberAmbientProgress(periodMillis: Int = IglooMotion.AMBIENT_MS): State<Float> =
    if (LocalIglooReducedMotion.current) {
        remember { mutableFloatStateOf(0f) }
    } else {
        rememberInfiniteTransition(label = "iglooAmbient").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = periodMillis, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "iglooAmbientProgress",
        )
    }

/**
 * Offset of an ambient element at [progress], staggered by [phase] and bounded by [amplitude].
 * Periodic by construction, so [RepeatMode.Restart] never jumps at the wrap.
 */
internal fun ambientOffset(progress: Float, phase: Float, amplitude: Float): Float =
    amplitude * sin((progress + phase) * 2f * PI.toFloat())

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
