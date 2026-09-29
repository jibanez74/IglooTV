package com.igloo.blindpenguincoder.feature.music

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.scaled
import com.igloo.blindpenguincoder.core.ui.rememberSpokenAccessibilityEnabled
import com.igloo.blindpenguincoder.feature.shared.DetailsHero
import com.igloo.blindpenguincoder.feature.shared.DetailsHeroSkeleton
import com.igloo.blindpenguincoder.feature.shared.DetailsState
import com.igloo.blindpenguincoder.feature.shared.TrackRowRequesters
import com.igloo.blindpenguincoder.feature.shared.heroHeaderModifier

/**
 * The album detail screen (docs/design-system.md section 11.5.1): the third occupant of the
 * host's one details slot, on the frame [MusicDetailsScaffold] gives every music overlay. The
 * screen anchors entry focus on the hero's primary action — Play Album — or on the facts panel
 * for an album with no tracks, where the action row is not composed at all (web parity, and the
 * inert-control rule). The backdrop is the album cover blown up full-bleed (the web page's
 * treatment): there is no separate backdrop asset for music, and the cover URL is used verbatim.
 *
 * [onPlayAlbum], [onShuffle] and a row's [onPlayTrack] open the host's music player overlay on
 * the album's queue — in order, freshly shuffled, or in order from that row; [playReturnRequester]
 * is parked on whichever of those controls launched it so closing that player restores focus
 * there (section 6.3), the movie details screen's pairing. [likes] and [onToggleLike] are the
 * shared like state every track row reads; its last failed write is the page's notice.
 * [onOpenMusician] replaces this overlay with a credited artist's — from a chip or a row's More —
 * and is null only where no musician screen can be reached, which leaves the chips display-only.
 */
@Composable
fun AlbumDetailsScreen(
    state: DetailsState<AlbumDetailsUi>,
    onRetry: () -> Unit,
    onPlayAlbum: () -> Unit,
    onShuffle: () -> Unit,
    onPlayTrack: (Int) -> Unit,
    likes: TrackLikesUiState,
    onToggleLike: (Long) -> Unit,
    onOpenMusician: ((Long) -> Unit)?,
    playReturnRequester: FocusRequester,
    modifier: Modifier = Modifier,
    // Parameterized so tests can force both states: the reading-stop chain below depends on it,
    // and a test device with TalkBack running would otherwise pin the gate open.
    spokenAccessibilityEnabled: Boolean = rememberSpokenAccessibilityEnabled(),
) {
    val loaded = (state as? DetailsState.Loaded)?.value
    MusicDetailsScaffold(
        stateKey = state::class,
        loaded = loaded,
        errorMessage = (state as? DetailsState.Error)?.message,
        paneTitle = loaded?.title ?: "Album details",
        tag = "album_details",
        trackRows = loaded?.discs?.flatMap { it.tracks }.orEmpty(),
        retrySemanticLabel = "Retry loading album details",
        onRetry = onRetry,
        onGoToAlbum = null,
        onGoToArtist = onOpenMusician,
        modifier = modifier,
        skeleton = { anchorRequester ->
            DetailsHeroSkeleton(
                artworkAspect = IglooTheme.layout.albumAspect,
                artworkShape = RoundedCornerShape(IglooTheme.radius.lg),
                anchorWidth = PLAY_ALBUM_STUB_WIDTH.scaled(),
                loadingLabel = "Loading album details",
                anchorRequester = anchorRequester,
                trailingStubWidths = listOf(SHUFFLE_STUB_WIDTH.scaled()),
            )
        },
    ) { album, entryRequester, trackRequesters, onOpenMore ->
        AlbumDetailsContent(
            album = album,
            likes = likes,
            spokenAccessibilityEnabled = spokenAccessibilityEnabled,
            entryRequester = entryRequester,
            playReturnRequester = playReturnRequester,
            trackRequesters = trackRequesters,
            onPlayAlbum = onPlayAlbum,
            onShuffle = onShuffle,
            onPlayTrack = onPlayTrack,
            onToggleLike = onToggleLike,
            onOpenMusician = onOpenMusician,
            onOpenMore = onOpenMore,
        )
    }
}

