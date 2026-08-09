package com.igloo.blindpenguincoder.core.navigation

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
        supportingText = "Movie library scaffolding is ready for API-backed content.",
    ),
    TvShows(
        label = "TV Shows",
        supportingText = "TV show browsing will use the same remote-first shell.",
    ),
    Music(
        label = "Music",
        supportingText = "Music playback dependencies are available for the next feature pass.",
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
