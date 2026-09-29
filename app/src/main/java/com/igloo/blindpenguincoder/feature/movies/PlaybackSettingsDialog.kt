package com.igloo.blindpenguincoder.feature.movies

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooRadioListDialog
import com.igloo.blindpenguincoder.core.ui.IglooRadioRow
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.data.model.PlaybackMode

/**
 * The Playback Settings modal (section 11.4.1): three flat radio lists — quality/mode, audio
 * track, subtitles — over a pinned explanation line, on the [IglooRadioListDialog] shell.
 *
 * Selection is not dismissal: OK on a row updates state and keeps focus where it is, so the
 * user can hear the explanation change and keep adjusting. Only Back and Done leave.
 */
@Composable
internal fun PlaybackSettingsDialog(
    settings: PlaybackSettingsUi,
    onSelectMode: (PlaybackMode) -> Unit,
    onSelectAudio: (Long) -> Unit,
    onSelectSubtitle: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    val rowCount = settings.modes.size + settings.audioTracks.size + settings.subtitleTracks.size
    IglooRadioListDialog(
        title = "Playback Settings",
        paneTitle = "Playback settings",
        rowCount = rowCount,
        // Entry focus lands on the selected mode row — it always exists, the modes are static.
        entryIndex = settings.modes.indexOfFirst { it.mode == settings.selectedMode }
            .coerceAtLeast(0),
        onDismiss = onDismiss,
        testTag = "playback_settings_dialog",
        doneTestTag = "playback_settings_done",
        footer = {
            // cardForeground, not mutedForeground, for the same reason as the confirm dialog's
            // body: this is prose the user must read to decide. Polite live region: it changes
            // only on a deliberate OK press, and the consequence of the choice is exactly what
            // the radio state alone cannot tell a TalkBack user.
            IglooText(
                text = settings.explanation,
                style = IglooTheme.typography.bodyMedium,
                color = IglooTheme.colors.cardForeground,
                modifier = Modifier
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag("playback_settings_explanation"),
            )
        },
    ) { rowFocus ->
        var row = 0
        DialogGroupLabel("Playback")
        settings.modes.forEach { option ->
            IglooRadioRow(
                label = option.label,
                selected = option.mode == settings.selectedMode,
                onSelect = { onSelectMode(option.mode) },
                modifier = Modifier
                    .rowFocus(row++)
                    .testTag("playback_mode_${modeTag(option.mode)}"),
            )
        }

        DialogGroupLabel("Audio")
        settings.audioTracks.forEach { track ->
            IglooRadioRow(
                label = track.label,
                selected = track.id == settings.selectedAudioId,
                onSelect = track.id
                    ?.takeIf { track.enabled }
                    ?.let { id -> { onSelectAudio(id) } },
                modifier = Modifier
                    .rowFocus(row++)
                    .testTag(
                        track.id?.let { "audio_track_$it" } ?: "audio_track_default",
                    ),
            )
        }

        DialogGroupLabel("Subtitles")
        settings.subtitleTracks.forEach { track ->
            IglooRadioRow(
                label = track.label,
                selected = track.id == settings.selectedSubtitleId,
                onSelect = if (track.enabled) {
                    { onSelectSubtitle(track.id) }
                } else {
                    null
                },
                modifier = Modifier
                    .rowFocus(row++)
                    .testTag(track.id?.let { "subtitle_$it" } ?: "subtitle_none"),
            )
        }
    }
}

@Composable
private fun DialogGroupLabel(text: String) {
    IglooText(
        text = text,
        style = IglooTheme.typography.label,
        color = IglooTheme.colors.mutedForeground,
        modifier = Modifier
            .padding(
                start = IglooTheme.spacing.md,
                top = IglooTheme.spacing.sm,
                bottom = IglooTheme.spacing.xs,
            )
            .semantics { heading() },
    )
}

/** The wire id ("direct", "1080p_8mbps") as a stable test tag suffix. */
private fun modeTag(mode: PlaybackMode): String =
    when (mode) {
        PlaybackMode.Direct -> "direct"
        PlaybackMode.Remux -> "remux"
        PlaybackMode.P2160Mbps16 -> "2160p_16mbps"
        PlaybackMode.P1080Mbps8 -> "1080p_8mbps"
        PlaybackMode.P1080Mbps6 -> "1080p_6mbps"
        PlaybackMode.P1080Mbps4 -> "1080p_4mbps"
        PlaybackMode.P720Mbps3 -> "720p_3mbps"
    }
