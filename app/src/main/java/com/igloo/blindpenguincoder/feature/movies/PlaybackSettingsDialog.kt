package com.igloo.blindpenguincoder.feature.movies

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import com.igloo.blindpenguincoder.core.design.IglooMotion
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.iglooTween
import com.igloo.blindpenguincoder.core.ui.IglooButton
import com.igloo.blindpenguincoder.core.ui.IglooRadioRow
import com.igloo.blindpenguincoder.core.ui.IglooScrim
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.iglooSurface
import com.igloo.blindpenguincoder.data.model.PlaybackMode

/**
 * The Playback Settings modal (section 11.4.1): three flat radio lists — quality/mode, audio
 * track, subtitles — over a pinned explanation line and a Done button. The [IglooConfirmDialog]
 * recipe throughout (section 9.3): in-tree, scrimmed, one alpha reveal, no exit animation,
 * Back dismisses, focus trapped, and the invoker restores focus in [onDismiss].
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
    modifier: Modifier = Modifier,
) {
    val colors = IglooTheme.colors

    // The host gates its own Back handlers while this is composed (section 9.3).
    BackHandler(onBack = onDismiss)

    var visible by remember { mutableStateOf(false) }
    val reveal by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = iglooTween(IglooMotion.STANDARD_MS),
        label = "playbackSettingsReveal",
    )
    LaunchedEffect(Unit) { visible = true }

    val rowCount = settings.modes.size + settings.audioTracks.size + settings.subtitleTracks.size
    val rowRequesters = remember(rowCount) { List(rowCount) { FocusRequester() } }
    val doneRequester = remember { FocusRequester() }

    // Entry focus lands on the selected mode row — it always exists, the modes are static.
    LaunchedEffect(Unit) {
        val selected = settings.modes.indexOfFirst { it.mode == settings.selectedMode }
        rowRequesters[selected.coerceAtLeast(0)].requestFocus()
    }

    // Up/down walk the flat row list and continue onto Done; every other direction cancels so
    // focus search terminates inside the card instead of falling through the scrim.
    fun Modifier.rowFocus(index: Int): Modifier = this
        .focusRequester(rowRequesters[index])
        .focusProperties {
            left = FocusRequester.Cancel
            right = FocusRequester.Cancel
            up = rowRequesters.getOrNull(index - 1) ?: FocusRequester.Cancel
            down = rowRequesters.getOrNull(index + 1) ?: doneRequester
        }

    IglooScrim(
        modifier = modifier.graphicsLayer { alpha = reveal },
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints {
            val cardWidth = minOf(IglooTheme.layout.dialogWidth, maxWidth)
            val cardMaxHeight = maxHeight - IglooTheme.layout.safeAreaVertical * 2
            Column(
                modifier = Modifier
                    .width(cardWidth)
                    .heightIn(max = cardMaxHeight)
                    .iglooSurface(radius = IglooTheme.radius.xl, fill = colors.card)
                    .padding(IglooTheme.spacing.xl)
                    .semantics {
                        paneTitle = "Playback settings"
                        isTraversalGroup = true
                    }
                    .testTag("playback_settings_dialog"),
                verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.lg),
            ) {
                IglooText(
                    text = "Playback Settings",
                    style = IglooTheme.typography.titleMedium,
                    color = colors.cardForeground,
                    modifier = Modifier.semantics { heading() },
                )

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.xs),
                ) {
                    var row = 0
                    SectionHeading("Playback")
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

                    SectionHeading("Audio")
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

                    SectionHeading("Subtitles")
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

                // cardForeground, not mutedForeground, for the same reason as the confirm
                // dialog's body: this is prose the user must read to decide. Polite live region:
                // it changes only on a deliberate OK press, and the consequence of the choice is
                // exactly what the radio state alone cannot tell a TalkBack user.
                IglooText(
                    text = settings.explanation,
                    style = IglooTheme.typography.bodyMedium,
                    color = colors.cardForeground,
                    modifier = Modifier
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .testTag("playback_settings_explanation"),
                )

                IglooButton(
                    text = "Done",
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(doneRequester)
                        .focusProperties {
                            left = FocusRequester.Cancel
                            right = FocusRequester.Cancel
                            down = FocusRequester.Cancel
                            up = rowRequesters.last()
                        }
                        .testTag("playback_settings_done"),
                )
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
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
