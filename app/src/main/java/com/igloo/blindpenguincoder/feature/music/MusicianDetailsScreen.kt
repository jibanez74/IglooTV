package com.igloo.blindpenguincoder.feature.music

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.igloo.blindpenguincoder.core.design.IglooMotion
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.iglooTween
import com.igloo.blindpenguincoder.core.design.overMedia
import com.igloo.blindpenguincoder.core.design.scaled
import com.igloo.blindpenguincoder.core.ui.IglooButton
import com.igloo.blindpenguincoder.core.ui.IglooButtonVariant
import com.igloo.blindpenguincoder.core.ui.IglooIcons
import com.igloo.blindpenguincoder.core.ui.IglooInlineError
import com.igloo.blindpenguincoder.core.ui.IglooMediaRail
import com.igloo.blindpenguincoder.core.ui.IglooNotice
import com.igloo.blindpenguincoder.core.ui.IglooPosterCard
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.focusRing
import com.igloo.blindpenguincoder.core.ui.iglooEnterStagger
import com.igloo.blindpenguincoder.core.ui.iglooSurface
import com.igloo.blindpenguincoder.core.ui.pinnedToScreen
import com.igloo.blindpenguincoder.core.ui.rememberSpokenAccessibilityEnabled
import com.igloo.blindpenguincoder.core.ui.requestFocusSafely
import com.igloo.blindpenguincoder.core.ui.withRequester
import com.igloo.blindpenguincoder.feature.shared.SectionHeading
import com.igloo.blindpenguincoder.feature.shared.TrackRow
import com.igloo.blindpenguincoder.feature.shared.TrackRowColumn
import com.igloo.blindpenguincoder.feature.shared.TrackRowFocus
import com.igloo.blindpenguincoder.feature.shared.TrackRowMenu
import com.igloo.blindpenguincoder.feature.shared.TrackRowRequesters
import com.igloo.blindpenguincoder.feature.shared.hasMoreActions
import com.igloo.blindpenguincoder.feature.shared.readingStopTarget

/**
 * The musician detail screen (docs/design-system.md section 11.5.2): the album overlay's shape
 * — full-screen, opaque, the fourth occupant of the host's one details slot — with the artist's
 * thumbnail as backdrop and hero, Play all and Shuffle, a discography rail, every track across
 * it as section 11.5's three-action rows, and a facts panel. Back and focus restore belong to
 * the host; the screen anchors entry focus on Play all, or on the rail or the facts panel when
 * the artist has no tracks.
 *
 * The hand-wired chain: actions → discography rail → track rows → facts panel, edges pinned
 * (the shell is composed underneath). [onOpenAlbum] replaces this overlay with the album's —
 * the one details slot is single-path — from a rail card or a row's More.
 */
