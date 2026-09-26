package com.igloo.blindpenguincoder.core.navigation

/**
 * [supportingText] is the line the placeholder pane shows for a destination that has no screen
 * yet. Home, Movies, TV Shows and Music render real content and never surface theirs; the
 * entries stay so the enum has one shape, and are worded as if they would be shown.
 */
enum class IglooDestination(
    val label: String,
    val supportingText: String,
) {
    Search(
        label = "Search",
        supportingText = "Find movies, shows, music, and photos across your library.",
    ),
    Home(
        label = "Home",
        supportingText = "Your media center starts here.",
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
    Photos(
        label = "Photos",
        supportingText = "Photo support is reserved for a later Igloo backend feature.",
    ),
    Settings(
        label = "Settings",
        supportingText = "Server, profile, theme, and playback settings will live here.",
    ),
}

val PrimaryIglooDestinations: List<IglooDestination> = IglooDestination.entries.toList()
