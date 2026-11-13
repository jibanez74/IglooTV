package com.igloo.blindpenguincoder.feature.music

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusRequester.Companion.Cancel
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.unit.dp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.overMedia
import com.igloo.blindpenguincoder.core.design.scaled
import com.igloo.blindpenguincoder.core.ui.IglooIcons
import com.igloo.blindpenguincoder.core.ui.IglooMediaRail
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.core.ui.rememberSpokenAccessibilityEnabled
import com.igloo.blindpenguincoder.feature.shared.DetailsHero
import com.igloo.blindpenguincoder.feature.shared.DetailsHeroSkeleton
import com.igloo.blindpenguincoder.feature.shared.DetailsState
import com.igloo.blindpenguincoder.feature.shared.FactsSection
import com.igloo.blindpenguincoder.feature.shared.HeroGenresLine
import com.igloo.blindpenguincoder.feature.shared.HeroReadingStop
import com.igloo.blindpenguincoder.feature.shared.HeroTitle
import com.igloo.blindpenguincoder.feature.shared.TrackRowRequesters
import com.igloo.blindpenguincoder.feature.shared.heroHeaderModifier

/**
 * The musician detail screen (docs/design-system.md section 11.5.2): the fourth occupant of
 * the host's one details slot, on the frame [MusicDetailsScaffold] gives every music overlay,
 * with the artist's thumbnail as backdrop and hero, Play all and Shuffle, a discography rail,
 * every track across it as section 11.5's three-action rows, and a facts panel. The screen
 * anchors entry focus on Play all, or on the rail or the facts panel when the artist has no
 * tracks.
 *
 * The hand-wired chain: actions → discography rail → track rows → facts panel, edges pinned
 * (the shell is composed underneath). [onOpenAlbum] replaces this overlay with the album's —
 * the one details slot is single-path — from a rail card or a row's More.
 */
@Composable
fun MusicianDetailsScreen(
    state: DetailsState<MusicianDetailsUi>,
    onRetry: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onPlayTrack: (Int) -> Unit,
    likes: TrackLikesUiState,
    onToggleLike: (Long) -> Unit,
    onOpenAlbum: (Long) -> Unit,
    playReturnRequester: FocusRequester,
    modifier: Modifier = Modifier,
    spokenAccessibilityEnabled: Boolean = rememberSpokenAccessibilityEnabled(),
) {
    val loaded = (state as? DetailsState.Loaded)?.value
    MusicDetailsScaffold(
        stateKey = state::class,
        loaded = loaded,
        errorMessage = (state as? DetailsState.Error)?.message,
        paneTitle = loaded?.name ?: "Artist details",
        tag = "musician_details",
        trackRows = loaded?.tracks.orEmpty(),
        retrySemanticLabel = "Retry loading artist details",
        onRetry = onRetry,
        onGoToAlbum = onOpenAlbum,
        onGoToArtist = null,
        modifier = modifier,
        skeleton = { anchorRequester ->
            DetailsHeroSkeleton(
                artworkAspect = IglooTheme.layout.albumAspect,
                artworkShape = CircleShape,
                anchorWidth = PLAY_ALL_STUB_WIDTH.scaled(),
                loadingLabel = "Loading artist details",
                anchorRequester = anchorRequester,
                trailingStubWidths = listOf(SHUFFLE_STUB_WIDTH.scaled()),
            )
        },
    ) { musician, entryRequester, trackRequesters, onOpenMore ->
        MusicianDetailsContent(
            musician = musician,
            likes = likes,
            spokenAccessibilityEnabled = spokenAccessibilityEnabled,
            entryRequester = entryRequester,
            playReturnRequester = playReturnRequester,
            trackRequesters = trackRequesters,
            onPlayAll = onPlayAll,
            onShuffle = onShuffle,
            onPlayTrack = onPlayTrack,
            onToggleLike = onToggleLike,
            onOpenAlbum = onOpenAlbum,
            onOpenMore = onOpenMore,
        )
    }
}

