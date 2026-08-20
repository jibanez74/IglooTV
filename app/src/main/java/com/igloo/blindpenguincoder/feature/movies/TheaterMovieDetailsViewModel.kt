package com.igloo.blindpenguincoder.feature.movies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import com.igloo.blindpenguincoder.core.ui.formatReleaseDate
import com.igloo.blindpenguincoder.core.ui.formatRuntime
import com.igloo.blindpenguincoder.core.ui.ratingBadgeSpec
import com.igloo.blindpenguincoder.data.model.TmdbMovie
import com.igloo.blindpenguincoder.data.repository.MovieRepository
import com.igloo.blindpenguincoder.feature.auth.toLibraryDisplayMessage
import com.igloo.blindpenguincoder.images.TmdbImageSize
import com.igloo.blindpenguincoder.images.tmdbImageUrl
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The in-theaters detail screen (docs/design-system.md section 11.4.2): one TMDB movie the
 * library does not hold, rendered by the same [MovieDetailsScreen] as a library movie.
 *
 * It publishes [MovieDetailsUiState] so the host has one overlay slot to drive rather than two,
 * and it is a separate view model from [MovieDetailsViewModel] rather than a mode of it: this
 * screen is a single read with nothing to write, and TMDB ids share a number space with library
 * ids, so one `openMovieId` could not tell the two apart. `mutationNotice` is never set here —
 * there are no toggles to fail — and every field of [MovieDetailsUi] the library fills from its
 * secondary reads (media badges, resume, watched, liked) stays absent.
 */
