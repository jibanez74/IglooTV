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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooButton
import com.igloo.blindpenguincoder.core.ui.IglooButtonVariant
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.feature.shared.SectionHeading
import com.igloo.blindpenguincoder.feature.shared.TrackRow
import com.igloo.blindpenguincoder.feature.shared.TrackRowColumn
import com.igloo.blindpenguincoder.feature.shared.TrackRowFocus
import com.igloo.blindpenguincoder.feature.shared.TrackRowRequesters
import com.igloo.blindpenguincoder.feature.shared.hasMoreActions
import com.igloo.blindpenguincoder.feature.shared.readingStopTarget

/**
 * Everything below the album hero: the artists row, the disc-grouped track list, and the facts
 * panel (docs/design-system.md section 11.5.1). All of it sits past the backdrop's fade, on the
 * token canvas, so nothing here carries the section 3.2 over-media treatment. Sections with
 * nothing to show are skipped rather than rendering empty shells.
 *
 * The track rows are section 11.5's three-action rows, hand-wired end to end: every row's
 * three controls know the row above and below in their own column, the first row's up is the
 * artist chips when they are actionable or the hero's last-focused action, and the last row's
 * down is the facts panel. [artistRequesters] is empty while the chips are display-only.
 */
@Composable
internal fun AlbumDetailsSections(
    album: AlbumDetailsUi,
    likes: TrackLikesUiState,
    artistRequesters: List<FocusRequester>,
    trackRequesters: List<TrackRowRequesters>,
    factsRequester: FocusRequester,
    upFromBelow: FocusRequester?,
    /** The flat row whose Play launched the player, so closing it lands back on that Play. */
    playReturnRow: Int?,
    playReturnRequester: FocusRequester,
    onOpenMusician: ((Long) -> Unit)?,
    onPlayTrack: (Int) -> Unit,
    onToggleLike: (Long) -> Unit,
    onOpenMore: (Int, Rect) -> Unit,
    /**
     * The overscan inset, applied per section (the movie sections' contract) so a future rail
     * can bleed past it while the prose stays inside it.
     */
    contentInset: PaddingValues,
    modifier: Modifier = Modifier,
) {
    // Up from the rows re-enters the chips on the one last focused, else the hero action.
    var lastFocusedArtist by remember(artistRequesters) { mutableStateOf(artistRequesters.firstOrNull()) }
    val upFromRows = lastFocusedArtist ?: upFromBelow
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.lg),
    ) {
        if (album.artists.isNotEmpty()) {
            ArtistsSection(
                artists = album.artists,
                requesters = artistRequesters,
                upRequester = upFromBelow,
                downRequester = trackRequesters.firstOrNull()?.play ?: factsRequester,
                onArtistFocused = { lastFocusedArtist = it },
                onOpenMusician = onOpenMusician,
                modifier = Modifier.padding(contentInset),
            )
        }
        TrackListSection(
            album = album,
            likes = likes,
            trackRequesters = trackRequesters,
            upRequester = upFromRows,
            downRequester = factsRequester,
            playReturnRow = playReturnRow,
            playReturnRequester = playReturnRequester,
            canOpenArtist = onOpenMusician != null,
            onPlayTrack = onPlayTrack,
            onToggleLike = onToggleLike,
            onOpenMore = onOpenMore,
            modifier = Modifier.padding(contentInset),
        )
        AlbumFactsSection(
            album = album,
            requester = factsRequester,
            upRequester = trackRequesters.lastOrNull()?.play ?: upFromRows,
            modifier = Modifier.padding(contentInset),
        )
    }
}

/**
 * The credited artists. With a musician screen to open — [requesters] is non-empty exactly then
 * — each is a Ghost button that opens it, chained left to right with the row's edges pinned.
 * Otherwise they are display-only chips, prose wearing chip styling: a control that announces
 * an action and does nothing is worse than none, and the names still reach a screen reader
 * through the hero stop's sentence and the facts panel's Artist row.
 */
// Foundation 1.11 still marks FlowRow experimental; using it here keeps wrapping measurement
// inside Compose instead of maintaining a custom layout for one chip group.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ArtistsSection(
    artists: List<AlbumArtistUi>,
    requesters: List<FocusRequester>,
    upRequester: FocusRequester?,
    downRequester: FocusRequester,
    onArtistFocused: (FocusRequester) -> Unit,
    onOpenMusician: ((Long) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val actionable = onOpenMusician != null && requesters.size == artists.size
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("album_artists")
            .then(if (actionable) Modifier else Modifier.clearAndSetSemantics { }),
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
    ) {
        SectionHeading(if (artists.size == 1) "Artist" else "Artists")
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
        ) {
            artists.forEachIndexed { index, artist ->
                if (actionable && onOpenMusician != null) {
                    IglooButton(
                        text = artist.name,
                        onClick = { artist.id?.let(onOpenMusician) },
                        variant = IglooButtonVariant.Ghost,
                        semanticLabel = artist.name,
                        actionLabel = "Open ${artist.name}",
                        modifier = Modifier
                            .testTag("album_artist_${artist.id}")
                            .focusRequester(requesters[index])
                            .focusProperties {
                                up = upRequester ?: Cancel
                                down = downRequester
                                left = requesters.getOrNull(index - 1) ?: Cancel
                                right = requesters.getOrNull(index + 1) ?: Cancel
                            }
                            .onFocusChanged { if (it.isFocused) onArtistFocused(requesters[index]) },
                    )
                } else {
                    AlbumDetailChip(text = artist.name, overMedia = false)
                }
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
    canOpenArtist: Boolean,
    onPlayTrack: (Int) -> Unit,
    onToggleLike: (Long) -> Unit,
    onOpenMore: (Int, Rect) -> Unit,
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
                    onOpenMore = if (track.hasMoreActions(canOpenAlbum = false, canOpenArtist = canOpenArtist)) {
                        { bounds -> onOpenMore(index, bounds) }
                    } else {
                        null
                    },
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
