package com.igloo.blindpenguincoder.feature.shows

import com.igloo.blindpenguincoder.core.network.IglooJson
import com.igloo.blindpenguincoder.data.model.ApiEnvelope
import com.igloo.blindpenguincoder.data.model.ShowDetailsData
import com.igloo.blindpenguincoder.data.model.ShowEpisode
import com.igloo.blindpenguincoder.data.model.ShowSeasonEpisodesData
import com.igloo.blindpenguincoder.data.repository.extraVideoJson
import com.igloo.blindpenguincoder.data.repository.genreJson
import com.igloo.blindpenguincoder.data.repository.productionCompanyJson
import com.igloo.blindpenguincoder.data.repository.showCastCreditJson
import com.igloo.blindpenguincoder.data.repository.showDetailsJson
import com.igloo.blindpenguincoder.data.repository.showEpisodeJson
import com.igloo.blindpenguincoder.data.repository.showNetworkJson
import com.igloo.blindpenguincoder.data.repository.showPersonJson
import com.igloo.blindpenguincoder.data.repository.showSeasonEpisodesJson
import com.igloo.blindpenguincoder.data.repository.showSeasonSummaryJson
import com.igloo.blindpenguincoder.feature.shared.CrewEntry
import com.igloo.blindpenguincoder.feature.shared.FactUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The show page's wire-to-render rules (design-system section 11.4.3), fed through the production
 * decoder from the contract's full shapes so a payload that maps here is one the app can read.
 */
class ShowDetailsMappingTest {

    private val apiBaseUrl = "http://igloo.test:8080/api"

    private fun details(json: String): ShowDetailsData =
        IglooJson.decodeFromString<ApiEnvelope<ShowDetailsData>>(json).data!!

    private fun episodes(vararg episodes: String): List<ShowEpisode> =
        IglooJson.decodeFromString<ApiEnvelope<ShowSeasonEpisodesData>>(
            showSeasonEpisodesJson(1, *episodes),
        ).data!!.episodes

    @Test
    fun `the play target resumes the first resumable episode`() {
        val target = seasonPlayTarget(
            1,
            episodes(
                showEpisodeJson(id = 1, episodeNumber = 1, watched = true),
                showEpisodeJson(id = 2, episodeNumber = 2, name = "Half Loop"),
                showEpisodeJson(
                    id = 3,
                    episodeNumber = 3,
                    name = "In Perpetuity",
                    progressSec = 600.0,
                    durationSec = 3300.0,
                ),
            ),
        )

        assertEquals(3L, target?.episodeId)
        assertEquals("Resume S1 E3", target?.label)
        assertEquals("Resume season 1, episode 3, In Perpetuity", target?.spokenLabel)
        assertEquals("Resume", target?.actionLabel)
    }

    @Test
    fun `with nothing to resume the play target is the first unwatched episode`() {
        val target = seasonPlayTarget(
            2,
            episodes(
                showEpisodeJson(id = 1, episodeNumber = 1, watched = true),
                showEpisodeJson(id = 2, episodeNumber = 2, name = "Half Loop"),
            ),
        )

        assertEquals(2L, target?.episodeId)
        assertEquals("Play S2 E2", target?.label)
        assertEquals("Play season 2, episode 2, Half Loop", target?.spokenLabel)
        assertEquals("Play", target?.actionLabel)
    }

    @Test
    fun `a fully watched season plays its first episode and an empty one has no target`() {
        val watched = episodes(
            showEpisodeJson(id = 1, episodeNumber = 1, name = "Pilot", watched = true),
            showEpisodeJson(id = 2, episodeNumber = 2, watched = true),
        )

        assertEquals("Play S1 E1", seasonPlayTarget(1, watched)?.label)
        assertEquals(1L, seasonPlayTarget(1, watched)?.episodeId)
        assertNull(seasonPlayTarget(1, emptyList()))
    }

