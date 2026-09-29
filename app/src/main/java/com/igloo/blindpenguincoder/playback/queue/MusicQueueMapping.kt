package com.igloo.blindpenguincoder.playback.queue

import com.igloo.blindpenguincoder.data.model.TrackListItem
import com.igloo.blindpenguincoder.playback.model.MusicPlayTrack
import com.igloo.blindpenguincoder.playback.model.millisToSeconds
import kotlin.random.Random

/** A library row as a queue entry: milliseconds to seconds, `Valid`-gated columns to nulls. */
fun TrackListItem.toMusicPlayTrack(): MusicPlayTrack = MusicPlayTrack(
    id = id,
    title = title,
    durationSec = millisToSeconds(duration),
    artistName = musicianName.orNullIfBlank(),
    albumTitle = albumTitle.orNullIfBlank(),
    coverUrl = albumCover.orNullIfBlank(),
)

/**
 * A finite queue's shuffle (docs/music-shuffle.md): Fisher-Yates over a copy of the known
 * membership, never the cached list itself, with duplicate ids dropped first so the queue
 * cannot hold one track twice.
 */
fun List<MusicPlayTrack>.shuffledQueue(random: Random = Random.Default): List<MusicPlayTrack> =
    distinctBy { it.id }.shuffled(random)