@Composable
fun MusicianDetailsScreen(
    state: MusicianDetailsState,
    onRetry: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onPlayTrack: (Int) -> Unit,
    likes: TrackLikesUiState,
    onToggleLike: (Long) -> Unit,
    notice: String?,
    onOpenAlbum: (Long) -> Unit,
    playReturnRequester: FocusRequester,
    modifier: Modifier = Modifier,
    spokenAccessibilityEnabled: Boolean = rememberSpokenAccessibilityEnabled(),
) {
    val colors = IglooTheme.colors
    val entryRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { entryRequester.requestFocusSafely() }

    var screenHasFocus by remember { mutableStateOf(false) }
    val hadFocusAtSwap = remember(state::class) { screenHasFocus }
    LaunchedEffect(state::class) {
        if (hadFocusAtSwap) entryRequester.requestFocusSafely()
    }
    var trackMenu by remember(state::class) { mutableStateOf<Pair<Int, Rect>?>(null) }
    val trackRequesters = remember(state) {
        val count = (state as? MusicianDetailsState.Loaded)?.musician?.tracks?.size ?: 0
        List(count) { TrackRowRequesters() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .onFocusChanged { screenHasFocus = it.hasFocus }
            .semantics {
                paneTitle = (state as? MusicianDetailsState.Loaded)?.musician?.name ?: "Artist details"
                isTraversalGroup = true
            }
            .testTag("musician_details"),
    ) {
        // The menu is a small anchored card that occludes nothing, so the body leaves the
        // semantics tree while it is up (the movie details rule); the tag stays outside.
        Box(
            modifier = Modifier
                .testTag("musician_details_body")
                .then(if (trackMenu != null) Modifier.clearAndSetSemantics { } else Modifier),
        ) {
            when (state) {
                MusicianDetailsState.Loading -> MusicianDetailsSkeleton(anchorRequester = entryRequester)

                is MusicianDetailsState.Error -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(IglooTheme.layout.safeAreaHorizontal),
                    contentAlignment = Alignment.Center,
                ) {
                    IglooInlineError(
                        message = state.message,
                        actionText = "Retry",
                        actionSemanticLabel = "Retry loading artist details",
                        onAction = onRetry,
                        actionModifier = Modifier
                            .focusRequester(entryRequester)
                            .pinnedToScreen(),
                        modifier = Modifier.width(IglooTheme.layout.dialogWidth),
                    )
                }

                is MusicianDetailsState.Loaded -> MusicianDetailsContent(
                    musician = state.musician,
                    likes = likes,
                    notice = notice,
                    spokenAccessibilityEnabled = spokenAccessibilityEnabled,
                    entryRequester = entryRequester,
                    playReturnRequester = playReturnRequester,
                    trackRequesters = trackRequesters,
                    onPlayAll = onPlayAll,
                    onShuffle = onShuffle,
                    onPlayTrack = onPlayTrack,
                    onToggleLike = onToggleLike,
                    onOpenAlbum = onOpenAlbum,
                    onOpenMore = { index, bounds -> trackMenu = index to bounds },
                )
            }
        }

        val loaded = state as? MusicianDetailsState.Loaded
        trackMenu?.let { (index, bounds) ->
            val track = loaded?.musician?.tracks?.getOrNull(index)
            if (track != null) {
                TrackRowMenu(
                    track = track,
                    anchorBounds = bounds,
                    onGoToAlbum = onOpenAlbum,
                    onGoToArtist = null,
                    onDismiss = {
                        trackMenu = null
                        trackRequesters.getOrNull(index)?.more?.requestFocusSafely()
                    },
                )
            }
        }
    }
}

