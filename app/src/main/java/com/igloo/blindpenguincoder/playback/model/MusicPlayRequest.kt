package com.igloo.blindpenguincoder.playback.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Everything the music player needs to play a queue, assembled by whichever surface launched
 * it. [tracks] is the queue in play order and [startIndex] the entry the press named; an
 * endless [source] grows the queue while it plays (see `MusicQueueController`).
 */
data class MusicPlayRequest(
    val source: MusicQueueSource,
    val startIndex: Int,
    val tracks: List<MusicPlayTrack>,
)

/**
 * One queue entry. The duration is the wire's word, kept because ExoPlayer reports
 * `TIME_UNSET` at an item transition until the new track's container is parsed — the seek bar
 * bridges that gap with this value. The display metadata rides per track because a library
 * queue crosses albums; an album queue repeats the album's on every entry. Serializable so the
 * whole queue rides one saved-state slot as JSON.
 */
@Serializable
data class MusicPlayTrack(
    val id: Long,
    val title: String,
    val durationSec: Double,
    val artistName: String? = null,
    val albumTitle: String? = null,
    /** Verbatim absolute URL or null (the album page's rule); never proxied. */
    val coverUrl: String? = null,
)

/**
 * Where a queue came from. It decides the player's title, the media session id, and whether
 * the queue refills as it plays: the three library sources are the endless ones, the album
 * and musician queues are finite and fully known at launch.
 */
@Serializable
sealed interface MusicQueueSource {
    val title: String

    /** The media session id's middle: `music-<sessionKey>-<instance>`. */
    val sessionKey: String

    @Serializable
    @SerialName("album")
    data class Album(val albumId: Long, override val title: String) : MusicQueueSource {
        override val sessionKey: String get() = "album-$albumId"
    }

    @Serializable
    @SerialName("musician")
    data class Musician(val musicianId: Long, override val title: String) : MusicQueueSource {
        override val sessionKey: String get() = "musician-$musicianId"
    }

    /** A pressed row on the Tracks tab: every track loaded so far, finite. */
    @Serializable
    @SerialName("tracks")
    data object TrackList : MusicQueueSource {
        override val title: String get() = "Tracks"
        override val sessionKey: String get() = "tracks"
    }

    /** Play all: pages of `GET /music/tracks` appended in order until [total]. */
    @Serializable
    @SerialName("library")
    data class LibraryInOrder(val nextOffset: Long, val total: Long) : MusicQueueSource {
        override val title: String get() = "All tracks"
        override val sessionKey: String get() = "library"
    }

    /** Shuffle all: `GET /music/tracks/shuffle` refilled with what the queue already holds. */
    @Serializable
    @SerialName("shuffle")
    data object LibraryShuffle : MusicQueueSource {
        override val title: String get() = "Shuffle"
        override val sessionKey: String get() = "shuffle"
    }
}

val MusicQueueSource.isEndless: Boolean
    get() = this is MusicQueueSource.LibraryInOrder || this is MusicQueueSource.LibraryShuffle

/**
 * The most tracks a queue may hold. An endless source stops refilling here — about 33 hours
 * of music — and a queue past it is not saved across recreation, which keeps the saved-state
 * slot bounded (docs/design-system.md section 11.8.2).
 */
const val MAX_QUEUE_TRACKS = 500
