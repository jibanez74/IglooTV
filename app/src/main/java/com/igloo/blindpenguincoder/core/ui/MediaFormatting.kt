package com.igloo.blindpenguincoder.core.ui

import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.ceil

private val integerCountFormat: NumberFormat = NumberFormat.getIntegerInstance()

/** Grouped integer text for on-screen counts: `1234` → `"1,234"`. */
fun formatCount(count: Long): String = integerCountFormat.format(count)

/** The noun beside a count: the [singular] for exactly one, otherwise its plural. */
fun countNoun(count: Long, singular: String): String =
    if (count == 1L) singular else singular + "s"

/** A line built from [values]: blanks dropped, and null rather than an empty line. */
fun joinedLine(values: List<String>, separator: String): String? = values
    .filter { it.isNotBlank() }
    .takeIf { it.isNotEmpty() }
    ?.joinToString(separator)

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
internal fun hms(seconds: Double): Triple<Long, Long, Long> {
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
        .map { (value, unit) -> "$value ${countNoun(value, unit)}" }
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

/** The longest caption [formatRemainingTime] writes, so a layout can reserve its width. */
internal const val LESS_THAN_A_MINUTE_LEFT = "Less than 1m left"

/** Compact remaining time for TV display; partial minutes round up. */
fun formatRemainingTime(progressSec: Double, durationSec: Double): String {
    val secondsLeft = remainingSeconds(progressSec, durationSec) ?: return "In progress"
    if (secondsLeft < 60.0) return LESS_THAN_A_MINUTE_LEFT
    return "${formatRuntime(ceil(secondsLeft / 60.0).toLong())} left"
}

/** Unabbreviated remaining time for TalkBack; partial minutes round up. */
fun formatSpokenRemainingTime(progressSec: Double, durationSec: Double): String {
    val secondsLeft = remainingSeconds(progressSec, durationSec) ?: return "In progress"
    if (secondsLeft < 60.0) return "Less than 1 minute remaining"
    return "${formatSpokenTime(ceil(secondsLeft / 60.0) * 60.0)} remaining"
}

/** The contract sends a positive duration; null is the defensive invalid-data fallback. */
private fun remainingSeconds(progressSec: Double, durationSec: Double): Double? {
    if (!durationSec.isFinite() || durationSec <= 0 || !progressSec.isFinite()) return null
    return (durationSec - progressSec).coerceAtLeast(0.0)
}

/** `1, 3` -> "S1 E3": the compact episode code the web client also uses. */
fun formatEpisodeCode(seasonNumber: Long, episodeNumber: Long): String =
    "S$seasonNumber E$episodeNumber"

/** The season as TalkBack says it: "season 1", or "Specials" for season 0. */
fun spokenSeason(seasonNumber: Long): String =
    if (seasonNumber == 0L) "Specials" else "season $seasonNumber"
