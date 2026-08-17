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

/** Whole hours, minutes, and seconds of a non-negative duration. */
private fun hms(seconds: Double): Triple<Long, Long, Long> {
    val total = seconds.toLong().coerceAtLeast(0)
    return Triple(total / 3600, (total % 3600) / 60, total % 60)
}

/** `72.4` → `"1:12"`, `3675.0` → `"1:01:15"`; the player's visual timecodes. */
fun formatTimecode(seconds: Double): String {
    val (hours, minutes, rest) = hms(seconds)
    return when {
        hours > 0 -> "%d:%02d:%02d".format(Locale.US, hours, minutes, rest)
        else -> "%d:%02d".format(Locale.US, minutes, rest)
    }
}

/** `72.4` → `"1 minute 12 seconds"`; timecodes read as digits are noise for a screen reader. */
fun formatSpokenTime(seconds: Double): String {
    val (hours, minutes, rest) = hms(seconds)
    val parts = buildList {
        if (hours > 0) add(if (hours == 1L) "1 hour" else "$hours hours")
        if (minutes > 0) add(if (minutes == 1L) "1 minute" else "$minutes minutes")
        if (rest > 0 || isEmpty()) add(if (rest == 1L) "1 second" else "$rest seconds")
    }
    return parts.joinToString(" ")
}

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
