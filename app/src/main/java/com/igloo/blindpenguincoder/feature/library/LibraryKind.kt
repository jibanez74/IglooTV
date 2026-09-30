package com.igloo.blindpenguincoder.feature.library

import com.igloo.blindpenguincoder.core.ui.countNoun
import com.igloo.blindpenguincoder.feature.shared.TabPresentation

/**
 * Which library the pane is showing. Movies and TV Shows share one screen and one paging
 * machine (docs/design-system.md section 11.4); the kind carries everything that differs on
 * the surface — the wording, the test tags and, in the screen, the glyph — so each string is
 * spelled once per kind rather than once per call site.
 */
enum class LibraryKind(
    /** The pane's heading. */
    val heading: String,
    /** The noun for one item, from which every plural and count phrase is built. */
    val singular: String,
    /** The library named as a whole: "Refresh the movie library", "Loading the TV show library". */
    val libraryPhrase: String,
    /** Prefix for the pane's test tags and focus-ownership keys. */
    val tagPrefix: String,
    /** Prefix for a card's test tag; the Movies grid keeps the tag Home's rails use. */
    private val cardTagPrefix: String,
) {
    Movies(
        heading = "Movies",
        singular = "movie",
        libraryPhrase = "the movie library",
        tagPrefix = "movies",
        cardTagPrefix = "poster_card",
    ),
    Shows(
        heading = "TV Shows",
        singular = "show",
        libraryPhrase = "the TV show library",
        tagPrefix = "shows",
        cardTagPrefix = "show_card",
    );

    val plural: String get() = singular + "s"

    /** The noun beside a count — one "movie", otherwise "movies". */
    fun noun(count: Long): String = countNoun(count, singular)

    fun cardTag(id: Long): String = "${cardTagPrefix}_$id"

    /** The skeleton anchor's announcement while page one is out. */
    val loadingLabel: String get() = "Loading $plural"

    /** What the empty box says for one filter's empty list. */
    fun emptyMessage(filter: LibraryFilter): String = when (filter) {
        LibraryFilter.All -> "No $plural found in your library."
        LibraryFilter.Liked ->
            "No liked $plural yet. Like a $singular from its details page and it will appear here."
        is LibraryFilter.Genre -> "No ${filter.tag} $plural in your library."
    }

    /** What the tab strip draws, speaks and is addressed by for one section. */
    fun presentation(tab: LibraryTab): TabPresentation = when (tab) {
        LibraryTab.All -> TabPresentation(
            label = "All ${plural.replaceFirstChar { it.uppercase() }}",
            semanticLabel = "All $plural",
            key = "${tagPrefix}_tab_all",
        )
        LibraryTab.Genres -> TabPresentation("Genres", "Genres", "${tagPrefix}_tab_genres")
        LibraryTab.Liked -> TabPresentation("Liked", "Liked $plural", "${tagPrefix}_tab_liked")
    }
}
