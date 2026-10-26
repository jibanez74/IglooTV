package com.igloo.blindpenguincoder.feature.search

import androidx.compose.ui.graphics.vector.ImageVector
import com.igloo.blindpenguincoder.core.ui.IglooIcons
import com.igloo.blindpenguincoder.core.ui.countNoun
import com.igloo.blindpenguincoder.feature.shared.TabPresentation

/** The five categories the server searches, in the order the All view stacks them. */
enum class SearchKind(
    /** The section heading and the category tab's label. */
    val title: String,
    /** The spoken form of [title]; the same unless the eye reads an abbreviation. */
    val spokenTitle: String,
    private val singular: String,
    /** The path segment and the key every tag and focus-ownership key is built from. */
    val key: String,
) {
    Movies("Movies", "Movies", "movie", "movies"),
    Shows("TV Shows", "TV shows", "TV show", "shows"),
    Albums("Albums", "Albums", "album", "albums"),
    Musicians("Musicians", "Musicians", "musician", "musicians"),
    Tracks("Tracks", "Tracks", "track", "tracks"),
    ;

    /** `"movie"` / `"movies"`, `"TV show"` / `"TV shows"`, by [count]. */
    fun noun(count: Long): String = countNoun(count, singular)

    val icon: ImageVector
        get() = when (this) {
            Movies -> IglooIcons.Movies
            Shows -> IglooIcons.TvShows
            Albums, Tracks -> IglooIcons.Music
            Musicians -> IglooIcons.Person
        }

    val tab: SearchTab
        get() = when (this) {
            Movies -> SearchTab.Movies
            Shows -> SearchTab.Shows
            Albums -> SearchTab.Albums
            Musicians -> SearchTab.Musicians
            Tracks -> SearchTab.Tracks
        }
}

/** The pane's tab strip: every category at once, then one tab per category. */
enum class SearchTab(val kind: SearchKind?) {
    All(null),
    Movies(SearchKind.Movies),
    Shows(SearchKind.Shows),
    Albums(SearchKind.Albums),
    Musicians(SearchKind.Musicians),
    Tracks(SearchKind.Tracks),
    ;

    val presentation: TabPresentation
        get() = kind?.let { TabPresentation(it.title, it.spokenTitle, "search_tab_${it.key}") }
            ?: TabPresentation("All", "All results", "search_tab_all")
}
