package com.igloo.blindpenguincoder.feature.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusRequester.Companion.Cancel
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.feature.shared.SectionHeading
import com.igloo.blindpenguincoder.feature.shared.TrackRow
import com.igloo.blindpenguincoder.feature.shared.TrackRowColumn
import com.igloo.blindpenguincoder.feature.shared.TrackRowFocus
import com.igloo.blindpenguincoder.feature.shared.TrackRowRequesters
import com.igloo.blindpenguincoder.feature.shared.readingStopTarget

/**
 * Everything below the album hero: the display-only artists row, the disc-grouped track list,
 * and the facts panel (docs/design-system.md section 11.5.1). All of it sits past the
 * backdrop's fade, on the token canvas, so nothing here carries the section 3.2 over-media
 * treatment. Sections with nothing to show are skipped rather than rendering empty shells.
 *
 * The track rows are section 11.5's three-action rows, hand-wired end to end: every row's
 * three controls know the row above and below in their own column, the first row's up is the
 * hero's last-focused action, and the last row's down is the facts panel.
 */
@Composable
internal fun AlbumDetailsSections(
    album: AlbumDetailsUi,
    likes: TrackLikesUiState,
    trackRequesters: List<TrackRowRequesters>,
    factsRequester: FocusRequester,
    upFromBelow: FocusRequester?,
    /** The flat row whose Play launched the player, so closing it lands back on that Play. */
    playReturnRow: Int?,
    playReturnRequester: FocusRequester,
    onPlayTrack: (Int) -> Unit,
    onToggleLike: (Long) -> Unit,
    /**
     * The overscan inset, applied per section (the movie sections' contract) so a future rail
     * can bleed past it while the prose stays inside it.
     */
    contentInset: PaddingValues,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.lg),
    ) {
        if (album.artists.isNotEmpty()) {
            ArtistsSection(
                artistNames = album.artistNames,
                modifier = Modifier.padding(contentInset),
            )
        }
        TrackListSection(
            album = album,
            likes = likes,
            trackRequesters = trackRequesters,
            upRequester = upFromBelow,
            downRequester = factsRequester,
            playReturnRow = playReturnRow,
            playReturnRequester = playReturnRequester,
            onPlayTrack = onPlayTrack,
            onToggleLike = onToggleLike,
            modifier = Modifier.padding(contentInset),
        )
        AlbumFactsSection(
            album = album,
            requester = factsRequester,
            upRequester = trackRequesters.lastOrNull()?.play ?: upFromBelow,
            modifier = Modifier.padding(contentInset),
        )
    }
}

/**
 * Display-only chips (web parity for placement, not behavior): there is no musician screen for
 * these to open, so they are prose, not targets — the album-card rule, where a control that
 * announces an action and does nothing is worse than none. The names also live in the hero
 * reading stop's sentence and the facts panel's Artist row, which is how a screen reader
 * reaches them. The flow stays inside the safe content width and grows with every credit.
 */
// Foundation 1.11 still marks FlowRow experimental; using it here keeps wrapping measurement
// inside Compose instead of maintaining a custom layout for one display-only chip group.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ArtistsSection(
    artistNames: List<String>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("album_artists")
            .clearAndSetSemantics { },
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
    ) {
        SectionHeading(if (artistNames.size == 1) "Artist" else "Artists")
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
        ) {
            artistNames.forEach { name ->
                AlbumDetailChip(text = name, overMedia = false)
            }
        }
    }
}

@Composable
private fun TrackListSection(
    album: AlbumDetailsUi,
    likes: TrackLikesUiState,
    trackRequesters: List<TrackRowRequesters>,
    upRequester: FocusRequester?,
    downRequester: FocusRequester,
    playReturnRow: Int?,
    playReturnRequester: FocusRequester,
    onPlayTrack: (Int) -> Unit,
    onToggleLike: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = IglooTheme.colors
    var rowIndex = 0
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
    ) {
        SectionHeading("Track List")
        if (album.discs.isEmpty()) {
            // Web parity: an album whose scan produced no tracks says so rather than rendering
            // a bare heading. Plain prose — the facts panel below is the section's focus stop.
            IglooText(
                text = "No tracks in this album",
                style = IglooTheme.typography.bodyMedium,
                color = colors.mutedForeground,
                modifier = Modifier.padding(vertical = IglooTheme.spacing.sm),
            )
        }
        album.discs.forEach { disc ->
            if (album.hasMultipleDiscs) {
                // Plain text a TV screen reader never reaches; the disc is folded into its
                // first row's spoken sentence at mapping time.
                IglooText(
                    text = "Disc ${disc.disc}",
                    style = IglooTheme.typography.label,
                    color = colors.mutedForeground,
                    modifier = Modifier
                        .padding(top = IglooTheme.spacing.sm)
                        .clearAndSetSemantics { },
                )
            }
            disc.tracks.forEach { track ->
                val index = rowIndex++
                val focus = remember(trackRequesters, index, upRequester, downRequester, playReturnRow) {
                    TrackRowFocus(
                        requesters = trackRequesters[index],
                        // A plain column composes every row, so each edge is wired outright
                        // rather than left to a spatial search that could reach the shell.
                        up = { column -> trackRequesters.getOrNull(index - 1)?.get(column) ?: upRequester ?: Cancel },
                        down = { column -> trackRequesters.getOrNull(index + 1)?.get(column) ?: downRequester },
                        left = Cancel,
                        riders = { column ->
                            listOfNotNull(
                                playReturnRequester.takeIf { column == TrackRowColumn.Play && playReturnRow == index },
                            )
                        },
                    )
                }
                TrackRow(
                    track = track,
                    liked = likes.isLiked(track.id),
                    likePending = track.id in likes.pendingIds,
                    focus = focus,
                    onPlay = { onPlayTrack(index) },
                    onToggleLike = { onToggleLike(track.id) },
                    // Inert until a musician screen exists for "Go to artist" to open.
                    onOpenMore = null,
                )
            }
        }
    }
}

/**
 * The fine print, on the movie About panel's exact treatment: heading outside the focusable
 * panel, one focus stop, one cleared announcement with the heading folded in. Reachable but not
 * actionable — content a d-pad can never scroll to may as well not be on the page.
 */
@Composable
private fun AlbumFactsSection(
    album: AlbumDetailsUi,
    requester: FocusRequester,
    upRequester: FocusRequester?,
    modifier: Modifier = Modifier,
) {
    val colors = IglooTheme.colors
    var focused by remember { mutableStateOf(false) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm)) {
        SectionHeading("Album Details")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .readingStopTarget(
                    tag = "album_details_facts",
                    focused = focused,
                    requester = requester,
                    upRequester = upRequester,
                    downRequester = null,
                    onFocusChanged = { focused = it },
                    description = album.factsDescription,
                )
                .padding(IglooTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.xs),
        ) {
            album.facts.forEach { fact ->
                Row(horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm)) {
                    IglooText(
                        text = "${fact.label}:",
                        style = IglooTheme.typography.label,
                        color = colors.mutedForeground,
                        maxLines = 1,
                    )
                    IglooText(
                        text = fact.value,
                        style = IglooTheme.typography.bodyMedium,
                        color = colors.foreground,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}