@Composable
private fun MusicianDetailsContent(
    musician: MusicianDetailsUi,
    likes: TrackLikesUiState,
    spokenAccessibilityEnabled: Boolean,
    entryRequester: FocusRequester,
    playReturnRequester: FocusRequester,
    trackRequesters: List<TrackRowRequesters>,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onPlayTrack: (Int) -> Unit,
    onToggleLike: (Long) -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onOpenMore: (Int, Rect) -> Unit,
) {
    val layout = IglooTheme.layout
    val hasHeroActions = musician.tracks.isNotEmpty()
    val hasAlbums = musician.albums.isNotEmpty()

    val albumsEntryRequester = remember { FocusRequester() }
    val factsStop = remember { FocusRequester() }
    // Entry: under a screen reader the hero's summary stop, as on every details page (section
    // 11.4.1); otherwise Play all; with no tracks, the rail; with neither, the facts panel.
    val hero = rememberMusicHeroFocus(entryRequester, spokenAccessibilityEnabled, hasHeroActions)
    val summaryFirst = hero.summaryFirst
    val albumsEntry =
        if (!summaryFirst && !hasHeroActions && hasAlbums) entryRequester else albumsEntryRequester
    val factsRequester =
        if (!summaryFirst && !hasHeroActions && !hasAlbums) entryRequester else factsStop
    var lastFocusedAlbumId by rememberSaveable { mutableStateOf<Long?>(null) }
    val firstRowPlay = trackRequesters.firstOrNull()?.play
    val belowActions = if (hasAlbums) albumsEntry else firstRowPlay ?: factsRequester
    val upFromRail = hero.upFromBelow
    // Up from the first row re-enters the rail on its remembered card, else the actions.
    val upFromRows = if (hasAlbums) albumsEntry else upFromRail
    val playReturnRow = hero.launchSite.rowIn(musician.tracks)

    MusicDetailsBody(
        notice = likes.notice,
        noticeTag = "musician_notice",
        hero = {
            DetailsHero(imageUrl = musician.thumbUrl, backdropTag = "musician_backdrop") { overMedia ->
                MusicDetailsHeader(
                    focus = hero,
                    artworkUrl = musician.thumbUrl,
                    artworkRadius = IglooTheme.radius.pill,
                    fallbackIcon = IglooIcons.Person,
                    overMedia = overMedia,
                    playReturnRequester = playReturnRequester,
                    downRequester = belowActions,
                    primaryText = "Play all",
                    primarySemanticLabel = "Play all tracks by ${musician.name}",
                    primaryTag = "musician_play_all",
                    shuffleSemanticLabel = "Shuffle all tracks by ${musician.name}",
                    shuffleTag = "musician_shuffle",
                    onPrimary = onPlayAll,
                    onShuffle = onShuffle,
                    modifier = Modifier.heroHeaderModifier(this),
                ) { requester, down ->
                    HeroReadingStop(
                        enabled = summaryFirst,
                        tag = "musician_hero_info",
                        overMedia = overMedia,
                        requester = requester,
                        downRequester = down,
                        description = musician.heroInfoDescription,
                    ) {
                        HeroTitle(musician.name, overMedia)
                        MusicMetadataChips(
                            parts = listOf(
                                musician.albumCountText,
                                musician.trackCountText,
                                musician.totalDurationText,
                            ),
                            overMedia = overMedia,
                        )
                        if (musician.genresLine != null) HeroGenresLine(musician.genresLine, overMedia)
                    }
                }
            }
        },
    ) {
        if (hasAlbums) {
            IglooMediaRail(
                title = "Discography",
                state = IglooRailState.Loaded(musician.albums),
                itemKey = { it.id },
                entryRequester = albumsEntry,
                // The overlay owns the whole screen; there is no spine to exit to.
                leftFocusRequester = Cancel,
                lastFocusedKey = lastFocusedAlbumId,
                onItemFocused = { lastFocusedAlbumId = it },
                contentInset = PaddingValues(horizontal = layout.safeAreaHorizontal),
                cardAspect = layout.albumAspect,
            ) { album, itemModifier, aspect ->
                AlbumCard(
                    album = album,
                    onClick = { onOpenAlbum(album.id) },
                    aspect = aspect,
                    modifier = itemModifier
                        .focusProperties {
                            up = upFromRail ?: Cancel
                            down = firstRowPlay ?: factsRequester
                        }
                        .testTag("musician_album_${album.id}"),
                )
            }
        }

        MusicDetailsTrackList(
            heading = "All Tracks",
            emptyText = "No tracks by this artist",
            isEmpty = musician.tracks.isEmpty(),
            likes = likes,
            trackRequesters = trackRequesters,
            upRequester = upFromRows,
            downRequester = factsRequester,
            playReturnRow = playReturnRow,
            playReturnRequester = playReturnRequester,
            onPlayTrack = { index ->
                hero.launchSite = PlayLaunchSite.Row(index)
                onPlayTrack(index)
            },
            onToggleLike = onToggleLike,
            onOpenMore = onOpenMore,
            canOpenAlbum = true,
            canOpenArtist = false,
            modifier = Modifier.padding(horizontal = layout.safeAreaHorizontal),
        ) { row -> musician.tracks.forEachIndexed { index, track -> row(index, track) } }

        FactsSection(
            heading = "Artist Details",
            tag = "musician_details_facts",
            facts = musician.facts,
            description = musician.factsDescription,
            requester = factsRequester,
            upRequester = trackRequesters.lastOrNull()?.play ?: upFromRows,
            valueMaxLines = 4,
            modifier = Modifier.padding(horizontal = layout.safeAreaHorizontal),
        )
    }
}

/** The Play all button's approximate footprint, so focus taken while loading does not jump. */
private val PLAY_ALL_STUB_WIDTH = 150.dp
