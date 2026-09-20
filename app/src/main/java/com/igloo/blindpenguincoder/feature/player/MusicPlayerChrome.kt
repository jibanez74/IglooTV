package com.igloo.blindpenguincoder.feature.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.overMedia
import com.igloo.blindpenguincoder.core.ui.IglooIcons
import com.igloo.blindpenguincoder.core.ui.IglooNotice
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.iglooSurface
import com.igloo.blindpenguincoder.feature.shared.readingStopTarget
import com.igloo.blindpenguincoder.playback.model.MusicPlayRequest
import com.igloo.blindpenguincoder.playback.model.MusicPlayTrack
import com.igloo.blindpenguincoder.playback.model.MusicQueueSource
import com.igloo.blindpenguincoder.playback.model.MusicPlayerPhase
import com.igloo.blindpenguincoder.playback.model.MusicPlayerState

/**
 * The always-visible chrome: top title bar named after the queue's source, centered cover of
 * the current track, bottom track block + transport. [notice] is the queue's one refill
 * message (docs/design-system.md section 10): announced politely, never a focus stop.
 */
@Composable
internal fun MusicPlayerChrome(
    request: MusicPlayRequest,
    state: MusicPlayerState,
    currentTrack: MusicPlayTrack?,
    notice: String?,
    playPauseRequester: FocusRequester,
    backRequester: FocusRequester,
    metadataRequester: FocusRequester,
    spokenAccessibilityEnabled: Boolean,
    onBack: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekBy: (Double) -> Unit,
    onSkipToNext: () -> Unit,
    onSkipToPrevious: () -> Unit,
) {
    val layout = IglooTheme.layout
    val playing = state.playWhenReady
    val trackTitle = currentTrack?.title ?: ""
    val transportUpRequester = if (spokenAccessibilityEnabled) metadataRequester else backRequester
    val backDownRequester = if (spokenAccessibilityEnabled) metadataRequester else playPauseRequester

    Column(modifier = Modifier.fillMaxSize()) {
        PlayerTopBar(
            title = request.source.title,
            backRequester = backRequester,
            downRequester = backDownRequester,
            backTag = "music_back",
            onBack = onBack,
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = IglooTheme.spacing.md),
            contentAlignment = Alignment.Center,
        ) {
            MusicPlayerCover(coverUrl = currentTrack?.coverUrl)
            val holdMessage = when (state.phase) {
                MusicPlayerPhase.Loading -> "Loading…"
                MusicPlayerPhase.Buffering -> "Buffering…"
                else -> null
            }
            if (holdMessage != null) {
                IglooText(
                    text = holdMessage,
                    style = IglooTheme.typography.bodyLarge.overMedia(true),
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .testTag("music_loading"),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .playerBottomScrim()
                .padding(
                    horizontal = layout.safeAreaHorizontal,
                    vertical = layout.safeAreaVertical,
                ),
            verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
        ) {
            if (notice != null) {
                IglooNotice(text = notice, modifier = Modifier.testTag("music_queue_notice"))
            }
            // The visible pair carries one spoken sentence. TV TalkBack can reach it through a
            // focus-only reading stop; sighted users keep the direct transport-to-Back route.
            val positionLine = musicPositionLine(
                source = request.source,
                index = state.currentTrackIndex,
                queueSize = request.tracks.size,
                track = currentTrack,
            )
            var metadataFocused by remember { mutableStateOf(false) }
            Column(
                modifier = if (spokenAccessibilityEnabled) {
                    Modifier.readingStopTarget(
                        tag = "music_track_metadata",
                        focused = metadataFocused,
                        requester = metadataRequester,
                        upRequester = backRequester,
                        downRequester = playPauseRequester,
                        onFocusChanged = { metadataFocused = it },
                        description = "$trackTitle. $positionLine.",
                        radius = IglooTheme.radius.lg,
                    )
                } else {
                    Modifier.clearAndSetSemantics {
                        contentDescription = "$trackTitle. $positionLine."
                    }
                },
            ) {
                IglooText(
                    text = trackTitle,
                    style = IglooTheme.typography.titleLarge.overMedia(true),
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier.testTag("music_track_title"),
                )
                IglooText(
                    text = positionLine,
                    style = IglooTheme.typography.bodyLarge.overMedia(true),
                    color = OVER_MEDIA_SECONDARY,
                    maxLines = 1,
                    modifier = Modifier.testTag("music_track_position"),
                )
            }
            PlayerSeekBar(
                currentTimeSec = state.currentTimeSec,
                durationSec = state.durationSec,
                seekTrackTag = "music_seek_track",
            )
            fun Modifier.transportEdges(isFirst: Boolean = false, isLast: Boolean = false) =
                transportFocus(transportUpRequester, isFirst, isLast)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(
                    IglooTheme.spacing.lg,
                    Alignment.CenterHorizontally,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TransportButton(
                    icon = IglooIcons.SkipPrevious,
                    label = "Previous track",
                    onClick = onSkipToPrevious,
                    modifier = Modifier
                        .transportEdges(isFirst = true)
                        .testTag("music_previous"),
                )
                TransportButton(
                    icon = IglooIcons.Rewind,
                    label = "Rewind 10 seconds",
                    onClick = { onSeekBy(-SEEK_STEP_SEC) },
                    modifier = Modifier
                        .transportEdges()
                        .testTag("music_rewind"),
                )
                TransportButton(
                    icon = if (playing) IglooIcons.Pause else IglooIcons.Play,
                    label = if (playing) "Pause" else "Play",
                    onClick = onTogglePlayPause,
                    modifier = Modifier
                        .focusRequester(playPauseRequester)
                        .transportEdges()
                        .testTag("music_play_pause"),
                )
                TransportButton(
                    icon = IglooIcons.FastForward,
                    label = "Forward 10 seconds",
                    onClick = { onSeekBy(SEEK_STEP_SEC) },
                    modifier = Modifier
                        .transportEdges()
                        .testTag("music_forward"),
                )
                TransportButton(
                    icon = IglooIcons.SkipNext,
                    label = "Next track",
                    onClick = onSkipToNext,
                    modifier = Modifier
                        .transportEdges(isLast = true)
                        .testTag("music_next"),
                )
            }
        }
    }
}

/**
 * "Track N of M · artist · album": the count names the whole queue for a finite source, the
 * library's total for Play all, and nothing for a shuffle that has no end to count to. The
 * album name is dropped when the source is the album — the top bar already carries it.
 */
internal fun musicPositionLine(
    source: MusicQueueSource,
    index: Int,
    queueSize: Int,
    track: MusicPlayTrack?,
): String {
    val position = when (source) {
        is MusicQueueSource.LibraryInOrder -> "Track ${index + 1} of ${source.total}"
        MusicQueueSource.LibraryShuffle -> "Track ${index + 1}"
        else -> "Track ${index + 1} of $queueSize"
    }
    return listOfNotNull(
        position,
        track?.artistName,
        track?.albumTitle?.takeUnless { source is MusicQueueSource.Album },
    ).joinToString(" · ")
}

/** Decorative — the artwork repeats nothing the track block does not say, so TalkBack skips it. */
@Composable
private fun MusicPlayerCover(coverUrl: String?) {
    var imageFailed by remember(coverUrl) { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .aspectRatio(IglooTheme.layout.albumAspect)
            // The muted token pair would vanish over the player's literal black; the over-media
            // control fill is the section 3.2 ground for chrome on media.
            .iglooSurface(radius = IglooTheme.radius.lg, fill = OVER_MEDIA_CONTROL_FILL),
        contentAlignment = Alignment.Center,
    ) {
        if (coverUrl != null && !imageFailed) {
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onState = { state ->
                    if (state is AsyncImagePainter.State.Error) imageFailed = true
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Image(
                imageVector = IglooIcons.Music,
                contentDescription = null,
                colorFilter = ColorFilter.tint(OVER_MEDIA_TERTIARY),
                modifier = Modifier.size(IglooTheme.icons.lg),
            )
        }
    }
}
