package com.igloo.blindpenguincoder.feature.movies

import com.igloo.blindpenguincoder.data.model.AudioStream
import com.igloo.blindpenguincoder.data.model.PlaybackMode
import com.igloo.blindpenguincoder.data.model.Subtitle
import java.util.Locale

/**
 * The Playback Settings dialog's wire-to-UI rules (section 11.4.1), ported from the web client's
 * in-player dialog (`web/src/lib/playback.ts`) so the two clients describe the same choices in
 * the same words. Everything here is pure: the view model calls [playbackSettingsUi] with the
 * technical-details fragment and the stored [PlaybackSelection]; composables read strings.
 */

/**
 * What the user picked, session-only — reset when the overlay opens or closes, never persisted.
 * Null ids mean "no explicit choice": audio falls back to the file's default track, subtitle
 * null is "off". The mode the user *stored* can differ from the mode that would actually play —
 * see the direct-play rule in [playbackSettingsUi].
 */
internal data class PlaybackSelection(
    val mode: PlaybackMode = PlaybackMode.Direct,
    val audioStreamId: Long? = null,
    val subtitleStreamId: Long? = null,
)

/** One quality/mode radio row. */
data class PlaybackModeOptionUi(val mode: PlaybackMode, val label: String)

/** One audio or subtitle radio row; a null [id] is the "Default"/"None" row of its section. */
data class PlaybackTrackOptionUi(
    val id: Long?,
    val label: String,
    val enabled: Boolean = true,
)

/**
 * The dialog, render-ready. The three `selected*` fields are the *effective* choice — defaults
 * resolved, the direct-play audio rule applied — and are what the Play wiring will read when
 * playback lands: [selectedMode], [selectedAudioId] (null = the file's first/default track) and
 * [selectedSubtitleId] (null = subtitles off).
 */
data class PlaybackSettingsUi(
    val modes: List<PlaybackModeOptionUi>,
    val selectedMode: PlaybackMode,
    val audioTracks: List<PlaybackTrackOptionUi>,
    val selectedAudioId: Long?,
    val subtitleTracks: List<PlaybackTrackOptionUi>,
    val selectedSubtitleId: Long?,
    val explanation: String,
)

/**
 * Composes the dialog from whatever has arrived. [audioStreams] and [subtitles] are null while
 * the technical-details request is in flight or degraded quietly — the sections then hold their
 * inert stand-ins ("Default", "None") so the focus chain and announcements stay meaningful.
 *
 * Resolution rules, all web parity:
 * - Effective audio: the selected id if the file still has it, else the `is_default` stream,
 *   else the first. A selection is matched by id, so a track list that changed under a kept
 *   selection degrades to the default instead of pointing at nothing.
 * - Effective subtitle: the selected id if present and not image-based, else off. Image-based
 *   tracks (PGS/DVD/DVB) render as inert rows — the backend can only serve text tracks as VTT.
 * - Effective mode: direct play serves the raw container, which always sounds its first track,
 *   so Direct plus any other audio track resolves to Remux and the explanation says why
 *   (`resolveModeForAudioTrack` on the web).
 */
internal fun playbackSettingsUi(
    audioStreams: List<AudioStream>?,
    subtitles: List<Subtitle>?,
    selection: PlaybackSelection,
): PlaybackSettingsUi {
    val modes = PlaybackMode.entries.map { PlaybackModeOptionUi(it, playbackModeLabel(it)) }

    val audio = audioStreams.orEmpty()
    val effectiveAudioIndex = audio.indexOfFirst { it.id == selection.audioStreamId }
        .takeIf { it >= 0 }
        ?: audio.indexOfFirst { it.isDefault }.takeIf { it >= 0 }
        ?: 0
    val effectiveAudio = audio.getOrNull(effectiveAudioIndex)
    val audioTracks = if (effectiveAudio == null) {
        listOf(PlaybackTrackOptionUi(id = null, label = AUDIO_DEFAULT_LABEL, enabled = false))
    } else {
        audio.mapIndexed { index, stream ->
            PlaybackTrackOptionUi(id = stream.id, label = audioTrackLabel(stream, index))
        }
    }

    val subtitleTracks = buildList {
        add(PlaybackTrackOptionUi(id = null, label = SUBTITLES_NONE_LABEL))
        subtitles.orEmpty().forEachIndexed { index, subtitle ->
            val imageBased = isImageBasedSubtitleCodec(subtitle.codec)
            add(
                PlaybackTrackOptionUi(
                    id = subtitle.id,
                    label = subtitleTrackLabel(subtitle, index) +
                        if (imageBased) IMAGE_BASED_SUFFIX else "",
                    enabled = !imageBased,
                ),
            )
        }
    }
    val effectiveSubtitle = subtitles.orEmpty()
        .firstOrNull { it.id == selection.subtitleStreamId }
        ?.takeUnless { isImageBasedSubtitleCodec(it.codec) }

    val audioForcesRemux = selection.mode == PlaybackMode.Direct && effectiveAudioIndex != 0
    val effectiveMode = if (audioForcesRemux) PlaybackMode.Remux else selection.mode

    return PlaybackSettingsUi(
        modes = modes,
        selectedMode = effectiveMode,
        audioTracks = audioTracks,
        selectedAudioId = effectiveAudio?.id,
        subtitleTracks = subtitleTracks,
        selectedSubtitleId = effectiveSubtitle?.id,
        explanation = playbackExplanation(
            mode = effectiveMode,
            audioLabel = effectiveAudio?.let { audioTrackLabel(it, effectiveAudioIndex) },
            subtitleLabel = effectiveSubtitle?.let {
                subtitleTrackLabel(it, subtitles.orEmpty().indexOf(it))
            },
            audioForcedRemux = audioForcesRemux,
        ),
    )
}

