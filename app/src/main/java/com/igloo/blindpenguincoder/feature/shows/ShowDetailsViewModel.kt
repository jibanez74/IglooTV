package com.igloo.blindpenguincoder.feature.shows

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.core.ui.RatingBadgeSpec
import com.igloo.blindpenguincoder.core.ui.orKeepContent
import com.igloo.blindpenguincoder.data.model.ShowEpisode
import com.igloo.blindpenguincoder.data.repository.ShowRepository
import com.igloo.blindpenguincoder.feature.auth.toLibraryDisplayMessage
import com.igloo.blindpenguincoder.feature.shared.CastMemberUi
import com.igloo.blindpenguincoder.feature.shared.CrewEntry
import com.igloo.blindpenguincoder.feature.shared.DetailsState
import com.igloo.blindpenguincoder.feature.shared.ExtraVideoUi
import com.igloo.blindpenguincoder.feature.shared.FactUi
import com.igloo.blindpenguincoder.feature.shared.ProgressUi
import com.igloo.blindpenguincoder.feature.shared.errorOrKeep
import com.igloo.blindpenguincoder.feature.shared.heroInfoDescription
import com.igloo.blindpenguincoder.playback.model.VideoPlayRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One season chip: "Season 1 · 24" on screen, "Season 1, 24 episodes" to TalkBack. */
data class SeasonUi(
    val number: Long,
    /** "Season 1", or "Specials" for season 0; the episode rail's heading. */
    val label: String,
    val chipText: String,
    val chipDescription: String,
)

/**
 * One episode card, render-ready. [progressFraction] is present only while the episode has a
 * position worth resuming; [description] is the card's one TalkBack sentence.
 */
data class EpisodeUi(
    val id: Long,
    val title: String,
    val stillUrl: String?,
    val metaLine: String?,
    val overview: String?,
    val watched: Boolean,
    val progressFraction: Float?,
    val description: String,
)

/**
 * The hero's Play/Resume for the selected season: the episode it starts, "Resume S1 E3" and its
 * spoken form, the verb TalkBack offers as the action ("double-tap to Resume"), and the resume
 * strip under it while the target has a position worth resuming.
 */
data class PlayTargetUi(
    val episodeId: Long,
    val label: String,
    val spokenLabel: String,
    val actionLabel: String,
    val progress: ProgressUi? = null,
)

/**
 * The show details page, render-ready: `SqlNull*` wrappers unwrapped, image URLs built, spoken
 * sentences composed — composables read strings, never wire models.
 */
data class ShowDetailsUi(
    val id: Long,
    val title: String,
    val tagline: String?,
    val backdropUrl: String?,
    val posterUrl: String?,
    val ratingBadge: RatingBadgeSpec?,
    val certification: String?,
    /** TMDB's series status ("Ended", "Returning Series"), a chip beside the certification. */
    val status: String?,
    /** "10 Seasons · 236 Episodes · 1994–2004", the metadata row's plain text. */
    val extentLine: String?,
    val metadataDescription: String,
    val genresLine: String?,
    val overview: String?,
    /** Server order: by number, specials last. */
    val seasons: List<SeasonUi>,
    val keyCrew: List<CrewEntry>,
    val cast: List<CastMemberUi>,
    val extraVideos: List<ExtraVideoUi>,
    val about: List<FactUi>,
) {
    /** Everything the hero's text column says, as its reading stop announces it. */
    val heroInfoDescription: String
        get() = heroInfoDescription(title, tagline, metadataDescription, genresLine)
}

/**
 * [openShowId] is the overlay's existence: null means closed and the rest is meaningless.
 * [episodes] and [playTarget] are the [selectedSeason]'s; a show with no seasons has no
 * selection, an empty rail and no target.
 */
data class ShowDetailsUiState(
    val openShowId: Long? = null,
    val details: DetailsState<ShowDetailsUi> = DetailsState.Loading,
    val selectedSeason: Long? = null,
    val episodes: IglooRailState<EpisodeUi> = IglooRailState.Loading,
    val playTarget: PlayTargetUi? = null,
    /** Why the last Play/Resume or episode press did not reach the player; cleared by the next. */
    val playbackNotice: String? = null,
)

/**
 * The show details page (docs/design-system.md section 11.4.3): the show's details, then one
 * season's episodes at a time. A Play/Resume or episode-card press prepares that episode through
 * the shared [EpisodePlayLauncher] and emits it on [playRequests] for the host to open the player
 * with. Nothing else is written — marking watched is not on the page yet.
 *
 * Opening holds the page in Loading until the details *and* the first season (server order,
 * so specials are never the default) have settled, so the hero's Play/Resume arrives with the
 * page instead of popping into a hero that is anchored to its bottom edge. A first season that
 * fails still opens the page, with the rail showing its own error. Seasons the user picks are
 * fetched on demand and cached once they load; one that failed is retried when picked again.
 * Every response is dropped unless its show is still the one open.
 */
