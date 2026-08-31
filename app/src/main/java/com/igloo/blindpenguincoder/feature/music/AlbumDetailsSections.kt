package com.igloo.blindpenguincoder.feature.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.scaled
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.feature.shared.SectionHeading
import com.igloo.blindpenguincoder.feature.shared.readingStopTarget

/**
 * Everything below the album hero: the display-only artists row, the disc-grouped track list,
 * and the facts panel (docs/design-system.md section 11.5.1). All of it sits past the
 * backdrop's fade, on the token canvas, so nothing here carries the section 3.2 over-media
 * treatment. Sections with nothing to show are skipped rather than rendering empty shells.
 *
 * The track rows are focus targets without actions — this pass ships the page's UI before
 * playback, and a row that announced "Play" and did nothing would spend a press teaching the
 * user it is empty. Section 11.5's three-action row arrives with the playback pass.
 */
@Composable
internal fun AlbumDetailsSections(
    album: AlbumDetailsUi,
    trackRequesters: List<FocusRequester>,
    factsRequester: FocusRequester,
    upFromBelow: FocusRequester?,
    /**
     * The overscan inset, applied per section (the movie sections' contract) so a future rail
     * can bleed past it while the prose stays inside it.
     */
    contentInset: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val trackRows = album.discs.flatMap { it.tracks }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.lg),
    ) {
        if (album.artistNames.isNotEmpty()) {
            ArtistsSection(
                artistNames = album.artistNames,
                modifier = Modifier.padding(contentInset),
            )
        }
        TrackListSection(
            album = album,
            trackRequesters = trackRequesters,
            upRequester = upFromBelow,
            downRequester = factsRequester,
            modifier = Modifier.padding(contentInset),
        )
        AlbumFactsSection(
            album = album,
            requester = factsRequester,
            upRequester = trackRequesters.lastOrNull() ?: upFromBelow,
            modifier = Modifier.padding(contentInset),
        )
    }
}

/**
 * Display-only chips (web parity for placement, not behavior): there is no musician screen for
 * these to open, so they are prose, not targets — the album-card rule, where a control that
 * announces an action and does nothing is worse than none. The names also live in the hero
 * reading stop's sentence and the facts panel's Artist row, which is how a screen reader
 * reaches them.
 */
@Composable
private fun ArtistsSection(
    artistNames: List<String>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.clearAndSetSemantics { },
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
    ) {
        SectionHeading(if (artistNames.size == 1) "Artist" else "Artists")
        Row(horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm)) {
            artistNames.forEach { name ->
                AlbumDetailChip(text = name, overMedia = false)
            }
        }
    }
}

@Composable
private fun TrackListSection(
    album: AlbumDetailsUi,
    trackRequesters: List<FocusRequester>,
    upRequester: FocusRequester?,
    downRequester: FocusRequester,
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
                AlbumTrackRow(
                    track = track,
                    requester = trackRequesters[index],
                    upRequester = trackRequesters.getOrNull(index - 1) ?: upRequester,
                    downRequester = trackRequesters.getOrNull(index + 1) ?: downRequester,
                )
            }
        }
    }
}

/**
 * One track: a full-width focus target with no action — index, title, genre line, duration.
 * It wears the focus treatment as a row surface (ring and fill, no scale) rather than a button
 * outline, so focus arriving here does not promise a press; there is deliberately no clickable
 * and no role until playback lands. One cleared node speaks the sentence composed at mapping
 * time.
 */
@Composable
private fun AlbumTrackRow(
    track: AlbumTrackUi,
    requester: FocusRequester,
    upRequester: FocusRequester?,
    downRequester: FocusRequester?,
) {
    val colors = IglooTheme.colors
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .readingStopTarget(
                tag = "album_track_${track.id}",
                focused = focused,
                requester = requester,
                upRequester = upRequester,
                downRequester = downRequester,
                onFocusChanged = { focused = it },
                description = track.contentDescription,
                radius = IglooTheme.radius.lg,
            )
            .padding(horizontal = IglooTheme.spacing.md, vertical = IglooTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
    ) {
        IglooText(
            text = track.indexText,
            style = IglooTheme.typography.label,
            color = colors.mutedForeground,
            maxLines = 1,
            modifier = Modifier.widthIn(min = TRACK_INDEX_MIN_WIDTH.scaled()),
        )
        Column(modifier = Modifier.weight(1f)) {
            IglooText(
                text = track.title,
                style = IglooTheme.typography.bodyMedium,
                color = colors.foreground,
                maxLines = 1,
            )
            if (track.genresLine != null) {
                IglooText(
                    text = track.genresLine,
                    style = IglooTheme.typography.label,
                    color = colors.mutedForeground,
                    maxLines = 1,
                )
            }
        }
        if (track.durationText.isNotEmpty()) {
            IglooText(
                text = track.durationText,
                style = IglooTheme.typography.label,
                color = colors.mutedForeground,
                maxLines = 1,
            )
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

/** Wide enough for a two-digit index without the titles ragged-lefting between rows. */
private val TRACK_INDEX_MIN_WIDTH = 28.dp