/**
 * The web's `STREAM_MODES` labels, except the first two say "Original quality" outright —
 * the user asked for the original-quality option to be unmistakable on a TV screen.
 */
internal fun playbackModeLabel(mode: PlaybackMode): String = when (mode) {
    PlaybackMode.Direct -> "Original quality — plays the file as-is"
    PlaybackMode.Remux -> "Original quality — audio adjusted"
    PlaybackMode.P2160Mbps16 -> "4K — highest quality"
    PlaybackMode.P1080Mbps8 -> "1080p — best quality"
    PlaybackMode.P1080Mbps6 -> "1080p — high quality"
    PlaybackMode.P1080Mbps4 -> "1080p — balanced"
    PlaybackMode.P720Mbps3 -> "720p — lower bandwidth"
}

/**
 * What the chosen settings will do, in one spoken-friendly line: how the video reaches the TV,
 * what will be heard, whether subtitles show — and, when the direct-play audio rule kicked in,
 * why the mode moved. TV-adapted from the web's `describePlaybackExperience`.
 */
internal fun playbackExplanation(
    mode: PlaybackMode,
    audioLabel: String?,
    subtitleLabel: String?,
    audioForcedRemux: Boolean = false,
): String = buildString {
    append(
        when (mode) {
            PlaybackMode.Direct ->
                "Your movie plays directly with no conversion — the best option when your " +
                    "file already matches what your TV can play."
            PlaybackMode.Remux ->
                "The picture stays the same; the soundtrack is adjusted so playback works " +
                    "reliably."
            else ->
                "Video is converted and streamed for smooth playback " +
                    "(${playbackModeLabel(mode)}). A steady internet connection helps."
        },
    )
    append(if (audioLabel != null) " You'll hear: $audioLabel." else " Default audio is used.")
    append(if (subtitleLabel != null) " Subtitles: $subtitleLabel." else " Subtitles are off.")
    if (audioForcedRemux) {
        append(
            " Direct play always uses the first audio track, so playback switched to " +
                "\"${playbackModeLabel(PlaybackMode.Remux)}.\" The picture is untouched.",
        )
    }
}

/** "English · 5.1 surround", or "Track 2 · Stereo" when ffprobe reported no language. */
internal fun audioTrackLabel(stream: AudioStream, index: Int): String {
    val language = languageDisplayName(stream.language?.orNull())
    val channels = describeChannelLayout(stream.channelLayout?.orNull(), stream.channels)
    return "${language ?: "Track ${index + 1}"} · $channels"
}

/** "English · SDH · Forced · Default" from whatever parts exist, or "Track N" from none. */
internal fun subtitleTrackLabel(subtitle: Subtitle, index: Int): String {
    val language = languageDisplayName(subtitle.language?.orNull())
    val title = subtitle.title?.orNullIfBlank()
    val parts = buildList {
        language?.let { add(it) }
        title?.takeIf { it != language }?.let { add(it) }
        if (subtitle.isForced) add("Forced")
        if (subtitle.isDefault) add("Default")
    }
    return if (parts.isNotEmpty()) parts.joinToString(" · ") else "Track ${index + 1}"
}

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
 * ffprobe's language tag as a display name. Unlike the web's `formatLanguageName`, which slices
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

/** PGS/DVD/DVB tracks are bitmaps the backend cannot serve as VTT (web parity). */
internal fun isImageBasedSubtitleCodec(codec: String): Boolean =
    codec.lowercase(Locale.US) in BITMAP_SUBTITLE_CODECS

private val BITMAP_SUBTITLE_CODECS = setOf(
    "hdmv_pgs_subtitle",
    "dvd_subtitle",
    "dvb_subtitle",
)

internal const val AUDIO_DEFAULT_LABEL = "Default"
internal const val SUBTITLES_NONE_LABEL = "None"
private const val IMAGE_BASED_SUFFIX = " (image-based)"

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
