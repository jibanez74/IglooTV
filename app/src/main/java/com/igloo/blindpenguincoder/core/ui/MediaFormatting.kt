package com.igloo.blindpenguincoder.core.ui

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.ceil

/** `170` → `"2h 50m"`; whole hours and sub-hour runtimes drop the empty part. */
fun formatRuntime(minutes: Long): String {
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0L -> "${rest}m"
        rest == 0L -> "${hours}h"
        else -> "${hours}h ${rest}m"
    }
}

private val releaseDateFormat: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(Locale.US)

/** The contract's `YYYY-MM-DD` → `"December 15, 1995"`; null when the value is not a date. */
fun formatReleaseDate(isoDate: String): String? = runCatching {
    LocalDate.parse(isoDate).format(releaseDateFormat)
}.getOrNull()

fun progressFraction(progressSec: Double, durationSec: Double): Float =
    if (durationSec > 0) (progressSec / durationSec).toFloat().coerceIn(0f, 1f) else 0f

/** The contract always sends a positive duration; the fallback is defensive only. */
fun progressLabel(progressSec: Double, durationSec: Double): String {
    if (durationSec <= 0) return "In progress"
    val minutesLeft = ceil((durationSec - progressSec).coerceAtLeast(0.0) / 60.0)
        .toInt()
        .coerceAtLeast(1)
    return "$minutesLeft min left"
}
