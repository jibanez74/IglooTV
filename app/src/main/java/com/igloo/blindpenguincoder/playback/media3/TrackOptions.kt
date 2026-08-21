package com.igloo.blindpenguincoder.playback.media3

import androidx.media3.common.C
import androidx.media3.common.Tracks
import com.igloo.blindpenguincoder.playback.model.TrackOption
import com.igloo.blindpenguincoder.playback.model.describeChannelLayout
import com.igloo.blindpenguincoder.playback.model.languageDisplayName

/**
 * ExoPlayer's [Tracks] as menu rows, labelled with the same vocabulary as the pre-play dialog.
 * Option ids are "group:track" over the *global* group index, so a selection can be applied
 * without re-deriving type ordering. Pure over Media3's plain data classes — unit-testable.
 */

internal fun audioTrackOptions(tracks: Tracks): List<TrackOption> =
    typeGroups(tracks, C.TRACK_TYPE_AUDIO).mapIndexed { ordinal, (groupIndex, group) ->
        val format = group.getTrackFormat(0)
        val language = languageDisplayName(format.language) ?: "Track ${ordinal + 1}"
        val layout = format.channelCount.takeIf { it > 0 }
            ?.let { " · ${describeChannelLayout(null, it.toLong())}" }
            .orEmpty()
        TrackOption(
            id = "$groupIndex:0",
            label = "$language$layout",
            selected = group.isTrackSelected(0),
        )
    }

internal fun subtitleTrackOptions(tracks: Tracks): List<TrackOption> =
    typeGroups(tracks, C.TRACK_TYPE_TEXT).mapIndexed { ordinal, (groupIndex, group) ->
        val format = group.getTrackFormat(0)
        val language = languageDisplayName(format.language)
        val title = format.label?.takeIf { it.isNotBlank() && it != language }
        val parts = buildList {
            language?.let { add(it) }
            title?.let { add(it) }
            if (format.selectionFlags and C.SELECTION_FLAG_FORCED != 0) add("Forced")
            if (format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0) add("Default")
        }
        TrackOption(
            id = "$groupIndex:0",
            label = if (parts.isNotEmpty()) parts.joinToString(" · ") else "Track ${ordinal + 1}",
            selected = group.isTrackSelected(0),
        )
    }

/**
 * The pre-play dialog picks the Nth ffprobe stream of a type (in `stream_index` order); the
 * demuxed container exposes the same streams as the Nth track group of that type, in container
 * order. Null when the file has fewer tracks than the index promises.
 */
internal fun trackOptionId(tracks: Tracks, trackType: Int, typeIndex: Int): String? =
    typeGroups(tracks, trackType).getOrNull(typeIndex)?.let { (groupIndex, _) -> "$groupIndex:0" }

/** An option id back into (global group index, track index); null when malformed. */
internal fun parseTrackOptionId(id: String): Pair<Int, Int>? {
    val (group, track) = id.split(':').takeIf { it.size == 2 } ?: return null
    return (group.toIntOrNull() ?: return null) to (track.toIntOrNull() ?: return null)
}

private fun typeGroups(tracks: Tracks, trackType: Int): List<Pair<Int, Tracks.Group>> =
    tracks.groups.withIndex()
        .filter { (_, group) -> group.type == trackType && group.length > 0 }
        .map { (index, group) -> index to group }