class ShowDetailsViewModel(
    private val shows: ShowRepository,
    private val serverUrl: ServerUrlProvider,
    /** The pre-flight gate's device capability, injected so the launch rules stay JVM-testable. */
    canPlayVideoMime: (mimeType: String) -> Boolean,
    canPlayAudioMime: (mimeType: String, channels: Int?) -> Boolean,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ShowDetailsUiState())
    val uiState: StateFlow<ShowDetailsUiState> = _uiState.asStateFlow()

    // Shared with Home's Continue Watching rail, so both prepare an episode the same way; the
    // notice it posts is this page's own.
    private val episodeLauncher = EpisodePlayLauncher(
        scope = viewModelScope,
        shows = shows,
        serverUrl = serverUrl,
        canPlayVideoMime = canPlayVideoMime,
        canPlayAudioMime = canPlayAudioMime,
        onNotice = { notice -> _uiState.update { it.copy(playbackNotice = notice) } },
    )

    /** An episode ready to play, once its preparation succeeded; the host opens the player. */
    val playRequests: Flow<VideoPlayRequest> get() = episodeLauncher.playRequests

    private var detailsJob: Job? = null
    private val seasonJobs = mutableMapOf<Long, Job>()

    /** Successful season reads only, keyed by season number, for the open show. */
    private val seasonCache = mutableMapOf<Long, List<ShowEpisode>>()

    /** Opens the overlay on show [id] and starts its load. */
    fun open(id: Long) {
        cancelReads()
        _uiState.value = ShowDetailsUiState(openShowId = id)
        loadDetails(id, userInitiated = true)
    }

    /**
     * Back from the overlay. Cancels the in-flight reads so a late response cannot reopen state.
     * Idempotent, because the host closes every detail view model on Back without asking which
     * one was up.
     */
    fun close() {
        cancelReads()
        _uiState.value = ShowDetailsUiState()
    }

    /** The full-screen error's Retry: user-initiated, so the screen returns to Loading truth. */
    fun retry() {
        val id = _uiState.value.openShowId ?: return
        cancelReads()
        _uiState.value = ShowDetailsUiState(openShowId = id)
        loadDetails(id, userInitiated = true)
    }

    /**
     * Background re-read while the overlay is open (the host's start effect): the details and
     * the selected season again, and a failure keeps what is on screen — a TV waking from
     * standby must not swap a readable page for an error the user never asked for. Other
     * seasons' cached episodes are dropped, so picking one later reads it fresh.
     */
    fun refresh() {
        val id = _uiState.value.openShowId ?: return
        // The refresh owns the notice, as Home's does: a preparation from before the app left the
        // foreground must not post one, or open a player, behind the re-read page.
        episodeLauncher.reset()
        loadDetails(id, userInitiated = false)
    }

    /**
     * A season chip's press. Shows the season at once — from the cache, or Loading while it is
     * read — and retargets the hero. Picking the season already shown changes nothing.
     */
    fun selectSeason(seasonNumber: Long) {
        val state = _uiState.value
        val id = state.openShowId ?: return
        if (state.details !is DetailsState.Loaded || state.selectedSeason == seasonNumber) return
        val cached = seasonCache[seasonNumber]
        if (cached != null) {
            _uiState.update { it.withSeason(seasonNumber, cached) }
        } else {
            _uiState.update {
                it.copy(
                    selectedSeason = seasonNumber,
                    episodes = IglooRailState.Loading,
                    playTarget = null,
                )
            }
            if (seasonJobs[seasonNumber]?.isActive != true) {
                loadSeason(id, seasonNumber, userInitiated = true)
            }
        }
    }

    /** The episode rail's Retry: reads the selected season again, back to Loading truth. */
    fun retrySeason() {
        val state = _uiState.value
        val id = state.openShowId ?: return
        val seasonNumber = state.selectedSeason ?: return
        _uiState.update { it.copy(episodes = IglooRailState.Loading, playTarget = null) }
        loadSeason(id, seasonNumber, userInitiated = true)
    }

    /**
     * A Play/Resume or episode-card press. The launcher reads the episode's header, technical
     * details and saved position in parallel and emits the request on [playRequests]; a failure
     * lands in [ShowDetailsUiState.playbackNotice]. A season switch meanwhile leaves the
     * preparation alone — the press named a concrete episode. Nothing happens on a closed page.
     */
    fun playEpisode(episodeId: Long) {
        if (_uiState.value.openShowId == null) return
        episodeLauncher.launch(episodeId)
    }

    private fun loadDetails(id: Long, userInitiated: Boolean) {
        detailsJob?.cancel()
        detailsJob = viewModelScope.launch {
            val result = shows.showDetails(id)
            if (_uiState.value.openShowId != id) return@launch
            when (result) {
                is ApiResult.Success -> {
                    val details = DetailsState.Loaded(result.value.toUi(apiBaseUrl()))
                    val current = _uiState.value
                    val seasonNumbers = result.value.seasons.map { it.seasonNumber }
                    val selected = current.selectedSeason?.takeIf { it in seasonNumbers }
                        ?: seasonNumbers.firstOrNull()
                    when {
                        selected == null -> {
                            seasonCache.clear()
                            _uiState.update {
                                it.copy(
                                    details = details,
                                    selectedSeason = null,
                                    episodes = IglooRailState.Loaded(emptyList()),
                                    playTarget = null,
                                )
                            }
                        }

                        // Opening (or recovering from an error): hold the page until the
                        // default season settles, then publish both in one update.
                        current.details !is DetailsState.Loaded -> {
                            val episodes = shows.seasonEpisodes(id, selected)
                            if (_uiState.value.openShowId != id) return@launch
                            _uiState.update {
                                it.copy(details = details, selectedSeason = selected)
                            }
                            applySeason(selected, episodes, keepContent = false)
                        }

                        else -> {
                            seasonCache.keys.retainAll { it == selected }
                            val reselected = selected != current.selectedSeason
                            _uiState.update {
                                if (reselected) {
                                    it.copy(
                                        details = details,
                                        selectedSeason = selected,
                                        episodes = IglooRailState.Loading,
                                        playTarget = null,
                                    )
                                } else {
                                    it.copy(details = details)
                                }
                            }
                            loadSeason(id, selected, userInitiated = reselected)
                        }
                    }
                }

                is ApiResult.Failure -> _uiState.update {
                    it.copy(
                        details = it.details.errorOrKeep(
                            result.error.toLibraryDisplayMessage(),
                            userInitiated,
                        ),
                    )
                }
            }
        }
    }

    private fun loadSeason(id: Long, seasonNumber: Long, userInitiated: Boolean) {
        seasonJobs[seasonNumber]?.cancel()
        seasonJobs[seasonNumber] = viewModelScope.launch {
            val result = shows.seasonEpisodes(id, seasonNumber)
            if (_uiState.value.openShowId != id) return@launch
            applySeason(seasonNumber, result, keepContent = !userInitiated)
        }
    }

    /**
     * A season's read landing. A success is cached whichever season it is; only the selected
     * season's result reaches the rail and the hero, so a late answer for a season the user has
     * moved off fills the cache without touching what is on screen.
     */
    private fun applySeason(
        seasonNumber: Long,
        result: ApiResult<List<ShowEpisode>>,
        keepContent: Boolean,
    ) {
        when (result) {
            is ApiResult.Success -> {
                seasonCache[seasonNumber] = result.value
                if (_uiState.value.selectedSeason == seasonNumber) {
                    _uiState.update { it.withSeason(seasonNumber, result.value) }
                }
            }

            is ApiResult.Failure -> _uiState.update {
                if (it.selectedSeason != seasonNumber) return@update it
                val episodes = IglooRailState.Error(result.error.toLibraryDisplayMessage())
                    .orKeepContent(it.episodes, keep = keepContent)
                it.copy(
                    episodes = episodes,
                    playTarget = it.playTarget.takeIf { episodes is IglooRailState.Loaded },
                )
            }
        }
    }

    private fun ShowDetailsUiState.withSeason(
        seasonNumber: Long,
        episodes: List<ShowEpisode>,
    ): ShowDetailsUiState {
        val apiBaseUrl = apiBaseUrl()
        return copy(
            selectedSeason = seasonNumber,
            episodes = IglooRailState.Loaded(episodes.map { it.toUi(apiBaseUrl) }),
            playTarget = seasonPlayTarget(seasonNumber, episodes),
        )
    }

    private fun cancelReads() {
        detailsJob?.cancel()
        detailsJob = null
        seasonJobs.values.forEach { it.cancel() }
        seasonJobs.clear()
        seasonCache.clear()
        // The launcher's reads too: a press on the page that is leaving must not open a player
        // over whatever comes next.
        episodeLauncher.reset()
    }

    private fun apiBaseUrl(): String = serverUrl.require().apiBaseUrl
}
