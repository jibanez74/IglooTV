package com.igloo.blindpenguincoder.playback.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the video player is playing, as the backend addresses it: a movie under `/movies/{id}`
 * or a TV episode under `/shows/episodes/{id}`. The two id spaces overlap, so the kind travels
 * with the id everywhere the player names its media — stream URLs, HLS sessions, progress
 * writes, the media session — and only the route builder tells them apart.
 */
@Serializable
sealed interface PlaybackMediaRef {
    val id: Long

    @Serializable
    @SerialName("movie")
    data class Movie(override val id: Long) : PlaybackMediaRef

    @Serializable
    @SerialName("episode")
    data class Episode(override val id: Long) : PlaybackMediaRef
}

/** What the player calls this media in its spoken and error copy. */
val PlaybackMediaRef.noun: String
    get() = when (this) {
        is PlaybackMediaRef.Movie -> "movie"
        is PlaybackMediaRef.Episode -> "episode"
    }
