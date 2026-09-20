package com.igloo.blindpenguincoder.feature.home

import androidx.compose.runtime.saveable.Saver
import com.igloo.blindpenguincoder.data.model.PlaybackMode
import com.igloo.blindpenguincoder.playback.model.MoviePlayRequest
import com.igloo.blindpenguincoder.playback.model.MusicPlayRequest
import com.igloo.blindpenguincoder.playback.model.MusicPlayTrack
import com.igloo.blindpenguincoder.playback.model.PlayableAudioTrack
import com.igloo.blindpenguincoder.playback.model.PlayableSubtitleTrack
import com.igloo.blindpenguincoder.playback.model.PlaybackChapter
import kotlinx.serialization.json.Json

/**
 * The shell's two player overlays ride activity recreation as flat `List<String>` slots.
 *
 * Both restores are wrapped: they parse numbers, enum names, and JSON out of a bundle written
 * by a possibly older build, and every one of those throws. A throw here lands inside saved
 * state restoration, which means a crash on relaunch — while the honest degradation is simply
 * to come back with no overlay, since the details screen underneath is restored anyway.
 */
private inline fun <T> restoreOrDrop(block: () -> T): T? = runCatching(block).getOrNull()

/**
 * What the movie player overlay is playing (the screen itself restarts the engine and re-seeks
 * to its saved position). Nullable fields ride as "" — no title is ever blank, so the encoding
 * is unambiguous.
 */
internal val MoviePlayRequestSaver: Saver<MoviePlayRequest?, List<String>> = Saver(
    save = { request ->
        if (request == null) {
            emptyList()
        } else {
            listOf(
                request.movieId.toString(),
                request.title,
                request.posterUrl.orEmpty(),
                request.mimeType,
                request.mode.name,
                request.audioTypeIndex?.toString().orEmpty(),
                request.subtitleTypeIndex?.toString().orEmpty(),
                // Lists have no natural slot in this flat encoding; JSON is one symmetric line.
                Json.encodeToString(request.audioTracks),
                Json.encodeToString(request.subtitleTracks),
                request.resumeAtSec?.toString().orEmpty(),
                request.durationSec?.toString().orEmpty(),
                Json.encodeToString(request.chapters),
            )
        }
    },
    restore = { saved ->
        if (saved.size < MOVIE_SLOTS) {
            null
        } else {
            restoreOrDrop {
                MoviePlayRequest(
                    movieId = saved[0].toLong(),
                    title = saved[1],
                    posterUrl = saved[2].ifEmpty { null },
                    mimeType = saved[3],
                    mode = PlaybackMode.valueOf(saved[4]),
                    audioTypeIndex = saved[5].toIntOrNull(),
                    subtitleTypeIndex = saved[6].toIntOrNull(),
                    audioTracks = Json.decodeFromString<List<PlayableAudioTrack>>(saved[7]),
                    subtitleTracks = Json.decodeFromString<List<PlayableSubtitleTrack>>(saved[8]),
                    resumeAtSec = saved[9].toDoubleOrNull(),
                    durationSec = saved[10].toDoubleOrNull(),
                    chapters = Json.decodeFromString<List<PlaybackChapter>>(saved[11]),
                )
            }
        }
    },
)

/**
 * What the music player overlay is playing, encoded like [MoviePlayRequestSaver]. Nullable
 * fields ride as "" — the album title is never blank (the mapping's "Untitled album"
 * fallback), so the encoding is unambiguous.
 */
internal val MusicPlayRequestSaver: Saver<MusicPlayRequest?, List<String>> = Saver(
    save = { request ->
        if (request == null) {
            emptyList()
        } else {
            listOf(
                request.albumId.toString(),
                request.albumTitle,
                request.artistName.orEmpty(),
                request.coverUrl.orEmpty(),
                // Lists have no natural slot in this flat encoding; JSON is one symmetric line.
                Json.encodeToString(request.tracks),
            )
        }
    },
    restore = { saved ->
        if (saved.size < MUSIC_SLOTS) {
            null
        } else {
            restoreOrDrop {
                MusicPlayRequest(
                    albumId = saved[0].toLong(),
                    albumTitle = saved[1],
                    artistName = saved[2].ifEmpty { null },
                    coverUrl = saved[3].ifEmpty { null },
                    tracks = Json.decodeFromString<List<MusicPlayTrack>>(saved[4]),
                )
            }
        }
    },
)

private const val MOVIE_SLOTS = 12
private const val MUSIC_SLOTS = 5
