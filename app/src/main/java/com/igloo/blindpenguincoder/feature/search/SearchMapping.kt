package com.igloo.blindpenguincoder.feature.search

import com.igloo.blindpenguincoder.core.ui.countNoun
import com.igloo.blindpenguincoder.core.ui.formatCount
import com.igloo.blindpenguincoder.data.model.MovieLibraryItem
import com.igloo.blindpenguincoder.data.model.SearchAllData
import com.igloo.blindpenguincoder.data.model.ShowLibraryItem
import com.igloo.blindpenguincoder.data.model.TrackListItem
import com.igloo.blindpenguincoder.feature.library.toPosterItem
import com.igloo.blindpenguincoder.feature.movies.toLibraryRow
import com.igloo.blindpenguincoder.feature.music.AlbumCardUi
import com.igloo.blindpenguincoder.feature.music.MusicianCardUi
import com.igloo.blindpenguincoder.feature.music.TracksEntry
import com.igloo.blindpenguincoder.feature.music.toCardUi
import com.igloo.blindpenguincoder.feature.music.toRowUi
import com.igloo.blindpenguincoder.feature.shared.PosterItem
import com.igloo.blindpenguincoder.feature.shared.TrackRowUi
import com.igloo.blindpenguincoder.feature.shows.toLibraryRow

/**
 * Wire-to-render rules for the Search pane (docs/design-system.md section 11.6), pure so every
 * grouping and wording decision is JVM-testable. The cards themselves are the library and music
 * panes' own: a search result is drawn exactly as the same row is drawn in its home pane.
 */

/**
 * One category of the All view: the rows the server chose, drawn as that category's cards, and
 * the category's full match count. [total] can exceed the rows shown, which is what the See-all
 * card is for; a section whose total is zero is not drawn at all.
 */
sealed interface SearchSectionUi {
    val kind: SearchKind
    val total: Long
    val shownCount: Int

    data class Movies(val items: List<PosterItem>, override val total: Long) : SearchSectionUi {
        override val kind: SearchKind get() = SearchKind.Movies
        override val shownCount: Int get() = items.size
    }

    data class Shows(val items: List<PosterItem>, override val total: Long) : SearchSectionUi {
        override val kind: SearchKind get() = SearchKind.Shows
        override val shownCount: Int get() = items.size
    }

    data class Albums(val items: List<AlbumCardUi>, override val total: Long) : SearchSectionUi {
        override val kind: SearchKind get() = SearchKind.Albums
        override val shownCount: Int get() = items.size
    }

    data class Musicians(val items: List<MusicianCardUi>, override val total: Long) : SearchSectionUi {
        override val kind: SearchKind get() = SearchKind.Musicians
        override val shownCount: Int get() = items.size
    }

    /** Rows plus the wire tracks they came from, because a row's Play queues the rows shown. */
    data class Tracks(
        val rows: List<TrackRowUi>,
        val tracks: List<TrackListItem>,
        override val total: Long,
    ) : SearchSectionUi {
        override val kind: SearchKind get() = SearchKind.Tracks
        override val shownCount: Int get() = rows.size
    }

    /** More matches exist than rows shown: the See-all card's reason to exist. */
    val hasMore: Boolean get() = total > shownCount
}

/** Every category, in All-view order, including the ones with nothing to show. */
internal fun SearchAllData.toSections(apiBaseUrl: String): List<SearchSectionUi> = listOf(
    SearchSectionUi.Movies(movies.results.map { it.toPosterItem(apiBaseUrl) }, movies.total),
    SearchSectionUi.Shows(shows.results.map { it.toPosterItem(apiBaseUrl) }, shows.total),
    SearchSectionUi.Albums(albums.results.map { it.toCardUi() }, albums.total),
    SearchSectionUi.Musicians(musicians.results.map { it.toCardUi() }, musicians.total),
    SearchSectionUi.Tracks(
        // The heading is plain text a TV screen reader never reaches, so the first row folds it
        // into its sentence — the rails say theirs on entry; this is the plain column's way.
        rows = tracks.results.mapIndexed { index, track ->
            track.toRowUi(prefix = sectionTitle(SearchKind.Tracks, tracks.total).takeIf { index == 0 })
        },
        tracks = tracks.results,
        total = tracks.total,
    ),
)

/** A section's heading on the All view: `"Movies (12)"`, the count being the full match count. */
internal fun sectionTitle(kind: SearchKind, total: Long): String = "${kind.title} (${formatCount(total)})"

internal fun MovieLibraryItem.toPosterItem(apiBaseUrl: String): PosterItem = toLibraryRow().toPosterItem(apiBaseUrl)

internal fun ShowLibraryItem.toPosterItem(apiBaseUrl: String): PosterItem = toLibraryRow().toPosterItem(apiBaseUrl)

/** The Tracks tab's flat list: ranked rows, no letter headers — the server's order is relevance. */
internal fun TrackListItem.toSearchEntry(): TracksEntry = TracksEntry.Track(toRowUi())

/** Every category's full count once the All view has landed; the header's number. */
internal fun List<SearchSectionUi>.totalMatches(): Long = sumOf { it.total }

/** The header's visible line: `"42 results for “casino”"`, or `"No results for “casino”"`. */
internal fun resultsCountLine(total: Long, query: String): String =
    if (total == 0L) "No results for “$query”" else "${formatCount(total)} ${countNoun(total, "result")} for “$query”"

/**
 * The header's spoken line on the All view: the total, then each category's count, so a screen
 * reader hears the shape of the results once per search without walking every rail.
 */
internal fun spokenResultsSummary(sections: List<SearchSectionUi>, query: String): String {
    val total = sections.totalMatches()
    if (total == 0L) return "No results for $query."
    val breakdown = sections.filter { it.total > 0 }
        .joinToString(", ") { "${formatCount(it.total)} ${it.kind.noun(it.total)}" }
    return "${formatCount(total)} ${countNoun(total, "result")} for $query: $breakdown."
}

/** The All-view card at the end of a section with more matches than it shows. */
internal fun seeAllLabel(kind: SearchKind, total: Long): String =
    "See all ${formatCount(total)} ${kind.noun(total)}"
