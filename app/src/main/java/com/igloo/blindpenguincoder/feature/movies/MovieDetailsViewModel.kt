package com.igloo.blindpenguincoder.feature.movies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import com.igloo.blindpenguincoder.core.ui.RatingBadgeSpec
import com.igloo.blindpenguincoder.core.ui.formatReleaseDate
import com.igloo.blindpenguincoder.core.ui.formatRuntime
import com.igloo.blindpenguincoder.core.ui.progressFraction
import com.igloo.blindpenguincoder.core.ui.progressLabel
import com.igloo.blindpenguincoder.core.ui.ratingBadgeSpec
import com.igloo.blindpenguincoder.data.model.MovieDetailsData
import com.igloo.blindpenguincoder.data.model.MovieTechnicalDetailsData
import com.igloo.blindpenguincoder.data.model.MovieWatchProgress
import com.igloo.blindpenguincoder.data.repository.MovieRepository
import com.igloo.blindpenguincoder.feature.auth.toLibraryDisplayMessage
import com.igloo.blindpenguincoder.images.TmdbImageSize
import com.igloo.blindpenguincoder.images.tmdbImageUrl
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One credit line in the Key Crew band: `"Director"` over `"Michael Mann"`. */
data class CrewEntry(val job: String, val name: String)

/** A cast member ready for the rail card; [photoUrl] is the w185 profile through the proxy. */
data class CastMemberUi(
    val id: Long,
    val name: String,
    val character: String?,
    val photoUrl: String?,
)

/** The fine-print rows at the page's end; every field may be absent. */
data class AboutUi(
    val production: String?,
    val language: String?,
    val budget: String?,
    val revenue: String?,
) {
    val isEmpty: Boolean
        get() = production == null && language == null && budget == null && revenue == null
}

/** The thin strip under the actions; present only while a resume position is worth showing. */
data class ProgressUi(val fraction: Float, val minutesLeftLabel: String)

/**
 * The details screen, render-ready: `SqlNull*` wrappers unwrapped, image URLs built, badges
 * derived — composables read strings, never wire models. Fields fed by the secondary requests
 * (media badges, progress, watched, liked) start absent and fill in as those requests land.
 */
data class MovieDetailsUi(
    val id: Long,
    val title: String,
    val tagline: String?,
    val backdropUrl: String?,
    val posterUrl: String?,
    val ratingBadge: RatingBadgeSpec?,
    val certification: String?,
    val mediaBadges: List<String>,
    val runtimeText: String?,
    val releaseDateText: String?,
    val genresLine: String?,
    val overview: String?,
    val keyCrew: List<CrewEntry>,
    val cast: List<CastMemberUi>,
    val about: AboutUi,
    val progress: ProgressUi?,
    val watched: Boolean?,
    val liked: Boolean?,
    /** The metadata row spoken as one TalkBack stop, composed here so it cannot drift. */
    val metadataDescription: String,
)

sealed interface MovieDetailsState {
    data object Loading : MovieDetailsState
    data class Loaded(val movie: MovieDetailsUi) : MovieDetailsState
    data class Error(val message: String) : MovieDetailsState
}

/** [openMovieId] is the overlay's existence: null means closed and [details] is meaningless. */
data class MovieDetailsUiState(
    val openMovieId: Long? = null,
    val details: MovieDetailsState = MovieDetailsState.Loading,
)

