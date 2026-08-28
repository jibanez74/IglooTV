package com.igloo.blindpenguincoder.playback.model

import java.util.Locale

/**
 * The shared track-label vocabulary: the pre-play Playback Settings dialog and the in-player
 * track menus must describe the same stream in the same words, whether it arrives as an ffprobe
 * wire model or a Media3 Format.
 */

/** Marks a subtitle row the current mode cannot serve, pre-play and in-player alike. */
internal const val IMAGE_BASED_SUFFIX = " (image-based)"

/** The web's `describePlaybackChannelLayout`: named layouts first, then channel-count guesses. */
internal fun describeChannelLayout(channelLayout: String?, channels: Long): String {
    val layout = channelLayout?.lowercase(Locale.US).orEmpty()
    return when {
        layout.contains("mono") || channels == 1L -> "Mono"
        layout.contains("stereo") || channels == 2L -> "Stereo"
        layout.contains("5.1") -> "5.1 surround"
        layout.contains("7.1") -> "7.1 surround"
        layout.contains("quad") || layout.contains("4.0") -> "Quad"
        channels >= 6 -> "Surround"
        else -> "$channels channels"
    }
}

/**
 * A language tag as a display name. Unlike the web's `formatLanguageName`, which slices
 * three-letter codes to two and so misses every 639-2 code whose prefix isn't its 639-1 form
 * ("spa", "deu", "zho"), this goes through the 639-2 table the web ships for its preference
 * matching. Unknown short codes surface uppercased rather than vanishing.
 */
internal fun languageDisplayName(code: String?): String? {
    val raw = code?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() } ?: return null
    val two = if (raw.length == 3) ISO_639_2_TO_1[raw] else raw
    two?.let { LANGUAGE_NAMES[it] }?.let { return it }
    return if (raw.length <= 3) {
        raw.uppercase(Locale.US)
    } else {
        raw.replaceFirstChar { it.uppercase(Locale.US) }
    }
}

/** ISO 639-2 three-letter codes → 639-1 two-letter codes (web's `ISO_639_2_TO_1`). */
private val ISO_639_2_TO_1 = mapOf(
    "ara" to "ar",
    "ces" to "cs",
    "cze" to "cs",
    "dan" to "da",
    "deu" to "de",
    "ger" to "de",
    "ell" to "el",
    "gre" to "el",
    "eng" to "en",
    "spa" to "es",
    "fin" to "fi",
    "fra" to "fr",
    "fre" to "fr",
    "heb" to "he",
    "hin" to "hi",
    "hun" to "hu",
    "ita" to "it",
    "jpn" to "ja",
    "kor" to "ko",
    "nld" to "nl",
    "dut" to "nl",
    "nor" to "no",
    "pol" to "pl",
    "por" to "pt",
    "ron" to "ro",
    "rum" to "ro",
    "rus" to "ru",
    "swe" to "sv",
    "tha" to "th",
    "tur" to "tr",
    "ukr" to "uk",
    "vie" to "vi",
    "zho" to "zh",
    "chi" to "zh",
)

/** ISO 639-1 two-letter codes → English display names (web's `LANGUAGE_NAMES`). */
private val LANGUAGE_NAMES = mapOf(
    "ar" to "Arabic",
    "cs" to "Czech",
    "da" to "Danish",
    "de" to "German",
    "el" to "Greek",
    "en" to "English",
    "es" to "Spanish",
    "fi" to "Finnish",
    "fr" to "French",
    "he" to "Hebrew",
    "hi" to "Hindi",
    "hu" to "Hungarian",
    "it" to "Italian",
    "ja" to "Japanese",
    "ko" to "Korean",
    "nl" to "Dutch",
    "no" to "Norwegian",
    "pl" to "Polish",
    "pt" to "Portuguese",
    "ro" to "Romanian",
    "ru" to "Russian",
    "sv" to "Swedish",
    "th" to "Thai",
    "tr" to "Turkish",
    "uk" to "Ukrainian",
    "vi" to "Vietnamese",
    "zh" to "Chinese",
)
