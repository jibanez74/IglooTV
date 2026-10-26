package com.igloo.blindpenguincoder.feature.search

import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.data.model.MovieLibraryItem
import com.igloo.blindpenguincoder.data.model.SearchAllData
import com.igloo.blindpenguincoder.data.model.SearchSection
import com.igloo.blindpenguincoder.data.model.ShowLibraryItem
import com.igloo.blindpenguincoder.data.model.SimpleAlbum
import com.igloo.blindpenguincoder.data.model.SimpleMusician
import com.igloo.blindpenguincoder.data.model.SqlNullInt64
import com.igloo.blindpenguincoder.data.model.SqlNullString
import com.igloo.blindpenguincoder.data.model.TrackListItem
import com.igloo.blindpenguincoder.feature.music.TracksEntry
import com.igloo.blindpenguincoder.feature.shared.PagedState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchMappingTest {

    private val movie = MovieLibraryItem(
        id = 3,
        title = "Help!",
        posterPath = SqlNullString("/help.jpg", valid = true),
        year = SqlNullInt64(1965, valid = true),
    )
    private val show = ShowLibraryItem(
        id = 8,
        name = "Help Me",
        posterPath = SqlNullString("", valid = false),
        premiereYear = SqlNullInt64(0, valid = false),
    )
    private val album = SimpleAlbum(
        id = 5,
        title = "Help!",
        cover = SqlNullString("https://i.scdn.co/image/help.jpg", valid = true),
        musician = SqlNullString("The Beatles", valid = true),
    )
    private val musician = SimpleMusician(
        id = 4,
        name = "The Beatles",
        thumb = SqlNullString("", valid = false),
        albumCount = 3,
        trackCount = 40,
    )
    private fun track(id: Long, title: String) = TrackListItem(
        id = id,
        title = title,
        duration = 125_000,
        albumId = SqlNullInt64(5, valid = true),
        albumTitle = SqlNullString("Help!", valid = true),
        albumCover = SqlNullString("", valid = false),
        musicianId = SqlNullInt64(4, valid = true),
        musicianName = SqlNullString("The Beatles", valid = true),
    )

    private fun allData(
        movies: List<MovieLibraryItem> = listOf(movie),
        moviesTotal: Long = movies.size.toLong(),
        tracks: List<TrackListItem> = listOf(track(1, "Help!"), track(2, "Yesterday")),
        tracksTotal: Long = tracks.size.toLong(),
    ) = SearchAllData(
        query = "help",
        movies = SearchSection(movies, moviesTotal),
        shows = SearchSection(listOf(show), 1),
        albums = SearchSection(listOf(album), 1),
        musicians = SearchSection(emptyList(), 0),
        tracks = SearchSection(tracks, tracksTotal),
    )

    @Test
    fun `every category maps in All-view order and keeps its full count`() {
        val sections = allData(moviesTotal = 12).toSections(API)

        assertEquals(SearchKind.entries, sections.map { it.kind })
        assertEquals(listOf(12L, 1L, 1L, 0L, 2L), sections.map { it.total })
        assertEquals(16L, sections.totalMatches())
        assertTrue(sections[0].hasMore)
        assertFalse(sections[1].hasMore)
    }

    @Test
    fun `a movie or show becomes the library poster card with the proxied poster`() {
        val sections = allData().toSections(API)

        val movieCard = (sections[0] as SearchSectionUi.Movies).items.single()
        assertEquals("Help!", movieCard.title)
        assertEquals(1965L, movieCard.year)
        assertEquals("$API/tmdb/images/w500/help.jpg", movieCard.posterUrl)
        val showCard = (sections[1] as SearchSectionUi.Shows).items.single()
        assertEquals("Help Me", showCard.title)
        assertNull(showCard.year)
        assertNull(showCard.posterUrl)
    }

    @Test
    fun `albums and musicians use the music pane's cards`() {
        val sections = allData().toSections(API)

        val albumCard = (sections[2] as SearchSectionUi.Albums).items.single()
        assertEquals("The Beatles", albumCard.subtitle)
        assertEquals("https://i.scdn.co/image/help.jpg", albumCard.coverUrl)
        assertTrue((sections[3] as SearchSectionUi.Musicians).items.isEmpty())
    }

    @Test
    fun `the tracks heading folds into the first row's sentence only`() {
        val tracks = allData(tracksTotal = 7).toSections(API)[4] as SearchSectionUi.Tracks

        assertEquals(
            "Tracks (7). Help!. The Beatles · Help!. 2 minutes and 5 seconds.",
            tracks.rows[0].spokenInfo,
        )
        assertEquals("Yesterday. The Beatles · Help!. 2 minutes and 5 seconds.", tracks.rows[1].spokenInfo)
        assertEquals(listOf(1L, 2L), tracks.tracks.map { it.id })
    }

    @Test
    fun `a track on its own tab has no header to fold`() {
        val entry = track(9, "Help!").toSearchEntry()

        assertEquals("Help!. The Beatles · Help!. 2 minutes and 5 seconds.", (entry as TracksEntry.Track).track.spokenInfo)
    }

    @Test
    fun `the count lines name the query and the categories`() {
        val sections = allData(moviesTotal = 1200).toSections(API)

        assertEquals("1,204 results for “help”", resultsCountLine(sections.totalMatches(), "help"))
        assertEquals("No results for “zzz”", resultsCountLine(0, "zzz"))
        assertEquals("1 result for “x”", resultsCountLine(1, "x"))
        assertEquals(
            "1,204 results for help: 1,200 movies, 1 TV show, 1 album, 2 tracks.",
            spokenResultsSummary(sections, "help"),
        )
        assertEquals("No results for zzz.", spokenResultsSummary(emptyList(), "zzz"))
    }

    @Test
    fun `a count with no rows is neither shown nor spoken`() {
        // An index mid-rebuild: five tracks counted, none returned.
        val sections = allData(tracks = emptyList(), tracksTotal = 5).toSections(API)
        val shown = SearchUiState(searched = "help", all = PagedState(IglooRailState.Loaded(sections))).all.shownSections()

        assertEquals("3 results for “help”", resultsCountLine(shown.totalMatches(), "help"))
        assertEquals("3 results for help: 1 movie, 1 TV show, 1 album.", spokenResultsSummary(shown, "help"))
    }

    @Test
    fun `section titles and see-all labels count in words`() {
        assertEquals("TV Shows (1)", sectionTitle(SearchKind.Shows, 1))
        assertEquals("Movies (1,200)", sectionTitle(SearchKind.Movies, 1200))
        assertEquals("See all 1,200 movies", seeAllLabel(SearchKind.Movies, 1200))
        assertEquals("See all 1 TV show", seeAllLabel(SearchKind.Shows, 1))
        assertEquals("See all 3 musicians", seeAllLabel(SearchKind.Musicians, 3))
    }

    @Test
    fun `the content is the prompt until a query is searched`() {
        assertEquals(SearchContent.Prompt, SearchUiState(query = "he").toSearchContent())
        assertEquals(SearchContent.Loading, SearchUiState(query = "help", searched = "help").toSearchContent())
    }

    @Test
    fun `the All view is empty only when every category is`() {
        val none = allData(movies = emptyList(), tracks = emptyList()).toSections(API)
            .map { if (it is SearchSectionUi.Shows || it is SearchSectionUi.Albums) emptied(it) else it }
        val emptyState = SearchUiState(searched = "zzz", all = PagedState(IglooRailState.Loaded(none)))
        assertEquals(SearchContent.Empty, emptyState.toSearchContent())
        assertTrue(emptyState.all.shownSections().isEmpty())

        val some = allData().toSections(API)
        val state = SearchUiState(searched = "help", all = PagedState(IglooRailState.Loaded(some)))
        assertEquals(SearchContent.Populated, state.toSearchContent())
        assertEquals(listOf(SearchKind.Movies, SearchKind.Shows, SearchKind.Albums, SearchKind.Tracks), state.all.shownSections().map { it.kind })
    }

    @Test
    fun `a category tab reads its own list`() {
        val state = SearchUiState(
            searched = "help",
            tab = SearchTab.Movies,
            movies = PagedState(IglooRailState.Error("Server down.")),
        )
        assertEquals(SearchContent.Error("Server down."), state.toSearchContent())
        assertEquals(SearchContent.Empty, state.copy(movies = PagedState(IglooRailState.Loaded(emptyList()))).toSearchContent())
    }

    @Test
    fun `every category has a tab and every tab but All a category`() {
        SearchKind.entries.forEach { assertEquals(it, it.tab.kind) }
        assertNull(SearchTab.All.kind)
        assertEquals("search_tab_shows", SearchTab.Shows.presentation.key)
        assertEquals("TV shows", SearchTab.Shows.presentation.semanticLabel)
        assertEquals("All results", SearchTab.All.presentation.semanticLabel)
    }

    private fun emptied(section: SearchSectionUi): SearchSectionUi = when (section) {
        is SearchSectionUi.Shows -> section.copy(items = emptyList(), total = 0)
        is SearchSectionUi.Albums -> section.copy(items = emptyList(), total = 0)
        else -> section
    }

    private companion object {
        const val API = "http://igloo.test:8080/api"
    }
}