class MovieDetailsViewModel(
    private val movies: MovieRepository,
    private val serverUrl: ServerUrlProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MovieDetailsUiState())
    val uiState: StateFlow<MovieDetailsUiState> = _uiState.asStateFlow()

    private enum class Load { Details, Technical, Progress, Like, ToggleWatched, ToggleLike }

    /** The reads. Leaving the screen cancels these; the two mutations are deliberately not here. */
    private val reads = setOf(Load.Details, Load.Technical, Load.Progress, Load.Like)

    private val loads = mutableMapOf<Load, Job>()

    // A read that started before a mutation finished carries the pre-flip value, and the server
    // can answer a read issued mid-write from before that write commits. Each toggle bumps its
    // counter when it starts *and* when it settles, and a read only applies its value if the
    // counter has not moved while it was in flight — which covers a read issued after the write
    // began, the case a plain "is a mutation running?" check misses.
    private var watchedMutations = 0
    private var likeMutations = 0

    // The four requests land in any order; the fragments live here and every arrival republishes
    // the composed Loaded state from whatever has arrived so far. All are keyed to openMovieId:
    // open() clears them, and a response for a movie no longer open is dropped.
    private var wireDetails: MovieDetailsData? = null
    private var technical: MovieTechnicalDetailsData? = null
    private var progress: MovieWatchProgress? = null
    private var watched: Boolean? = null
    private var liked: Boolean? = null

    /** Opens the overlay on [movieId] and starts the four loads. */
    fun open(movieId: Long) {
        cancelReads()
        clearFragments()
        _uiState.value = MovieDetailsUiState(
            openMovieId = movieId,
            details = MovieDetailsState.Loading,
        )
        loadAll(movieId, userInitiated = true)
    }

    /**
     * Back from the overlay. Cancels the in-flight *reads* so a late response cannot reopen
     * state — but never the mutations: a Watched press followed straight away by Back is a
     * change the user made, and cancelling the PUT would silently drop it. Their own guards
     * already stop a late mutation from writing to a screen that has moved on.
     */
    fun close() {
        cancelReads()
        clearFragments()
        _uiState.value = MovieDetailsUiState()
    }

    /** The full-screen error's Retry: user-initiated, so the screen returns to Loading truth. */
    fun retry() {
        val movieId = _uiState.value.openMovieId ?: return
        cancelReads()
        clearFragments()
        _uiState.update { it.copy(details = MovieDetailsState.Loading) }
        loadAll(movieId, userInitiated = true)
    }

    /**
     * Background re-read while the overlay is open (the host's start effect, matching Home): a
     * failure keeps what is on screen — a TV waking from standby must not swap a readable page
     * for an error card the user never asked for.
     */
    fun refresh() {
        val movieId = _uiState.value.openMovieId ?: return
        loadAll(movieId, userInitiated = false)
    }

    /** Optimistic: the button flips now and flips back if the server disagrees. */
    fun toggleWatched() {
        val movieId = _uiState.value.openMovieId ?: return
        // Status still unknown counts as "not watched": the visible button said "Watch".
        val previous = watched ?: false
        watched = !previous
        publishLoaded()
        // The mutation owns the value now; a status read racing it must not stomp the flip.
        loads.remove(Load.Progress)?.cancel()
        watchedMutations += 1
        launchLoad(Load.ToggleWatched) {
            val result = movies.setMovieWatched(movieId, watched = !previous)
            watchedMutations += 1
            if (_uiState.value.openMovieId != movieId) return@launchLoad
            watched = when (result) {
                is ApiResult.Success -> result.value.watched
                is ApiResult.Failure -> previous
            }
            publishLoaded()
        }
    }

    /** Optimistic, like [toggleWatched]. The endpoint is a server-side toggle with no body. */
    fun toggleLike() {
        val movieId = _uiState.value.openMovieId ?: return
        val previous = liked ?: false
        liked = !previous
        publishLoaded()
        loads.remove(Load.Like)?.cancel()
        likeMutations += 1
        launchLoad(Load.ToggleLike) {
            val result = movies.toggleMovieLike(movieId)
            likeMutations += 1
            if (_uiState.value.openMovieId != movieId) return@launchLoad
            liked = when (result) {
                is ApiResult.Success -> result.value.isLiked
                is ApiResult.Failure -> previous
            }
            publishLoaded()
        }
    }

    private fun loadAll(movieId: Long, userInitiated: Boolean) {
        loadDetails(movieId, userInitiated)
        loadSecondary(movieId, Load.Technical) {
            technical = (movies.movieTechnicalDetails(movieId) as? ApiResult.Success)?.value ?: technical
        }
        loadSecondary(movieId, Load.Progress) {
            val mutationsAtStart = watchedMutations
            (movies.movieWatchProgress(movieId) as? ApiResult.Success)?.value?.let {
                progress = it
                if (mutationsAtStart == watchedMutations) watched = it.watched
            }
        }
        loadSecondary(movieId, Load.Like) {
            val mutationsAtStart = likeMutations
            (movies.movieLikeStatus(movieId) as? ApiResult.Success)?.value?.let {
                if (mutationsAtStart == likeMutations) liked = it.isLiked
            }
        }
    }

    private fun loadDetails(movieId: Long, userInitiated: Boolean) {
        launchLoad(Load.Details) {
            val result = movies.movieDetails(movieId)
            if (_uiState.value.openMovieId != movieId) return@launchLoad
            when (result) {
                is ApiResult.Success -> {
                    wireDetails = result.value
                    publishLoaded()
                }
                is ApiResult.Failure -> _uiState.update {
                    // The orKeep rule: a failed background refresh leaves loaded content alone.
                    if (!userInitiated && it.details is MovieDetailsState.Loaded) {
                        it
                    } else {
                        it.copy(
                            details = MovieDetailsState.Error(
                                result.error.toLibraryDisplayMessage(),
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * The secondary requests degrade instead of failing the screen: badges, the progress strip,
     * and the toggle states simply stay absent, and a stale value survives a failed refresh.
     */
    private fun loadSecondary(movieId: Long, load: Load, fetch: suspend () -> Unit) {
        launchLoad(load) {
            fetch()
            if (_uiState.value.openMovieId == movieId) publishLoaded()
        }
    }

    private fun launchLoad(load: Load, block: suspend () -> Unit) {
        loads[load]?.cancel()
        loads[load] = viewModelScope.launch { block() }
    }

    private fun cancelReads() {
        reads.forEach { load ->
            loads.remove(load)?.cancel()
        }
    }

    private fun clearFragments() {
        wireDetails = null
        technical = null
        progress = null
        watched = null
        liked = null
    }

    /** Composes the Loaded state from whatever fragments have arrived. No details yet, no-op. */
    private fun publishLoaded() {
        val details = wireDetails ?: return
        _uiState.update { it.copy(details = MovieDetailsState.Loaded(toUi(details))) }
    }

    private fun toUi(details: MovieDetailsData): MovieDetailsUi {
        val movie = details.movie
        val apiBaseUrl = serverUrl.require().apiBaseUrl
        // Zero-guarded like the Home hero: the scraper writes TMDB's "no data" as a valid 0.
        val ratingBadge = movie.criticRating?.orNull()?.takeIf { it > 0 }?.let(::ratingBadgeSpec)
        val certification = movie.certification?.orNullIfBlank()
        val badges = technical?.let(::mediaBadges).orEmpty()
        val runtimeMinutes = movie.runTime?.orNull()?.takeIf { it > 0 }
        val releaseDateText = movie.releaseDate?.orNullIfBlank()?.let(::formatReleaseDate)
        return MovieDetailsUi(
            id = movie.id,
            title = movie.title,
            tagline = movie.tagLine?.orNullIfBlank(),
            backdropUrl = tmdbImageUrl(apiBaseUrl, TmdbImageSize.W1280, movie.backdropPath?.orNull()),
            posterUrl = tmdbImageUrl(apiBaseUrl, TmdbImageSize.W500, movie.posterPath?.orNull()),
            ratingBadge = ratingBadge,
            certification = certification,
            mediaBadges = badges,
            runtimeText = runtimeMinutes?.let(::formatRuntime),
            releaseDateText = releaseDateText,
            genresLine = details.genres
                .map { it.tag }
                .filter { it.isNotBlank() }
                .takeIf { it.isNotEmpty() }
                ?.joinToString(" · "),
            overview = movie.overview?.orNullIfBlank(),
            keyCrew = keyCrew(details),
            cast = details.cast
                .sortedBy { it.castOrder }
                .take(CAST_LIMIT)
                .map { member ->
                    CastMemberUi(
                        id = member.id,
                        name = member.artistName,
                        character = member.character.takeIf { it.isNotBlank() },
                        photoUrl = tmdbImageUrl(
                            apiBaseUrl,
                            TmdbImageSize.W185,
                            member.artistProfile?.orNull(),
                        ),
                    )
                },
            about = AboutUi(
                production = details.productionCompanies
                    .map { it.name }
                    .filter { it.isNotBlank() }
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(", "),
                language = movie.language?.orNullIfBlank()?.uppercase(Locale.US),
                budget = movie.budget?.orNull()?.takeIf { it > 0 }?.let(::formatUsd),
                revenue = movie.revenue?.orNull()?.takeIf { it > 0 }?.let(::formatUsd),
            ),
            progress = progressUi(),
            watched = watched,
            liked = liked,
            metadataDescription = metadataDescription(
                ratingBadge = ratingBadge,
                certification = certification,
                badges = badges,
                runtimeMinutes = runtimeMinutes,
                releaseDateText = releaseDateText,
            ),
        )
    }

    /** Director(s) first, then up to three writing credits under their actual jobs (web parity). */
    private fun keyCrew(details: MovieDetailsData): List<CrewEntry> {
        val directors = details.crew
            .filter { it.job == "Director" }
            .map { CrewEntry(job = it.job, name = it.artistName) }
        val writers = details.crew
            .filter { it.department == "Writing" }
            .map { CrewEntry(job = it.job, name = it.artistName) }
            .distinct()
            .take(WRITER_LIMIT)
        return (directors + writers).distinct()
    }

    /**
     * A strip is worth showing from 30 seconds in until the position stops meaning anything —
     * the server itself flips to watched at 98% — and never once the movie is marked watched.
     */
    private fun progressUi(): ProgressUi? {
        if (watched == true) return null
        val current = progress ?: return null
        val progressSec = current.progressSec ?: return null
        val durationSec = current.durationSec ?: return null
        if (progressSec < RESUME_MIN_SEC || durationSec <= 0) return null
        if (progressSec / durationSec >= RESUME_MAX_RATIO) return null
        return ProgressUi(
            fraction = progressFraction(progressSec, durationSec),
            minutesLeftLabel = progressLabel(progressSec, durationSec),
        )
    }

    private fun metadataDescription(
        ratingBadge: RatingBadgeSpec?,
        certification: String?,
        badges: List<String>,
        runtimeMinutes: Long?,
        releaseDateText: String?,
    ): String = listOfNotNull(
        ratingBadge?.let { "Rated ${it.label} out of 10" },
        certification,
        *badges.map { spokenBadge(it) }.toTypedArray(),
        runtimeMinutes?.let(::spokenRuntime),
        releaseDateText?.let { "released $it" },
    ).joinToString(", ")

    /** The visual chip is terse; TalkBack gets the words the abbreviation stands for. */
    private fun spokenBadge(badge: String): String = when (badge) {
        "CC" -> "subtitles available"
        "5.1", "7.1" -> "$badge surround sound"
        "Surround" -> "surround sound"
        else -> badge
    }

    private fun spokenRuntime(minutes: Long): String {
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0L -> "$rest minutes"
            rest == 0L -> if (hours == 1L) "1 hour" else "$hours hours"
            else -> (if (hours == 1L) "1 hour" else "$hours hours") + " $rest minutes"
        }
    }

    private fun formatUsd(amount: Double): String =
        NumberFormat.getCurrencyInstance(Locale.US)
            .apply { maximumFractionDigits = 0 }
            .format(amount)

    private fun mediaBadges(tech: MovieTechnicalDetailsData): List<String> = buildList {
        // Width thresholds deliberately catch scope/anamorphic sources (web parity): a 3840x1600
        // scope master is 4K even though its height is under 2160.
        val video = tech.videoStreams.maxByOrNull { it.width }
        if (video != null) {
            when {
                video.width >= 3200 || video.height >= 2100 -> add("4K")
                video.width >= 1800 || video.height >= 1000 -> add("HD")
            }
            when (video.colorTransfer?.orNull()) {
                "smpte2084" -> add("HDR10")
                "arib-std-b67" -> add("HLG")
            }
        }
        val audio = tech.audioStreams.maxByOrNull { it.channels }
        if (audio != null && audio.channels >= SURROUND_MIN_CHANNELS) {
            val layout = audio.channelLayout?.orNull().orEmpty()
            // Only claim a named layout ffprobe actually reported; otherwise the generic word.
            add(
                when {
                    layout.startsWith("7.1") -> "7.1"
                    layout.startsWith("5.1") -> "5.1"
                    else -> "Surround"
                },
            )
        }
        if (tech.subtitles.isNotEmpty()) add("CC")
    }

    private companion object {
        const val CAST_LIMIT = 10
        const val WRITER_LIMIT = 3
        const val RESUME_MIN_SEC = 30.0
        const val RESUME_MAX_RATIO = 0.98
        const val SURROUND_MIN_CHANNELS = 6
    }
}
