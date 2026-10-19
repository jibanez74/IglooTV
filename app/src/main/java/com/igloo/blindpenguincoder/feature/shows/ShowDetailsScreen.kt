package com.igloo.blindpenguincoder.feature.shows

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.scaled
import com.igloo.blindpenguincoder.core.ui.IglooPinnedError
import com.igloo.blindpenguincoder.core.ui.announcedSection
import com.igloo.blindpenguincoder.core.ui.iglooEnterStagger
import com.igloo.blindpenguincoder.core.ui.rememberAnnouncedSectionColumn
import com.igloo.blindpenguincoder.core.ui.rememberRefocusAfterSwap
import com.igloo.blindpenguincoder.core.ui.rememberSpokenAccessibilityEnabled
import com.igloo.blindpenguincoder.core.ui.requestFocusSafely
import com.igloo.blindpenguincoder.feature.shared.AboutSection
import com.igloo.blindpenguincoder.feature.shared.CastSection
import com.igloo.blindpenguincoder.feature.shared.DetailsHero
import com.igloo.blindpenguincoder.feature.shared.DetailsHeroSkeleton
import com.igloo.blindpenguincoder.feature.shared.DetailsState
import com.igloo.blindpenguincoder.feature.shared.ExtraVideosSection
import com.igloo.blindpenguincoder.feature.shared.KeyCrewSection
import com.igloo.blindpenguincoder.feature.shared.OverviewSection
import com.igloo.blindpenguincoder.feature.shared.ResumeProgress
import com.igloo.blindpenguincoder.feature.shared.heroHeaderModifier

/**
 * What the show page can do. Close is the host's own business (it owns Back and focus
 * restoration) and is deliberately not here; so is everything the page draws but cannot do yet
 * — playing an episode or a video, marking one watched.
 */
data class ShowDetailsActions(
    val onRetry: () -> Unit,
    val onSelectSeason: (Long) -> Unit,
    val onRetrySeason: () -> Unit,
)

/**
 * The show details screen (docs/design-system.md section 11.4.3): a full-screen in-tree overlay
 * above the shell, opaque on the `background` token, with the movie page's full-bleed hero and
 * below it Overview, the Seasons chips and the selected season's episode rail, Key Crew, Cast,
 * Extra Videos and About. Back and focus restoration belong to the host
 * ([com.igloo.blindpenguincoder.feature.home.IglooApp]), which also fences the shell's focusables
 * while this is open — the screen itself only anchors entry focus.
 *
 * The screen keeps a frame of its own, the movie page's: the entry requester rides the
 * skeleton's action stub while loading, the Retry on error, and once loaded the first section
 * that can take focus — the hero summary under a screen reader, else Play/Resume, or (for a show
 * whose first season failed or that has no seasons) the next section down.
 */
@Composable
fun ShowDetailsScreen(
    state: ShowDetailsUiState,
    actions: ShowDetailsActions,
    modifier: Modifier = Modifier,
    // Parameterized so tests can force both states: the reading-stop chain below depends on it,
    // and a test device with TalkBack running would otherwise pin the gate open.
    spokenAccessibilityEnabled: Boolean = rememberSpokenAccessibilityEnabled(),
) {
    val colors = IglooTheme.colors
    val entryRequester = remember { FocusRequester() }
    // Requested safely: a page that is only prose has nothing to anchor without a screen reader.
    LaunchedEffect(Unit) { entryRequester.requestFocusSafely() }
    // Keyed on the state's class — a Loaded republish (a refresh) must not yank focus back.
    val onScreenFocus = rememberRefocusAfterSwap(state.details::class, spokenAccessibilityEnabled) {
        entryRequester.requestFocusSafely()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .onFocusChanged { onScreenFocus(it.hasFocus) }
            .semantics {
                // The loaded pane announces the show itself (section 12's pane rule).
                paneTitle = (state.details as? DetailsState.Loaded)?.value?.title ?: "Show details"
                isTraversalGroup = true
            }
            .testTag("show_details"),
    ) {
        when (val details = state.details) {
            // Geometry-matched to the hero it stands in for: a poster and one action, so the
            // anchor focus is sitting on is where Play/Resume lands.
            is DetailsState.Loading -> DetailsHeroSkeleton(
                artworkAspect = IglooTheme.layout.posterAspect,
                artworkShape = RoundedCornerShape(IglooTheme.radius.lg),
                anchorWidth = PLAY_STUB_WIDTH.scaled(),
                loadingLabel = "Loading show details",
                anchorRequester = entryRequester,
                trailingStubWidths = emptyList(),
                belowAnchor = { ResumeProgress(progress = null, overMedia = false) },
            )

            is DetailsState.Error -> IglooPinnedError(
                message = details.message,
                actionText = "Retry",
                actionSemanticLabel = "Retry loading show details",
                actionRequester = entryRequester,
                onAction = actions.onRetry,
            )

            is DetailsState.Loaded -> ShowDetailsContent(
                show = details.value,
                state = state,
                actions = actions,
                spokenAccessibilityEnabled = spokenAccessibilityEnabled,
                entryRequester = entryRequester,
            )
        }
    }
}