    @Test
    fun `a watched episode is never resumable and never draws a bar`() {
        // A mark made after a save keeps the saved numbers; watched wins (web parity).
        val episode = episodes(
            showEpisodeJson(
                id = 1,
                episodeNumber = 1,
                progressSec = 600.0,
                durationSec = 3300.0,
                watched = true,
            ),
            showEpisodeJson(id = 2, episodeNumber = 2, name = "Half Loop"),
        )

        assertEquals("Play S1 E2", seasonPlayTarget(1, episode)?.label)
        val card = episode.first().toUi(apiBaseUrl)
        assertNull(card.progressFraction)
        assertTrue(card.watched)
        assertTrue(card.description.contains(". Watched. "))
    }

    @Test
    fun `a position under 30 seconds or past 95 percent is not worth resuming`() {
        val episodes = episodes(
            showEpisodeJson(id = 1, episodeNumber = 1, progressSec = 20.0, durationSec = 3300.0),
            showEpisodeJson(id = 2, episodeNumber = 2, progressSec = 3200.0, durationSec = 3300.0),
        )

        assertEquals("Play S1 E1", seasonPlayTarget(1, episodes)?.label)
        assertNull(episodes[0].toUi(apiBaseUrl).progressFraction)
        assertNull(episodes[1].toUi(apiBaseUrl).progressFraction)
    }

    @Test
    fun `specials are named Specials and coded S0`() {
        val target = seasonPlayTarget(
            0,
            episodes(showEpisodeJson(episodeNumber = 1, name = "Behind the Scenes")),
        )
        val data = details(
            showDetailsJson(
                seasons = listOf(
                    showSeasonSummaryJson(1, availableEpisodes = 9),
                    showSeasonSummaryJson(0, availableEpisodes = 2),
                ),
            ),
        ).toUi(apiBaseUrl)

        assertEquals("Specials", seasonLabel(0))
        assertEquals("Season 3", seasonLabel(3))
        assertEquals("Play S0 E1", target?.label)
        assertEquals("Play Specials, episode 1, Behind the Scenes", target?.spokenLabel)
        assertEquals(
            listOf(
                SeasonUi(1, "Season 1", "Season 1 · 9", "Season 1, 9 episodes"),
                SeasonUi(0, "Specials", "Specials · 2", "Specials, 2 episodes"),
            ),
            data.seasons,
        )
    }

    @Test
    fun `the metadata row counts library seasons and episodes against TMDB's and spans the air years`() {
        val show = details(
            showDetailsJson(
                voteAverage = 8.4,
                certification = "TV-MA",
                status = "Returning Series",
                firstAirDate = "2022-02-17",
                lastAirDate = "2025-03-21",
                tmdbSeasonCount = 3,
                tmdbEpisodeCount = 19,
                seasons = listOf(
                    showSeasonSummaryJson(1, availableEpisodes = 9),
                    showSeasonSummaryJson(2, availableEpisodes = 8),
                    // Specials count as neither a season nor its episodes.
                    showSeasonSummaryJson(0, availableEpisodes = 4),
                ),
            ),
        ).toUi(apiBaseUrl)

        assertEquals("8.4", show.ratingBadge?.label)
        assertEquals("TV-MA", show.certification)
        assertEquals("Returning Series", show.status)
        assertEquals("2 of 3 Seasons · 17 of 19 Episodes · 2022–2025", show.extentLine)
        assertEquals(
            "Rated 8.4 out of 10, TV-MA, Returning Series, 2 of 3 seasons, 17 of 19 episodes, " +
                "aired 2022 to 2025",
            show.metadataDescription,
        )
    }

    @Test
    fun `a library holding more than TMDB lists counts plainly, and a zero rating draws no badge`() {
        val show = details(
            showDetailsJson(
                voteAverage = 0.0,
                certification = null,
                status = null,
                firstAirDate = "2019-11-12",
                lastAirDate = "2019-12-27",
                tmdbSeasonCount = 1,
                tmdbEpisodeCount = 8,
                seasons = listOf(showSeasonSummaryJson(1, availableEpisodes = 9)),
            ),
        ).toUi(apiBaseUrl)

        assertNull(show.ratingBadge)
        assertEquals("1 Season · 9 Episodes · 2019", show.extentLine)
        assertEquals("1 season, 9 episodes, aired 2019", show.metadataDescription)
    }

