package com.igloo.blindpenguincoder.feature.movies

import com.igloo.blindpenguincoder.data.model.AudioStream
import com.igloo.blindpenguincoder.data.model.PlaybackMode
import com.igloo.blindpenguincoder.data.model.Subtitle
import com.igloo.blindpenguincoder.playback.model.PlaybackGateResult
import com.igloo.blindpenguincoder.playback.model.audioCodecDisplayName
import com.igloo.blindpenguincoder.playback.model.availablePlaybackModes
import com.igloo.blindpenguincoder.playback.model.describeChannelLayout
import com.igloo.blindpenguincoder.playback.model.IMAGE_BASED_SUFFIX
import com.igloo.blindpenguincoder.playback.model.evaluatePlaybackGate
import com.igloo.blindpenguincoder.playback.model.isUnreliableHlsAudio
import com.igloo.blindpenguincoder.playback.model.languageDisplayName
import com.igloo.blindpenguincoder.playback.model.playbackModeLabel
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
 * null is "off".
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
 * resolved — and are what the Play wiring reads: [selectedMode], [selectedAudioId] (null = the
 * file's first/default track) and [selectedSubtitleId] (null = subtitles off).
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
 * Resolution rules:
 * - Modes: [availablePlaybackModes] returns the normative seven-mode ladder in order. Direct
 *   stays listed and selectable even when the selected audio track can't play on this
 *   device — offers may be filtered, but a user's choice is never overridden; the explanation
 *   (and the Play gate, in the same words) says why Direct would refuse.
 * - Effective audio: the selected id if the file still has it, else the `is_default` stream,
 *   else the first. A selection is matched by id, so a track list that changed under a kept
 *   selection degrades to the default instead of pointing at nothing. Unlike the web client,
 *   any track works under Direct — ExoPlayer demuxes the container and selects the track
 *   itself, so lossless audio survives on every track and no mode upgrade is needed.
 * - Effective subtitle: the selected id if present, else off. Image-based tracks (PGS/DVD/DVB)
 *   are selectable under Direct — ExoPlayer renders their bitmaps — but inert for every other
 *   mode, where the backend can only serve text tracks as VTT.
 */
internal fun playbackSettingsUi(
    audioStreams: List<AudioStream>?,
    subtitles: List<Subtitle>?,
    selection: PlaybackSelection,
    canPlayAudioMime: (mimeType: String, channels: Int?) -> Boolean = { _, _ -> true },
): PlaybackSettingsUi {
    val modes = availablePlaybackModes()
        .map { PlaybackModeOptionUi(it, playbackModeLabel(it)) }

    // `stream_index` order, for the same reason the play request uses it: the type ordinal is
    // what every consumer downstream shares, and the "Track N" fallback labels are numbered from
    // it — so a wire list that arrives unsorted must not label this dialog and the in-player
    // menus differently.
    val audio = audioStreams.orEmpty().sortedBy { it.streamIndex }
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

    val imageBasedSelectable = selection.mode == PlaybackMode.Direct
    val subtitleList = subtitles.orEmpty().sortedBy { it.streamIndex }
    val subtitleTracks = buildList {
        add(PlaybackTrackOptionUi(id = null, label = SUBTITLES_NONE_LABEL))
        subtitleList.forEachIndexed { index, subtitle ->
            val inert = isImageBasedSubtitleCodec(subtitle.codec) && !imageBasedSelectable
            add(
                PlaybackTrackOptionUi(
                    id = subtitle.id,
                    label = subtitleTrackLabel(subtitle, index) +
                        if (inert) IMAGE_BASED_SUFFIX else "",
                    enabled = !inert,
                ),
            )
        }
    }
    val effectiveSubtitle = subtitleList
        .firstOrNull { it.id == selection.subtitleStreamId }
        ?.takeUnless { isImageBasedSubtitleCodec(it.codec) && !imageBasedSelectable }

    // The same check, in the same words, that will refuse Play — shown here so the user
    // learns about an unplayable Direct combination while still inside the dialog.
    val directCaution = evaluatePlaybackGate(
        mode = selection.mode,
        audioCodec = effectiveAudio?.codec,
        audioCodecProfile = effectiveAudio?.codecProfile?.orNull(),
        audioChannels = effectiveAudio?.channels?.toInt(),
        audioLabel = effectiveAudio?.let { audioTrackLabel(it, effectiveAudioIndex) },
        canPlayMime = { mime -> canPlayAudioMime(mime, effectiveAudio?.channels?.toInt()) },
    ) as? PlaybackGateResult.Blocked

    // The same predicate the engine uses to substitute the Remux conversion under Direct —
    // announced here so the automatic switch never surprises anyone mid-movie.
    val conversionNote = effectiveAudio
        ?.takeIf {
            selection.mode == PlaybackMode.Direct &&
                isUnreliableHlsAudio(it.codec, it.channels.toInt())
        }
        ?.let {
            " This track's ${audioCodecDisplayName(it.codec, it.codecProfile?.orNull())} audio " +
                "will be adjusted automatically for reliable playback."
        }
        .orEmpty()

    return PlaybackSettingsUi(
        modes = modes,
        selectedMode = selection.mode,
        audioTracks = audioTracks,
        selectedAudioId = effectiveAudio?.id,
        subtitleTracks = subtitleTracks,
        selectedSubtitleId = effectiveSubtitle?.id,
        explanation = playbackExplanation(
            mode = selection.mode,
            audioLabel = effectiveAudio?.let { audioTrackLabel(it, effectiveAudioIndex) },
            subtitleLabel = effectiveSubtitle?.let {
                subtitleTrackLabel(it, subtitleList.indexOf(it))
            },
        ) + conversionNote + directCaution?.let { " ${it.message}" }.orEmpty(),
    )
}

/**
 * What the chosen settings will do, in one spoken-friendly line: how the video reaches the TV,
 * what will be heard, whether subtitles show. TV-adapted from the web's
 * `describePlaybackExperience`.
 */
internal fun playbackExplanation(
    mode: PlaybackMode,
    audioLabel: String?,
    subtitleLabel: String?,
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