@Composable
private fun AlbumDetailsContent(
    album: AlbumDetailsUi,
    likes: TrackLikesUiState,
    spokenAccessibilityEnabled: Boolean,
    entryRequester: FocusRequester,
    playReturnRequester: FocusRequester,
    trackRequesters: List<TrackRowRequesters>,
    onPlayAlbum: () -> Unit,
    onShuffle: () -> Unit,
    onPlayTrack: (Int) -> Unit,
    onToggleLike: (Long) -> Unit,
    onOpenMusician: ((Long) -> Unit)?,
    onOpenMore: (Int, Rect) -> Unit,
) {
    val layout = IglooTheme.layout
    // A trackless album composes no action row at all: a Play button over zero tracks is a
    // control that takes focus and does nothing (web parity — the web page hides both buttons).
    val trackRows = album.discs.flatMap { it.tracks }
    val hasHeroActions = trackRows.isNotEmpty()

    // The screen's vertical chain, hand-wired end to end: actions -> artist chips -> track rows
    // -> facts panel — and, while a screen reader runs, the hero info reading stop above the
    // actions. Nothing is left to a spatial search, because the shell composed underneath would
    // be a candidate.
    val heroInfoRequester = remember { FocusRequester() }
    val shuffleRequester = remember { FocusRequester() }
    val factsStop = remember { FocusRequester() }
    // Chips are focus targets only with a musician screen to open and a real id to open it on.
    val actionableArtists = onOpenMusician != null && album.artists.all { it.id != null }
    val artistRequesters = remember(album.artists.size, actionableArtists) {
        if (actionableArtists) List(album.artists.size) { FocusRequester() } else emptyList()
    }
    var playLaunchSite by rememberPlayLaunchSite()
    val playReturnRow = playLaunchSite.rowIn(trackRows)
    // With no action row the facts panel owns the entry anchor: the screen's requester *is* the
    // panel's, which lands entry focus there with every edge wired at the panel untouched.
    val factsRequester = if (hasHeroActions) factsStop else entryRequester
    // Up from the rows and the panel returns to whichever action the user left, not
    // unconditionally to Play Album — the same focus memory the movie action row keeps.
    var lastFocusedAction by remember(hasHeroActions) {
        mutableStateOf(entryRequester.takeIf { hasHeroActions })
    }
    val belowActions = artistRequesters.firstOrNull() ?: trackRequesters.firstOrNull()?.play ?: factsRequester
    val upFromBelow = when {
        hasHeroActions -> lastFocusedAction
        spokenAccessibilityEnabled -> heroInfoRequester
        else -> null
    }

    MusicDetailsBody(
        notice = likes.notice,
        noticeTag = "album_notice",
        hero = {
            DetailsHero(imageUrl = album.coverUrl, backdropTag = "album_backdrop") { overMedia ->
                AlbumDetailsHeader(
                    album = album,
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
                    onPlayAlbum = {
                        playLaunchSite = PlayLaunchSite.Primary
                        onPlayAlbum()
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
        AlbumDetailsSections(
            album = album,
            likes = likes,
            artistRequesters = artistRequesters,
            trackRequesters = trackRequesters,
            factsRequester = factsRequester,
            upFromBelow = upFromBelow,
            playReturnRow = playReturnRow,
            playReturnRequester = playReturnRequester,
            onOpenMusician = onOpenMusician?.takeIf { actionableArtists },
            onPlayTrack = { index ->
                playLaunchSite = PlayLaunchSite.Row(index)
                onPlayTrack(index)
            },
            onToggleLike = onToggleLike,
            onOpenMore = onOpenMore,
            contentInset = PaddingValues(horizontal = layout.safeAreaHorizontal),
        )
    }
}

/** The Play Album button's approximate footprint, so focus taken while loading does not jump. */
private val PLAY_ALBUM_STUB_WIDTH = 168.dp
