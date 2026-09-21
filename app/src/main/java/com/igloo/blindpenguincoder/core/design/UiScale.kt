package com.igloo.blindpenguincoder.core.design

/**
 * User-declared UI size. Android TV reports ~960x540dp regardless of the panel's physical
 * size, and viewing distance is not detectable, so apparent size is the user's choice rather
 * than something the app infers. See docs/design-system.md section 2.
 */
enum class UiScale(val factor: Float) {
    Compact(0.875f),
    Standard(1.0f),
    Large(1.15f),
    ;

    companion object {
        fun fromName(name: String?): UiScale = entries.firstOrNull { it.name == name } ?: Standard
    }
}

/** Reference TV viewport width; every Standard dimension is authored against it. */
const val TV_REFERENCE_WIDTH_DP = 960f

/**
 * Above this width the device is misreporting density rather than genuinely offering more
 * room. Sits well above any real TV viewport and well below the 1920dp failure case.
 */
private const val VIEWPORT_GUARD_DP = 1200f

private const val MAX_VIEWPORT_FACTOR = 2f

/**
 * Corrects devices that report density 1.0, which turns a 1080p panel into a 1920x1080dp
 * viewport and would render the UI at half its intended apparent size. This is a guard, not
 * a breakpoint: it restores the reference size and never changes layout shape.
 */
internal fun viewportFactor(widthDp: Float): Float =
    if (widthDp >= VIEWPORT_GUARD_DP) {
        (widthDp / TV_REFERENCE_WIDTH_DP).coerceAtMost(MAX_VIEWPORT_FACTOR)
    } else {
        1f
    }
