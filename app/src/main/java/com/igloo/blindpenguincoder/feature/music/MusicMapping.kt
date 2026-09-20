package com.igloo.blindpenguincoder.feature.music

import com.igloo.blindpenguincoder.data.model.SimpleAlbum
import com.igloo.blindpenguincoder.data.model.SimpleMusician
import com.igloo.blindpenguincoder.data.model.TrackListItem
import com.igloo.blindpenguincoder.feature.shared.TrackRowUi
import com.igloo.blindpenguincoder.feature.shared.letterBucket
import com.igloo.blindpenguincoder.feature.shared.spokenLetterHeader
import com.igloo.blindpenguincoder.feature.shared.trackSpokenInfo
import com.igloo.blindpenguincoder.feature.shared.trackSubtitle
import com.igloo.blindpenguincoder.playback.model.MusicPlayRequest
import com.igloo.blindpenguincoder.playback.model.MusicQueueSource
import com.igloo.blindpenguincoder.playback.queue.toMusicPlayTrack
import java.text.NumberFormat
import java.util.Locale

/**
 * Wire-to-render rules for the Music pane (docs/design-system.md section 11.5), pure so every
 * formatting and grouping decision is JVM-testable.
 */

/** One circular card on the Musicians tab. */
data class MusicianCardUi(
    val id: Long,
    val name: String,
    /** Verbatim: the scanner stores an absolute URL or nothing; there is no music image proxy. */
    val thumbUrl: String?,
    /** `"3 albums · 40 tracks"` */
    val countsLine: String,
    /** The card's one announcement: `"The Beatles. 3 albums, 40 tracks."` */
    val spoken: String,
)

/** One square card on the Albums tab. */
data class AlbumCardUi(
    val id: Long,
    val title: String,
    val musician: String?,
    val coverUrl: String?,
)

/** One item of the Tracks tab's flat list: a letter header the eye reads, or a row. */
sealed interface TracksEntry {
    data class Letter(val letter: String) : TracksEntry
    data class Track(val track: TrackRowUi) : TracksEntry
}

internal fun SimpleMusician.toCardUi(): MusicianCardUi {
    val albums = "$albumCount " + if (albumCount == 1L) "album" else "albums"
    val tracks = "$trackCount " + if (trackCount == 1L) "track" else "tracks"
    val cardName = name.ifBlank { "Unknown artist" }
    return MusicianCardUi(
        id = id,
        name = cardName,
        thumbUrl = thumb.orNullIfBlank(),
        countsLine = "$albums · $tracks",
        spoken = "$cardName. $albums, $tracks.",
    )
}

internal fun SimpleAlbum.toCardUi(): AlbumCardUi = AlbumCardUi(
    id = id,
    title = title.ifBlank { "Untitled album" },
    musician = musician.orNullIfBlank(),
    coverUrl = cover.orNullIfBlank(),
)

/**
 * The whole loaded track list as entries, a letter header before the first title of each
 * bucket and that title's row folding the header into its sentence (section 11.5.1's disc
 * rule at row scale). The server orders by bucket then title, so recomputing over the full
 * list after every append keeps the headers monotonic and the fold on exactly one row.
 */
internal fun tracksEntries(tracks: List<TrackListItem>): List<TracksEntry> {
    val entries = ArrayList<TracksEntry>(tracks.size + 27)
    var currentLetter: String? = null
    tracks.forEach { track ->
        val letter = letterBucket(track.title)
        val folded = letter != currentLetter
        if (folded) {
            entries += TracksEntry.Letter(letter)
            currentLetter = letter
        }
        entries += TracksEntry.Track(track.toRowUi(prefix = spokenLetterHeader(letter).takeIf { folded }))
    }
    return entries
}

private fun TrackListItem.toRowUi(prefix: String?): TrackRowUi {
    val play = toMusicPlayTrack()
    val subtitle = trackSubtitle(play.artistName, play.albumTitle)
    return TrackRowUi(
        id = id,
        title = title,
        subtitle = subtitle,
        indexText = null,
        durationText = formatTrackDuration(duration),
        durationSec = play.durationSec,
        albumId = albumId.orNull(),
        musicianId = musicianId.orNull(),
        spokenInfo = trackSpokenInfo(prefix, title, subtitle, play.durationSec),
    )
}

/** A row's Play: every track loaded so far, starting at the pressed one (web parity). */
internal fun trackListPlayRequest(loaded: List<TrackListItem>, trackId: Long): MusicPlayRequest? {
    val startIndex = loaded.indexOfFirst { it.id == trackId }
    if (startIndex < 0) return null
    return MusicPlayRequest(
        source = MusicQueueSource.TrackList,
        startIndex = startIndex,
        tracks = loaded.map { it.toMusicPlayTrack() },
    )
}

/** Play all: the loaded rows in order, growing through `GET /music/tracks` until [total]. */
internal fun playAllRequest(loaded: List<TrackListItem>, total: Long): MusicPlayRequest? {
    if (loaded.isEmpty()) return null
    return MusicPlayRequest(
        source = MusicQueueSource.LibraryInOrder(nextOffset = loaded.size.toLong(), total = total),
        startIndex = 0,
        tracks = loaded.map { it.toMusicPlayTrack() },
    )
}

/** Shuffle all: the server's first random batch, refilled with exclusions as it plays. */
internal fun shuffleAllRequest(batch: List<TrackListItem>): MusicPlayRequest? {
    if (batch.isEmpty()) return null
    return MusicPlayRequest(
        source = MusicQueueSource.LibraryShuffle,
        startIndex = 0,
        tracks = batch.map { it.toMusicPlayTrack() }.distinctBy { it.id },
    )
}

private val integerCountFormat: NumberFormat = NumberFormat.getIntegerInstance(Locale.US)

internal fun formatCount(count: Long): String = integerCountFormat.format(count)
