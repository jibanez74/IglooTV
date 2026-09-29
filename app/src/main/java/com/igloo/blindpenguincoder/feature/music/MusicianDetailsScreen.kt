package com.igloo.blindpenguincoder.feature.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
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
import com.igloo.blindpenguincoder.core.ui.IglooPosterCard
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.SectionHeading
import com.igloo.blindpenguincoder.core.ui.rememberSpokenAccessibilityEnabled
import com.igloo.blindpenguincoder.feature.shared.DetailsHero
import com.igloo.blindpenguincoder.feature.shared.DetailsHeroSkeleton
import com.igloo.blindpenguincoder.feature.shared.DetailsState
import com.igloo.blindpenguincoder.feature.shared.FactsSection
import com.igloo.blindpenguincoder.feature.shared.HeroArtwork
import com.igloo.blindpenguincoder.feature.shared.HeroGenresLine
import com.igloo.blindpenguincoder.feature.shared.HeroReadingStop
import com.igloo.blindpenguincoder.feature.shared.HeroTitle
import com.igloo.blindpenguincoder.feature.shared.TrackRow
import com.igloo.blindpenguincoder.feature.shared.TrackRowRequesters
import com.igloo.blindpenguincoder.feature.shared.hasMoreActions
import com.igloo.blindpenguincoder.feature.shared.heroHeaderModifier
import com.igloo.blindpenguincoder.feature.shared.rememberPlainColumnTrackRowFocus

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

    val heroInfoRequester = remember { FocusRequester() }
    val shuffleRequester = remember { FocusRequester() }
    val albumsEntryRequester = remember { FocusRequester() }
    val factsStop = remember { FocusRequester() }
    // Entry: Play all; with no tracks, the rail; with neither, the facts panel.
    val albumsEntry = if (!hasHeroActions && hasAlbums) entryRequester else albumsEntryRequester
    val factsRequester = if (!hasHeroActions && !hasAlbums) entryRequester else factsStop
    var lastFocusedAction by remember(hasHeroActions) {
        mutableStateOf(entryRequester.takeIf { hasHeroActions })
    }
    var lastFocusedAlbumId by rememberSaveable { mutableStateOf<Long?>(null) }
    val firstRowPlay = trackRequesters.firstOrNull()?.play
    val belowActions = if (hasAlbums) albumsEntry else firstRowPlay ?: factsRequester
    val upFromRail = when {
        hasHeroActions -> lastFocusedAction
        spokenAccessibilityEnabled -> heroInfoRequester
        else -> null
    }
    // Up from the first row re-enters the rail on its remembered card, else the actions.
    val upFromRows = if (hasAlbums) albumsEntry else upFromRail
    var playLaunchSite by rememberPlayLaunchSite()
    val playReturnRow = playLaunchSite.rowIn(musician.tracks)

    MusicDetailsBody(
        notice = likes.notice,
        noticeTag = "musician_notice",
        hero = {
            DetailsHero(imageUrl = musician.thumbUrl, backdropTag = "musician_backdrop") { overMedia ->
                MusicianHeader(
                    musician = musician,
                    overMedia = overMedia,
                    hasHeroActions = hasHeroActions,
                    spokenAccessibilityEnabled = spokenAccessibilityEnabled,
                    heroInfoRequester = heroInfoRequester,
                    primaryRequester = entryRequester,
                    playReturnRequester = playReturnRequester.takeIf { playLaunchSite == PlayLaunchSite.Primary },
                    shuffleRequester = shuffleRequester,
                    shuffleReturnRequester = playReturnRequester.takeIf { playLaunchSite == PlayLaunchSite.Shuffle },
                    downRequester = belowActions,
                    onActionFocused = { lastFocusedAction = it },
                    onPlayAll = {
                        playLaunchSite = PlayLaunchSite.Primary
                        onPlayAll()
                    },
                    onShuffle = {
                        playLaunchSite = PlayLaunchSite.Shuffle
                        onShuffle()
                    },
                    modifier = heroHeaderModifier(),
                )
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
                IglooPosterCard(
                    title = album.title,
                    subtitle = album.subtitle,
                    imageUrl = album.coverUrl,
                    onClick = { onOpenAlbum(album.id) },
                    aspect = aspect,
                    fallbackIcon = IglooIcons.Music,
                    modifier = itemModifier
                        .focusProperties {
                            up = upFromRail ?: Cancel
                            down = firstRowPlay ?: factsRequester
                        }
                        .testTag("musician_album_${album.id}"),
                )
            }
        }

        MusicianTrackList(
            musician = musician,
            likes = likes,
            trackRequesters = trackRequesters,
            upRequester = upFromRows,
            downRequester = factsRequester,
            playReturnRow = playReturnRow,
            playReturnRequester = playReturnRequester,
            onPlayTrack = { index ->
                playLaunchSite = PlayLaunchSite.Row(index)
                onPlayTrack(index)
            },
            onToggleLike = onToggleLike,
            onOpenMore = onOpenMore,
            modifier = Modifier.padding(horizontal = layout.safeAreaHorizontal),
        )

        FactsSection(
            heading = "Artist Details",
            tag = "musician_details_facts",
            facts = musician.facts,
            description = musician.factsDescription,
            requester = factsRequester,
            upRequester = trackRequesters.lastOrNull()?.play ?: (if (hasAlbums) albumsEntry else upFromRail),
            valueMaxLines = 4,
            modifier = Modifier.padding(horizontal = layout.safeAreaHorizontal),
        )
    }
}

