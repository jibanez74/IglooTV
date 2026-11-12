package com.igloo.blindpenguincoder.playback.media3

import androidx.media3.common.C
import androidx.media3.common.Tracks
import com.igloo.blindpenguincoder.playback.model.PlayableAudioTrack
import com.igloo.blindpenguincoder.playback.model.PlayableSubtitleTrack
import com.igloo.blindpenguincoder.playback.model.TrackOption
import com.igloo.blindpenguincoder.playback.model.describeAudioTrack
import com.igloo.blindpenguincoder.playback.model.describeChannelLayout
import com.igloo.blindpenguincoder.playback.model.describeSubtitleTrack

/**
 * ExoPlayer's [Tracks] as menu rows, labelled with the same vocabulary as the pre-play dialog.
 * Option ids are "group:track" over the *global* group index, so a selection can be applied
 * without re-deriving type ordering. Pure over Media3's plain data classes — unit-testable.
 */

internal fun audioTrackOptions(tracks: Tracks): List<TrackOption> =
    typeGroups(tracks, C.TRACK_TYPE_AUDIO).mapIndexed { ordinal, (groupIndex, group) ->
        val format = group.getTrackFormat(0)
        val channels = format.channelCount.takeIf { it > 0 }
            ?.let { describeChannelLayout(null, it.toLong()) }
        TrackOption(
            id = groupOptionId(groupIndex),
            label = describeAudioTrack(format.language, channels, ordinal),
            selected = group.isTrackSelected(0),
        )
    }

internal fun subtitleTrackOptions(tracks: Tracks): List<TrackOption> =
    typeGroups(tracks, C.TRACK_TYPE_TEXT).mapIndexed { ordinal, (groupIndex, group) ->
        val format = group.getTrackFormat(0)
        TrackOption(
            id = groupOptionId(groupIndex),
            label = describeSubtitleTrack(
                languageCode = format.language,
                title = format.label,
                forced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
                isDefault = format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
                ordinal = ordinal,
            ),
            selected = group.isTrackSelected(0),
        )
    }

/**
 * An HLS session muxes exactly one audio track, so ExoPlayer's [Tracks] can't list the movie's
 * alternatives; the wire summaries can. Ids are `audio:<typeIndex>` — the ordinal a switched
 * session sends as `audio_track` — deliberately unlike the "group:track" shape so the engine
 * can tell a restart request from a selection override.
 */
internal fun hlsAudioTrackOptions(
    tracks: List<PlayableAudioTrack>,
    selectedTypeIndex: Int?,
): List<TrackOption> =
    wireTrackOptions(tracks.map { it.label }, selectedTypeIndex, HLS_AUDIO_OPTION_PREFIX)

/** The `audio:<typeIndex>` ordinal, or null when [optionId] is an ExoPlayer "group:track" id. */
internal fun parseHlsAudioOptionId(optionId: String): Int? =
    parsePrefixedOrdinal(optionId, HLS_AUDIO_OPTION_PREFIX)

/**
 * An HLS session streams only the chosen subtitle track, so ExoPlayer's [Tracks] cannot list
 * the others; the wire summaries can. Rows come out in wire-ordinal order — the same order and
 * words as the pre-play dialog — and every one is selectable: picking one streams that track.
 * Ids are `sub:<typeIndex>`, deliberately unlike "group:track" ids, so the engine can tell a
 * new stream apart from a selection override. The wire list is never filtered — positions are
 * backend `trackIndex` ordinals.
 */
internal fun hlsSubtitleTrackOptions(
    wireTracks: List<PlayableSubtitleTrack>,
    chosenTypeIndex: Int?,
): List<TrackOption> =
    wireTrackOptions(wireTracks.map { it.label }, chosenTypeIndex, HLS_SUBTITLE_OPTION_PREFIX)

/** The `sub:<typeIndex>` ordinal, or null when [optionId] is an ExoPlayer "group:track" id. */
internal fun parseHlsSubtitleOptionId(optionId: String): Int? =
    parsePrefixedOrdinal(optionId, HLS_SUBTITLE_OPTION_PREFIX)

private fun wireTrackOptions(labels: List<String>, selectedIndex: Int?, prefix: String) =
    labels.mapIndexed { index, label ->
        TrackOption("$prefix$index", label, selected = index == selectedIndex)
    }

private fun parsePrefixedOrdinal(optionId: String, prefix: String): Int? =
    optionId.removePrefix(prefix).takeIf { it != optionId }?.toIntOrNull()

private const val HLS_AUDIO_OPTION_PREFIX = "audio:"
private const val HLS_SUBTITLE_OPTION_PREFIX = "sub:"

/**
 * The option id of the streamed subtitle track: the text group the merged source's second
 * child carries. A merged source prefixes every format id with its child's index, and the
 * session's own media is child 0.
 */
internal fun subtitleStreamOptionId(tracks: Tracks): String? =
    typeGroups(tracks, C.TRACK_TYPE_TEXT)
        .firstOrNull { (_, group) -> group.getTrackFormat(0).id?.startsWith(SUBTITLE_STREAM_ID_PREFIX) == true }
        ?.let { (groupIndex, _) -> groupOptionId(groupIndex) }

/** The id prefix a merged source gives its second child's formats. */
internal const val SUBTITLE_STREAM_ID_PREFIX = "1:"

/**
 * The pre-play dialog picks the Nth ffprobe stream of a type (in `stream_index` order); the
 * demuxed container exposes the same streams as the Nth track group of that type, in container
 * order. Null when the file has fewer tracks than the index promises.
 */
internal fun trackOptionId(tracks: Tracks, trackType: Int, typeIndex: Int): String? =
    typeGroups(tracks, trackType).getOrNull(typeIndex)
        ?.let { (groupIndex, _) -> groupOptionId(groupIndex) }

/** The group and track a "group:track" option names; null when malformed or out of range. */
internal fun resolveTrackOption(tracks: Tracks, optionId: String): Pair<Tracks.Group, Int>? {
    val (groupIndex, trackIndex) = parseTrackOptionId(optionId) ?: return null
    val group = tracks.groups.getOrNull(groupIndex) ?: return null
    return (group to trackIndex).takeIf { trackIndex < group.length }
}

/**
 * The type-relative ordinal of a "group:track" option — which Nth audio/text group it is —
 * so an in-player choice made under direct play survives a switch into an HLS session.
 */
internal fun typeIndexForOptionId(tracks: Tracks, trackType: Int, optionId: String): Int? {
    val (groupIndex, _) = parseTrackOptionId(optionId) ?: return null
    return typeGroups(tracks, trackType)
        .indexOfFirst { (index, _) -> index == groupIndex }
        .takeIf { it >= 0 }
}

/** An option id back into (global group index, track index); null when malformed. */
internal fun parseTrackOptionId(id: String): Pair<Int, Int>? {
    val (group, track) = id.split(':').takeIf { it.size == 2 } ?: return null
    return (group.toIntOrNull() ?: return null) to (track.toIntOrNull() ?: return null)
}

/** The option id for a group's first track, the only one a menu row ever names. */
private fun groupOptionId(groupIndex: Int): String = "$groupIndex:0"

private fun typeGroups(tracks: Tracks, trackType: Int): List<Pair<Int, Tracks.Group>> =
    tracks.groups.withIndex()
        .filter { (_, group) -> group.type == trackType && group.length > 0 }
        .map { (index, group) -> index to group }
