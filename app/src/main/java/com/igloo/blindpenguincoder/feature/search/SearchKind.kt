package com.igloo.blindpenguincoder.feature.search

import androidx.compose.ui.graphics.vector.ImageVector
import com.igloo.blindpenguincoder.core.ui.IglooIcons
import com.igloo.blindpenguincoder.core.ui.countNoun
import com.igloo.blindpenguincoder.data.api.SearchRoute
import com.igloo.blindpenguincoder.feature.shared.TabPresentation

/** The five categories the server searches, in the order the All view stacks them. */
enum class SearchKind(
    /** The section heading and the category tab's label. */
    val title: String,
    /** The spoken form of [title]; the same unless the eye reads an abbreviation. */
    val spokenTitle: String,
    private val singular: String,
    private val route: SearchRoute,
) {
    Movies("Movies", "Movies", "movie", SearchRoute.Movies),
    Shows("TV Shows", "TV shows", "TV show", SearchRoute.Shows),
    Albums("Albums", "Albums", "album", SearchRoute.Albums),
    Musicians("Musicians", "Musicians", "musician", SearchRoute.Musicians),
    Tracks("Tracks", "Tracks", "track", SearchRoute.Tracks),
    ;

    /** The route's path segment: the key every tag and focus-ownership key is built from. */
    val key: String get() = route.segment

    /** `"movie"` / `"movies"`, `"TV show"` / `"TV shows"`, by [count]. */
    fun noun(count: Long): String = countNoun(count, singular)

    val icon: ImageVector
        get() = when (this) {
            Movies -> IglooIcons.Movies
            Shows -> IglooIcons.TvShows
            Albums, Tracks -> IglooIcons.Music
            Musicians -> IglooIcons.Person
        }

    /** The category's own tab; [SearchTab.kind] is the one place the pairing is spelled. */
    val tab: SearchTab get() = SearchTab.entries.first { it.kind == this }
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
