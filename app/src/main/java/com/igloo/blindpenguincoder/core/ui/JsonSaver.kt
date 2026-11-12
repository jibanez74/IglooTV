package com.igloo.blindpenguincoder.core.ui

import androidx.compose.runtime.saveable.Saver
import kotlinx.serialization.json.Json

/**
 * Saves a nullable value as one JSON string, "" for none (or for one [keep] turns away).
 *
 * Restore is wrapped: it parses a bundle written by a possibly older build (one whose shape has
 * since changed), and a throw there lands inside saved state restoration, which means a crash on
 * relaunch — while the honest degradation is simply to come back with nothing remembered.
 */
internal inline fun <reified T : Any> jsonSaver(
    crossinline keep: (T) -> Boolean = { true },
): Saver<T?, Any> = Saver(
    save = { value -> value?.takeIf(keep)?.let { Json.encodeToString(it) }.orEmpty() },
    restore = { saved ->
        (saved as? String)?.ifEmpty { null }
            ?.let { runCatching { Json.decodeFromString<T>(it) }.getOrNull() }
    },
)