@Composable
private fun MusicianDetailsContent(
    musician: MusicianDetailsUi,
    likes: TrackLikesUiState,
    notice: String?,
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
    val colors = IglooTheme.colors
    val layout = IglooTheme.layout
    var imageFailed by remember(musician.thumbUrl) { mutableStateOf(false) }
    var imageLoaded by remember(musician.thumbUrl) { mutableStateOf(false) }
    val showBackdrop = musician.thumbUrl != null && !imageFailed
    val overMedia = imageLoaded

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

    // Which control launched the player, so its close lands back on it (the album page's rule).
    var playLaunchSite by rememberSaveable { mutableStateOf(PLAY_ALL_SITE) }
    val playReturnRow = playLaunchSite.removePrefix(ROW_SITE_PREFIX).toIntOrNull()
        ?.takeIf { playLaunchSite.startsWith(ROW_SITE_PREFIX) && it in musician.tracks.indices }

    val backdropAlpha by animateFloatAsState(
        targetValue = if (imageLoaded) 1f else 0f,
        animationSpec = iglooTween(IglooMotion.PAGE_MS),
        label = "musicianBackdrop",
    )
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = HERO_MIN_HEIGHT.scaled()),
        ) {
            if (showBackdrop) {
                AsyncImage(
                    model = musician.thumbUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onState = { state ->
                        when (state) {
                            is AsyncImagePainter.State.Success -> imageLoaded = true
                            is AsyncImagePainter.State.Error -> imageFailed = true
                            else -> Unit
                        }
                    },
                    modifier = Modifier
                        .testTag("musician_backdrop")
                        .matchParentSize()
                        .graphicsLayer { alpha = backdropAlpha },
                )
                Box(
                    modifier = Modifier
                        .then(if (overMedia) Modifier.testTag("musician_backdrop_scrim") else Modifier)
                        .matchParentSize()
                        .graphicsLayer { alpha = backdropAlpha }
                        .background(
                            Brush.horizontalGradient(
                                0f to Color.Black.copy(alpha = 0.80f),
                                0.65f to Color.Black.copy(alpha = 0.55f),
                                1f to Color.Black.copy(alpha = 0f),
                            ),
                        ),
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                0.45f to colors.background.copy(alpha = 0f),
                                1f to colors.background,
                            ),
                        ),
                )
            }

            MusicianHeader(
                musician = musician,
                overMedia = overMedia,
                hasHeroActions = hasHeroActions,
                spokenAccessibilityEnabled = spokenAccessibilityEnabled,
                heroInfoRequester = heroInfoRequester,
                primaryRequester = entryRequester,
                playReturnRequester = playReturnRequester.takeIf { playLaunchSite == PLAY_ALL_SITE },
                shuffleRequester = shuffleRequester,
                shuffleReturnRequester = playReturnRequester.takeIf { playLaunchSite == SHUFFLE_SITE },
                downRequester = belowActions,
                onActionFocused = { lastFocusedAction = it },
                onPlayAll = {
                    playLaunchSite = PLAY_ALL_SITE
                    onPlayAll()
                },
                onShuffle = {
                    playLaunchSite = SHUFFLE_SITE
                    onShuffle()
                },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(
                        start = layout.safeAreaHorizontal,
                        end = layout.safeAreaHorizontal,
                        top = layout.safeAreaVertical,
                        bottom = IglooTheme.spacing.lg,
                    ),
            )
        }

        if (notice != null) {
            IglooNotice(
                text = notice,
                modifier = Modifier
                    .padding(horizontal = layout.safeAreaHorizontal)
                    .padding(bottom = IglooTheme.spacing.lg)
                    .testTag("musician_notice"),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = layout.safeAreaVertical)
                .iglooEnterStagger(entered = entered, index = 0),
            verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.lg),
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
                        subtitle = album.musician,
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
                    playLaunchSite = ROW_SITE_PREFIX + index
                    onPlayTrack(index)
                },
                onToggleLike = onToggleLike,
                onOpenMore = onOpenMore,
                modifier = Modifier.padding(horizontal = layout.safeAreaHorizontal),
            )

            MusicianFactsSection(
                musician = musician,
                requester = factsRequester,
                upRequester = trackRequesters.lastOrNull()?.play ?: (if (hasAlbums) albumsEntry else upFromRail),
                modifier = Modifier.padding(horizontal = layout.safeAreaHorizontal),
            )
        }
    }
}

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
    var heroFocused by remember { mutableStateOf(false) }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.xl),
        verticalAlignment = Alignment.Bottom,
    ) {
        HeaderThumb(thumbUrl = musician.thumbUrl)

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
        ) {
            Column(
                modifier = Modifier.then(
                    if (spokenAccessibilityEnabled) {
                        Modifier
                            .testTag("musician_hero_info")
                            .focusRing(
                                focused = heroFocused,
                                radius = IglooTheme.radius.lg,
                                fill = when {
                                    !heroFocused -> Color.Transparent
                                    overMedia -> Color.Black.copy(alpha = 0.45f)
                                    else -> colors.card.copy(alpha = 0.72f)
                                },
                                scaleOnFocus = false,
                            )
                            .focusRequester(heroInfoRequester)
                            .focusProperties {
                                up = Cancel
                                left = Cancel
                                right = Cancel
                                down = primaryRequester
                            }
                            .onFocusChanged { heroFocused = it.isFocused }
                            .focusable()
                            .clearAndSetSemantics { contentDescription = musician.heroInfoDescription }
                    } else {
                        Modifier
                    },
                ),
                verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
            ) {
                IglooText(
                    text = musician.name,
                    style = IglooTheme.typography.titleLarge.overMedia(overMedia),
                    color = if (overMedia) Color.White else colors.foreground,
                    maxLines = 2,
                    modifier = Modifier.semantics { heading() },
                )
                val parts = listOf(musician.albumCountText, musician.trackCountText, musician.totalDurationText)
                Row(
                    modifier = Modifier.clearAndSetSemantics { contentDescription = parts.joinToString(", ") },
                    horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    parts.forEach { AlbumDetailChip(text = it, overMedia = overMedia) }
                }
                if (musician.genresLine != null) {
                    IglooText(
                        text = musician.genresLine,
                        style = IglooTheme.typography.label.overMedia(overMedia),
                        color = if (overMedia) Color.White.copy(alpha = 0.75f) else colors.mutedForeground,
                        maxLines = 1,
                    )
                }
                if (musician.popularity != null) {
                    SpotifyPopularityMeter(score = musician.popularity, overMedia = overMedia)
                }
            }
            if (hasHeroActions) {
                val ghostFill = if (overMedia) Color.Black.copy(alpha = 0.45f) else null
                val ghostContent = if (overMedia) Color.White else null
                var rowHasFocus by remember { mutableStateOf(false) }
                var playFocused by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .padding(top = IglooTheme.spacing.sm)
                        .onFocusChanged { rowHasFocus = it.hasFocus },
                    horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
                ) {
                    IglooButton(
                        text = "Play all",
                        onClick = onPlayAll,
                        icon = IglooIcons.Play,
                        semanticLabel = "Play all tracks by ${musician.name}",
                        recessed = rowHasFocus && !playFocused,
                        modifier = Modifier
                            .testTag("musician_play_all")
                            .focusRequester(primaryRequester)
                            .withRequester(playReturnRequester)
                            .focusProperties {
                                up = actionUpRequester
                                down = downRequester
                                left = Cancel
                                right = shuffleRequester
                            }
                            .onFocusChanged {
                                playFocused = it.isFocused
                                if (it.isFocused) onActionFocused(primaryRequester)
                            },
                    )
                    IglooButton(
                        text = "Shuffle",
                        onClick = onShuffle,
                        variant = IglooButtonVariant.Ghost,
                        icon = IglooIcons.Shuffle,
                        restingFill = ghostFill,
                        contentColor = ghostContent,
                        semanticLabel = "Shuffle all tracks by ${musician.name}",
                        modifier = Modifier
                            .testTag("musician_shuffle")
                            .focusRequester(shuffleRequester)
                            .withRequester(shuffleReturnRequester)
                            .focusProperties {
                                up = actionUpRequester
                                down = downRequester
                                right = Cancel
                            }
                            .onFocusChanged { if (it.isFocused) onActionFocused(shuffleRequester) },
                    )
                }
            }
        }
    }
}

