package com.igloo.blindpenguincoder.core.ui

import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.ceil

/** Grouped integer format for on-screen counts: `1234` → `"1,234"`. */
val integerCountFormat: NumberFormat = NumberFormat.getIntegerInstance()

/** The noun beside a count: the [singular] for exactly one, otherwise [plural]. */
fun countNoun(count: Long, singular: String, plural: String = singular + "s"): String =
    if (count == 1L) singular else plural

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

/** `72.4` → `"1 minute and 12 seconds"`; empty units are omitted. */
fun formatSpokenTime(seconds: Double): String = formatSpokenTime(seconds, throughSeconds = false)

/** `3617.9` → `"1 hour, 0 minutes, and 17 seconds"`; exact through completed seconds. */
fun formatSpokenTimeThroughSeconds(seconds: Double): String =
    formatSpokenTime(seconds, throughSeconds = true)

private fun formatSpokenTime(seconds: Double, throughSeconds: Boolean): String {
    val (hours, minutes, rest) = hms(seconds)
    val units = listOf(
        hours to "hour",
        minutes to "minute",
        rest to "second",
    )
    val firstRelevantUnit = units.indexOfFirst { it.first > 0 }.takeIf { it >= 0 }
        ?: units.lastIndex
    val parts = units
        .filterIndexed { index, unit ->
            if (throughSeconds) index >= firstRelevantUnit else unit.first > 0
        }
        .ifEmpty { listOf(units.last()) }
        .map { (value, unit) ->
            if (value == 1L) "1 $unit" else "$value ${unit}s"
        }
    return when (parts.size) {
        1 -> parts.first()
        2 -> parts.joinToString(" and ")
        else -> parts.dropLast(1).joinToString(", ") + ", and " + parts.last()
    }
}

fun progressFraction(progressSec: Double, durationSec: Double): Float =
    if (progressSec.isFinite() && durationSec.isFinite() && durationSec > 0) {
        (progressSec / durationSec).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }

/** Compact remaining time for TV display; partial minutes round up. */
fun formatRemainingTime(progressSec: Double, durationSec: Double): String {
    val secondsLeft = remainingSeconds(progressSec, durationSec) ?: return "In progress"
    if (secondsLeft < 60.0) return "Less than 1m left"
    val minutesLeft = ceil(secondsLeft / 60.0).toLong()
    val hours = minutesLeft / 60
    val minutes = minutesLeft % 60
    return when {
        hours == 0L -> "${minutes}m left"
        minutes == 0L -> "${hours}h left"
        else -> "${hours}h ${minutes}m left"
    }
}

/** Unabbreviated remaining time for TalkBack; partial minutes round up. */
fun formatSpokenRemainingTime(progressSec: Double, durationSec: Double): String {
    val secondsLeft = remainingSeconds(progressSec, durationSec) ?: return "In progress"
    if (secondsLeft < 60.0) return "Less than 1 minute remaining"
    val minutesLeft = ceil(secondsLeft / 60.0).toLong()
    val hours = minutesLeft / 60
    val minutes = minutesLeft % 60
    val parts = buildList {
        if (hours > 0) add(if (hours == 1L) "1 hour" else "$hours hours")
        if (minutes > 0) add(if (minutes == 1L) "1 minute" else "$minutes minutes")
    }
    return "${parts.joinToString(" and ")} remaining"
}

/** The contract sends a positive duration; null is the defensive invalid-data fallback. */
private fun remainingSeconds(progressSec: Double, durationSec: Double): Double? {
    if (!durationSec.isFinite() || durationSec <= 0 || !progressSec.isFinite()) return null
    return (durationSec - progressSec).coerceAtLeast(0.0)
}
