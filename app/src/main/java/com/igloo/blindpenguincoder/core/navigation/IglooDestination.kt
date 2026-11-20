package com.igloo.blindpenguincoder.core.navigation

/**
 * The navigation rail's rows, in the order it draws them.
 *
 * [supportingText] is the line the placeholder pane shows for a destination that has no screen
 * yet. Search, Home, Watch Rooms, Movies, TV Shows and Music render real content and never
 * surface theirs; the entries stay so the enum has one shape, and are worded as if they would
 * be shown.
 *
 * [inLibrary] marks the library's own shelves — the web sidebar's "Library" group. The rail sets
 * them apart from what comes before and after with a hairline (section 11.2).
 */
enum class IglooDestination(
    val label: String,
    val supportingText: String,
    val inLibrary: Boolean = false,
) {
    Search(
        label = "Search",
        supportingText = "Find movies, shows, albums, musicians and tracks across your library.",
    ),
    Home(
        label = "Home",
        supportingText = "Your media center starts here.",
    ),
    WatchRooms(
        label = "Watch Rooms",
        supportingText = "Rooms you host or were invited to, for watching a movie together.",
    ),
    Movies(
        label = "Movies",
        supportingText = "Browse every movie in your library.",
        inLibrary = true,
    ),
    TvShows(
        label = "TV Shows",
        supportingText = "Browse every show in your library.",
        inLibrary = true,
    ),
    Music(
        label = "Music",
        supportingText = "Musicians, albums and tracks in your library.",
        inLibrary = true,
    ),
    Settings(
        label = "Settings",
        supportingText = "Server, profile, theme, and playback settings will live here.",
    ),
}