    @Test
    fun `a show with only specials draws no season count`() {
        val show = details(
            showDetailsJson(
                firstAirDate = null,
                premiereYear = 2001,
                seasons = listOf(showSeasonSummaryJson(0, availableEpisodes = 2)),
            ),
        ).toUi(apiBaseUrl)

        assertEquals("2001", show.extentLine)
        assertFalse(show.metadataDescription.contains("season"))
    }

    @Test
    fun `the hero reads title, tagline, sentence and genres as one stop`() {
        val show = details(
            showDetailsJson(
                tagline = "Please enjoy each fact equally.",
                tmdbSeasonCount = null,
                tmdbEpisodeCount = null,
                genres = listOf(genreJson(tag = "Drama"), genreJson(id = 2, tag = "Mystery")),
            ),
        ).toUi(apiBaseUrl)

        assertEquals("Drama · Mystery", show.genresLine)
        assertEquals(
            "Severance. Please enjoy each fact equally. Rated 8.4 out of 10, TV-MA, " +
                "Returning Series, 2 seasons, 18 episodes, aired 2022. Drama, Mystery",
            show.heroInfoDescription,
        )
        assertEquals("http://igloo.test:8080/api/tmdb/images/w500/severance.jpg", show.posterUrl)
        assertEquals(
            "http://igloo.test:8080/api/tmdb/images/w1280/severance-backdrop.jpg",
            show.backdropUrl,
        )
    }

    @Test
    fun `an episode card titles, describes and speaks its episode`() {
        val card = episodes(
            showEpisodeJson(
                id = 900,
                episodeNumber = 1,
                name = "Good News About Hell",
                overview = "Mark is promoted to department chief.",
                airDate = "2022-02-17",
                runtimeMinutes = 57,
                progressSec = 600.0,
                durationSec = 3300.0,
            ),
        ).single().toUi(apiBaseUrl)

        assertEquals("1. Good News About Hell", card.title)
        assertEquals("57m · February 17, 2022 · 45m left", card.metaLine)
        assertEquals(600f / 3300f, requireNotNull(card.progressFraction), 0.0001f)
        assertEquals("http://igloo.test:8080/api/tmdb/images/w500/still-1.jpg", card.stillUrl)
        assertEquals(
            "Episode 1, Good News About Hell. 57 minutes, aired February 17, 2022. " +
                "45 minutes remaining. Mark is promoted to department chief",
            card.description,
        )
    }

    @Test
    fun `an episode with nothing known about it still names itself`() {
        val card = episodes(
            showEpisodeJson(
                episodeNumber = 4,
                name = "The You You Are",
                overview = null,
                airDate = null,
                stillPath = null,
                runtimeMinutes = null,
            ),
        ).single().toUi(apiBaseUrl)

        assertNull(card.metaLine)
        assertNull(card.overview)
        assertNull(card.stillUrl)
        assertEquals("Episode 4, The You You Are", card.description)
    }

    @Test
    fun `about lists the web's rows in its order, dropping an original name that matches`() {
        fun about(originalName: String?, networks: List<String>) = details(
            showDetailsJson(
                originalName = originalName,
                lastAirDate = "2025-03-21",
                networks = networks,
                productionCompanies = listOf(
                    productionCompanyJson(name = "Red Hour"),
                    productionCompanyJson(id = 2, name = "Fifth Season"),
                ),
            ),
        ).toUi(apiBaseUrl).about

        assertEquals(
            listOf(
                FactUi("Network", "Apple TV+"),
                FactUi("Production", "Red Hour, Fifth Season"),
                FactUi("Status", "Returning Series"),
                FactUi("Type", "Scripted"),
                FactUi("Original language", "English"),
                FactUi("First aired", "February 17, 2022"),
                FactUi("Last aired", "March 21, 2025"),
            ),
            about(originalName = "Severance", networks = listOf(showNetworkJson())),
        )
        val renamed = about(
            originalName = "Separación",
            networks = listOf(showNetworkJson(), showNetworkJson(id = 2, name = "Max")),
        )
        assertEquals(FactUi("Original name", "Separación"), renamed.first())
        assertEquals(FactUi("Networks", "Apple TV+, Max"), renamed[1])
    }