class TheaterMovieDetailsViewModel(
    private val movies: MovieRepository,
    private val serverUrl: ServerUrlProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MovieDetailsUiState())
    val uiState: StateFlow<MovieDetailsUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    /** Opens the overlay on the TMDB movie [tmdbId] and starts its one load. */
    fun open(tmdbId: Long) {
        loadJob?.cancel()
        _uiState.value = MovieDetailsUiState(
            openMovieId = tmdbId,
            details = MovieDetailsState.Loading,
        )
        load(tmdbId, userInitiated = true)
    }

    /**
     * Back from the overlay. Cancels the in-flight read so a late response cannot reopen state.
     * Idempotent, because the host closes both detail view models on Back without asking which
     * one was up.
     */
    fun close() {
        loadJob?.cancel()
        loadJob = null
        _uiState.value = MovieDetailsUiState()
    }

    /** The full-screen error's Retry: user-initiated, so the screen returns to Loading truth. */
    fun retry() {
        val tmdbId = _uiState.value.openMovieId ?: return
        loadJob?.cancel()
        _uiState.update { it.copy(details = MovieDetailsState.Loading) }
        load(tmdbId, userInitiated = true)
    }

    /**
     * Background re-read while the overlay is open (the host's start effect): a failure keeps
     * what is on screen — a TV waking from standby must not swap a readable page for an error
     * card the user never asked for.
     */
    fun refresh() {
        val tmdbId = _uiState.value.openMovieId ?: return
        load(tmdbId, userInitiated = false)
    }

    private fun load(tmdbId: Long, userInitiated: Boolean) {
        loadJob = viewModelScope.launch {
            val result = movies.tmdbMovie(tmdbId)
            if (_uiState.value.openMovieId != tmdbId) return@launch
            when (result) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(details = MovieDetailsState.Loaded(toUi(result.value)))
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

    private fun toUi(movie: TmdbMovie): MovieDetailsUi {
        val apiBaseUrl = serverUrl.require().apiBaseUrl
        // TMDB sends 0 for an unrated movie, exactly as the theaters rail's card guards against.
        val ratingBadge = movie.voteAverage.takeIf { it > 0 }?.let(::ratingBadgeSpec)
        val certification = certification(movie)
        val runtimeMinutes = movie.runtime.takeIf { it > 0 }
        val releaseDateText = movie.releaseDate.takeIf { it.isNotBlank() }?.let(::formatReleaseDate)
        val extraVideos = youTubeExtraVideos(videoSources(movie), apiBaseUrl)
        return MovieDetailsUi(
            id = movie.id.toLong(),
            title = movie.title,
            tagline = movie.tagline.orNullIfBlank(),
            backdropUrl = tmdbImageUrl(apiBaseUrl, TmdbImageSize.W1280, movie.backdropPath),
            posterUrl = tmdbImageUrl(apiBaseUrl, TmdbImageSize.W500, movie.posterPath),
            ratingBadge = ratingBadge,
            certification = certification,
            // No probed streams to derive badges from: this movie is not in the library.
            mediaBadges = emptyList(),
            runtimeText = runtimeMinutes?.let(::formatRuntime),
            releaseDateText = releaseDateText,
            genresLine = joinedNames(movie.genres.orEmpty().map { it.name }, " · "),
            overview = movie.overview.orNullIfBlank(),
            keyCrew = keyCrew(
                movie.credits.crew.orEmpty().map { CrewCredit(it.job, it.department, it.name) },
            ),
            cast = movie.credits.cast.orEmpty()
                .sortedBy { it.order }
                .take(CAST_LIMIT)
                .map { member ->
                    CastMemberUi(
                        id = member.id.toLong(),
                        name = member.name,
                        character = member.character.takeIf { it.isNotBlank() },
                        photoUrl = tmdbImageUrl(
                            apiBaseUrl,
                            TmdbImageSize.W185,
                            member.profilePath,
                        ),
                    )
                },
            extraVideos = extraVideos,
            about = AboutUi(
                production = joinedNames(movie.productionCompanies.orEmpty().map { it.name }, ", "),
                language = languageDisplayName(movie.originalLanguage.orNullIfBlank()),
                budget = movie.budget.takeIf { it > 0 }?.toDouble()?.let(::formatUsd),
                revenue = movie.revenue.takeIf { it > 0 }?.toDouble()?.let(::formatUsd),
                status = movie.status.orNullIfBlank(),
            ),
            progress = null,
            watched = null,
            liked = null,
            metadataDescription = metadataDescription(
                ratingBadge = ratingBadge,
                certification = certification,
                mediaBadges = emptyList(),
                runtimeMinutes = runtimeMinutes,
                releaseDateText = releaseDateText,
            ),
            heroTrailer = heroTrailer(movie, extraVideos),
        )
    }

    /**
     * TMDB has no numeric id for a video, so the rail's keys are positions in the payload —
     * assigned before the sort, so the same response always keys the same card.
     */
    private fun videoSources(movie: TmdbMovie): List<VideoSource> =
        movie.videos.results.orEmpty().mapIndexed { index, video ->
            VideoSource(
                id = (index + 1).toLong(),
                title = video.name,
                type = video.type,
                site = video.site,
                key = video.key,
            )
        }

    /**
     * The hero plays the first YouTube trailer in TMDB's own order — the same pick the web page
     * makes — located in the mapped extras so the button and its rail card play one video.
     */
    private fun heroTrailer(movie: TmdbMovie, extraVideos: List<ExtraVideoUi>): ExtraVideoUi? {
        val trailerKey = movie.videos.results.orEmpty()
            .firstOrNull {
                normalizedVideoValue(it.site) == "youtube" &&
                    normalizedVideoValue(it.type) == "trailer"
            }
            ?.key
            ?: return null
        return extraVideos.firstOrNull { it.key == trailerKey }
    }

    /**
     * The parental rating, by the backend scanner's own rule (`TmdbMovie.Certification` in the
     * server): the US rating when TMDB has one, otherwise the first non-empty rating from any
     * country. Matching it is what makes this chip agree with the one the same movie would show
     * on its library page once it is scanned in.
     */
    private fun certification(movie: TmdbMovie): String? {
        val certifications = movie.releaseDates.results.orEmpty()
            .flatMap { country ->
                country.releaseDates.orEmpty()
                    .map { country.country to it.certification.trim() }
            }
            .filter { (_, certification) -> certification.isNotEmpty() }
        return certifications.firstOrNull { (country, _) -> country == "US" }?.second
            ?: certifications.firstOrNull()?.second
    }

    private fun String.orNullIfBlank(): String? = takeIf { it.isNotBlank() }
}