/**
 * The page's sections, top to bottom. HeroInfo, Overview and Key Crew are reading stops, focus
 * targets only while a screen reader runs (section 12).
 */
private enum class ShowSection {
    HeroInfo,
    Action,
    Overview,
    Seasons,
    Episodes,
    KeyCrew,
    Cast,
    Extras,
    About,
}

@Composable
private fun ShowDetailsContent(
    show: ShowDetailsUi,
    state: ShowDetailsUiState,
    actions: ShowDetailsActions,
    spokenAccessibilityEnabled: Boolean,
    entryRequester: FocusRequester,
) {
    val layout = IglooTheme.layout
    val stops = spokenAccessibilityEnabled
    // The vertical chain, hand-wired end to end over the sections actually on the page: nothing
    // is left to a spatial search, because the shell composed underneath would be a candidate.
    val present = buildList {
        if (stops) add(ShowSection.HeroInfo)
        if (state.playTarget != null) add(ShowSection.Action)
        if (stops) add(ShowSection.Overview)
        if (show.seasons.size >= 2) add(ShowSection.Seasons)
        if (show.seasons.isNotEmpty()) add(ShowSection.Episodes)
        if (stops && show.keyCrew.isNotEmpty()) add(ShowSection.KeyCrew)
        if (show.cast.isNotEmpty()) add(ShowSection.Cast)
        if (show.extraVideos.isNotEmpty()) add(ShowSection.Extras)
        if (show.about.isNotEmpty()) add(ShowSection.About)
    }
    // The screen's entry requester *is* the first section's own. Under a screen reader that is
    // the hero's summary stop: the skeleton giving way drops focus, Android hands it to the first
    // focusable — the summary — and TalkBack settles there, deaf to a move to Play that follows
    // the pane-title change; landing on the summary on purpose keeps TalkBack and input focus
    // together and speaks what the page is before what it can do. Otherwise it is Play/Resume,
    // else whatever section comes next (section 11.4.2's no-action rule).
    val entrySection = present.firstOrNull()
    val scrollState = rememberScrollState()
    // Moves between sections that a screen reader can hear even when the next section is still
    // below the fold — at 540dp the chips, the episode rail and everything under them are.
    val sectionColumn = rememberAnnouncedSectionColumn(
        scrollState = scrollState,
        screenReader = spokenAccessibilityEnabled,
        order = present,
        requesterFor = { column, key ->
            if (key == entrySection) entryRequester else column.section(key).entry
        },
    )
    fun requesterOf(section: ShowSection): FocusRequester =
        if (section == entrySection) entryRequester else sectionColumn.section(section).entry
    fun above(section: ShowSection): FocusRequester? {
        val index = present.indexOf(section)
        return if (index <= 0) null else requesterOf(present[index - 1])
    }
    fun below(section: ShowSection): FocusRequester? {
        val index = present.indexOf(section)
        return if (index < 0) null else present.getOrNull(index + 1)?.let(::requesterOf)
    }
    fun tracked(section: ShowSection): Modifier =
        Modifier.announcedSection(sectionColumn.section(section))

    // A season chip's press hands focus down to that season's episodes once the rail it selects
    // has composed — never from inside the press, while the entry requester still rides the
    // outgoing season's card. Through the section move, so the landing is heard.
    var pendingSeason by remember { mutableStateOf<Long?>(null) }
    var handoffToken by remember { mutableIntStateOf(0) }
    LaunchedEffect(handoffToken, state.selectedSeason) {
        val pending = pendingSeason ?: return@LaunchedEffect
        if (pending != state.selectedSeason) return@LaunchedEffect
        pendingSeason = null
        withFrameNanos { }
        if (sectionColumn.section(ShowSection.Seasons).hasFocus) {
            sectionColumn.handOff(ShowSection.Episodes)
        }
    }
    val onPressSeason: (Long) -> Unit = { seasonNumber ->
        pendingSeason = seasonNumber
        handoffToken += 1
        if (seasonNumber != state.selectedSeason) actions.onSelectSeason(seasonNumber)
    }
    // Focus on a node that a state change disposes is dropped on the floor; these two re-land
    // it. Play goes when a refresh leaves its season with nothing to play, and the rail when a
    // refresh finds its season gone and the page falls back to the first.
    val screenReader = spokenAccessibilityEnabled
    val onActionFocus = rememberRefocusAfterSwap(state.playTarget != null, screenReader) {
        entryRequester.requestFocusSafely()
    }
    val onEpisodesFocus = rememberRefocusAfterSwap(state.selectedSeason, screenReader) {
        requesterOf(ShowSection.Episodes).requestFocusSafely()
    }

    val contentInset = PaddingValues(horizontal = layout.safeAreaHorizontal)
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(sectionColumn.modifier)
            .verticalScroll(scrollState),
    ) {
        DetailsHero(imageUrl = show.backdropUrl, backdropTag = "details_backdrop") { overMedia ->
            ShowDetailsHeader(
                show = show,
                episodes = state.episodes,
                playTarget = state.playTarget,
                overMedia = overMedia,
                spokenAccessibilityEnabled = stops,
                heroInfoRequester = requesterOf(ShowSection.HeroInfo),
                heroInfoDownRequester = below(ShowSection.HeroInfo),
                actionRequester = requesterOf(ShowSection.Action),
                actionUpRequester = above(ShowSection.Action),
                actionDownRequester = below(ShowSection.Action),
                heroInfoModifier = tracked(ShowSection.HeroInfo),
                actionModifier = tracked(ShowSection.Action)
                    .onFocusChanged { onActionFocus(it.hasFocus) },
                modifier = heroHeaderModifier(),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = layout.safeAreaVertical)
                .iglooEnterStagger(entered = entered, index = 0),
            verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.lg),
        ) {
            OverviewSection(
                overview = show.overview,
                readingStop = stops,
                requester = requesterOf(ShowSection.Overview),
                upRequester = above(ShowSection.Overview),
                downRequester = below(ShowSection.Overview),
                modifier = tracked(ShowSection.Overview).padding(contentInset),
            )
            ShowSeasonsSection(
                seasons = show.seasons,
                selectedSeason = state.selectedSeason,
                episodes = state.episodes,
                contentInset = contentInset,
                screenReader = spokenAccessibilityEnabled,
                chipsRequester = requesterOf(ShowSection.Seasons),
                chipsUpRequester = above(ShowSection.Seasons),
                chipsDownRequester = below(ShowSection.Seasons),
                episodesRequester = requesterOf(ShowSection.Episodes),
                episodesUpRequester = above(ShowSection.Episodes),
                episodesDownRequester = below(ShowSection.Episodes),
                chipsModifier = tracked(ShowSection.Seasons),
                episodesModifier = tracked(ShowSection.Episodes)
                    .onFocusChanged { onEpisodesFocus(it.hasFocus) },
                onPressSeason = onPressSeason,
                onRetrySeason = actions.onRetrySeason,
            )
            if (show.keyCrew.isNotEmpty()) {
                KeyCrewSection(
                    crew = show.keyCrew,
                    readingStop = stops,
                    requester = requesterOf(ShowSection.KeyCrew),
                    upRequester = above(ShowSection.KeyCrew),
                    downRequester = below(ShowSection.KeyCrew),
                    modifier = tracked(ShowSection.KeyCrew).padding(contentInset),
                )
            }
            if (show.cast.isNotEmpty()) {
                CastSection(
                    cast = show.cast,
                    contentInset = contentInset,
                    entryRequester = requesterOf(ShowSection.Cast),
                    upRequester = above(ShowSection.Cast),
                    downRequester = below(ShowSection.Cast),
                    modifier = tracked(ShowSection.Cast),
                )
            }
            if (show.extraVideos.isNotEmpty()) {
                ExtraVideosSection(
                    videos = show.extraVideos,
                    contentInset = contentInset,
                    entryRequester = requesterOf(ShowSection.Extras),
                    upRequester = above(ShowSection.Extras),
                    downRequester = below(ShowSection.Extras),
                    // Playing a video is not on the show page yet: the cards are inert.
                    returnRequester = null,
                    onPlayExtra = null,
                    modifier = tracked(ShowSection.Extras),
                )
            }
            if (show.about.isNotEmpty()) {
                AboutSection(
                    title = show.title,
                    facts = show.about,
                    requester = requesterOf(ShowSection.About),
                    upRequester = above(ShowSection.About),
                    modifier = tracked(ShowSection.About).padding(contentInset),
                )
            }
        }
    }
}
