package com.igloo.blindpenguincoder.playback.model

import kotlinx.serialization.Serializable

/**
 * Everything the music player needs to play one album, assembled by the host from the loaded
 * album details the moment Play Album is pressed — the tracks are already on screen, so unlike
 * the movie's deferred Play there is nothing to await. [tracks] is the queue, flattened in
 * disc-then-track order, exactly the order the detail page lists.
 */
data class MusicPlayRequest(
    val albumId: Long,
    val albumTitle: String,
    /** The hero's credited artist; null renders and announces the title alone. */
    val artistName: String?,
    /** Verbatim absolute Spotify URL or null (the album page's rule); never proxied. */
    val coverUrl: String?,
    val tracks: List<MusicPlayTrack>,
)

/**
 * One queue entry. The duration is the wire's word, kept because ExoPlayer reports
 * `TIME_UNSET` at an item transition until the new track's container is parsed — the seek bar
 * bridges that gap with this value. Serializable so the whole queue rides one saved-state slot
 * as JSON.
 */
@Serializable
data class MusicPlayTrack(
    val id: Long,
    val title: String,
    val durationSec: Double,
)