    @Test
    fun `creators become the Key Crew`() {
        val show = details(
            showDetailsJson(
                creators = listOf(
                    showPersonJson(name = "David Crane"),
                    showPersonJson(id = 3, name = "Marta Kauffman"),
                ),
            ),
        ).toUi(apiBaseUrl)

        assertEquals(
            listOf(CrewEntry("Creator", "David Crane"), CrewEntry("Creator", "Marta Kauffman")),
            show.keyCrew,
        )
    }

    @Test
    fun `cast cards are keyed by position so one artist can hold two roles`() {
        val show = details(
            showDetailsJson(
                cast = listOf(
                    showCastCreditJson(
                        artistId = 9,
                        artistName = "Tom Holland",
                        character = "Peter",
                        castOrder = 1,
                        episodeCount = 1,
                    ),
                    showCastCreditJson(
                        artistId = 9,
                        artistName = "Tom Holland",
                        character = "Spider-Man",
                        castOrder = 2,
                        episodeCount = 3,
                    ),
                    showCastCreditJson(artistId = 3, castOrder = 0, profilePath = null),
                ),
            ),
        ).toUi(apiBaseUrl)

        assertEquals(listOf(2L, 0L, 1L), show.cast.map { it.id })
        assertEquals(listOf("Mark Scout", "Peter", "Spider-Man"), show.cast.map { it.character })
        assertEquals(
            listOf("19 episodes", "1 episode", "3 episodes"),
            show.cast.map { it.episodeCountText },
        )
        assertNull(show.cast.first().photoUrl)
    }

    @Test
    fun `extras are YouTube only, trailers first`() {
        val show = details(
            showDetailsJson(
                extraVideos = listOf(
                    extraVideoJson(id = 1, title = "Theme", type = "other"),
                    extraVideoJson(id = 2, title = "Vimeo Teaser", type = "trailer", site = "vimeo"),
                    extraVideoJson(id = 3, title = "Season 2 Trailer", type = "trailer"),
                ),
            ),
        ).toUi(apiBaseUrl)

        assertEquals(listOf(3L, 1L), show.extraVideos.map { it.id })
        assertEquals(listOf("Trailer", "Other"), show.extraVideos.map { it.typeLabel })
    }

    // --- watched toggle ---

    @Test
    fun `marking watched clears the saved position and unwatched keeps it`() {
        val inProgress = episodes(showEpisodeJson(id = 2, progressSec = 600.0, durationSec = 3300.0)).single()

        val watched = inProgress.markedWatched(true)
        assertTrue(watched.watched)
        assertNull(watched.progressSec.orNull())
        assertFalse(watched.isResumable())

        val unwatched = watched.copy(progressSec = inProgress.progressSec).markedWatched(false)
        assertFalse(unwatched.watched)
        assertEquals(600.0, unwatched.progressSec.orNull())
        assertTrue(unwatched.isResumable())
    }

    @Test
    fun `the watched toggle offers the opposite of what the card shows`() {
        val (plain, done) = episodes(
            showEpisodeJson(id = 1, episodeNumber = 1),
            showEpisodeJson(id = 2, episodeNumber = 2, watched = true),
        ).map { it.toUi("http://igloo.test:8080/api") }
        assertEquals("Mark watched", plain.watchedToggleLabel)
        assertEquals("Mark unwatched", done.watchedToggleLabel)
    }
}