/** Decorative — the thumbnail repeats nothing the text does not say, so TalkBack skips it. */
@Composable
private fun HeaderThumb(thumbUrl: String?) {
    val colors = IglooTheme.colors
    var imageFailed by remember(thumbUrl) { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .width(IglooTheme.layout.posterWidth)
            .aspectRatio(IglooTheme.layout.albumAspect)
            .iglooSurface(radius = IglooTheme.radius.pill, fill = colors.muted),
        contentAlignment = Alignment.Center,
    ) {
        if (thumbUrl != null && !imageFailed) {
            AsyncImage(
                model = thumbUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onState = { state -> if (state is AsyncImagePainter.State.Error) imageFailed = true },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Image(
                imageVector = IglooIcons.Person,
                contentDescription = null,
                colorFilter = ColorFilter.tint(colors.mutedForeground),
                modifier = Modifier.size(IglooTheme.icons.lg),
            )
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
            val focus = remember(trackRequesters, index, upRequester, downRequester, playReturnRow) {
                TrackRowFocus(
                    requesters = trackRequesters[index],
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
                onOpenMore = if (track.hasMoreActions(canOpenAlbum = true, canOpenArtist = false)) {
                    { bounds -> onOpenMore(index, bounds) }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun MusicianFactsSection(
    musician: MusicianDetailsUi,
    requester: FocusRequester,
    upRequester: FocusRequester?,
    modifier: Modifier = Modifier,
) {
    val colors = IglooTheme.colors
    var focused by remember { mutableStateOf(false) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm)) {
        SectionHeading("Artist Details")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .readingStopTarget(
                    tag = "musician_details_facts",
                    focused = focused,
                    requester = requester,
                    upRequester = upRequester,
                    downRequester = null,
                    onFocusChanged = { focused = it },
                    description = musician.factsDescription,
                )
                .padding(IglooTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.xs),
        ) {
            musician.facts.forEach { fact ->
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
                        maxLines = 4,
                    )
                }
            }
        }
    }
}

/**
 * Static geometry-matched stand-ins (section 10): the circular thumb and text stubs where the
 * hero lands, and a two-slot action row whose first slot is the screen's one focusable anchor.
 */
@Composable
private fun MusicianDetailsSkeleton(anchorRequester: FocusRequester) {
    val colors = IglooTheme.colors
    val layout = IglooTheme.layout
    val stubShape = RoundedCornerShape(IglooTheme.radius.sm)
    var focused by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HERO_MIN_HEIGHT.scaled()),
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(
                    start = layout.safeAreaHorizontal,
                    end = layout.safeAreaHorizontal,
                    top = layout.safeAreaVertical,
                    bottom = IglooTheme.spacing.lg,
                ),
            horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.xl),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                modifier = Modifier
                    .width(layout.posterWidth)
                    .aspectRatio(layout.albumAspect)
                    .background(colors.muted, CircleShape),
            )
            Column(verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md)) {
                Box(
                    modifier = Modifier
                        .width(320.dp.scaled())
                        .heightIn(min = 30.dp.scaled())
                        .background(colors.muted, stubShape),
                )
                Box(
                    modifier = Modifier
                        .width(220.dp.scaled())
                        .heightIn(min = 16.dp.scaled())
                        .background(colors.muted, stubShape),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md)) {
                    Box(
                        modifier = Modifier
                            .width(PLAY_ALL_STUB_WIDTH.scaled())
                            .heightIn(min = IglooTheme.sizes.controlHeight)
                            .focusRing(focused = focused, radius = IglooTheme.radius.lg, fill = colors.muted)
                            .focusRequester(anchorRequester)
                            .pinnedToScreen()
                            .onFocusChanged { focused = it.isFocused }
                            .focusable()
                            .clearAndSetSemantics {
                                contentDescription = "Loading artist details"
                                liveRegion = LiveRegionMode.Polite
                            },
                    )
                    Box(
                        modifier = Modifier
                            .width(SHUFFLE_STUB_WIDTH.scaled())
                            .heightIn(min = IglooTheme.sizes.controlHeight)
                            .background(colors.muted, RoundedCornerShape(IglooTheme.radius.lg)),
                    )
                }
            }
        }
    }
}

private val HERO_MIN_HEIGHT = 320.dp
private val PLAY_ALL_STUB_WIDTH = 150.dp
private val SHUFFLE_STUB_WIDTH = 128.dp

private const val PLAY_ALL_SITE = "play"
private const val SHUFFLE_SITE = "shuffle"
private const val ROW_SITE_PREFIX = "row:"