/**
 * The musician hero's content block: circular thumb left; name, count chips, genres, the
 * Spotify popularity meter, and the action row right. The section 3.2 over-media treatment is
 * gated on [overMedia], exactly like the album hero.
 */
@Composable
private fun MusicianHeader(
    musician: MusicianDetailsUi,
    overMedia: Boolean,
    hasHeroActions: Boolean,
    spokenAccessibilityEnabled: Boolean,
    heroInfoRequester: FocusRequester,
    primaryRequester: FocusRequester,
    playReturnRequester: FocusRequester?,
    shuffleRequester: FocusRequester,
    shuffleReturnRequester: FocusRequester?,
    downRequester: FocusRequester,
    onActionFocused: (FocusRequester) -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = IglooTheme.colors
    val actionUpRequester = if (spokenAccessibilityEnabled) heroInfoRequester else Cancel

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.xl),
        verticalAlignment = Alignment.Bottom,
    ) {
        HeroArtwork(
            imageUrl = musician.thumbUrl,
            aspect = IglooTheme.layout.albumAspect,
            radius = IglooTheme.radius.pill,
            fallbackIcon = IglooIcons.Person,
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
        ) {
            HeroReadingStop(
                enabled = spokenAccessibilityEnabled,
                tag = "musician_hero_info",
                overMedia = overMedia,
                requester = heroInfoRequester,
                downRequester = primaryRequester,
                description = musician.heroInfoDescription,
            ) {
                HeroTitle(musician.name, overMedia)
                MusicMetadataChips(
                    parts = listOf(musician.albumCountText, musician.trackCountText, musician.totalDurationText),
                    overMedia = overMedia,
                )
                if (musician.genresLine != null) HeroGenresLine(musician.genresLine, overMedia)
                if (musician.popularity != null) {
                    SpotifyPopularityMeter(score = musician.popularity, overMedia = overMedia)
                }
            }
            if (hasHeroActions) {
                MusicHeroActionRow(
                    primaryText = "Play all",
                    primarySemanticLabel = "Play all tracks by ${musician.name}",
                    primaryTag = "musician_play_all",
                    shuffleSemanticLabel = "Shuffle all tracks by ${musician.name}",
                    shuffleTag = "musician_shuffle",
                    overMedia = overMedia,
                    primaryRequester = primaryRequester,
                    primaryReturnRequester = playReturnRequester,
                    shuffleRequester = shuffleRequester,
                    shuffleReturnRequester = shuffleReturnRequester,
                    upRequester = actionUpRequester,
                    downRequester = downRequester,
                    onActionFocused = onActionFocused,
                    onPrimary = onPlayAll,
                    onShuffle = onShuffle,
                    modifier = Modifier.padding(top = IglooTheme.spacing.sm),
                )
            }
        }
    }
}

@Composable
private fun MusicianTrackList(
    musician: MusicianDetailsUi,
    likes: TrackLikesUiState,
    trackRequesters: List<TrackRowRequesters>,
    upRequester: FocusRequester?,
    downRequester: FocusRequester,
    playReturnRow: Int?,
    playReturnRequester: FocusRequester,
    onPlayTrack: (Int) -> Unit,
    onToggleLike: (Long) -> Unit,
    onOpenMore: (Int, Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = IglooTheme.colors
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
    ) {
        SectionHeading("All Tracks")
        if (musician.tracks.isEmpty()) {
            IglooText(
                text = "No tracks by this artist",
                style = IglooTheme.typography.bodyMedium,
                color = colors.mutedForeground,
                modifier = Modifier.padding(vertical = IglooTheme.spacing.sm),
            )
        }
        musician.tracks.forEachIndexed { index, track ->
            val focus = rememberPlainColumnTrackRowFocus(
                requesters = trackRequesters,
                index = index,
                upRequester = upRequester,
                downRequester = downRequester,
                playReturnRow = playReturnRow,
                playReturnRequester = playReturnRequester,
            )
            TrackRow(
                track = track,
                liked = likes.isLiked(track.id),
                likePending = track.id in likes.pendingIds,
                focus = focus,
                onPlay = { onPlayTrack(index) },
                onToggleLike = { onToggleLike(track.id) },
                onOpenMore = if (track.hasMoreActions(canOpenAlbum = true, canOpenArtist = false)) {
                    { bounds -> onOpenMore(index, bounds) }
                } else {
                    null
                },
            )
        }
    }
}

/** The Play all button's approximate footprint, so focus taken while loading does not jump. */
private val PLAY_ALL_STUB_WIDTH = 150.dp
