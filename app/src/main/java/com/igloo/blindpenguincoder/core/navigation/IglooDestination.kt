package com.igloo.blindpenguincoder.core.navigation

/**
 * The navigation rail's rows, in the order it draws them.
 *
 * [supportingText] is the line the placeholder pane shows for a destination that has no screen
 * yet. Search, Home, Watch Rooms, Movies, TV Shows and Music render real content and never
 * surface theirs; the entries stay so the enum has one shape, and are worded as if they would
 * be shown.
 */
enum class IglooDestination(
    val label: String,
    val supportingText: String,
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
    ),
    TvShows(
        label = "TV Shows",
        supportingText = "Browse every show in your library.",
    ),
    Music(
        label = "Music",
        supportingText = "Musicians, albums and tracks in your library.",
    ),
    Settings(
        label = "Settings",
        supportingText = "Server, profile, theme, and playback settings will live here.",
    ),
}

val PrimaryIglooDestinations: List<IglooDestination> = IglooDestination.entries.toList()
